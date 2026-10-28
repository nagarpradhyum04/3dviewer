package ai.scan3d.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Failure talking to the reconstruction server, with a message suitable for the user. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No connection, timeout, DNS failure… Retrying later may help. */
    class Network(cause: Throwable) : ApiException(
        "Cannot reach the reconstruction server. Check your internet connection and the server address in Settings.",
        cause,
    )

    class Unauthorized : ApiException("The server rejected the API key. Check it in Settings.")
    class NotFound(what: String) : ApiException("$what was not found on the server (it may have expired).")

    /** 4xx with an explanation from the server (validation errors, limits…). */
    class Rejected(val code: Int, detail: String) : ApiException(detail)

    /** 5xx: problem on the server side. */
    class Server(val code: Int, detail: String) :
        ApiException("The reconstruction server had a problem ($code): $detail. Try again later.")

    class BadConfig(detail: String) : ApiException(detail)
}

/**
 * Thin, blocking-IO-on-Dispatchers.IO client for the Scan3D reconstruction API.
 * All methods throw [ApiException].
 */
class ReconstructionClient(
    baseUrl: String,
    private val apiKey: String? = null,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val api: HttpUrl = parseBase(baseUrl)

    suspend fun health(): Health = get("health")

    suspend fun createJob(expectedImages: Int, options: JobOptions): CreateJobResponse =
        post("jobs", ApiJson.encodeToString(CreateJobRequest(expectedImages, options)))

    /** Idempotent: re-uploading the same name replaces the file, so interrupted uploads can resume. */
    suspend fun uploadImage(jobId: String, name: String, file: File): UploadResponse {
        val body = file.asRequestBody(JPEG)
        return execute(builder("jobs", jobId, "images", name).put(body).build())
    }

    suspend fun start(jobId: String): JobStatus = post("jobs/$jobId/start", "{}")
    suspend fun status(jobId: String): JobStatus = get("jobs/$jobId")
    suspend fun cancel(jobId: String): JobStatus = post("jobs/$jobId/cancel", "{}")

    suspend fun calibrate(jobId: String, request: CalibrateRequest): JobStatus =
        post("jobs/$jobId/calibrate", ApiJson.encodeToString(request))

    suspend fun delete(jobId: String) {
        executeRaw(builder("jobs", jobId).delete().build()).close()
    }

    /** Streams an output file to [dest] (written to a temp file first, then renamed). */
    suspend fun download(jobId: String, name: String, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val response = executeRaw(builder("jobs", jobId, "files", name).get().build())
        withContext(Dispatchers.IO) {
            response.use { r ->
                val body = r.body ?: throw ApiException.Server(r.code, "empty response")
                val total = body.contentLength()
                dest.parentFile?.mkdirs()
                val tmp = File(dest.parentFile, dest.name + ".part")
                try {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                } catch (e: IOException) {
                    tmp.delete()
                    throw ApiException.Network(e)
                }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    throw IOException("Could not write ${dest.name}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ plumbing
    private suspend inline fun <reified T> get(path: String): T =
        execute(builder(*path.split('/').toTypedArray()).get().build())

    private suspend inline fun <reified T> post(path: String, json: String): T =
        execute(builder(*path.split('/').toTypedArray()).post(json.toRequestBody(JSON)).build())

    private suspend inline fun <reified T> execute(request: Request): T {
        val text = executeRaw(request).use { it.body?.string().orEmpty() }
        return try {
            ApiJson.decodeFromString<T>(text)
        } catch (e: Exception) {
            throw ApiException.Server(200, "unexpected response format")
        }
    }

    private suspend fun executeRaw(request: Request): Response = withContext(Dispatchers.IO) {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        if (response.isSuccessful) return@withContext response
        val detail = response.use { r -> detailOf(r.body?.string()) ?: r.message.ifBlank { "HTTP ${r.code}" } }
        throw when (response.code) {
            401, 403 -> ApiException.Unauthorized()
            404 -> ApiException.NotFound(if (request.url.pathSegments.contains("files")) "The file" else "The job")
            in 400..499 -> ApiException.Rejected(response.code, detail)
            else -> ApiException.Server(response.code, detail)
        }
    }

    private fun builder(vararg segments: String): Request.Builder {
        val url = api.newBuilder().apply { segments.filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }.build()
        return Request.Builder().url(url).apply { if (!apiKey.isNullOrBlank()) header("X-API-Key", apiKey) }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun parseBase(baseUrl: String): HttpUrl {
            val trimmed = baseUrl.trim().trimEnd('/')
            val url = trimmed.toHttpUrlOrNull()
                ?: throw ApiException.BadConfig("Server address \"$baseUrl\" is not a valid http(s) URL.")
            return url.newBuilder().addPathSegment("api").addPathSegment("v1").build()
        }

        internal fun detailOf(body: String?): String? = try {
            body?.let {
                val el = ApiJson.parseToJsonElement(it)
                val d = (el as? kotlinx.serialization.json.JsonObject)?.get("detail")
                when (d) {
                    is kotlinx.serialization.json.JsonPrimitive -> d.content
                    null -> null
                    else -> d.toString()   // pydantic validation error list
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

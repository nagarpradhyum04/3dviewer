package ai.scan3d.core.processing

import ai.scan3d.core.api.ApiException
import ai.scan3d.core.api.JobOptions
import ai.scan3d.core.api.JobStatus
import ai.scan3d.core.api.ReconstructionClient
import ai.scan3d.core.storage.ScanRecord
import ai.scan3d.core.storage.ScanState
import ai.scan3d.core.storage.ScanStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Drives one scan through upload -> server reconstruction -> model download.
 *
 * Every step is persisted in the [ScanStore], so if the app is closed or the network drops the
 * process can be resumed: photos already uploaded are skipped, and a job that is already running
 * on the server is simply polled again. Nothing is ever marked complete until the server reports
 * success and the model file has been downloaded.
 */
class ScanProcessor(
    private val store: ScanStore,
    private val clientFor: (serverUrl: String) -> ReconstructionClient,
    private val pollIntervalMs: Long = 2000,
    private val maxNetworkFailures: Int = 30,
) {
    suspend fun process(scanId: String, serverUrl: String) {
        try {
            run(scanId, serverUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            fail(scanId, e.message ?: "Server error", remedyFor(e))
        } catch (e: java.io.IOException) {
            fail(scanId, "Could not read or write files: ${e.message}",
                "Free up storage space on the phone and try again.")
        }
    }

    private suspend fun run(scanId: String, serverUrl: String, allowRecreate: Boolean = true) {
        var scan = store.require(scanId)
        require(scan.photos.isNotEmpty()) { "scan has no photos" }
        val serverRejected = scan.state == ScanState.FAILED && scan.result?.status == "failed"
        if (scan.state == ScanState.CANCELLED || serverRejected) {
            // the server job is finished; a retry needs a fresh job
            scan = resetJob(scanId)
        }
        // an existing job lives on the server it was created on
        val client = clientFor(if (scan.jobId != null) scan.serverUrl ?: serverUrl else serverUrl)

        // 1. create job
        if (scan.jobId == null) {
            scan = store.update(scanId) { it.copy(state = ScanState.UPLOADING, statusMessage = "Contacting server",
                serverUrl = serverUrl, error = null, remedy = null) }
            val health = client.health()
            if (scan.photos.size < health.minImages) {
                fail(scanId, "This scan has ${scan.photos.size} photos; the server needs at least ${health.minImages}.",
                    "Go back and capture more overlapping photos around the object.")
                return
            }
            val job = client.createJob(scan.photos.size, JobOptions(scan.markerSizeMm, 0, scan.name))
            scan = store.update(scanId) { it.copy(jobId = job.id, uploaded = emptySet()) }
        }
        val jobId = scan.jobId!!

        // 2. upload (resumable)
        var status: JobStatus = try {
            withRetry(scanId) { client.status(jobId) }
        } catch (e: ApiException.NotFound) {
            if (!allowRecreate) throw e
            resetJob(scanId)                     // job expired on the server: start over once
            return run(scanId, serverUrl, allowRecreate = false)
        }
        if (status.state == "uploading") {
            val pending = scan.photos.map { it.name }.filter { it !in scan.uploaded }
            var done = scan.uploaded.size
            for (name in pending) {
                store.update(scanId) { it.copy(state = ScanState.UPLOADING,
                    statusMessage = "Uploading photo ${done + 1} of ${scan.photos.size}") }
                val file = store.photoFile(scanId, name)
                if (!file.isFile) throw java.io.IOException("photo $name is missing")
                withRetry(scanId) { client.uploadImage(jobId, name, file) }
                done++
                store.update(scanId) { it.copy(uploaded = it.uploaded + name) }
            }
            status = withRetry(scanId) { client.start(jobId) }
        }

        // 3. poll
        store.update(scanId) { it.copy(state = ScanState.PROCESSING, statusMessage = "Waiting for the server") }
        while (!status.isTerminal) {
            delay(pollIntervalMs)
            status = withRetry(scanId) { client.status(jobId) }
            val msg = when (status.state) {
                "queued" -> "Queued on the server (position ${status.queuePosition ?: "?"})"
                else -> status.progress?.stageLabel ?: "Processing"
            }
            store.update(scanId) { it.copy(state = ScanState.PROCESSING, progress = status.progress, statusMessage = msg) }
        }

        // 4. outcome
        val result = status.result
        when (status.state) {
            "succeeded" -> {
                if (result == null || result.files.none { it.name == "model.glb" }) {
                    store.update(scanId) { it.copy(state = ScanState.COMPLETED, result = result, progress = status.progress,
                        statusMessage = "Reconstruction finished without a viewable mesh") }
                    return
                }
                store.update(scanId) { it.copy(state = ScanState.DOWNLOADING, result = result, progress = status.progress,
                    statusMessage = "Downloading 3D model") }
                withRetry(scanId) {
                    client.download(jobId, "model.glb", store.modelFile(scanId, "model.glb")) { got, total ->
                        if (total > 0) store.update(scanId) {
                            it.copy(statusMessage = "Downloading 3D model (${got * 100 / total}%)")
                        }
                    }
                }
                store.update(scanId) { it.copy(state = ScanState.COMPLETED, downloaded = it.downloaded + "model.glb",
                    statusMessage = "Model ready") }
            }
            "cancelled" -> store.update(scanId) { it.copy(state = ScanState.CANCELLED, statusMessage = "Cancelled") }
            else -> fail(scanId, result?.error ?: "Reconstruction failed.",
                result?.remedy ?: "Check the photo tips and try again.", status)
        }
    }

    /** Downloads another output file (OBJ, STL…) on demand, e.g. for export. */
    suspend fun ensureFile(scanId: String, name: String, serverUrl: String): java.io.File {
        val scan = store.require(scanId)
        val dest = store.modelFile(scanId, name)
        if (name in scan.downloaded && dest.isFile) return dest
        val jobId = scan.jobId ?: throw ApiException.NotFound("The job")
        clientFor(scan.serverUrl ?: serverUrl).download(jobId, name, dest)
        store.update(scanId) { it.copy(downloaded = it.downloaded + name) }
        return dest
    }

    suspend fun cancel(scanId: String, serverUrl: String) {
        val scan = store.require(scanId)
        scan.jobId?.let { id ->
            try {
                clientFor(scan.serverUrl ?: serverUrl).cancel(id)
            } catch (e: ApiException) {
                // still mark it locally; the server job expires on its own
            }
        }
        store.update(scanId) { it.copy(state = ScanState.CANCELLED, statusMessage = "Cancelled") }
    }

    private fun resetJob(scanId: String) = store.update(scanId) {
        it.copy(jobId = null, uploaded = emptySet(), result = null, error = null, remedy = null, progress = null)
    }

    private suspend fun <T> withRetry(scanId: String, block: suspend () -> T): T {
        var failures = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException.Network) {
                failures++
                if (failures >= maxNetworkFailures) throw e
                store.update(scanId) { it.copy(statusMessage = "Connection lost – retrying (attempt ${failures + 1})") }
                delay(minOf(30_000L, 1000L shl minOf(failures, 5)))
            } catch (e: ApiException.Server) {
                failures++
                if (failures >= 3) throw e
                delay(2000L * failures)
            }
        }
    }

    private fun fail(scanId: String, error: String, remedy: String, status: JobStatus? = null) {
        store.update(scanId) {
            it.copy(state = ScanState.FAILED, error = error, remedy = remedy, result = status?.result ?: it.result,
                progress = status?.progress ?: it.progress, statusMessage = "Failed")
        }
    }

    private fun remedyFor(e: ApiException): String = when (e) {
        is ApiException.Network -> "Your photos are saved. Reconnect and tap Retry; finished uploads are not repeated."
        is ApiException.Unauthorized -> "Enter the correct API key in Settings › Reconstruction server."
        is ApiException.NotFound -> "The server no longer has this job. Tap Retry to upload the photos again."
        is ApiException.BadConfig -> "Fix the server address in Settings."
        else -> "Tap Retry. If it keeps failing, contact the server operator."
    }
}

fun ScanRecord.hasModel(): Boolean = state == ScanState.COMPLETED && viewerFile != null

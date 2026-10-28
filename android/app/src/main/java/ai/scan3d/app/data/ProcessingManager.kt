package ai.scan3d.app.data

import ai.scan3d.core.api.ApiException
import ai.scan3d.core.api.CalibrateRequest
import ai.scan3d.core.api.ReconstructionClient
import ai.scan3d.core.processing.ScanProcessor
import ai.scan3d.core.storage.ScanState
import ai.scan3d.core.storage.ScanStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs scan processing in an application-wide scope so it continues when the user leaves the
 * processing screen. If the app process is killed, [resumeInterrupted] picks active scans up again
 * on the next launch (uploads resume where they stopped; server jobs keep running meanwhile).
 */
class ProcessingManager(private val store: ScanStore, private val settings: AppSettings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    fun client(url: String = settings.current.serverUrl) = ReconstructionClient(url, settings.current.apiKey)

    private val processor = ScanProcessor(store, { url -> client(url) })

    fun isRunning(scanId: String) = jobs[scanId]?.isActive == true

    fun start(scanId: String) {
        if (isRunning(scanId)) return
        jobs[scanId] = scope.launch {
            try {
                processor.process(scanId, settings.current.serverUrl)
                val scan = store.get(scanId)
                if (scan?.state == ScanState.COMPLETED && settings.current.deleteServerCopy) {
                    // the user asked not to keep data on the server once the model is on the phone
                    scan.jobId?.let { runCatching { client(scan.serverUrl ?: settings.current.serverUrl).delete(it) } }
                    store.update(scanId) { it.copy(serverCopyDeleted = true) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processing $scanId failed", e)
                store.update(scanId) {
                    it.copy(state = ScanState.FAILED, error = e.message ?: e.javaClass.simpleName,
                        remedy = "Tap Retry. Your photos are kept on the phone.")
                }
            } finally {
                jobs.remove(scanId)
            }
        }
    }

    fun cancel(scanId: String) {
        val running = jobs.remove(scanId)
        scope.launch {
            running?.cancelAndJoin()      // make sure no late progress update overwrites "cancelled"
            processor.cancel(scanId, settings.current.serverUrl)
        }
    }

    fun resumeInterrupted() {
        store.scans.value.filter { it.state.isActive }.forEach { start(it.id) }
    }

    /** Downloads an output file if needed. Throws [ApiException] (e.g. after the server copy was deleted). */
    suspend fun ensureFile(scanId: String, name: String): File =
        processor.ensureFile(scanId, name, settings.current.serverUrl)

    /**
     * Applies a user-measured reference distance (metres) between two picked points (model units),
     * then re-downloads the rescaled viewer model. Other formats are re-downloaded on demand.
     */
    suspend fun calibrate(scanId: String, a: List<Double>, b: List<Double>, metres: Double) {
        val scan = store.require(scanId)
        val jobId = scan.jobId ?: throw ApiException.NotFound("The job")
        val c = client(scan.serverUrl ?: settings.current.serverUrl)
        val status = c.calibrate(jobId, CalibrateRequest(a, b, metres))
        c.download(jobId, "model.glb", store.modelFile(scanId, "model.glb"))
        store.modelDir(scanId).listFiles()?.filter { it.name != "model.glb" }?.forEach { it.delete() }
        store.thumbnailFile(scanId).delete()
        store.update(scanId) { it.copy(result = status.result ?: it.result, downloaded = setOf("model.glb"), thumbnail = null) }
    }

    fun deleteScan(scanId: String, alsoOnServer: Boolean) {
        cancelLocalOnly(scanId)
        val scan = store.get(scanId)
        store.delete(scanId)
        if (alsoOnServer && scan?.jobId != null && !scan.serverCopyDeleted) {
            scope.launch { runCatching { client(scan.serverUrl ?: settings.current.serverUrl).delete(scan.jobId!!) } }
        }
    }

    private fun cancelLocalOnly(scanId: String) {
        jobs.remove(scanId)?.cancel()
    }

    companion object {
        private const val TAG = "Scan3D"
    }
}

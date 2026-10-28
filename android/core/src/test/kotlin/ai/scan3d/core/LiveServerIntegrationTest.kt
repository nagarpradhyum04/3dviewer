package ai.scan3d.core

import ai.scan3d.core.api.ReconstructionClient
import ai.scan3d.core.processing.ScanProcessor
import ai.scan3d.core.storage.PhotoRecord
import ai.scan3d.core.storage.ScanState
import ai.scan3d.core.storage.ScanStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the app's real upload/poll/download code against a running backend.
 *
 *     SCAN3D_IT_SERVER=http://127.0.0.1:8000 SCAN3D_IT_IMAGES=/path/to/images ./gradlew test
 *
 * Skipped unless both variables are set.
 */
class LiveServerIntegrationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fullScanAgainstRealServer() = runBlocking {
        val server = System.getenv("SCAN3D_IT_SERVER")
        val images = System.getenv("SCAN3D_IT_IMAGES")?.let(::File)
        assumeTrue(server != null && images != null && images.isDirectory)
        val store = ScanStore(tmp.root)
        val scan = store.create("IT box", 50.0)
        images!!.listFiles { f -> f.extension.lowercase() == "jpg" }!!.sorted().forEach { f ->
            f.copyTo(store.photoFile(scan.id, f.name))
            store.addPhoto(scan.id, PhotoRecord(f.name, f.lastModified()))
        }
        val stages = mutableSetOf<String>()
        val watcher = Thread {
            while (!Thread.currentThread().isInterrupted) {
                store.get(scan.id)?.progress?.stage?.let { stages += it }
                try { Thread.sleep(200) } catch (e: InterruptedException) { break }
            }
        }.apply { start() }
        ScanProcessor(store, { ReconstructionClient(it) }, pollIntervalMs = 1000).process(scan.id, server!!)
        watcher.interrupt()
        val done = store.require(scan.id)
        println("state=${done.state} error=${done.error} stages=$stages dims=${done.result?.dimensions}")
        assertEquals(ScanState.COMPLETED, done.state)
        assertTrue(store.modelFile(scan.id, "model.glb").length() > 10_000)
        assertTrue(done.result!!.calibration!!.isCalibrated)
        assertTrue("saw progress stages", stages.size >= 3)
    }
}

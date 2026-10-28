package ai.scan3d.core

import ai.scan3d.core.api.ReconstructionClient
import ai.scan3d.core.processing.ScanProcessor
import ai.scan3d.core.storage.PhotoRecord
import ai.scan3d.core.storage.ScanState
import ai.scan3d.core.storage.ScanStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real HTTP client against a scripted fake of the backend API. */
class ScanProcessorTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val uploads = mutableListOf<String>()
    private val polls = AtomicInteger()
    private var failFirstUploadOf: String? = null
    private var outcome = "succeeded"
    private var state = "uploading"

    private val result = """{"status":"succeeded","files":[{"name":"model.glb","format":"glb","bytes":4}],
        "calibration":{"status":"calibrated","method":"aruco_marker","units":"m","scale_factor":0.09},
        "dimensions":{"length":0.12,"width":0.08,"height":0.06,"units":"m","kind":"estimated"}}"""

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!.removePrefix("/api/v1/")
                return when {
                    p == "health" -> json("""{"status":"ok","version":"t","min_images":3}""")
                    p == "jobs" -> json("""{"id":"abc","state":"uploading"}""", 201)
                    p.startsWith("jobs/abc/images/") -> {
                        val name = p.substringAfterLast('/')
                        if (name == failFirstUploadOf) {
                            failFirstUploadOf = null
                            MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
                        } else {
                            uploads += name
                            json("""{"name":"$name","bytes":3,"received":${uploads.size}}""")
                        }
                    }
                    p == "jobs/abc/start" -> { state = "queued"; json("""{"id":"abc","state":"queued"}""") }
                    p == "jobs/abc" -> {
                        if (state != "uploading" && polls.incrementAndGet() >= 3) state = outcome
                        val res = when (state) {
                            "succeeded" -> ""","result":$result"""
                            "failed" -> ""","result":{"status":"failed","error":"Could not determine camera positions","remedy":"More overlap"}"""
                            else -> ""
                        }
                        json("""{"id":"abc","state":"$state","progress":{"stage":"sfm","stage_label":"Estimating camera positions","overall_fraction":0.3}$res}""")
                    }
                    p == "jobs/abc/files/model.glb" -> MockResponse().setBody("glTF")
                    else -> MockResponse().setResponseCode(404).setBody("""{"detail":"Unknown job"}""")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun newScan(store: ScanStore, photos: Int): String {
        val s = store.create("Box", 50.0)
        repeat(photos) {
            val n = store.nextPhotoName(s.id)
            store.photoFile(s.id, n).writeBytes(byteArrayOf(1, 2, 3))
            store.addPhoto(s.id, PhotoRecord(n, 0))
        }
        return s.id
    }

    private fun processor(store: ScanStore) =
        ScanProcessor(store, { ReconstructionClient(it) }, pollIntervalMs = 1, maxNetworkFailures = 3)

    @Test fun uploadsProcessesAndDownloads() = runBlocking {
        val store = ScanStore(tmp.root)
        val id = newScan(store, 4)
        failFirstUploadOf = "IMG_0002.jpg"     // network drop mid-upload is retried
        processor(store).process(id, server.url("/").toString())
        val scan = store.require(id)
        assertEquals(ScanState.COMPLETED, scan.state)
        assertEquals(4, uploads.toSet().size)
        assertEquals("glTF", store.modelFile(id, "model.glb").readText())
        assertEquals(0.12, scan.result!!.dimensions!!.length, 1e-9)
        assertTrue(scan.result!!.calibration!!.isCalibrated)
    }

    @Test fun serverFailureIsReportedWithRemedy() = runBlocking {
        outcome = "failed"
        val store = ScanStore(tmp.root)
        val id = newScan(store, 4)
        processor(store).process(id, server.url("/").toString())
        val scan = store.require(id)
        assertEquals(ScanState.FAILED, scan.state)
        assertEquals("Could not determine camera positions", scan.error)
        assertEquals("More overlap", scan.remedy)
        assertTrue(scan.viewerFile == null)
    }

    @Test fun tooFewPhotosRejectedBeforeUpload() = runBlocking {
        val store = ScanStore(tmp.root)
        val id = newScan(store, 2)
        processor(store).process(id, server.url("/").toString())
        assertEquals(ScanState.FAILED, store.require(id).state)
        assertTrue(uploads.isEmpty())
    }

    @Test fun resumesWithoutReuploading() = runBlocking {
        val store = ScanStore(tmp.root)
        val id = newScan(store, 4)
        store.update(id) { it.copy(jobId = "abc", serverUrl = server.url("/").toString(),
            uploaded = setOf("IMG_0001.jpg", "IMG_0002.jpg"), state = ScanState.UPLOADING) }
        processor(store).process(id, server.url("/").toString())
        assertEquals(listOf("IMG_0003.jpg", "IMG_0004.jpg"), uploads)
        assertEquals(ScanState.COMPLETED, store.require(id).state)
    }

    @Test fun unreachableServerFailsGracefully() = runBlocking {
        val store = ScanStore(tmp.root)
        val id = newScan(store, 4)
        processor(store).process(id, "http://127.0.0.1:1")
        val scan = store.require(id)
        assertEquals(ScanState.FAILED, scan.state)
        assertTrue(scan.error!!.contains("Cannot reach"))
    }
}

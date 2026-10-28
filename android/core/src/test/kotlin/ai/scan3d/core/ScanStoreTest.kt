package ai.scan3d.core

import ai.scan3d.core.storage.PhotoRecord
import ai.scan3d.core.storage.ScanStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScanStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun lifecycle() {
        var t = 1000L
        val store = ScanStore(tmp.root) { t++ }
        val a = store.create("Mug", 50.0)
        val b = store.create("Shoe", null)
        assertEquals(listOf(b.id, a.id), store.scans.value.map { it.id })
        val n1 = store.nextPhotoName(a.id)
        store.photoFile(a.id, n1).writeText("x")
        store.addPhoto(a.id, PhotoRecord(n1, 1))
        assertEquals("IMG_0002.jpg", store.nextPhotoName(a.id))
        store.rename(a.id, "  Coffee mug ")
        assertEquals("Coffee mug", store.require(a.id).name)
        store.removePhoto(a.id, n1)
        assertTrue(store.require(a.id).photos.isEmpty())
        store.delete(a.id)
        assertNull(store.get(a.id))
        assertEquals(1, store.scans.value.size)
    }

    @Test fun corruptRecordIsSkipped() {
        val store = ScanStore(tmp.root)
        val a = store.create("A", null)
        File(store.dir(a.id), "scan.json").writeText("{broken")
        store.refresh()
        assertTrue(store.scans.value.isEmpty())
    }
}

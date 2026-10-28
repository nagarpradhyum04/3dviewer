package ai.scan3d.core.storage

import ai.scan3d.core.api.ApiJson
import ai.scan3d.core.api.JobResult
import ai.scan3d.core.api.Progress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

@Serializable
enum class ScanState {
    CAPTURING,      // taking photos
    UPLOADING,      // sending photos to the server
    PROCESSING,     // server is reconstructing
    DOWNLOADING,    // fetching the model
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean get() = this == UPLOADING || this == PROCESSING || this == DOWNLOADING
}

@Serializable
data class PhotoRecord(
    val name: String,
    val takenAt: Long,
    val azimuthDeg: Float? = null,
    val pitchDeg: Float? = null,
    val sharpness: Double? = null,
    val brightness: Double? = null,
    val issues: List<String> = emptyList(),
)

@Serializable
data class ScanRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: ScanState = ScanState.CAPTURING,
    val markerSizeMm: Double? = null,
    val photos: List<PhotoRecord> = emptyList(),
    val serverUrl: String? = null,
    val jobId: String? = null,
    val uploaded: Set<String> = emptySet(),
    val progress: Progress? = null,
    val statusMessage: String? = null,
    val result: JobResult? = null,
    val downloaded: Set<String> = emptySet(),
    val error: String? = null,
    val remedy: String? = null,
    val thumbnail: String? = null,
    val serverCopyDeleted: Boolean = false,
) {
    val viewerFile: String? get() = if ("model.glb" in downloaded) "model.glb" else null
}

/**
 * File-based scan library. Layout per scan:
 *
 *     <root>/<id>/scan.json      metadata (this record)
 *     <root>/<id>/photos/        original JPEGs exactly as captured (never modified)
 *     <root>/<id>/model/         files downloaded from the reconstruction server
 *     <root>/<id>/thumb.jpg      preview (rendered model snapshot, or first photo)
 */
class ScanStore(val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val _scans = MutableStateFlow<List<ScanRecord>>(emptyList())
    val scans: StateFlow<List<ScanRecord>> = _scans.asStateFlow()

    init {
        root.mkdirs()
        refresh()
    }

    fun dir(id: String): File {
        require(ID.matches(id)) { "bad scan id" }
        return File(root, id)
    }

    fun photosDir(id: String) = File(dir(id), "photos")
    fun modelDir(id: String) = File(dir(id), "model")
    fun photoFile(id: String, name: String) = File(photosDir(id), name)
    fun modelFile(id: String, name: String) = File(modelDir(id), name)
    fun thumbnailFile(id: String) = File(dir(id), "thumb.jpg")

    fun create(name: String, markerSizeMm: Double?): ScanRecord {
        val now = clock()
        val rec = ScanRecord(UUID.randomUUID().toString(), name.ifBlank { "Scan" }.take(80), now, now,
            markerSizeMm = markerSizeMm)
        photosDir(rec.id).mkdirs()
        modelDir(rec.id).mkdirs()
        save(rec)
        return rec
    }

    fun get(id: String): ScanRecord? = synchronized(lock) { read(File(dir(id), "scan.json")) }

    fun require(id: String): ScanRecord = get(id) ?: throw NoSuchElementException("Scan $id not found")

    fun save(record: ScanRecord): ScanRecord = synchronized(lock) {
        val rec = record.copy(updatedAt = clock())
        val d = dir(rec.id).apply { mkdirs() }
        val tmp = File(d, "scan.json.tmp")
        tmp.writeText(ApiJson.encodeToString(rec))
        if (!tmp.renameTo(File(d, "scan.json"))) {
            File(d, "scan.json").delete()
            check(tmp.renameTo(File(d, "scan.json"))) { "could not save scan" }
        }
        refreshLocked()
        rec
    }

    /** Read-modify-write under the store lock. */
    fun update(id: String, change: (ScanRecord) -> ScanRecord): ScanRecord = synchronized(lock) {
        save(change(require(id)))
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }.take(80)) }

    fun delete(id: String) = synchronized(lock) {
        dir(id).deleteRecursively()
        refreshLocked()
    }

    /** Next free photo file name, e.g. IMG_0007.jpg. */
    fun nextPhotoName(id: String): String {
        val used = require(id).photos.map { it.name }.toSet() + (photosDir(id).list()?.toSet() ?: emptySet())
        var i = used.size + 1
        while (String.format("IMG_%04d.jpg", i) in used) i++
        return String.format("IMG_%04d.jpg", i)
    }

    fun addPhoto(id: String, photo: PhotoRecord) = update(id) { r ->
        r.copy(photos = r.photos.filterNot { it.name == photo.name } + photo)
    }

    fun removePhoto(id: String, name: String) = update(id) { r ->
        photoFile(id, name).delete()
        r.copy(photos = r.photos.filterNot { it.name == name }, uploaded = r.uploaded - name)
    }

    fun sizeOf(id: String): Long = dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun totalSize(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun refresh() = synchronized(lock) { refreshLocked() }

    private fun refreshLocked() {
        _scans.value = (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && ID.matches(it.name) }
            .mapNotNull { read(File(it, "scan.json")) }
            .sortedByDescending { it.updatedAt }
    }

    private fun read(f: File): ScanRecord? = try {
        if (f.isFile) ApiJson.decodeFromString<ScanRecord>(f.readText()) else null
    } catch (e: Exception) {
        null   // a corrupt record must not break the whole library
    }

    companion object {
        private val ID = Regex("[0-9a-f-]{36}")
    }
}

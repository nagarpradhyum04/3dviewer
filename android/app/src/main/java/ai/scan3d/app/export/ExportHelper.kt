package ai.scan3d.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ExportHelper {
    fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "glb" -> "model/gltf-binary"
        "obj" -> "model/obj"
        "stl" -> "model/stl"
        "ply" -> "application/octet-stream"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Opens Android's share sheet for one or more files (model files, photos). */
    fun share(context: Context, files: List<File>, title: String) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uri(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeOf(files[0].name)
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = if (files.all { mimeOf(it.name).startsWith("image/") }) "image/*" else "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, title)
        intent.clipData = ClipData.newRawUri(title, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copies a file to a location the user picked with the system "Save as" dialog. */
    fun copyTo(context: Context, file: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw java.io.IOException("Cannot write to the selected location")
    }

    /** Copies an asset (e.g. the calibration marker) to the cache so it can be shared/printed. */
    fun assetToCache(context: Context, asset: String): File {
        val f = File(File(context.cacheDir, "share").apply { mkdirs() }, asset.substringAfterLast('/'))
        context.assets.open(asset).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }
}

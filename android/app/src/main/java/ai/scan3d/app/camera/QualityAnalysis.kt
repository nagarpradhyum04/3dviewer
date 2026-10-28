package ai.scan3d.app.camera

import ai.scan3d.core.quality.ImageQuality
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.File

/**
 * Live frame analysis for lighting/blur hints. Runs on CameraX's analysis thread, throttled.
 * Only the luminance (Y) plane is used; nothing is stored.
 */
class LiveQualityAnalyzer(
    private val intervalMs: Long = 600,
    private val onMetrics: (ImageQuality.Metrics) -> Unit,
) : ImageAnalysis.Analyzer {
    private var last = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - last < intervalMs) return
            last = now
            val plane = image.planes[0]
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            if (plane.pixelStride != 1) return     // Y plane is always packed in practice
            val (luma, w, h) = ImageQuality.downscale(bytes, image.width, image.height, plane.rowStride)
            onMetrics(ImageQuality.analyse(luma, w, h))
        } catch (e: Exception) {
            // analysis is best-effort; never crash the camera
        } finally {
            image.close()
        }
    }
}

/** Quality of a saved photo, computed on a ~800 px wide decode (same scale the server uses). */
object PhotoQuality {
    fun analyse(file: File): ImageQuality.Metrics? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ImageQuality.ANALYSIS_WIDTH) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = if (bmp.width > ImageQuality.ANALYSIS_WIDTH) {
            Bitmap.createScaledBitmap(bmp, ImageQuality.ANALYSIS_WIDTH,
                bmp.height * ImageQuality.ANALYSIS_WIDTH / bmp.width, true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        val w = scaled.width
        val h = scaled.height
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        scaled.recycle()
        val luma = ByteArray(w * h) { i ->
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            ((299 * r + 587 * g + 114 * b) / 1000).toByte()   // same weights as OpenCV's BGR2GRAY
        }
        return ImageQuality.analyse(luma, w, h)
    }
}

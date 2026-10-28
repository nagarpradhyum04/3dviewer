package ai.scan3d.core.quality

import kotlin.math.sqrt

/**
 * Photo quality heuristics, computed on a downscaled 8-bit luminance image.
 *
 * The same metrics (and thresholds) are used by the backend (backend/app/pipeline/validate.py),
 * so the phone can warn the user live and the server makes the final decision. They are hints,
 * not guarantees: e.g. a low sharpness score can also mean a plain, featureless subject.
 */
object ImageQuality {
    /** Width the luminance image should be downscaled to before analysis (matches the backend). */
    const val ANALYSIS_WIDTH = 800
    const val BLUR_ABS_THRESHOLD = 40.0
    const val DARK_MEAN = 45.0
    const val BRIGHT_MEAN = 215.0
    const val CLIPPED_FRACTION = 0.12
    const val LOW_TEXTURE_EDGE_DENSITY = 0.015

    enum class Issue(val message: String) {
        BLURRY("Photo looks blurry. Hold still, tap to focus and make sure there is enough light."),
        TOO_DARK("Photo is too dark. Add diffuse light; avoid using the flash."),
        TOO_BRIGHT("Photo is overexposed. Move away from direct light."),
        HIGHLIGHTS("Large bright highlights: reflective surfaces reconstruct poorly. Use soft, indirect light."),
        LOW_TEXTURE("Little visible texture. Plain, transparent or shiny objects are hard to reconstruct."),
    }

    data class Metrics(
        val sharpness: Double,
        val brightness: Double,
        val clippedFraction: Double,
        val edgeDensity: Double,
    ) {
        fun issues(): List<Issue> = buildList {
            if (sharpness < BLUR_ABS_THRESHOLD) add(Issue.BLURRY)
            if (brightness < DARK_MEAN) add(Issue.TOO_DARK)
            if (brightness > BRIGHT_MEAN) add(Issue.TOO_BRIGHT)
            if (clippedFraction > CLIPPED_FRACTION) add(Issue.HIGHLIGHTS)
            if (edgeDensity < LOW_TEXTURE_EDGE_DENSITY) add(Issue.LOW_TEXTURE)
        }
    }

    /**
     * @param luma row-major luminance, values 0..255 stored in the low 8 bits of each byte
     * @param rowStride bytes between the starts of consecutive rows (>= width)
     */
    fun analyse(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Metrics {
        require(width >= 3 && height >= 3) { "image too small" }
        require(rowStride >= width && luma.size >= rowStride * (height - 1) + width) { "buffer too small" }
        fun px(x: Int, y: Int) = luma[y * rowStride + x].toInt() and 0xFF

        var sum = 0.0
        var clipped = 0
        for (y in 0 until height) for (x in 0 until width) {
            val v = px(x, y)
            sum += v
            if (v >= 250) clipped++
        }
        val n = width.toDouble() * height
        // Laplacian (4-neighbour, as OpenCV ksize=1) variance and Sobel edge density over the interior.
        var lapSum = 0.0
        var lapSq = 0.0
        var edges = 0
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val c = px(x, y)
            val lap = (px(x - 1, y) + px(x + 1, y) + px(x, y - 1) + px(x, y + 1) - 4 * c).toDouble()
            lapSum += lap
            lapSq += lap * lap
            val gx = (px(x + 1, y - 1) + 2 * px(x + 1, y) + px(x + 1, y + 1)) -
                (px(x - 1, y - 1) + 2 * px(x - 1, y) + px(x - 1, y + 1))
            val gy = (px(x - 1, y + 1) + 2 * px(x, y + 1) + px(x + 1, y + 1)) -
                (px(x - 1, y - 1) + 2 * px(x, y - 1) + px(x + 1, y - 1))
            if (sqrt((gx * gx + gy * gy).toDouble()) > 100.0) edges++
        }
        val m = (width - 2).toDouble() * (height - 2)
        val mean = lapSum / m
        return Metrics(
            sharpness = lapSq / m - mean * mean,
            brightness = sum / n,
            clippedFraction = clipped / n,
            edgeDensity = edges / n,
        )
    }

    /** Nearest-neighbour downscale of a luminance plane so that width <= [targetWidth]. */
    fun downscale(luma: ByteArray, width: Int, height: Int, rowStride: Int, targetWidth: Int = ANALYSIS_WIDTH):
        Triple<ByteArray, Int, Int> {
        if (width <= targetWidth) {
            val out = ByteArray(width * height)
            for (y in 0 until height) System.arraycopy(luma, y * rowStride, out, y * width, width)
            return Triple(out, width, height)
        }
        val step = width.toDouble() / targetWidth
        val h = (height / step).toInt().coerceAtLeast(1)
        val out = ByteArray(targetWidth * h)
        for (y in 0 until h) {
            val sy = (y * step).toInt().coerceAtMost(height - 1)
            for (x in 0 until targetWidth) {
                out[y * targetWidth + x] = luma[sy * rowStride + (x * step).toInt().coerceAtMost(width - 1)]
            }
        }
        return Triple(out, targetWidth, h)
    }
}

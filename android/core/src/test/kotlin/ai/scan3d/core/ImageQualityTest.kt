package ai.scan3d.core

import ai.scan3d.core.quality.ImageQuality
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ImageQualityTest {
    private fun checker(w: Int, h: Int, cell: Int): ByteArray {
        val rnd = Random(1)
        val shades = IntArray(((w / cell) + 1) * ((h / cell) + 1)) { rnd.nextInt(30, 230) }
        return ByteArray(w * h) { i -> shades[(i / w / cell) * ((w / cell) + 1) + (i % w) / cell].toByte() }
    }

    private fun boxBlur(src: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (dy in -r..r) for (dx in -r..r) {
                val yy = (y + dy).coerceIn(0, h - 1); val xx = (x + dx).coerceIn(0, w - 1)
                s += src[yy * w + xx].toInt() and 0xFF; n++
            }
            out[y * w + x] = (s / n).toByte()
        }
        return out
    }

    @Test fun sharpTexturedImageHasNoIssues() {
        val m = ImageQuality.analyse(checker(400, 300, 6), 400, 300)
        assertTrue(m.issues().isEmpty())
    }

    @Test fun blurDetected() {
        val img = checker(400, 300, 6)
        val sharp = ImageQuality.analyse(img, 400, 300)
        val blurred = ImageQuality.analyse(boxBlur(img, 400, 300, 6), 400, 300)
        assertTrue(blurred.sharpness < sharp.sharpness / 10)
        assertTrue(ImageQuality.Issue.BLURRY in blurred.issues())
    }

    @Test fun darkAndPlainDetected() {
        val m = ImageQuality.analyse(ByteArray(200 * 100) { 20 }, 200, 100)
        assertTrue(ImageQuality.Issue.TOO_DARK in m.issues())
        assertTrue(ImageQuality.Issue.LOW_TEXTURE in m.issues())
        assertFalse(ImageQuality.Issue.HIGHLIGHTS in m.issues())
    }

    @Test fun highlightsDetected() {
        val img = checker(400, 300, 6)
        for (i in 0 until img.size / 3) img[i] = 255.toByte()
        assertTrue(ImageQuality.Issue.HIGHLIGHTS in ImageQuality.analyse(img, 400, 300).issues())
    }

    @Test fun downscaleRespectsStride() {
        val w = 1600; val h = 1200; val stride = 1664
        val buf = ByteArray(stride * h) { 7 }
        val (out, ow, oh) = ImageQuality.downscale(buf, w, h, stride)
        assertTrue(ow == 800 && oh == 600 && out.size == 800 * 600)
    }
}

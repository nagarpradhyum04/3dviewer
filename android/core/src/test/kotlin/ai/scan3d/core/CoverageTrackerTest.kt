package ai.scan3d.core

import ai.scan3d.core.capture.CoverageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTrackerTest {
    @Test fun fullRingAndTopRingIsReady() {
        val t = CoverageTracker()
        for (i in 0 until 16) t.add(i * 22.5f, -30f)
        for (i in 0 until 14) t.add(i * 25.7f + 5, -60f)
        assertEquals(1f, t.ringCoverage())
        assertTrue(t.largestGapDeg() < 30)
        val r = t.readiness(8)
        assertTrue(r.canSubmit)
        assertFalse(r.recommendedMore)
    }

    @Test fun tooFewPhotosBlocksSubmit() {
        val t = CoverageTracker()
        repeat(3) { t.add(it * 20f, -30f) }
        assertFalse(t.readiness(8).canSubmit)
    }

    @Test fun halfRingRecommendsMore() {
        val t = CoverageTracker()
        for (i in 0 until 10) t.add(i * 15f, -30f)
        val r = t.readiness(8)
        assertTrue(r.canSubmit && r.recommendedMore)
        assertTrue(t.nextHint().contains("missing"))
    }

    @Test fun largeStepWarns() {
        val t = CoverageTracker()
        t.add(350f, -30f)
        assertFalse(t.add(10f, -30f).tooFarFromPrevious)     // wraps around: 20 degrees
        assertTrue(t.add(80f, -30f).tooFarFromPrevious)
    }

    @Test fun angularDistanceWraps() {
        assertEquals(20f, CoverageTracker.angularDistance(350f, 10f), 1e-4f)
        assertEquals(180f, CoverageTracker.angularDistance(0f, 180f), 1e-4f)
    }
}

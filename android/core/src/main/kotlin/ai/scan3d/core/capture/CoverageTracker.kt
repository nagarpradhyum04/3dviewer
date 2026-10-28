package ai.scan3d.core.capture

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tracks which viewing directions around the object have been photographed.
 *
 * The phone cannot know where the object is, so we use the device's compass heading (azimuth)
 * and tilt (pitch) at each capture as a proxy for the viewpoint while the user circles a
 * stationary object, keeping it centred. This is guidance only; the server measures the real
 * camera positions after Structure-from-Motion and reports any remaining gaps.
 */
class CoverageTracker(val sectors: Int = 12) {
    enum class Band(val label: String) { LOW("low"), MIDDLE("eye-level"), HIGH("high") }

    data class Shot(val azimuthDeg: Float, val pitchDeg: Float)

    private val shots = mutableListOf<Shot>()
    val count: Int get() = shots.size

    fun add(azimuthDeg: Float, pitchDeg: Float): Feedback {
        val prev = shots.lastOrNull()
        shots += Shot(normalise(azimuthDeg), pitchDeg)
        return feedback(prev)
    }

    /** Heading change since the most recent shot, or null if there is none. */
    fun stepFrom(azimuthDeg: Float): Float? = shots.lastOrNull()?.let { angularDistance(it.azimuthDeg, azimuthDeg) }

    fun removeAt(index: Int) {
        if (index in shots.indices) shots.removeAt(index)
    }

    fun clear() = shots.clear()

    fun restore(list: List<Shot>) {
        shots.clear()
        shots += list.map { Shot(normalise(it.azimuthDeg), it.pitchDeg) }
    }

    /** Pitch is negative when the phone looks down. Looking down steeply = high viewpoint. */
    fun bandOf(pitchDeg: Float): Band = when {
        pitchDeg < -45f -> Band.HIGH
        pitchDeg > -12f -> Band.LOW
        else -> Band.MIDDLE
    }

    fun sectorOf(azimuthDeg: Float): Int = ((normalise(azimuthDeg) / (360f / sectors)).toInt()).coerceIn(0, sectors - 1)

    /** Covered sectors per band. */
    fun covered(): Map<Band, Set<Int>> =
        Band.entries.associateWith { b -> shots.filter { bandOf(it.pitchDeg) == b }.map { sectorOf(it.azimuthDeg) }.toSet() }

    /** Sectors covered in any band (the main ring). */
    fun ringCoverage(): Float = shots.map { sectorOf(it.azimuthDeg) }.toSet().size / sectors.toFloat()

    fun largestGapDeg(): Float {
        if (shots.size < 2) return 360f
        val az = shots.map { it.azimuthDeg }.sorted()
        var gap = 360f - az.last() + az.first()
        for (i in 1 until az.size) gap = maxOf(gap, az[i] - az[i - 1])
        return gap
    }

    data class Feedback(val tooFarFromPrevious: Boolean, val stepDeg: Float?)

    private fun feedback(prev: Shot?): Feedback {
        if (prev == null) return Feedback(false, null)
        val step = angularDistance(prev.azimuthDeg, shots.last().azimuthDeg)
        return Feedback(step > MAX_STEP_DEG, step)
    }

    /** Readiness for submission, with a reason the user can act on. */
    fun readiness(minPhotos: Int): Readiness {
        val cov = covered()
        val ring = ringCoverage()
        return when {
            count < minPhotos -> Readiness(false, "Take at least $minPhotos photos (${minPhotos - count} more).")
            ring < 0.6f -> Readiness(true, "Only ${(ring * 100).roundToInt()}% of the way around is covered. " +
                "Keep circling the object for a complete model.", recommendedMore = true)
            cov.getValue(Band.HIGH).size < 3 -> Readiness(true, "Add a few photos from above to capture the top.",
                recommendedMore = true)
            count < RECOMMENDED_PHOTOS -> Readiness(true, "Good start. ${RECOMMENDED_PHOTOS - count} more photos " +
                "improve detail.", recommendedMore = true)
            else -> Readiness(true, "Coverage looks good.")
        }
    }

    /** What to do next, for the on-screen hint. */
    fun nextHint(): String {
        if (count == 0) return "Place the object on a textured surface and take the first photo."
        val cov = covered()
        val ring = cov.values.flatten().toSet()
        val missing = (0 until sectors).filter { it !in ring }
        return when {
            missing.isNotEmpty() -> "Step sideways around the object (~${(360 / sectors)}° per photo). " +
                "${missing.size} of $sectors directions still missing."
            cov.getValue(Band.HIGH).size < sectors / 2 -> "Now raise the phone and circle again looking down at the object."
            cov.getValue(Band.LOW).size < sectors / 3 -> "Optionally, take a lower ring to capture the sides near the base."
            else -> "Good coverage. Add close-ups of detailed areas or tap Finish."
        }
    }

    companion object {
        const val MAX_STEP_DEG = 40f
        const val RECOMMENDED_PHOTOS = 30

        fun normalise(deg: Float): Float = ((deg % 360f) + 360f) % 360f

        fun angularDistance(a: Float, b: Float): Float {
            val d = abs(normalise(a) - normalise(b))
            return if (d > 180f) 360f - d else d
        }
    }
}

data class Readiness(val canSubmit: Boolean, val message: String, val recommendedMore: Boolean = false)

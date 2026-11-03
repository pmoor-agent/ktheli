package ktheli.input

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Raw extent of one joystick axis, as reported by the device (GLFW reports roughly -1..1).
 *
 * [neutralLow]..[neutralHigh] is the raw range a resting spring-loaded stick wanders in. It reads as
 * exactly 0, and the remaining travel on each side is rescaled to the full 0..±1.
 */
@Serializable
data class AxisCalibration(
    val min: Float = -1f,
    val center: Float = 0f,
    val max: Float = 1f,
    val neutralLow: Float = center,
    val neutralHigh: Float = center,
) {
    /**
     * Maps a raw value to [-1, 1]. Centered axes (spring-loaded sticks) map piecewise-linearly outside
     * the neutral band; non-centered axes (throttle/collective) map min..max linearly.
     */
    fun normalize(raw: Float, centered: Boolean): Double {
        val v = if (centered) {
            when {
                raw > neutralHigh -> (raw - neutralHigh).toDouble() / (max - neutralHigh)
                raw < neutralLow -> (raw - neutralLow).toDouble() / (neutralLow - min)
                else -> 0.0
            }
        } else {
            2.0 * (raw - min) / (max - min) - 1.0
        }
        return if (v.isFinite()) v.coerceIn(-1.0, 1.0) else 0.0
    }
}

/**
 * Two-phase calibration. In [Phase.CENTER] the pilot leaves all sticks resting while their jitter is
 * recorded; after [beginExtremes] they move every stick to its extremes; then [finish].
 */
class CalibrationSession(axisCount: Int) {
    enum class Phase { CENTER, EXTREMES }

    var phase = Phase.CENTER
        private set

    private val neutralMin = FloatArray(axisCount) { Float.POSITIVE_INFINITY }
    private val neutralMax = FloatArray(axisCount) { Float.NEGATIVE_INFINITY }
    private val min = FloatArray(axisCount) { Float.POSITIVE_INFINITY }
    private val max = FloatArray(axisCount) { Float.NEGATIVE_INFINITY }

    fun observe(axes: FloatArray) {
        for (i in 0 until minOf(min.size, axes.size)) {
            val v = axes[i]
            if (phase == Phase.CENTER) {
                neutralMin[i] = minOf(neutralMin[i], v)
                neutralMax[i] = maxOf(neutralMax[i], v)
            }
            min[i] = minOf(min[i], v)
            max[i] = maxOf(max[i], v)
        }
    }

    fun beginExtremes() {
        phase = Phase.EXTREMES
    }

    /** Axes that were never moved fall back to the nominal range. */
    fun finish(): List<AxisCalibration> = min.indices.map { i ->
        if (!neutralMin[i].isFinite() || travel(i) < MIN_TRAVEL) {
            AxisCalibration()
        } else {
            val center = (neutralMin[i] + neutralMax[i]) / 2
            val jitter = (neutralMax[i] - neutralMin[i]) / 2
            val halfTravel = minOf(max[i] - center, center - min[i])
            val band = (jitter * NEUTRAL_MARGIN + NEUTRAL_MIN).coerceAtMost(halfTravel * MAX_NEUTRAL_FRACTION)
            AxisCalibration(min[i], center, max[i], center - band, center + band)
        }
    }

    /** For on-screen feedback during calibration. */
    fun travel(i: Int): Float = if (max[i] >= min[i]) max[i] - min[i] else 0f

    /** Half-width of the resting jitter seen so far. */
    fun jitter(i: Int): Float = if (neutralMax[i] >= neutralMin[i]) (neutralMax[i] - neutralMin[i]) / 2 else 0f

    private companion object {
        const val MIN_TRAVEL = 0.2f
        const val NEUTRAL_MARGIN = 1.5f // headroom over the observed jitter
        const val NEUTRAL_MIN = 0.01f // ≈0.5 % of full travel, even for perfectly clean signals
        const val MAX_NEUTRAL_FRACTION = 0.25f // never swallow more than a quarter of each side
    }
}

/**
 * Detects which axis the pilot moves, for "move the collective stick up now" style assignment.
 * The sign of the movement tells whether the axis must be reversed.
 */
class AxisDetector(private val baseline: FloatArray, private val threshold: Float = 0.4f) {
    data class Detection(val axis: Int, val reversed: Boolean)

    fun observe(axes: FloatArray): Detection? {
        var best = -1
        var bestDelta = 0f
        for (i in 0 until minOf(baseline.size, axes.size)) {
            val d = axes[i] - baseline[i]
            if (abs(d) > abs(bestDelta)) {
                best = i
                bestDelta = d
            }
        }
        return if (best >= 0 && abs(bestDelta) >= threshold) Detection(best, bestDelta < 0) else null
    }
}

/** Detects the first button pressed after creation. Buttons already held at creation must be released first. */
class ButtonDetector(baseline: BooleanArray) {
    private val held = baseline.copyOf()

    fun observe(buttons: BooleanArray): Int? {
        for (i in buttons.indices) {
            val wasHeld = i < held.size && held[i]
            if (buttons[i] && !wasHeld) return i
            if (!buttons[i] && i < held.size) held[i] = false
        }
        return null
    }
}

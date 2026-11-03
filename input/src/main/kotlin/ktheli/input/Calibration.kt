package ktheli.input

import kotlinx.serialization.Serializable

/** Raw extent of one joystick axis, as reported by the device (GLFW reports roughly -1..1). */
@Serializable
data class AxisCalibration(val min: Float = -1f, val center: Float = 0f, val max: Float = 1f) {
    /**
     * Maps a raw value to [-1, 1]. Centered axes (spring-loaded sticks) map piecewise-linearly so the
     * captured center is exactly 0; non-centered axes (throttle/collective) map min..max linearly.
     */
    fun normalize(raw: Float, centered: Boolean): Double {
        val v = if (centered) {
            if (raw >= center) (raw - center).toDouble() / (max - center) else (raw - center).toDouble() / (center - min)
        } else {
            2.0 * (raw - min) / (max - min) - 1.0
        }
        return if (v.isFinite()) v.coerceIn(-1.0, 1.0) else 0.0
    }
}

/**
 * Captures per-axis calibration: construct with sticks centered, then [observe] while the pilot
 * moves every stick to its extremes, then [finish].
 */
class CalibrationSession(centered: FloatArray) {
    private val center = centered.copyOf()
    private val min = centered.copyOf()
    private val max = centered.copyOf()

    fun observe(axes: FloatArray) {
        for (i in center.indices) {
            if (i >= axes.size) break
            if (axes[i] < min[i]) min[i] = axes[i]
            if (axes[i] > max[i]) max[i] = axes[i]
        }
    }

    /** Axes that were never moved fall back to the nominal range. */
    fun finish(): List<AxisCalibration> = center.indices.map { i ->
        if (max[i] - min[i] < MIN_TRAVEL) AxisCalibration() else AxisCalibration(min[i], center[i], max[i])
    }

    /** For on-screen feedback during calibration. */
    fun travel(i: Int): Float = max[i] - min[i]

    private companion object {
        const val MIN_TRAVEL = 0.2f
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
        for (i in baseline.indices) {
            if (i >= axes.size) break
            val d = axes[i] - baseline[i]
            if (kotlin.math.abs(d) > kotlin.math.abs(bestDelta)) {
                best = i
                bestDelta = d
            }
        }
        return if (best >= 0 && kotlin.math.abs(bestDelta) >= threshold) Detection(best, bestDelta < 0) else null
    }
}

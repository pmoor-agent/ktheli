package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.math.deg

/**
 * A symmetric airfoil over the full ±180° range, shaped after the NACA 0012 at RC Reynolds numbers
 * (~300k; Sheldahl & Klimas, SAND80-2114): linear lift up to stall, then a smooth transition to flat-plate
 * behavior (`Cl = Cfp·sin 2α`, `Cd = Cd0 + Cdfp·sin²α`), which also covers reverse flow on the retreating side.
 *
 * Below [referenceReynolds] the zero-lift drag grows like a laminar boundary layer, `Cd0·√(Re_ref/Re)`,
 * capped at [maxLowReynoldsFactor]: small, slow blades are draggier, which matters as a rotor coasts down.
 */
data class AirfoilParams(
    val liftSlope: Double = 5.7, // per rad
    val stallAngle: Double = 12.5.deg,
    val stallWidth: Double = 1.0.deg, // sharpness of the transition to separated flow
    val cd0: Double = 0.011,
    val dragRise: Double = 0.45, // per rad², attached flow
    val flatPlateLift: Double = 1.05,
    val flatPlateDrag: Double = 2.0,
    val referenceReynolds: Double = 150_000.0,
    val maxLowReynoldsFactor: Double = 3.0,
)

/** Precomputed lift/drag tables (0.25° steps) so the hot loop is a lookup, and identical everywhere. */
class Airfoil(val params: AirfoilParams = AirfoilParams()) {
    private val step = DMath.toRadians(0.25)
    private val size = (DMath.TWO_PI / step + 0.5).toInt() + 1
    private val clTable = DoubleArray(size)
    private val cdTable = DoubleArray(size)

    init {
        for (i in 0 until size) {
            val alpha = -DMath.PI + i * step
            clTable[i] = liftCoefficient(alpha)
            cdTable[i] = dragCoefficient(alpha)
        }
    }

    /** Separated-flow weight: 0 well below stall, 1 well above (and in reverse flow). */
    private fun separation(alpha: Double): Double {
        val a = kotlin.math.abs(alpha)
        if (a > DMath.PI / 2) return 1.0
        return 1.0 / (1.0 + DMath.exp(-(a - params.stallAngle) / params.stallWidth))
    }

    private fun liftCoefficient(alpha: Double): Double {
        val s = separation(alpha)
        val attached = if (kotlin.math.abs(alpha) <= DMath.PI / 2) params.liftSlope * alpha else 0.0
        return (1.0 - s) * attached + s * params.flatPlateLift * DMath.sin(2.0 * alpha)
    }

    private fun dragCoefficient(alpha: Double): Double {
        val s = separation(alpha)
        val sin = DMath.sin(alpha)
        val attached = params.cd0 + params.dragRise * alpha * alpha
        return (1.0 - s) * attached + s * (params.cd0 + params.flatPlateDrag * sin * sin)
    }

    /** Lift coefficient for an angle of attack in radians, any range. */
    fun cl(alpha: Double): Double = lookup(clTable, alpha)

    fun cd(alpha: Double): Double = lookup(cdTable, alpha)

    /** Drag including the low-Reynolds-number increase of the zero-lift part. */
    fun cd(alpha: Double, reynolds: Double): Double = cd(alpha) + params.cd0 * (lowReynoldsFactor(reynolds) - 1.0)

    fun lowReynoldsFactor(reynolds: Double): Double =
        if (reynolds >= params.referenceReynolds) 1.0
        else DMath.sqrt(params.referenceReynolds / maxOf(reynolds, 1.0)).coerceAtMost(params.maxLowReynoldsFactor)

    private fun lookup(table: DoubleArray, alpha: Double): Double {
        val t = (wrap(alpha) + DMath.PI) / step
        val i = t.toInt().coerceIn(0, size - 2)
        val f = t - i
        return table[i] + (table[i + 1] - table[i]) * f
    }

    companion object {
        /** Dynamic viscosity of air at 15 °C, Pa·s (nearly independent of pressure). */
        const val AIR_DYNAMIC_VISCOSITY = 1.789e-5

        /** Reynolds number of a section with chord [chord] in airflow [speed] at density [density]. */
        fun reynolds(speed: Double, chord: Double, density: Double) = speed * chord * density / AIR_DYNAMIC_VISCOSITY

        /** Wraps an angle to [-π, π). */
        fun wrap(angle: Double): Double {
            var a = angle
            while (a >= DMath.PI) a -= DMath.TWO_PI
            while (a < -DMath.PI) a += DMath.TWO_PI
            return a
        }
    }
}

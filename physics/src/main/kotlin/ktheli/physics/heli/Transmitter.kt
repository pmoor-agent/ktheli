package ktheli.physics.heli

import ktheli.physics.sim.Controls

/** Receiver channels as a flight controller sees them, each in [-1, 1]. */
data class ReceiverInputs(
    val collective: Double,
    val roll: Double,
    val pitch: Double,
    val yaw: Double,
    val throttle: Double,
    val gain: Double,
) {
    companion object {
        val OFF = ReceiverInputs(0.0, 0.0, 0.0, 0.0, -1.0, 0.0)
    }
}

/** Piecewise-linear curve through points evenly spaced over [-1, 1], like a transmitter's 5-point curves. */
class Curve(vararg val points: Double) {
    init {
        require(points.size >= 2)
    }

    operator fun invoke(x: Double): Double {
        val t = (x.coerceIn(-1.0, 1.0) + 1.0) / 2.0 * (points.size - 1)
        val i = t.toInt().coerceAtMost(points.size - 2)
        return points[i] + (points[i + 1] - points[i]) * (t - i)
    }

    companion object {
        fun flat(value: Double) = Curve(value, value)
    }
}

/**
 * @property pitchCurve collective stick to collective channel
 * @property throttleCurve collective stick to throttle, 0..1 (the ESC governor reads it as target headspeed)
 * @property gain gain channel; positive selects heading hold, negative rate mode (Futaba AVCS style)
 */
data class FlightModeSetup(val name: String, val pitchCurve: Curve, val throttleCurve: Curve, val gain: Double)

data class TransmitterSetup(
    val modes: List<FlightModeSetup>,
    val holdPitchCurve: Curve,
    val holdGain: Double,
    val cyclicExpo: Double,
    val yawExpo: Double,
)

/** A computer radio: flight modes with pitch/throttle curves, throttle hold, expo. Pure function of [Controls]. */
class Transmitter(val setup: TransmitterSetup) {
    fun mode(controls: Controls): FlightModeSetup = setup.modes[controls.flightMode.coerceIn(0, setup.modes.lastIndex)]

    fun receive(c: Controls): ReceiverInputs {
        val mode = mode(c)
        return ReceiverInputs(
            collective = if (c.throttleHold) setup.holdPitchCurve(c.collective) else mode.pitchCurve(c.collective),
            roll = expo(c.roll, setup.cyclicExpo),
            pitch = expo(c.pitch, setup.cyclicExpo),
            yaw = expo(c.yaw, setup.yawExpo),
            throttle = if (c.throttleHold) -1.0 else 2.0 * mode.throttleCurve(c.collective).coerceIn(0.0, 1.0) - 1.0,
            gain = if (c.throttleHold) setup.holdGain else mode.gain,
        )
    }

    companion object {
        fun expo(x: Double, e: Double): Double {
            val v = x.coerceIn(-1.0, 1.0)
            return (1.0 - e) * v + e * v * v * v
        }

        /**
         * A typical electric 3D setup, for a flight controller with ±[collectiveRangeDeg] collective:
         * normal mode for spool-up and hovering, two idle-ups with symmetric 3D pitch, throttle hold.
         */
        fun default3d(collectiveRangeDeg: Double = 12.5): TransmitterSetup {
            fun pitch(vararg deg: Double) = Curve(*DoubleArray(deg.size) { deg[it] / collectiveRangeDeg })
            return TransmitterSetup(
                modes = listOf(
                    FlightModeSetup("Normal", pitch(-3.0, 1.0, 4.5, 8.0, 11.0), Curve(0.0, 0.55, 0.65, 0.65, 0.65), gain = 0.6),
                    FlightModeSetup("Idle up 1", pitch(-11.0, -5.5, 0.0, 5.5, 11.0), Curve.flat(0.75), gain = 0.6),
                    FlightModeSetup("Idle up 2", pitch(-12.0, -6.0, 0.0, 6.0, 12.0), Curve.flat(0.85), gain = 0.55),
                ),
                holdPitchCurve = pitch(-4.0, 0.0, 4.0, 8.0, 12.0),
                holdGain = 0.6,
                cyclicExpo = 0.25,
                yawExpo = 0.2,
            )
        }
    }
}

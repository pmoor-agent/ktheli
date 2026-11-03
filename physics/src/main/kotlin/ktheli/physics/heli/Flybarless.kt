package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.math.deg
import kotlin.math.abs

/** Proportional-integral loop with a feed-forward term and integrator clamping. */
class RateLoop(private val feedForward: Double, private val p: Double, private val i: Double, private val integralLimit: Double) {
    var integral = 0.0
        private set

    /** Output for a desired and measured rate; [gain] scales the feedback (not the feed-forward) part. */
    fun update(desired: Double, measured: Double, dt: Double, gain: Double = 1.0, integrate: Boolean = true): Double {
        val error = desired - measured
        if (integrate) integral = (integral + error * dt).coerceIn(-integralLimit, integralLimit) else integral = 0.0
        return feedForward * desired + gain * (p * error + i * integral)
    }

    fun reset() {
        integral = 0.0
    }
}

/**
 * Tail gyro settings. Rates in rad/s, outputs in normalized servo units.
 *
 * @property collectivePrecomp tail servo per unit of |collective channel|: main rotor torque grows with
 *   collective in either direction, so the tail is pre-loaded before the gyro sees any yaw.
 */
data class TailGyroSettings(
    val maxYawRate: Double = 500.0.deg,
    val feedForward: Double = 0.06, // servo per rad/s
    val p: Double = 0.35, // servo per rad/s of error, at gain 1
    val i: Double = 3.0, // servo per rad of accumulated error, at gain 1
    val integralLimit: Double = 0.6, // rad
    val collectivePrecomp: Double = 0.12,
)

/**
 * A heading-hold tail gyro in the style of a Futaba GY520 with AVCS: the gain channel's magnitude sets the
 * loop gain and its sign the mode, positive for heading hold (PI: holds heading against disturbances)
 * and negative for rate mode (P only: the tail weathervanes slowly).
 */
class HeadingHoldGyro(private val s: TailGyroSettings = TailGyroSettings()) : TailGyro {
    private val loop = RateLoop(s.feedForward, s.p, s.i, s.integralLimit)

    var headingHold = true
        private set

    override fun update(sensors: SensorReadings, rx: ReceiverInputs, dt: Double): Double {
        headingHold = rx.gain >= 0.0
        val desired = rx.yaw * s.maxYawRate
        val out = loop.update(desired, sensors.gyro.z, dt, gain = abs(rx.gain), integrate = headingHold)
        return (out + s.collectivePrecomp * abs(rx.collective)).coerceIn(-1.0, 1.0)
    }
}

/** An old-school rate gyro: proportional damping only, no heading hold, whatever the gain channel's sign. */
class RateGyro(private val s: TailGyroSettings = TailGyroSettings()) : TailGyro {
    private val loop = RateLoop(s.feedForward, s.p, 0.0, 0.0)

    override fun update(sensors: SensorReadings, rx: ReceiverInputs, dt: Double): Double {
        val out = loop.update(rx.yaw * s.maxYawRate, sensors.gyro.z, dt, gain = abs(rx.gain), integrate = false)
        return (out + s.collectivePrecomp * abs(rx.collective)).coerceIn(-1.0, 1.0)
    }
}

/**
 * Flybarless unit settings. Ranges in degrees of blade pitch must match the airframe's swashplate setup
 * (as when setting up a real unit with a pitch gauge).
 *
 * @property cyclicFeedForward normalized cyclic per rad/s of commanded rate; roughly the rotor's flapping
 *   time constant divided by the cyclic range
 * @property lowRpmIntegratorCutoff below this rotor rpm the integrators are held at zero, so they don't wind
 *   up while the heli sits on its skids
 */
data class FlybarlessSettings(
    val loopRateHz: Int = 1000,
    val servoPitchRangeDeg: Double = 20.0, // blade pitch for full servo travel
    val collectiveRangeDeg: Double = 12.5,
    val cyclicRangeDeg: Double = 8.0,
    val maxRollRate: Double = 400.0.deg,
    val maxPitchRate: Double = 400.0.deg,
    val cyclicFeedForward: Double = 0.12,
    val cyclicP: Double = 0.06,
    val cyclicI: Double = 0.6,
    val cyclicIntegralLimit: Double = 0.4, // rad of attitude error
    val lowRpmIntegratorCutoff: Double = 1000.0,
)

/**
 * A flybarless controller: rate loops on roll and pitch driving a 120° CCPM swashplate, collective passed
 * through, a pluggable [TailGyro], and throttle passed on to the ESC (whose governor holds headspeed).
 */
class FlybarlessController(
    val settings: FlybarlessSettings = FlybarlessSettings(),
    val tailGyro: TailGyro = HeadingHoldGyro(),
) : FlightController {
    private val roll = RateLoop(settings.cyclicFeedForward, settings.cyclicP, settings.cyclicI, settings.cyclicIntegralLimit)
    private val pitch = RateLoop(settings.cyclicFeedForward, settings.cyclicP, settings.cyclicI, settings.cyclicIntegralLimit)
    private val collectiveScale = settings.collectiveRangeDeg / settings.servoPitchRangeDeg
    private val cyclicScale = settings.cyclicRangeDeg / settings.servoPitchRangeDeg

    override fun update(sensors: SensorReadings, rx: ReceiverInputs, dt: Double): ServoCommands {
        val spinning = (sensors.rotorRpm ?: Double.MAX_VALUE) >= settings.lowRpmIntegratorCutoff
        val rollOut = roll.update(rx.roll * settings.maxRollRate, sensors.gyro.x, dt, integrate = spinning)
        // Stick forward commands nose-down (negative q); the swashplate's positive pitch tilts it forward.
        val noseUp = pitch.update(-rx.pitch * settings.maxPitchRate, sensors.gyro.y, dt, integrate = spinning)

        // Cyclic ring: limit combined cyclic so the swashplate stays within its geometric range.
        var r = rollOut
        var p = -noseUp
        val magnitude = DMath.sqrt(r * r + p * p)
        if (magnitude > 1.0) {
            r /= magnitude
            p /= magnitude
        }
        val swash = Ccpm120.mix(rx.collective * collectiveScale, r * cyclicScale, p * cyclicScale)
        return ServoCommands(swash, tailGyro.update(sensors, rx, dt), rx.throttle)
    }
}

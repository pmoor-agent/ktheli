package ktheli.app.sound

import ktheli.physics.heli.BladeElementRotor
import ktheli.physics.heli.Helicopter
import ktheli.physics.math.Vec3
import ktheli.physics.model.AircraftModel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where the sound is heard: a position and the listener's right-hand direction (world NED). */
data class Listener(val position: Vec3, val right: Vec3) {
    companion object {
        /**
         * A listener at [position] looking at [target]; [previous] supplies the right-hand direction when
         * looking straight up or down.
         */
        fun facing(position: Vec3, target: Vec3, previous: Vec3 = Vec3(0.0, 1.0, 0.0)): Listener {
            val f = target - position
            // Right = down x forward, flattened to the horizon.
            val right = Vec3(-f.y, f.x, 0.0)
            val length = right.length()
            return Listener(position, if (length > 1e-6) right / length else previous)
        }
    }
}

/**
 * Turns the simulated helicopter's state into [SoundParams]. Display only, like the renderer: it reads the
 * simulation and never feeds back.
 *
 * The levels follow the physics loosely: rotor thickness noise grows with the cube of tip speed, loading
 * noise with thrust, the motor's whine with its current, and the blades "slap" when heavily loaded or when
 * they meet their own wake (vortex ring state).
 */
object HeliAcoustics {
    /** What the model is doing, independent of where it is heard. */
    data class Source(
        val mainRpm: Double,
        val mainBlades: Int,
        val tipSpeed: Double, // m/s
        /** Main rotor thrust over weight; negative in negative collective. */
        val loading: Double,
        /** 0..1 blade-vortex interaction. */
        val slap: Double,
        val tailRpm: Double,
        val tailBlades: Int,
        val tailTipSpeed: Double,
        /** Tail thrust over weight. */
        val tailLoading: Double,
        val motorRpm: Double,
        val motorCurrent: Double, // A
        /**
         * 0..1: whether the ESC drives the motor. Without drive (throttle hold, autorotation) the one-way
         * bearing lets motor and main gear wind down while the rotor freewheels: no whine, no gear mesh.
         */
        val driven: Double,
    )

    const val SPEED_OF_SOUND = 343.0 // m/s
    const val REFERENCE_TIP_SPEED = 100.0 // m/s: a 450 at ~2650 rpm
    const val REFERENCE_DISTANCE = 5.0 // m: full level, about where the pilot stands
    const val POLE_PAIRS = 3 // 6-pole outrunner, typical for a 450
    const val PINION_TEETH = 13
    const val TOY_RPM = 2500.0

    fun source(aircraft: AircraftModel): Source = when (aircraft) {
        is Helicopter -> source(aircraft)
        // The toy has no rotor physics: a steady whirr.
        else -> Source(
            mainRpm = TOY_RPM, mainBlades = 2, tipSpeed = 0.36 * TOY_RPM * RPM_TO_RAD, loading = 1.0, slap = 0.0,
            tailRpm = TOY_RPM * 4.5, tailBlades = 2, tailTipSpeed = 0.0775 * TOY_RPM * 4.5 * RPM_TO_RAD,
            tailLoading = 0.1, motorRpm = TOY_RPM * 11.0, motorCurrent = 10.0, driven = 1.0,
        )
    }

    fun source(heli: Helicopter): Source {
        val airframe = heli.airframe
        val weight = airframe.mass * 9.80665
        val main = airframe.mainRotor.rotor
        val tail = airframe.tailRotor.rotor
        return Source(
            mainRpm = heli.rotorRpm,
            mainBlades = main.blades,
            tipSpeed = main.radius * heli.rotorRpm * RPM_TO_RAD,
            loading = heli.mainThrust / weight,
            slap = (heli.mainRotor as? BladeElementRotor)?.vortexRing ?: 0.0,
            tailRpm = heli.tailRotorRpm,
            tailBlades = tail.blades,
            tailTipSpeed = tail.radius * heli.tailRotorRpm * RPM_TO_RAD,
            tailLoading = heli.tailThrust / weight,
            motorRpm = heli.motorRpm,
            motorCurrent = heli.motorCurrent,
            driven = smoothstep(heli.escDuty / 0.05),
        )
    }

    /**
     * @param position where the model is, [velocity] how it moves (world NED)
     * @param ambientWind wind speed at the listener, m/s
     */
    fun params(
        source: Source,
        position: Vec3,
        velocity: Vec3,
        listener: Listener,
        ambientWind: Double,
        volume: Double,
    ): SoundParams {
        val m = source.tipSpeed / REFERENCE_TIP_SPEED
        val load = min(abs(source.loading), 3.0)
        val mt = source.tailTipSpeed / REFERENCE_TIP_SPEED
        val tailLoad = min(abs(source.tailLoading) / 0.15, 2.0)
        val running = smoothstep(source.motorRpm / 3000.0) * source.driven
        val current = (max(source.motorCurrent, 0.0) / 40.0).coerceAtMost(2.0)

        val offset = position - listener.position
        val distance = offset.length()
        val toModel = if (distance > 1e-6) offset / distance else Vec3(1.0, 0.0, 0.0)
        val receding = (velocity dot toModel).coerceIn(-0.5 * SPEED_OF_SOUND, 0.5 * SPEED_OF_SOUND)

        return SoundParams(
            bladePassHz = source.mainBlades * source.mainRpm / 60.0,
            rotorLevel = 0.35 * m * m * m * (0.4 + 0.6 * load),
            rotorSharpness = (0.25 * max(0.0, load - 1.0) + source.slap).coerceIn(0.0, 1.0),
            rotorNoise = 0.12 * m * m * (0.5 + 0.5 * load),
            tailPassHz = source.tailBlades * source.tailRpm / 60.0,
            tailLevel = 0.10 * mt * mt * mt + 0.08 * tailLoad * mt * mt,
            motorHz = source.motorRpm / 60.0 * POLE_PAIRS,
            motorLevel = running * (0.05 + 0.10 * current),
            gearHz = source.motorRpm / 60.0 * PINION_TEETH,
            gearLevel = running * 0.025 * (0.5 + 0.5 * current),
            windLevel = 0.08 * min((ambientWind / 8.0).let { it * it }, 2.0),
            distanceGain = min(REFERENCE_DISTANCE / max(distance, 1e-3), 2.0),
            absorptionHz = 18_000.0 / (1.0 + distance / 40.0),
            doppler = SPEED_OF_SOUND / (SPEED_OF_SOUND + receding),
            pan = (toModel dot listener.right).coerceIn(-1.0, 1.0),
            volume = volume,
        )
    }

    private const val RPM_TO_RAD = 2.0 * Math.PI / 60.0

    private fun smoothstep(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}

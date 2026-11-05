package ktheli.app.session

import ktheli.physics.heli.Helicopter
import ktheli.physics.model.AircraftModel

/**
 * Rotor state for drawing, from the simulation.
 *
 * @property bladeAzimuth main rotor blade 0 azimuth (rad, from the tail in the direction of rotation) when the
 *   simulation tracks individual blades; otherwise the renderer integrates [rpm]
 */
class RotorPose(
    val rpm: Double,
    val tailRpm: Double,
    val tiltBack: Double = 0.0,
    val tiltRight: Double = 0.0,
    val bladeAzimuth: Double? = null,
)

/**
 * What the UI and the renderer show of the aircraft, read from the simulation in this one place: the only display
 * code that knows the concrete aircraft types. (The sound needs much more of the helicopter and has its own
 * adapter, `HeliAcoustics.source`.)
 *
 * @property headspeed main rotor speed against the governor, null for models without rotor physics
 * @property flightModes names of the radio's flight modes; empty for models without a transmitter
 */
class AircraftView(
    val rotors: RotorPose,
    val headspeed: Headspeed?,
    val flightModes: List<String>,
) {
    /** [targetRpm] is 0 when no governor is holding the headspeed (e.g. throttle hold). */
    class Headspeed(val rpm: Double, val targetRpm: Double)

    companion object {
        /** The toy has no rotor physics: shown spinning steadily. */
        const val TOY_ROTOR_RPM = 2500.0
        const val TOY_TAIL_RATIO = 4.5

        /** [crashed]: frozen at the impact, so the blades stand still. */
        fun of(aircraft: AircraftModel, crashed: Boolean): AircraftView = when (aircraft) {
            is Helicopter -> {
                val main = aircraft.mainRotor
                AircraftView(
                    rotors = RotorPose(
                        rpm = if (crashed) 0.0 else aircraft.rotorRpm,
                        tailRpm = if (crashed) 0.0 else aircraft.tailRotorRpm,
                        tiltBack = main.tiltBack,
                        tiltRight = main.tiltRight,
                        bladeAzimuth = main.bladeAzimuth,
                    ),
                    headspeed = Headspeed(aircraft.rotorRpm, aircraft.governorTargetRpm),
                    flightModes = aircraft.transmitter.setup.modes.map { it.name },
                )
            }
            else -> {
                val rpm = if (crashed) 0.0 else TOY_ROTOR_RPM
                AircraftView(RotorPose(rpm, rpm * TOY_TAIL_RATIO), headspeed = null, flightModes = emptyList())
            }
        }
    }
}

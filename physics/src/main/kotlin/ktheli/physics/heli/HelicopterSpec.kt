package ktheli.physics.heli

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3

/** Which main rotor model a helicopter is simulated with. */
enum class RotorModel {
    /** Per-blade, per-section aerodynamics with flapping blades ([BladeElementRotor]). */
    BLADE_ELEMENT,

    /** Uniform-inflow disc with first-order tip-path-plane dynamics ([FlappingDiscRotor]). */
    DISC,
}

enum class TailGyroType { HEADING_HOLD, RATE }

/**
 * Everything the simulation needs to build one helicopter: the airframe (dimensions, masses, rotors,
 * drivetrain, servos, ...), the main rotor model, the flight controller and tail gyro setup, and the radio's
 * model memory (flight modes, curves). Presentation (3D model, textures) lives in the app.
 */
data class HelicopterSpec(
    val airframe: Airframe,
    val rotorModel: RotorModel = RotorModel.BLADE_ELEMENT,
    val flybarless: FlybarlessSettings = FlybarlessSettings(),
    val tailGyroType: TailGyroType = TailGyroType.HEADING_HOLD,
    val tailGyro: TailGyroSettings = TailGyroSettings(),
    val transmitter: TransmitterSetup = Transmitter.default3d(),
) {
    /** Resting on its skids at [position] (z ignored). */
    fun parkedAt(position: Vec3): RigidBodyState = Helicopter.parkedAt(position, airframe)

    fun build(initial: RigidBodyState = parkedAt(Vec3.ZERO), seed: Long = 1): Helicopter {
        val mainRotor = when (rotorModel) {
            RotorModel.BLADE_ELEMENT -> BladeElementRotor(airframe.mainRotor, seed)
            RotorModel.DISC -> FlappingDiscRotor(airframe.mainRotor)
        }
        val gyro = when (tailGyroType) {
            TailGyroType.HEADING_HOLD -> HeadingHoldGyro(tailGyro)
            TailGyroType.RATE -> RateGyro(tailGyro)
        }
        return Helicopter(
            airframe = airframe,
            transmitter = Transmitter(transmitter),
            controller = FlybarlessController(flybarless, gyro),
            initial = initial,
            mainRotor = mainRotor,
            seed = seed,
            maxYawRate = tailGyro.maxYawRate,
        )
    }

    companion object {
        /** A generic 450 with the blade element rotor; its FBL unit compensates the head's cyclic phase. */
        val GENERIC_450 = HelicopterSpec(
            airframe = Airframes.class450,
            flybarless = FlybarlessSettings(cyclicPhaseDeg = Helicopter.BLADE_ELEMENT_PHASE),
        )

        /** The same 450 with the simpler disc rotor (no phase compensation needed). */
        val GENERIC_450_DISC = HelicopterSpec(airframe = Airframes.class450, rotorModel = RotorModel.DISC)

        /** Align T-Rex 450 class: the generic 450 numbers, tail rotor on the right of the boom like the real one. */
        val TREX_450 = GENERIC_450.copy(airframe = Airframes.trex450)

        val TREX_450_DISC = GENERIC_450_DISC.copy(airframe = Airframes.trex450)
    }
}

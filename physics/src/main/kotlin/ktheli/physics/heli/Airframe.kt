package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.math.Mat3
import ktheli.physics.math.Vec3
import ktheli.physics.math.deg

/**
 * Blade geometry and airfoil of one rotor.
 *
 * Section drag grows with the mean blade angle of attack α: `Cd = Cd0 + dragRise·α²`, plus a steep
 * `stallDrag·(α − stallAngle)²` past the stall onset, so over-pitching costs power and can bog the rotor.
 *
 * @property inducedPowerFactor κ, real rotors' induced power over ideal momentum theory (tip losses,
 *   non-uniform inflow); typically 1.1–1.2
 */
data class RotorParams(
    val radius: Double, // m
    val chord: Double, // m
    val blades: Int,
    val liftSlope: Double, // per rad; ~5.5 at RC Reynolds numbers
    val profileDrag: Double, // Cd0
    val dragRise: Double = 0.4, // per rad²
    val stallAngle: Double = 9.0.deg, // mean blade angle of attack where drag starts rising steeply
    val stallDrag: Double = 3.0, // per rad²
    val inducedPowerFactor: Double = 1.15,
)

/**
 * @property hubHeight hub above the CG, m
 * @property hubStiffnessStatic flap stiffness of the head (dampers) with the rotor stopped, N·m/rad
 * @property hubStiffnessCentrifugal additional stiffness at [referenceSpeed], growing with Ω²
 * @property lockNumber ratio of aerodynamic to inertial blade forces; sets the flapping time constant 16/(γΩ)
 * @property inertia rotor plus drivetrain about the main shaft, kg·m²
 *
 * Blade element model only:
 * @property bladeMass one blade, kg, spread evenly from [rootCutout] to the tip
 * @property rootCutout start of the aerodynamic blade section, m from the shaft
 * @property flapSpring flap stiffness of the head's dampers, per blade, N·m/rad
 * @property flapDamping flap damping of the dampers, per blade, N·m·s/rad
 * @property tipLoss fraction of the radius that produces lift (Prandtl-style tip loss)
 * @property elements blade sections per blade
 *
 * Drivetrain losses (gears, belt or torque tube, tail gearbox, bearings), as torque on the main shaft:
 * @property frictionTorque constant part, N·m; dominates when the rotor coasts down at low rpm
 * @property viscousTorque speed-proportional part at [referenceSpeed], N·m
 */
data class MainRotorParams(
    val rotor: RotorParams,
    val hubHeight: Double,
    val hubStiffnessStatic: Double,
    val hubStiffnessCentrifugal: Double,
    val referenceSpeed: Double, // rad/s
    val lockNumber: Double,
    val inertia: Double,
    val airfoil: AirfoilParams = AirfoilParams(),
    val bladeMass: Double = 0.023,
    val rootCutout: Double = 0.045,
    val flapSpring: Double = 10.0,
    val flapDamping: Double = 0.002,
    val tipLoss: Double = 0.97,
    val elements: Int = 12,
    val frictionTorque: Double = 0.015,
    val viscousTorque: Double = 0.010, // with frictionTorque ~7 W at hover: a 7 % drivetrain loss
)

/**
 * @property gearRatio tail rotor turns per main rotor turn
 * @property pitchOffset blade pitch with the tail servo centered (mechanical torque compensation)
 * @property pitchPerServo blade pitch for full servo travel (normalized 1)
 */
data class TailRotorParams(
    val rotor: RotorParams,
    val position: Vec3,
    val gearRatio: Double,
    val pitchOffset: Double,
    val pitchPerServo: Double,
)

/**
 * Swashplate geometry: blade pitch produced by one unit of normalized servo travel. The same for
 * collective and cyclic, since both come from moving the same swashplate.
 */
data class SwashParams(val pitchPerServo: Double)

/** @property secondsPer60 servo speed spec; normalized travel ±1 spans ±[travelDegrees] of servo arm */
data class ServoParams(val secondsPer60: Double, val frameRateHz: Double, val travelDegrees: Double = 40.0) {
    val unitsPerSecond: Double get() = 60.0 / secondsPer60 / travelDegrees
}

/** @property gearRatio motor turns per main rotor turn */
data class MotorParams(val kv: Double, val resistance: Double, val noLoadCurrent: Double, val gearRatio: Double)

data class BatteryParams(
    val cells: Int,
    val capacityAh: Double,
    val internalResistance: Double, // whole pack, Ω
    val cellFull: Double = 4.2,
    val cellEmpty: Double = 3.5,
)

/**
 * ESC with a headspeed governor. The throttle channel (0..1 after mapping) selects the target headspeed
 * as a fraction of [governorMaxRpm]; below [offBelow] the motor is off.
 */
data class EscParams(
    val governorMaxRpm: Double, // main rotor rpm at 100 % throttle
    val governorP: Double, // duty per rad/s of rotor speed error
    val governorI: Double, // duty per rad of accumulated error
    val spoolUpSeconds: Double, // soft start, 0 to max
    val bailoutSeconds: Double, // fast recovery when the rotor is still spinning
    val offBelow: Double = 0.05,
)

/** Drag areas (Cd·S, m²) along the body axes, and a vertical fin for weathervane stability. */
data class FuselageParams(
    val dragAreaX: Double,
    val dragAreaY: Double,
    val dragAreaZ: Double,
    val finArea: Double,
    val finSideForceCoefficient: Double,
    val finPosition: Vec3,
)

/**
 * Rough crash geometry, body frame: points that must never touch the ground or an obstacle. The main and
 * tail rotor discs are added from their parameters.
 *
 * @property fuselage canopy and frame corners
 * @property boom points along the tail boom
 * @property fin tips of the vertical fin
 * @property hardLanding skid sink rate (m/s) at touchdown beyond which the landing gear breaks
 */
data class CollisionParams(
    val fuselage: List<Vec3>,
    val boom: List<Vec3>,
    val fin: List<Vec3>,
    val hardLanding: Double = 3.0,
) {
    companion object {
        /** A box around canopy, frame and battery, above the skids; boom and fin of a 450. */
        val class450 = CollisionParams(
            fuselage = buildList {
                for (x in listOf(-0.10, 0.20)) for (y in listOf(-0.045, 0.045)) for (z in listOf(-0.10, 0.07)) add(Vec3(x, y, z))
                add(Vec3(0.24, 0.0, 0.0)) // canopy nose
            },
            boom = listOf(Vec3(-0.20, 0.0, -0.03), Vec3(-0.33, 0.0, -0.03)),
            fin = listOf(Vec3(-0.50, 0.0, 0.07), Vec3(-0.49, 0.0, -0.10)),
        )
    }
}

/** Skid contact points (body frame) with per-point spring, damper and Coulomb friction. */
data class SkidParams(val contacts: List<Vec3>, val stiffness: Double, val damping: Double, val friction: Double)

data class Airframe(
    val name: String,
    val mass: Double,
    val inertia: Mat3,
    val mainRotor: MainRotorParams,
    val tailRotor: TailRotorParams,
    val swash: SwashParams,
    val motor: MotorParams,
    val battery: BatteryParams,
    val esc: EscParams,
    val cyclicServo: ServoParams,
    val tailServo: ServoParams,
    val fuselage: FuselageParams,
    val skids: SkidParams,
    val collision: CollisionParams = CollisionParams.class450,
)

object Airframes {
    /** Align T-Rex 450 class: [class450] with the tail rotor on the right of the boom, where the real one sits. */
    val trex450: Airframe by lazy {
        class450.copy(
            name = "Align T-Rex 450",
            tailRotor = class450.tailRotor.copy(position = Vec3(-0.47, 0.028, -0.03)),
        )
    }

    /** A generic 450-size 3D helicopter on 3S, numbers in the range of a T-Rex 450 class machine. */
    val class450: Airframe = run {
        val mass = 0.82
        val skidDrop = 0.12
        val perContact = mass * 9.81 / 4
        Airframe(
            name = "450 class (FBL, 3S)",
            mass = mass,
            inertia = Mat3.diagonal(0.010, 0.026, 0.022),
            mainRotor = MainRotorParams(
                rotor = RotorParams(radius = 0.36, chord = 0.035, blades = 2, liftSlope = 5.5, profileDrag = 0.013),
                hubHeight = 0.15,
                hubStiffnessStatic = 2.0,
                hubStiffnessCentrifugal = 8.0,
                referenceSpeed = 280.0,
                lockNumber = 3.3,
                inertia = 0.0030,
            ),
            tailRotor = TailRotorParams(
                rotor = RotorParams(radius = 0.0775, chord = 0.016, blades = 2, liftSlope = 5.0, profileDrag = 0.015),
                position = Vec3(-0.47, 0.0, -0.03),
                gearRatio = 4.5,
                pitchOffset = 5.0.deg,
                pitchPerServo = 25.0.deg,
            ),
            swash = SwashParams(pitchPerServo = 20.0.deg),
            motor = MotorParams(kv = 3500.0, resistance = 0.045, noLoadCurrent = 1.2, gearRatio = 11.0),
            battery = BatteryParams(cells = 3, capacityAh = 2.2, internalResistance = 0.045),
            esc = EscParams(
                governorMaxRpm = 3600.0,
                governorP = 0.0025, // a "medium" governor: ~6 % dip on a full punch, recovered in ~0.5 s
                governorI = 0.015,
                spoolUpSeconds = 8.0,
                bailoutSeconds = 1.5,
            ),
            cyclicServo = ServoParams(secondsPer60 = 0.10, frameRateHz = 200.0),
            tailServo = ServoParams(secondsPer60 = 0.04, frameRateHz = 333.0),
            fuselage = FuselageParams(
                dragAreaX = 0.010,
                dragAreaY = 0.035,
                dragAreaZ = 0.040,
                finArea = 0.006,
                finSideForceCoefficient = 1.2,
                finPosition = Vec3(-0.50, 0.0, 0.0),
            ),
            skids = SkidParams(
                contacts = listOf(
                    Vec3(0.09, 0.075, skidDrop), Vec3(0.09, -0.075, skidDrop),
                    Vec3(-0.07, 0.075, skidDrop), Vec3(-0.07, -0.075, skidDrop),
                ),
                stiffness = perContact / 0.01, // 1 cm static sag
                damping = 0.7 * 2.0 * DMath.sqrt(perContact / 0.01 * mass / 4),
                friction = 0.6,
            ),
        )
    }
}

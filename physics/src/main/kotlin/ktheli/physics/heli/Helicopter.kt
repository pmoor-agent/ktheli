package ktheli.physics.heli

import ktheli.physics.body.MassProperties
import ktheli.physics.body.RigidBodyState
import ktheli.physics.body.Wrench
import ktheli.physics.body.integrateRk4
import ktheli.physics.env.Environment
import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.model.AircraftModel
import ktheli.physics.model.Gauge
import ktheli.physics.sim.Controls
import ktheli.physics.sim.StateHash
import kotlin.math.abs

/**
 * A single-rotor electric helicopter:
 *
 * ```
 * Controls → Transmitter → ReceiverInputs ─┐
 * Imu (from true state) → SensorReadings ──┴→ FlightController → servos → swashplate / tail pitch
 * ESC governor → motor ─(one-way bearing)→ rotor speed ← main + tail rotor torque
 * main rotor (thrust, flapping disc tilt, hub moments) + tail rotor + fuselage + skids → rigid body
 * ```
 *
 * The rigid body is integrated with RK4; rotor speed, inflow, flapping, servos and electrics advance once per
 * tick with forward Euler (their time constants are ≥ 5 ms against a 0.5 ms tick).
 */
class Helicopter(
    val airframe: Airframe = Airframes.class450,
    val transmitter: Transmitter = Transmitter(Transmitter.default3d()),
    val controller: FlightController = FlybarlessController(),
    initial: RigidBodyState = parkedAt(Vec3.ZERO, airframe),
    /** The main rotor model; the disc model is the simpler Phase 2 one. */
    val mainRotor: MainRotor = FlappingDiscRotor(airframe.mainRotor),
    seed: Long = 1,
    private val tickRateHz: Int = 2000,
    controllerRateHz: Int = 1000,
    /** Full-stick yaw rate of the tail gyro, for the "commanded" telemetry gauge. */
    private val maxYawRate: Double = TailGyroSettings().maxYawRate,
) : AircraftModel {
    override var body: RigidBodyState = initial
        private set

    private val mp = MassProperties(airframe.mass, airframe.inertia)
    private val tailDisc = RotorDisc(airframe.tailRotor.rotor)
    private val drivetrain = Drivetrain(airframe.motor, airframe.battery, airframe.esc)
    private val imu = Imu(seed)
    private val controllerTicks = maxOf(1, tickRateHz / controllerRateHz)
    private val controllerDt = controllerTicks.toDouble() / tickRateHz
    private val servoFront = Servo(airframe.cyclicServo, tickRateHz)
    private val servoRight = Servo(airframe.cyclicServo, tickRateHz)
    private val servoLeft = Servo(airframe.cyclicServo, tickRateHz)
    private val servoTail = Servo(airframe.tailServo, tickRateHz)
    private val hub = Vec3(0.0, 0.0, -airframe.mainRotor.hubHeight)

    private var tick = 0L
    private var commands = ServoCommands.IDLE

    // Rotor states.
    var rotorSpeed = 0.0 // main rotor, rad/s
        private set
    private var tailInflow = 0.0
    private var specificForce = Vec3(0.0, 0.0, -9.80665)

    // Latest values, for gauges.
    var receiver = ReceiverInputs.OFF
        private set
    var sensors = SensorReadings(Vec3.ZERO, specificForce, 0.0)
        private set
    private var pitches = BladePitches(0.0, 0.0, 0.0, 0.0)
    private var last = Forces(
        Wrench.ZERO, MainRotorLoads(Vec3.ZERO, Vec3.ZERO, 0.0, 0.0, 0.0), RotorDisc.Output(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 1.0,
        HubAir.STILL, 0.0,
    )
    private var motorTorque = 0.0
    private var electricalPower = 0.0

    /** Blade pitch angles in rad; cyclic as swashplate tilt (forward / right positive). */
    data class BladePitches(val collective: Double, val lateral: Double, val longitudinal: Double, val tail: Double)

    private class Forces(
        val wrench: Wrench,
        val main: MainRotorLoads,
        val tail: RotorDisc.Output,
        val groundEffect: Double,
        val hubAir: HubAir,
        val airspeed: Double,
    )

    override fun step(controls: Controls, environment: Environment, dt: Double) {
        receiver = transmitter.receive(controls)
        sensors = imu.read(body.angularVelocity, specificForce, rotorSpeed)
        if (tick % controllerTicks == 0L) commands = controller.update(sensors, receiver, controllerDt)

        servoFront.step(commands.swash.front, tick, dt)
        servoRight.step(commands.swash.right, tick, dt)
        servoLeft.step(commands.swash.left, tick, dt)
        servoTail.step(commands.tail, tick, dt)
        val swash = Ccpm120.unmix(SwashCommand(servoFront.position, servoRight.position, servoLeft.position))
        val k = airframe.swash.pitchPerServo
        val tr = airframe.tailRotor
        pitches = BladePitches(swash.collective * k, swash.roll * k, swash.pitch * k, tr.pitchOffset + servoTail.position * tr.pitchPerServo)

        val electric = drivetrain.step(commands.throttle, rotorSpeed, dt)
        motorTorque = electric.rotorTorque
        electricalPower = electric.electricalPower

        val start = forces(body, environment)
        val before = body
        body = integrateRk4(body, mp, dt) { s -> forces(s, environment).wrench }
        advanceRotorStates(start, before, dt)
        specificForce = start.wrench.force / mp.mass
        last = start
        tick++
    }

    private fun advanceRotorStates(f: Forces, before: RigidBodyState, dt: Double) {
        val mr = airframe.mainRotor
        val tailShaftTorque = if (rotorSpeed > 1.0) f.tail.power / rotorSpeed else 0.0
        val friction = mr.viscousTorque * rotorSpeed / mr.referenceSpeed + (if (rotorSpeed > 0.0) mr.frictionTorque else 0.0)
        rotorSpeed = (rotorSpeed + (motorTorque - f.main.shaftTorque - tailShaftTorque - friction) / mr.inertia * dt).coerceAtLeast(0.0)

        val tailSpeed = rotorSpeed * airframe.tailRotor.gearRatio
        tailInflow += (f.tail.equilibriumInflow - tailInflow) * dt / tailDisc.inflowTimeConstant(tailSpeed, f.tail.inflowRatio)
        mainRotor.advance(f.main, before, body, rotorSpeed, pitches, specificForce, f.groundEffect, f.hubAir, dt)
    }

    private fun forces(s: RigidBodyState, env: Environment): Forces {
        val omega = s.angularVelocity
        val v = s.velocityBody
        val mr = airframe.mainRotor
        val tr = airframe.tailRotor
        val density = env.density
        /** Wind at a body-frame point, in the body frame. */
        fun wind(point: Vec3) = s.attitude.inverseRotate(env.windAt(s.position + s.attitude.rotate(point)))

        // Main rotor.
        val hubAltitude = -(s.position + s.attitude.rotate(hub)).z
        val groundEffect = RotorDisc.groundEffect(mr.rotor.radius, hubAltitude)
        val hubAir = HubAir(density, wind(hub))
        val main = mainRotor.loads(s, rotorSpeed, pitches, groundEffect, hubAir)
        val mainForce = main.force
        // The motor turns the rotor clockwise seen from above (+z body); its reaction yaws the fuselage nose-left.
        val mainMoment = (hub cross mainForce) + main.hubMoment + Vec3(0.0, 0.0, -motorTorque)

        // Tail rotor on the right side, thrusting left (-y) to yaw the nose right.
        val vTail = v + (omega cross tr.position) - wind(tr.position)
        val tail = tailDisc.evaluate(
            RotorDisc.Input(pitches.tail, vTail.y, DMath.hypot(vTail.x, vTail.z), rotorSpeed * tr.gearRatio, tailInflow, 1.0, density),
        )
        val tailForce = Vec3(0.0, -tail.thrust, 0.0)
        val tailMoment = tr.position cross tailForce

        // Fuselage drag and vertical fin.
        val fu = airframe.fuselage
        val q = 0.5 * density
        val vAir = v - wind(Vec3.ZERO)
        val drag = Vec3(-q * fu.dragAreaX * vAir.x * abs(vAir.x), -q * fu.dragAreaY * vAir.y * abs(vAir.y), -q * fu.dragAreaZ * vAir.z * abs(vAir.z))
        val vFin = v + (omega cross fu.finPosition) - wind(fu.finPosition)
        val finForce = Vec3(0.0, -q * fu.finArea * fu.finSideForceCoefficient * vFin.y * vFin.length(), 0.0)
        val finMoment = fu.finPosition cross finForce

        val ground = skidContacts(s)

        val force = mainForce + tailForce + drag + finForce + ground.force
        val moment = mainMoment + tailMoment + finMoment + ground.torque
        return Forces(Wrench(force, moment), main, tail, groundEffect, hubAir, vAir.length())
    }

    private fun skidContacts(s: RigidBodyState): Wrench {
        val sk = airframe.skids
        var force = Vec3.ZERO
        var torque = Vec3.ZERO
        for (c in sk.contacts) {
            val world = s.position + s.attitude.rotate(c)
            val penetration = world.z // NED: positive below ground
            if (penetration <= 0.0) continue
            val velocity = s.velocity + s.attitude.rotate(s.angularVelocity cross c)
            val normal = maxOf(0.0, sk.stiffness * penetration + sk.damping * velocity.z)
            val slip = Vec3(velocity.x, velocity.y, 0.0)
            val slipSpeed = slip.length()
            val friction = slip * (-sk.friction * normal / maxOf(slipSpeed, SLIP_REGULARIZATION))
            val fBody = s.attitude.inverseRotate(Vec3(friction.x, friction.y, -normal))
            force += fBody
            torque += c cross fBody
        }
        return Wrench(force, torque)
    }

    // --- Read-outs ---------------------------------------------------------------------------------

    val rotorRpm: Double get() = rotorSpeed * 60.0 / DMath.TWO_PI
    val tailRotorRpm: Double get() = rotorRpm * airframe.tailRotor.gearRatio
    val governorTargetRpm: Double get() = drivetrain.governorTarget * 60.0 / DMath.TWO_PI
    val bladePitches: BladePitches get() = pitches
    val mainThrust: Double get() = last.main.thrust
    val tailThrust: Double get() = last.tail.thrust

    override val gauges: List<Gauge> = listOf(
        Gauge("Rotor", "headspeed", "rpm") { rotorRpm },
        Gauge("Rotor", "governor target", "rpm") { governorTargetRpm },
        Gauge("Power", "electrical", "W") { electricalPower },
        Gauge("Power", "main rotor", "W") { last.main.power },
        Gauge("Power", "tail rotor", "W") { last.tail.power },
        Gauge("Electrics", "battery", "V") { drivetrain.batteryVoltage },
        Gauge("Electrics", "motor current", "A") { drivetrain.motorCurrent },
        Gauge("Electrics", "ESC duty x10", "") { drivetrain.duty * 10.0 },
        Gauge("Pitch", "collective", "deg") { DMath.toDegrees(pitches.collective) },
        Gauge("Pitch", "cyclic lateral", "deg") { DMath.toDegrees(pitches.lateral) },
        Gauge("Pitch", "cyclic longitudinal", "deg") { DMath.toDegrees(pitches.longitudinal) },
        Gauge("Pitch", "tail", "deg") { DMath.toDegrees(pitches.tail) },
        Gauge("Yaw", "yaw rate", "deg/s") { DMath.toDegrees(body.angularVelocity.z) },
        Gauge("Yaw", "commanded", "deg/s") { DMath.toDegrees(receiver.yaw * maxYawRate) },
        Gauge("Thrust", "main", "N") { last.main.thrust },
        Gauge("Thrust", "tail", "N") { last.tail.thrust },
        Gauge("Thrust", "weight", "N") { mp.mass * 9.80665 },
        Gauge("Wind", "wind at rotor", "m/s") { last.hubAir.wind.length() },
        Gauge("Wind", "vertical gust (up)", "m/s") { -body.attitude.rotate(last.hubAir.wind).z },
        Gauge("Wind", "airspeed", "m/s") { last.airspeed },
        Gauge("Disc", "tilt back", "deg") { DMath.toDegrees(mainRotor.tiltBack) },
        Gauge("Disc", "tilt right", "deg") { DMath.toDegrees(mainRotor.tiltRight) },
        Gauge("Disc", "coning", "deg") { DMath.toDegrees(mainRotor.coning) },
        Gauge("Inflow", "induced velocity", "m/s") { mainRotor.inflow },
        Gauge("Inflow", "vortex ring (0-1)", "") { (mainRotor as? BladeElementRotor)?.vortexRing ?: 0.0 },
    )

    override fun hashInto(hash: StateHash) {
        hash.add(body).add(rotorSpeed).add(tailInflow)
        mainRotor.hashInto(hash)
        hash.add(servoFront.position).add(servoRight.position).add(servoLeft.position).add(servoTail.position)
        hash.add(drivetrain.stateOfCharge).add(drivetrain.governorTarget).add(imu.rng.state)
    }

    companion object {
        /** The generic 450 with the Phase 2 disc rotor; see [HelicopterSpec.GENERIC_450_DISC]. */
        fun disc450(initial: RigidBodyState = parkedAt(Vec3.ZERO), seed: Long = 1) =
            HelicopterSpec.GENERIC_450_DISC.build(initial, seed)

        /** The generic 450 with the blade element rotor; see [HelicopterSpec.GENERIC_450]. */
        fun bladeElement450(initial: RigidBodyState = parkedAt(Vec3.ZERO), seed: Long = 1) =
            HelicopterSpec.GENERIC_450.build(initial, seed)

        const val BLADE_ELEMENT_PHASE = 18.0 // minimizes roll-pitch cross-coupling (measured in flight)

        private const val SLIP_REGULARIZATION = 0.05 // m/s; smooths Coulomb friction around zero slip

        /** Resting on its skids at the given world position (z is ignored), with ~1 cm sag. */
        fun parkedAt(p: Vec3, airframe: Airframe = Airframes.class450): RigidBodyState {
            val drop = airframe.skids.contacts.maxOf { it.z }
            return RigidBodyState(position = Vec3(p.x, p.y, -drop + 0.01))
        }
    }
}

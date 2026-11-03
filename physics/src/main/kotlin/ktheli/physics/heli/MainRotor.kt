package ktheli.physics.heli

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.sim.StateHash

/**
 * Loads of the main rotor on the airframe and on its shaft, for one body state with the rotor's own states
 * held fixed.
 *
 * @property force total rotor force, body frame, acting at the hub
 * @property hubMoment moment transmitted through the head (hub springs and dampers), body frame; the caller
 *   adds the hub-offset moment and the motor's reaction torque
 * @property shaftTorque aerodynamic torque resisting rotation, N·m
 */
open class MainRotorLoads(
    val force: Vec3,
    val hubMoment: Vec3,
    val shaftTorque: Double,
    val thrust: Double,
    val power: Double,
)

/**
 * A main rotor model: computes loads and advances its internal states (flapping, inflow). [loads] is called
 * several times per tick (RK4 stages) and must not change state; [advance] is called once per tick with the
 * loads from the start of the tick and the body state after integration.
 */
interface MainRotor {
    val name: String

    fun loads(s: RigidBodyState, rotorSpeed: Double, pitches: Helicopter.BladePitches, groundEffect: Double): MainRotorLoads

    fun advance(
        start: MainRotorLoads,
        before: RigidBodyState,
        after: RigidBodyState,
        rotorSpeed: Double,
        pitches: Helicopter.BladePitches,
        specificForce: Vec3,
        groundEffect: Double,
        dt: Double,
    )

    /** Tip-path-plane tilt back (a1) and right (b1), rad, and coning, rad; for displays. */
    val tiltBack: Double
    val tiltRight: Double
    val coning: Double

    /** Mean induced velocity through the disc, m/s. */
    val inflow: Double

    fun hashInto(hash: StateHash)
}

/**
 * Phase 2 rotor: uniform-inflow [RotorDisc] for thrust and power, and a first-order tip-path plane
 * (Gavrilets et al., small aerobatic helicopter) that follows cyclic with time constant 16/(γΩ), lags body
 * rotation and blows back away from the relative wind.
 */
class FlappingDiscRotor(private val params: MainRotorParams) : MainRotor {
    override val name = "disc"
    private val disc = RotorDisc(params.rotor)
    private val hub = Vec3(0.0, 0.0, -params.hubHeight)

    private var flapLon = 0.0 // a1: disc tilt back (nose-up), rad
    private var flapLat = 0.0 // b1: disc tilt right, rad
    private var mainInflow = 0.0
    private var lastOutput = RotorDisc.Output(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    override val tiltBack get() = flapLon
    override val tiltRight get() = flapLat
    override val coning get() = 0.0
    override val inflow get() = mainInflow

    override fun loads(s: RigidBodyState, rotorSpeed: Double, pitches: Helicopter.BladePitches, groundEffect: Double): MainRotorLoads {
        val vHub = s.velocityBody + (s.angularVelocity cross hub)
        val out = disc.evaluate(
            RotorDisc.Input(pitches.collective, vHub.z, DMath.hypot(vHub.x, vHub.y), rotorSpeed, mainInflow, groundEffect),
        )
        val thrust = out.thrust
        val force = Vec3(
            -thrust * DMath.sin(flapLon),
            thrust * DMath.sin(flapLat) * DMath.cos(flapLon),
            -thrust * DMath.cos(flapLon) * DMath.cos(flapLat),
        )
        val stiffness = params.hubStiffnessStatic + params.hubStiffnessCentrifugal * (rotorSpeed / params.referenceSpeed).let { it * it }
        return DiscLoads(force, Vec3(stiffness * flapLat, stiffness * flapLon, 0.0), out)
    }

    private class DiscLoads(force: Vec3, hubMoment: Vec3, val output: RotorDisc.Output) :
        MainRotorLoads(force, hubMoment, output.torque, output.thrust, output.power)

    override fun advance(
        start: MainRotorLoads,
        before: RigidBodyState,
        after: RigidBodyState,
        rotorSpeed: Double,
        pitches: Helicopter.BladePitches,
        specificForce: Vec3,
        groundEffect: Double,
        dt: Double,
    ) {
        val out = (start as DiscLoads).output
        lastOutput = out
        mainInflow += (out.equilibriumInflow - mainInflow) * dt / disc.inflowTimeConstant(rotorSpeed, out.inflowRatio)

        val tau = 16.0 / (params.lockNumber * maxOf(rotorSpeed, MIN_FLAP_SPEED))
        val v = after.velocityBody + (after.angularVelocity cross hub)
        val tipSpeed = maxOf(rotorSpeed, MIN_FLAP_SPEED) * params.rotor.radius
        val blowback = 2.0 * (4.0 / 3.0 * pitches.collective - out.inflowRatio)
        val omega = after.angularVelocity
        flapLon += (-omega.y + (-flapLon - pitches.longitudinal + blowback * v.x / tipSpeed) / tau) * dt
        flapLat += (-omega.x + (-flapLat + pitches.lateral - blowback * v.y / tipSpeed) / tau) * dt
        flapLon = flapLon.coerceIn(-MAX_FLAP, MAX_FLAP)
        flapLat = flapLat.coerceIn(-MAX_FLAP, MAX_FLAP)
    }

    override fun hashInto(hash: StateHash) {
        hash.add(mainInflow).add(flapLon).add(flapLat)
    }

    private companion object {
        const val MIN_FLAP_SPEED = 30.0 // rad/s; keeps the flapping time constant finite at standstill
        val MAX_FLAP = DMath.toRadians(15.0)
    }
}

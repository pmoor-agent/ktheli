package ktheli.physics.model

import ktheli.physics.Frames
import ktheli.physics.body.MassProperties
import ktheli.physics.body.RigidBodyState
import ktheli.physics.body.Wrench
import ktheli.physics.body.integrateRk4
import ktheli.physics.env.Environment
import ktheli.physics.math.DMath
import ktheli.physics.math.Mat3
import ktheli.physics.math.Vec3
import ktheli.physics.sim.Controls
import ktheli.physics.sim.StateHash
import kotlin.math.max

/**
 * Placeholder "helicopter" for Phase 1: thrust along the shaft proportional to collective (3D-style,
 * zero thrust at center stick) and an ideal rate controller standing in for an FBL unit. No rotor
 * aerodynamics yet; it exists to exercise input, integration, camera and rendering end-to-end.
 *
 * Roughly 450-size numbers.
 */
class ToyHeli(initial: RigidBodyState = parkedAt(Vec3.ZERO)) : AircraftModel {
    override var body: RigidBodyState = initial
        private set

    private val mp = MassProperties(0.75, Mat3.diagonal(0.010, 0.026, 0.022))
    private val maxThrust = 3.0 * mp.mass * Frames.GRAVITY
    private val maxRate = Vec3(DMath.toRadians(360.0), DMath.toRadians(360.0), DMath.toRadians(540.0))
    private val rateGain = 25.0 // 1/s, bandwidth of the ideal rate loop
    private val dragArea = 0.5 * 1.225 * 0.04 // ½ρ·CdA

    // Ground contact: one spring-damper at skid level below the CG, ~1 cm static sag, near-critical damping.
    private val groundStiffness = mp.mass * Frames.GRAVITY / 0.01
    private val groundDamping = 2.0 * DMath.sqrt(groundStiffness * mp.mass)
    private val groundFriction = 3.0 // N per m/s of sliding

    override fun step(controls: Controls, environment: Environment, dt: Double) {
        body = integrateRk4(body, mp, dt) { s -> wrench(s, controls, environment) }
    }

    private fun wrench(s: RigidBodyState, c: Controls, env: Environment): Wrench {
        val thrust = Vec3(0.0, 0.0, -maxThrust * c.collective)

        // Stick pitch forward = nose down = negative body pitch rate.
        val desiredRate = Vec3(c.roll * maxRate.x, -c.pitch * maxRate.y, c.yaw * maxRate.z)
        val rateError = desiredRate - s.angularVelocity
        val torque = mp.inertia * (rateError * rateGain)

        val v = s.velocity
        val air = v - env.windAt(s.position)
        val dragWorld = air * (-dragArea * air.length())

        val penetration = s.position.z + SKID_DROP // skid depth below ground, positive when touching
        var groundWorld = Vec3.ZERO
        if (penetration > 0.0) {
            val normal = max(0.0, groundStiffness * penetration + groundDamping * v.z)
            val slide = Vec3(v.x, v.y, 0.0) * -groundFriction
            groundWorld = Vec3(0.0, 0.0, -normal) + slide
        }

        val forceBody = thrust + s.attitude.inverseRotate(dragWorld + groundWorld)
        return Wrench(forceBody, torque)
    }

    override fun hashInto(hash: StateHash) {
        hash.add(body)
    }

    companion object {
        /** Distance from CG down to the skid bottoms, m. */
        const val SKID_DROP = 0.12

        /** Resting on the ground at the given world position (z is ignored). */
        fun parkedAt(p: Vec3) = RigidBodyState(position = Vec3(p.x, p.y, -SKID_DROP))
    }
}

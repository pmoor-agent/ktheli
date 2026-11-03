package ktheli.physics.body

import ktheli.physics.Frames
import ktheli.physics.math.Mat3
import ktheli.physics.math.Quat
import ktheli.physics.math.Vec3

data class MassProperties(val mass: Double, val inertia: Mat3) {
    val inverseInertia: Mat3 = inertia.inverse()
}

/**
 * Full 6-DOF state.
 *
 * @property position CG position in world (NED), m
 * @property velocity CG velocity in world (NED), m/s
 * @property attitude body → world rotation
 * @property angularVelocity body-frame angular rate (p, q, r), rad/s
 */
data class RigidBodyState(
    val position: Vec3 = Vec3.ZERO,
    val velocity: Vec3 = Vec3.ZERO,
    val attitude: Quat = Quat.IDENTITY,
    val angularVelocity: Vec3 = Vec3.ZERO,
) {
    val velocityBody: Vec3 get() = attitude.inverseRotate(velocity)
}

/** Force and moment about the CG, both in the body frame. Gravity is applied by the integrator. */
data class Wrench(val force: Vec3, val torque: Vec3) {
    operator fun plus(o: Wrench) = Wrench(force + o.force, torque + o.torque)

    companion object {
        val ZERO = Wrench(Vec3.ZERO, Vec3.ZERO)
    }
}

private data class Derivative(val dPos: Vec3, val dVel: Vec3, val dAtt: Quat, val dOmega: Vec3)

/**
 * Classic RK4 integration of the Newton–Euler equations.
 *
 * [wrench] is evaluated four times per step and must be a pure function of its arguments.
 */
fun integrateRk4(
    s: RigidBodyState,
    mp: MassProperties,
    dt: Double,
    wrench: (RigidBodyState) -> Wrench,
): RigidBodyState {
    fun deriv(x: RigidBodyState): Derivative {
        val w = wrench(x)
        val accel = x.attitude.rotate(w.force) / mp.mass + Vec3(0.0, 0.0, Frames.GRAVITY)
        val omega = x.angularVelocity
        val alpha = mp.inverseInertia * (w.torque - (omega cross (mp.inertia * omega)))
        return Derivative(x.velocity, accel, x.attitude.derivative(omega), alpha)
    }

    fun advance(d: Derivative, h: Double) = RigidBodyState(
        s.position + d.dPos * h,
        s.velocity + d.dVel * h,
        s.attitude + d.dAtt * h,
        s.angularVelocity + d.dOmega * h,
    )

    val k1 = deriv(s)
    val k2 = deriv(advance(k1, dt * 0.5))
    val k3 = deriv(advance(k2, dt * 0.5))
    val k4 = deriv(advance(k3, dt))
    val h6 = dt / 6.0
    return RigidBodyState(
        s.position + (k1.dPos + k2.dPos * 2.0 + k3.dPos * 2.0 + k4.dPos) * h6,
        s.velocity + (k1.dVel + k2.dVel * 2.0 + k3.dVel * 2.0 + k4.dVel) * h6,
        (s.attitude + (k1.dAtt + k2.dAtt * 2.0 + k3.dAtt * 2.0 + k4.dAtt) * h6).normalized(),
        s.angularVelocity + (k1.dOmega + k2.dOmega * 2.0 + k3.dOmega * 2.0 + k4.dOmega) * h6,
    )
}

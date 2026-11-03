package ktheli.physics

import ktheli.physics.body.MassProperties
import ktheli.physics.body.RigidBodyState
import ktheli.physics.body.Wrench
import ktheli.physics.body.integrateRk4
import ktheli.physics.math.Mat3
import ktheli.physics.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals

class RigidBodyTest {
    private val dt = 1.0 / 2000

    @Test
    fun `free fall matches analytic solution`() {
        val mp = MassProperties(2.0, Mat3.diagonal(1.0, 1.0, 1.0))
        var s = RigidBodyState(velocity = Vec3(3.0, 0.0, -5.0))
        val steps = 2000
        repeat(steps) { s = integrateRk4(s, mp, dt) { Wrench.ZERO } }
        val t = steps * dt
        assertEquals(3.0 * t, s.position.x, 1e-9)
        assertEquals(-5.0 * t + 0.5 * Frames.GRAVITY * t * t, s.position.z, 1e-9)
    }

    @Test
    fun `torque-free tumbling conserves energy and angular momentum`() {
        val inertia = Mat3.diagonal(0.01, 0.026, 0.022)
        val mp = MassProperties(1.0, inertia)
        // Spin near the unstable intermediate axis to get a non-trivial tumble.
        var s = RigidBodyState(angularVelocity = Vec3(0.1, 20.0, 0.1))

        fun energy(s: RigidBodyState) = 0.5 * (s.angularVelocity dot (inertia * s.angularVelocity))
        fun momentumWorld(s: RigidBodyState) = s.attitude.rotate(inertia * s.angularVelocity)

        val e0 = energy(s)
        val h0 = momentumWorld(s)
        repeat(20_000) { s = integrateRk4(s, mp, dt) { Wrench.ZERO } }

        assertEquals(e0, energy(s), e0 * 1e-6)
        assertEquals(0.0, (momentumWorld(s) - h0).length(), h0.length() * 1e-6)
    }
}

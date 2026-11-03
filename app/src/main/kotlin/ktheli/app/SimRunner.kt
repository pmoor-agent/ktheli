package ktheli.app

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Quat
import ktheli.physics.math.Vec3
import ktheli.physics.model.ToyHeli
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Simulation

/**
 * Bridges wall-clock time to the fixed-step simulation. This is the only place real time enters;
 * the simulation itself only ever sees (controls, tick).
 */
class SimRunner {
    var sim = newSim()
        private set
    private var previous = sim.aircraft.body
    private var accumulatorTicks = 0.0

    fun reset() {
        sim = newSim()
        previous = sim.aircraft.body
        accumulatorTicks = 0.0
    }

    /** Advances by [realSeconds] of wall time using [controls] for every tick in this frame. */
    fun advance(realSeconds: Double, controls: Controls) {
        accumulatorTicks += realSeconds.coerceAtMost(MAX_FRAME_SECONDS) * sim.tickRateHz
        while (accumulatorTicks >= 1.0) {
            previous = sim.aircraft.body
            sim.step(controls)
            accumulatorTicks -= 1.0
        }
    }

    /** State interpolated between the last two ticks, for smooth rendering at any frame rate. */
    fun renderState(): RigidBodyState {
        val a = previous
        val b = sim.aircraft.body
        val t = accumulatorTicks
        return RigidBodyState(
            position = a.position + (b.position - a.position) * t,
            attitude = nlerp(a.attitude, b.attitude, t),
            velocity = b.velocity,
            angularVelocity = b.angularVelocity,
        )
    }

    private fun nlerp(a: Quat, b: Quat, t: Double): Quat {
        val dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
        val bb = if (dot < 0) b * -1.0 else b
        return (a * (1 - t) + bb * t).normalized()
    }

    private companion object {
        const val MAX_FRAME_SECONDS = 0.1 // drop time rather than spiral after a hitch
        fun newSim() = Simulation(ToyHeli(ToyHeli.parkedAt(Vec3(6.0, 0.0, 0.0))))
    }
}

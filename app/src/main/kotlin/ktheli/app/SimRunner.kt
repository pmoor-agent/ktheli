package ktheli.app

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Quat
import ktheli.physics.math.Vec3
import ktheli.physics.heli.Helicopter
import ktheli.physics.model.AircraftModel
import ktheli.physics.model.ToyHeli
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Simulation

/**
 * Bridges wall-clock time to the fixed-step simulation. This is the only place real time enters;
 * the simulation itself only ever sees (controls, tick).
 */
class SimRunner {
    /** The aircraft types the simulator offers; [create] parks a fresh one in front of the pilot. */
    enum class AircraftType(val label: String, val create: () -> AircraftModel) {
        HELI_450("450 class (FBL, 3S)", { Helicopter(initial = Helicopter.parkedAt(START)) }),
        TOY("Toy heli (no rotor physics)", { ToyHeli(ToyHeli.parkedAt(START)) }),
    }

    var aircraftType = AircraftType.HELI_450
        private set
    var sim = newSim()
        private set
    private var previous = sim.aircraft.body
    private var accumulatorTicks = 0.0

    /** While paused, wall time is discarded; the simulation simply doesn't tick. */
    var paused = false

    fun select(type: AircraftType) {
        aircraftType = type
        reset()
    }

    fun reset() {
        sim = newSim()
        previous = sim.aircraft.body
        accumulatorTicks = 0.0
    }

    /** Advances by [realSeconds] of wall time using [controls] for every tick in this frame. */
    fun advance(realSeconds: Double, controls: Controls) {
        if (paused) return
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

    private fun newSim() = Simulation(aircraftType.create())

    private companion object {
        const val MAX_FRAME_SECONDS = 0.1 // drop time rather than spiral after a hitch
        val START = Vec3(6.0, 0.0, 0.0) // 6 m in front of the pilot
    }
}

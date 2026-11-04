package ktheli.app.session

import ktheli.physics.body.RigidBodyState
import ktheli.physics.env.Environment
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.math.Quat
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Crash
import ktheli.physics.sim.Simulation

/**
 * Bridges wall-clock time to the fixed-step simulation. This is the only place real time enters;
 * the simulation itself only ever sees (controls, tick).
 */
class SimRunner(
    model: HelicopterModel = HelicopterModels.DEFAULT,
    environment: EnvironmentSettings = EnvironmentSettings.CALM,
    field: FlyingField = FlyingFields.DEFAULT,
) {
    var model = model
        private set

    /** Where we fly; models are parked at its start position on reset. */
    var field = field
        private set

    /** Current environment; changing it applies immediately, as a simulation input. */
    var environment: EnvironmentSettings = environment
        set(value) {
            field = value
            sim.environment.settings = value
        }

    private var flights = 0L // seeds turbulence differently for each flight, reproducibly within a session
    var sim = newSim()
        private set
    private var previous = sim.aircraft.body
    private var accumulatorTicks = 0.0

    /** While paused, wall time is discarded; the simulation simply doesn't tick. */
    var paused = false

    /** The crash that ended this flight, if any: the simulation stays frozen at that moment until [reset]. */
    val crash: Crash? get() = sim.crash

    /** Wall-clock seconds since the crash (0 while flying). */
    var crashedSeconds = 0.0
        private set

    fun select(next: HelicopterModel) {
        model = next
        reset()
    }

    fun select(next: FlyingField) {
        field = next
        reset()
    }

    fun reset() {
        sim = newSim()
        previous = sim.aircraft.body
        accumulatorTicks = 0.0
        crashedSeconds = 0.0
    }

    /** Advances by [realSeconds] of wall time using [controls] for every tick in this frame; returns the ticks run. */
    fun advance(realSeconds: Double, controls: Controls): Int {
        if (sim.crash != null) {
            crashedSeconds += realSeconds
            return 0
        }
        if (paused) return 0
        accumulatorTicks += realSeconds.coerceAtMost(MAX_FRAME_SECONDS) * sim.tickRateHz
        var ticks = 0
        while (accumulatorTicks >= 1.0) {
            previous = sim.aircraft.body
            sim.step(controls)
            accumulatorTicks -= 1.0
            ticks++
            if (sim.crash != null) {
                // Show exactly the moment of impact.
                previous = sim.aircraft.body
                accumulatorTicks = 0.0
                break
            }
        }
        return ticks
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

    private fun newSim(): Simulation {
        val seed = ++flights
        return Simulation(model.create(field.start, seed), Environment(environment, seed = seed), obstacles = field.obstacles)
    }

    private companion object {
        const val MAX_FRAME_SECONDS = 0.1 // drop time rather than spiral after a hitch
    }
}

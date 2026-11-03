package ktheli.physics.sim

import ktheli.physics.env.Environment
import ktheli.physics.env.Obstacles
import ktheli.physics.model.AircraftModel

/**
 * Fixed-step simulation clock. Time is derived from the integer tick count, never accumulated, so it
 * cannot drift. The simulation knows nothing about wall-clock time; the caller decides how many ticks
 * to run per frame.
 *
 * Inputs are the per-tick [Controls] and changes to [Environment.settings]; with the seeds, they reproduce a
 * flight exactly.
 */
class Simulation(
    val aircraft: AircraftModel,
    val environment: Environment = Environment(),
    val tickRateHz: Int = DEFAULT_TICK_RATE_HZ,
    /** The flying field's trees, poles etc. */
    val obstacles: Obstacles = Obstacles.NONE,
) {
    val dt: Double = 1.0 / tickRateHz

    var tick: Long = 0
        private set

    val time: Double get() = tick.toDouble() / tickRateHz

    /** Set when the aircraft crashes; from then on the simulation is over and [step] does nothing. */
    var crash: Crash? = null
        private set

    fun step(controls: Controls) {
        if (crash != null) return
        val body = aircraft.body
        environment.step(body.position, body.velocity, dt)
        aircraft.step(controls, environment, dt)
        tick++
        aircraft.impact(obstacles)?.let { crash = Crash(tick, time, it) }
    }

    fun stateHash(): Long {
        val h = StateHash().add(tick)
        crash?.let { h.add(it.tick).add(it.impact.speed).add(it.impact.point) }
        aircraft.hashInto(h)
        environment.hashInto(h)
        return h.value
    }

    companion object {
        const val DEFAULT_TICK_RATE_HZ = 2000
    }
}

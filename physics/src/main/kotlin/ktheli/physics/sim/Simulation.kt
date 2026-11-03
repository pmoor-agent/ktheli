package ktheli.physics.sim

import ktheli.physics.model.AircraftModel

/**
 * Fixed-step simulation clock. Time is derived from the integer tick count, never accumulated, so it
 * cannot drift. The simulation knows nothing about wall-clock time; the caller decides how many ticks
 * to run per frame.
 */
class Simulation(val aircraft: AircraftModel, val tickRateHz: Int = DEFAULT_TICK_RATE_HZ) {
    val dt: Double = 1.0 / tickRateHz

    var tick: Long = 0
        private set

    val time: Double get() = tick.toDouble() / tickRateHz

    fun step(controls: Controls) {
        aircraft.step(controls, dt)
        tick++
    }

    fun stateHash(): Long {
        val h = StateHash().add(tick)
        aircraft.hashInto(h)
        return h.value
    }

    companion object {
        const val DEFAULT_TICK_RATE_HZ = 2000
    }
}

package ktheli.physics.model

import ktheli.physics.body.RigidBodyState
import ktheli.physics.env.Environment
import ktheli.physics.env.Obstacles
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Impact
import ktheli.physics.sim.StateHash

/** A named, live value for telemetry and displays. [group] collects related gauges into one chart. */
class Gauge(val group: String, val name: String, val unit: String, val read: () -> Double)

/**
 * A simulated aircraft. Implementations own all of their state (rigid body plus any internal states
 * such as rotor speed or inflow) and must advance it as a pure function of (state, controls, environment, dt).
 */
interface AircraftModel {
    val body: RigidBodyState

    /** Values worth plotting; read-only views of the model's state. */
    val gauges: List<Gauge> get() = emptyList()

    /** Advances one tick; [environment] has already been stepped for this tick (sample it with `windAt`). */
    fun step(controls: Controls, environment: Environment, dt: Double)

    /**
     * Checks the current state for a crash: a part that must not touch the ground or [obstacles] does, or a
     * landing too hard for the gear. Called after every tick; null while all is well.
     */
    fun impact(obstacles: Obstacles): Impact? = null

    /** Folds all internal state into [hash], for determinism checks. */
    fun hashInto(hash: StateHash)
}

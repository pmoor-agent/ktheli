package ktheli.physics.model

import ktheli.physics.body.RigidBodyState
import ktheli.physics.sim.Controls
import ktheli.physics.sim.StateHash

/** A named, live value for telemetry and displays. [group] collects related gauges into one chart. */
class Gauge(val group: String, val name: String, val unit: String, val read: () -> Double)

/**
 * A simulated aircraft. Implementations own all of their state (rigid body plus any internal states
 * such as rotor speed or inflow) and must advance it as a pure function of (state, controls, dt).
 */
interface AircraftModel {
    val body: RigidBodyState

    /** Values worth plotting; read-only views of the model's state. */
    val gauges: List<Gauge> get() = emptyList()

    fun step(controls: Controls, dt: Double)

    /** Folds all internal state into [hash], for determinism checks. */
    fun hashInto(hash: StateHash)
}

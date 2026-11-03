package ktheli.physics.model

import ktheli.physics.body.RigidBodyState
import ktheli.physics.sim.Controls
import ktheli.physics.sim.StateHash

/**
 * A simulated aircraft. Implementations own all of their state (rigid body plus any internal states
 * such as rotor speed or inflow) and must advance it as a pure function of (state, controls, dt).
 */
interface AircraftModel {
    val body: RigidBodyState

    fun step(controls: Controls, dt: Double)

    /** Folds all internal state into [hash], for determinism checks. */
    fun hashInto(hash: StateHash)
}

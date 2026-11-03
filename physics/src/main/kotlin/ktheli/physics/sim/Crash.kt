package ktheli.physics.sim

import ktheli.physics.math.Vec3

/** What broke, how fast it hit (m/s, the part's speed at contact), and where (world NED). */
data class Impact(val reason: String, val speed: Double, val point: Vec3)

/** A crash: the [impact] on [tick]; the simulation stops there. */
data class Crash(val tick: Long, val time: Double, val impact: Impact)

package ktheli.physics.sim

/**
 * Pilot stick positions after calibration, each in [-1, 1]. This is the only external input to the
 * simulation, so recording one [Controls] per tick (plus the initial state and seed) is a complete replay.
 *
 * Sign conventions: collective up, roll right, pitch forward (nose down), yaw right are positive.
 */
data class Controls(
    val collective: Double = 0.0,
    val roll: Double = 0.0,
    val pitch: Double = 0.0,
    val yaw: Double = 0.0,
    val throttle: Double = -1.0,
) {
    companion object {
        val NEUTRAL = Controls()
    }
}

package ktheli.physics.sim

/**
 * Everything the pilot does: stick positions after calibration (each in [-1, 1]) plus transmitter
 * switches. This is the only external input to the simulation, so recording one [Controls] per tick
 * (plus the initial state and seed) is a complete replay.
 *
 * Sign conventions: collective up, roll right, pitch forward (nose down), yaw right are positive.
 * Throttle is not a stick: on a helicopter the transmitter derives it from the collective stick
 * through the active flight mode's throttle curve.
 *
 * @property flightMode index into the transmitter's flight modes (0 = normal, 1 = idle-up 1, ...)
 * @property throttleHold motor off regardless of stick, as for autorotation practice
 */
data class Controls(
    val collective: Double = 0.0,
    val roll: Double = 0.0,
    val pitch: Double = 0.0,
    val yaw: Double = 0.0,
    val flightMode: Int = 0,
    val throttleHold: Boolean = false,
) {
    companion object {
        val NEUTRAL = Controls()
    }
}

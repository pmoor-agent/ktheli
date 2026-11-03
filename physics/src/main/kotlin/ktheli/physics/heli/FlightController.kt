package ktheli.physics.heli

import ktheli.physics.math.Vec3

/**
 * What a flight controller's sensors report: only what a real unit could measure.
 *
 * @property gyro body angular rates (p, q, r), rad/s, from a 3-axis MEMS gyroscope
 * @property accel specific force in the body frame, m/s², from a 3-axis accelerometer. This is acceleration
 *   minus gravity, so a heli at rest or in hover reads about (0, 0, -g): 1 g "up".
 * @property rotorRpm main rotor speed from an RPM sensor or ESC telemetry, if fitted
 */
data class SensorReadings(val gyro: Vec3, val accel: Vec3, val rotorRpm: Double?)

/** Three swashplate servo positions, normalized [-1, 1]; see [Ccpm120] for their arrangement. */
data class SwashCommand(val front: Double, val right: Double, val left: Double) {
    companion object {
        val CENTER = SwashCommand(0.0, 0.0, 0.0)
    }
}

/** The outputs of a flight controller, like its servo and ESC connectors: each normalized [-1, 1]. */
data class ServoCommands(val swash: SwashCommand, val tail: Double, val throttle: Double) {
    companion object {
        val IDLE = ServoCommands(SwashCommand.CENTER, 0.0, -1.0)
    }
}

/**
 * A flybarless unit or similar: reads sensors and receiver channels, drives servos and the ESC. Called at
 * the unit's own loop rate with a fixed [dt]; implementations keep their state internally and must be
 * deterministic.
 */
interface FlightController {
    fun update(sensors: SensorReadings, rx: ReceiverInputs, dt: Double): ServoCommands
}

/** A tail gyro: standalone or as part of a flight controller. Returns the tail servo position, [-1, 1]. */
interface TailGyro {
    fun update(sensors: SensorReadings, rx: ReceiverInputs, dt: Double): Double
}

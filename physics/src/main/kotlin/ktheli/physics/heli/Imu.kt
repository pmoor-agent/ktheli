package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.random.Pcg32

/** Turns true motion into what a MEMS gyro/accelerometer and an RPM sensor report, with seeded white noise. */
class Imu(seed: Long, private val gyroNoise: Double = 0.003, private val accelNoise: Double = 0.05) {
    val rng: Pcg32 = Pcg32.seeded(seed, stream = 0x1A2B3C4DL)

    fun read(angularVelocity: Vec3, specificForce: Vec3, rotorSpeed: Double): SensorReadings {
        fun noisy(v: Vec3, sigma: Double) =
            if (sigma == 0.0) v else Vec3(v.x + sigma * rng.nextGaussian(), v.y + sigma * rng.nextGaussian(), v.z + sigma * rng.nextGaussian())
        return SensorReadings(
            gyro = noisy(angularVelocity, gyroNoise),
            accel = noisy(specificForce, accelNoise),
            rotorRpm = rotorSpeed * 60.0 / DMath.TWO_PI,
        )
    }
}

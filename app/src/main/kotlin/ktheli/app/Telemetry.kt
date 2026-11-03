package ktheli.app

import ktheli.physics.body.RigidBodyState
import ktheli.physics.sim.Controls

/** Rolling window of per-frame samples for plotting. Display-only; nothing here feeds back into the sim. */
class Telemetry(private val windowSeconds: Double = 15.0) {
    class Series(val name: String) {
        internal val values = ArrayDeque<Double>()
        fun toArray() = values.toDoubleArray()
    }

    private val times = ArrayDeque<Double>()
    val altitude = Series("altitude [m]")
    val climbRate = Series("climb [m/s]")
    val speed = Series("speed [m/s]")
    val collective = Series("collective")
    val roll = Series("roll")
    val pitch = Series("pitch")
    val yaw = Series("yaw")
    private val all = listOf(altitude, climbRate, speed, collective, roll, pitch, yaw)

    val latestTime: Double get() = times.lastOrNull() ?: 0.0
    val window: Double get() = windowSeconds

    fun record(time: Double, body: RigidBodyState, controls: Controls) {
        if (times.isNotEmpty() && time <= times.last()) return // paused, or reset (cleared separately)
        times.addLast(time)
        altitude.values.addLast(-body.position.z)
        climbRate.values.addLast(-body.velocity.z)
        speed.values.addLast(body.velocity.length())
        collective.values.addLast(controls.collective)
        roll.values.addLast(controls.roll)
        pitch.values.addLast(controls.pitch)
        yaw.values.addLast(controls.yaw)
        while (times.first() < time - windowSeconds) {
            times.removeFirst()
            all.forEach { it.values.removeFirst() }
        }
    }

    fun timesArray() = times.toDoubleArray()

    fun clear() {
        times.clear()
        all.forEach { it.values.clear() }
    }
}

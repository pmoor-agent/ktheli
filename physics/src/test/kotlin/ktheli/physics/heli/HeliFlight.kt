package ktheli.physics.heli

import ktheli.physics.env.Environment
import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Simulation

/** Test helper: flies a [Helicopter] with scripted pilot inputs and offers a few read-outs. */
class HeliFlight(val heli: Helicopter = Helicopter(), environment: Environment = Environment()) {
    val sim = Simulation(heli, environment)
    val body get() = heli.body
    val altitude get() = -body.position.z - Helicopter.parkedAt(Vec3.ZERO).position.z.let { -it }
    val rpm get() = heli.rotorRpm

    /** Roll, pitch, yaw (rad) of the current attitude, aerospace Z-Y-X convention. */
    fun euler(): Vec3 {
        val q = body.attitude
        val roll = DMath.atan2(2 * (q.w * q.x + q.y * q.z), 1 - 2 * (q.x * q.x + q.y * q.y))
        val pitch = DMath.asin((2 * (q.w * q.y - q.z * q.x)).coerceIn(-1.0, 1.0))
        val yaw = DMath.atan2(2 * (q.w * q.z + q.x * q.y), 1 - 2 * (q.y * q.y + q.z * q.z))
        return Vec3(roll, pitch, yaw)
    }

    fun fly(seconds: Double, pilot: (HeliFlight) -> Controls) {
        repeat((seconds * sim.tickRateHz).toInt()) { sim.step(pilot(this)) }
    }

    fun fly(seconds: Double, controls: Controls) = fly(seconds) { controls }

    /**
     * A simple hover pilot in idle-up 1: collective holds [targetAltitude], cyclic levels the heli and
     * holds [forwardSpeed] (0: kills drift), yaw stick centered (the gyro holds heading). [base] supplies
     * switches.
     */
    fun hoverPilot(
        targetAltitude: Double,
        base: Controls = Controls(flightMode = 1),
        forwardSpeed: Double = 0.0,
    ): (HeliFlight) -> Controls = { f ->
        val e = f.euler()
        val v = f.body.velocityBody
        val climb = -f.body.velocity.z
        val collective = (0.36 + 0.25 * (targetAltitude - f.altitude) - 0.25 * climb).coerceIn(-0.2, 0.9)
        base.copy(
            collective = collective,
            roll = (-1.2 * e.x - 0.15 * v.y).coerceIn(-0.5, 0.5),
            pitch = (1.2 * e.y + 0.15 * (forwardSpeed - v.x)).coerceIn(-0.5, 0.5), // too fast: stick back
        )
    }

    fun deg(rad: Double) = DMath.toDegrees(rad)

    fun report(label: String) {
        val e = euler()
        val p = heli.bladePitches
        println(
            "%-22s t=%5.2f alt=%6.2f vz=%5.2f rpm=%5.0f roll=%5.1f pitch=%5.1f yawRate=%6.1f coll=%5.2f tail=%5.2f lat=%5.2f lon=%5.2f T=%5.2f".format(
                label, sim.time, altitude, -body.velocity.z, rpm, deg(e.x), deg(e.y), deg(body.angularVelocity.z),
                deg(p.collective), deg(p.tail), deg(p.lateral), deg(p.longitudinal), heli.mainThrust,
            ),
        )
    }
}

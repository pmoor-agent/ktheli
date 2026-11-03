package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.math.deg
import ktheli.physics.sim.Controls
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComponentsTest {
    @Test
    fun `curves interpolate between evenly spaced points`() {
        val c = Curve(0.0, 0.5, 0.6, 0.7, 1.0)
        assertEquals(0.0, c(-1.0))
        assertEquals(0.25, c(-0.75), 1e-12)
        assertEquals(0.6, c(0.0), 1e-12)
        assertEquals(1.0, c(2.0))
    }

    @Test
    fun `transmitter applies flight mode curves, throttle hold and expo`() {
        val tx = Transmitter(Transmitter.default3d())
        val normal = tx.receive(Controls(collective = 0.0))
        assertEquals(4.5 / 12.5, normal.collective, 1e-12)
        assertEquals(2 * 0.65 - 1, normal.throttle, 1e-12)
        assertEquals(0.6, normal.gain)

        val idleUp = tx.receive(Controls(collective = -1.0, flightMode = 1))
        assertEquals(-11.0 / 12.5, idleUp.collective, 1e-12)
        assertEquals(2 * 0.75 - 1, idleUp.throttle, 1e-12)

        val hold = tx.receive(Controls(collective = 1.0, flightMode = 1, throttleHold = true))
        assertEquals(-1.0, hold.throttle)
        assertEquals(12.0 / 12.5, hold.collective, 1e-12)

        assertEquals(1.0, Transmitter.expo(1.0, 0.3), 1e-12)
        assertTrue(Transmitter.expo(0.5, 0.3) < 0.5)
        assertEquals(tx.setup.modes.last(), tx.mode(Controls(flightMode = 9)))
    }

    @Test
    fun `CCPM mixing round-trips and clamps at the servo limits`() {
        val mixed = Ccpm120.mix(0.2, -0.3, 0.1)
        val back = Ccpm120.unmix(mixed)
        assertEquals(0.2, back.collective, 1e-12)
        assertEquals(-0.3, back.roll, 1e-12)
        assertEquals(0.1, back.pitch, 1e-12)
        val saturated = Ccpm120.mix(0.9, 0.0, -0.5) // front servo would need 1.4
        assertEquals(1.0, saturated.front)
    }

    @Test
    fun `servos update once per frame and move at their rated speed`() {
        val params = ServoParams(secondsPer60 = 0.1, frameRateHz = 200.0) // 600°/s over ±40° = 15 units/s
        val servo = Servo(params, tickRateHz = 2000)
        assertEquals(10, servo.frameTicks)
        servo.step(1.0, tick = 1, dt = 0.0005) // between frames: command ignored
        assertEquals(0.0, servo.position)
        repeat(20) { servo.step(1.0, tick = 10L + it, dt = 0.0005) }
        assertEquals(15.0 * 0.01, servo.position, 1e-9) // 10 ms at 15 units/s
    }

    @Test
    fun `governor holds headspeed against load and soft-starts`() {
        val a = Airframes.class450
        val dt = 1.0 / 2000
        val drivetrain = Drivetrain(a.motor, a.battery, a.esc)
        var omega = 0.0
        val throttle = 2 * 0.75 - 1
        var reached = Double.NaN
        repeat(20 * 2000) { i ->
            val load = if (i > 12 * 2000) 0.9 else 0.3 // N·m: hover, then a hard punch
            val out = drivetrain.step(throttle, omega, dt)
            omega += (out.rotorTorque - load) / a.mainRotor.inertia * dt
            if (reached.isNaN() && omega >= 0.98 * 0.75 * 3600 * DMath.TWO_PI / 60) reached = i * dt
        }
        val rpm = omega * 60 / DMath.TWO_PI
        println("spool-up to 98%% in %.1f s, rpm under load %.0f, current %.0f A".format(reached, rpm, drivetrain.motorCurrent))
        assertTrue(reached in 4.0..8.0, "soft start")
        assertEquals(2700.0, rpm, 0.03 * 2700)
    }

    @Test
    fun `throttle off freewheels the motor`() {
        val a = Airframes.class450
        val drivetrain = Drivetrain(a.motor, a.battery, a.esc)
        val out = drivetrain.step(-1.0, 280.0, 0.0005)
        assertEquals(0.0, out.rotorTorque)
        assertEquals(0.0, drivetrain.motorCurrent)
    }

    @Test
    fun `heading-hold gyro integrates, rate mode does not, and precomp is symmetric`() {
        val sensors = SensorReadings(Vec3(0.0, 0.0, -0.2), Vec3.ZERO, 2700.0) // yawing left, unwanted
        val hh = HeadingHoldGyro()
        val first = hh.update(sensors, ReceiverInputs.OFF.copy(gain = 0.5), 0.001)
        var later = first
        repeat(100) { later = hh.update(sensors, ReceiverInputs.OFF.copy(gain = 0.5), 0.001) }
        assertTrue(first > 0.0 && later > first, "pushes the nose right, harder over time")

        val rate = HeadingHoldGyro()
        val r1 = rate.update(sensors, ReceiverInputs.OFF.copy(gain = -0.5), 0.001)
        var r2 = r1
        repeat(100) { r2 = rate.update(sensors, ReceiverInputs.OFF.copy(gain = -0.5), 0.001) }
        assertEquals(r1, r2, 1e-12)
        assertTrue(!rate.headingHold)

        val still = SensorReadings(Vec3.ZERO, Vec3.ZERO, 2700.0)
        val up = RateGyro().update(still, ReceiverInputs.OFF.copy(collective = 0.8, gain = 0.5), 0.001)
        val down = RateGyro().update(still, ReceiverInputs.OFF.copy(collective = -0.8, gain = 0.5), 0.001)
        assertEquals(up, down, 1e-12)
        assertTrue(up > 0.0)
    }

    @Test
    fun `flybarless unit holds integrators at low rotor speed`() {
        val fbl = FlybarlessController()
        val disturbed = SensorReadings(Vec3(0.5, 0.0, 0.0), Vec3.ZERO, 300.0)
        val first = fbl.update(disturbed, ReceiverInputs.OFF, 0.001)
        var later = first
        repeat(200) { later = fbl.update(disturbed, ReceiverInputs.OFF, 0.001) }
        assertEquals(first.swash, later.swash) // no wind-up while the rotor is slow
    }

    @Test
    fun `parked helicopter rests on its skids`() {
        val f = HeliFlight()
        f.fly(2.0, Controls())
        assertEquals(0.0, f.altitude, 0.005)
        assertTrue(abs(f.deg(f.euler().x)) < 1.0 && abs(f.deg(f.euler().y)) < 1.0)
        assertEquals(1.0.deg, 1.0.deg)
    }
}

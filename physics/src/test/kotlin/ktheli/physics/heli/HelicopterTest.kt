package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.sim.Controls
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Flight tests, run against both main rotor models. */
abstract class HelicopterTest(private val factory: () -> Helicopter) {
    private fun HeliFlight() = HeliFlight(factory())

    @Test
    fun `spools up on the ground in normal mode and stays put`() {
        val f = HeliFlight()
        f.fly(1.0, Controls(collective = -1.0)) // throttle curve 0: motor off
        assertEquals(0.0, f.rpm, 1.0)
        f.fly(12.0, Controls(collective = -0.5)) // 55 % throttle: governor spools up softly
        f.report("normal spool-up")
        assertEquals(0.55 * 3600, f.rpm, 0.03 * 0.55 * 3600)
        assertTrue(f.altitude < 0.02, "still on the skids")
        assertTrue(abs(f.deg(f.euler().z)) < 10.0, "heading held on the ground")
    }

    @Test
    fun `hovers in idle-up 1 with realistic pitch and headspeed`() {
        val f = HeliFlight()
        f.fly(10.0, Controls(flightMode = 1)) // spool up at 0° pitch
        f.report("idle-up spooled")
        f.fly(10.0, f.hoverPilot(3.0))
        f.report("hover")
        val e = f.euler()
        assertEquals(3.0, f.altitude, 0.3)
        assertEquals(0.75 * 3600, f.rpm, 0.03 * 0.75 * 3600)
        // The tail rotor pushes left; the disc must tilt right to cancel it ("translating tendency").
        assertTrue(f.deg(e.x) in 1.0..7.0, "slightly right-skid low: ${f.deg(e.x)}")
        assertTrue(abs(f.deg(e.y)) < 5, "level in pitch")
        assertTrue(abs(f.deg(f.body.angularVelocity.z)) < 5, "no yaw")
        assertTrue(f.deg(f.heli.bladePitches.collective) in 3.0..5.5, "hover collective")
    }

    private fun hovering(): HeliFlight = HeliFlight().apply {
        fly(10.0, Controls(flightMode = 1))
        fly(10.0, hoverPilot(5.0))
    }

    @Test
    fun `roll rate follows the stick`() {
        val f = hovering()
        val pilot = f.hoverPilot(5.0)
        val commanded = DMath.toDegrees(Transmitter.expo(0.5, 0.25) * FlybarlessSettings().maxRollRate)
        var peak = 0.0
        var at150ms = 0.0
        val t0 = f.sim.time
        f.fly(0.4) { fl ->
            val p = fl.deg(fl.body.angularVelocity.x)
            peak = maxOf(peak, p)
            if (fl.sim.time - t0 < 0.15) at150ms = p
            pilot(fl).copy(roll = 0.5)
        }
        println("roll: commanded %.0f deg/s, at 150 ms %.0f, peak %.0f".format(commanded, at150ms, peak))
        assertTrue(at150ms > 0.75 * commanded, "fast response")
        assertTrue(peak < 1.3 * commanded, "limited overshoot")
    }

    @Test
    fun `yaw follows the stick and collective punches barely move the tail`() {
        val f = hovering()
        val pilot = f.hoverPilot(5.0)
        f.fly(0.8) { pilot(it).copy(yaw = 0.5) }
        val commanded = DMath.toDegrees(Transmitter.expo(0.5, 0.2) * TailGyroSettings().maxYawRate)
        val yawRate = f.deg(f.body.angularVelocity.z)
        println("yaw: commanded %.0f deg/s, actual %.0f".format(commanded, yawRate))
        assertEquals(commanded, yawRate, 0.15 * commanded)

        f.fly(2.0, pilot)
        var worst = 0.0
        val rpmBefore = f.rpm
        var lowestRpm = rpmBefore
        f.fly(0.6) { fl ->
            worst = maxOf(worst, abs(fl.deg(fl.body.angularVelocity.z)))
            lowestRpm = minOf(lowestRpm, fl.rpm)
            pilot(fl).copy(collective = 0.85)
        }
        f.report("after punch")
        println("punch: worst yaw rate %.1f deg/s, headspeed %.0f -> lowest %.0f".format(worst, rpmBefore, lowestRpm))
        assertTrue(worst < 40.0, "tail holds")
        assertTrue(lowestRpm > 0.85 * rpmBefore, "governor holds headspeed")
    }

    /** A full punch from hover: blade drag rises with angle of attack, the governor catches up. */
    @Test
    fun `full collective punch sags headspeed, release surges it`() {
        val f = hovering()
        val pilot = f.hoverPilot(5.0)
        val target = f.heli.governorTargetRpm
        var lowest = f.rpm
        f.fly(1.0) { fl ->
            lowest = minOf(lowest, fl.rpm)
            pilot(fl).copy(collective = 1.0)
        }
        val afterPunch = f.rpm
        var highest = 0.0
        f.fly(1.0) { fl ->
            highest = maxOf(highest, fl.rpm)
            pilot(fl)
        }
        println("punch: target %.0f, lowest %.0f, after 1 s %.0f, release peak %.0f".format(target, lowest, afterPunch, highest))
        assertTrue(lowest in 0.88 * target..0.96 * target, "noticeable sag")
        assertEquals(target, afterPunch, 0.03 * target) // governor caught up
        assertTrue(highest > 1.02 * target, "surge when the load comes off")
    }

    /**
     * Throttle hold in forward flight: the rotor is driven by the air coming up through the disc. (Vertical
     * autorotation sits in the vortex-ring/turbulent-wake region, where momentum theory has no valid
     * solution; that needs the empirical inflow model planned with vortex ring state.)
     */
    @Test
    fun `autorotation in forward flight keeps the rotor turning`() {
        val f = HeliFlight()
        f.fly(10.0, Controls(flightMode = 1))
        f.fly(10.0, f.hoverPilot(40.0))
        f.fly(10.0, f.hoverPilot(40.0, forwardSpeed = 9.0))
        f.report("forward flight")
        val rpm0 = f.rpm
        val pilot = f.hoverPilot(40.0, Controls(flightMode = 1, throttleHold = true), forwardSpeed = 9.0)
        f.fly(6.0) { pilot(it).copy(collective = -0.875) } // hold curve: -3° pitch, as pilots do to keep headspeed
        f.report("autorotation")
        val descent = f.body.velocity.z
        assertTrue(f.rpm > 0.75 * rpm0, "rotor kept turning: ${f.rpm}")
        assertTrue(descent in 3.0..10.0, "steady descent: $descent m/s")
    }

    @Test
    fun `throttle hold on the ground spins the rotor down within 20 seconds`() {
        for (stick in listOf(-1.0, -0.5, 0.0)) { // hold curve: -4°, 0°, +4° pitch
            val f = HeliFlight()
            f.fly(10.0, Controls(flightMode = 1))
            var t = 0.0
            while (f.rpm >= 1.0 && t < 30.0) {
                f.fly(0.5, Controls(flightMode = 1, throttleHold = true, collective = stick))
                t += 0.5
            }
            println("spin-down with hold stick %+.1f: %.1f s".format(stick, t))
            assertTrue(t in 8.0..20.0, "spin-down took $t s")
        }
    }

    @Test
    fun `identical runs are bit-identical`() {
        fun run(): Long {
            val f = HeliFlight()
            f.fly(3.0, Controls(flightMode = 1))
            f.fly(2.0, f.hoverPilot(2.0))
            return f.sim.stateHash()
        }
        assertEquals(run(), run())
    }
}

class DiscHelicopterTest : HelicopterTest({ Helicopter.disc450(seed = 1) })

class BladeElementHelicopterTest : HelicopterTest({ Helicopter.bladeElement450(seed = 1) })

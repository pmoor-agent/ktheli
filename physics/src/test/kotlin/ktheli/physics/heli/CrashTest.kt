package ktheli.physics.heli

import ktheli.physics.body.RigidBodyState
import ktheli.physics.env.Obstacle
import ktheli.physics.env.Obstacles
import ktheli.physics.math.DMath
import ktheli.physics.math.Quat
import ktheli.physics.math.Vec3
import ktheli.physics.sim.Controls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashTest {
    private val parked = Helicopter.parkedAt(Vec3.ZERO)
    private val hold = Controls(throttleHold = true)

    private fun at(altitude: Double, attitude: Quat = parked.attitude, velocity: Vec3 = Vec3.ZERO) =
        RigidBodyState(position = parked.position + Vec3(0.0, 0.0, -altitude), attitude = attitude, velocity = velocity)

    private fun flight(initial: RigidBodyState = parked, obstacles: Obstacles = Obstacles.NONE) =
        HeliFlight(Helicopter.bladeElement450(initial), obstacles = obstacles)

    private fun pitched(deg: Double) = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), DMath.toRadians(deg))
    private fun rolled(deg: Double) = Quat.fromAxisAngle(Vec3(1.0, 0.0, 0.0), DMath.toRadians(deg))

    @Test
    fun `spooling up, hovering and a gentle landing are not crashes`() {
        val f = flight()
        f.fly(10.0, Controls(flightMode = 1))
        f.fly(8.0, f.hoverPilot(2.0))
        f.fly(12.0) { g -> g.hoverPilot((2.0 - 0.4 * (g.sim.time - 18.0)).coerceAtLeast(-0.5))(g) } // ~0.4 m/s down
        f.report("landed")
        assertNull(f.sim.crash)
        assertTrue(f.altitude < 0.05)
    }

    @Test
    fun `a short drop onto the skids is fine`() {
        val f = flight(at(0.15))
        f.fly(2.0, hold)
        assertNull(f.sim.crash)
    }

    @Test
    fun `dropping from three metres breaks the gear`() {
        val f = flight(at(3.0))
        f.fly(2.0, hold)
        val crash = assertNotNull(f.sim.crash)
        assertEquals("Hard landing", crash.impact.reason)
        assertTrue(crash.impact.speed in 6.0..DMath.sqrt(2 * 9.80665 * 3.0), "sink rate ${crash.impact.speed} (free fall less drag)")
    }

    @Test
    fun `tipping over on the ground strikes the main rotor`() {
        val f = flight()
        f.fly(10.0, Controls(flightMode = 1)) // spooled up, 0 deg pitch, sitting on the skids
        f.fly(3.0, Controls(flightMode = 1, roll = 1.0)) // full right cyclic on the ground
        f.report("tipped")
        val crash = assertNotNull(f.sim.crash)
        assertEquals("Main rotor strike", crash.impact.reason)
        assertTrue(crash.impact.speed > 50.0, "blade tip speed ${crash.impact.speed}")
    }

    @Test
    fun `landing nose high hits the tail first`() {
        val f = flight(at(0.25, pitched(30.0)))
        f.fly(1.0, hold)
        val reason = assertNotNull(f.sim.crash).impact.reason
        assertTrue(reason.startsWith("Tail"), reason) // the lower fin or the tail rotor
    }

    @Test
    fun `rolled over on its side, the rotor or fuselage hits`() {
        val f = flight(at(0.3, rolled(80.0)))
        f.fly(1.0, hold)
        val reason = assertNotNull(f.sim.crash).impact.reason
        assertTrue(reason == "Main rotor strike" || reason == "Fuselage hit the ground", reason)
    }

    @Test
    fun `flying into a tree`() {
        val tree = Obstacles(listOf(Obstacle("a tree", x = 2.0, y = 0.0, radius = 1.0, bottom = 0.0, top = 12.0)))
        val f = flight(at(3.0, velocity = Vec3(6.0, 0.0, 0.0)), tree)
        f.fly(1.0, hold)
        val crash = assertNotNull(f.sim.crash)
        assertTrue("a tree" in crash.impact.reason, crash.impact.reason)
        assertTrue(f.sim.time < 0.2)
    }

    @Test
    fun `after a crash the simulation stands still`() {
        val f = flight(at(3.0))
        f.fly(2.0, hold)
        val crash = assertNotNull(f.sim.crash)
        val tick = f.sim.tick
        val hash = f.sim.stateHash()
        f.fly(1.0, hold)
        assertEquals(crash.tick, tick)
        assertEquals(tick, f.sim.tick)
        assertEquals(hash, f.sim.stateHash())
    }

    @Test
    fun `obstacle lookup`() {
        val o = Obstacles(
            listOf(
                Obstacle("trunk", -20.0, -20.0, 0.3, 0.0, 3.0),
                Obstacle("crown", -20.0, -20.0, 2.5, 3.0, 12.0),
                Obstacle("pole", 15.98, 0.1, 0.05, 0.0, 3.0),
            ),
        )
        assertEquals("trunk", o.hit(Vec3(-20.1, -20.1, -1.0))?.name)
        assertNull(o.hit(Vec3(-21.0, -20.0, -1.0))) // beside the trunk, under the crown
        assertEquals("crown", o.hit(Vec3(-22.0, -20.0, -5.0))?.name)
        assertNull(o.hit(Vec3(-20.0, -20.0, -12.5))) // above the tree
        assertEquals("pole", o.hit(Vec3(15.95, 0.1, -2.0))?.name)
        assertEquals("pole", o.hit(Vec3(16.01, 0.1, -2.0))?.name) // across a grid cell boundary (x = 16)
        assertNull(o.hit(Vec3(16.05, 0.1, -2.0)))
    }
}

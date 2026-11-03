package ktheli.physics.heli

import ktheli.physics.sim.Controls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelicopterSpecTest {
    @Test
    fun `specs build the configured rotor model`() {
        assertIs<BladeElementRotor>(HelicopterSpec.TREX_450.build().mainRotor)
        assertIs<FlappingDiscRotor>(HelicopterSpec.TREX_450_DISC.build().mainRotor)
        assertEquals(0.028, HelicopterSpec.TREX_450.build().airframe.tailRotor.position.y, 1e-12)
    }

    @Test
    fun `only the blade element rotor reports a blade azimuth, advancing with the rotor`() {
        assertNull(HelicopterSpec.TREX_450_DISC.build().mainRotor.bladeAzimuth)
        val f = HeliFlight(HelicopterSpec.TREX_450.build())
        f.fly(10.0, Controls(flightMode = 1))
        val a0 = f.heli.mainRotor.bladeAzimuth!!
        f.fly(0.001, Controls(flightMode = 1)) // two ticks
        val a1 = f.heli.mainRotor.bladeAzimuth!!
        val expected = f.heli.rotorRpm / 60.0 * 2 * Math.PI * 0.001
        assertEquals(expected, (a1 - a0 + 2 * Math.PI) % (2 * Math.PI), 0.01)
    }

    @Test
    fun `the gyro type and settings come from the spec`() {
        val rate = HelicopterSpec.TREX_450.copy(tailGyroType = TailGyroType.RATE).build()
        assertIs<RateGyro>((rate.controller as FlybarlessController).tailGyro)
        val hh = HelicopterSpec.TREX_450.build()
        assertIs<HeadingHoldGyro>((hh.controller as FlybarlessController).tailGyro)
        val fast = HelicopterSpec.TREX_450.copy(tailGyro = TailGyroSettings(maxYawRate = Math.toRadians(720.0))).build()
        fast.step(Controls(yaw = 1.0, flightMode = 1), ktheli.physics.env.Environment(), 0.0005)
        val commanded = fast.gauges.first { it.name == "commanded" }.read()
        assertTrue(commanded > 600.0, "commanded yaw follows the spec's max rate: $commanded")
    }
}

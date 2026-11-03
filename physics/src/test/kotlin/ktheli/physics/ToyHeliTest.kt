package ktheli.physics

import ktheli.physics.model.ToyHeli
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToyHeliTest {
    private fun Simulation.runFor(seconds: Double, c: Controls) = repeat((seconds * tickRateHz).toInt()) { step(c) }

    @Test
    fun `rests on skids with zero collective`() {
        val sim = Simulation(ToyHeli())
        sim.runFor(2.0, Controls.NEUTRAL)
        assertEquals(-ToyHeli.SKID_DROP + 0.01, sim.aircraft.body.position.z, 1e-4) // 1 cm static sag
    }

    @Test
    fun `hovers at one third collective and climbs above it`() {
        val hover = Simulation(ToyHeli())
        hover.runFor(2.0, Controls(collective = 1.0 / 3.0))
        assertTrue(hover.aircraft.body.position.z > -ToyHeli.SKID_DROP - 0.01, "should not climb at hover collective")

        val climb = Simulation(ToyHeli())
        climb.runFor(2.0, Controls(collective = 0.6))
        assertTrue(climb.aircraft.body.position.z < -5.0, "should climb well above ground")
    }
}

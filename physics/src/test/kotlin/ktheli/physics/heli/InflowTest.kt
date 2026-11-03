package ktheli.physics.heli

import ktheli.physics.math.DMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InflowTest {
    @Test
    fun `axial curve joins momentum theory in climb and windmill states`() {
        assertEquals(1.0, Inflow.axialRatio(0.0), 1e-12) // hover
        assertEquals(-0.5 + DMath.sqrt(1.25), Inflow.axialRatio(1.0), 1e-12) // climb at vh
        assertEquals(1.5 - DMath.sqrt(1.25), Inflow.axialRatio(-3.0), 1e-12) // windmill brake state
        assertEquals(1.0, Inflow.axialRatio(-2.0), 0.03) // empirical end meets the windmill branch
        assertEquals(1.0, Inflow.axialRatio(-1e-9), 1e-6)
        val peak = (0..200).maxOf { Inflow.axialRatio(-it / 100.0) }
        assertTrue(peak in 1.6..2.0, "induced velocity peaks in the vortex ring state: $peak")
    }

    @Test
    fun `steep descent reverses the flow through the disc, edgewise speed restores momentum theory`() {
        val density = 1.225
        val area = 0.407
        val thrust = 8.0
        val vh = Inflow.hoverInducedVelocity(thrust, density, area)
        val momentumHelicopterBranch = 1.9 * vh / 2 + DMath.sqrt(1.9 * 1.9 * vh * vh / 4 + vh * vh)
        val corrected = Inflow.correct(momentumHelicopterBranch, thrust, 1.9 * vh, 0.0, density, area)
        assertTrue(corrected < 1.9 * vh, "air flows up through the disc: vi $corrected < descent ${1.9 * vh}")
        assertEquals(momentumHelicopterBranch, Inflow.correct(momentumHelicopterBranch, thrust, 1.9 * vh, 2 * vh, density, area))
        assertTrue(Inflow.vortexRingIntensity(thrust, 0.9 * vh, 0.0, density, area) > 0.95)
        assertTrue(Inflow.vortexRingIntensity(thrust, 0.9 * vh, 1.5 * vh, density, area) == 0.0)
        assertTrue(Inflow.vortexRingIntensity(thrust, -1.0, 0.0, density, area) < 0.01) // climbing
    }
}

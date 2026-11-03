package ktheli.physics.heli

import ktheli.physics.sim.Controls
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class VortexRingTest {
    /** Holds a vertical speed with collective while keeping the heli level and over the spot. */
    private fun verticalPilot(climb: Double): (HeliFlight) -> Controls = { f ->
        val e = f.euler()
        val v = f.body.velocityBody
        Controls(
            flightMode = 1,
            collective = (0.36 + 0.12 * (climb - -f.body.velocity.z)).coerceIn(-0.6, 0.9),
            roll = (-1.2 * e.x - 0.15 * v.y).coerceIn(-0.5, 0.5),
            pitch = (1.2 * e.y - 0.15 * v.x).coerceIn(-0.5, 0.5),
        )
    }

    private class Run(val activity: Double, val thrustSwing: Double, val vortexRing: Double)

    private fun fly(climb: Double): Run {
        val f = HeliFlight(Helicopter.bladeElement450(seed = 4))
        f.fly(10.0, Controls(flightMode = 1))
        f.fly(10.0, f.hoverPilot(if (climb < 0) 120.0 else 5.0))
        val pilot = verticalPilot(climb)
        f.fly(4.0, pilot) // settle into the vertical speed
        var activity = 0.0
        var tMin = Double.MAX_VALUE
        var tMax = -Double.MAX_VALUE
        var vrs = 0.0
        val rotor = f.heli.mainRotor as BladeElementRotor
        f.fly(6.0) { fl ->
            activity += abs(fl.body.angularVelocity.x) + abs(fl.body.angularVelocity.y)
            tMin = minOf(tMin, fl.heli.mainThrust)
            tMax = maxOf(tMax, fl.heli.mainThrust)
            vrs = maxOf(vrs, rotor.vortexRing)
            pilot(fl)
        }
        return Run(activity, tMax - tMin, vrs)
    }

    @Test
    fun `slow vertical descent under power shudders like the vortex ring state`() {
        val climb = fly(2.5)
        val descent = fly(-2.5) // ~0.9 vh: deep in the vortex ring state
        println("climb 2.5 m/s: activity %.0f, thrust swing %.1f N, vrs %.2f | descent 2.5 m/s: activity %.0f, thrust swing %.1f N, vrs %.2f"
            .format(climb.activity, climb.thrustSwing, climb.vortexRing, descent.activity, descent.thrustSwing, descent.vortexRing))
        assertTrue(descent.vortexRing > 0.5) // the wobble itself adds some edgewise speed
        assertTrue(climb.vortexRing < 0.05)
        assertTrue(descent.activity > 3 * climb.activity, "wobbles")
        assertTrue(descent.thrustSwing > 2 * climb.thrustSwing, "unsteady thrust")
    }
}

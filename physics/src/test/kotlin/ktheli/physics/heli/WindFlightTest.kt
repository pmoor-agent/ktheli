package ktheli.physics.heli

import ktheli.physics.env.AtmosphereSettings
import ktheli.physics.env.Environment
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.env.WindSettings
import ktheli.physics.math.DMath
import ktheli.physics.sim.Controls
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class WindFlightTest {
    private fun hover(settings: EnvironmentSettings, gain: Double? = null): HeliFlight {
        val f = HeliFlight(Helicopter.bladeElement450(seed = 3), Environment(settings, seed = 5))
        f.fly(10.0, Controls(flightMode = 1))
        f.fly(12.0, f.hoverPilot(5.0))
        return f
    }

    @Test
    fun `hovering into a steady wind needs less collective and leans into it`() {
        val calm = hover(EnvironmentSettings.CALM)
        val windy = hover(EnvironmentSettings(WindSettings(speed = 7.0, fromDegrees = 0.0, turbulence = 0.0))) // headwind
        calm.report("calm hover")
        windy.report("hover in 7 m/s headwind")
        assertTrue(windy.heli.bladePitches.collective < calm.heli.bladePitches.collective - 0.5.let { DMath.toRadians(it) }, "translational lift")
        assertTrue(windy.euler().y < calm.euler().y - DMath.toRadians(1.0), "nose down into the wind")
    }

    @Test
    fun `in rate mode the fin weathervanes the nose into the wind`() {
        val settings = EnvironmentSettings(WindSettings(speed = 6.0, fromDegrees = 90.0, turbulence = 0.0)) // from the right
        val f = HeliFlight(
            Helicopter(
                mainRotor = BladeElementRotor(Airframes.class450.mainRotor),
                controller = FlybarlessController(FlybarlessSettings(cyclicPhaseDeg = Helicopter.BLADE_ELEMENT_PHASE)),
                transmitter = Transmitter(Transmitter.default3d().let { tx -> tx.copy(modes = tx.modes.map { it.copy(gain = -0.4) }) }),
            ),
            Environment(settings, seed = 5),
        )
        f.fly(10.0, Controls(flightMode = 1))
        f.fly(15.0, f.hoverPilot(5.0))
        val heading = DMath.toDegrees(f.euler().z)
        println("rate-mode heading after 15 s in wind from 090: %.0f deg".format(heading))
        assertTrue(heading > 30.0, "turned towards the wind")
    }

    @Test
    fun `turbulence makes the hover work`() {
        val smooth = hover(EnvironmentSettings(WindSettings(speed = 6.0, turbulence = 0.0)))
        val rough = hover(EnvironmentSettings(WindSettings(speed = 6.0, turbulence = 1.5, gustiness = 0.5)))
        fun busy(f: HeliFlight): Double {
            var sum = 0.0
            val pilot = f.hoverPilot(5.0)
            f.fly(10.0) { fl -> sum += abs(fl.body.angularVelocity.x) + abs(fl.body.angularVelocity.y); pilot(fl) }
            return sum
        }
        val a = busy(smooth)
        val b = busy(rough)
        println("attitude activity: smooth %.0f, turbulent %.0f".format(a, b))
        assertTrue(b > 2 * a)
    }

    /**
     * Thin air: more collective to hover and less punch. (Hover *power* barely changes for a lightly loaded
     * RC rotor: induced power rises like 1/√ρ but profile power falls with ρ.)
     */
    @Test
    fun `high and hot needs more collective and has less punch`() {
        val sea = hover(EnvironmentSettings.CALM)
        val high = hover(EnvironmentSettings(atmosphere = AtmosphereSettings(temperatureC = 35.0, elevation = 2000.0)))
        val hoverCollective = { f: HeliFlight -> DMath.toDegrees(f.heli.bladePitches.collective) }
        val seaHover = hoverCollective(sea)
        val highHover = hoverCollective(high)
        fun punch(f: HeliFlight): Double {
            val start = f.altitude
            val pilot = f.hoverPilot(5.0)
            f.fly(1.0) { pilot(it).copy(collective = 1.0) }
            return f.altitude - start
        }
        val seaClimb = punch(sea)
        val highClimb = punch(high)
        println("sea level: hover %.2f deg, 1 s punch %.1f m | 2000 m 35 C: hover %.2f deg, punch %.1f m".format(seaHover, seaClimb, highHover, highClimb))
        assertTrue(highHover > seaHover + 0.4)
        assertTrue(highClimb < 0.85 * seaClimb)
    }
}

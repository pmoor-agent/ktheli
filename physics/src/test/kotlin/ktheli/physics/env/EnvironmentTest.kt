package ktheli.physics.env

import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EnvironmentTest {
    private val dt = 1.0 / 2000
    private val at5m = Vec3(0.0, 0.0, -5.0)

    @Test
    fun `standard atmosphere density`() {
        assertEquals(1.225, AtmosphereSettings().density, 0.0005)
        assertEquals(1.007, AtmosphereSettings(temperatureC = 2.0, elevation = 2000.0).density, 0.005) // ISA at 2000 m
        assertTrue(AtmosphereSettings(temperatureC = 35.0, elevation = 2000.0).density < 0.95)
    }

    @Test
    fun `mean wind follows the height profile and blows from the given direction`() {
        val env = Environment(EnvironmentSettings(WindSettings(speed = 6.0, fromDegrees = 270.0, turbulence = 0.0)))
        val at10 = env.meanWind(10.0)
        assertEquals(0.0, at10.x, 1e-9)
        assertEquals(6.0, at10.y, 1e-9) // from the west: the air moves east
        assertEquals(6.0 * DMath.pow(0.25, 0.15), env.meanWind(2.5).length(), 1e-9)
        assertTrue(env.meanWind(0.0).length() > 0.0)
    }

    @Test
    fun `calm air is exactly still`() {
        val env = Environment()
        repeat(1000) { env.step(at5m, Vec3.ZERO, dt) }
        assertEquals(Vec3.ZERO, env.windAt(at5m))
        assertEquals(Vec3.ZERO, env.windAt(Vec3(3.0, -2.0, -1.0)))
    }

    private fun turbulent(seed: Long = 1) = Environment(EnvironmentSettings(WindSettings(speed = 5.0, fromDegrees = 0.0, turbulence = 1.0)), seed)

    @Test
    fun `turbulence has the Dryden intensity`() {
        val env = turbulent()
        val mean = env.meanWind(5.0)
        var sumU = 0.0
        var sumU2 = 0.0
        var sumW2 = 0.0
        val n = 600 * 2000
        repeat(n) {
            env.step(at5m, Vec3.ZERO, dt)
            val d = env.windAt(at5m) - mean
            sumU += d.x
            sumU2 += d.x * d.x
            sumW2 += d.z * d.z
        }
        val sigmaW = 0.1 * env.meanWind(6.1).length()
        val k = 0.177 + 0.000823 * 5.0 * 3.28084
        val sigmaU = sigmaW / DMath.pow(k, 0.4)
        val measuredU = DMath.sqrt(sumU2 / n - (sumU / n) * (sumU / n))
        val measuredW = DMath.sqrt(sumW2 / n)
        println("sigma_u expected %.3f measured %.3f; sigma_w expected %.3f measured %.3f".format(sigmaU, measuredU, sigmaW, measuredW))
        assertEquals(sigmaU, measuredU, 0.25 * sigmaU)
        assertEquals(sigmaW, measuredW, 0.25 * sigmaW)
        assertEquals(0.0, sumU / n, 0.2 * sigmaU)
    }

    @Test
    fun `turbulence is reproducible and depends on the seed`() {
        fun run(seed: Long): Vec3 {
            val env = turbulent(seed)
            repeat(20_000) { env.step(at5m, Vec3.ZERO, dt) }
            return env.windAt(at5m)
        }
        assertEquals(run(3), run(3))
        assertNotEquals(run(3), run(4))
    }

    @Test
    fun `a gust reaches points downstream later`() {
        val env = turbulent()
        val downstream = Vec3(-0.5, 0.0, -5.0) // wind from the north blows south (-x): 0.5 m downstream
        val delayTicks = (0.5 / env.meanWind(5.0).length() / dt).toInt()
        val atReference = ArrayList<Vec3>()
        repeat(20_000) {
            env.step(at5m, Vec3.ZERO, dt)
            atReference += env.windAt(at5m)
        }
        val later = env.windAt(downstream)
        val earlier = atReference[atReference.size - 1 - delayTicks]
        assertEquals(earlier.x, later.x, 0.05) // ±1 tick of delay quantization
        assertEquals(earlier.y, later.y, 0.05)
    }

    @Test
    fun `gusty wind brings gusts well above the mean`() {
        val env = Environment(EnvironmentSettings(WindSettings(speed = 6.0, turbulence = 0.0, gustiness = 1.0)))
        val mean = env.meanWind(5.0).length()
        var peak = 0.0
        repeat(120 * 2000) {
            env.step(at5m, Vec3.ZERO, dt)
            peak = maxOf(peak, env.windAt(at5m).length())
        }
        println("gusts: mean %.1f m/s, peak %.1f m/s".format(mean, peak))
        assertTrue(peak > 1.3 * mean)
    }
}

package ktheli.physics

import ktheli.physics.heli.Helicopter
import ktheli.physics.math.DMath
import ktheli.physics.model.ToyHeli
import ktheli.physics.random.Pcg32
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DeterminismTest {
    /** A busy, pseudo-random but reproducible stick script: take off, then wiggle everything. */
    private fun script(seed: Long, ticks: Int): List<Controls> {
        val rng = Pcg32.seeded(seed)
        var c = Controls(collective = 0.45)
        return List(ticks) { i ->
            if (i % 40 == 0) { // ~50 Hz, like a real input poll
                c = Controls(
                    collective = 0.35 + 0.2 * DMath.sin(i / 3000.0),
                    roll = rng.nextGaussian() * 0.3,
                    pitch = rng.nextGaussian() * 0.3,
                    yaw = rng.nextGaussian() * 0.3,
                )
            }
            c
        }
    }

    private fun run(controls: List<Controls>): Long {
        val sim = Simulation(ToyHeli())
        controls.forEach(sim::step)
        return sim.stateHash()
    }

    @Test
    fun `identical inputs produce bit-identical state`() {
        val controls = script(seed = 1234, ticks = 20_000)
        assertEquals(run(controls), run(controls))
    }

    @Test
    fun `different inputs produce different state`() {
        assertNotEquals(run(script(1, 4_000)), run(script(2, 4_000)))
    }

    /**
     * Golden hash: catches unintended changes to the physics (and any platform dependence once this test
     * runs on other machines). When you change the model on purpose, update the constant.
     */
    @Test
    fun `golden replay hash`() {
        val hash = run(script(seed = 42, ticks = 10_000))
        assertEquals(GOLDEN, hash, "Golden hash changed: 0x%016x".format(hash))
    }

    private fun flyHelicopter(heli: Helicopter): Long {
        val sim = Simulation(heli)
        val rng = Pcg32.seeded(99)
        var c = Controls(flightMode = 1)
        repeat(6_000) { i ->
            if (i % 40 == 0) c = c.copy(collective = if (i > 4000) 0.5 else 0.0, roll = 0.2 * rng.nextGaussian(), yaw = 0.2 * rng.nextGaussian())
            sim.step(c)
        }
        return sim.stateHash()
    }

    @Test
    fun `golden helicopter hashes`() {
        val disc = flyHelicopter(Helicopter.disc450(seed = 7))
        val blades = flyHelicopter(Helicopter.bladeElement450(seed = 7))
        assertEquals(GOLDEN_DISC, disc, "Golden disc helicopter hash changed: 0x%016x".format(disc))
        assertEquals(GOLDEN_BLADES, blades, "Golden blade element helicopter hash changed: 0x%016x".format(blades))
    }

    private companion object {
        val GOLDEN_DISC = 0xcfd26c9459dd9814uL.toLong()
        val GOLDEN_BLADES = 0x62a26f0860b6b7fcuL.toLong()
        val GOLDEN = 0x4944919a453d52f4uL.toLong()
    }
}

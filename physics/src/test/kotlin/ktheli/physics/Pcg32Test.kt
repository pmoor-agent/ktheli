package ktheli.physics

import ktheli.physics.random.Pcg32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Pcg32Test {
    @Test
    fun `matches reference implementation`() {
        // First outputs of the official pcg32-demo: pcg32_srandom_r(&rng, 42u, 54u)
        val rng = Pcg32.seeded(42, 54)
        val expected = listOf(0xa15c02b7, 0x7b47f409, 0xba1d3330, 0x83d2f293, 0xbfa4784b, 0xcbed606e)
        assertEquals(expected, List(6) { rng.nextInt().toLong() and 0xFFFFFFFFL })
    }

    @Test
    fun `restore continues the same sequence`() {
        val a = Pcg32.seeded(7)
        repeat(100) { a.nextInt() }
        val b = Pcg32.restore(a.state, a.increment)
        assertEquals(List(10) { a.nextDouble() }, List(10) { b.nextDouble() })
    }

    @Test
    fun `doubles are in unit interval`() {
        val rng = Pcg32.seeded(1)
        repeat(100_000) {
            val d = rng.nextDouble()
            assertTrue(d >= 0.0 && d < 1.0)
        }
    }
}

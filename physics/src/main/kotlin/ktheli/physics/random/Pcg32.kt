package ktheli.physics.random

import ktheli.physics.math.DMath

/**
 * PCG32 (XSH-RR) pseudo-random generator, see https://www.pcg-random.org.
 *
 * Fully specified here so results never depend on JDK implementation details. The complete state is two
 * longs, so it can be snapshotted and restored for replays.
 */
class Pcg32 private constructor(state: Long, val increment: Long) {
    var state: Long = state
        private set

    fun nextInt(): Int {
        val old = state
        state = old * MULTIPLIER + increment
        val xorShifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return xorShifted.rotateRight(rot)
    }

    /** Uniform in [0, 1) with 53 bits of precision. */
    fun nextDouble(): Double {
        val hi = (nextInt().toLong() and 0xFFFFFFFFL) ushr 5 // 27 bits
        val lo = (nextInt().toLong() and 0xFFFFFFFFL) ushr 6 // 26 bits
        return ((hi shl 26) or lo) * (1.0 / (1L shl 53))
    }

    /** Standard normal sample (Box–Muller, no cached second value so the state stays two longs). */
    fun nextGaussian(): Double {
        val u1 = 1.0 - nextDouble() // (0, 1]
        val u2 = nextDouble()
        return DMath.sqrt(-2.0 * DMath.ln(u1)) * DMath.cos(DMath.TWO_PI * u2)
    }

    fun copy() = Pcg32(state, increment)

    companion object {
        private const val MULTIPLIER = 6364136223846793005L

        /** Seeds exactly like the reference `pcg32_srandom_r(initstate, initseq)`. */
        fun seeded(seed: Long, stream: Long = 0x14057B7EF767814FL): Pcg32 {
            val rng = Pcg32(0L, (stream shl 1) or 1L)
            rng.nextInt()
            rng.state += seed
            rng.nextInt()
            return rng
        }

        fun restore(state: Long, increment: Long) = Pcg32(state, increment)
    }
}

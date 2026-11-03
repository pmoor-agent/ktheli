package ktheli.app.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeliSynthTest {
    /** A 450 hovering at 2700 rpm, heard from 3 m. */
    private val hover = SoundParams(
        bladePassHz = 90.0, rotorLevel = 0.35, rotorSharpness = 0.0, rotorNoise = 0.12,
        tailPassHz = 405.0, tailLevel = 0.15,
        motorHz = 1485.0, motorLevel = 0.1,
        gearHz = 6435.0, gearLevel = 0.02,
        volume = 0.7,
    )

    @Test
    fun `silence stays silent`() {
        val (left, right) = render(SoundParams.SILENT)
        assertEquals(0.0, rms(left))
        assertEquals(0.0, rms(right))
        val muted = render(hover.copy(volume = 0.0))
        assertEquals(0.0, rms(muted.first))
    }

    @Test
    fun `hover has the rotor thump, tail buzz and motor whine at their frequencies`() {
        val mono = render(hover).mono()
        fun ratio(on: Double, off: Double) = power(mono, on) / power(mono, off)
        assertTrue(ratio(90.0, 135.0) > 100, "main rotor blade passage: ${ratio(90.0, 135.0)}")
        assertTrue(ratio(405.0, 427.0) > 100, "tail rotor blade passage: ${ratio(405.0, 427.0)}")
        assertTrue(ratio(1485.0, 1507.0) > 100, "motor: ${ratio(1485.0, 1507.0)}")
        assertTrue(ratio(6435.0, 6415.0) > 10, "gear mesh: ${ratio(6435.0, 6415.0)}")
    }

    @Test
    fun `approaching raises the pitch`() {
        val mono = render(hover.copy(doppler = 1.05)).mono()
        assertTrue(power(mono, 1485.0 * 1.05) > 100 * power(mono, 1485.0))
        assertTrue(power(mono, 90.0 * 1.05) > 100 * power(mono, 90.0))
    }

    @Test
    fun `a model on the right is louder on the right`() {
        val (left, right) = render(hover.copy(pan = 1.0))
        assertTrue(rms(right) > 3 * rms(left), "${rms(right)} vs ${rms(left)}")
        val (l2, r2) = render(hover.copy(pan = -1.0))
        assertTrue(rms(l2) > 3 * rms(r2))
    }

    @Test
    fun `farther is quieter`() {
        val near = rms(render(hover).mono())
        val far = rms(render(hover.copy(distanceGain = 0.25)).mono())
        assertEquals(0.25, far / near, 0.03)
    }

    @Test
    fun `heavier loading sharpens the blade pulses`() {
        val rotorOnly = SoundParams(bladePassHz = 90.0, rotorLevel = 0.35, volume = 0.7)
        val soft = render(rotorOnly).mono()
        val slap = render(rotorOnly.copy(rotorSharpness = 1.0)).mono()
        // The 20th harmonic (1800 Hz): a sharper pulse has much more high-frequency content.
        assertTrue(power(slap, 1800.0) > 10 * power(soft, 1800.0), "${power(slap, 1800.0)} vs ${power(soft, 1800.0)}")
    }

    @Test
    fun `sudden changes glide instead of clicking, and stay within full scale`() {
        val synth = HeliSynth()
        val out = FloatArray(2 * 44_100)
        synth.render(SoundParams.SILENT, out, 4410)
        synth.render(hover.copy(volume = 1.0, rotorSharpness = 1.0, distanceGain = 2.0, windLevel = 0.16), out)
        var maxStep = 0.0
        for (i in 2 until out.size step 2) maxStep = maxOf(maxStep, abs(out[i] - out[i - 2]).toDouble())
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(maxStep < 0.5, "largest sample step $maxStep")
    }

    @Test
    fun `wind is heard on both sides`() {
        val (left, right) = render(SoundParams(windLevel = 0.1, volume = 1.0))
        assertTrue(rms(left) > 0.005 && rms(right) > 0.005, "${rms(left)} ${rms(right)}")
    }

    @Test
    fun `a crash is a loud bang that dies away`() {
        val synth = HeliSynth(rate)
        val quiet = SoundParams(volume = 0.8)
        val block = FloatArray(2 * rate / 10)
        synth.render(quiet, block) // first sight of impacts = 0: nothing to play
        synth.render(quiet.copy(impacts = 1, impactLevel = 1.0), block)
        val bang = rms(DoubleArray(block.size) { block[it].toDouble() })
        repeat(20) { synth.render(quiet.copy(impacts = 1, impactLevel = 1.0), block) } // 2 s later
        val later = rms(DoubleArray(block.size) { block[it].toDouble() })
        assertTrue(bang > 0.1, "bang $bang")
        assertTrue(later < bang / 20, "dies away: $later")
    }

    // --- helpers ---------------------------------------------------------------------------------

    private val rate = 44_100

    /** Renders 1.5 s and returns the last second (after the parameters have settled), left and right. */
    private fun render(params: SoundParams): Pair<DoubleArray, DoubleArray> {
        val synth = HeliSynth(rate)
        val warmup = FloatArray(rate)
        synth.render(params, warmup, rate / 2)
        val out = FloatArray(2 * rate)
        synth.render(params, out)
        return DoubleArray(rate) { out[2 * it].toDouble() } to DoubleArray(rate) { out[2 * it + 1].toDouble() }
    }

    private fun Pair<DoubleArray, DoubleArray>.mono() = DoubleArray(first.size) { (first[it] + second[it]) / 2 }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    /** Power at [hz] (Goertzel with a Hann window). */
    private fun power(x: DoubleArray, hz: Double): Double {
        val w = 2 * PI * hz / rate
        val c = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in x.indices) {
            val hann = 0.5 - 0.5 * cos(2 * PI * i / (x.size - 1))
            val s0 = x[i] * hann + c * s1 - s2
            s2 = s1
            s1 = s0
        }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }
}

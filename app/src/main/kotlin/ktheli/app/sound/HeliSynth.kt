package ktheli.app.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Synthesizes helicopter sound from [SoundParams], sample by sample, as interleaved stereo floats in -1..1.
 * Plain Kotlin without any audio API, so it can be tested and rendered to a file anywhere.
 *
 * Layers:
 * - **Main rotor:** a pulse per blade passage, made of a symmetric (thickness) and an antisymmetric (loading)
 *   part; sharper pulses when loaded or slapping. The two blades differ slightly, as real ones do.
 * - **Blade swish:** band-passed noise bursting as each blade sweeps past; loudest and brightest under load.
 * - **Downwash:** a low roar of the air the rotor moves.
 * - **Tail rotor:** softer, faster pulses: a buzz.
 * - **Motor:** electrical fundamental with harmonics; **gears:** a faint tooth-mesh tone.
 * - **Wind** at the listener: low rumbling noise with slow gusts.
 *
 * The model's sound is shifted by the Doppler factor, low-passed for air absorption, scaled for distance and
 * panned; everything goes through a soft limiter. All parameters glide over ~[GLIDE_SECONDS] so frame-rate
 * updates don't click.
 */
class HeliSynth(val sampleRate: Int = 44_100, seed: Long = 0x5EED) {
    private val glide = 1.0 - exp(-1.0 / (GLIDE_SECONDS * sampleRate))

    // Smoothed parameters.
    private var bladePassHz = 0.0
    private var rotorLevel = 0.0
    private var sharpness = 0.0
    private var rotorNoise = 0.0
    private var swishHz = 900.0
    private var washLevel = 0.0
    private var tailPassHz = 0.0
    private var tailLevel = 0.0
    private var motorHz = 0.0
    private var motorLevel = 0.0
    private var gearHz = 0.0
    private var gearLevel = 0.0
    private var windLevel = 0.0
    private var distanceGain = 1.0
    private var absorptionHz = 18_000.0
    private var doppler = 1.0
    private var pan = 0.0
    private var volume = 0.0

    // Oscillator phases, in cycles.
    private var mainPhase = 0.0
    private var secondBlade = false
    private var tailPhase = 0.0
    private var motorPhase = 0.0
    private var gearPhase = 0.0

    // Filters.
    private var swishLow = 0.0
    private var swishBand = 0.0
    private var wash1 = 0.0
    private var wash2 = 0.0
    private var absorbed = 0.0
    private var windLeft = 0.0
    private var windRight = 0.0
    private var gust = 0.0
    private var gustTarget = 0.0
    private var gustCountdown = 0

    private var noise = seed or 1L

    /** Renders [frames] stereo frames into [out] (`out[2i]` left, `out[2i+1]` right), gliding towards [target]. */
    fun render(target: SoundParams, out: FloatArray, frames: Int = out.size / 2) {
        require(out.size >= 2 * frames)
        val nyquist = 0.45 * sampleRate
        for (i in 0 until frames) {
            glideTowards(target)

            // Main rotor: a pulse per blade passage. Width as a share of the passage period.
            val width = 0.10 - 0.07 * sharpness
            mainPhase += bladePassHz * doppler / sampleRate
            if (mainPhase >= 1.0) {
                mainPhase -= 1.0
                secondBlade = !secondBlade
            }
            val x = mainPhase - 0.5
            val g = exp(-0.5 * (x / width) * (x / width))
            val thickness = g - width * SQRT_2PI
            val loading = -(x / width) * g
            val blade = if (secondBlade) 1.0 - BLADE_MISMATCH else 1.0 + BLADE_MISMATCH
            val rotor = rotorLevel * blade * (0.6 * thickness + 0.5 * (0.5 + sharpness) * loading)

            // Blade swish: each blade sweeping past pushes a burst of air. Band-passed noise (state-variable
            // filter), strongly modulated at the blade-passage rate: the "whop-whop" of the air itself.
            val sx = x / SWISH_WIDTH
            val sweep = exp(-0.5 * sx * sx)
            val f = 2.0 * sin(PI * minOf(swishHz * doppler, nyquist) / sampleRate)
            swishLow += f * swishBand
            val high = white() - swishLow - SWISH_DAMPING * swishBand
            swishBand += f * high
            val swish = rotorNoise * blade * SWISH_GAIN * swishBand * (0.15 + 0.85 * sweep)

            // Downwash: a low roar of the air being moved, pulsing gently with the blades.
            wash1 += WASH_LOWPASS * (white() - wash1)
            wash2 += WASH_LOWPASS * (wash1 - wash2)
            val wash = washLevel * WASH_GAIN * wash2 * (0.7 + 0.3 * sweep)

            // Tail rotor buzz.
            tailPhase = wrap(tailPhase + tailPassHz * doppler / sampleRate)
            val tx = tailPhase - 0.5
            val tg = exp(-0.5 * (tx / TAIL_WIDTH) * (tx / TAIL_WIDTH))
            val tail = tailLevel * (tg - TAIL_WIDTH * SQRT_2PI + 0.3 * sin(TWO_PI * tailPhase))

            // Motor whine with harmonics (dropped above Nyquist), and gear mesh.
            val mf = motorHz * doppler
            motorPhase = wrap(motorPhase + mf / sampleRate)
            var motor = sin(TWO_PI * motorPhase)
            if (2 * mf < nyquist) motor += 0.35 * sin(2 * TWO_PI * motorPhase)
            if (6 * mf < nyquist) motor += 0.15 * sin(6 * TWO_PI * motorPhase)
            motor *= motorLevel
            val gf = gearHz * doppler
            gearPhase = wrap(gearPhase + gf / sampleRate)
            val gear = if (gf < nyquist) gearLevel * (0.85 + 0.15 * g) * sin(TWO_PI * gearPhase) else 0.0

            // Air absorption, then distance and panning (equal power, never fully one-sided).
            val a = 1.0 - exp(-TWO_PI * minOf(absorptionHz, nyquist) / sampleRate)
            absorbed += a * (rotor + swish + wash + tail + motor + gear - absorbed)
            val angle = (1.0 + PAN_WIDTH * pan) * PI / 4.0
            val model = absorbed * distanceGain * MODEL_GAIN
            var left = model * cos(angle) * SQRT2
            var right = model * sin(angle) * SQRT2

            // Wind at the listener: low-passed noise, slowly gusting, decorrelated left and right.
            if (windLevel > 1e-6) {
                if (--gustCountdown <= 0) {
                    gustTarget = 0.4 + 0.6 * abs(white())
                    gustCountdown = sampleRate / 2
                }
                gust += GUST_RATE * (gustTarget - gust)
                windLeft += WIND_LOWPASS * (white() - windLeft)
                windRight += WIND_LOWPASS * (white() - windRight)
                left += windLevel * gust * windLeft * WIND_GAIN
                right += windLevel * gust * windRight * WIND_GAIN
            }

            out[2 * i] = limit(volume * left)
            out[2 * i + 1] = limit(volume * right)
        }
    }

    private fun glideTowards(t: SoundParams) {
        val k = glide
        bladePassHz += k * (t.bladePassHz - bladePassHz)
        rotorLevel += k * (t.rotorLevel - rotorLevel)
        sharpness += k * (t.rotorSharpness.coerceIn(0.0, 1.0) - sharpness)
        rotorNoise += k * (t.rotorNoise - rotorNoise)
        swishHz += k * (t.swishHz - swishHz)
        washLevel += k * (t.washLevel - washLevel)
        tailPassHz += k * (t.tailPassHz - tailPassHz)
        tailLevel += k * (t.tailLevel - tailLevel)
        motorHz += k * (t.motorHz - motorHz)
        motorLevel += k * (t.motorLevel - motorLevel)
        gearHz += k * (t.gearHz - gearHz)
        gearLevel += k * (t.gearLevel - gearLevel)
        windLevel += k * (t.windLevel - windLevel)
        distanceGain += k * (t.distanceGain - distanceGain)
        absorptionHz += k * (t.absorptionHz - absorptionHz)
        doppler += k * (t.doppler - doppler)
        pan += k * (t.pan.coerceIn(-1.0, 1.0) - pan)
        volume += k * (t.volume.coerceIn(0.0, 1.0) - volume)
    }

    /** Uniform noise in -1..1 (xorshift64*); its own generator, so the physics' random streams stay untouched. */
    private fun white(): Double {
        noise = noise xor (noise ushr 12)
        noise = noise xor (noise shl 25)
        noise = noise xor (noise ushr 27)
        val bits = (noise * 0x2545F4914F6CDD1DL) ushr 11
        return bits * (2.0 / (1L shl 53)) - 1.0
    }

    private fun wrap(phase: Double) = if (phase >= 1.0) phase - 1.0 else phase

    /** Soft limiter: linear up to ~0.5, never beyond ±1. */
    private fun limit(x: Double): Float = (if (abs(x) < 0.5) x else tanh(x)).toFloat()

    companion object {
        const val GLIDE_SECONDS = 0.03
        private const val TWO_PI = 2.0 * PI
        private val SQRT_2PI = sqrt(2.0 * PI)
        private val SQRT2 = sqrt(2.0)
        private const val BLADE_MISMATCH = 0.06 // tracking/balance difference between the two blades
        private const val SWISH_DAMPING = 1.2 // state-variable filter 1/Q: a broad band
        private const val SWISH_WIDTH = 0.16 // share of the blade-passage period each blade's burst lasts
        private const val SWISH_GAIN = 4.0 // the band-pass keeps ~1/4 of the noise's amplitude
        private const val WASH_LOWPASS = 0.025 // two one-pole stages, ~180 Hz
        private const val WASH_GAIN = 12.0
        private const val TAIL_WIDTH = 0.14
        private const val PAN_WIDTH = 0.7
        private const val MODEL_GAIN = 2.0
        private const val GUST_RATE = 2e-5
        private const val WIND_LOWPASS = 0.03 // ~210 Hz at 44.1 kHz
        private const val WIND_GAIN = 6.0 // the low-pass takes most of the noise's energy away
    }
}

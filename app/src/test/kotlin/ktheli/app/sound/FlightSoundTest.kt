package ktheli.app.sound

import ktheli.app.HelicopterModels
import ktheli.app.SimRunner
import ktheli.physics.heli.Helicopter
import ktheli.physics.math.Vec3
import ktheli.physics.sim.Controls
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Flies a short script with the real physics, maps it through [HeliAcoustics] like the app does, and writes
 * what it sounds like from the pilot's position to `app/build/sound/flight.wav`.
 */
class FlightSoundTest {
    private val pilot = Vec3(-6.0, 0.0, -1.7)

    private class Moment(val label: String, val params: SoundParams, val source: HeliAcoustics.Source)

    @Test
    fun `a scripted flight sounds like it should and is written to a WAV file`() {
        val runner = SimRunner(HelicopterModels.TREX_450)
        val synth = HeliSynth()
        val frame = 1.0 / 60
        val frameSamples = synth.sampleRate / 60
        val buffer = FloatArray(2 * frameSamples)
        val audio = ArrayList<FloatArray>()
        val moments = HashMap<String, Moment>() // last frame of each segment
        val peaks = HashMap<String, Moment>() // loudest swish of each segment

        fun fly(seconds: Double, label: String, controls: Controls) {
            repeat((seconds / frame).toInt()) {
                runner.advance(frame, controls)
                val body = runner.renderState()
                val listener = Listener.facing(pilot, body.position)
                val source = HeliAcoustics.source(runner.sim.aircraft)
                val params = HeliAcoustics.params(source, body.position, body.velocity, listener, ambientWind = 0.0, volume = 0.8)
                synth.render(params, buffer)
                audio += buffer.copyOf()
                moments[label] = Moment(label, params, source)
                if (params.rotorNoise > (peaks[label]?.params?.rotorNoise ?: -1.0)) peaks[label] = Moment(label, params, source)
            }
        }

        val idleUp = Controls(flightMode = 1)
        fly(1.0, "parked", Controls(throttleHold = true))
        fly(9.0, "spooled", idleUp)
        fly(3.0, "lift", idleUp.copy(collective = 0.45))
        fly(0.8, "punch", idleUp.copy(collective = 1.0))
        fly(1.5, "ease", idleUp.copy(collective = 0.3))
        fly(1.5, "dive", idleUp.copy(collective = -0.4))
        fly(0.6, "pull", idleUp.copy(collective = 1.0))
        fly(1.0, "level", idleUp.copy(collective = 0.35))
        fly(4.0, "hold", idleUp.copy(collective = 0.1, throttleHold = true))

        val heli = assertIs<Helicopter>(runner.sim.aircraft)
        val parked = moments.getValue("parked").params
        assertEquals(0.0, parked.rotorLevel + parked.motorLevel + parked.tailLevel)

        val spooled = moments.getValue("spooled").params
        assertEquals(90.0, spooled.bladePassHz, 3.0) // 2 blades x 2700 rpm
        assertEquals(2700.0 * 11 / 60 * 3, spooled.motorHz, 50.0) // 11:1 gearing, 3 pole pairs
        assertEquals(2 * 2700.0 * 4.5 / 60, spooled.tailPassHz, 10.0)
        assertTrue(spooled.motorLevel > 0.04)

        val punch = moments.getValue("punch")
        val lift = moments.getValue("lift")
        assertTrue(punch.source.loading > lift.source.loading, "punch loads the rotor")
        assertTrue(punch.params.rotorLevel > lift.params.rotorLevel * 1.2, "${punch.params.rotorLevel} vs ${lift.params.rotorLevel}")
        assertTrue(punch.params.rotorSharpness > lift.params.rotorSharpness)
        assertTrue(punch.params.motorLevel > lift.params.motorLevel, "the motor works harder")

        // Pulling out of a dive loads the blades hard: a loud, bright swoosh and more downwash.
        val pull = peaks.getValue("pull")
        assertTrue(pull.source.loading > 1.8, "pull-up loading ${pull.source.loading}")
        assertTrue(pull.params.rotorNoise > 3 * lift.params.rotorNoise, "${pull.params.rotorNoise} vs ${lift.params.rotorNoise}")
        assertTrue(pull.params.swishHz > lift.params.swishHz)
        assertTrue(pull.params.washLevel > 2 * lift.params.washLevel)

        val hold = moments.getValue("hold").params
        assertTrue(hold.motorLevel < 1e-3 && hold.gearLevel < 1e-3, "motor unpowered in throttle hold: ${hold.motorLevel}")
        assertTrue(hold.rotorLevel > 0.01, "the rotor still turns")
        assertTrue(heli.rotorRpm < 2600.0)

        val out = Paths.get("build/sound/flight.wav")
        writeWav(out, audio, synth.sampleRate)
        assertTrue(Files.size(out) > 44 + 4 * synth.sampleRate * 22)
    }

    @Test
    fun `the listener hears direction and motion`() {
        val source = HeliAcoustics.source(HelicopterModels.TOY.create(Vec3.ZERO, 1))
        val listener = Listener.facing(pilot, Vec3.ZERO) // looking north at the origin: east is right
        assertEquals(0.0, (listener.right - Vec3(0.0, 1.0, 0.0)).length(), 1e-12)
        val east = HeliAcoustics.params(source, Vec3(-6.0, 5.0, -2.0), Vec3.ZERO, listener, 0.0, 1.0)
        assertTrue(east.pan > 0.9)
        val approaching = HeliAcoustics.params(source, Vec3(0.0, 0.0, -1.7), Vec3(-20.0, 0.0, 0.0), listener, 0.0, 1.0)
        assertEquals(343.0 / (343.0 - 20.0), approaching.doppler, 1e-9)
        assertEquals(5.0 / 6.0, approaching.distanceGain, 1e-9) // 5 m reference at 6 m
        val far = HeliAcoustics.params(source, Vec3(100.0, 0.0, -10.0), Vec3.ZERO, listener, 0.0, 1.0)
        assertTrue(far.distanceGain < 0.05 && far.absorptionHz < 5000.0)
        val windy = HeliAcoustics.params(source, Vec3.ZERO, Vec3.ZERO, listener, ambientWind = 8.0, volume = 1.0)
        assertTrue(windy.windLevel > 0.05)
    }

    private fun writeWav(path: Path, chunks: List<FloatArray>, sampleRate: Int) {
        val samples = chunks.sumOf { it.size }
        val data = ByteBuffer.allocate(44 + 2 * samples).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + 2 * samples).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate)
            .putInt(sampleRate * 4).putShort(4).putShort(16)
        data.put("data".toByteArray()).putInt(2 * samples)
        for (chunk in chunks) for (s in chunk) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        Files.createDirectories(path.toAbsolutePath().parent)
        Files.write(path, data.array())
    }
}

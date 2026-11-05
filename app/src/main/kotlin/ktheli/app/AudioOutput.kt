package ktheli.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.audio.AudioDevice
import ktheli.app.sound.HeliSynth
import ktheli.app.sound.SoundParams

/**
 * Streams a [HeliSynth] to the sound card on its own thread. The render thread hands over new [params] each
 * frame; the audio thread picks up the latest whenever it renders the next small chunk, and the synth glides
 * between them.
 *
 * Latency is set by the OpenAL buffers configured in `Main.kt` (about 50 ms).
 */
class AudioOutput private constructor(private val device: AudioDevice) : AutoCloseable {
    @Volatile
    var params: SoundParams = SoundParams.SILENT

    @Volatile
    private var running = true
    private val synth = HeliSynth(SAMPLE_RATE)

    private val thread = Thread(::run, "ktheli-audio").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
        start()
    }

    private fun run() {
        val buffer = FloatArray(2 * CHUNK_FRAMES)
        val start = System.nanoTime()
        var written = 0L
        try {
            while (running) {
                synth.render(params, buffer, CHUNK_FRAMES)
                device.writeSamples(buffer, 0, buffer.size) // blocks while the device's buffers are full
                written += CHUNK_FRAMES
                // A device that doesn't block (no real output) must not make us spin.
                val ahead = written.toDouble() / SAMPLE_RATE - (System.nanoTime() - start) / 1e9
                if (ahead > MAX_AHEAD_SECONDS) Thread.sleep(((ahead - MAX_AHEAD_SECONDS / 2) * 1000).toLong())
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Gdx.app?.error("ktheli", "Audio output stopped", e)
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
        thread.join(500)
        device.dispose()
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHUNK_FRAMES = 256 // 5.8 ms: one OpenAL buffer of 1024 bytes
        private const val MAX_AHEAD_SECONDS = 0.2

        /** Opens the default sound output, or returns null if there is none. */
        fun open(): AudioOutput? = runCatching {
            val audio = Gdx.audio ?: return null
            if (audio.javaClass.simpleName.contains("Mock")) return null // OpenAL failed to start
            AudioOutput(audio.newAudioDevice(SAMPLE_RATE, false))
        }.onFailure { Gdx.app?.error("ktheli", "No audio output", it) }.getOrNull()
    }
}

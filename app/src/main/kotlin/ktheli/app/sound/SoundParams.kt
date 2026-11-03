package ktheli.app.sound

import kotlinx.serialization.Serializable

/** Sound preferences, persisted with the other settings. */
@Serializable
data class SoundSettings(
    val enabled: Boolean = true,
    /** Master volume, 0..1. */
    val volume: Double = 0.7,
)

/**
 * What the [HeliSynth] should sound like right now: frequencies in Hz as emitted by the model, levels as
 * linear amplitudes (roughly 0..1), plus how the sound reaches the listener. The synth glides towards new
 * values, so they can be updated once per rendered frame.
 */
data class SoundParams(
    /** Main rotor blade passages per second (blades x rev/s): the "thump" of the rotor. */
    val bladePassHz: Double = 0.0,
    /** Blade pulses (thickness and loading noise). */
    val rotorLevel: Double = 0.0,
    /** 0 = soft pulses, 1 = sharp "slap" (heavy loading, blades hitting their own wake). */
    val rotorSharpness: Double = 0.0,
    /**
     * Broadband swish of the blades: each blade sweeping past pushes a burst of air. Grows steeply with tip
     * speed and blade loading: the "swoosh" of a hard pull.
     */
    val rotorNoise: Double = 0.0,
    /** Centre of the swish's frequency band; brighter with tip speed and loading. */
    val swishHz: Double = 900.0,
    /** Low roar of the downwash: the air the rotor moves. */
    val washLevel: Double = 0.0,
    /** Tail rotor blade passages per second: a buzz. */
    val tailPassHz: Double = 0.0,
    val tailLevel: Double = 0.0,
    /** Brushless motor electrical frequency (rev/s x pole pairs): the whine. */
    val motorHz: Double = 0.0,
    val motorLevel: Double = 0.0,
    /** Pinion-to-main-gear tooth mesh frequency. */
    val gearHz: Double = 0.0,
    val gearLevel: Double = 0.0,
    /** Wind noise at the listener (not affected by distance or panning). */
    val windLevel: Double = 0.0,
    /** Amplitude factor for the distance to the listener. */
    val distanceGain: Double = 1.0,
    /** Air absorption: low-pass cutoff, lower when far away. */
    val absorptionHz: Double = 18_000.0,
    /** Doppler frequency factor: > 1 approaching, < 1 receding. */
    val doppler: Double = 1.0,
    /** Where the model is from the listener: -1 left .. 1 right. */
    val pan: Double = 0.0,
    /** Master volume; 0 when muted or paused. */
    val volume: Double = 0.0,
) {
    companion object {
        val SILENT = SoundParams()
    }
}

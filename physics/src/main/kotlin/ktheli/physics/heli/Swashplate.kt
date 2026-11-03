package ktheli.physics.heli

/**
 * 120° CCPM: three servos at 0° (front), 120° (right rear) and 240° (left rear), azimuth measured clockwise
 * from the nose seen from above. All values in normalized servo units.
 *
 * Swashplate height at azimuth ψ is `collective − pitch·cos ψ − roll·sin ψ`: positive pitch tilts the
 * plate forward (nose down), positive roll tilts it right.
 */
object Ccpm120 {
    private const val SIN120 = 0.8660254037844386

    data class Swash(val collective: Double, val roll: Double, val pitch: Double)

    /** Servo positions for a swashplate state, clamped to the servos' travel. */
    fun mix(collective: Double, roll: Double, pitch: Double) = SwashCommand(
        front = (collective - pitch).coerceIn(-1.0, 1.0),
        right = (collective + 0.5 * pitch - SIN120 * roll).coerceIn(-1.0, 1.0),
        left = (collective + 0.5 * pitch + SIN120 * roll).coerceIn(-1.0, 1.0),
    )

    /** The swashplate state produced by three servo positions. */
    fun unmix(s: SwashCommand) = Swash(
        collective = (s.front + s.right + s.left) / 3.0,
        roll = (s.left - s.right) / (2.0 * SIN120),
        pitch = ((s.right + s.left) / 2.0 - s.front) / 1.5,
    )
}

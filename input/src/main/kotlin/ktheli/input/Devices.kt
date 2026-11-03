package ktheli.input

/**
 * One polled device. [buttons] holds the device's own buttons followed by four virtual buttons per hat
 * (D-pad): up, right, down, left. A diagonal presses two of them.
 */
class Joystick(
    val jid: Int,
    val guid: String,
    val name: String,
    val axes: FloatArray,
    val buttons: BooleanArray,
    val hatCount: Int = 0,
) {
    private val firstHatButton get() = buttons.size - hatCount * HAT_DIRECTIONS.size

    fun buttonLabel(index: Int): String {
        val hatIndex = index - firstHatButton
        if (hatIndex < 0) return "button $index"
        val hat = hatIndex / HAT_DIRECTIONS.size
        val direction = HAT_DIRECTIONS[hatIndex % HAT_DIRECTIONS.size]
        return if (hatCount == 1) "D-pad $direction" else "D-pad ${hat + 1} $direction"
    }

    /** Short label for the monitor's button grid. */
    fun buttonShortLabel(index: Int): String {
        val hatIndex = index - firstHatButton
        return if (hatIndex < 0) "%2d".format(index) else HAT_SHORT[hatIndex % HAT_SHORT.size]
    }

    companion object {
        /** Order of each hat's virtual buttons. */
        val HAT_DIRECTIONS = listOf("up", "right", "down", "left")
        private val HAT_SHORT = listOf("up", "rt", "dn", "lt")
    }
}

/** Source of joystick state, polled once per frame. */
fun interface JoystickSource {
    fun poll(): List<Joystick>
}

/** Which keys are currently held (toolkit key codes). */
fun interface KeyboardState {
    fun isPressed(keycode: Int): Boolean
}

/**
 * What the toolkit's key codes mean, as far as input handling needs to know: the default bindings, which key
 * cancels a workflow, which keys are modifiers, and what keys are called.
 */
class KeyLayout(
    val defaults: Map<Action, KeyChord>,
    val escape: Int,
    val ctrl: Set<Int>,
    val shift: Set<Int>,
    val alt: Set<Int>,
    /** Further keys that only modify others (e.g. a "Sym" key); like [ctrl], [shift] and [alt], never bound alone. */
    otherModifiers: Set<Int> = emptySet(),
    private val keyName: (Int) -> String,
) {
    private val modifiers = ctrl + shift + alt + otherModifiers

    fun isModifier(keycode: Int) = keycode in modifiers

    /** E.g. "Ctrl+Q". */
    fun label(chord: KeyChord): String = buildString {
        if (chord.ctrl) append("Ctrl+")
        if (chord.shift) append("Shift+")
        if (chord.alt) append("Alt+")
        append(keyName(chord.key))
    }
}

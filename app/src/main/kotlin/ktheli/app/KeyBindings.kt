package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.input.Action
import ktheli.input.KeyChord

/** Default keyboard bindings; user changes are stored as overrides in the input config. */
object DefaultKeys {
    val map: Map<Action, KeyChord> = mapOf(
        Action.COLLECTIVE_UP to KeyChord(Keys.W),
        Action.COLLECTIVE_DOWN to KeyChord(Keys.S),
        Action.COLLECTIVE_ZERO to KeyChord(Keys.K),
        Action.ROLL_LEFT to KeyChord(Keys.LEFT),
        Action.ROLL_RIGHT to KeyChord(Keys.RIGHT),
        Action.PITCH_FORWARD to KeyChord(Keys.UP),
        Action.PITCH_BACK to KeyChord(Keys.DOWN),
        Action.YAW_LEFT to KeyChord(Keys.A),
        Action.YAW_RIGHT to KeyChord(Keys.D),
        Action.FLIGHT_MODE to KeyChord(Keys.F),
        Action.THROTTLE_HOLD to KeyChord(Keys.H),
        Action.PAUSE to KeyChord(Keys.P),
        Action.RESET to KeyChord(Keys.R),
        Action.CONTROLS_DIALOG to KeyChord(Keys.F1),
        Action.TELEMETRY to KeyChord(Keys.F2),
        Action.HUD to KeyChord(Keys.F3),
        Action.ENVIRONMENT_DIALOG to KeyChord(Keys.F4),
        Action.CAMERA_MODE to KeyChord(Keys.V),
        Action.CAMERA_DIALOG to KeyChord(Keys.F5),
        Action.BINOCULARS to KeyChord(Keys.B),
        Action.AUTO_ZOOM to KeyChord(Keys.Z),
        Action.SCREENSHOT to KeyChord(Keys.F12),
        Action.NEXT_DEVICE to KeyChord(Keys.TAB),
        Action.QUIT to KeyChord(Keys.Q, ctrl = true),
    )
}

fun KeyChord.label(): String = buildString {
    if (ctrl) append("Ctrl+")
    if (shift) append("Shift+")
    if (alt) append("Alt+")
    append(Keys.toString(key) ?: "Key $key")
}

fun isModifierKey(keycode: Int) = keycode in setOf(
    Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT, Keys.SHIFT_LEFT, Keys.SHIFT_RIGHT, Keys.ALT_LEFT, Keys.ALT_RIGHT,
    Keys.SYM,
)

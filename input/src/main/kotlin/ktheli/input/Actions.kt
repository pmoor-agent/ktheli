package ktheli.input

import kotlinx.serialization.Serializable
import ktheli.physics.sim.Controls

/**
 * Everything that can be bound to a key or a controller button. [hold] actions act while pressed;
 * the others fire once per press. [flight] actions drive channels that have no axis assigned.
 */
enum class Action(val label: String, val hold: Boolean = false, val flight: Boolean = false) {
    COLLECTIVE_UP("Collective up", hold = true, flight = true),
    COLLECTIVE_DOWN("Collective down", hold = true, flight = true),
    COLLECTIVE_ZERO("Collective to center", flight = true),
    ROLL_LEFT("Roll left", hold = true, flight = true),
    ROLL_RIGHT("Roll right", hold = true, flight = true),
    PITCH_FORWARD("Pitch forward", hold = true, flight = true),
    PITCH_BACK("Pitch back", hold = true, flight = true),
    YAW_LEFT("Yaw left", hold = true, flight = true),
    YAW_RIGHT("Yaw right", hold = true, flight = true),

    PAUSE("Pause / resume"),
    RESET("Reset"),
    CONTROLS_DIALOG("Controls dialog"),
    TELEMETRY("Telemetry window"),
    HUD("HUD"),
    AUTO_ZOOM("Camera auto-zoom"),
    SCREENSHOT("Screenshot"),
    NEXT_DEVICE("Next joystick"),
    QUIT("Quit"),
}

/** A key plus modifiers. [key] is a toolkit key code (libGDX `Input.Keys` in the app). */
@Serializable
data class KeyChord(val key: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

/**
 * Digital (on/off) flight input from keys or buttons. Collective integrates while held, so it stays
 * where it was left; cyclic and yaw deflect by a fixed amount while held.
 */
class DigitalFlight(
    private val collectiveRate: Double = 0.6, // per second
    private val cyclicDeflection: Double = 0.5,
    private val yawDeflection: Double = 0.6,
) {
    var collective = 0.0
        private set

    fun zeroCollective() {
        collective = 0.0
    }

    fun update(held: (Action) -> Boolean, dt: Double): Controls {
        fun axis(neg: Action, pos: Action) = (if (held(pos)) 1.0 else 0.0) - (if (held(neg)) 1.0 else 0.0)
        collective = (collective + axis(Action.COLLECTIVE_DOWN, Action.COLLECTIVE_UP) * collectiveRate * dt).coerceIn(-1.0, 1.0)
        return Controls(
            collective = collective,
            roll = axis(Action.ROLL_LEFT, Action.ROLL_RIGHT) * cyclicDeflection,
            pitch = axis(Action.PITCH_BACK, Action.PITCH_FORWARD) * cyclicDeflection,
            yaw = axis(Action.YAW_LEFT, Action.YAW_RIGHT) * yawDeflection,
        )
    }
}

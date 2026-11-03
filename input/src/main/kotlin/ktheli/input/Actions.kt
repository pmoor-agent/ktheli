package ktheli.input

import kotlinx.serialization.Serializable
import ktheli.physics.sim.Controls

/**
 * Everything that can be bound to a key or a controller button. [hold] actions act while pressed;
 * the others fire once per press.
 */
enum class Action(val label: String, val group: Group, val hold: Boolean = false) {
    COLLECTIVE_UP("Collective up", Group.FLIGHT, hold = true),
    COLLECTIVE_DOWN("Collective down", Group.FLIGHT, hold = true),
    COLLECTIVE_ZERO("Collective to center", Group.FLIGHT),
    ROLL_LEFT("Roll left", Group.FLIGHT, hold = true),
    ROLL_RIGHT("Roll right", Group.FLIGHT, hold = true),
    PITCH_FORWARD("Pitch forward", Group.FLIGHT, hold = true),
    PITCH_BACK("Pitch back", Group.FLIGHT, hold = true),
    YAW_LEFT("Yaw left", Group.FLIGHT, hold = true),
    YAW_RIGHT("Yaw right", Group.FLIGHT, hold = true),

    FLIGHT_MODE("Flight mode (cycle)", Group.SWITCH),
    THROTTLE_HOLD("Throttle hold (toggle)", Group.SWITCH),

    PAUSE("Pause / resume", Group.SIM),
    RESET("Reset", Group.SIM),
    CONTROLS_DIALOG("Controls dialog", Group.SIM),
    ENVIRONMENT_DIALOG("Environment dialog", Group.SIM),
    TELEMETRY("Telemetry window", Group.SIM),
    HUD("HUD", Group.SIM),
    CAMERA_MODE("Camera view (cycle)", Group.SIM),
    CAMERA_DIALOG("Camera settings", Group.SIM),
    BINOCULARS("Binoculars", Group.SIM),
    AUTO_ZOOM("Camera auto-zoom", Group.SIM),
    SCREENSHOT("Screenshot", Group.SIM),
    NEXT_DEVICE("Next joystick", Group.SIM),
    QUIT("Quit", Group.SIM),
    ;

    /**
     * [FLIGHT]: digital stick inputs, used for channels without an axis. [SWITCH]: transmitter switches,
     * part of the simulation input. [SIM]: the simulator application itself.
     */
    enum class Group(val title: String) {
        FLIGHT("Flight (for channels without an axis)"),
        SWITCH("Transmitter switches"),
        SIM("Simulator"),
    }
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

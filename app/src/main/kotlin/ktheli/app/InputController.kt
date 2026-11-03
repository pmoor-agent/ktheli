package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.input.Action
import ktheli.input.AxisDetector
import ktheli.input.ButtonDetector
import ktheli.input.CalibrationSession
import ktheli.input.Channel
import ktheli.input.ChannelBinding
import ktheli.input.DeviceProfile
import ktheli.input.DigitalFlight
import ktheli.input.InputConfig
import ktheli.input.KeyChord
import ktheli.input.ProfileStore
import ktheli.physics.sim.Controls

/**
 * Owns device selection, the binding/calibration workflows, keyboard and button bindings, and digital
 * flight input. Produces one [Frame] per rendered frame; key events come in through [keyDown].
 */
class InputController(
    private val store: ProfileStore,
    private val joystickSource: JoystickSource,
    private val keyboard: KeyboardState,
    /** Where workflow results and errors go for the pilot to see; "" clears a stale message. */
    private val report: (String) -> Unit = {},
) {
    sealed interface Mode {
        data object Fly : Mode
        class Calibrating(val session: CalibrationSession) : Mode {
            var centerSeconds = 0.0
        }
        class AssigningAxis(val channel: Channel, val detector: AxisDetector) : Mode
        class CapturingKey(val action: Action) : Mode
        class CapturingButton(val action: Action, val detector: ButtonDetector) : Mode
    }

    /** Stick positions for this frame, plus press-actions triggered by controller buttons. */
    class Frame(val controls: Controls, val actions: List<Action>)

    var mode: Mode = Mode.Fly
        private set
    var joysticks: List<Joystick> = emptyList()
        private set
    var config: InputConfig = runCatching { store.load() }.getOrElse { InputConfig() }
        private set

    val digital = DigitalFlight()

    /** Transmitter switches; part of [Controls], so they're simulation inputs like the sticks. */
    var flightMode = 0
        private set
    /** How many positions the flight-mode switch has: set from the current model's radio setup. */
    var flightModeCount = 1
        set(value) {
            field = value.coerceAtLeast(1)
            flightMode = flightMode.coerceAtMost(field - 1)
        }
    /** Starts on, like arming a real heli: the motor can't start until the pilot releases it. */
    var throttleHold = true
        private set
    private val previousButtons = HashMap<String, BooleanArray>()
    private var keyboardEnabled = true

    val active: Joystick?
        get() = joysticks.firstOrNull { it.guid == config.activeGuid } ?: joysticks.firstOrNull()

    val activeProfile: DeviceProfile?
        get() = active?.let { config.devices[it.guid] }

    fun keyFor(action: Action): KeyChord? = config.keyFor(action, DefaultKeys.map)

    fun buttonFor(action: Action): Int? = activeProfile?.buttons?.get(action)

    fun update(dt: Double, keyboardEnabled: Boolean): Frame {
        this.keyboardEnabled = keyboardEnabled
        joysticks = joystickSource.poll()
        joysticks.forEach { j ->
            if (j.guid !in config.devices) save(config.copy(devices = config.devices + (j.guid to DeviceProfile.default(j.guid, j.name))))
        }

        val j = active
        if (j == null && mode !is Mode.Fly && mode !is Mode.CapturingKey) mode = Mode.Fly // device unplugged mid-workflow
        val pressed = j?.let(::risingEdges).orEmpty()
        val actions = mutableListOf<Action>()

        when (val m = mode) {
            is Mode.Calibrating -> j?.let {
                m.session.observe(it.axes)
                if (m.session.phase == CalibrationSession.Phase.CENTER) {
                    m.centerSeconds += dt
                    if (m.centerSeconds >= CENTER_SECONDS) m.session.beginExtremes()
                }
            }
            is Mode.AssigningAxis -> j?.let { joy ->
                m.detector.observe(joy.axes)?.let { detection ->
                    saveProfile(config.devices.getValue(joy.guid).withBinding(m.channel, detection))
                    report("${m.channel.label} -> axis ${detection.axis}${if (detection.reversed) " (reversed)" else ""}")
                    mode = Mode.Fly
                }
            }
            is Mode.CapturingButton -> j?.let { joy ->
                m.detector.observe(joy.buttons)?.let { button ->
                    saveProfile(config.devices.getValue(joy.guid).withButton(m.action, button))
                    report("${m.action.label} -> ${joy.buttonLabel(button)}")
                    mode = Mode.Fly
                }
            }
            is Mode.CapturingKey -> {}
            Mode.Fly -> {
                val byButton = activeProfile?.buttons.orEmpty().entries.associate { (action, button) -> button to action }
                pressed.mapNotNullTo(actions) { button -> byButton[button]?.takeUnless { it.hold } }
            }
        }

        val digitalControls = digital.update(if (mode == Mode.Fly) ::isHeld else { _ -> false }, dt)
        val sticks = when {
            mode is Mode.Calibrating || mode is Mode.AssigningAxis -> Controls.NEUTRAL
            j != null -> config.devices.getValue(j.guid).toControls(j.axes, digitalControls)
            else -> digitalControls
        }
        return Frame(sticks.copy(flightMode = flightMode, throttleHold = throttleHold), actions)
    }

    private fun risingEdges(j: Joystick): List<Int> {
        val previous = previousButtons.put(j.guid, j.buttons.copyOf()) ?: return emptyList() // first sight: no edges
        return j.buttons.indices.filter { j.buttons[it] && !previous.getOrElse(it) { false } }
    }

    private fun isHeld(action: Action): Boolean {
        val key = keyFor(action)
        if (keyboardEnabled && key != null && keyboard.isPressed(key.key)) return true
        val button = buttonFor(action) ?: return false
        return active?.buttons?.getOrNull(button) == true
    }

    /** Handles a key press: completes a key capture, cancels workflows on Esc, or returns the bound action. */
    fun keyDown(keycode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean): Action? {
        if (isModifierKey(keycode)) return null
        when (val m = mode) {
            Mode.Fly -> {}
            is Mode.CapturingKey -> {
                if (keycode == Keys.ESCAPE) {
                    cancel()
                } else {
                    val chord = KeyChord(keycode, ctrl, shift, alt)
                    save(config.withKey(m.action, chord, DefaultKeys.map))
                    report("${m.action.label} -> ${chord.label()}")
                    mode = Mode.Fly
                }
                return null
            }
            else -> {
                if (keycode == Keys.ESCAPE) cancel()
                return null
            }
        }
        val chord = KeyChord(keycode, ctrl, shift, alt)
        return Action.entries.firstOrNull { !it.hold && keyFor(it) == chord }
    }

    // --- Workflows -----------------------------------------------------------------------------

    /** Phase 1 records the resting jitter of the centered sticks; phase 2 the extremes. */
    fun startCalibration() {
        val j = active ?: return
        mode = Mode.Calibrating(CalibrationSession(j.axes.size))
        report("")
    }

    fun finishCalibration() {
        val m = mode as? Mode.Calibrating ?: return
        val profile = activeProfile ?: return
        saveProfile(profile.copy(calibration = m.session.finish()))
        mode = Mode.Fly
        report("Calibration saved")
    }

    fun startAssignAxis(channel: Channel) {
        val j = active ?: return
        mode = Mode.AssigningAxis(channel, AxisDetector(j.axes))
        report("")
    }

    fun clearAxis(channel: Channel) {
        activeProfile?.let { saveProfile(it.withoutBinding(channel)) }
    }

    fun startCaptureKey(action: Action) {
        mode = Mode.CapturingKey(action)
        report("")
    }

    fun clearKey(action: Action) = save(config.withoutKey(action))

    fun resetKeysToDefaults() {
        save(config.withDefaultKeys())
        report("Keyboard bindings reset to defaults")
    }

    fun startCaptureButton(action: Action) {
        val j = active ?: return
        mode = Mode.CapturingButton(action, ButtonDetector(j.buttons))
        report("")
    }

    fun clearButton(action: Action) {
        activeProfile?.let { saveProfile(it.withoutButton(action)) }
    }

    fun cancel() {
        if (mode == Mode.Fly) return
        mode = Mode.Fly
        report("Cancelled")
    }

    fun selectDevice(guid: String) = save(config.copy(activeGuid = guid))

    fun selectNextDevice() {
        if (joysticks.size < 2) return
        selectDevice(joysticks[(joysticks.indexOf(active) + 1) % joysticks.size].guid)
    }

    fun updateBinding(channel: Channel, change: (ChannelBinding) -> ChannelBinding) {
        val profile = activeProfile ?: return
        val binding = profile.bindings[channel] ?: return
        saveProfile(profile.copy(bindings = profile.bindings + (channel to change(binding))))
    }

    fun cycleFlightMode() {
        flightMode = (flightMode + 1) % flightModeCount
    }

    fun selectFlightMode(index: Int) {
        flightMode = index.coerceIn(0, flightModeCount - 1)
    }

    fun toggleThrottleHold() {
        throttleHold = !throttleHold
    }

    /** Back to the safe state after a reset: throttle hold on, first flight mode. */
    fun resetSwitches() {
        throttleHold = true
        flightMode = 0
    }

    private fun saveProfile(profile: DeviceProfile) = save(config.copy(devices = config.devices + (profile.guid to profile)))

    private fun save(newConfig: InputConfig) {
        config = newConfig
        runCatching { store.save(newConfig) }.onFailure { report("Could not save input config: ${it.message}") }
    }

    companion object {
        const val CENTER_SECONDS = 1.5
    }
}

val Channel.label: String get() = name.lowercase()

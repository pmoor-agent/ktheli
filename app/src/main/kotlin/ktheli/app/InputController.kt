package ktheli.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import ktheli.input.AxisDetector
import ktheli.input.CalibrationSession
import ktheli.input.Channel
import ktheli.input.ChannelBinding
import ktheli.input.DeviceProfile
import ktheli.input.InputConfig
import ktheli.input.ProfileStore
import ktheli.physics.sim.Controls

/**
 * Owns device selection, calibration/assignment workflows and the keyboard fallback, and produces
 * one [Controls] per frame. UI and keyboard shortcuts both drive it through the public actions.
 */
class InputController(private val store: ProfileStore = ProfileStore()) {
    sealed interface Mode {
        data object Fly : Mode
        class Calibrating(val session: CalibrationSession) : Mode
        class Assigning(val channel: Channel, val detector: AxisDetector) : Mode
    }

    var mode: Mode = Mode.Fly
        private set
    var joysticks: List<Joystick> = emptyList()
        private set
    var config: InputConfig = runCatching { store.load() }.getOrElse { InputConfig() }
        private set
    var status: String = ""
        private set

    private var keyboardCollective = 0.0

    val active: Joystick?
        get() = joysticks.firstOrNull { it.guid == config.activeGuid } ?: joysticks.firstOrNull()

    val activeProfile: DeviceProfile?
        get() = active?.let { config.devices[it.guid] }

    fun update(dt: Double, keyboardEnabled: Boolean): Controls {
        joysticks = Joysticks.poll()
        joysticks.forEach { j ->
            if (j.guid !in config.devices) save(config.copy(devices = config.devices + (j.guid to DeviceProfile.default(j.guid, j.name))))
        }

        val j = active
        if (j == null && mode != Mode.Fly) mode = Mode.Fly // device unplugged mid-workflow
        when (val m = mode) {
            is Mode.Calibrating -> j?.let { m.session.observe(it.axes) }
            is Mode.Assigning -> j?.let { joy ->
                m.detector.observe(joy.axes)?.let { detection ->
                    saveProfile(config.devices.getValue(joy.guid).withBinding(m.channel, detection))
                    status = "${m.channel} → axis ${detection.axis}${if (detection.reversed) " (reversed)" else ""}"
                    mode = Mode.Fly
                }
            }
            Mode.Fly -> {}
        }

        if (mode != Mode.Fly) return Controls.NEUTRAL
        return when {
            j != null -> config.devices.getValue(j.guid).toControls(j.axes)
            keyboardEnabled -> keyboardControls(dt)
            else -> Controls(collective = keyboardCollective)
        }
    }

    /** Fallback for testing without a transmitter: W/S collective, A/D yaw, arrows cyclic. */
    private fun keyboardControls(dt: Double): Controls {
        fun axis(neg: Int, pos: Int) = (if (Gdx.input.isKeyPressed(pos)) 1.0 else 0.0) - (if (Gdx.input.isKeyPressed(neg)) 1.0 else 0.0)
        keyboardCollective = (keyboardCollective + axis(Keys.S, Keys.W) * 0.6 * dt).coerceIn(-1.0, 1.0)
        return Controls(
            collective = keyboardCollective,
            roll = axis(Keys.LEFT, Keys.RIGHT) * 0.5,
            pitch = axis(Keys.DOWN, Keys.UP) * 0.5,
            yaw = axis(Keys.A, Keys.D) * 0.6,
        )
    }

    // --- Actions -------------------------------------------------------------------------------

    /** Captures the current stick positions as centers; the pilot then moves every stick to its extremes. */
    fun startCalibration() {
        val j = active ?: return
        mode = Mode.Calibrating(CalibrationSession(j.axes))
        status = ""
    }

    fun finishCalibration() {
        val m = mode as? Mode.Calibrating ?: return
        val profile = activeProfile ?: return
        saveProfile(profile.copy(calibration = m.session.finish()))
        mode = Mode.Fly
        status = "Calibration saved"
    }

    fun startAssign(channel: Channel) {
        val j = active ?: return
        mode = Mode.Assigning(channel, AxisDetector(j.axes))
        status = ""
    }

    fun cancel() {
        if (mode == Mode.Fly) return
        mode = Mode.Fly
        status = "Cancelled"
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

    fun zeroKeyboardCollective() {
        keyboardCollective = 0.0
    }

    fun showStatus(message: String) {
        status = message
    }

    /** Keyboard shortcuts for the input workflows; returns true if consumed. */
    fun keyDown(keycode: Int): Boolean {
        when {
            keycode == Keys.ESCAPE && mode != Mode.Fly -> cancel()
            keycode == Keys.C && active != null ->
                if (mode is Mode.Calibrating) finishCalibration() else startCalibration()
            keycode in Keys.NUM_1..Keys.NUM_4 && active != null && mode == Mode.Fly ->
                startAssign(Channel.entries[keycode - Keys.NUM_1])
            keycode == Keys.TAB -> selectNextDevice()
            keycode == Keys.K -> zeroKeyboardCollective()
            else -> return false
        }
        return true
    }

    private fun saveProfile(profile: DeviceProfile) = save(config.copy(devices = config.devices + (profile.guid to profile)))

    private fun save(newConfig: InputConfig) {
        config = newConfig
        runCatching { store.save(newConfig) }.onFailure { status = "Could not save input config: ${it.message}" }
    }
}

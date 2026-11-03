package ktheli.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import ktheli.input.AxisDetector
import ktheli.input.CalibrationSession
import ktheli.input.Channel
import ktheli.input.DeviceProfile
import ktheli.input.InputConfig
import ktheli.input.ProfileStore
import ktheli.physics.sim.Controls

/**
 * Owns device selection, calibration/assignment workflows and the keyboard fallback, and produces
 * one [Controls] per frame.
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

    fun update(dt: Double): Controls {
        joysticks = Joysticks.poll()
        joysticks.forEach { j ->
            if (j.guid !in config.devices) save(config.copy(devices = config.devices + (j.guid to DeviceProfile.default(j.guid, j.name))))
        }

        val j = active
        when (val m = mode) {
            is Mode.Calibrating -> j?.let { m.session.observe(it.axes) }
            is Mode.Assigning -> j?.let { joy ->
                m.detector.observe(joy.axes)?.let { detection ->
                    val profile = config.devices.getValue(joy.guid).withBinding(m.channel, detection)
                    save(config.copy(devices = config.devices + (joy.guid to profile)))
                    status = "${m.channel} → axis ${detection.axis}${if (detection.reversed) " (reversed)" else ""}"
                    mode = Mode.Fly
                }
            }
            Mode.Fly -> {}
        }

        if (mode != Mode.Fly) return Controls.NEUTRAL
        return if (j != null) config.devices.getValue(j.guid).toControls(j.axes) else keyboardControls(dt)
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

    fun keyDown(keycode: Int): Boolean {
        val j = active
        when {
            keycode == Keys.ESCAPE && mode != Mode.Fly -> { mode = Mode.Fly; status = "Cancelled" }
            keycode == Keys.C && j != null -> when (val m = mode) {
                is Mode.Calibrating -> {
                    val profile = config.devices.getValue(j.guid).copy(calibration = m.session.finish())
                    save(config.copy(devices = config.devices + (j.guid to profile)))
                    mode = Mode.Fly
                    status = "Calibration saved"
                }
                else -> mode = Mode.Calibrating(CalibrationSession(j.axes))
            }
            keycode in Keys.NUM_1..Keys.NUM_4 && j != null && mode == Mode.Fly ->
                mode = Mode.Assigning(Channel.entries[keycode - Keys.NUM_1], AxisDetector(j.axes))
            keycode == Keys.TAB && joysticks.size > 1 -> {
                val next = joysticks[(joysticks.indexOf(j) + 1) % joysticks.size]
                save(config.copy(activeGuid = next.guid))
            }
            keycode == Keys.K -> keyboardCollective = 0.0
            else -> return false
        }
        return true
    }

    fun showStatus(message: String) {
        status = message
    }

    private fun save(newConfig: InputConfig) {
        config = newConfig
        runCatching { store.save(newConfig) }.onFailure { status = "Could not save input config: ${it.message}" }
    }
}

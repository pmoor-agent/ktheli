package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.input.ProfileStore
import ktheli.physics.sim.Controls

/**
 * Everything except rendering: simulation, input, telemetry and the ImGui UI. The windowed app wraps it
 * with a GL scene and ImGui renderer; tests drive it headlessly (see `UiDriver` in the test sources).
 *
 * Per frame, call [update] and then, inside an ImGui frame, `ui.draw()`.
 */
class AppCore(
    joysticks: JoystickSource,
    private val keyboard: KeyboardState,
    host: AppHost,
    store: ProfileStore = ProfileStore(),
) {
    val runner = SimRunner()
    val input = InputController(store, joysticks, keyboard)
    val telemetry = Telemetry()
    val view = ViewSettings()
    var controls: Controls = Controls.NEUTRAL
        private set
    val ui = Ui(UiContext(runner, input, view, telemetry, { controls }, host))

    fun update(frameSeconds: Double, keyboardEnabled: Boolean) {
        val frame = input.update(frameSeconds, keyboardEnabled)
        controls = frame.controls
        frame.actions.forEach(ui::perform)
        runner.advance(frameSeconds, controls)
        telemetry.record(runner.sim.time, runner.sim.aircraft.body, controls)
    }

    /** A key went down; modifiers are read from the keyboard state. */
    fun keyDown(keycode: Int) {
        fun down(a: Int, b: Int) = keyboard.isPressed(a) || keyboard.isPressed(b)
        val action = input.keyDown(
            keycode,
            ctrl = down(Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT),
            shift = down(Keys.SHIFT_LEFT, Keys.SHIFT_RIGHT),
            alt = down(Keys.ALT_LEFT, Keys.ALT_RIGHT),
        )
        action?.let(ui::perform)
    }
}

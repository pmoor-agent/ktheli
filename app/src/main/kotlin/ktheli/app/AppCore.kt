package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.input.Action
import ktheli.input.ProfileStore

/**
 * The app without its window: the toolkit-free [session], the ImGui [ui] on top of it, and the frame [profiler].
 * The windowed app adds a GL scene and the ImGui renderer; tests drive it headlessly (see `UiDriver` in the test
 * sources). Every [Action], whether from a key, a controller button or a menu, goes through [perform].
 *
 * Per frame, call [update] and then, inside an ImGui frame, [drawUi].
 */
class AppCore(
    joysticks: JoystickSource,
    private val keyboard: KeyboardState,
    host: AppHost,
    store: ProfileStore = ProfileStore(),
    settingsStore: SettingsStore = SettingsStore(),
) {
    val session = AppSession(joysticks, keyboard, host, store, settingsStore)

    /** Frame timing for the Performance window; display only. */
    val profiler = FrameProfiler(1.0 / session.runner.sim.tickRateHz)

    val ui = Ui(session, host, { profiler }, ::perform)

    /** Window toggles go to the UI, everything else to the session. */
    fun perform(action: Action) {
        if (!ui.toggle(action)) session.perform(action)
    }

    fun update(frameSeconds: Double, keyboardEnabled: Boolean) {
        val actions = profiler.measure(FrameProfiler.Section.INPUT) { session.pollInput(frameSeconds, keyboardEnabled) }
        actions.forEach(::perform)
        profiler.ticks(profiler.measure(FrameProfiler.Section.PHYSICS) { session.advance(frameSeconds) })
    }

    /** Builds this frame's UI (inside an ImGui frame). */
    fun drawUi() = profiler.measure(FrameProfiler.Section.UI) { ui.draw() }

    /** A key went down; modifiers are read from the keyboard state. */
    fun keyDown(keycode: Int) {
        fun down(a: Int, b: Int) = keyboard.isPressed(a) || keyboard.isPressed(b)
        val action = session.input.keyDown(
            keycode,
            ctrl = down(Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT),
            shift = down(Keys.SHIFT_LEFT, Keys.SHIFT_RIGHT),
            alt = down(Keys.ALT_LEFT, Keys.ALT_RIGHT),
        )
        action?.let(::perform)
    }
}

package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.sound.HeliAcoustics
import ktheli.app.sound.Listener
import ktheli.app.sound.SoundParams
import ktheli.app.sound.SoundSettings
import ktheli.input.ProfileStore
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.math.Vec3
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
    private val settingsStore: SettingsStore = SettingsStore(),
) {
    private var settings = settingsStore.load()
    val runner = SimRunner(settings.model, settings.environment.toSettings(), settings.flyingField)
    val input = InputController(store, joysticks, keyboard)
    val telemetry = Telemetry()
    val view = ViewSettings(settings.camera)
    var controls: Controls = Controls.NEUTRAL
        private set
    var sound: SoundSettings = settings.sound
        private set
    var crashSettings: CrashSettings = settings.crash
        private set
    val ui = Ui(
        UiContext(
            runner, input, view, telemetry, { controls }, host, ::changeEnvironment, ::selectAircraft, ::changeCamera, ::selectField,
            { profiler }, { sound }, ::changeSound, { crashSettings }, ::changeCrashSettings,
        ),
    )

    /** Where the sound is heard: the renderer moves it with the camera. Starts where the pilot stands. */
    var listener = Listener(runner.field.pilotEye, Vec3(0.0, 1.0, 0.0))

    fun changeCrashSettings(crash: CrashSettings) {
        crashSettings = crash
        save(settings.copy(crash = crash))
    }

    /** Crashes so far this session; the sound plays an impact whenever it goes up. */
    private var impacts = 0
    private var lastCrash: ktheli.physics.sim.Crash? = null

    fun changeSound(sound: SoundSettings) {
        this.sound = sound
        save(settings.copy(sound = sound))
    }

    /** What the model sounds like at the [listener] right now; silent when muted or paused. */
    fun soundParams(): SoundParams {
        val body = runner.renderState()
        val wind = runner.sim.environment.windAt(listener.position).length()
        val crash = runner.crash
        val volume = if (sound.enabled && (!runner.paused || crash != null)) sound.volume else 0.0
        val params = HeliAcoustics.params(HeliAcoustics.source(runner.sim.aircraft), body.position, body.velocity, listener, wind, volume)
        return if (crash == null) params.copy(impacts = impacts) else HeliAcoustics.crashed(params, impacts, crash.impact.speed)
    }

    fun selectField(field: FlyingField) {
        runner.select(field)
        save(settings.copy(field = field.id))
    }

    fun changeCamera(camera: CameraSettings) {
        view.camera = camera
        save(settings.copy(camera = camera))
    }

    /** Applies (immediately, as a simulation input) and remembers new environment settings. */
    fun changeEnvironment(environment: EnvironmentSettings) {
        runner.environment = environment
        save(settings.copy(environment = EnvironmentDto.of(environment)))
    }

    fun selectAircraft(model: HelicopterModel) {
        runner.select(model)
        save(settings.copy(aircraft = model.id))
    }

    private fun save(newSettings: AppSettings) {
        settings = newSettings
        runCatching { settingsStore.save(newSettings) }.onFailure { input.showStatus("Could not save settings: ${it.message}") }
    }

    /** Frame timing for the Performance window; display only. */
    val profiler = FrameProfiler(1.0 / runner.sim.tickRateHz)

    fun update(frameSeconds: Double, keyboardEnabled: Boolean) {
        val frame = profiler.measure(FrameProfiler.Section.INPUT) { input.update(frameSeconds, keyboardEnabled) }
        controls = frame.controls
        frame.actions.forEach(ui::perform)
        profiler.ticks(profiler.measure(FrameProfiler.Section.PHYSICS) { runner.advance(frameSeconds, controls) })
        val crash = runner.crash
        if (crash != null && crash !== lastCrash) impacts++
        lastCrash = crash
        if (crash != null && crashSettings.autoReset && runner.crashedSeconds >= crashSettings.delaySeconds) ui.reset()
        telemetry.record(runner.sim.time, runner.sim.aircraft, controls)
    }

    /** Builds this frame's UI (inside an ImGui frame). */
    fun drawUi() = profiler.measure(FrameProfiler.Section.UI) { ui.draw() }

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

package ktheli.app

import ktheli.app.sound.HeliAcoustics
import ktheli.app.sound.Listener
import ktheli.app.sound.SoundParams
import ktheli.app.sound.SoundSettings
import ktheli.input.Action
import ktheli.input.InputController
import ktheli.input.JoystickSource
import ktheli.input.KeyLayout
import ktheli.input.KeyboardState
import ktheli.input.ProfileStore
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.heli.Helicopter
import ktheli.physics.math.Vec3
import ktheli.physics.sim.Controls
import ktheli.physics.sim.Crash

/** The latest feedback for the pilot (a binding made, a file saved, an error); empty when there's nothing to say. */
class StatusLine {
    var message: String = ""
        private set

    fun show(message: String) {
        this.message = message
    }
}

/**
 * The simulator without a GUI or a rendering toolkit: simulation, input, telemetry, persisted settings, and
 * every [Action] that isn't about which windows are open. The ImGui [Ui] shows it and issues commands to it;
 * the renderer and the sound read it. It never calls back into either.
 *
 * Per frame: [pollInput], perform the returned actions, then [advance].
 */
class AppSession(
    joysticks: JoystickSource,
    keyboard: KeyboardState,
    keys: KeyLayout,
    private val host: AppHost,
    store: ProfileStore = ProfileStore(ConfigPaths.input),
    private val settingsStore: SettingsStore = SettingsStore(),
) {
    val status = StatusLine()
    private var settings = settingsStore.load()
    val runner = SimRunner(settings.model, settings.environment.toSettings(), settings.flyingField)
    val input = InputController(store, joysticks, keyboard, keys, status::show)
    val telemetry = Telemetry()

    /** Camera configuration (persisted); [cameraPose] is where it actually is this frame. */
    var camera: CameraSettings = settings.camera
        private set

    /** This frame's stick and switch positions, as fed to the simulation. */
    var controls: Controls = Controls.NEUTRAL
        private set
    var sound: SoundSettings = settings.sound
        private set
    var crashSettings: CrashSettings = settings.crash
        private set

    private val rig = CameraRig()

    /** The main camera this frame, from [updateCamera]: what the renderer shows and where the sound is heard. */
    lateinit var cameraPose: CameraPose
        private set

    /** Where the sound is heard: at the camera, facing what it looks at. */
    var listener = Listener(runner.field.pilotEye, Vec3(0.0, 1.0, 0.0))
        private set

    /** Crashes so far this session; the sound plays an impact whenever it goes up. */
    private var impacts = 0
    private var lastCrash: Crash? = null

    init {
        syncFlightModes()
        updateCamera(0.0)
    }

    /** Reads keyboard and joysticks; returns the press-actions their buttons triggered, for the caller to route. */
    fun pollInput(frameSeconds: Double, keyboardEnabled: Boolean): List<Action> {
        val frame = input.update(frameSeconds, keyboardEnabled)
        controls = frame.controls
        return frame.actions
    }

    /** Runs the simulation for [frameSeconds] of wall time with this frame's [controls]; returns the ticks run. */
    fun advance(frameSeconds: Double): Int {
        val ticks = runner.advance(frameSeconds, controls)
        val crash = runner.crash
        if (crash != null && crash !== lastCrash) impacts++
        lastCrash = crash
        if (crash != null && crashSettings.autoReset && runner.crashedSeconds >= crashSettings.delaySeconds) reset()
        telemetry.record(runner.sim.time, runner.sim.aircraft, controls)
        return ticks
    }

    /**
     * Moves the camera for this frame (display only: it never feeds back into the simulation) and the listener
     * with it. Call after [advance], before drawing and sound.
     */
    fun updateCamera(frameSeconds: Double) {
        val pose = rig.update(runner.renderState(), runner.model.visual.rotorRadius, camera, frameSeconds.coerceIn(0.0, 0.1), runner.field.pilotEye)
        cameraPose = pose
        listener = Listener.facing(pose.position, pose.target, listener.right)
    }

    /** Executes a press-action that belongs to the simulator; window toggles are ignored (the UI owns them). */
    fun perform(action: Action) {
        when (action) {
            Action.PAUSE -> if (runner.crash == null) {
                runner.paused = !runner.paused
            } else {
                status.show("Crashed: ${input.keyLabel(Action.RESET) ?: "reset"} to start again")
            }
            Action.RESET -> reset()
            Action.FLIGHT_MODE -> input.cycleFlightMode()
            Action.THROTTLE_HOLD -> input.toggleThrottleHold()
            Action.COLLECTIVE_ZERO -> input.digital.zeroCollective()
            Action.AUTO_ZOOM -> camera.let { changeCamera(it.copy(autoZoom = !it.autoZoom)) }
            Action.CAMERA_MODE -> camera.let {
                changeCamera(it.copy(mode = CameraMode.entries[(it.mode.ordinal + 1) % CameraMode.entries.size]))
            }
            Action.BINOCULARS -> camera.let {
                changeCamera(it.copy(binoculars = it.binoculars.copy(enabled = !it.binoculars.enabled)))
            }
            Action.SOUND -> changeSound(sound.copy(enabled = !sound.enabled))
            Action.SCREENSHOT -> host.requestScreenshot()
            Action.NEXT_DEVICE -> input.selectNextDevice()
            Action.QUIT -> host.exit()
            else -> {} // hold actions are polled; window toggles belong to the UI
        }
    }

    /**
     * Starts the flight over. The transmitter's switches stay where they are, as on a real radio, so after a
     * crash you can take off again right away; [armSwitches] goes back to throttle hold and the first flight mode.
     */
    fun reset(armSwitches: Boolean = false) {
        runner.reset()
        afterReset(armSwitches)
    }

    /** A new model starts parked, with the switches armed. */
    fun selectAircraft(model: HelicopterModel) {
        runner.select(model)
        afterReset(armSwitches = true)
        save(settings.copy(aircraft = model.id))
    }

    /** A new field starts parked, with the switches armed. */
    fun selectField(field: FlyingField) {
        runner.select(field)
        afterReset(armSwitches = true)
        save(settings.copy(field = field.id))
    }

    private fun afterReset(armSwitches: Boolean) {
        telemetry.clear()
        syncFlightModes()
        if (armSwitches) input.resetSwitches()
    }

    /** The flight-mode switch has as many positions as the model's radio setup has flight modes. */
    private fun syncFlightModes() {
        input.flightModeCount = (runner.sim.aircraft as? Helicopter)?.transmitter?.setup?.modes?.size ?: 1
    }

    fun changeCamera(camera: CameraSettings) {
        this.camera = camera
        save(settings.copy(camera = camera))
    }

    /** Applies (immediately, as a simulation input) and remembers new environment settings. */
    fun changeEnvironment(environment: EnvironmentSettings) {
        runner.environment = environment
        save(settings.copy(environment = EnvironmentDto.of(environment)))
    }

    fun changeSound(sound: SoundSettings) {
        this.sound = sound
        save(settings.copy(sound = sound))
    }

    fun changeCrashSettings(crash: CrashSettings) {
        crashSettings = crash
        save(settings.copy(crash = crash))
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

    private fun save(newSettings: AppSettings) {
        settings = newSettings
        runCatching { settingsStore.save(newSettings) }.onFailure { status.show("Could not save settings: ${it.message}") }
    }
}

package ktheli.app.ui

import imgui.ImColor
import imgui.ImGui
import imgui.extension.implot.ImPlot
import imgui.extension.implot.flag.ImPlotAxis
import imgui.extension.implot.flag.ImPlotAxisFlags
import imgui.extension.implot.flag.ImPlotCond
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import ktheli.app.session.AppHost
import ktheli.app.session.AppSession
import ktheli.app.session.Binoculars
import ktheli.app.session.CameraMode
import ktheli.app.session.FlyingFields
import ktheli.app.session.FrameProfiler
import ktheli.app.session.HelicopterModels
import ktheli.input.Action
import ktheli.input.CalibrationSession
import ktheli.input.InputController
import ktheli.physics.heli.Helicopter
import ktheli.physics.sim.Crash

/**
 * Immediate-mode UI: call [draw] once per frame between ImGui newFrame/render. It shows the [session] and owns
 * only which windows are open ([toggle]); every other action, from a menu or a key, goes to [perform].
 */
class Ui(
    private val session: AppSession,
    private val host: AppHost,
    profiler: () -> FrameProfiler,
    private val perform: (Action) -> Unit,
) {
    val showHud = ImBoolean(true)
    val showTelemetry = ImBoolean(false)
    val controlsWindow = ControlsWindow(session.input, { session.controls }, { session.status.message })
    val environmentWindow = EnvironmentWindow({ session.runner.environment }, session::changeEnvironment)
    val cameraWindow = CameraWindow({ session.camera }, session::changeCamera)
    val performanceWindow = PerformanceWindow(profiler, { session.runner.sim.tickRateHz })
    private val showDemo = ImBoolean(false)
    private var openAbout = false

    /** Height of the main menu bar in logical pixels, valid after [draw]. */
    var menuBarHeight = 0f
        private set

    private val helicopter: Helicopter? get() = session.runner.sim.aircraft as? Helicopter

    fun draw() {
        drawMenuBar()
        if (showHud.get()) drawHud()
        session.runner.crash?.let(::drawCrash)
        if (session.camera.binoculars.enabled) drawBinocularFrame()
        controlsWindow.draw(menuBarHeight)
        environmentWindow.draw(menuBarHeight)
        cameraWindow.draw(menuBarHeight)
        performanceWindow.draw(menuBarHeight)
        if (showTelemetry.get()) drawTelemetryWindow()
        if (showDemo.get()) ImGui.showDemoWindow(showDemo)
        drawAbout()
    }

    /** Opens or closes the window [action] stands for; false if it isn't a window toggle. */
    fun toggle(action: Action): Boolean {
        when (action) {
            Action.CONTROLS_DIALOG -> controlsWindow.open.toggle()
            Action.ENVIRONMENT_DIALOG -> environmentWindow.open.toggle()
            Action.TELEMETRY -> showTelemetry.toggle()
            Action.PERFORMANCE -> performanceWindow.open.toggle()
            Action.HUD -> showHud.toggle()
            Action.CAMERA_DIALOG -> cameraWindow.open.toggle()
            else -> return false
        }
        return true
    }

    private fun ImBoolean.toggle() = set(!get())

    private fun shortcut(action: Action) = session.input.keyLabel(action).orEmpty()

    private fun actionItem(label: String, action: Action, selected: Boolean = false) {
        if (Gui.menuItem(label, shortcut(action), selected)) perform(action)
    }

    private fun drawMenuBar() {
        if (!ImGui.beginMainMenuBar()) return
        menuBarHeight = ImGui.getWindowHeight()

        if (Gui.beginMenu("Sim")) {
            actionItem(if (session.runner.paused) "Resume" else "Pause", Action.PAUSE)
            actionItem("Reset", Action.RESET)
            if (Gui.beginMenu("After a crash")) {
                val c = session.crashSettings
                if (Gui.menuItem("Reset automatically", "", c.autoReset)) session.changeCrashSettings(c.copy(autoReset = !c.autoReset))
                Gui.slider("Delay", c.delaySeconds, 1.0, 10.0, "%.0f s")?.let { session.changeCrashSettings(c.copy(delaySeconds = it)) }
                Gui.endMenu()
            }
            actionItem("Environment...", Action.ENVIRONMENT_DIALOG, environmentWindow.open.get())
            if (Gui.beginMenu("Sound")) {
                val sound = session.sound
                actionItem("Sound on", Action.SOUND, sound.enabled)
                Gui.slider("Volume", 100 * sound.volume, 0.0, 100.0, "%.0f%%")?.let { session.changeSound(sound.copy(volume = it / 100)) }
                Gui.endMenu()
            }
            ImGui.separator()
            actionItem("Throttle hold", Action.THROTTLE_HOLD, session.input.throttleHold)
            helicopter?.let { heli ->
                if (Gui.beginMenu("Flight mode")) {
                    heli.transmitter.setup.modes.forEachIndexed { i, mode ->
                        if (Gui.menuItem(mode.name, if (i == 0) shortcut(Action.FLIGHT_MODE) else "", i == session.input.flightMode)) {
                            session.input.selectFlightMode(i)
                        }
                    }
                    Gui.endMenu()
                }
            }
            ImGui.separator()
            actionItem("Quit", Action.QUIT)
            Gui.endMenu()
        }
        if (Gui.beginMenu("Aircraft")) {
            HelicopterModels.ALL.forEach { model ->
                if (Gui.menuItem(model.name, "", model == session.runner.model)) {
                    session.selectAircraft(model)
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(model.description)
            }
            Gui.endMenu()
        }
        if (Gui.beginMenu("Field")) {
            FlyingFields.ALL.forEach { field ->
                if (Gui.menuItem(field.name, "", field == session.runner.field)) {
                    session.selectField(field)
                }
                if (ImGui.isItemHovered()) ImGui.setTooltip(field.description)
            }
            Gui.endMenu()
        }
        if (Gui.beginMenu("Input")) {
            actionItem("Controls...", Action.CONTROLS_DIALOG, controlsWindow.open.get())
            ImGui.separator()
            val joysticks = session.input.joysticks
            if (joysticks.isEmpty()) {
                Gui.textDisabled("No joystick connected - keyboard active")
            } else {
                val active = session.input.active
                joysticks.forEach { j ->
                    if (Gui.menuItem("${j.name}##${j.jid}", "", j == active)) session.input.selectDevice(j.guid)
                }
            }
            Gui.endMenu()
        }
        if (Gui.beginMenu("View")) {
            actionItem("HUD", Action.HUD, showHud.get())
            actionItem("Telemetry", Action.TELEMETRY, showTelemetry.get())
            actionItem("Performance", Action.PERFORMANCE, performanceWindow.open.get())
            ImGui.separator()
            CameraMode.entries.forEachIndexed { i, mode ->
                val shortcut = if (i == 0) shortcut(Action.CAMERA_MODE) else ""
                if (Gui.menuItem(mode.label, shortcut, session.camera.mode == mode)) session.changeCamera(session.camera.copy(mode = mode))
            }
            actionItem("Binoculars", Action.BINOCULARS, session.camera.binoculars.enabled)
            actionItem("Camera settings...", Action.CAMERA_DIALOG, cameraWindow.open.get())
            ImGui.separator()
            actionItem("Save screenshot", Action.SCREENSHOT)
            Gui.endMenu()
        }
        if (Gui.beginMenu("Help")) {
            actionItem("Controls...", Action.CONTROLS_DIALOG)
            Gui.menuItem("ImGui demo", "", showDemo)
            ImGui.separator()
            if (Gui.menuItem("About ktheli...")) openAbout = true
            Gui.endMenu()
        }
        ImGui.endMainMenuBar()
    }

    private fun drawHud() {
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.AlwaysAutoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or
            ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoMouseInputs
        ImGui.setNextWindowPos(10f, menuBarHeight + 10f)
        ImGui.setNextWindowBgAlpha(0.35f)
        if (Gui.begin("##hud", flags = flags)) {
            val sim = session.runner.sim
            val body = sim.aircraft.body
            Gui.text("t %.2f s   tick %d   %d fps".format(sim.time, sim.tick, host.fps))
            Gui.text("alt %.2f m   speed %.1f m/s".format(-body.position.z, body.velocity.length()))
            Gui.text(session.input.active?.let { "Input: ${it.name}" } ?: "Input: keyboard")
            helicopter?.let { heli ->
                val mode = heli.transmitter.setup.modes[session.input.flightMode.coerceIn(0, heli.transmitter.setup.modes.lastIndex)]
                val target = heli.governorTargetRpm
                val delta = if (target > 0.0) "  (%+.0f)".format(heli.rotorRpm - target) else ""
                Gui.text("%s   headspeed %.0f rpm%s".format(mode.name, heli.rotorRpm, delta))
                if (session.input.throttleHold) {
                    val key = session.input.keyLabel(Action.THROTTLE_HOLD)?.let { " - $it to release" }.orEmpty()
                    Gui.textColored(1f, 0.5f, 0.2f, 1f, "THROTTLE HOLD$key")
                }
            }
            val wind = session.runner.environment.wind
            if (wind.speed > 0.0) {
                val here = sim.environment.windAt(body.position).length()
                Gui.text("Wind %.1f m/s from %s (%03.0f)   at heli %.1f m/s".format(
                    wind.speed, EnvironmentWindow.compass(wind.fromDegrees), wind.fromDegrees, here))
            }
            if (session.runner.paused) Gui.textColored(1f, 0.8f, 0.2f, 1f, "PAUSED")
            modePrompt()?.let { Gui.textColored(1f, 0.8f, 0.2f, 1f, it) }
        }
        Gui.end()
    }

    /** A banner in the middle of the view: what happened, and how to go again. */
    private fun drawCrash(crash: Crash) {
        val io = ImGui.getIO()
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.AlwaysAutoResize or ImGuiWindowFlags.NoSavedSettings or
            ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoMouseInputs
        ImGui.setNextWindowPos(io.displaySizeX / 2, menuBarHeight + (io.displaySizeY - menuBarHeight) * 0.3f, ImGuiCond.Always, 0.5f, 0.5f)
        ImGui.setNextWindowBgAlpha(0.6f)
        if (Gui.begin("##crash", flags = flags)) {
            ImGui.setWindowFontScale(2f)
            Gui.textColored(1f, 0.3f, 0.25f, 1f, "CRASHED")
            ImGui.setWindowFontScale(1f)
            Gui.text("%s at %.1f m/s".format(crash.impact.reason, crash.impact.speed))
            val key = shortcut(Action.RESET).ifEmpty { "Reset" }
            val settings = session.crashSettings
            if (settings.autoReset) {
                val left = (settings.delaySeconds - session.runner.crashedSeconds).coerceAtLeast(0.0)
                Gui.textDisabled("Resetting in %.0f s - %s to reset now".format(kotlin.math.ceil(left), key))
            } else {
                Gui.textDisabled("$key to reset")
            }
        }
        Gui.end()
    }

    /** A frame and magnification label over the binocular inset (which the 3D renderer draws underneath). */
    private fun drawBinocularFrame() {
        val io = ImGui.getIO()
        val b = session.camera.binoculars
        val viewHeight = io.displaySizeY - menuBarHeight
        val r = Binoculars.layout(io.displaySizeX, viewHeight, menuBarHeight, b)
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.NoBackground or ImGuiWindowFlags.NoInputs or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or ImGuiWindowFlags.NoNav
        ImGui.setNextWindowPos(r.x, r.y)
        ImGui.setNextWindowSize(r.width, r.height)
        if (Gui.begin("##binoculars", flags = flags)) {
            // The frame straddles the window edge, so draw it unclipped on the foreground list.
            val frame = ImGui.getForegroundDrawList()
            frame.addRect(r.x - 1f, r.y - 1f, r.x + r.width + 1f, r.y + r.height + 1f, ImColor.rgba(20, 20, 24, 230), 0f, 0, 4f)
            frame.addRect(r.x, r.y, r.x + r.width, r.y + r.height, ImColor.rgba(235, 235, 240, 230), 0f, 0, 1.5f)
            ImGui.getWindowDrawList().addRectFilled(r.x, r.y, r.x + 150f, r.y + ImGui.getTextLineHeightWithSpacing() + 6f, ImColor.rgba(0, 0, 0, 120))
            val m = Binoculars.inset(session.cameraPose, session.runner.model.visual.rotorRadius, b.framing, viewHeight.toDouble(), r.height.toDouble())
            Gui.textColored(1f, 1f, 1f, 0.95f, "BINOCULARS x%.1f".format(m.magnification))
        }
        Gui.end()
    }

    private fun modePrompt(): String? = when (val m = session.input.mode) {
        is InputController.Mode.Calibrating -> when (m.session.phase) {
            CalibrationSession.Phase.CENTER -> "Calibrating: keep sticks centered..."
            CalibrationSession.Phase.EXTREMES -> "Calibrating: move every stick to all extremes, then Finish"
        }
        is InputController.Mode.AssigningAxis -> "${m.channel.prompt}  (Esc cancels)"
        is InputController.Mode.CapturingKey -> "Press a key for \"${m.action.label}\"  (Esc cancels)"
        is InputController.Mode.CapturingButton -> "Press a controller button for \"${m.action.label}\"  (Esc cancels)"
        InputController.Mode.Fly -> null
    }

    private fun drawTelemetryWindow() {
        ImGui.setNextWindowPos(ImGui.getIO().displaySizeX - 620f, menuBarHeight + 20f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(600f, 420f, ImGuiCond.FirstUseEver)
        if (!Gui.begin("Telemetry", showTelemetry)) {
            Gui.end()
            return
        }
        val t = session.telemetry
        val xs = t.timesArray()
        if (ImGui.beginTabBar("telemetryGroups")) {
            for (group in t.groups) {
                if (Gui.beginTabItem(group.title)) {
                    if (ImPlot.beginPlot(group.title, -1f, -1f)) {
                        val sticks = group.title == "Sticks"
                        ImPlot.setupAxes("t [s]", "", 0, if (sticks) 0 else ImPlotAxisFlags.AutoFit)
                        ImPlot.setupAxisLimits(ImPlotAxis.X1, t.latestTime - t.window, t.latestTime, ImPlotCond.Always)
                        if (sticks) ImPlot.setupAxisLimits(ImPlotAxis.Y1, -1.05, 1.05, ImPlotCond.Always)
                        group.series.forEach { ImPlot.plotLine(it.name, xs, it.toArray()) }
                        ImPlot.endPlot()
                    }
                    Gui.endTabItem()
                }
            }
            ImGui.endTabBar()
        }
        Gui.end()
    }

    private fun drawAbout() {
        if (openAbout) {
            ImGui.openPopup("About ktheli")
            openAbout = false
        }
        val center = ImGui.getMainViewport().center
        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.Appearing, 0.5f, 0.5f)
        if (Gui.beginPopupModal("About ktheli", ImGuiWindowFlags.AlwaysAutoResize)) {
            Gui.text("ktheli - an RC helicopter simulator")
            Gui.textDisabled("Kotlin | libGDX | Dear ImGui")
            ImGui.spacing()
            Gui.text("Deterministic fixed-step physics at ${session.runner.sim.tickRateHz} Hz.")
            ImGui.spacing()
            if (Gui.button("Close", 120f, 0f)) ImGui.closeCurrentPopup()
            Gui.endPopup()
        }
    }
}

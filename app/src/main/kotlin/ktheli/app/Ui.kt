package ktheli.app

import imgui.ImGui
import imgui.extension.implot.ImPlot
import imgui.extension.implot.flag.ImPlotAxis
import imgui.extension.implot.flag.ImPlotAxisFlags
import imgui.extension.implot.flag.ImPlotCond
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import ktheli.input.Action
import ktheli.input.CalibrationSession
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.heli.Helicopter
import ktheli.physics.sim.Controls

/** Everything the UI can show or act on. */
class UiContext(
    val runner: SimRunner,
    val input: InputController,
    val view: ViewSettings,
    val telemetry: Telemetry,
    val controls: () -> Controls,
    val host: AppHost,
    val changeEnvironment: (EnvironmentSettings) -> Unit,
    val selectAircraft: (SimRunner.AircraftType) -> Unit,
)

/** Immediate-mode UI: call [draw] once per frame between ImGui newFrame/render. */
class Ui(private val ctx: UiContext) {
    val showHud = ImBoolean(true)
    val showTelemetry = ImBoolean(false)
    val controlsWindow = ControlsWindow(ctx.input, ctx.controls)
    val environmentWindow = EnvironmentWindow({ ctx.runner.environment }, ctx.changeEnvironment)
    private val showDemo = ImBoolean(false)
    private var openAbout = false

    /** Height of the main menu bar in logical pixels, valid after [draw]. */
    var menuBarHeight = 0f
        private set

    private val helicopter: Helicopter? get() = ctx.runner.sim.aircraft as? Helicopter

    fun draw() {
        ctx.input.flightModeCount = helicopter?.transmitter?.setup?.modes?.size ?: 1
        drawMenuBar()
        if (showHud.get()) drawHud()
        controlsWindow.draw(menuBarHeight)
        environmentWindow.draw(menuBarHeight)
        if (showTelemetry.get()) drawTelemetryWindow()
        if (showDemo.get()) ImGui.showDemoWindow(showDemo)
        drawAbout()
    }

    /** Executes a press-action, whether it came from the menu, a key, or a controller button. */
    fun perform(action: Action) {
        when (action) {
            Action.PAUSE -> ctx.runner.paused = !ctx.runner.paused
            Action.RESET -> reset()
            Action.FLIGHT_MODE -> ctx.input.cycleFlightMode()
            Action.THROTTLE_HOLD -> ctx.input.toggleThrottleHold()
            Action.CONTROLS_DIALOG -> controlsWindow.open.toggle()
            Action.ENVIRONMENT_DIALOG -> environmentWindow.open.toggle()
            Action.TELEMETRY -> showTelemetry.toggle()
            Action.HUD -> showHud.toggle()
            Action.AUTO_ZOOM -> ctx.view.autoZoom = !ctx.view.autoZoom
            Action.SCREENSHOT -> ctx.host.requestScreenshot()
            Action.NEXT_DEVICE -> ctx.input.selectNextDevice()
            Action.QUIT -> ctx.host.exit()
            Action.COLLECTIVE_ZERO -> ctx.input.digital.zeroCollective()
            else -> {} // hold actions are polled, not performed
        }
    }

    private fun ImBoolean.toggle() = set(!get())

    private fun reset() {
        ctx.runner.reset()
        ctx.telemetry.clear()
        ctx.input.resetSwitches()
    }

    private fun shortcut(action: Action) = ctx.input.keyFor(action)?.label().orEmpty()

    private fun actionItem(label: String, action: Action, selected: Boolean = false) {
        if (Gui.menuItem(label, shortcut(action), selected)) perform(action)
    }

    private fun drawMenuBar() {
        if (!ImGui.beginMainMenuBar()) return
        menuBarHeight = ImGui.getWindowHeight()

        if (Gui.beginMenu("Sim")) {
            actionItem(if (ctx.runner.paused) "Resume" else "Pause", Action.PAUSE)
            actionItem("Reset", Action.RESET)
            actionItem("Environment...", Action.ENVIRONMENT_DIALOG, environmentWindow.open.get())
            ImGui.separator()
            actionItem("Throttle hold", Action.THROTTLE_HOLD, ctx.input.throttleHold)
            helicopter?.let { heli ->
                if (Gui.beginMenu("Flight mode")) {
                    heli.transmitter.setup.modes.forEachIndexed { i, mode ->
                        if (Gui.menuItem(mode.name, if (i == 0) shortcut(Action.FLIGHT_MODE) else "", i == ctx.input.flightMode)) {
                            ctx.input.selectFlightMode(i)
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
            SimRunner.AircraftType.entries.forEach { type ->
                if (Gui.menuItem(type.label, "", type == ctx.runner.aircraftType)) {
                    ctx.selectAircraft(type)
                    reset()
                }
            }
            Gui.endMenu()
        }
        if (Gui.beginMenu("Input")) {
            actionItem("Controls...", Action.CONTROLS_DIALOG, controlsWindow.open.get())
            ImGui.separator()
            val joysticks = ctx.input.joysticks
            if (joysticks.isEmpty()) {
                Gui.textDisabled("No joystick connected - keyboard active")
            } else {
                val active = ctx.input.active
                joysticks.forEach { j ->
                    if (Gui.menuItem("${j.name}##${j.jid}", "", j == active)) ctx.input.selectDevice(j.guid)
                }
            }
            Gui.endMenu()
        }
        if (Gui.beginMenu("View")) {
            actionItem("HUD", Action.HUD, showHud.get())
            actionItem("Telemetry", Action.TELEMETRY, showTelemetry.get())
            actionItem("Camera auto-zoom", Action.AUTO_ZOOM, ctx.view.autoZoom)
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
            val sim = ctx.runner.sim
            val body = sim.aircraft.body
            Gui.text("t %.2f s   tick %d   %d fps".format(sim.time, sim.tick, ctx.host.fps))
            Gui.text("alt %.2f m   speed %.1f m/s".format(-body.position.z, body.velocity.length()))
            Gui.text(ctx.input.active?.let { "Input: ${it.name}" } ?: "Input: keyboard")
            helicopter?.let { heli ->
                val mode = heli.transmitter.setup.modes[ctx.input.flightMode.coerceIn(0, heli.transmitter.setup.modes.lastIndex)]
                val target = heli.governorTargetRpm
                val delta = if (target > 0.0) "  (%+.0f)".format(heli.rotorRpm - target) else ""
                Gui.text("%s   headspeed %.0f rpm%s".format(mode.name, heli.rotorRpm, delta))
                if (ctx.input.throttleHold) {
                    val key = ctx.input.keyFor(Action.THROTTLE_HOLD)?.label()?.let { " - $it to release" }.orEmpty()
                    Gui.textColored(1f, 0.5f, 0.2f, 1f, "THROTTLE HOLD$key")
                }
            }
            val wind = ctx.runner.environment.wind
            if (wind.speed > 0.0) {
                val here = sim.environment.windAt(body.position).length()
                Gui.text("Wind %.1f m/s from %s (%03.0f)   at heli %.1f m/s".format(
                    wind.speed, EnvironmentWindow.compass(wind.fromDegrees), wind.fromDegrees, here))
            }
            if (ctx.runner.paused) Gui.textColored(1f, 0.8f, 0.2f, 1f, "PAUSED")
            modePrompt()?.let { Gui.textColored(1f, 0.8f, 0.2f, 1f, it) }
        }
        Gui.end()
    }

    private fun modePrompt(): String? = when (val m = ctx.input.mode) {
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
        val t = ctx.telemetry
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
            Gui.text("Deterministic fixed-step physics at ${ctx.runner.sim.tickRateHz} Hz.")
            ImGui.spacing()
            if (Gui.button("Close", 120f, 0f)) ImGui.closeCurrentPopup()
            Gui.endPopup()
        }
    }
}

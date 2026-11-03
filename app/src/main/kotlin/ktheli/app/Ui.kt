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
import ktheli.physics.sim.Controls

/** Everything the UI can show or act on. */
class UiContext(
    val runner: SimRunner,
    val input: InputController,
    val view: ViewSettings,
    val telemetry: Telemetry,
    val controls: () -> Controls,
    val host: AppHost,
)

/** Immediate-mode UI: call [draw] once per frame between ImGui newFrame/render. */
class Ui(private val ctx: UiContext) {
    val showHud = ImBoolean(true)
    val showTelemetry = ImBoolean(false)
    val controlsWindow = ControlsWindow(ctx.input, ctx.controls)
    private val showDemo = ImBoolean(false)
    private var openAbout = false

    /** Height of the main menu bar in logical pixels, valid after [draw]. */
    var menuBarHeight = 0f
        private set

    fun draw() {
        drawMenuBar()
        if (showHud.get()) drawHud()
        controlsWindow.draw(menuBarHeight)
        if (showTelemetry.get()) drawTelemetryWindow()
        if (showDemo.get()) ImGui.showDemoWindow(showDemo)
        drawAbout()
    }

    /** Executes a press-action, whether it came from the menu, a key, or a controller button. */
    fun perform(action: Action) {
        when (action) {
            Action.PAUSE -> ctx.runner.paused = !ctx.runner.paused
            Action.RESET -> {
                ctx.runner.reset()
                ctx.telemetry.clear()
            }
            Action.CONTROLS_DIALOG -> controlsWindow.open.toggle()
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
            ImGui.separator()
            actionItem("Quit", Action.QUIT)
            Gui.endMenu()
        }
        if (Gui.beginMenu("Aircraft")) {
            Gui.menuItem("Toy heli (450 class)", "", true)
            Gui.textDisabled("Rotor models coming in Phase 2")
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
        ImGui.setNextWindowSize(600f, 520f, ImGuiCond.FirstUseEver)
        if (!Gui.begin("Telemetry", showTelemetry)) {
            Gui.end()
            return
        }
        val t = ctx.telemetry
        val xs = t.timesArray()
        val plotHeight = (ImGui.getContentRegionAvailY() - ImGui.getStyle().itemSpacingY) / 2f
        fun plot(title: String, series: List<Telemetry.Series>, yMin: Double? = null, yMax: Double? = null) {
            if (ImPlot.beginPlot(title, -1f, plotHeight)) {
                ImPlot.setupAxes("t [s]", "", 0, if (yMin == null) ImPlotAxisFlags.AutoFit else 0)
                ImPlot.setupAxisLimits(ImPlotAxis.X1, t.latestTime - t.window, t.latestTime, ImPlotCond.Always)
                if (yMin != null && yMax != null) ImPlot.setupAxisLimits(ImPlotAxis.Y1, yMin, yMax, ImPlotCond.Always)
                series.forEach { ImPlot.plotLine(it.name, xs, it.toArray()) }
                ImPlot.endPlot()
            }
        }
        plot("Flight", listOf(t.altitude, t.climbRate, t.speed))
        plot("Sticks", listOf(t.collective, t.roll, t.pitch, t.yaw), -1.05, 1.05)
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

package ktheli.app

import com.badlogic.gdx.Gdx
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
    val scene: Scene,
    val telemetry: Telemetry,
    val controls: () -> Controls,
    val requestScreenshot: () -> Unit,
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
            Action.AUTO_ZOOM -> ctx.scene.autoZoom = !ctx.scene.autoZoom
            Action.SCREENSHOT -> ctx.requestScreenshot()
            Action.NEXT_DEVICE -> ctx.input.selectNextDevice()
            Action.QUIT -> Gdx.app.exit()
            Action.COLLECTIVE_ZERO -> ctx.input.digital.zeroCollective()
            else -> {} // hold actions are polled, not performed
        }
    }

    private fun ImBoolean.toggle() = set(!get())

    private fun shortcut(action: Action) = ctx.input.keyFor(action)?.label().orEmpty()

    private fun actionItem(label: String, action: Action, selected: Boolean = false) {
        if (ImGui.menuItem(label, shortcut(action), selected)) perform(action)
    }

    private fun drawMenuBar() {
        if (!ImGui.beginMainMenuBar()) return
        menuBarHeight = ImGui.getWindowHeight()

        if (ImGui.beginMenu("Sim")) {
            actionItem(if (ctx.runner.paused) "Resume" else "Pause", Action.PAUSE)
            actionItem("Reset", Action.RESET)
            ImGui.separator()
            actionItem("Quit", Action.QUIT)
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Aircraft")) {
            ImGui.menuItem("Toy heli (450 class)", "", true)
            ImGui.textDisabled("Rotor models coming in Phase 2")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Input")) {
            actionItem("Controls...", Action.CONTROLS_DIALOG, controlsWindow.open.get())
            ImGui.separator()
            val joysticks = ctx.input.joysticks
            if (joysticks.isEmpty()) {
                ImGui.textDisabled("No joystick connected - keyboard active")
            } else {
                val active = ctx.input.active
                joysticks.forEach { j ->
                    if (ImGui.menuItem("${j.name}##${j.jid}", "", j == active)) ctx.input.selectDevice(j.guid)
                }
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("View")) {
            actionItem("HUD", Action.HUD, showHud.get())
            actionItem("Telemetry", Action.TELEMETRY, showTelemetry.get())
            actionItem("Camera auto-zoom", Action.AUTO_ZOOM, ctx.scene.autoZoom)
            ImGui.separator()
            actionItem("Save screenshot", Action.SCREENSHOT)
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Help")) {
            actionItem("Controls...", Action.CONTROLS_DIALOG)
            ImGui.menuItem("ImGui demo", "", showDemo)
            ImGui.separator()
            if (ImGui.menuItem("About ktheli...")) openAbout = true
            ImGui.endMenu()
        }
        ImGui.endMainMenuBar()
    }

    private fun drawHud() {
        val flags = ImGuiWindowFlags.NoDecoration or ImGuiWindowFlags.AlwaysAutoResize or
            ImGuiWindowFlags.NoSavedSettings or ImGuiWindowFlags.NoFocusOnAppearing or
            ImGuiWindowFlags.NoNav or ImGuiWindowFlags.NoMove or ImGuiWindowFlags.NoMouseInputs
        ImGui.setNextWindowPos(10f, menuBarHeight + 10f)
        ImGui.setNextWindowBgAlpha(0.35f)
        if (ImGui.begin("##hud", flags)) {
            val sim = ctx.runner.sim
            val body = sim.aircraft.body
            ImGui.text("t %.2f s   tick %d   %d fps".format(sim.time, sim.tick, Gdx.graphics.framesPerSecond))
            ImGui.text("alt %.2f m   speed %.1f m/s".format(-body.position.z, body.velocity.length()))
            ImGui.text(ctx.input.active?.let { "Input: ${it.name}" } ?: "Input: keyboard")
            if (ctx.runner.paused) ImGui.textColored(1f, 0.8f, 0.2f, 1f, "PAUSED")
            modePrompt()?.let { ImGui.textColored(1f, 0.8f, 0.2f, 1f, it) }
        }
        ImGui.end()
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
        ImGui.setNextWindowPos(Gdx.graphics.width - 620f, menuBarHeight + 20f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(600f, 520f, ImGuiCond.FirstUseEver)
        if (!ImGui.begin("Telemetry", showTelemetry)) {
            ImGui.end()
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
        ImGui.end()
    }


    private fun drawAbout() {
        if (openAbout) {
            ImGui.openPopup("About ktheli")
            openAbout = false
        }
        val center = ImGui.getMainViewport().center
        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.Appearing, 0.5f, 0.5f)
        if (ImGui.beginPopupModal("About ktheli", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("ktheli - an RC helicopter simulator")
            ImGui.textDisabled("Kotlin | libGDX | Dear ImGui")
            ImGui.spacing()
            ImGui.text("Deterministic fixed-step physics at ${ctx.runner.sim.tickRateHz} Hz.")
            ImGui.spacing()
            if (ImGui.button("Close", 120f, 0f)) ImGui.closeCurrentPopup()
            ImGui.endPopup()
        }
    }
}

package ktheli.app

import com.badlogic.gdx.Gdx
import imgui.ImGui
import imgui.extension.implot.ImPlot
import imgui.extension.implot.flag.ImPlotAxis
import imgui.extension.implot.flag.ImPlotAxisFlags
import imgui.extension.implot.flag.ImPlotCond
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiTableFlags
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImBoolean
import ktheli.input.Channel
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
    val showInput = ImBoolean(false)
    val showTelemetry = ImBoolean(false)
    private val showDemo = ImBoolean(false)
    private var openAbout = false
    private var openControlsHelp = false

    /** Height of the main menu bar in logical pixels, valid after [draw]. */
    var menuBarHeight = 0f
        private set

    fun draw() {
        drawMenuBar()
        if (showHud.get()) drawHud()
        if (showInput.get()) drawInputWindow()
        if (showTelemetry.get()) drawTelemetryWindow()
        if (showDemo.get()) ImGui.showDemoWindow(showDemo)
        drawPopups()
    }

    private fun drawMenuBar() {
        if (!ImGui.beginMainMenuBar()) return
        menuBarHeight = ImGui.getWindowHeight()

        if (ImGui.beginMenu("Sim")) {
            if (ImGui.menuItem(if (ctx.runner.paused) "Resume" else "Pause", "P")) togglePause()
            if (ImGui.menuItem("Reset", "R")) reset()
            ImGui.separator()
            if (ImGui.menuItem("Quit", "Ctrl+Q")) Gdx.app.exit()
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Aircraft")) {
            ImGui.menuItem("Toy heli (450 class)", "", true)
            ImGui.textDisabled("Rotor models coming in Phase 2")
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Input")) {
            ImGui.menuItem("Input setup…", "F1", showInput)
            ImGui.separator()
            val joysticks = ctx.input.joysticks
            if (joysticks.isEmpty()) {
                ImGui.textDisabled("No joystick connected — keyboard active")
            } else {
                val active = ctx.input.active
                joysticks.forEach { j ->
                    if (ImGui.menuItem("${j.name}##${j.jid}", "", j == active)) ctx.input.selectDevice(j.guid)
                }
            }
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("View")) {
            ImGui.menuItem("HUD", "", showHud)
            ImGui.menuItem("Telemetry", "F2", showTelemetry)
            if (ImGui.menuItem("Camera auto-zoom", "", ctx.scene.autoZoom)) ctx.scene.autoZoom = !ctx.scene.autoZoom
            ImGui.separator()
            if (ImGui.menuItem("Save screenshot", "F12")) ctx.requestScreenshot()
            ImGui.endMenu()
        }
        if (ImGui.beginMenu("Help")) {
            if (ImGui.menuItem("Controls…")) openControlsHelp = true
            ImGui.menuItem("ImGui demo", "", showDemo)
            ImGui.separator()
            if (ImGui.menuItem("About ktheli…")) openAbout = true
            ImGui.endMenu()
        }
        ImGui.endMainMenuBar()
    }

    fun togglePause() {
        ctx.runner.paused = !ctx.runner.paused
    }

    fun reset() {
        ctx.runner.reset()
        ctx.telemetry.clear()
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
            ImGui.text(ctx.input.active?.let { "Input: ${it.name}" } ?: "Input: keyboard (W/S, A/D, arrows)")
            if (ctx.runner.paused) ImGui.textColored(1f, 0.8f, 0.2f, 1f, "PAUSED")
            modePrompt()?.let { ImGui.textColored(1f, 0.8f, 0.2f, 1f, it) }
        }
        ImGui.end()
    }

    private fun modePrompt(): String? = when (val m = ctx.input.mode) {
        is InputController.Mode.Calibrating -> "Calibrating: move every stick to all extremes, then Finish (C)"
        is InputController.Mode.Assigning -> "${m.channel.prompt}  (Esc cancels)"
        InputController.Mode.Fly -> null
    }

    private fun drawInputWindow() {
        ImGui.setNextWindowPos(20f, menuBarHeight + 120f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(560f, 520f, ImGuiCond.FirstUseEver)
        if (!ImGui.begin("Input Setup", showInput)) {
            ImGui.end()
            return
        }
        val input = ctx.input
        val active = input.active
        val profile = input.activeProfile

        if (active == null || profile == null) {
            ImGui.textWrapped(
                "No joystick detected. Plug in the transmitter dongle or a game controller; it appears here " +
                    "automatically. Until then the keyboard flies: W/S collective, A/D yaw, arrows cyclic, K zeroes collective.",
            )
            ImGui.end()
            return
        }

        if (ImGui.beginCombo("Device", active.name)) {
            input.joysticks.forEach { j ->
                if (ImGui.selectable("${j.name}##${j.jid}", j == active)) input.selectDevice(j.guid)
            }
            ImGui.endCombo()
        }
        ImGui.textDisabled("GUID ${active.guid}")

        ImGui.separatorText("Channels")
        val tableFlags = ImGuiTableFlags.Borders or ImGuiTableFlags.RowBg or ImGuiTableFlags.SizingStretchProp
        if (ImGui.beginTable("channels", 6, tableFlags)) {
            ImGui.tableSetupColumn("Channel")
            ImGui.tableSetupColumn("Axis")
            ImGui.tableSetupColumn("Reverse")
            ImGui.tableSetupColumn("Centered")
            ImGui.tableSetupColumn("Output", 0, 2.5f)
            ImGui.tableSetupColumn("")
            ImGui.tableHeadersRow()
            val c = ctx.controls()
            val outputs = mapOf(
                Channel.COLLECTIVE to c.collective, Channel.ROLL to c.roll, Channel.PITCH to c.pitch, Channel.YAW to c.yaw,
            )
            Channel.entries.forEach { ch ->
                val b = profile.bindings[ch]
                ImGui.tableNextRow()
                ImGui.tableNextColumn(); ImGui.text(ch.name.lowercase())
                ImGui.tableNextColumn(); ImGui.text(b?.axis?.toString() ?: "—")
                ImGui.tableNextColumn()
                if (b != null && ImGui.checkbox("##rev$ch", b.reversed)) input.updateBinding(ch) { it.copy(reversed = !it.reversed) }
                ImGui.tableNextColumn()
                if (b != null && ImGui.checkbox("##ctr$ch", b.centered)) input.updateBinding(ch) { it.copy(centered = !it.centered) }
                ImGui.tableNextColumn(); bar(outputs.getValue(ch))
                ImGui.tableNextColumn()
                val assigning = (input.mode as? InputController.Mode.Assigning)?.channel == ch
                if (assigning) {
                    if (ImGui.button("Cancel##as$ch")) input.cancel()
                } else {
                    ImGui.beginDisabled(input.mode != InputController.Mode.Fly)
                    if (ImGui.button("Assign##as$ch")) input.startAssign(ch)
                    ImGui.endDisabled()
                }
            }
            ImGui.endTable()
        }
        ImGui.textDisabled("Assign: click, then move that stick up / right / forward.")
        ImGui.textDisabled("Centered: spring-loaded stick, 0 at center (off for throttle-style).")

        ImGui.separatorText("Calibration")
        when (val m = input.mode) {
            is InputController.Mode.Calibrating -> {
                ImGui.textColored(1f, 0.8f, 0.2f, 1f, "Move every stick to all of its extremes, then click Finish.")
                if (ImGui.button("Finish")) input.finishCalibration()
                ImGui.sameLine()
                if (ImGui.button("Cancel##cal")) input.cancel()
                rawAxes(m)
            }
            else -> {
                ImGui.text("Center all sticks (collective anywhere), then click Start.")
                ImGui.beginDisabled(m != InputController.Mode.Fly)
                if (ImGui.button("Start calibration")) input.startCalibration()
                ImGui.endDisabled()
                rawAxes(null)
            }
        }
        if (input.status.isNotEmpty()) {
            ImGui.separator()
            ImGui.text(input.status)
        }
        ImGui.end()
    }

    private fun rawAxes(calibrating: InputController.Mode.Calibrating?) {
        val active = ctx.input.active ?: return
        val profile = ctx.input.activeProfile
        if (ImGui.beginTable("axes", 3, ImGuiTableFlags.SizingStretchProp)) {
            active.axes.forEachIndexed { i, v ->
                ImGui.tableNextRow()
                ImGui.tableNextColumn(); ImGui.text("axis $i")
                ImGui.tableNextColumn(); bar(v.toDouble())
                ImGui.tableNextColumn()
                val bound = profile?.bindings?.filterValues { it.axis == i }?.keys?.joinToString { it.name.lowercase() }.orEmpty()
                val travel = calibrating?.session?.travel(i)?.let { "travel %.2f".format(it) }
                ImGui.textDisabled(travel ?: bound)
            }
            ImGui.endTable()
        }
    }

    /** A centered bar for a value in [-1, 1]. */
    private fun bar(v: Double) {
        ImGui.progressBar(((v.coerceIn(-1.0, 1.0) + 1.0) / 2.0).toFloat(), -1f, 0f, "%+.3f".format(v))
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

    private fun drawPopups() {
        if (openAbout) { ImGui.openPopup("About ktheli"); openAbout = false }
        if (openControlsHelp) { ImGui.openPopup("Controls"); openControlsHelp = false }

        val center = ImGui.getMainViewport().center
        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.Appearing, 0.5f, 0.5f)
        if (ImGui.beginPopupModal("About ktheli", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("ktheli — an RC helicopter simulator")
            ImGui.textDisabled("Kotlin · libGDX · Dear ImGui")
            ImGui.spacing()
            ImGui.text("Deterministic fixed-step physics at ${ctx.runner.sim.tickRateHz} Hz.")
            ImGui.spacing()
            if (ImGui.button("Close", 120f, 0f)) ImGui.closeCurrentPopup()
            ImGui.endPopup()
        }

        ImGui.setNextWindowPos(center.x, center.y, ImGuiCond.Appearing, 0.5f, 0.5f)
        if (ImGui.beginPopupModal("Controls", ImGuiWindowFlags.AlwaysAutoResize)) {
            if (ImGui.beginTable("keys", 2, ImGuiTableFlags.RowBg)) {
                listOf(
                    "P" to "Pause / resume",
                    "R" to "Reset",
                    "F1" to "Input setup",
                    "F2" to "Telemetry",
                    "F12" to "Screenshot",
                    "C" to "Start / finish calibration",
                    "1–4" to "Assign collective / roll / pitch / yaw",
                    "Tab" to "Next joystick",
                    "Esc" to "Cancel calibration / assignment",
                    "W / S" to "Keyboard collective up / down",
                    "A / D" to "Keyboard yaw",
                    "Arrows" to "Keyboard cyclic",
                    "K" to "Zero keyboard collective",
                    "Ctrl+Q" to "Quit",
                ).forEach { (key, what) ->
                    ImGui.tableNextRow()
                    ImGui.tableNextColumn(); ImGui.text(key)
                    ImGui.tableNextColumn(); ImGui.text(what)
                }
                ImGui.endTable()
            }
            if (ImGui.button("Close", 120f, 0f)) ImGui.closeCurrentPopup()
            ImGui.endPopup()
        }
    }
}

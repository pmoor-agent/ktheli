package ktheli.app

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiTabItemFlags
import imgui.flag.ImGuiTableFlags
import imgui.type.ImBoolean
import ktheli.input.Action
import ktheli.input.CalibrationSession
import ktheli.input.Channel
import ktheli.physics.sim.Controls

/** The "Controls" dialog: stick channels and calibration, key/button bindings, and a live device monitor. */
class ControlsWindow(private val input: InputController, private val controls: () -> Controls) {
    val open = ImBoolean(false)
    private var selectTab: String? = null

    enum class Tab(val title: String) { STICKS("Sticks"), BINDINGS("Buttons & keys"), MONITOR("Monitor") }

    fun show(tab: Tab) {
        open.set(true)
        selectTab = tab.title
    }

    private fun tab(t: Tab): Boolean {
        val flags = if (selectTab == t.title) ImGuiTabItemFlags.SetSelected else 0
        return ImGui.beginTabItem(t.title, flags)
    }

    fun draw(menuBarHeight: Float) {
        if (!open.get()) return
        ImGui.setNextWindowPos(20f, menuBarHeight + 120f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(600f, 560f, ImGuiCond.FirstUseEver)
        if (ImGui.begin("Controls", open)) {
            if (ImGui.beginTabBar("controlsTabs")) {
                if (tab(Tab.STICKS)) { sticksTab(); ImGui.endTabItem() }
                if (tab(Tab.BINDINGS)) { bindingsTab(); ImGui.endTabItem() }
                if (tab(Tab.MONITOR)) { monitorTab(); ImGui.endTabItem() }
                ImGui.endTabBar()
                selectTab = null
            }
            if (input.status.isNotEmpty()) {
                ImGui.separator()
                ImGui.text(input.status)
            }
        }
        ImGui.end()
    }

    // --- Sticks ----------------------------------------------------------------------------------

    private fun sticksTab() {
        val active = input.active
        val profile = input.activeProfile
        if (active == null || profile == null) {
            ImGui.textWrapped(
                "No joystick detected. Plug in the transmitter dongle or a game controller; it appears here " +
                    "automatically. Until then the keys on the \"Buttons & keys\" tab fly the heli.",
            )
            return
        }
        deviceCombo()

        ImGui.separatorText("Channels")
        val tableFlags = ImGuiTableFlags.Borders or ImGuiTableFlags.RowBg or ImGuiTableFlags.SizingStretchProp
        if (ImGui.beginTable("channels", 6, tableFlags)) {
            ImGui.tableSetupColumn("Channel")
            ImGui.tableSetupColumn("Source")
            ImGui.tableSetupColumn("Reverse")
            ImGui.tableSetupColumn("Centered")
            ImGui.tableSetupColumn("Output", 0, 2.5f)
            ImGui.tableSetupColumn("", 0, 1.6f)
            ImGui.tableHeadersRow()
            val c = controls()
            val outputs = mapOf(Channel.COLLECTIVE to c.collective, Channel.ROLL to c.roll, Channel.PITCH to c.pitch, Channel.YAW to c.yaw)
            Channel.entries.forEach { ch ->
                val b = profile.bindings[ch]
                ImGui.pushID(ch.name)
                ImGui.tableNextRow()
                ImGui.tableNextColumn(); ImGui.text(ch.label)
                ImGui.tableNextColumn()
                if (b != null) ImGui.text("axis ${b.axis}") else ImGui.textDisabled("keys/buttons")
                ImGui.tableNextColumn()
                if (b != null && ImGui.checkbox("##rev", b.reversed)) input.updateBinding(ch) { it.copy(reversed = !it.reversed) }
                ImGui.tableNextColumn()
                if (b != null && ImGui.checkbox("##ctr", b.centered)) input.updateBinding(ch) { it.copy(centered = !it.centered) }
                ImGui.tableNextColumn(); bar(outputs.getValue(ch))
                ImGui.tableNextColumn()
                if ((input.mode as? InputController.Mode.AssigningAxis)?.channel == ch) {
                    if (ImGui.smallButton("Cancel")) input.cancel()
                } else {
                    ImGui.beginDisabled(input.mode != InputController.Mode.Fly)
                    if (ImGui.smallButton("Assign")) input.startAssignAxis(ch)
                    if (b != null) {
                        ImGui.sameLine()
                        if (ImGui.smallButton("Clear")) input.clearAxis(ch)
                    }
                    ImGui.endDisabled()
                }
                ImGui.popID()
            }
            ImGui.endTable()
        }
        ImGui.textDisabled("Assign: click, then move that stick up / right / forward.")
        ImGui.textDisabled("Centered: spring-loaded stick, 0 at center (off for throttle-style).")
        ImGui.textDisabled("Cleared channels use the digital flight keys/buttons.")

        ImGui.separatorText("Calibration")
        when (val m = input.mode) {
            is InputController.Mode.Calibrating -> when (m.session.phase) {
                CalibrationSession.Phase.CENTER -> {
                    ImGui.textColored(1f, 0.8f, 0.2f, 1f, "Keep all sticks centered and still...")
                    ImGui.progressBar((m.centerSeconds / InputController.CENTER_SECONDS).toFloat(), -1f, 0f, "learning neutral range")
                    if (ImGui.button("Cancel##cal")) input.cancel()
                    calibrationAxes(m.session)
                }
                CalibrationSession.Phase.EXTREMES -> {
                    ImGui.textColored(1f, 0.8f, 0.2f, 1f, "Now move every stick to all of its extremes, then click Finish.")
                    if (ImGui.button("Finish")) input.finishCalibration()
                    ImGui.sameLine()
                    if (ImGui.button("Cancel##cal")) input.cancel()
                    calibrationAxes(m.session)
                }
            }
            else -> {
                ImGui.textWrapped("Center all sticks (collective anywhere) and click Start. Leave them still for a moment while the neutral range is learned, then sweep every stick to its extremes.")
                ImGui.beginDisabled(m != InputController.Mode.Fly)
                if (ImGui.button("Start calibration")) input.startCalibration()
                ImGui.endDisabled()
            }
        }
    }

    private fun calibrationAxes(session: CalibrationSession) {
        val active = input.active ?: return
        if (ImGui.beginTable("calAxes", 3, ImGuiTableFlags.SizingStretchProp)) {
            active.axes.forEachIndexed { i, v ->
                ImGui.tableNextRow()
                ImGui.tableNextColumn(); ImGui.text("axis $i")
                ImGui.tableNextColumn(); bar(v.toDouble())
                ImGui.tableNextColumn()
                ImGui.textDisabled("jitter +/-%.3f  travel %.2f".format(session.jitter(i), session.travel(i)))
            }
            ImGui.endTable()
        }
    }

    private fun deviceCombo() {
        val active = input.active ?: return
        if (ImGui.beginCombo("Device", active.name)) {
            input.joysticks.forEach { j ->
                if (ImGui.selectable("${j.name}##${j.jid}", j == active)) input.selectDevice(j.guid)
            }
            ImGui.endCombo()
        }
        ImGui.textDisabled("GUID ${active.guid}")
    }

    // --- Buttons & keys -------------------------------------------------------------------------

    private fun bindingsTab() {
        val hasDevice = input.active != null
        val tableFlags = ImGuiTableFlags.Borders or ImGuiTableFlags.RowBg or ImGuiTableFlags.SizingStretchProp
        if (ImGui.beginTable("bindings", 3, tableFlags)) {
            ImGui.tableSetupColumn("Action", 0, 1.4f)
            ImGui.tableSetupColumn("Keyboard")
            ImGui.tableSetupColumn("Controller")
            ImGui.tableHeadersRow()
            groupRow("Flight (for channels without an axis)")
            Action.entries.filter { it.flight }.forEach { bindingRow(it, hasDevice) }
            groupRow("Simulator")
            Action.entries.filterNot { it.flight }.forEach { bindingRow(it, hasDevice) }
            ImGui.endTable()
        }
        if (ImGui.button("Reset keyboard to defaults")) input.resetKeysToDefaults()
        ImGui.textDisabled("Click a binding, then press the key or button. Esc cancels.")
        ImGui.textDisabled("D-pad directions count as buttons.")
        if (!hasDevice) ImGui.textDisabled("Connect a controller to bind its buttons.")
    }

    private fun groupRow(title: String) {
        ImGui.tableNextRow()
        ImGui.tableNextColumn()
        ImGui.textColored(0.6f, 0.8f, 1f, 1f, title)
    }

    private fun bindingRow(action: Action, hasDevice: Boolean) {
        ImGui.pushID(action.name)
        ImGui.tableNextRow()
        ImGui.tableNextColumn(); ImGui.text(action.label)

        ImGui.tableNextColumn()
        bindingCell(
            id = "key",
            label = input.keyFor(action)?.label(),
            capturing = (input.mode as? InputController.Mode.CapturingKey)?.action == action,
            prompt = "press a key...",
            enabled = true,
            onCapture = { input.startCaptureKey(action) },
            onClear = { input.clearKey(action) },
        )

        ImGui.tableNextColumn()
        bindingCell(
            id = "btn",
            label = input.buttonFor(action)?.let { "button $it" },
            capturing = (input.mode as? InputController.Mode.CapturingButton)?.action == action,
            prompt = "press a button...",
            enabled = hasDevice,
            onCapture = { input.startCaptureButton(action) },
            onClear = { input.clearButton(action) },
        )
        ImGui.popID()
    }

    private fun bindingCell(
        id: String, label: String?, capturing: Boolean, prompt: String, enabled: Boolean,
        onCapture: () -> Unit, onClear: () -> Unit,
    ) {
        if (capturing) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.8f, 0.55f, 0.1f, 1f)
            if (ImGui.smallButton("$prompt##$id")) input.cancel()
            ImGui.popStyleColor()
            return
        }
        ImGui.beginDisabled(!enabled || input.mode != InputController.Mode.Fly)
        if (ImGui.smallButton("${label ?: "-"}##$id")) onCapture()
        if (label != null) {
            ImGui.sameLine()
            if (ImGui.smallButton("x##clear$id")) onClear()
            ImGui.setItemTooltip("Clear")
        }
        ImGui.endDisabled()
    }

    // --- Monitor ---------------------------------------------------------------------------------

    private fun monitorTab() {
        val active = input.active
        val profile = input.activeProfile
        if (active == null || profile == null) {
            ImGui.textWrapped("No joystick detected.")
            return
        }
        deviceCombo()

        ImGui.separatorText("Axes")
        if (ImGui.beginTable("axes", 3, ImGuiTableFlags.SizingStretchProp)) {
            active.axes.forEachIndexed { i, v ->
                ImGui.tableNextRow()
                ImGui.tableNextColumn(); ImGui.text("axis $i")
                ImGui.tableNextColumn(); bar(v.toDouble())
                ImGui.tableNextColumn()
                val bound = profile.bindings.filterValues { it.axis == i }.keys.joinToString { it.label }
                val cal = profile.calibration.getOrNull(i)
                val neutral = cal?.let { "neutral +/-%.3f".format((it.neutralHigh - it.neutralLow) / 2) }.orEmpty()
                ImGui.textDisabled(listOf(bound, neutral).filter { it.isNotEmpty() }.joinToString("  "))
            }
            ImGui.endTable()
        }

        ImGui.separatorText("Buttons")
        val boundTo = profile.buttons.entries.associate { (action, button) -> button to action }
        active.buttons.forEachIndexed { i, down ->
            if (i % 10 != 0) ImGui.sameLine()
            val color = if (down) floatArrayOf(0.2f, 0.75f, 0.3f) else floatArrayOf(0.25f, 0.25f, 0.3f)
            ImGui.pushStyleColor(ImGuiCol.Button, color[0], color[1], color[2], 1f)
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, color[0], color[1], color[2], 1f)
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, color[0], color[1], color[2], 1f)
            ImGui.button("%2d##mon$i".format(i), 34f, 0f)
            ImGui.popStyleColor(3)
            ImGui.setItemTooltip(boundTo[i]?.label ?: "button $i (unbound)")
        }
    }

    /** A centered bar for a value in [-1, 1]. */
    private fun bar(v: Double) {
        ImGui.progressBar(((v.coerceIn(-1.0, 1.0) + 1.0) / 2.0).toFloat(), -1f, 0f, "%+.3f".format(v))
    }
}

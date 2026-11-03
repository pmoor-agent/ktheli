package ktheli.app

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean

/** The "Camera" dialog: view mode and its options. */
class CameraWindow(private val current: () -> CameraSettings, private val change: (CameraSettings) -> Unit) {
    val open = ImBoolean(false)

    fun draw(menuBarHeight: Float) {
        if (!open.get()) return
        ImGui.setNextWindowPos(660f, menuBarHeight + 60f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(400f, 0f, ImGuiCond.FirstUseEver)
        if (Gui.begin("Camera", open)) {
            val s = current()
            ImGui.separatorText("View")
            CameraMode.entries.forEachIndexed { i, mode ->
                if (i > 0) ImGui.sameLine()
                if (Gui.radioButton(mode.label, s.mode == mode)) change(s.copy(mode = mode))
            }

            ImGui.separatorText(CameraMode.PILOT.label)
            if (Gui.checkbox("Auto-zoom", s.autoZoom)) change(s.copy(autoZoom = !s.autoZoom))
            Gui.textDisabled("Zooms so the model keeps a usable size at any distance.")
            Gui.beginDisabled(s.autoZoom)
            Gui.slider("Field of view##pilot", s.pilotFov, 15.0, 90.0, "%.0f deg")?.let { change(s.copy(pilotFov = it)) }
            Gui.endDisabled()

            ImGui.separatorText(CameraMode.FOLLOW.label)
            Gui.slider("Distance##follow", s.followDistance, 0.8, 8.0, "%.1f m")?.let { change(s.copy(followDistance = it)) }
            Gui.slider("Height##follow", s.followHeight, -0.5, 3.0, "%.1f m")?.let { change(s.copy(followHeight = it)) }
            Gui.slider("Smoothing##follow", s.followSmoothing, 0.1, 2.0, "%.2f s")?.let { change(s.copy(followSmoothing = it)) }
            Gui.slider("Field of view##follow", s.followFov, 30.0, 100.0, "%.0f deg")?.let { change(s.copy(followFov = it)) }
            Gui.textDisabled("Follows the nose heading; smoothing is how lazily it swings round.")
        }
        Gui.end()
    }
}

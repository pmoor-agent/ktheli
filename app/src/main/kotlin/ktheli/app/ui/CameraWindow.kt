package ktheli.app.ui

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean
import ktheli.app.session.BinocularSettings
import ktheli.app.session.CameraMode
import ktheli.app.session.CameraSettings
import ktheli.app.session.Corner

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

            ImGui.separatorText("Binoculars")
            val b = s.binoculars
            fun binoculars(updated: BinocularSettings) = change(s.copy(binoculars = updated))
            if (Gui.checkbox("Show binoculars", b.enabled)) binoculars(b.copy(enabled = !b.enabled))
            Gui.textDisabled("A magnified inset from the same viewpoint, sized to the model.")
            Gui.beginDisabled(!b.enabled)
            Gui.slider("Size##bino", b.size, 0.15, 0.6, "%.2f of view")?.let { binoculars(b.copy(size = it)) }
            Gui.slider("Framing##bino", b.framing, 0.2, 1.0, "model fills %.2f")?.let { binoculars(b.copy(framing = it)) }
            Corner.entries.forEachIndexed { i, corner ->
                if (i > 0) ImGui.sameLine()
                if (Gui.radioButton("${corner.label}##bino", b.corner == corner)) binoculars(b.copy(corner = corner))
            }
            Gui.endDisabled()
        }
        Gui.end()
    }
}

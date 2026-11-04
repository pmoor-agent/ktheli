package ktheli.app.ui

import imgui.ImGui

/** The app's look; shared with the headless test driver so UI snapshots match. */
fun applyKtheliStyle() {
    ImGui.styleColorsClassic()
    ImGui.getStyle().apply {
        windowRounding = 0f
        frameRounding = 0f // the built-in pixel font stays crisp only at its native size
    }
}

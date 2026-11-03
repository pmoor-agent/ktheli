package ktheli.app

import imgui.ImGui
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean
import ktheli.physics.env.AtmosphereSettings
import ktheli.physics.env.EnvironmentSettings
import ktheli.physics.env.WindSettings

/** The "Environment" dialog: wind, turbulence, gusts, and the atmosphere at the flying field. */
class EnvironmentWindow(
    private val current: () -> EnvironmentSettings,
    private val change: (EnvironmentSettings) -> Unit,
) {
    val open = ImBoolean(false)

    fun draw(menuBarHeight: Float) {
        if (!open.get()) return
        ImGui.setNextWindowPos(660f, menuBarHeight + 120f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(440f, 0f, ImGuiCond.FirstUseEver)
        if (Gui.begin("Environment", open)) {
            val s = current()
            ImGui.separatorText("Presets")
            PRESETS.forEachIndexed { i, (name, preset) ->
                if (i > 0) ImGui.sameLine()
                if (Gui.button(name)) change(preset)
            }

            ImGui.separatorText("Wind")
            val w = s.wind
            fun wind(updated: WindSettings) = change(s.copy(wind = updated))
            Gui.slider("Speed##wind", w.speed, 0.0, 15.0, "%.1f m/s at 10 m")?.let { wind(w.copy(speed = it)) }
            Gui.slider("From##wind", w.fromDegrees, 0.0, 359.0, "%.0f deg (${compass(w.fromDegrees)})")?.let { wind(w.copy(fromDegrees = it)) }
            Gui.slider("Turbulence##wind", w.turbulence, 0.0, 2.0, "%.1f x")?.let { wind(w.copy(turbulence = it)) }
            Gui.slider("Gusts##wind", w.gustiness, 0.0, 1.0, "%.2f")?.let { wind(w.copy(gustiness = it)) }
            Gui.slider("Shear##wind", w.shearExponent, 0.0, 0.4, "%.2f")?.let { wind(w.copy(shearExponent = it)) }
            Gui.textDisabled("Weaker near the ground (shear); turbulence scales with wind.")

            ImGui.separatorText("Atmosphere")
            val a = s.atmosphere
            fun atmosphere(updated: AtmosphereSettings) = change(s.copy(atmosphere = updated))
            Gui.slider("Temperature##air", a.temperatureC, -10.0, 45.0, "%.0f C")?.let { atmosphere(a.copy(temperatureC = it)) }
            Gui.slider("Field elevation##air", a.elevation, 0.0, 4000.0, "%.0f m")?.let { atmosphere(a.copy(elevation = it)) }
            Gui.text("Air density %.3f kg/m^3 (%.0f%% of sea level)".format(a.density, 100 * a.density / SEA_LEVEL))
        }
        Gui.end()
    }

    companion object {
        private const val SEA_LEVEL = 1.225

        val PRESETS = listOf(
            "Calm" to EnvironmentSettings.CALM,
            "Light breeze" to EnvironmentSettings.LIGHT_BREEZE,
            "Breezy" to EnvironmentSettings.BREEZY,
            "Gusty" to EnvironmentSettings.GUSTY_AFTERNOON,
            "High & hot" to EnvironmentSettings.HIGH_AND_HOT,
        )

        fun compass(degrees: Double): String {
            val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return names[(((degrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
        }
    }
}

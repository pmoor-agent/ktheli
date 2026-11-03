package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.HeadlessGl
import ktheli.app.harness.UiDriver
import org.lwjgl.glfw.GLFW.GLFW_HAT_LEFT
import org.lwjgl.glfw.GLFW.GLFW_HAT_UP
import java.nio.file.Paths
import kotlin.test.Test

/**
 * Renders a tour of the UI to `app/build/ui-snapshots/` for visual review (no assertions): full frames with the
 * 3D scene where headless OpenGL is available, else the UI alone rasterized on the CPU.
 */
class UiSnapshotTest {
    private val out = Paths.get("build/ui-snapshots")

    @Test
    fun `ui tour`() = UiDriver(gl = HeadlessGl.available).use { ui ->
        ui.click("Input")
        ui.snapshot(out.resolve("01-input-menu.png"))
        ui.click("Input/Controls...")
        ui.snapshot(out.resolve("02-controls-sticks.png"))

        ui.click("Controls/Sticks/Start calibration")
        ui.seconds(2.0)
        ui.joystick.axes[2] = 0.7f
        ui.frame()
        ui.snapshot(out.resolve("03-calibrating.png"))
        ui.press(Keys.ESCAPE)
        ui.joystick.axes[2] = 0f

        ui.click("Controls/Buttons & keys")
        ui.click("Controls/Buttons & keys/Pause / resume/btn")
        ui.snapshot(out.resolve("04-capturing-button.png"))
        ui.pressButton(9)

        ui.click("Controls/Monitor")
        ui.joystick.buttons[3] = true // not button 9: that one now pauses
        ui.joystick.hats[0] = GLFW_HAT_UP or GLFW_HAT_LEFT
        ui.joystick.axes[0] = -0.4f
        ui.frame()
        ui.snapshot(out.resolve("05-monitor.png"))
        ui.joystick.buttons[3] = false
        ui.joystick.hats[0] = 0
        ui.joystick.axes[0] = 0f

        ui.press(Keys.F1)
        ui.hold(Keys.W, 1.0)
        ui.press(Keys.F2)
        ui.seconds(2.0)
        ui.snapshot(out.resolve("06-telemetry.png"))
    }
}

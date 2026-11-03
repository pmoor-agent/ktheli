package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.UiDriver
import ktheli.app.harness.uiTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CameraUiTest {
    @Test
    fun `view menu, shortcut and dialog switch the camera`() = uiTest { ui ->
        assertEquals(CameraMode.PILOT, ui.core.session.view.camera.mode)
        ui.menu("View", "Follow")
        assertEquals(CameraMode.FOLLOW, ui.core.session.view.camera.mode)
        ui.press(Keys.V)
        assertEquals(CameraMode.PILOT, ui.core.session.view.camera.mode)
        ui.press(Keys.Z) // auto-zoom still has its shortcut
        assertFalse(ui.core.session.view.camera.autoZoom)
        ui.press(Keys.F5)
        ui.click("Camera/Follow")
        assertEquals(CameraMode.FOLLOW, ui.core.session.view.camera.mode)
        ui.click("Camera/Auto-zoom")
        assertEquals(true, ui.core.session.view.camera.autoZoom)
        ui.waitFor("Camera/Distance")
        ui.waitFor("Camera/Smoothing")
    }

    @Test
    fun `camera settings persist across restarts`() {
        val dir = Files.createTempDirectory("ktheli-camera")
        try {
            UiDriver(configDir = dir).use { ui ->
                ui.press(Keys.V)
                ui.press(Keys.Z)
            }
            UiDriver(configDir = dir).use { ui ->
                assertEquals(CameraMode.FOLLOW, ui.core.session.view.camera.mode)
                assertFalse(ui.core.session.view.camera.autoZoom)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

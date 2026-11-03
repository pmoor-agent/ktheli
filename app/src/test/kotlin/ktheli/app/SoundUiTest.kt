package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.uiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundUiTest {
    @Test
    fun `M mutes and unmutes, and the setting persists`() = uiTest { ui ->
        assertTrue(ui.core.sound.enabled)
        assertTrue(ui.core.soundParams().volume > 0)
        ui.press(Keys.M)
        assertFalse(ui.core.sound.enabled)
        assertEquals(0.0, ui.core.soundParams().volume)
        assertFalse(SettingsStore(ui.configDir.resolve("settings.json")).load().sound.enabled)
        ui.menu("Sim", "Sound", "Sound on")
        assertTrue(ui.core.sound.enabled)
    }

    @Test
    fun `pausing falls silent`() = uiTest { ui ->
        ui.press(Keys.P)
        assertEquals(0.0, ui.core.soundParams().volume)
        ui.press(Keys.P)
        assertTrue(ui.core.soundParams().volume > 0)
    }

    @Test
    fun `the sound menu has a volume slider`() = uiTest { ui ->
        ui.click("Sim")
        ui.click("Sim/Sound")
        ui.waitFor("Sim/Sound/Volume")
    }
}

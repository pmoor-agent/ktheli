package ktheli.input

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InputTest {
    @Test
    fun `centered axis maps captured center to zero`() {
        val cal = AxisCalibration(min = -0.8f, center = 0.1f, max = 0.9f)
        assertEquals(0.0, cal.normalize(0.1f, centered = true), 1e-6)
        assertEquals(1.0, cal.normalize(0.9f, centered = true), 1e-6)
        assertEquals(-1.0, cal.normalize(-0.8f, centered = true), 1e-6)
        assertEquals(1.0, cal.normalize(5f, centered = true)) // clamped
    }

    @Test
    fun `non-centered axis is linear over full travel`() {
        val cal = AxisCalibration(min = -0.6f, center = -0.6f, max = 1.0f)
        assertEquals(-1.0, cal.normalize(-0.6f, centered = false), 1e-6)
        assertEquals(0.0, cal.normalize(0.2f, centered = false), 1e-6)
    }

    @Test
    fun `calibration session tracks extremes and ignores idle axes`() {
        val s = CalibrationSession(floatArrayOf(0f, 0f))
        s.observe(floatArrayOf(-0.7f, 0.01f))
        s.observe(floatArrayOf(0.9f, -0.01f))
        assertEquals(listOf(AxisCalibration(-0.7f, 0f, 0.9f), AxisCalibration()), s.finish())
    }

    @Test
    fun `detector finds the moved axis and its direction`() {
        val d = AxisDetector(floatArrayOf(0f, 0f, 0f))
        assertNull(d.observe(floatArrayOf(0.1f, 0f, 0f)))
        assertEquals(AxisDetector.Detection(2, reversed = true), d.observe(floatArrayOf(0.1f, 0f, -0.8f)))
    }

    @Test
    fun `profile maps axes to controls and survives a save-load round trip`() {
        val profile = DeviceProfile("guid", "Spektrum")
            .withBinding(Channel.COLLECTIVE, AxisDetector.Detection(0, reversed = false))
            .withBinding(Channel.ROLL, AxisDetector.Detection(1, reversed = true))
        val c = profile.toControls(floatArrayOf(1f, 0.5f))
        assertEquals(1.0, c.collective, 1e-6)
        assertEquals(-0.5, c.roll, 1e-6)

        val dir = Files.createTempDirectory("ktheli")
        val store = ProfileStore(dir.resolve("input.json"))
        val config = InputConfig(mapOf(profile.guid to profile), activeGuid = profile.guid)
        store.save(config)
        assertEquals(config, store.load())
    }
}

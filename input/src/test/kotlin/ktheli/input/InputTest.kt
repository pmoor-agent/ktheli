package ktheli.input

import kotlinx.serialization.json.Json
import ktheli.physics.sim.Controls
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `neutral band reads as zero and the rest of the travel is rescaled`() {
        val cal = AxisCalibration(min = -1f, center = 0f, max = 1f, neutralLow = -0.1f, neutralHigh = 0.1f)
        assertEquals(0.0, cal.normalize(0.08f, centered = true))
        assertEquals(0.0, cal.normalize(-0.1f, centered = true))
        assertEquals(0.5, cal.normalize(0.55f, centered = true), 1e-6)
        assertEquals(-1.0, cal.normalize(-1f, centered = true), 1e-6)
    }

    @Test
    fun `non-centered axis is linear over full travel and ignores the neutral band`() {
        val cal = AxisCalibration(min = -0.6f, center = -0.6f, max = 1.0f, neutralLow = -0.7f, neutralHigh = -0.5f)
        assertEquals(-1.0, cal.normalize(-0.6f, centered = false), 1e-6)
        assertEquals(0.0, cal.normalize(0.2f, centered = false), 1e-6)
    }

    @Test
    fun `calibration learns the neutral band and the extremes`() {
        val s = CalibrationSession(2)
        s.observe(floatArrayOf(0.02f, 0f))
        s.observe(floatArrayOf(-0.02f, 0.001f)) // jitter ±0.02 around 0 on axis 0
        s.beginExtremes()
        s.observe(floatArrayOf(-0.7f, 0.01f))
        s.observe(floatArrayOf(0.9f, -0.01f))
        val (moved, idle) = s.finish()
        assertEquals(-0.7f, moved.min)
        assertEquals(0.9f, moved.max)
        assertEquals(0f, moved.center, 1e-6f)
        assertEquals(0.02f * 1.5f + 0.01f, moved.neutralHigh, 1e-6f)
        assertEquals(-moved.neutralHigh, moved.neutralLow, 1e-6f)
        assertEquals(AxisCalibration(), idle) // never moved: nominal range
    }

    @Test
    fun `neutral band never swallows more than a quarter of the travel`() {
        val s = CalibrationSession(1)
        s.observe(floatArrayOf(0.4f))
        s.observe(floatArrayOf(-0.4f)) // pathological "jitter"
        s.beginExtremes()
        s.observe(floatArrayOf(1f))
        s.observe(floatArrayOf(-1f))
        assertEquals(0.25f, s.finish().single().neutralHigh, 1e-6f)
    }

    @Test
    fun `axis detector finds the moved axis and its direction`() {
        val d = AxisDetector(floatArrayOf(0f, 0f, 0f))
        assertNull(d.observe(floatArrayOf(0.1f, 0f, 0f)))
        assertEquals(AxisDetector.Detection(2, reversed = true), d.observe(floatArrayOf(0.1f, 0f, -0.8f)))
    }

    @Test
    fun `button detector needs held buttons to be released first`() {
        val d = ButtonDetector(booleanArrayOf(true, false))
        assertNull(d.observe(booleanArrayOf(true, false)))
        assertNull(d.observe(booleanArrayOf(false, false)))
        assertEquals(0, d.observe(booleanArrayOf(true, false)))
        assertEquals(1, ButtonDetector(booleanArrayOf(false, false)).observe(booleanArrayOf(false, true)))
    }

    @Test
    fun `unassigned channels fall back to digital input`() {
        val profile = DeviceProfile("guid", "pad")
            .withBinding(Channel.ROLL, AxisDetector.Detection(0, reversed = true))
        val c = profile.toControls(floatArrayOf(0.5f), fallback = Controls(collective = 0.3, roll = 0.9, yaw = -0.2))
        assertEquals(-0.5, c.roll, 1e-6)
        assertEquals(0.3, c.collective)
        assertEquals(-0.2, c.yaw)
    }

    @Test
    fun `button bindings move between actions`() {
        val p = DeviceProfile("guid", "pad").withButton(Action.PAUSE, 3).withButton(Action.RESET, 3)
        assertEquals(mapOf(Action.RESET to 3), p.buttons)
    }

    @Test
    fun `key overrides displace conflicting bindings and can be cleared`() {
        val defaults = mapOf(Action.PAUSE to KeyChord(44), Action.RESET to KeyChord(46))
        val c = InputConfig().withKey(Action.PAUSE, KeyChord(46), defaults)
        assertEquals(KeyChord(46), c.keyFor(Action.PAUSE, defaults))
        assertNull(c.keyFor(Action.RESET, defaults))
        assertNull(c.withoutKey(Action.PAUSE).keyFor(Action.PAUSE, defaults))
        assertEquals(KeyChord(46), c.withDefaultKeys().keyFor(Action.RESET, defaults))
    }

    @Test
    fun `digital collective integrates and holds`() {
        val d = DigitalFlight(collectiveRate = 0.5)
        repeat(10) { d.update({ it == Action.COLLECTIVE_UP }, 0.1) }
        val c = d.update({ it == Action.ROLL_LEFT }, 0.1)
        assertEquals(0.5, c.collective, 1e-9)
        assertTrue(c.roll < 0)
        d.zeroCollective()
        assertEquals(0.0, d.update({ false }, 0.1).collective)
    }

    @Test
    fun `profiles saved by the previous version still load`() {
        val old = """{"devices":{"g":{"guid":"g","name":"pad","calibration":[{"min":-0.9,"center":0.05,"max":0.95}],
            "bindings":{"ROLL":{"axis":0}}}},"activeGuid":"g"}"""
        val config = Json { ignoreUnknownKeys = true }.decodeFromString(InputConfig.serializer(), old)
        val cal = config.devices.getValue("g").calibration.single()
        assertEquals(0.05f, cal.neutralLow)
        assertEquals(0.05f, cal.neutralHigh)
        assertEquals(emptyMap(), config.devices.getValue("g").buttons)
    }

    @Test
    fun `config survives a save-load round trip`() {
        val profile = DeviceProfile("guid", "Spektrum")
            .withBinding(Channel.COLLECTIVE, AxisDetector.Detection(0, reversed = false))
            .withButton(Action.PAUSE, 7)
        val config = InputConfig(mapOf(profile.guid to profile), activeGuid = profile.guid)
            .withKey(Action.RESET, KeyChord(46, ctrl = true), emptyMap())
            .withoutKey(Action.QUIT)
        val store = ProfileStore(Files.createTempDirectory("ktheli").resolve("input.json"))
        store.save(config)
        assertEquals(config, store.load())
    }
}

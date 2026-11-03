package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.FrameProfiler.Section
import ktheli.app.harness.glTest
import ktheli.app.harness.uiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PerformanceTest {
    @Test
    fun `stats turn section times into headroom and a real-time factor`() {
        val p = FrameProfiler(tickSeconds = 1.0 / 2000)
        repeat(200) {
            p.add(Section.PHYSICS, 1_000_000) // 1 ms for 33 ticks
            p.ticks(33)
            p.add(Section.WORLD, 2_000_000)
            p.gpu(4_000_000)
            p.endFrame(1.0 / 60)
        }
        val s = p.stats
        assertEquals(3.0, s.cpuMs, 1e-6)
        assertEquals(4.0, s.busyMs, 1e-6) // GPU-bound
        assertEquals(1 - 4.0 / 16.667, s.headroom, 1e-3)
        assertEquals(250.0, s.maxFps, 1e-3)
        assertEquals(1000.0 / 33, s.microsPerTick, 1e-3)
        assertEquals(500.0 / (1000.0 / 33), s.realtimeFactor, 1e-3) // 0.5 ms of simulated time per 30.3 µs
        assertEquals(200, p.history.size)
    }

    @Test
    fun `peaks show the worst recent frame while averages stay smooth`() {
        val p = FrameProfiler()
        repeat(100) {
            p.add(Section.WORLD, if (it == 95) 30_000_000 else 2_000_000)
            p.endFrame(1.0 / 60)
        }
        assertEquals(30.0, p.stats.peakCpuMs, 1e-6)
        assertTrue(p.stats.cpuMs < 5.0)
        assertEquals(0.0, p.stats.headroom.coerceAtMost(0.0)) // never negative
    }

    @Test
    fun `the performance window opens from its key and the view menu`() = uiTest { ui ->
        ui.press(Keys.F6)
        ui.waitForText("Physics:")
        ui.waitForText("Headroom")
        ui.menu("View", "Performance")
        assertTrue(!ui.core.ui.performanceWindow.open.get())
    }

    @Test
    fun `rendering for real yields physics, CPU and GPU timings`() = glTest(640, 360) { ui ->
        ui.press(Keys.H)
        ui.press(Keys.F)
        ui.press(Keys.F6)
        ui.seconds(3.0)
        val s = ui.core.profiler.stats
        println("perf: frame %.1f ms, cpu %.2f ms (world %.2f, physics %.2f), gpu %s ms, %.1f ticks/frame, %.1f us/tick, %.0fx real time"
            .format(s.frameMs, s.cpuMs, s.sections[Section.WORLD], s.physicsMs, s.gpuMs?.let { "%.2f".format(it) }, s.ticksPerFrame, s.microsPerTick, s.realtimeFactor))
        assertEquals(2000.0 / 60, s.ticksPerFrame, 1.0)
        assertTrue(s.physicsMs > 0 && s.realtimeFactor > 1.0, "physics keeps up with real time")
        assertTrue((s.sections[Section.WORLD] ?: 0.0) > 0.0)
        assertNotNull(s.gpuMs, "GPU timer queries work").let { assertTrue(it > 0.0) }
        ui.snapshot(java.nio.file.Paths.get("build/ui-snapshots/10-performance.png"))
    }
}

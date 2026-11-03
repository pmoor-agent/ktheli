package ktheli.app

import imgui.ImGui
import imgui.extension.implot.ImPlot
import imgui.extension.implot.flag.ImPlotAxis
import imgui.extension.implot.flag.ImPlotAxisFlags
import imgui.extension.implot.flag.ImPlotCond
import imgui.flag.ImGuiCond
import imgui.type.ImBoolean

/** Where frame time goes (physics vs rendering, CPU vs GPU) and how much headroom is left. */
class PerformanceWindow(private val profiler: () -> FrameProfiler, private val tickRateHz: () -> Int) {
    val open = ImBoolean(false)

    fun draw(menuBarHeight: Float) {
        if (!open.get()) return
        ImGui.setNextWindowPos(20f, menuBarHeight + 120f, ImGuiCond.FirstUseEver)
        ImGui.setNextWindowSize(560f, 470f, ImGuiCond.FirstUseEver)
        if (Gui.begin("Performance", open)) {
            val p = profiler()
            val s = p.stats
            val budget = s.frameMs

            Gui.text("Frame %.1f ms (%.0f fps), worst %.1f ms".format(s.frameMs, s.fps, s.peakFrameMs))
            val limit = if ((s.gpuMs ?: 0.0) > s.cpuMs) "GPU" else "CPU"
            val headroomColor = if (s.headroom > 0.3) floatArrayOf(0.4f, 0.9f, 0.4f) else if (s.headroom > 0.1) floatArrayOf(1f, 0.8f, 0.2f) else floatArrayOf(1f, 0.35f, 0.3f)
            Gui.textColored(headroomColor[0], headroomColor[1], headroomColor[2], 1f,
                "Headroom %.0f%%: work fits %.0f fps (%s-bound)".format(100 * s.headroom, s.maxFps, limit))
            Gui.text("Physics: %.1f ticks/frame at %d Hz, %.1f us/tick = %.0fx real time".format(
                s.ticksPerFrame, tickRateHz(), s.microsPerTick, s.realtimeFactor))

            ImGui.separatorText("Per frame (avg ms, share of frame)")
            // The built-in font is monospace: padded text lines line up without a table.
            fun row(label: String, ms: Double) {
                val share = if (budget > 0) ms / budget else 0.0
                Gui.text("%-18s %6.2f %4.0f%%".format(label, ms, 100 * share))
                ImGui.sameLine()
                ImGui.progressBar(share.toFloat().coerceIn(0f, 1f), -1f, ImGui.getTextLineHeight(), "")
            }
            for (section in FrameProfiler.Section.entries) row(section.label, s.sections[section] ?: 0.0)
            row("CPU total", s.cpuMs)
            s.gpuMs?.let { row("GPU", it) } ?: Gui.textDisabled("GPU: no timer queries on this driver")
            Gui.textDisabled("Worst recent frame: CPU %.1f ms%s".format(s.peakCpuMs, s.peakGpuMs?.let { ", GPU %.1f ms".format(it) }.orEmpty()))

            val frames = p.history.toList()
            if (frames.isNotEmpty() && ImPlot.beginPlot("ms per frame", -1f, -1f)) {
                val xs = DoubleArray(frames.size) { it.toDouble() - frames.size }
                ImPlot.setupAxes("frames", "ms", 0, ImPlotAxisFlags.AutoFit)
                ImPlot.setupAxisLimits(ImPlotAxis.X1, -frames.size.toDouble(), 0.0, ImPlotCond.Always)
                ImPlot.plotLine("frame interval", xs, DoubleArray(frames.size) { frames[it].frame })
                ImPlot.plotLine("CPU", xs, DoubleArray(frames.size) { frames[it].sections.sum() })
                ImPlot.plotLine("physics", xs, DoubleArray(frames.size) { frames[it].sections[FrameProfiler.Section.PHYSICS.ordinal] })
                if (frames.any { it.gpu != null }) ImPlot.plotLine("GPU", xs, DoubleArray(frames.size) { frames[it].gpu ?: 0.0 })
                ImPlot.endPlot()
            }
        }
        Gui.end()
    }
}

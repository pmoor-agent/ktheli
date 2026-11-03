package ktheli.app

import kotlin.math.max

/**
 * Where each frame's time goes. Sections are timed on the CPU with [measure]; the GPU's own time for the frame
 * comes from [GpuTimer] via [gpu]. [stats] are smoothed (exponential moving average) with the peak of the
 * last second alongside, and [history] keeps a few seconds of frames for plotting.
 *
 * Display only: the simulation never sees any of this.
 */
class FrameProfiler(private val tickSeconds: Double = 1.0 / 2000, private val historyFrames: Int = 600) {
    enum class Section(val label: String) {
        INPUT("Input"),
        PHYSICS("Physics"),
        UI("UI build"),
        WORLD("3D scene (CPU)"),
        UI_RENDER("UI draw (CPU)"),
    }

    private val current = LongArray(Section.entries.size)
    private var currentTicks = 0
    private var gpuNanos: Long? = null

    private val smoothed = DoubleArray(Section.entries.size)
    private var smoothedFrame = 0.0
    private var smoothedTicks = 0.0
    private var smoothedGpu: Double? = null
    private var frames = 0L

    private val peakWindow = ArrayDeque<DoubleArray>() // per frame: [cpu ms, gpu ms, frame ms]

    /** One recorded frame, in milliseconds. */
    class Frame(val sections: DoubleArray, val gpu: Double?, val frame: Double)

    val history = ArrayDeque<Frame>()

    var stats: PerfStats = PerfStats(tickSeconds = tickSeconds)
        private set

    inline fun <T> measure(section: Section, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            add(section, System.nanoTime() - start)
        }
    }

    fun add(section: Section, nanos: Long) {
        current[section.ordinal] += nanos
    }

    /** Simulation ticks run this frame. */
    fun ticks(n: Int) {
        currentTicks += n
    }

    /** GPU time of a recent frame (results arrive a few frames late), when the driver supports timer queries. */
    fun gpu(nanos: Long?) {
        if (nanos != null) gpuNanos = nanos
    }

    /** Closes the frame that took [frameSeconds] wall time (including any wait for vsync). */
    fun endFrame(frameSeconds: Double) {
        val ms = DoubleArray(current.size) { current[it] / 1e6 }
        val gpu = gpuNanos?.let { it / 1e6 }
        val frameMs = frameSeconds * 1000
        val a = if (frames < 10) 0.5 else ALPHA // settle quickly at start
        for (i in ms.indices) smoothed[i] += (ms[i] - smoothed[i]) * a
        smoothedFrame += (frameMs - smoothedFrame) * a
        smoothedTicks += (currentTicks - smoothedTicks) * a
        if (gpu != null) smoothedGpu = smoothedGpu?.let { it + (gpu - it) * a } ?: gpu
        frames++

        peakWindow.addLast(doubleArrayOf(ms.sum(), gpu ?: 0.0, frameMs))
        while (peakWindow.size > PEAK_FRAMES) peakWindow.removeFirst()
        history.addLast(Frame(ms, gpu, frameMs))
        while (history.size > historyFrames) history.removeFirst()

        stats = PerfStats(
            sections = Section.entries.associateWith { smoothed[it.ordinal] },
            frameMs = smoothedFrame,
            gpuMs = smoothedGpu,
            ticksPerFrame = smoothedTicks,
            peakCpuMs = peakWindow.maxOf { it[0] },
            peakGpuMs = smoothedGpu?.let { peakWindow.maxOf { it[1] } },
            peakFrameMs = peakWindow.maxOf { it[2] },
            tickSeconds = tickSeconds,
        )
        current.fill(0)
        currentTicks = 0
        gpuNanos = null
    }

    private companion object {
        const val ALPHA = 0.05
        const val PEAK_FRAMES = 60
    }
}

/**
 * Smoothed frame costs (milliseconds) and what they mean for headroom.
 *
 * @property frameMs wall time between frames; with vsync this is the display's refresh period, which is the
 *   budget everything has to fit into
 */
data class PerfStats(
    val sections: Map<FrameProfiler.Section, Double> = emptyMap(),
    val frameMs: Double = 0.0,
    val gpuMs: Double? = null,
    val ticksPerFrame: Double = 0.0,
    val peakCpuMs: Double = 0.0,
    val peakGpuMs: Double? = null,
    val peakFrameMs: Double = 0.0,
    val tickSeconds: Double = 1.0 / 2000,
) {
    val cpuMs: Double get() = sections.values.sum()
    val physicsMs: Double get() = sections[FrameProfiler.Section.PHYSICS] ?: 0.0
    val fps: Double get() = if (frameMs > 0) 1000 / frameMs else 0.0

    /** The busier of CPU and GPU: they work in parallel, so the slower one limits the frame rate. */
    val busyMs: Double get() = max(cpuMs, gpuMs ?: 0.0)

    /** Share of the frame interval not needed for work, 0..1. */
    val headroom: Double get() = if (frameMs > 0) (1 - busyMs / frameMs).coerceIn(0.0, 1.0) else 0.0

    /** Frame rate the work alone would allow (without waiting for vsync). */
    val maxFps: Double get() = if (busyMs > 0) 1000 / busyMs else 0.0

    val microsPerTick: Double get() = if (ticksPerFrame > 0) physicsMs * 1000 / ticksPerFrame else 0.0

    /** How many times faster than real time the physics alone could run. */
    val realtimeFactor: Double get() = if (microsPerTick > 0) tickSeconds * 1e6 / microsPerTick else 0.0
}

package ktheli.app

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33

/**
 * Measures how long the GPU spends on a frame with OpenGL timer queries (GL 3.3 / ARB_timer_query). CPU-side
 * timing only sees how long it takes to *submit* work; the GPU finishes later. Queries rotate through a small
 * ring and are read once their results are ready, so measuring never stalls the pipeline; results are a few
 * frames old. Unsupported drivers just report nothing.
 */
class GpuTimer : AutoCloseable {
    private val supported = runCatching { GL.getCapabilities().let { it.OpenGL33 || it.GL_ARB_timer_query } }.getOrDefault(false)
    private val queries = if (supported) IntArray(RING) { GL15.glGenQueries() } else IntArray(0)
    private val pending = BooleanArray(RING)
    private var next = 0
    private var latest: Long? = null

    fun begin() {
        if (!supported || pending[next]) return // still waiting for that slot: skip measuring this frame
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[next])
    }

    fun end() {
        if (!supported || pending[next]) return
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED)
        pending[next] = true
        next = (next + 1) % RING
    }

    /** The most recent finished measurement, in nanoseconds. */
    fun poll(): Long? {
        if (!supported) return null
        for (i in 0 until RING) {
            if (pending[i] && GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                latest = GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT)
                pending[i] = false
            }
        }
        return latest
    }

    override fun close() {
        if (supported) queries.forEach(GL15::glDeleteQueries)
    }

    private companion object {
        const val RING = 4
    }
}

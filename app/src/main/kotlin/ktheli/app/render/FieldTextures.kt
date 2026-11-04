package ktheli.app.render

import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import java.util.Random
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Field textures, generated in code from fixed seeds: no asset files, and identical on every run (which keeps
 * rendered-image tests reproducible).
 */
object FieldTextures {
    /** Tileable grass: soft value noise plus short vertical blade streaks, around a mid green. */
    fun grass(size: Int = 256): Texture {
        val noise = TileableNoise(size, cells = 8, seed = 11)
        val fine = TileableNoise(size, cells = 32, seed = 12)
        val rng = Random(13)
        val streak = FloatArray(size * size)
        repeat(size * size / 6) {
            val x = rng.nextInt(size)
            val y0 = rng.nextInt(size)
            val length = 2 + rng.nextInt(6)
            val v = (rng.nextFloat() - 0.5f) * 0.25f
            for (k in 0 until length) streak[((y0 + k) % size) * size + x] += v
        }
        return texture(size, wrap = true) { x, y ->
            val v = 0.75f * noise(x, y) + 0.25f * fine(x, y) + streak[y * size + x]
            val shade = 0.82f + 0.36f * v
            rgba(0.40f * shade, 0.60f * shade, 0.30f * shade, 1f)
        }
    }

    /**
     * A round asphalt helipad seen from above: speckled dark grey, a white ring near the edge and a big white
     * "H" whose bars run along the texture's V axis. Transparent outside the circle.
     */
    fun helipad(size: Int = 512): Texture {
        val noise = TileableNoise(size, cells = 64, seed = 21)
        val rng = Random(22)
        val speckles = HashSet<Int>()
        repeat(size * size / 40) { speckles += rng.nextInt(size * size) }
        val c = size / 2f
        return texture(size, wrap = false) { x, y ->
            val dx = (x + 0.5f - c) / c
            val dy = (y + 0.5f - c) / c
            val r = sqrt(dx * dx + dy * dy)
            when {
                r > 1f -> 0
                r in 0.86f..0.93f -> rgba(0.92f, 0.92f, 0.90f, 1f)
                isH(dx, dy) -> rgba(0.95f, 0.95f, 0.93f, 1f)
                else -> {
                    val g = 0.20f + 0.07f * noise(x, y) + (if (y * size + x in speckles) 0.12f else 0f)
                    rgba(g, g, g * 1.04f, 1f)
                }
            }
        }
    }

    /** The "H": two bars along V and a crossbar, in normalized pad coordinates. */
    private fun isH(u: Float, v: Float): Boolean {
        val bar = kotlin.math.abs(u) in 0.22f..0.36f && kotlin.math.abs(v) < 0.48f
        val cross = kotlin.math.abs(u) < 0.22f && kotlin.math.abs(v) < 0.065f
        return bar || cross
    }

    private fun rgba(r: Float, g: Float, b: Float, a: Float): Int {
        fun c(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (c(r) shl 24) or (c(g) shl 16) or (c(b) shl 8) or c(a)
    }

    private fun texture(size: Int, wrap: Boolean, pixel: (Int, Int) -> Int): Texture {
        val pixmap = Pixmap(size, size, Pixmap.Format.RGBA8888)
        pixmap.blending = Pixmap.Blending.None
        for (y in 0 until size) for (x in 0 until size) pixmap.drawPixel(x, y, pixel(x, y))
        val texture = Texture(pixmap, true)
        pixmap.dispose()
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear)
        val w = if (wrap) Texture.TextureWrap.Repeat else Texture.TextureWrap.ClampToEdge
        texture.setWrap(w, w)
        return texture
    }

    /** Smooth value noise in -0.5..0.5 that wraps around at the texture edges. */
    private class TileableNoise(private val size: Int, private val cells: Int, seed: Long) {
        private val grid = Random(seed).let { r -> FloatArray(cells * cells) { r.nextFloat() - 0.5f } }

        operator fun invoke(x: Int, y: Int): Float {
            val fx = x.toFloat() / size * cells
            val fy = y.toFloat() / size * cells
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val tx = smooth(fx - x0)
            val ty = smooth(fy - y0)
            fun g(i: Int, j: Int) = grid[Math.floorMod(j, cells) * cells + Math.floorMod(i, cells)]
            val a = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * tx
            val b = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * tx
            return a + (b - a) * ty
        }

        private fun smooth(t: Float) = t * t * (3 - 2 * t)
    }
}

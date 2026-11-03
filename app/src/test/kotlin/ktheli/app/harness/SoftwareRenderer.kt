package ktheli.app.harness

import imgui.ImDrawData
import imgui.ImFontAtlas
import imgui.type.ImInt
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal CPU rasterizer for ImGui draw data, so headless tests can produce UI screenshots. Supports what
 * ImGui emits: textured, vertex-colored triangles with clip rectangles, alpha-blended in order. The only
 * texture is the font atlas. The 3D scene is not drawn; the background is a flat sky color.
 */
class SoftwareRenderer(atlas: ImFontAtlas) {
    private val texWidth: Int
    private val texHeight: Int
    private val texAlpha: ByteArray // the default font atlas is white with coverage in alpha

    init {
        val w = ImInt()
        val h = ImInt()
        val rgba = atlas.getTexDataAsRGBA32(w, h)
        texWidth = w.get()
        texHeight = h.get()
        texAlpha = ByteArray(texWidth * texHeight) { rgba.get(it * 4 + 3) }
    }

    fun render(data: ImDrawData, width: Int, height: Int, file: Path) {
        val r = FloatArray(width * height) { SKY_R }
        val g = FloatArray(width * height) { SKY_G }
        val b = FloatArray(width * height) { SKY_B }
        val ox = data.displayPosX
        val oy = data.displayPosY
        val idxSize = ImDrawData.sizeOfImDrawIdx()
        val vtxSize = ImDrawData.sizeOfImDrawVert()

        for (list in 0 until data.cmdListsCount) {
            // imgui-java hands out both buffers through one shared native buffer: copy each before the next call.
            val vtx = copy(data.getCmdListVtxBufferData(list), data.getCmdListVtxBufferSize(list) * vtxSize)
            val idx = copy(data.getCmdListIdxBufferData(list), data.getCmdListIdxBufferSize(list) * idxSize)
            fun index(i: Int) = if (idxSize == 2) idx.getShort(i * 2).toInt() and 0xFFFF else idx.getInt(i * 4)

            for (cmd in 0 until data.getCmdListCmdBufferSize(list)) {
                val clip = data.getCmdListCmdBufferClipRect(list, cmd)
                val cx0 = max(0, floor(clip.x - ox).toInt())
                val cy0 = max(0, floor(clip.y - oy).toInt())
                val cx1 = min(width, ceil(clip.z - ox).toInt())
                val cy1 = min(height, ceil(clip.w - oy).toInt())
                if (cx0 >= cx1 || cy0 >= cy1) continue
                val vtxOffset = data.getCmdListCmdBufferVtxOffset(list, cmd)
                val idxOffset = data.getCmdListCmdBufferIdxOffset(list, cmd)
                val count = data.getCmdListCmdBufferElemCount(list, cmd)

                var t = 0
                while (t < count) {
                    val v = IntArray(3) { (vtxOffset + index(idxOffset + t + it)) * vtxSize }
                    val px = FloatArray(3) { vtx.getFloat(v[it]) - ox }
                    val py = FloatArray(3) { vtx.getFloat(v[it] + 4) - oy }
                    val pu = FloatArray(3) { vtx.getFloat(v[it] + 8) }
                    val pv = FloatArray(3) { vtx.getFloat(v[it] + 12) }
                    val col = IntArray(3) { vtx.getInt(v[it] + 16) }
                    triangle(px, py, pu, pv, col, cx0, cy0, cx1, cy1, width, r, g, b)
                    t += 3
                }
            }
        }

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until width * height) {
            fun c(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            image.setRGB(i % width, i / width, (c(r[i]) shl 16) or (c(g[i]) shl 8) or c(b[i]))
        }
        ImageIO.write(image, "png", file.toFile())
    }

    private fun copy(src: ByteBuffer, bytes: Int): ByteBuffer {
        val dst = ByteArray(bytes)
        src.duplicate().apply { rewind() }.get(dst, 0, bytes)
        return ByteBuffer.wrap(dst).order(ByteOrder.nativeOrder())
    }

    private fun triangle(
        px: FloatArray, py: FloatArray, pu: FloatArray, pv: FloatArray, col: IntArray,
        cx0: Int, cy0: Int, cx1: Int, cy1: Int, width: Int, r: FloatArray, g: FloatArray, b: FloatArray,
    ) {
        val area = (px[1] - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (py[1] - py[0])
        if (area == 0f) return
        val x0 = max(cx0, floor(minOf(px[0], px[1], px[2])).toInt())
        val x1 = min(cx1, ceil(maxOf(px[0], px[1], px[2])).toInt())
        val y0 = max(cy0, floor(minOf(py[0], py[1], py[2])).toInt())
        val y1 = min(cy1, ceil(maxOf(py[0], py[1], py[2])).toInt())
        fun ch(c: Int, shift: Int) = ((c ushr shift) and 0xFF) / 255f // ImU32 is ABGR: R in the low byte

        for (y in y0 until y1) {
            val sy = y + 0.5f
            for (x in x0 until x1) {
                val sx = x + 0.5f
                val w0 = ((px[1] - sx) * (py[2] - sy) - (px[2] - sx) * (py[1] - sy)) / area
                val w1 = ((px[2] - sx) * (py[0] - sy) - (px[0] - sx) * (py[2] - sy)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val u = w0 * pu[0] + w1 * pu[1] + w2 * pu[2]
                val v = w0 * pv[0] + w1 * pv[1] + w2 * pv[2]
                val tx = (u * texWidth).toInt().coerceIn(0, texWidth - 1)
                val ty = (v * texHeight).toInt().coerceIn(0, texHeight - 1)
                val tex = (texAlpha[ty * texWidth + tx].toInt() and 0xFF) / 255f
                val a = (w0 * ch(col[0], 24) + w1 * ch(col[1], 24) + w2 * ch(col[2], 24)) * tex
                if (a <= 0f) continue
                val i = y * width + x
                r[i] += (w0 * ch(col[0], 0) + w1 * ch(col[1], 0) + w2 * ch(col[2], 0) - r[i]) * a
                g[i] += (w0 * ch(col[0], 8) + w1 * ch(col[1], 8) + w2 * ch(col[2], 8) - g[i]) * a
                b[i] += (w0 * ch(col[0], 16) + w1 * ch(col[1], 16) + w2 * ch(col[2], 16) - b[i]) * a
            }
        }
    }

    private companion object {
        const val SKY_R = 0.55f
        const val SKY_G = 0.72f
        const val SKY_B = 0.92f
    }
}

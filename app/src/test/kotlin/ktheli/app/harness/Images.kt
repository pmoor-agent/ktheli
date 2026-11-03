package ktheli.app.harness

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.abs

/** Pixel-level helpers for rendered-image tests. */
object Images {
    fun write(image: BufferedImage, file: Path) {
        Files.createDirectories(file.toAbsolutePath().parent)
        ImageIO.write(image, "png", file.toFile())
    }

    /**
     * Sky (blue dominant) and field (green dominant: grass, grid, shadow) are background; everything else
     * (airframe, livery, blades, windsock) counts as an object.
     */
    fun isBackground(rgb: Int): Boolean {
        val r = rgb shr 16 and 255
        val g = rgb shr 8 and 255
        val b = rgb and 255
        val sky = b > r + 25 && b >= g
        val field = g > r + 8 && g > b
        return sky || field
    }

    data class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0
    }

    /** Statistics of non-background pixels within [region]: count, centroid, bounding box. */
    data class Blob(val count: Int, val cx: Double, val cy: Double, val box: Region?)

    fun objects(image: BufferedImage, region: Region = Region(0, 0, image.width, image.height)): Blob {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (y in region.y0 until region.y1) for (x in region.x0 until region.x1) {
            if (!isBackground(image.getRGB(x, y))) {
                n++; sx += x; sy += y
                x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y)
            }
        }
        return if (n == 0) Blob(0, 0.0, 0.0, null) else Blob(n, sx / n, sy / n, Region(x0, y0, x1 + 1, y1 + 1))
    }

    /** Pixels whose largest channel difference exceeds [threshold], optionally only where [where] holds. */
    fun changed(a: BufferedImage, b: BufferedImage, threshold: Int = 24, where: (Int, Int) -> Boolean = { _, _ -> true }): List<Pair<Int, Int>> {
        require(a.width == b.width && a.height == b.height)
        val out = ArrayList<Pair<Int, Int>>()
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (where(x, y) && channelDiff(a.getRGB(x, y), b.getRGB(x, y)) > threshold) out += x to y
        }
        return out
    }

    private fun channelDiff(p: Int, q: Int) = maxOf(
        abs((p shr 16 and 255) - (q shr 16 and 255)),
        abs((p shr 8 and 255) - (q shr 8 and 255)),
        abs((p and 255) - (q and 255)),
    )

    private val goldenDir: Path = Paths.get(System.getProperty("ktheli.goldenDir") ?: "src/test/resources/golden")
    private val updateGolden = System.getProperty("ktheli.updateGolden") == "true"

    /**
     * Compares [actual] with the reference image `golden/<name>.png`: at most [maxFraction] of the pixels may
     * differ by more than [threshold] (rasterizers differ in edge and filtering details). With
     * `-PupdateGolden` the reference is rewritten instead. On mismatch, actual and diff images land in
     * `build/golden-failures/`.
     */
    fun assertGolden(name: String, actual: BufferedImage, threshold: Int = 40, maxFraction: Double = 0.01) {
        val file = goldenDir.resolve("$name.png")
        if (updateGolden || !Files.exists(file)) {
            write(actual, file)
            if (!updateGolden) throw AssertionError("No golden image for '$name'; wrote $file — review and commit it")
            return
        }
        val expected = ImageIO.read(file.toFile())
        check(expected.width == actual.width && expected.height == actual.height) { "golden '$name' has a different size" }
        val diff = changed(expected, actual, threshold)
        val fraction = diff.size.toDouble() / (actual.width * actual.height)
        if (fraction > maxFraction) {
            val out = Paths.get("build/golden-failures")
            write(actual, out.resolve("$name-actual.png"))
            val marked = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until actual.height) for (x in 0 until actual.width) marked.setRGB(x, y, (expected.getRGB(x, y) and 0xFEFEFE) shr 1)
            diff.forEach { (x, y) -> marked.setRGB(x, y, 0xFF2020) }
            write(marked, out.resolve("$name-diff.png"))
            throw AssertionError("Golden '$name': %.2f%% of pixels differ (limit %.2f%%); see %s".format(100 * fraction, 100 * maxFraction, out.toAbsolutePath()))
        }
    }
}

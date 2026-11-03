package ktheli.app

import kotlinx.serialization.Serializable
import kotlin.math.atan
import kotlin.math.tan

@Serializable
enum class Corner(val label: String) {
    BOTTOM_RIGHT("Bottom right"),
    BOTTOM_LEFT("Bottom left"),
    TOP_RIGHT("Top right"),
}

/**
 * The binocular inset: a magnified view of the model from the main camera's position (same perspective,
 * narrower field of view), scaled so the model fills [framing] of the inset's height at any distance.
 *
 * @property size inset height as a fraction of the 3D view's height (the inset is 4:3)
 */
@Serializable
data class BinocularSettings(
    val enabled: Boolean = false,
    val size: Double = 0.32,
    val framing: Double = 0.6,
    val corner: Corner = Corner.BOTTOM_RIGHT,
)

/** A rectangle in logical pixels, origin at the top left (ImGui convention). */
data class Rect(val x: Float, val y: Float, val width: Float, val height: Float)

object Binoculars {
    const val ASPECT = 4f / 3f
    const val MARGIN = 12f

    /** Where the inset goes inside a 3D view of [viewWidth] × [viewHeight] whose top-left is at [viewTop]. */
    fun layout(viewWidth: Float, viewHeight: Float, viewTop: Float, s: BinocularSettings): Rect {
        val h = (viewHeight * s.size.toFloat()).coerceAtMost(viewHeight - 2 * MARGIN).coerceAtLeast(40f)
        val w = (h * ASPECT).coerceAtMost(viewWidth - 2 * MARGIN)
        val x = when (s.corner) {
            Corner.BOTTOM_LEFT -> MARGIN
            Corner.BOTTOM_RIGHT, Corner.TOP_RIGHT -> viewWidth - MARGIN - w
        }
        val y = when (s.corner) {
            Corner.TOP_RIGHT -> viewTop + MARGIN
            Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT -> viewTop + viewHeight - MARGIN - h
        }
        return Rect(x, y, w, h)
    }

    /**
     * Vertical field of view (degrees) that makes a model with rotor radius [rotorRadius] at [distance] fill
     * [framing] of the inset's height; never wider than the main camera's [mainFov].
     */
    fun fov(distance: Double, rotorRadius: Double, framing: Double, mainFov: Double): Double {
        val halfHeight = rotorRadius / framing.coerceIn(0.1, 1.0)
        val fov = Math.toDegrees(2.0 * atan(halfHeight / distance.coerceAtLeast(0.1)))
        return fov.coerceIn(0.3, mainFov)
    }

    /** How much larger things look in the inset than in the main view. */
    fun magnification(mainFov: Double, insetFov: Double): Double =
        tan(Math.toRadians(mainFov) / 2) / tan(Math.toRadians(insetFov) / 2)
}

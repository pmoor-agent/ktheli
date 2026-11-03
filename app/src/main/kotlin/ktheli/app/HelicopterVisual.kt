package ktheli.app

import ktheli.physics.heli.HelicopterSpec
import ktheli.physics.math.Vec3

/**
 * When a spinning rotor looks like blades and when like a blurred disc. What matters is how far a blade moves
 * between two rendered frames: a few degrees reads as distinct blades, tens of degrees only as a blur (and
 * drawing sharp blades there just strobes). The blades fade out and the disc fades in between the two thresholds.
 */
data class RotorBlur(
    val sharpBelowDegreesPerFrame: Double = 12.0,
    val blurredAboveDegreesPerFrame: Double = 48.0,
    val bladeOpacityBlurred: Double = 0.1,
    val discOpacityBlurred: Double = 0.9,
    /** The blur disc's texture turns at this fraction of the rotor speed, just enough to read as motion. */
    val discSpinFactor: Double = 0.35,
) {
    /** 0 = sharp blades, 1 = fully blurred, for a rotor at [rpm] rendered at [fps]. */
    fun blur(rpm: Double, fps: Double): Double {
        val degreesPerFrame = rpm * 6.0 / fps.coerceAtLeast(1.0)
        val t = (degreesPerFrame - sharpBelowDegreesPerFrame) / (blurredAboveDegreesPerFrame - sharpBelowDegreesPerFrame)
        return t.coerceIn(0.0, 1.0)
    }

    fun bladeOpacity(blur: Double) = 1.0 + (bladeOpacityBlurred - 1.0) * blur

    fun discOpacity(blur: Double) = discOpacityBlurred * blur
}

/** How a model looks. Geometry is in body FRD coordinates (x forward, y right, z down, origin at the CG). */
sealed interface HelicopterVisual {
    /** Main rotor radius, m: sizes the shadow and the camera's zoom. */
    val rotorRadius: Double

    /**
     * A Wavefront OBJ/MTL asset authored in body coordinates. Rotors are separate nodes: sharp blades and a
     * blur disc for each rotor, posed about the hubs given by the physics airframe.
     */
    data class Obj(
        val objPath: String,
        val bodyMaterial: String,
        val blurMaterial: String,
        val mainHub: Vec3,
        val tailHub: Vec3,
        override val rotorRadius: Double,
        val nodes: RotorNodes = RotorNodes(),
        val blur: RotorBlur = RotorBlur(),
        val shininess: Float = 48f,
    ) : HelicopterVisual {
        companion object {
            /** An OBJ visual whose hubs and size come from the physics [spec], so they can't disagree. */
            fun forSpec(spec: HelicopterSpec, objPath: String, bodyMaterial: String, blurMaterial: String) = Obj(
                objPath = objPath,
                bodyMaterial = bodyMaterial,
                blurMaterial = blurMaterial,
                mainHub = Vec3(0.0, 0.0, -spec.airframe.mainRotor.hubHeight),
                tailHub = spec.airframe.tailRotor.position,
                rotorRadius = spec.airframe.mainRotor.rotor.radius,
            )
        }
    }

    /** Simple boxes and translucent discs, for aircraft without an asset. */
    data class Procedural(
        override val rotorRadius: Double,
        val skidDrop: Double,
        val mainHubHeight: Double,
        val tailHub: Vec3,
        val tailRadius: Double,
    ) : HelicopterVisual
}

/** Node names of the rotor parts in an OBJ asset. */
data class RotorNodes(
    val mainBlades: String = "rotorBlades",
    val mainDisc: String = "rotor",
    val tailBlades: String = "tailBlades",
    val tailDisc: String = "tailRotor",
)

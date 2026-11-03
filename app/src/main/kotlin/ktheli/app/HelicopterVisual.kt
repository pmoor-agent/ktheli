package ktheli.app

import ktheli.physics.heli.HelicopterSpec
import ktheli.physics.math.Vec3

/**
 * When a spinning rotor looks like blades and when like a blurred disc: sharp blades up to [sharpUntilRpm],
 * cross-fading into the blur disc until [blurredFromRpm]. On a slow display the fade starts earlier: a
 * two-bladed rotor looks the same after half a turn, so blades moving more than ~90° between frames alias
 * ("wagon wheel"); [maxSharpDegreesPerFrame] and [maxPartialDegreesPerFrame] keep below that.
 */
data class RotorBlur(
    val sharpUntilRpm: Double = 300.0,
    val blurredFromRpm: Double = 700.0,
    val maxSharpDegreesPerFrame: Double = 85.0,
    val maxPartialDegreesPerFrame: Double = 170.0,
    val bladeOpacityBlurred: Double = 0.1,
    val discOpacityBlurred: Double = 0.9,
    /** The blur disc's texture turns at this fraction of the rotor speed, just enough to read as motion. */
    val discSpinFactor: Double = 0.35,
) {
    /** 0 = sharp blades, 1 = fully blurred, for a rotor at [rpm] rendered at [fps]. */
    fun blur(rpm: Double, fps: Double): Double {
        val rpmPerDegreePerFrame = fps.coerceAtLeast(1.0) / 6.0 // rpm that moves a blade 1° per frame
        val start = minOf(sharpUntilRpm, maxSharpDegreesPerFrame * rpmPerDegreePerFrame)
        val end = maxOf(start + 1.0, minOf(blurredFromRpm, maxPartialDegreesPerFrame * rpmPerDegreePerFrame))
        return ((rpm - start) / (end - start)).coerceIn(0.0, 1.0)
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

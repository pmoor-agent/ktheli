package ktheli.physics.heli

import ktheli.physics.math.DMath
import kotlin.math.abs

/**
 * Uniform induced velocity beyond plain momentum theory.
 *
 * Momentum theory has no valid solution in steep descent between the "helicopter" and "windmill brake" states,
 * i.e. for descent rates between 0 and twice the hover induced velocity `vh` (vortex ring and turbulent wake
 * states). There the measured induced velocity follows Leishman's empirical fit (Principles of Helicopter
 * Aerodynamics, 2nd ed., eq. 2.135), normalized to join momentum theory continuously at both ends:
 *
 * ```
 * vi/vh = (κ + k1·x + k2·x² + k3·x³ + k4·x⁴) / κ,   x = Vc/vh ∈ (−2, 0)
 * ```
 *
 * With edgewise flow the momentum solution is valid again, so the empirical curve is blended out as the
 * edgewise speed approaches `vh`. All quantities are along the thrust direction, so inverted flight works
 * the same way.
 */
object Inflow {
    private const val KAPPA = 1.15
    private const val K1 = -1.125
    private const val K2 = -1.372
    private const val K3 = -1.718
    private const val K4 = -0.655

    /** Axial induced velocity ratio vi/vh for a climb ratio x = Vc/vh (descent negative). */
    fun axialRatio(x: Double): Double = when {
        x >= 0.0 -> -x / 2.0 + DMath.sqrt(x * x / 4.0 + 1.0)
        x <= -2.0 -> -x / 2.0 - DMath.sqrt(x * x / 4.0 - 1.0)
        else -> (KAPPA + x * (K1 + x * (K2 + x * (K3 + x * K4)))) / KAPPA
    }

    /** Weight of the empirical axial curve against momentum theory: 1 in axial flight, 0 once edgewise speed ≥ vh. */
    fun axialWeight(inplane: Double, vh: Double): Double {
        val r = inplane / vh
        return if (r >= 1.0) 0.0 else (1.0 - r) * (1.0 - r)
    }

    /** Hover induced velocity for a thrust: `√(|T| / 2ρA)`. */
    fun hoverInducedVelocity(thrust: Double, density: Double, area: Double) = DMath.sqrt(abs(thrust) / (2.0 * density * area))

    /**
     * Corrects a momentum-theory induced velocity for steep descent. [axial] is the rotor's motion against its
     * thrust direction (descent, for a main rotor in normal flight), [inplane] the edgewise speed.
     */
    fun correct(momentum: Double, thrust: Double, axial: Double, inplane: Double, density: Double, area: Double): Double {
        val vh = hoverInducedVelocity(thrust, density, area)
        if (vh < 1e-6) return momentum
        val weight = axialWeight(inplane, vh)
        if (weight == 0.0) return momentum
        val sign = if (thrust < 0.0) -1.0 else 1.0
        val climbRatio = -sign * axial / vh
        val empirical = sign * vh * axialRatio(climbRatio)
        return weight * empirical + (1.0 - weight) * momentum
    }

    /**
     * How deep in the vortex ring state the rotor is, 0..1: peaks when descending at ~0.9 vh with little
     * edgewise speed, where real rotors shudder and lose thrust unpredictably.
     */
    fun vortexRingIntensity(thrust: Double, axial: Double, inplane: Double, density: Double, area: Double): Double {
        val vh = hoverInducedVelocity(thrust, density, area)
        if (vh < 1e-6) return 0.0
        val sign = if (thrust < 0.0) -1.0 else 1.0
        val climbRatio = -sign * axial / vh
        val d = (climbRatio + 0.9) / 0.45
        return DMath.exp(-d * d) * axialWeight(inplane, vh)
    }
}

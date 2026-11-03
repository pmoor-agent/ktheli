package ktheli.physics.heli

import ktheli.physics.math.DMath
import kotlin.math.abs

/**
 * Uniform-inflow rotor combining blade-element thrust with momentum-theory inflow, after Heffley & Mnich,
 * "Minimum-Complexity Helicopter Simulation Math Model" (NASA CR-177476, 1988). Used for both the main and
 * the tail rotor.
 *
 * Velocities are along the thrust axis: [Input.axialVelocity] is the rotor's own motion *against* its thrust
 * direction (descent, for a main rotor), [Input.inplaneSpeed] the edgewise speed. Thrust is positive along
 * the thrust axis and may be negative (inverted flight); the induced velocity carries the same sign.
 */
class RotorDisc(val params: RotorParams, private val airDensity: Double = AIR_DENSITY) {
    val area = DMath.PI * params.radius * params.radius
    val solidity = params.blades * params.chord / (DMath.PI * params.radius)

    data class Input(
        val collective: Double, // rad
        val axialVelocity: Double, // m/s
        val inplaneSpeed: Double, // m/s
        val omega: Double, // rad/s
        val inducedVelocity: Double, // current (lagged) inflow state, m/s
        val groundEffect: Double = 1.0, // induced velocity factor, ≤ 1 near the ground
    )

    data class Output(
        val thrust: Double, // N
        val power: Double, // W absorbed from the shaft: κ·T·vi − T·w + profile (negative when windmilling)
        val torque: Double, // N·m on the shaft
        val equilibriumInflow: Double, // induced velocity the inflow state is relaxing towards, m/s
        val inflowRatio: Double, // (vi - w) / ΩR, through the disc
        val advanceRatio: Double, // μ
    )

    fun evaluate(input: Input): Output {
        val omega = input.omega
        if (omega < MIN_OMEGA) return Output(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val tipSpeed = omega * params.radius
        val vi = input.inducedVelocity
        val thrust = thrust(input.collective, input.axialVelocity, omega, vi)

        val mu = input.inplaneSpeed / tipSpeed
        val profilePower = solidity * sectionDrag(thrust, tipSpeed) / 8.0 * airDensity * area * tipSpeed * tipSpeed * tipSpeed *
            (1.0 + 4.6 * mu * mu)
        val power = params.inducedPowerFactor * thrust * vi - thrust * input.axialVelocity + profilePower
        return Output(
            thrust = thrust,
            power = power,
            torque = power / omega,
            equilibriumInflow = equilibriumInflow(input) * input.groundEffect,
            inflowRatio = (vi - input.axialVelocity) / tipSpeed,
            advanceRatio = mu,
        )
    }

    /**
     * Mean section drag coefficient. The mean blade angle of attack follows from the thrust coefficient,
     * `α ≈ 6·C_T / (σ·a)` (Leishman, Principles of Helicopter Aerodynamics).
     */
    fun sectionDrag(thrust: Double, tipSpeed: Double): Double {
        val ct = thrust / (airDensity * area * tipSpeed * tipSpeed)
        val alpha = abs(6.0 * ct / (solidity * params.liftSlope))
        val stalled = alpha - params.stallAngle
        return params.profileDrag + params.dragRise * alpha * alpha +
            (if (stalled > 0.0) params.stallDrag * stalled * stalled else 0.0)
    }

    /** Blade-element thrust for a given induced velocity. */
    fun thrust(collective: Double, axialVelocity: Double, omega: Double, vi: Double): Double {
        val r = params.radius
        val bladeVelocity = axialVelocity + 2.0 / 3.0 * omega * r * collective
        return (bladeVelocity - vi) * airDensity * omega * r * params.liftSlope * params.blades * params.chord * r / 4.0
    }

    /**
     * Induced velocity where blade-element thrust and Glauert's momentum relation agree:
     * `vi·√(u² + (vi − w)²) = T(vi) / (2ρA)`. Solved with a fixed number of damped Newton steps from the
     * current inflow state, so the result is deterministic and continuous from tick to tick.
     */
    fun equilibriumInflow(input: Input): Double {
        val omega = input.omega
        if (omega < MIN_OMEGA) return 0.0
        val w = input.axialVelocity
        val u = input.inplaneSpeed
        val r = params.radius
        val bladeVelocity = w + 2.0 / 3.0 * omega * r * input.collective
        val c = airDensity * omega * r * params.liftSlope * params.blades * params.chord * r / 4.0 / (2.0 * airDensity * area)
        var vi = input.inducedVelocity
        repeat(INFLOW_ITERATIONS) {
            val s = DMath.sqrt(u * u + (vi - w) * (vi - w))
            val f = vi * s - c * (bladeVelocity - vi)
            val df = s + (if (s > 1e-9) vi * (vi - w) / s else 0.0) + c
            val step = if (df > 1e-6) f / df else f * 0.1
            vi -= step.coerceIn(-MAX_NEWTON_STEP, MAX_NEWTON_STEP)
        }
        return vi
    }

    /** Pitt-Peters style time constant of the uniform inflow mode, clamped to a sensible range. */
    fun inflowTimeConstant(omega: Double, inflowRatio: Double): Double {
        if (omega < MIN_OMEGA) return MAX_INFLOW_TAU
        val lambda = maxOf(abs(inflowRatio), 0.02)
        return (0.849 / (4.0 * lambda * omega)).coerceIn(MIN_INFLOW_TAU, MAX_INFLOW_TAU)
    }

    companion object {
        const val AIR_DENSITY = 1.225 // kg/m³, sea level ISA
        private const val MIN_OMEGA = 1.0
        private const val INFLOW_ITERATIONS = 8
        private const val MAX_NEWTON_STEP = 3.0 // m/s
        private const val MIN_INFLOW_TAU = 0.005
        private const val MAX_INFLOW_TAU = 0.2

        /** Cheeseman-Bennett ground effect as an induced velocity factor; [height] is the hub above ground. */
        fun groundEffect(radius: Double, height: Double): Double {
            val z = maxOf(height, radius / 2.0)
            val k = radius / (4.0 * z)
            return 1.0 - k * k
        }
    }
}

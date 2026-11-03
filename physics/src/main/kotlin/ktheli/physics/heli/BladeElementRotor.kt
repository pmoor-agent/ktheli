package ktheli.physics.heli

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.sim.StateHash
import kotlin.math.abs

/**
 * Blade element main rotor: each blade is tracked at its actual azimuth and split into radial sections;
 * every section sees its own airflow (rotation, fuselage motion, flapping, inflow) and gets lift and drag from
 * a 360° [Airfoil]. Blades flap as rigid bodies on a spring hinge at the shaft (the head's dampers).
 *
 * Conventions (body FRD, rotor turning clockwise seen from above, i.e. about +z): azimuth ψ is measured from
 * the tail in the direction of rotation, so ψ = 90° is the advancing blade on the *left*. Blade pitch is
 * `θ0 + θlat·cos ψ − θlon·sin ψ`, which tilts the disc right for positive lateral and forward for positive
 * longitudinal cyclic (after the ~90° flapping phase lag). Flap β is positive up.
 *
 * Flap equation per blade (rigid blade, hinge on the shaft, spring K and damper C), from the angular momentum
 * of the blade about its hinge axis `h = e_t` in a fuselage rotating with ω:
 *
 * ```
 * I_b·β̈ = M_aero − K·β − C·β̇ − S_b·(f·e_β) − I_b·[h·ω̇ + Ω·h·(ω × z) + (Ω_b·e_r)(Ω_b·e_β)],  Ω_b = ω + Ω·z + β̇·h
 * ```
 *
 * with f the specific force at the hub (g-load). It contains centrifugal stiffening, the gyroscopic coupling
 * to body rates and the g-load on coning. Inflow is uniform from momentum theory (with dynamic lag and ground
 * effect) plus a Pitt-Peters fore-aft gradient in forward flight.
 */
class BladeElementRotor(private val p: MainRotorParams, private val airDensity: Double = RotorDisc.AIR_DENSITY) : MainRotor {
    override val name = "blade element"

    private val blades = p.rotor.blades
    private val radius = p.rotor.radius
    private val chord = p.rotor.chord
    private val airfoil = Airfoil(p.airfoil)
    private val hub = Vec3(0.0, 0.0, -p.hubHeight)
    private val area = DMath.PI * radius * radius
    private val dr = (radius - p.rootCutout) / p.elements
    private val radii = DoubleArray(p.elements) { p.rootCutout + (it + 0.5) * dr }
    private val lifting = BooleanArray(p.elements) { radii[it] <= p.tipLoss * radius }
    private val massPerLength = p.bladeMass / (radius - p.rootCutout)
    val flapInertia = massPerLength * (radius * radius * radius - p.rootCutout * p.rootCutout * p.rootCutout) / 3.0
    private val staticMoment = massPerLength * (radius * radius - p.rootCutout * p.rootCutout) / 2.0

    private var azimuth = 0.0 // of blade 0, rad
    private val flap = DoubleArray(blades)
    private val flapRate = DoubleArray(blades)
    private var inflowMean = 0.0

    // Display-only tip-path-plane estimates (low-passed multi-blade projections).
    private var tpBack = 0.0
    private var tpRight = 0.0
    private var tpConing = 0.0

    override val tiltBack get() = tpBack
    override val tiltRight get() = tpRight
    override val coning get() = tpConing
    override val inflow get() = inflowMean

    private class BladeLoads(
        force: Vec3, hubMoment: Vec3, shaftTorque: Double, thrust: Double, power: Double,
        val flapMoments: DoubleArray,
    ) : MainRotorLoads(force, hubMoment, shaftTorque, thrust, power)

    /** Unit vectors of one blade: radial (with flap), tangential (direction of motion = hinge axis), flap direction. */
    private class BladeFrame(psi: Double, beta: Double) {
        val cosPsi = DMath.cos(psi)
        val sinPsi = DMath.sin(psi)
        private val cosBeta = DMath.cos(beta)
        private val sinBeta = DMath.sin(beta)
        val radial = Vec3(-cosPsi * cosBeta, -sinPsi * cosBeta, -sinBeta)
        val tangential = Vec3(sinPsi, -cosPsi, 0.0)
        val flapDir = Vec3(cosPsi * sinBeta, sinPsi * sinBeta, -cosBeta)
        val normal = tangential cross radial
        val radialScale = cosBeta
    }

    override fun loads(s: RigidBodyState, rotorSpeed: Double, pitches: Helicopter.BladePitches, groundEffect: Double): MainRotorLoads {
        val w = s.angularVelocity
        val vHub = s.velocityBody + (w cross hub)
        val inplane = DMath.hypot(vHub.x, vHub.y)
        // Pitt-Peters linear inflow: more inflow at the rear of the disc in forward flight (wake skew χ).
        var skewGradient = 0.0
        var dirX = 0.0
        var dirY = 0.0
        if (inplane > 0.1) {
            val chi = DMath.atan2(inplane, abs(inflowMean - vHub.z))
            skewGradient = 15.0 * DMath.PI / 32.0 * DMath.tan(chi / 2.0)
            dirX = vHub.x / inplane
            dirY = vHub.y / inplane
        }

        var force = Vec3.ZERO
        var hubMoment = Vec3.ZERO
        var shaftMoment = 0.0
        val flapMoments = DoubleArray(blades)
        for (b in 0 until blades) {
            val f = BladeFrame(azimuth + b * DMath.TWO_PI / blades, flap[b])
            val theta = pitches.collective + pitches.lateral * f.cosPsi - pitches.longitudinal * f.sinPsi
            val beta = flapRate[b]
            for (k in radii.indices) {
                val r = radii[k]
                val pos = f.radial * r
                val velocity = vHub + (w cross pos) + f.tangential * (rotorSpeed * r * f.radialScale) + f.flapDir * (r * beta)
                val along = (pos.x * dirX + pos.y * dirY) / radius
                val inducedVelocity = inflowMean * (1.0 - skewGradient * along)
                val wind = Vec3(-velocity.x, -velocity.y, inducedVelocity - velocity.z)
                val ut = -(wind dot f.tangential) // edgewise speed, positive for normal operation
                val wn = wind dot f.normal // through the section, negative for normal inflow
                val u2 = ut * ut + wn * wn
                if (u2 < 1e-12) continue
                val u = DMath.sqrt(u2)
                val alpha = theta + DMath.atan2(wn, ut)
                val q = 0.5 * airDensity * u2 * chord * dr
                val cl = if (lifting[k]) airfoil.cl(alpha) else 0.0
                val cd = airfoil.cd(alpha)
                val windDir = (f.tangential * (-ut) + f.normal * wn) / u
                val liftDir = f.radial cross windDir
                val sectionForce = liftDir * (q * cl) + windDir * (q * cd)
                force += sectionForce
                flapMoments[b] += r * (sectionForce dot f.flapDir)
                shaftMoment += (pos cross sectionForce).z
            }
            hubMoment += f.tangential * (p.flapSpring * flap[b] + p.flapDamping * flapRate[b])
        }
        val shaftTorque = -shaftMoment // aerodynamic torque resisting rotation
        return BladeLoads(force, hubMoment, shaftTorque, -force.z, shaftTorque * rotorSpeed, flapMoments)
    }

    override fun advance(
        start: MainRotorLoads,
        before: RigidBodyState,
        after: RigidBodyState,
        rotorSpeed: Double,
        pitches: Helicopter.BladePitches,
        specificForce: Vec3,
        groundEffect: Double,
        dt: Double,
    ) {
        val loads = start as BladeLoads

        // Uniform inflow relaxes towards the momentum-theory value for the current thrust.
        val vHub = after.velocityBody + (after.angularVelocity cross hub)
        val inplane = DMath.hypot(vHub.x, vHub.y)
        val target = momentumInflow(loads.thrust, vHub.z, inplane) * groundEffect
        val tipSpeed = maxOf(rotorSpeed, 1.0) * radius
        val lambda = maxOf(abs(inflowMean - vHub.z) / tipSpeed, 0.02)
        val tau = (0.849 / (4.0 * lambda * maxOf(rotorSpeed, 1.0))).coerceIn(0.005, 0.2)
        inflowMean += (target - inflowMean) * dt / tau

        // Flapping, per blade, semi-implicit Euler.
        val w = after.angularVelocity
        val wDot = (w - before.angularVelocity) / dt
        val wCrossZ = w cross Z
        var back = 0.0
        var right = 0.0
        var sum = 0.0
        for (b in 0 until blades) {
            val f = BladeFrame(azimuth + b * DMath.TWO_PI / blades, flap[b])
            val bladeOmega = w + Vec3(0.0, 0.0, rotorSpeed) + f.tangential * flapRate[b]
            val inertial = (wDot dot f.tangential) + rotorSpeed * (f.tangential dot wCrossZ) +
                (bladeOmega dot f.radial) * (bladeOmega dot f.flapDir)
            val moment = loads.flapMoments[b] - p.flapSpring * flap[b] - p.flapDamping * flapRate[b] -
                staticMoment * (specificForce dot f.flapDir)
            val accel = moment / flapInertia - inertial
            flapRate[b] += accel * dt
            flap[b] = (flap[b] + flapRate[b] * dt).coerceIn(-MAX_FLAP, MAX_FLAP)
            back += -flap[b] * f.cosPsi
            right += flap[b] * f.sinPsi
            sum += flap[b]
        }
        azimuth = (azimuth + rotorSpeed * dt) % DMath.TWO_PI

        val k = dt / DISPLAY_FILTER
        tpBack += (2.0 / blades * back - tpBack) * k
        tpRight += (2.0 / blades * right - tpRight) * k
        tpConing += (sum / blades - tpConing) * k
    }

    /** Induced velocity from Glauert's relation `vi·√(u² + (vi − w)²) = T/(2ρA)` for a known thrust. */
    private fun momentumInflow(thrust: Double, axial: Double, inplane: Double): Double {
        val t = thrust / (2.0 * airDensity * area)
        var v = inflowMean
        if (abs(v) < 0.1) v = DMath.sqrt(abs(t)) * (if (t < 0.0) -1.0 else 1.0)
        repeat(8) {
            val s = DMath.sqrt(inplane * inplane + (v - axial) * (v - axial))
            val g = v * s - t
            val dg = s + (if (s > 1e-9) v * (v - axial) / s else 0.0)
            val step = if (abs(dg) > 1e-6) g / dg else 0.0
            v -= step.coerceIn(-3.0, 3.0)
        }
        return v
    }

    override fun hashInto(hash: StateHash) {
        hash.add(azimuth).add(inflowMean)
        for (b in 0 until blades) hash.add(flap[b]).add(flapRate[b])
    }

    private companion object {
        val Z = Vec3(0.0, 0.0, 1.0)
        val MAX_FLAP = DMath.toRadians(20.0)
        const val DISPLAY_FILTER = 0.03 // s
    }
}

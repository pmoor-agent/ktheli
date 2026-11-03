package ktheli.physics.env

import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.random.Pcg32
import ktheli.physics.sim.StateHash

/**
 * @property speed mean wind at 10 m above ground, m/s
 * @property fromDegrees direction the wind blows *from*, like a weather report: 0 = north, 90 = east
 * @property shearExponent power-law wind profile `V(h) = V₁₀·(h/10 m)^α`; ~0.15 over short grass, more over
 *   rough terrain
 * @property turbulence multiplier on the MIL-F-8785C low-altitude Dryden intensity (`σ_w = 0.1·V(6 m)`);
 *   0 = smooth air, 1 = typical, 2 = rough
 * @property gustiness 0..1: how often and how strongly discrete gusts come through
 */
data class WindSettings(
    val speed: Double = 0.0,
    val fromDegrees: Double = 270.0,
    val shearExponent: Double = 0.15,
    val turbulence: Double = 1.0,
    val gustiness: Double = 0.0,
)

/** International Standard Atmosphere at the flying field, with the actual temperature. */
data class AtmosphereSettings(val temperatureC: Double = 15.0, val elevation: Double = 0.0) {
    val pressure: Double get() = 101_325.0 * DMath.pow(1.0 - 2.25577e-5 * elevation, 5.25588) // Pa
    val density: Double get() = pressure / (287.058 * (temperatureC + 273.15)) // kg/m³
}

data class EnvironmentSettings(
    val wind: WindSettings = WindSettings(),
    val atmosphere: AtmosphereSettings = AtmosphereSettings(),
) {
    companion object {
        val CALM = EnvironmentSettings()
        val LIGHT_BREEZE = EnvironmentSettings(WindSettings(speed = 3.0, turbulence = 0.8, gustiness = 0.2))
        val BREEZY = EnvironmentSettings(WindSettings(speed = 6.0, turbulence = 1.0, gustiness = 0.4))
        val GUSTY_AFTERNOON = EnvironmentSettings(WindSettings(speed = 8.0, turbulence = 1.5, gustiness = 0.8), AtmosphereSettings(temperatureC = 28.0))
        val HIGH_AND_HOT = EnvironmentSettings(WindSettings(speed = 2.0, turbulence = 0.8), AtmosphereSettings(temperatureC = 35.0, elevation = 2000.0))
    }
}

/**
 * The air the aircraft flies in: density, and wind as the sum of
 *
 * - a **mean wind** with a power-law height profile,
 * - **Dryden turbulence** (MIL-F-8785C, low altitude): white noise from a seeded [Pcg32] shaped by first-order
 *   (along-wind) and second-order (lateral, vertical) filters whose length scales and intensities depend on
 *   height and mean wind,
 * - discrete **"1−cosine" gusts** at random intervals.
 *
 * Turbulence is a frozen field carried past the aircraft by the relative airflow: it is generated slightly
 * upstream and kept in a short history, so a point further downstream (the tail) sees the same gust a little
 * later than the rotor. That is where the yaw and pitch kicks of real gusts come from.
 *
 * Everything advances once per tick from [settings] and the seed, so it is deterministic. Changing [settings]
 * is a simulation input like the sticks (record it with its tick for a replay).
 */
class Environment(settings: EnvironmentSettings = EnvironmentSettings.CALM, seed: Long = 1) {
    var settings: EnvironmentSettings = settings
        set(value) {
            field = value
            density = value.atmosphere.density
        }

    var density: Double = settings.atmosphere.density
        private set

    private val rng = Pcg32.seeded(seed, stream = 0x57494E44L) // "WIND"
    private val u = FirstOrder()
    private val v = SecondOrder()
    private val w = SecondOrder()

    private var gustRemaining = 0.0
    private var gustDuration = 0.0
    private var gustAmplitude = Vec3.ZERO
    private var nextGustIn = -1.0 // < 0: schedule on the next tick

    // Frozen-turbulence history: one sample per tick, generated UPSTREAM metres ahead of the reference point.
    private val historyX = DoubleArray(HISTORY)
    private val historyY = DoubleArray(HISTORY)
    private val historyZ = DoubleArray(HISTORY)
    private var head = 0
    private var filled = 0
    private var dt = 1.0 / 2000
    private var reference = Vec3.ZERO
    private var flowDirection = Vec3(1.0, 0.0, 0.0)
    private var flowSpeed = MIN_FLOW_SPEED

    /** Latest turbulence + gust at the generation point (world NED), for displays. */
    var disturbance: Vec3 = Vec3.ZERO
        private set

    /** Mean wind (air velocity over ground, world NED) at [altitude] metres above ground. */
    fun meanWind(altitude: Double): Vec3 {
        val ws = settings.wind
        if (ws.speed <= 0.0) return Vec3.ZERO
        val h = maxOf(altitude, MIN_PROFILE_HEIGHT)
        val speed = ws.speed * DMath.pow(h / 10.0, ws.shearExponent)
        val from = DMath.toRadians(ws.fromDegrees)
        return Vec3(-speed * DMath.cos(from), -speed * DMath.sin(from), 0.0)
    }

    /**
     * Advances turbulence and gusts by one tick around the aircraft at [position] moving at [velocity]
     * (world NED). Call once per tick, before the aircraft samples [windAt].
     */
    fun step(position: Vec3, velocity: Vec3, dt: Double) {
        this.dt = dt
        reference = position
        val altitude = -position.z
        val mean = meanWind(altitude)
        val relative = mean - velocity
        val relativeSpeed = relative.length()
        flowSpeed = maxOf(relativeSpeed, MIN_FLOW_SPEED)
        if (relativeSpeed > 1e-6) flowDirection = relative / relativeSpeed

        // Dryden low-altitude model (MIL-F-8785C), heights in feet inside the formulas.
        val hFt = altitude.coerceIn(MIN_DRYDEN_HEIGHT, MAX_DRYDEN_HEIGHT) * FEET
        val k = 0.177 + 0.000823 * hFt
        val sigmaW = 0.1 * meanWind(6.1).length() * settings.wind.turbulence
        val sigmaU = sigmaW / DMath.pow(k, 0.4)
        val lengthU = hFt / DMath.pow(k, 1.2) / FEET
        val lengthW = hFt / FEET
        val nu = rng.nextGaussian()
        val nv = rng.nextGaussian()
        val nw = rng.nextGaussian()
        val tu = u.step(nu, lengthU / flowSpeed, dt) * sigmaU
        val tv = v.step(nv, lengthU / flowSpeed, dt) * sigmaU
        val groundDamping = (altitude / 2.0).coerceIn(0.0, 1.0) // vertical gusts can't blow into the ground
        val tw = w.step(nw, lengthW / flowSpeed, dt) * sigmaW * groundDamping

        // Turbulence axes: along the mean wind, lateral (to its right), vertical (down).
        val meanSpeed = mean.length()
        val along = if (meanSpeed > 1e-6) Vec3(mean.x / meanSpeed, mean.y / meanSpeed, 0.0) else Vec3(1.0, 0.0, 0.0)
        val lateral = Vec3(-along.y, along.x, 0.0)
        val turbulence = along * tu + lateral * tv + Vec3(0.0, 0.0, tw)

        disturbance = turbulence + gust(along)
        head = (head + 1) % HISTORY
        historyX[head] = disturbance.x
        historyY[head] = disturbance.y
        historyZ[head] = disturbance.z
        if (filled < HISTORY) filled++
    }

    private fun gust(along: Vec3): Vec3 {
        val ws = settings.wind
        if (ws.gustiness <= 0.0 || ws.speed <= 0.0) {
            gustRemaining = 0.0
            nextGustIn = -1.0
            return Vec3.ZERO
        }
        if (gustRemaining > 0.0) {
            val phase = 1.0 - gustRemaining / gustDuration
            gustRemaining -= dt
            return gustAmplitude * (0.5 * (1.0 - DMath.cos(DMath.TWO_PI * phase)))
        }
        if (nextGustIn < 0.0) nextGustIn = nextInterval(ws)
        nextGustIn -= dt
        if (nextGustIn <= 0.0) {
            gustDuration = 1.5 + 2.5 * rng.nextDouble()
            gustRemaining = gustDuration
            val strength = ws.speed * (0.25 + 0.5 * ws.gustiness) * (0.6 + 0.8 * rng.nextDouble())
            val turn = DMath.toRadians(25.0) * rng.nextGaussian()
            val c = DMath.cos(turn)
            val s = DMath.sin(turn)
            gustAmplitude = Vec3(along.x * c - along.y * s, along.x * s + along.y * c, 0.0) * strength
            nextGustIn = nextInterval(ws)
        }
        return Vec3.ZERO
    }

    private fun nextInterval(ws: WindSettings): Double {
        val meanInterval = 20.0 / (0.2 + 1.8 * ws.gustiness) // ~11 s at 0.5, ~10 s at 1
        return maxOf(2.0, -DMath.ln(1.0 - rng.nextDouble()) * meanInterval)
    }

    /** Wind (air velocity over ground, world NED) at a world point near the aircraft, on the current tick. */
    fun windAt(point: Vec3): Vec3 {
        val mean = meanWind(-point.z)
        if (filled == 0) return mean
        // Delay from the generation point (UPSTREAM metres up the relative flow) to this point.
        val downstream = UPSTREAM + ((point - reference) dot flowDirection)
        val delayTicks = (downstream / flowSpeed / dt).coerceIn(0.0, (filled - 1).toDouble())
        val back = delayTicks.toInt()
        val f = delayTicks - back
        val i0 = Math.floorMod(head - back, HISTORY)
        val i1 = Math.floorMod(head - back - 1, HISTORY)
        val n = if (back + 1 < filled) i1 else i0
        return mean + Vec3(
            historyX[i0] + (historyX[n] - historyX[i0]) * f,
            historyY[i0] + (historyY[n] - historyY[i0]) * f,
            historyZ[i0] + (historyZ[n] - historyZ[i0]) * f,
        )
    }

    fun hashInto(hash: StateHash) {
        hash.add(rng.state).add(u.x).add(v.x1).add(v.x2).add(w.x1).add(w.x2)
        hash.add(gustRemaining).add(gustDuration).add(gustAmplitude).add(nextGustIn).add(density)
        hash.add(head.toLong()).add(filled.toLong())
        for (i in 0 until HISTORY) hash.add(historyX[i]).add(historyY[i]).add(historyZ[i])
    }

    /**
     * Unit-variance first-order shaping filter `1/(1 + τs)` driven by discrete white noise: output scaled
     * by `√(2τ)` so its variance is 1 for any τ.
     */
    private class FirstOrder {
        var x = 0.0

        fun step(noise: Double, tau: Double, dt: Double): Double {
            x += dt / tau * (noise / DMath.sqrt(dt) - x)
            return x * DMath.sqrt(2.0 * tau)
        }
    }

    /** Unit-variance Dryden lateral/vertical filter `(1 + √3·τs)/(1 + τs)²`: variance 1/τ, scaled by `√τ`. */
    private class SecondOrder {
        var x1 = 0.0
        var x2 = 0.0

        fun step(noise: Double, tau: Double, dt: Double): Double {
            x1 += dt / tau * (noise / DMath.sqrt(dt) - x1)
            x2 += dt / tau * (x1 - x2)
            return (x2 + SQRT3 * (x1 - x2)) * DMath.sqrt(tau)
        }
    }

    private companion object {
        const val HISTORY = 16_384 // ticks; > 6 s at 2 kHz
        const val UPSTREAM = 1.5 // m ahead of the reference point where turbulence is generated
        const val MIN_FLOW_SPEED = 0.5 // m/s; frozen-field speed floor
        const val MIN_PROFILE_HEIGHT = 0.3 // m
        const val MIN_DRYDEN_HEIGHT = 3.05 // m (10 ft): the spec's lower limit
        const val MAX_DRYDEN_HEIGHT = 304.8 // m (1000 ft): low-altitude model limit
        const val FEET = 3.28084
        val SQRT3 = DMath.sqrt(3.0)
    }
}

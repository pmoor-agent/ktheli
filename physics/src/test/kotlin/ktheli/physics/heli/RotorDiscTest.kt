package ktheli.physics.heli

import ktheli.physics.math.DMath
import ktheli.physics.math.deg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RotorDiscTest {
    private val main = RotorDisc(Airframes.class450.mainRotor.rotor)
    private val omega = 2700.0 * DMath.TWO_PI / 60.0
    private val weight = Airframes.class450.mass * 9.80665

    /** Collective and converged output for a given thrust, by bisection. */
    private fun trim(thrust: Double, axial: Double = 0.0, inplane: Double = 0.0): Pair<Double, RotorDisc.Output> {
        var lo = -15.0.deg
        var hi = 15.0.deg
        repeat(60) {
            val mid = (lo + hi) / 2
            if (converged(mid, axial, inplane).thrust < thrust) lo = mid else hi = mid
        }
        return lo to converged(lo, axial, inplane)
    }

    private fun converged(collective: Double, axial: Double = 0.0, inplane: Double = 0.0): RotorDisc.Output {
        var vi = 0.0
        repeat(20) {
            vi = main.equilibriumInflow(RotorDisc.Input(collective, axial, inplane, omega, vi))
        }
        return main.evaluate(RotorDisc.Input(collective, axial, inplane, omega, vi))
    }

    @Test
    fun `hover needs about 4 degrees and induced velocity matches momentum theory`() {
        val (collective, out) = trim(weight)
        println("hover collective %.2f deg, power %.1f W, torque %.3f Nm".format(DMath.toDegrees(collective), out.power, out.torque))
        assertTrue(DMath.toDegrees(collective) in 3.0..5.5)
        val ideal = DMath.sqrt(weight / (2 * RotorDisc.AIR_DENSITY * main.area))
        assertEquals(ideal, out.equilibriumInflow, 0.01)
    }

    @Test
    fun `negative collective gives negative thrust and inflow`() {
        val out = converged((-8.0).deg)
        assertTrue(out.thrust < -10.0)
        assertTrue(out.equilibriumInflow < 0.0)
        assertTrue(out.power > 0.0)
    }

    @Test
    fun `forward speed adds translational lift`() {
        val collective = trim(weight).first
        assertTrue(converged(collective, inplane = 8.0).thrust > 1.15 * weight)
    }

    @Test
    fun `steep descent at low collective windmills the rotor`() {
        val out = converged(1.0.deg, axial = 12.0)
        assertTrue(out.power < 0.0, "power ${out.power}")
    }

    @Test
    fun `ground effect reduces induced velocity close to the ground`() {
        assertEquals(0.75, RotorDisc.groundEffect(0.36, 0.0), 1e-9)
        assertTrue(RotorDisc.groundEffect(0.36, 0.25) < 0.9)
        assertTrue(RotorDisc.groundEffect(0.36, 2.0) > 0.99)
    }
}

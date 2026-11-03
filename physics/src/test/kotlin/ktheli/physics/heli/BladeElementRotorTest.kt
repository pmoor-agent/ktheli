package ktheli.physics.heli

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.math.deg
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BladeElementRotorTest {
    private val params = Airframes.class450.mainRotor
    private val omega = 2700.0 * DMath.TWO_PI / 60.0
    private val weight = Airframes.class450.mass * 9.80665
    private val dt = 1.0 / 2000
    private val hover = Vec3(0.0, 0.0, -9.80665)

    @Test
    fun `airfoil covers the full circle like a symmetric section`() {
        val a = Airfoil()
        assertEquals(0.0, a.cl(0.0), 1e-9)
        assertEquals(5.7 * 0.05, a.cl(0.05), 0.01)
        assertEquals(-a.cl(0.1), a.cl(-0.1), 1e-9)
        val clMax = (0..200).maxOf { a.cl((it / 10.0).deg) }
        assertTrue(clMax in 0.9..1.1, "Clmax $clMax")
        assertTrue(a.cl(18.0.deg) < 0.8 * clMax, "lift drops after stall")
        assertEquals(2.0, a.cd(90.0.deg), 0.05)
        assertTrue(a.cd(179.0.deg) < 0.05 && abs(a.cl(180.0.deg)) < 0.05, "trailing edge first")
        assertEquals(a.cl(0.3), a.cl(0.3 + DMath.TWO_PI), 1e-12)
    }

    /** Runs the rotor on a fixed (or prescribed) fuselage and returns loads averaged over the last revolution. */
    private fun run(
        pitches: Helicopter.BladePitches,
        seconds: Double = 1.0,
        body: RigidBodyState = RigidBodyState(),
        rotor: BladeElementRotor = BladeElementRotor(params),
    ): Pair<BladeElementRotor, MainRotorLoads> {
        val steps = (seconds / dt).toInt()
        val avgSteps = (DMath.TWO_PI / omega / dt).toInt() * 2
        var force = Vec3.ZERO
        var moment = Vec3.ZERO
        var torque = 0.0
        repeat(steps) { i ->
            val l = rotor.loads(body, omega, pitches, 1.0)
            rotor.advance(l, body, body, omega, pitches, hover, 1.0, dt)
            if (i >= steps - avgSteps) {
                force += l.force / avgSteps.toDouble()
                moment += l.hubMoment / avgSteps.toDouble()
                torque += l.shaftTorque / avgSteps
            }
        }
        return rotor to MainRotorLoads(force, moment, torque, -force.z, torque * omega)
    }

    private fun pitches(collective: Double, lateral: Double = 0.0, longitudinal: Double = 0.0) =
        Helicopter.BladePitches(collective, lateral, longitudinal, 0.0)

    @Test
    fun `hover collective and power are in the range of the disc model`() {
        var lo = 0.0.deg
        var hi = 10.0.deg
        repeat(20) {
            val mid = (lo + hi) / 2
            if (run(pitches(mid), seconds = 0.6).second.thrust < weight) lo = mid else hi = mid
        }
        val (rotor, l) = run(pitches(lo))
        println("BET hover: collective %.2f deg, thrust %.2f N, power %.1f W, inflow %.2f m/s, coning %.2f deg".format(
            DMath.toDegrees(lo), l.thrust, l.power, rotor.inflow, DMath.toDegrees(rotor.coning)))
        assertTrue(DMath.toDegrees(lo) in 3.0..6.0)
        assertTrue(l.power in 60.0..130.0)
        assertTrue(DMath.toDegrees(rotor.coning) in 0.5..6.0, "blades cone up under load")
    }

    @Test
    fun `cyclic tilts the disc the right way with a modest phase error`() {
        val (rotor, l) = run(pitches(4.0.deg, lateral = 3.0.deg))
        val phase = DMath.toDegrees(DMath.atan2(rotor.tiltBack, rotor.tiltRight))
        println("lateral cyclic 3 deg: tilt right %.2f deg, back %.2f deg (phase error %.1f deg), hub moment %s".format(
            DMath.toDegrees(rotor.tiltRight), DMath.toDegrees(rotor.tiltBack), phase, l.hubMoment))
        assertTrue(rotor.tiltRight > 2.0.deg, "disc tilts right")
        assertTrue(abs(phase) < 25.0)
        assertTrue(l.hubMoment.x > 0.0, "rolls the fuselage right")

        val (r2, _) = run(pitches(4.0.deg, longitudinal = 3.0.deg))
        println("longitudinal cyclic 3 deg: tilt back %.2f deg, right %.2f deg".format(DMath.toDegrees(r2.tiltBack), DMath.toDegrees(r2.tiltRight)))
        assertTrue(r2.tiltBack < -2.0.deg, "disc tilts forward")
    }

    @Test
    fun `pitching the fuselage makes the rotor roll it left and resist the pitch`() {
        val q = 2.0 // rad/s nose-up
        val (rotor, l) = run(pitches(4.0.deg), body = RigidBodyState(angularVelocity = Vec3(0.0, q, 0.0)))
        val polar = 2 * rotor.flapInertia
        println("pitch rate %.1f: hub moment %s, gyroscopic -w x H would be %.3f".format(q, l.hubMoment, -polar * omega * q))
        assertTrue(l.hubMoment.x < 0.0, "gyroscopic roll left for a clockwise rotor")
        assertTrue(l.hubMoment.y < 0.0, "aerodynamic damping resists the pitch rate")
    }
}

package ktheli.app

import ktheli.app.session.CameraMode
import ktheli.app.session.CameraRig
import ktheli.app.session.CameraSettings
import ktheli.app.session.FlyingFields
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Quat
import ktheli.physics.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraRigTest {
    private val follow = CameraSettings(mode = CameraMode.FOLLOW, followDistance = 2.0, followHeight = 0.5, followSmoothing = 0.5)
    private val dt = 1.0 / 60
    private val eye = FlyingFields.HOME.pilotEye

    private fun heli(position: Vec3, headingDeg: Double = 0.0) =
        RigidBodyState(position = position, attitude = Quat.fromEuler(0.0, 0.0, Math.toRadians(headingDeg)))

    @Test
    fun `follow camera settles behind and above the nose at the set distance`() {
        val rig = CameraRig()
        val h = heli(Vec3(10.0, 5.0, -3.0), headingDeg = 90.0) // nose east
        var pose = rig.update(h, 0.36, follow, dt, eye)
        repeat(300) { pose = rig.update(h, 0.36, follow, dt, eye) }
        val offset = pose.position - h.position
        assertEquals(0.0, offset.x, 0.01)
        assertEquals(-2.0, offset.y, 0.01) // west of it, i.e. behind
        assertEquals(-0.5, offset.z, 0.01) // above
        assertEquals(h.position, pose.target)
        assertEquals(60.0, pose.fovDegrees)
    }

    @Test
    fun `follow camera flows round smoothly when the heli turns, without overshoot`() {
        val rig = CameraRig()
        val position = Vec3(0.0, 0.0, -5.0)
        repeat(300) { rig.update(heli(position, 0.0), 0.36, follow, dt, eye) }
        var last = rig.update(heli(position, 0.0), 0.36, follow, dt, eye).position
        var maxStep = 0.0
        var maxDistance = 0.0
        repeat(240) {
            val p = rig.update(heli(position, 90.0), 0.36, follow, dt, eye).position
            maxStep = maxOf(maxStep, (p - last).length())
            maxDistance = maxOf(maxDistance, (p - position).length())
            last = p
        }
        val bearing = Math.toDegrees(atan2(last.y - position.y, last.x - position.x))
        assertEquals(-90.0, bearing, 2.0) // ended up behind the new heading (nose east, camera west)
        assertTrue(maxStep < 0.15, "no jumps: $maxStep m per frame")
        val settled = (last - position).length()
        assertTrue(maxDistance < settled * 1.05, "no overshoot beyond the set distance")
    }

    @Test
    fun `follow camera snaps on a reset and stays above the ground`() {
        val rig = CameraRig()
        repeat(60) { rig.update(heli(Vec3(0.0, 0.0, -20.0)), 0.36, follow, dt, eye) }
        val parked = heli(Vec3(6.0, 0.0, -0.11)) // reset: parked on the ground, far away
        val pose = rig.update(parked, 0.36, follow.copy(followHeight = -1.0), dt, eye)
        assertEquals(4.0, pose.position.x, 0.01) // snapped straight to 2 m behind
        assertTrue(-pose.position.z >= CameraRig.MIN_HEIGHT - 1e-9)
    }

    @Test
    fun `pilot view stays put and zooms with distance unless auto-zoom is off`() {
        val rig = CameraRig()
        val near = rig.update(heli(Vec3(6.0, 0.0, -2.0)), 0.36, CameraSettings(), dt, eye)
        val far = rig.update(heli(Vec3(40.0, 0.0, -10.0)), 0.36, CameraSettings(), dt, eye)
        assertEquals(eye, near.position)
        assertTrue(far.fovDegrees < near.fovDegrees)
        val fixed = rig.update(heli(Vec3(40.0, 0.0, -10.0)), 0.36, CameraSettings(autoZoom = false, pilotFov = 42.0), dt, eye)
        assertEquals(42.0, fixed.fovDegrees)
    }

    @Test
    fun `heading wraps the short way round`() {
        assertEquals(-10.0, Math.toDegrees(CameraRig.wrap(Math.toRadians(350.0))), 1e-9)
        assertTrue(abs(CameraRig.wrap(Math.toRadians(-190.0)) - Math.toRadians(170.0)) < 1e-9)
    }
}

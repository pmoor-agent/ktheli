package ktheli.app

import kotlinx.serialization.Serializable
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
enum class CameraMode(val label: String) {
    /** Standing at the flying field, like a real RC pilot: fixed position, tracking the model. */
    PILOT("Pilot's eye"),

    /** Flowing along behind the model at a fixed distance. */
    FOLLOW("Follow"),
}

/**
 * Camera configuration, persisted with the other settings. Distances in metres, angles in degrees, smoothing
 * as a time constant in seconds.
 */
@Serializable
data class CameraSettings(
    val mode: CameraMode = CameraMode.PILOT,
    /** Pilot's eye: zoom so the model keeps a usable apparent size, else a fixed field of view. */
    val autoZoom: Boolean = true,
    val pilotFov: Double = 50.0,
    val followDistance: Double = 1.8,
    val followHeight: Double = 0.5,
    val followSmoothing: Double = 0.5,
    val followFov: Double = 60.0,
    val binoculars: BinocularSettings = BinocularSettings(),
)

/** Where the camera is and what it looks at, in world NED coordinates. */
data class CameraPose(val position: Vec3, val target: Vec3, val fovDegrees: Double)

/**
 * Moves the camera each rendered frame. Display only: it never feeds back into the simulation.
 *
 * The follow camera tracks the model's heading with a first-order lag and moves on a critically damped spring
 * towards a point [CameraSettings.followDistance] behind and [CameraSettings.followHeight] above the model,
 * so it flows smoothly, never overshoots, and always settles at that distance. It stays above the ground and
 * snaps when the model jumps between frames (a reset) or the mode changes.
 */
class CameraRig {
    private var position: Vec3? = null
    private var velocity = Vec3.ZERO
    private var heading = 0.0 // rad, NED yaw the follow camera looks along
    private var lastMode: CameraMode? = null
    private var lastHeli: Vec3? = null

    /** [pilotEye] is where the pilot stands at the current field (world NED). */
    fun update(heli: RigidBodyState, rotorRadius: Double, s: CameraSettings, dt: Double, pilotEye: Vec3): CameraPose {
        val modeChanged = s.mode != lastMode
        lastMode = s.mode
        // The model jumping metres between two frames can only be a reset (or a new model): snap.
        val jumped = lastHeli?.let { (heli.position - it).length() > JUMP_DISTANCE } ?: true
        lastHeli = heli.position
        return when (s.mode) {
            CameraMode.PILOT -> pilot(heli, rotorRadius, s, pilotEye)
            CameraMode.FOLLOW -> follow(heli, s, dt.coerceIn(0.0, MAX_DT), snap = modeChanged || jumped)
        }
    }

    private fun pilot(heli: RigidBodyState, rotorRadius: Double, s: CameraSettings, eye: Vec3): CameraPose {
        position = eye
        velocity = Vec3.ZERO
        val fov = if (s.autoZoom) {
            val halfWidth = VISIBLE_HALF_WIDTH * rotorRadius / REFERENCE_RADIUS
            val distance = maxOf((heli.position - eye).length(), 0.5)
            Math.toDegrees(2.0 * atan(halfWidth / distance)).coerceIn(12.0, 65.0)
        } else {
            s.pilotFov
        }
        return CameraPose(eye, heli.position, fov)
    }

    private fun follow(heli: RigidBodyState, s: CameraSettings, dt: Double, snap: Boolean): CameraPose {
        // Heading: where the nose points over the ground. Nose straight up or down has no heading: keep the last.
        val nose = heli.attitude.rotate(Vec3.X)
        val horizontal = sqrt(nose.x * nose.x + nose.y * nose.y)
        val noseHeading = if (horizontal > 0.2) atan2(nose.y, nose.x) else heading
        val smoothing = s.followSmoothing.coerceAtLeast(0.05)

        val current = position
        val start = snap || current == null
        if (start) {
            heading = noseHeading
        } else {
            heading += wrap(noseHeading - heading) * (1.0 - exp(-dt / smoothing))
        }
        val desired = heli.position + Vec3(-cos(heading) * s.followDistance, -sin(heading) * s.followDistance, -s.followHeight)

        var p: Vec3
        if (start) {
            p = desired
            velocity = Vec3.ZERO
        } else {
            // Critically damped spring, in substeps so it stays stable for any smoothing setting.
            val omega = 2.0 / smoothing
            val steps = maxOf(1, (dt * omega / 0.2).toInt() + 1)
            val h = dt / steps
            p = current!!
            repeat(steps) {
                val accel = (desired - p) * (omega * omega) - velocity * (2.0 * omega)
                velocity += accel * h
                p += velocity * h
            }
        }
        if (-p.z < MIN_HEIGHT) {
            p = Vec3(p.x, p.y, -MIN_HEIGHT)
            if (velocity.z > 0.0) velocity = Vec3(velocity.x, velocity.y, 0.0)
        }
        position = p
        return CameraPose(p, heli.position, s.followFov)
    }

    companion object {
        const val VISIBLE_HALF_WIDTH = 3.5 // m of scene visible either side of a 450 when auto-zoomed
        const val REFERENCE_RADIUS = 0.36 // ... scaled for other rotor sizes
        const val MIN_HEIGHT = 0.3 // m: the follow camera stays above the grass
        const val JUMP_DISTANCE = 3.0 // m between two frames: a reset, not flight
        const val MAX_DT = 0.1

        fun wrap(angle: Double): Double {
            var a = angle % (2 * Math.PI)
            if (a > Math.PI) a -= 2 * Math.PI
            if (a < -Math.PI) a += 2 * Math.PI
            return a
        }
    }
}

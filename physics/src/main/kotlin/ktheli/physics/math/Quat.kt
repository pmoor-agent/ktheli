package ktheli.physics.math

/**
 * Immutable unit quaternion (w + xi + yj + zk). As an attitude it rotates body-frame vectors into the
 * world frame: `v_world = q.rotate(v_body)`.
 */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    operator fun plus(o: Quat) = Quat(w + o.w, x + o.x, y + o.y, z + o.z)
    operator fun times(s: Double) = Quat(w * s, x * s, y * s, z * s)

    fun conjugate() = Quat(w, -x, -y, -z)
    fun norm() = DMath.sqrt(w * w + x * x + y * y + z * z)
    fun normalized(): Quat = this * (1.0 / norm())

    /** Rotates [v] by this quaternion (body → world for an attitude). */
    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(u × v) + 2u × (u × v), with u = (x, y, z)
        val u = Vec3(x, y, z)
        val t = (u cross v) * 2.0
        return v + t * w + (u cross t)
    }

    /** Rotates [v] by the inverse of this quaternion (world → body for an attitude). */
    fun inverseRotate(v: Vec3): Vec3 = conjugate().rotate(v)

    /** Time derivative for body-frame angular velocity [omegaBody]: q̇ = ½ q ⊗ (0, ω). */
    fun derivative(omegaBody: Vec3): Quat = this * Quat(0.0, omegaBody.x, omegaBody.y, omegaBody.z) * 0.5

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(axis: Vec3, angle: Double): Quat {
            val a = axis.normalized()
            val s = DMath.sin(angle * 0.5)
            return Quat(DMath.cos(angle * 0.5), a.x * s, a.y * s, a.z * s)
        }

        /** Aerospace Z-Y-X sequence: yaw ψ, then pitch θ, then roll φ. */
        fun fromEuler(roll: Double, pitch: Double, yaw: Double): Quat =
            fromAxisAngle(Vec3.Z, yaw) * fromAxisAngle(Vec3.Y, pitch) * fromAxisAngle(Vec3.X, roll)
    }
}

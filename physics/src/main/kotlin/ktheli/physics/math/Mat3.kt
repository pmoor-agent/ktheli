package ktheli.physics.math

/** Immutable row-major 3×3 matrix, mainly for inertia tensors. */
data class Mat3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double,
) {
    operator fun times(v: Vec3) = Vec3(
        m00 * v.x + m01 * v.y + m02 * v.z,
        m10 * v.x + m11 * v.y + m12 * v.z,
        m20 * v.x + m21 * v.y + m22 * v.z,
    )

    fun determinant() =
        m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20)

    fun inverse(): Mat3 {
        val d = determinant()
        require(d != 0.0) { "Singular matrix" }
        val s = 1.0 / d
        return Mat3(
            (m11 * m22 - m12 * m21) * s, (m02 * m21 - m01 * m22) * s, (m01 * m12 - m02 * m11) * s,
            (m12 * m20 - m10 * m22) * s, (m00 * m22 - m02 * m20) * s, (m02 * m10 - m00 * m12) * s,
            (m10 * m21 - m11 * m20) * s, (m01 * m20 - m00 * m21) * s, (m00 * m11 - m01 * m10) * s,
        )
    }

    companion object {
        fun diagonal(xx: Double, yy: Double, zz: Double) = Mat3(xx, 0.0, 0.0, 0.0, yy, 0.0, 0.0, 0.0, zz)
    }
}

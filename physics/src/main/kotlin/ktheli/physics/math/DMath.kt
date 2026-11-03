package ktheli.physics.math

/**
 * Deterministic math for the physics module.
 *
 * `java.lang.Math` (and therefore `kotlin.math`) may use CPU-specific intrinsics whose results differ
 * by up to 1 ulp between platforms. `StrictMath` is specified to return the exact fdlibm results everywhere.
 * Basic arithmetic and `sqrt` are already exact IEEE 754 operations on the JVM, so they're fine to use directly.
 *
 * The `checkDeterminism` Gradle task enforces that physics code goes through this object.
 */
object DMath {
    const val PI = StrictMath.PI
    const val TWO_PI = 2.0 * StrictMath.PI

    fun sin(x: Double): Double = StrictMath.sin(x)
    fun cos(x: Double): Double = StrictMath.cos(x)
    fun tan(x: Double): Double = StrictMath.tan(x)
    fun asin(x: Double): Double = StrictMath.asin(x)
    fun acos(x: Double): Double = StrictMath.acos(x)
    fun atan(x: Double): Double = StrictMath.atan(x)
    fun atan2(y: Double, x: Double): Double = StrictMath.atan2(y, x)
    fun tanh(x: Double): Double = StrictMath.tanh(x)
    fun exp(x: Double): Double = StrictMath.exp(x)
    fun ln(x: Double): Double = StrictMath.log(x)
    fun pow(x: Double, y: Double): Double = StrictMath.pow(x, y)
    fun cbrt(x: Double): Double = StrictMath.cbrt(x)
    fun hypot(x: Double, y: Double): Double = StrictMath.hypot(x, y)
    fun sqrt(x: Double): Double = StrictMath.sqrt(x)

    fun toRadians(deg: Double): Double = deg * (PI / 180.0)
    fun toDegrees(rad: Double): Double = rad * (180.0 / PI)
}

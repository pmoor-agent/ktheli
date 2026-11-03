package ktheli.physics

/**
 * Coordinate frame conventions used throughout the physics module (standard flight-dynamics practice,
 * matching Padfield and the Gavrilets/Mettler small-helicopter papers):
 *
 * - **World (NED):** x = north, y = east, z = down. Origin at the pilot's feet on the ground.
 *   Altitude is therefore `-z`.
 * - **Body (FRD):** x = forward (nose), y = right, z = down (along the main rotor shaft, pointing
 *   away from the rotor). Origin at the center of gravity.
 * - Attitude quaternions rotate body vectors into world.
 * - Positive rotations follow the right-hand rule: roll right, pitch nose-up, yaw nose-right.
 *
 * All quantities are SI: m, kg, s, rad, N, N·m.
 */
object Frames {
    const val GRAVITY = 9.80665
}

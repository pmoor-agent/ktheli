package ktheli.physics.sim

import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Quat
import ktheli.physics.math.Vec3

/** 64-bit FNV-1a over raw IEEE bits; used to verify that two runs are bit-for-bit identical. */
class StateHash {
    var value: Long = OFFSET
        private set

    fun add(v: Long): StateHash {
        var x = v
        repeat(8) {
            value = (value xor (x and 0xFF)) * PRIME
            x = x ushr 8
        }
        return this
    }

    fun add(d: Double) = add(d.toRawBits())
    fun add(v: Vec3) = add(v.x).add(v.y).add(v.z)
    fun add(q: Quat) = add(q.w).add(q.x).add(q.y).add(q.z)
    fun add(s: RigidBodyState) = add(s.position).add(s.velocity).add(s.attitude).add(s.angularVelocity)

    private companion object {
        const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        const val PRIME = 0x100000001b3L
    }
}

package ktheli.physics.env

import ktheli.physics.math.Vec3
import kotlin.math.floor

/**
 * A vertical cylinder standing on the ground (world NED, ground at z = 0): a tree trunk or crown, a pole.
 * [bottom] and [top] are heights above ground, m.
 */
data class Obstacle(val name: String, val x: Double, val y: Double, val radius: Double, val bottom: Double, val top: Double) {
    fun contains(p: Vec3): Boolean {
        val height = -p.z
        if (height < bottom || height > top) return false
        val dx = p.x - x
        val dy = p.y - y
        return dx * dx + dy * dy < radius * radius
    }
}

/**
 * Fixed obstacles of a flying field, in a coarse grid (each obstacle listed in every cell its footprint
 * touches) so a point test only looks at the few nearby.
 * Lookups are in list order, so results are deterministic.
 */
class Obstacles(val all: List<Obstacle> = emptyList()) {
    private val cells = HashMap<Long, IntArray>()

    init {
        val building = HashMap<Long, MutableList<Int>>()
        all.forEachIndexed { i, o ->
            for (cx in cell(o.x - o.radius)..cell(o.x + o.radius)) {
                for (cy in cell(o.y - o.radius)..cell(o.y + o.radius)) building.getOrPut(key(cx, cy)) { ArrayList() } += i
            }
        }
        for ((k, v) in building) cells[k] = v.toIntArray()
    }

    /** The first obstacle containing [p], if any. */
    fun hit(p: Vec3): Obstacle? {
        if (all.isEmpty()) return null
        val indices = cells[key(cell(p.x), cell(p.y))] ?: return null
        for (i in indices) if (all[i].contains(p)) return all[i]
        return null
    }

    private fun cell(v: Double): Int = floor(v / CELL).toInt()

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    companion object {
        val NONE = Obstacles()
        private const val CELL = 8.0 // m
    }
}

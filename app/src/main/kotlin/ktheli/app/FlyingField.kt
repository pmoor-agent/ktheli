package ktheli.app

import ktheli.physics.math.Vec3
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A place to fly: where the pilot stands, where the model starts, and what the field looks like. Purely
 * presentation and placement; the physics ground is the flat plane z = 0 everywhere. Positions are world NED
 * (x north, y east, z down) with the origin at the field's centre.
 */
class FlyingField(
    val id: String,
    val name: String,
    val description: String,
    /** The pilot's eyes. */
    val pilotEye: Vec3,
    /** Where a model is parked on reset (z ignored). */
    val start: Vec3,
    /** Foot of the windsock pole. */
    val windsock: Vec3,
    val ground: Ground,
    val pads: List<Pad> = emptyList(),
    val trees: TreeLine? = null,
) {
    override fun toString() = id
}

/**
 * Grass: a tiling texture [tileSize] metres across, mowing stripes [stripeWidth] wide running north-south
 * (0 = none), and optionally the classic 2.5 m grid for depth perception.
 */
data class Ground(
    val size: Double = 600.0,
    val tileSize: Double = 3.0,
    val stripeWidth: Double = 6.0,
    val grid: Boolean = false,
    val textured: Boolean = true,
)

/** A round landing pad with an "H", drawn flat on the grass. */
data class Pad(val center: Vec3, val radius: Double)

/** A ring of trees around the field, [rows] staggered rows deep, placed deterministically from [seed]. */
data class TreeLine(
    val radius: Double = 200.0,
    val depth: Double = 30.0,
    val rows: Int = 3,
    val spacing: Double = 5.5,
    val minHeight: Double = 10.0,
    val maxHeight: Double = 20.0,
    val conifers: Double = 0.55,
    val seed: Long = 450,
) {
    enum class Kind { CONIFER, LEAFY }

    /** One tree: foot position (NED, on the ground), height, crown colour shade 0..1. */
    data class Tree(val position: Vec3, val height: Double, val kind: Kind, val shade: Double)

    /** The trees in a band [radius]..[radius] + [depth], [rows] staggered rows, roughly [spacing] apart in each. */
    fun trees(): List<Tree> {
        val rng = Random(seed)
        val out = ArrayList<Tree>()
        for (row in 0 until rows) {
            val r0 = radius + depth * row / rows
            val count = (2 * PI * r0 / spacing).toInt()
            for (i in 0 until count) {
                val angle = 2 * PI * (i + row.toDouble() / rows + 0.6 * (rng.nextDouble() - 0.5)) / count
                val r = r0 + depth / rows * rng.nextDouble()
                val height = minHeight + (maxHeight - minHeight) * rng.nextDouble()
                val kind = if (rng.nextDouble() < conifers) Kind.CONIFER else Kind.LEAFY
                out += Tree(Vec3(r * cos(angle), r * sin(angle), 0.0), height, kind, rng.nextDouble())
            }
        }
        return out
    }
}

object FlyingFields {
    /** A club field: asphalt helipad at the centre, mown grass, a tree line all around. */
    val HOME = FlyingField(
        id = "home",
        name = "Home field",
        description = "Helipad, mown grass, trees 200 m out",
        pilotEye = Vec3(-6.0, 0.0, -1.7),
        start = Vec3.ZERO,
        windsock = Vec3(4.0, -9.0, 0.0),
        ground = Ground(),
        pads = listOf(Pad(Vec3.ZERO, radius = 1.0)),
        trees = TreeLine(),
    )

    /** The original look: plain grass with a 2.5 m grid, nothing else. Also a clean backdrop for render tests. */
    val OPEN = FlyingField(
        id = "open",
        name = "Open field",
        description = "Plain grass with a grid, no obstacles",
        pilotEye = Vec3(-6.0, 0.0, -1.7),
        start = Vec3.ZERO,
        windsock = Vec3(3.0, -5.0, 0.0),
        ground = Ground(size = 1000.0, stripeWidth = 0.0, grid = true, textured = false),
    )

    val ALL = listOf(HOME, OPEN)
    val DEFAULT = HOME

    fun byId(id: String): FlyingField? = ALL.firstOrNull { it.id == id }
}

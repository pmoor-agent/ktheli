package ktheli.app.session

import ktheli.physics.env.Obstacle
import ktheli.physics.env.Obstacles
import ktheli.physics.math.DMath
import ktheli.physics.math.Vec3
import ktheli.physics.random.Pcg32
import kotlin.math.PI

/**
 * A place to fly: where the pilot stands, where the model starts, and what the field looks like. The physics
 * ground is the flat plane z = 0 everywhere; trees and the windsock pole are [obstacles] the model can crash
 * into. Positions are world NED (x north, y east, z down) with the origin at the field's centre.
 *
 * The obstacles are a simulation input, so everything they're built from is bit-reproducible: [Pcg32] and
 * [DMath] (StrictMath), as in the physics module.
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
    val windsockHeight: Double = 3.0,
    val ground: Ground,
    val pads: List<Pad> = emptyList(),
    val trees: TreeLine? = null,
) {
    /** Top of the windsock pole, where the sock shows the wind. */
    val windsockTop: Vec3 get() = Vec3(windsock.x, windsock.y, -windsockHeight)

    /** Rough crash shapes matching what the field renderer draws: a trunk and a crown per tree, the windsock pole. */
    val obstacles: Obstacles by lazy {
        val list = ArrayList<Obstacle>()
        list += Obstacle("the windsock", windsock.x, windsock.y, 0.04, 0.0, windsockHeight)
        trees?.trees()?.forEach { t ->
            val x = t.position.x
            val y = t.position.y
            val h = t.height
            when (t.kind) {
                TreeLine.Kind.CONIFER -> {
                    list += Obstacle("a tree", x, y, 0.2, 0.0, 0.15 * h)
                    list += Obstacle("a tree", x, y, 0.13 * h, 0.15 * h, 0.9 * h) // a cone, as a slimmer cylinder
                }
                TreeLine.Kind.LEAFY -> {
                    list += Obstacle("a tree", x, y, 0.25, 0.0, 0.45 * h)
                    list += Obstacle("a tree", x, y, 0.27 * h, 0.37 * h, 0.93 * h) // the round crown
                }
            }
        }
        Obstacles(list)
    }

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
        val rng = Pcg32.seeded(seed)
        val out = ArrayList<Tree>()
        for (row in 0 until rows) {
            val r0 = radius + depth * row / rows
            val count = (2 * PI * r0 / spacing).toInt()
            for (i in 0 until count) {
                val angle = 2 * PI * (i + row.toDouble() / rows + 0.6 * (rng.nextDouble() - 0.5)) / count
                val r = r0 + depth / rows * rng.nextDouble()
                val height = minHeight + (maxHeight - minHeight) * rng.nextDouble()
                val kind = if (rng.nextDouble() < conifers) Kind.CONIFER else Kind.LEAFY
                out += Tree(Vec3(r * DMath.cos(angle), r * DMath.sin(angle), 0.0), height, kind, rng.nextDouble())
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

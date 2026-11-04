package ktheli.app.render

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.ConeShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.CylinderShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.SphereShapeBuilder
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import ktheli.app.session.FlyingField
import ktheli.app.session.Pad
import ktheli.app.session.TreeLine
import ktheli.physics.math.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Draws a [FlyingField]: ground, pads, tree line and windsock. */
class FieldRenderer(val field: FlyingField) : Disposable {
    private val models = ArrayList<Model>()
    private val textures = ArrayList<Texture>()
    // The ground (grass, grid, pads) and what stands on it are drawn separately: see [renderGround].
    private val ground = ArrayList<ModelInstance>()
    private val groundUnlit = ArrayList<ModelInstance>()
    private val objects = ArrayList<ModelInstance>()
    private val windsock: ModelInstance
    private val sockDirection = Vector3(0f, -1f, 0f)
    private val sockRotation = Quaternion()
    private val lit = (Usage.Position or Usage.Normal).toLong()

    /** World position of the windsock's top, where the wind for [update] should be sampled. */

    init {
        val mb = ModelBuilder()
        buildGround(mb)
        for (pad in field.pads) buildPad(mb, pad)
        field.trees?.let { buildTrees(mb, it) }

        objects += ModelInstance(
            mb.createBox(0.05f, field.windsockHeight.toFloat(), 0.05f, Material(ColorAttribute.createDiffuse(Color.LIGHT_GRAY)), lit).keep(),
        ).apply { transform.setToTranslation(field.windsock.toGdx().add(0f, field.windsockHeight.toFloat() / 2, 0f)) }
        // A cone along +Y with its tip at +Y: the wide end is the sock's mouth at the pole.
        windsock = ModelInstance(
            mb.createCone(0.3f, SOCK_LENGTH, 0.3f, 16, Material(ColorAttribute.createDiffuse(Color(1f, 0.45f, 0.05f, 1f)), IntAttribute.createCullFace(GL20.GL_NONE)), lit).keep(),
        )
        objects += windsock
    }

    private fun Model.keep() = also { models += it }

    private fun buildGround(mb: ModelBuilder) {
        val g = field.ground
        val half = (g.size / 2).toFloat()
        val y = GROUND_Y
        if (!g.textured) {
            ground += ModelInstance(
                mb.createBox(g.size.toFloat(), 0.1f, g.size.toFloat(), Material(ColorAttribute.createDiffuse(Color(0.36f, 0.55f, 0.3f, 1f))), lit).keep(),
            ).apply { transform.setToTranslation(0f, y - 0.05f, 0f) }
        } else {
            val grass = FieldTextures.grass().also { textures += it }
            val stripe = if (g.stripeWidth > 0) g.stripeWidth else g.size
            // A stripe centred on x = 0, so no stripe edge runs right along the pilot's line of sight to the pad.
            val count = 2 * kotlin.math.ceil(g.size / 2 / stripe).toInt() + 1
            val shades = listOf(Color(1.04f, 1.04f, 1.04f, 1f), Color(0.9f, 0.93f, 0.9f, 1f))
            val attrs = lit or Usage.TextureCoordinates.toLong()
            mb.begin()
            for (i in 0 until count) {
                // Stripes run north-south (gdx -z); x is east. Texture offsets keep the grass seamless across stripes.
                val x0 = ((i - count / 2) * stripe - stripe / 2).toFloat().coerceAtLeast(-half)
                val x1 = ((i - count / 2) * stripe + stripe / 2).toFloat().coerceAtMost(half)
                val material = Material(
                    TextureAttribute.createDiffuse(grass).apply {
                        offsetU = (x0 / g.tileSize).toFloat()
                        scaleU = (stripe / g.tileSize).toFloat()
                        offsetV = (-half / g.tileSize).toFloat()
                        scaleV = (g.size / g.tileSize).toFloat()
                    },
                    ColorAttribute.createDiffuse(shades[i % 2]),
                )
                mb.part("stripe$i", GL20.GL_TRIANGLES, attrs, material)
                    .rect(x0, y, half, x1, y, half, x1, y, -half, x0, y, -half, 0f, 1f, 0f)
            }
            ground += ModelInstance(mb.end().keep())
        }
        if (g.grid) {
            groundUnlit += ModelInstance(
                mb.createLineGrid(80, 80, 2.5f, 2.5f, Material(ColorAttribute.createDiffuse(Color(0.27f, 0.42f, 0.23f, 1f))), Usage.Position.toLong()).keep(),
            )
        }
    }

    private fun buildPad(mb: ModelBuilder, pad: Pad) {
        val texture = FieldTextures.helipad().also { textures += it }
        val c = pad.center.toGdx()
        val r = pad.radius.toFloat()
        val material = Material(
            TextureAttribute.createDiffuse(texture),
            BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f), // round: transparent corners
        )
        mb.begin()
        // Corners SW, SE, NE, NW (gdx x = east, -z = north): the texture's V axis, and the H's bars, run north-south.
        mb.part("pad", GL20.GL_TRIANGLES, lit or Usage.TextureCoordinates.toLong(), material).rect(
            c.x - r, PAD_Y, c.z + r, c.x + r, PAD_Y, c.z + r, c.x + r, PAD_Y, c.z - r, c.x - r, PAD_Y, c.z - r, 0f, 1f, 0f,
        )
        ground += ModelInstance(mb.end().keep())
    }

    /** All trees in a few merged meshes with per-vertex colours: a handful of draw calls for hundreds of trees. */
    private fun buildTrees(mb: ModelBuilder, line: TreeLine) {
        val attrs = lit or Usage.ColorUnpacked.toLong()
        val material = Material(ColorAttribute.createDiffuse(Color.WHITE))
        val m = Matrix4()
        mb.begin()
        var part: MeshPartBuilder? = null
        var parts = 0
        fun builder(): MeshPartBuilder {
            val current = part
            if (current != null && current.lastIndex() < MAX_VERTICES_PER_PART) return current
            return mb.part("trees${parts++}", GL20.GL_TRIANGLES, attrs, material).also { part = it }
        }
        for (tree in line.trees()) {
            val p = tree.position.toGdx()
            val h = tree.height.toFloat()
            val shade = 0.85f + 0.3f * tree.shade.toFloat()
            val b = builder()
            fun at(height: Float) = b.setVertexTransform(m.setToTranslation(p.x, height, p.z))
            when (tree.kind) {
                TreeLine.Kind.CONIFER -> {
                    b.setColor(0.33f, 0.24f, 0.15f, 1f)
                    at(0.125f * h)
                    CylinderShapeBuilder.build(b, 0.4f, 0.25f * h, 0.4f, 5)
                    b.setColor(0.12f * shade, 0.30f * shade, 0.15f * shade, 1f)
                    at(0.15f * h + 0.425f * h)
                    ConeShapeBuilder.build(b, 0.38f * h, 0.85f * h, 0.38f * h, 9)
                }
                TreeLine.Kind.LEAFY -> {
                    b.setColor(0.36f, 0.27f, 0.17f, 1f)
                    at(0.225f * h)
                    CylinderShapeBuilder.build(b, 0.5f, 0.45f * h, 0.5f, 5)
                    b.setColor(0.24f * shade, 0.42f * shade, 0.18f * shade, 1f)
                    at(0.65f * h)
                    SphereShapeBuilder.build(b, 0.62f * h, 0.62f * h, 0.62f * h, 9, 6)
                }
            }
        }
        part?.setVertexTransformationEnabled(false)
        objects += ModelInstance(mb.end().keep())
    }

    /** Points the sock downwind ([windAtSock], world NED); it hangs down in calm air and lifts at ~8 m/s. */
    fun update(windAtSock: Vec3) {
        val horizontal = Vec3(windAtSock.x, windAtSock.y, 0.0)
        val speed = horizontal.length()
        val droop = Math.toRadians(85.0) * (1.0 - (speed / 8.0).coerceIn(0.0, 1.0))
        val dir = if (speed > 1e-6) horizontal / speed else Vec3.X
        val ned = Vec3(dir.x * cos(droop), dir.y * cos(droop), sin(droop))
        sockDirection.set(ned.toGdx()).nor()
        sockRotation.setFromCross(Vector3.Y, sockDirection)
        windsock.transform.set(field.windsockTop.toGdx().add(sockDirection.cpy().scl(SOCK_LENGTH / 2)), sockRotation)
    }

    /**
     * The flat ground: grass, grid and pads. ModelBatch draws opaque before blended renderables, so the pads
     * (blended for their round edge) always land on the grass. Flush the batch before drawing anything that
     * lies on top (the shadow), so distance sorting can't put it underneath.
     */
    fun renderGround(batch: ModelBatch, environment: Environment) {
        for (i in ground) batch.render(i, environment)
        for (i in groundUnlit) batch.render(i)
    }

    /** Everything standing on the ground: trees and the windsock. */
    fun renderObjects(batch: ModelBatch, environment: Environment) {
        for (i in objects) batch.render(i, environment)
    }

    override fun dispose() {
        models.forEach(Model::dispose)
        textures.forEach(Texture::dispose)
    }

    companion object {
        const val SOCK_LENGTH = 0.9f
        const val GROUND_Y = -0.01f // just under the grid lines
        const val PAD_Y = 0.004f // painted on the grass
        const val SHADOW_Y = 0.008f // above the pad
        // ModelBuilder starts a new mesh once a mesh passes half of MeshBuilder.MAX_VERTICES (16-bit indices).
        private const val MAX_VERTICES_PER_PART = MeshBuilder.MAX_VERTICES / 2
    }
}

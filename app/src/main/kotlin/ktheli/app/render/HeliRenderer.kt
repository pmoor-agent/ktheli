package ktheli.app.render

import com.badlogic.gdx.Gdx
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
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader
import com.badlogic.gdx.graphics.g3d.model.data.ModelData
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.TextureProvider
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.CylinderShapeBuilder
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import ktheli.app.session.HelicopterVisual
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3

/**
 * Rotor state for drawing, from the simulation.
 *
 * @property bladeAzimuth main rotor blade 0 azimuth (rad, from the tail in the direction of rotation) when the
 *   simulation tracks individual blades; otherwise the renderer integrates [rpm]
 */
class RotorPose(
    val rpm: Double,
    val tailRpm: Double,
    val tiltBack: Double = 0.0,
    val tiltRight: Double = 0.0,
    val bladeAzimuth: Double? = null,
)

/** Draws one helicopter model. Created from a [HelicopterVisual] by [create]. */
interface HeliRenderer : Disposable {
    /** Poses the model for this frame: [state] is the (interpolated) body, [dt] and [fps] the frame timing. */
    fun update(state: RigidBodyState, rotors: RotorPose, dt: Float, fps: Float)

    fun render(batch: ModelBatch, environment: Environment)

    companion object {
        fun create(visual: HelicopterVisual): HeliRenderer = when (visual) {
            is HelicopterVisual.Obj -> ObjHeliRenderer(visual)
            is HelicopterVisual.Procedural -> ProceduralHeliRenderer(visual)
        }

        /** Body-to-world transform: (NED → libGDX Y-up) · [R_body→NED | p]. Models are authored in body FRD. */
        fun setBodyTransform(m: Matrix4, s: RigidBodyState) {
            val bx = s.attitude.rotate(Vec3.X).toGdx()
            val by = s.attitude.rotate(Vec3.Y).toGdx()
            val bz = s.attitude.rotate(Vec3.Z).toGdx()
            val p = s.position.toGdx()
            val v = m.`val`
            v[Matrix4.M00] = bx.x; v[Matrix4.M10] = bx.y; v[Matrix4.M20] = bx.z; v[Matrix4.M30] = 0f
            v[Matrix4.M01] = by.x; v[Matrix4.M11] = by.y; v[Matrix4.M21] = by.z; v[Matrix4.M31] = 0f
            v[Matrix4.M02] = bz.x; v[Matrix4.M12] = bz.y; v[Matrix4.M22] = bz.z; v[Matrix4.M32] = 0f
            v[Matrix4.M03] = p.x; v[Matrix4.M13] = p.y; v[Matrix4.M23] = p.z; v[Matrix4.M33] = 1f
        }

        /** Tip-path-plane tilt: back about body y, right about body x. */
        fun tilt(out: Quaternion, back: Double, right: Double, scratch: Quaternion): Quaternion =
            out.set(Vector3.X, Math.toDegrees(right).toFloat()).mul(scratch.set(Vector3.Y, Math.toDegrees(back).toFloat()))
    }
}

/**
 * Renders an OBJ asset: sharp blade meshes that fade into blur discs as the rotors speed up ([RotorBlur]),
 * the main rotor tilted with the simulated tip-path plane and turned to the simulated blade azimuth.
 */
class ObjHeliRenderer(private val visual: HelicopterVisual.Obj) : HeliRenderer {
    private val model: Model = load()
    private val instance = ModelInstance(model)
    private var mainAzimuthDeg = 0.0
    private var tailAzimuthDeg = 0.0
    private val tilt = Quaternion()
    private val scratch = Quaternion()

    private fun load(): Model {
        val data = ObjLoader().loadModelData(Gdx.files.internal(visual.objPath), ObjLoader.ObjLoaderParameters(true))
        val n = visual.nodes
        // Rotor meshes are authored in place; move their origins to the hubs so they can spin and tilt there.
        recenter(data, n.mainBlades, visual.mainHub)
        recenter(data, n.mainDisc, visual.mainHub)
        recenter(data, n.tailBlades, visual.tailHub)
        recenter(data, n.tailDisc, visual.tailHub)

        val textures = TextureProvider.FileTextureProvider(
            Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear,
            Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge, true,
        )
        return Model(data, textures).apply {
            getMaterial(visual.bodyMaterial)?.apply {
                set(FloatAttribute.createShininess(visual.shininess))
                set(IntAttribute.createCullFace(GL20.GL_NONE))
            }
            getMaterial(visual.blurMaterial)?.apply {
                set(BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0f))
                set(DepthTestAttribute(GL20.GL_LEQUAL, false))
                set(IntAttribute.createCullFace(GL20.GL_NONE))
            }
            // Each rotor part gets its own material copy: blades fade without fading the airframe, and the main
            // and tail rotors (which turn at very different speeds) fade independently even though the asset
            // gives both discs one blur material.
            val body = getMaterial(visual.bodyMaterial)
            val blur = getMaterial(visual.blurMaterial)
            fun own(nodeId: String, base: Material?, blended: Boolean) {
                val copy = base?.copy() ?: return
                copy.id = "${base.id}_$nodeId"
                if (blended) copy.set(BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f))
                materials.add(copy)
                getNode(nodeId)?.parts?.forEach { it.material = copy }
            }
            own(n.mainBlades, body, blended = true)
            own(n.tailBlades, body, blended = true)
            own(n.mainDisc, blur, blended = false)
            own(n.tailDisc, blur, blended = false)
        }
    }

    private fun recenter(data: ModelData, nodeId: String, pivot: Vec3) {
        val node = data.nodes.firstOrNull { it.id == nodeId } ?: return
        val mesh = data.meshes.firstOrNull { it.id == node.meshId } ?: return
        val stride = mesh.attributes.sumOf { it.numComponents }
        var i = 0
        while (i < mesh.vertices.size) {
            mesh.vertices[i] -= pivot.x.toFloat()
            mesh.vertices[i + 1] -= pivot.y.toFloat()
            mesh.vertices[i + 2] -= pivot.z.toFloat()
            i += stride
        }
        node.translation.set(pivot.x.toFloat(), pivot.y.toFloat(), pivot.z.toFloat())
    }

    override fun update(state: RigidBodyState, rotors: RotorPose, dt: Float, fps: Float) {
        HeliRenderer.setBodyTransform(instance.transform, state)
        val b = visual.blur
        val n = visual.nodes

        // Main rotor: the simulated blade azimuth when available (positive about body +z, i.e. clockwise
        // seen from above), else integrated from rpm.
        mainAzimuthDeg = rotors.bladeAzimuth?.let { Math.toDegrees(it) } ?: ((mainAzimuthDeg + rotors.rpm * 6.0 * dt) % 360.0)
        tailAzimuthDeg = (tailAzimuthDeg + rotors.tailRpm * 6.0 * dt) % 360.0

        val mainBlur = b.blur(rotors.rpm, fps.toDouble())
        val tailBlur = b.blur(rotors.tailRpm, fps.toDouble())
        setOpacity(n.mainBlades, b.bladeOpacity(mainBlur))
        setOpacity(n.mainDisc, b.discOpacity(mainBlur))
        setOpacity(n.tailBlades, b.bladeOpacity(tailBlur))
        setOpacity(n.tailDisc, b.discOpacity(tailBlur))

        HeliRenderer.tilt(tilt, rotors.tiltBack, rotors.tiltRight, scratch)
        instance.getNode(n.mainBlades)?.rotation?.set(tilt)?.mul(scratch.set(Vector3.Z, mainAzimuthDeg.toFloat()))
        instance.getNode(n.mainDisc)?.rotation?.set(tilt)?.mul(scratch.set(Vector3.Z, (mainAzimuthDeg * b.discSpinFactor).toFloat()))
        instance.getNode(n.tailBlades)?.rotation?.set(Vector3.Y, tailAzimuthDeg.toFloat())
        instance.getNode(n.tailDisc)?.rotation?.set(Vector3.Y, (tailAzimuthDeg * b.discSpinFactor).toFloat())
        instance.calculateTransforms()
    }

    /** Current opacity of a node's material, for tests. */
    internal fun opacity(nodeId: String): Float? =
        (instance.getNode(nodeId)?.parts?.firstOrNull()?.material?.get(BlendingAttribute.Type) as? BlendingAttribute)?.opacity

    private fun setOpacity(nodeId: String, opacity: Double) {
        instance.getNode(nodeId)?.parts?.forEach { part ->
            (part.material.get(BlendingAttribute.Type) as? BlendingAttribute)?.opacity = opacity.toFloat()
        }
    }

    override fun render(batch: ModelBatch, environment: Environment) = batch.render(instance, environment)

    override fun dispose() = model.dispose()
}

/** Boxes and translucent discs built in code, for aircraft without an asset (the toy). */
class ProceduralHeliRenderer(private val visual: HelicopterVisual.Procedural) : HeliRenderer {
    private val model: Model = build()
    private val instance = ModelInstance(model)
    private val tilt = Quaternion()
    private val scratch = Quaternion()

    private fun build(): Model {
        val mb = ModelBuilder()
        val lit = (Usage.Position or Usage.Normal).toLong()
        val skid = visual.skidDrop.toFloat()
        val canopy = Material(ColorAttribute.createDiffuse(Color(0.95f, 0.45f, 0.1f, 1f)))
        val dark = Material(ColorAttribute.createDiffuse(Color(0.15f, 0.15f, 0.17f, 1f)))
        mb.begin()
        fun box(id: String, mat: Material, cx: Float, cy: Float, cz: Float, w: Float, h: Float, d: Float) {
            mb.node().id = id
            BoxShapeBuilder.build(mb.part(id, GL20.GL_TRIANGLES, lit, mat), cx, cy, cz, w, h, d)
        }
        box("fuselage", canopy, 0.04f, 0f, 0f, 0.34f, 0.11f, 0.15f)
        box("boom", dark, -0.36f, 0f, -0.03f, 0.48f, 0.022f, 0.022f)
        box("fin", canopy, -0.58f, 0f, 0.0f, 0.07f, 0.004f, 0.11f)
        box("mast", dark, 0f, 0f, -visual.mainHubHeight.toFloat() + 0.05f, 0.014f, 0.014f, 0.1f)
        for (side in listOf(-1f, 1f)) {
            box("skid$side", dark, 0.02f, side * 0.075f, skid - 0.006f, 0.32f, 0.012f, 0.012f)
            box("strut$side", dark, 0.02f, side * 0.06f, skid / 2 + 0.03f, 0.012f, 0.012f, skid - 0.06f)
        }
        val main = 2f * visual.rotorRadius.toFloat()
        mb.node().apply { id = "rotor"; translation.set(0f, 0f, -visual.mainHubHeight.toFloat()) }
        CylinderShapeBuilder.build(mb.part("rotor", GL20.GL_TRIANGLES, lit, translucent()), main, 0.004f, main, 48)
        val tail = 2f * visual.tailRadius.toFloat()
        val th = visual.tailHub
        mb.node().apply { id = "tailRotor"; translation.set(th.x.toFloat(), th.y.toFloat(), th.z.toFloat()) }
        CylinderShapeBuilder.build(mb.part("tailRotor", GL20.GL_TRIANGLES, lit, translucent()), tail, 0.003f, tail, 24)
        return mb.end()
    }

    private fun translucent() = Material(
        ColorAttribute.createDiffuse(Color.LIGHT_GRAY),
        BlendingAttribute(0.3f),
        IntAttribute.createCullFace(GL20.GL_NONE),
    )

    override fun update(state: RigidBodyState, rotors: RotorPose, dt: Float, fps: Float) {
        HeliRenderer.setBodyTransform(instance.transform, state)
        val alpha = (0.04 + 0.28 * (rotors.rpm / 2500.0).coerceIn(0.0, 1.0)).toFloat()
        for (id in listOf("rotor", "tailRotor")) {
            instance.getNode(id)?.parts?.forEach { (it.material.get(BlendingAttribute.Type) as? BlendingAttribute)?.opacity = alpha }
        }
        // Cylinders are built along Y: lay the main disc along body z, then tilt it.
        HeliRenderer.tilt(tilt, rotors.tiltBack, rotors.tiltRight, scratch).mul(scratch.set(Vector3.X, 90f))
        instance.getNode("rotor")?.rotation?.set(tilt)
        instance.calculateTransforms()
    }

    override fun render(batch: ModelBatch, environment: Environment) = batch.render(instance, environment)

    override fun dispose() = model.dispose()
}

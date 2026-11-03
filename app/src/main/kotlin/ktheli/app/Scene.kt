package ktheli.app

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.graphics.VertexAttributes.Usage
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Model
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.CylinderShapeBuilder
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3
import ktheli.physics.model.ToyHeli
import kotlin.math.atan
import kotlin.math.max

/**
 * Converts physics NED world coordinates to libGDX's Y-up world: x = east, y = up, z = south.
 */
fun Vec3.toGdx() = Vector3(y.toFloat(), (-z).toFloat(), (-x).toFloat())

class Scene : Disposable {
    val camera = PerspectiveCamera(60f, 1f, 1f).apply {
        position.set(PILOT_EYE)
        near = 0.1f
        far = 1000f
    }

    private val batch = ModelBatch()
    private val environment = Environment().apply {
        set(ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.55f, 0.6f, 1f))
        add(DirectionalLight().set(0.75f, 0.75f, 0.7f, -0.3f, -1f, -0.5f))
    }
    private val models = mutableListOf<Model>()
    private val ground: ModelInstance
    private val grid: ModelInstance
    private val heli: ModelInstance
    private val shadow: ModelInstance

    init {
        val mb = ModelBuilder()
        val lit = (Usage.Position or Usage.Normal).toLong()

        ground = ModelInstance(
            mb.createBox(1000f, 0.1f, 1000f, Material(ColorAttribute.createDiffuse(Color(0.36f, 0.55f, 0.3f, 1f))), lit)
                .also(models::add),
        ).apply { transform.setToTranslation(0f, -0.06f, 0f) }

        grid = ModelInstance(
            mb.createLineGrid(80, 80, 2.5f, 2.5f, Material(ColorAttribute.createDiffuse(Color(0.27f, 0.42f, 0.23f, 1f))), Usage.Position.toLong())
                .also(models::add),
        )

        heli = ModelInstance(buildHeli(mb, lit).also(models::add))

        shadow = ModelInstance(
            mb.createCylinder(0.8f, 0.001f, 0.8f, 24, translucent(Color.BLACK, 0.35f), Usage.Position.toLong())
                .also(models::add),
        )
    }

    /** Heli geometry built directly in body FRD coordinates (x fwd, y right, z down, origin at CG). */
    private fun buildHeli(mb: ModelBuilder, lit: Long): Model {
        val skid = ToyHeli.SKID_DROP.toFloat()
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
        box("mast", dark, 0f, 0f, -0.12f, 0.014f, 0.014f, 0.1f)
        for (side in listOf(-1f, 1f)) {
            box("skid$side", dark, 0.02f, side * 0.075f, skid - 0.006f, 0.32f, 0.012f, 0.012f)
            box("strut$side", dark, 0.02f, side * 0.06f, skid / 2 + 0.03f, 0.012f, 0.012f, skid - 0.06f)
        }

        // Rotor discs: cylinders are built along Y, so the main disc is turned to lie along body Z.
        mb.node().apply { id = "rotor"; translation.set(0f, 0f, -0.17f); rotation.set(Vector3.X, 90f) }
        CylinderShapeBuilder.build(mb.part("rotor", GL20.GL_TRIANGLES, lit, translucent(Color.LIGHT_GRAY, 0.3f)), 0.72f, 0.004f, 0.72f, 48)
        mb.node().apply { id = "tailRotor"; translation.set(-0.58f, 0.03f, -0.06f) }
        CylinderShapeBuilder.build(mb.part("tailRotor", GL20.GL_TRIANGLES, lit, translucent(Color.LIGHT_GRAY, 0.3f)), 0.15f, 0.003f, 0.15f, 24)
        return mb.end()
    }

    private fun translucent(c: Color, alpha: Float) = Material(
        ColorAttribute.createDiffuse(c),
        BlendingAttribute(alpha),
        IntAttribute.createCullFace(GL20.GL_NONE),
    )

    fun resize(width: Int, height: Int) {
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()
    }

    fun render(state: RigidBodyState) {
        setHeliTransform(heli.transform, state)
        val p = state.position.toGdx()

        val altitude = max(0f, p.y - ToyHeli.SKID_DROP.toFloat())
        val s = 1f / (1f + altitude * 0.15f)
        shadow.transform.setToTranslation(p.x, 0.004f, p.z).scale(s, 1f, s)

        // Pilot's-eye camera: fixed position, tracks the heli, zooms so it keeps a usable apparent size.
        val distance = p.dst(camera.position)
        camera.fieldOfView = Math.toDegrees(2.0 * atan(VISIBLE_HALF_WIDTH / max(distance, 0.5f))).toFloat().coerceIn(12f, 65f)
        camera.up.set(Vector3.Y)
        camera.lookAt(p)
        camera.update()

        batch.begin(camera)
        batch.render(ground, environment)
        batch.render(grid)
        batch.render(shadow)
        batch.render(heli, environment)
        batch.end()
    }

    /** World transform = (NED → gdx) · [R_body→NED | p]. */
    private fun setHeliTransform(m: Matrix4, s: RigidBodyState) {
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

    override fun dispose() {
        batch.dispose()
        models.forEach(Model::dispose)
    }

    private companion object {
        val PILOT_EYE = Vector3(0f, 1.7f, 0f)
        const val VISIBLE_HALF_WIDTH = 3.5f // m of scene visible either side of the heli when zoomed
    }
}

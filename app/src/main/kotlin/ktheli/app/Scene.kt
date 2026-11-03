package ktheli.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.PerspectiveCamera
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
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader
import com.badlogic.gdx.graphics.g3d.model.data.ModelData
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder
import com.badlogic.gdx.graphics.g3d.utils.TextureProvider
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.math.Quaternion
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

class Scene(private val view: ViewSettings) : Disposable {
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
    private val windsockPole: ModelInstance
    private val windsock: ModelInstance
    private val sockDirection = Vector3(0f, -1f, 0f)

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

        heli = ModelInstance(loadHeli().also(models::add))

        windsockPole = ModelInstance(
            mb.createBox(0.05f, WINDSOCK_HEIGHT.toFloat(), 0.05f, Material(ColorAttribute.createDiffuse(Color.LIGHT_GRAY)), lit)
                .also(models::add),
        ).apply { transform.setToTranslation(WINDSOCK.toGdx().add(0f, WINDSOCK_HEIGHT.toFloat() / 2, 0f)) }
        // A cone along +Y with its tip at +Y: the wide end is the sock's mouth at the pole.
        windsock = ModelInstance(
            mb.createCone(0.3f, SOCK_LENGTH, 0.3f, 16, Material(ColorAttribute.createDiffuse(Color(1f, 0.45f, 0.05f, 1f)), IntAttribute.createCullFace(GL20.GL_NONE)), lit)
                .also(models::add),
        )

        shadow = ModelInstance(
            mb.createCylinder(0.8f, 0.001f, 0.8f, 24, translucent(Color.BLACK, 0.35f), Usage.Position.toLong())
                .also(models::add),
        )
    }

    /** Loads the Align T-Rex 450 model (FRD coordinates: x fwd, y right, z down, origin at CG). */
    private fun loadHeli(): Model {
        val data = ObjLoader().loadModelData(Gdx.files.internal("models/trex450/trex450.obj"), ObjLoader.ObjLoaderParameters(true))
        recenterNode(data, "rotor", MAIN_HUB)
        recenterNode(data, "rotorBlades", MAIN_HUB)
        recenterNode(data, "tailRotor", TAIL_HUB)
        recenterNode(data, "tailBlades", TAIL_HUB)

        val textures = TextureProvider.FileTextureProvider(
            Texture.TextureFilter.MipMapLinearLinear,
            Texture.TextureFilter.Linear,
            Texture.TextureWrap.ClampToEdge,
            Texture.TextureWrap.ClampToEdge,
            true,
        )
        return Model(data, textures).apply {
            getMaterial("trex450_body")?.apply {
                set(FloatAttribute.createShininess(48f))
                set(IntAttribute.createCullFace(GL20.GL_NONE))
            }
            getMaterial("rotor_blur_mat")?.apply {
                set(BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0f))
                set(DepthTestAttribute(GL20.GL_LEQUAL, false))
                set(IntAttribute.createCullFace(GL20.GL_NONE))
            }
            // Give the solid 3D blades their own blending material instance so they can soften at high RPM.
            val bladeMat = getMaterial("trex450_body")?.copy()?.apply {
                id = "trex450_blades"
                set(BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 1f))
            }
            if (bladeMat != null) {
                materials.add(bladeMat)
                for (id in listOf("rotorBlades", "tailBlades")) {
                    getNode(id)?.parts?.forEach { it.material = bladeMat }
                }
            }
        }
    }

    private fun recenterNode(data: ModelData, nodeId: String, pivot: Vector3) {
        val node = data.nodes.firstOrNull { it.id == nodeId } ?: return
        val mesh = data.meshes.firstOrNull { it.id == node.meshId } ?: return
        val stride = mesh.attributes.sumOf { it.numComponents }
        var i = 0
        while (i < mesh.vertices.size) {
            mesh.vertices[i] -= pivot.x
            mesh.vertices[i + 1] -= pivot.y
            mesh.vertices[i + 2] -= pivot.z
            i += stride
        }
        node.translation.set(pivot)
    }

    private val sockRotation = Quaternion()

    /** Points the sock downwind; it hangs down in calm air and lifts to horizontal at ~8 m/s. */
    private fun orientWindsock(wind: Vec3) {
        val horizontal = Vec3(wind.x, wind.y, 0.0)
        val speed = horizontal.length()
        val droop = Math.toRadians(85.0) * (1.0 - (speed / 8.0).coerceIn(0.0, 1.0))
        val dir = if (speed > 1e-6) horizontal / speed else Vec3.X
        // NED direction: horizontal downwind component and a downward droop.
        val ned = Vec3(dir.x * kotlin.math.cos(droop), dir.y * kotlin.math.cos(droop), kotlin.math.sin(droop))
        if (speed > 1e-6 || droop > 0.0) sockDirection.set(ned.toGdx()).nor()
        sockRotation.setFromCross(Vector3.Y, sockDirection)
        val top = windsockTop.toGdx()
        windsock.transform.set(top.add(sockDirection.cpy().scl(SOCK_LENGTH / 2)), sockRotation)
    }

    private val tilt = Quaternion()
    private val tiltPart = Quaternion()
    private var mainAzimuthDeg = 32f
    private var tailAzimuthDeg = 20f

    /** Tilts the main rotor tip-path plane (back about body y, right about body x) and spins the hubs. */
    private fun updateRotors(rotor: RotorView, dt: Float) {
        val spin = rotor.spin.coerceIn(0f, 1f)
        if (spin > 0.001f) {
            // Visual rotation rate scales with rotor spin (clockwise about +z seen from above).
            mainAzimuthDeg = (mainAzimuthDeg + spin * 1950f * dt) % 360f
            tailAzimuthDeg = (tailAzimuthDeg + spin * 4300f * dt) % 360f
        }

        val blurAlpha = (spin * 0.92f).coerceIn(0f, 0.92f)
        val bladeAlpha = (1f - spin * 0.68f).coerceIn(0.32f, 1f)
        for (id in listOf("rotor", "tailRotor")) {
            heli.getNode(id)?.parts?.forEach { part ->
                (part.material.get(BlendingAttribute.Type) as? BlendingAttribute)?.opacity = blurAlpha
            }
        }
        for (id in listOf("rotorBlades", "tailBlades")) {
            heli.getNode(id)?.parts?.forEach { part ->
                (part.material.get(BlendingAttribute.Type) as? BlendingAttribute)?.opacity = bladeAlpha
            }
        }

        tilt.set(Vector3.X, Math.toDegrees(rotor.tiltRight).toFloat())
        tilt.mul(tiltPart.set(Vector3.Y, Math.toDegrees(rotor.tiltBack).toFloat()))
        heli.getNode("rotor")?.rotation?.set(tilt)?.mul(tiltPart.set(Vector3.Z, mainAzimuthDeg * 0.35f))
        heli.getNode("rotorBlades")?.rotation?.set(tilt)?.mul(tiltPart.set(Vector3.Z, mainAzimuthDeg))
        heli.getNode("tailBlades")?.rotation?.set(Vector3.Y, tailAzimuthDeg)
        heli.getNode("tailRotor")?.rotation?.set(Vector3.Y, tailAzimuthDeg * 0.35f)
        heli.calculateTransforms()
    }

    private fun translucent(c: Color, alpha: Float) = Material(
        ColorAttribute.createDiffuse(c),
        BlendingAttribute(alpha),
        IntAttribute.createCullFace(GL20.GL_NONE),
    )

    /** Renders into the given back-buffer pixel rectangle. */
    /** How to draw the main rotor: [spin] 0..1 fades it in, tilts (rad) follow the simulated tip-path plane. */
    class RotorView(val spin: Float, val tiltBack: Double = 0.0, val tiltRight: Double = 0.0)

    /** World position of the windsock's top, where the app should sample the wind for [render]. */
    val windsockTop: Vec3 get() = Vec3(WINDSOCK.x, WINDSOCK.y, -WINDSOCK_HEIGHT)

    /** [windAtSock] is the wind (world NED) at [windsockTop]. */
    fun render(state: RigidBodyState, rotor: RotorView, windAtSock: Vec3, x: Int, y: Int, width: Int, height: Int) {
        orientWindsock(windAtSock)
        updateRotors(rotor, Gdx.graphics.deltaTime.coerceIn(0f, 0.05f))
        Gdx.gl.glViewport(x, y, width, height)
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()

        setHeliTransform(heli.transform, state)
        val p = state.position.toGdx()

        val altitude = max(0f, p.y - ToyHeli.SKID_DROP.toFloat())
        val s = 1f / (1f + altitude * 0.15f)
        shadow.transform.setToTranslation(p.x, 0.004f, p.z).scale(s, 1f, s)

        // Pilot's-eye camera: fixed position, tracks the heli, zooms so it keeps a usable apparent size.
        val distance = p.dst(camera.position)
        camera.fieldOfView = if (view.autoZoom) {
            Math.toDegrees(2.0 * atan(VISIBLE_HALF_WIDTH / max(distance, 0.5f))).toFloat().coerceIn(12f, 65f)
        } else {
            FIXED_FOV
        }
        camera.up.set(Vector3.Y)
        camera.lookAt(p)
        camera.update()

        batch.begin(camera)
        batch.render(ground, environment)
        batch.render(grid)
        batch.render(shadow)
        batch.render(windsockPole, environment)
        batch.render(windsock, environment)
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
        val MAIN_HUB = Vector3(0f, 0f, -0.15f)
        val TAIL_HUB = Vector3(-0.47f, 0.028f, -0.03f)
        const val VISIBLE_HALF_WIDTH = 3.5f // m of scene visible either side of the heli when zoomed
        const val FIXED_FOV = 50f
        val WINDSOCK = Vec3(9.0, -5.0, 0.0) // NED: 9 m ahead, 5 m to the left of the pilot
        const val WINDSOCK_HEIGHT = 3.0
        const val SOCK_LENGTH = 0.9f
    }
}

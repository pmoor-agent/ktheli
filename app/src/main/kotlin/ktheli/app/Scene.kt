package ktheli.app

import com.badlogic.gdx.Gdx
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
import com.badlogic.gdx.math.Quaternion
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3
import kotlin.math.max

/**
 * Converts physics NED world coordinates to libGDX's Y-up world: x = east, y = up, z = south.
 */
fun Vec3.toGdx() = Vector3(y.toFloat(), (-z).toFloat(), (-x).toFloat())

/** The flying field (ground, grid, windsock), the camera ([CameraRig]), and the current model's [HeliRenderer]. */
class Scene(private val view: ViewSettings) : Disposable {
    val camera = PerspectiveCamera(60f, 1f, 1f).apply {
        position.set(CameraRig.PILOT_EYE.toGdx())
        near = 0.05f
        far = 1000f
    }
    private val rig = CameraRig()

    private val batch = ModelBatch()
    private val environment = Environment().apply {
        set(ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.55f, 0.6f, 1f))
        add(DirectionalLight().set(0.75f, 0.75f, 0.7f, -0.3f, -1f, -0.5f))
    }
    private val models = mutableListOf<Model>()
    private val ground: ModelInstance
    private val grid: ModelInstance
    private val shadow: ModelInstance
    private val windsockPole: ModelInstance
    private val windsock: ModelInstance
    private val sockDirection = Vector3(0f, -1f, 0f)

    private var model: HelicopterModel? = null
    private var heli: HeliRenderer? = null

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

        windsockPole = ModelInstance(
            mb.createBox(0.05f, WINDSOCK_HEIGHT.toFloat(), 0.05f, Material(ColorAttribute.createDiffuse(Color.LIGHT_GRAY)), lit)
                .also(models::add),
        ).apply { transform.setToTranslation(WINDSOCK.toGdx().add(0f, WINDSOCK_HEIGHT.toFloat() / 2, 0f)) }
        // A cone along +Y with its tip at +Y: the wide end is the sock's mouth at the pole.
        windsock = ModelInstance(
            mb.createCone(0.3f, SOCK_LENGTH, 0.3f, 16, Material(ColorAttribute.createDiffuse(Color(1f, 0.45f, 0.05f, 1f)), IntAttribute.createCullFace(GL20.GL_NONE)), lit)
                .also(models::add),
        )

        // Unit-diameter shadow disc, scaled to each model's rotor.
        shadow = ModelInstance(
            mb.createCylinder(1f, 0.001f, 1f, 24, Material(ColorAttribute.createDiffuse(Color.BLACK), BlendingAttribute(0.35f), IntAttribute.createCullFace(GL20.GL_NONE)), Usage.Position.toLong())
                .also(models::add),
        )
    }

    /** Switches to drawing [next]; loads its assets the first time it's used. */
    fun use(next: HelicopterModel) {
        if (next === model) return
        heli?.dispose()
        heli = HeliRenderer.create(next.visual)
        model = next
    }

    /** World position of the windsock's top, where the app should sample the wind for [render]. */
    val windsockTop: Vec3 get() = Vec3(WINDSOCK.x, WINDSOCK.y, -WINDSOCK_HEIGHT)

    /** [windAtSock] is the wind (world NED) at [windsockTop]. */
    fun render(state: RigidBodyState, rotors: RotorPose, windAtSock: Vec3, x: Int, y: Int, width: Int, height: Int) {
        val visual = model?.visual ?: return
        val dt = Gdx.graphics.deltaTime.coerceIn(0f, 0.1f)
        val fps = if (dt > 0f) 1f / dt else 60f
        orientWindsock(windAtSock)
        heli?.update(state, rotors, dt, fps)

        Gdx.gl.glViewport(x, y, width, height)
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()

        val p = state.position.toGdx()
        val radius = visual.rotorRadius.toFloat()
        val s = 2.2f * radius / (1f + max(0f, p.y) * 0.15f)
        shadow.transform.setToTranslation(p.x, 0.004f, p.z).scale(s, 1f, s)

        val pose = rig.update(state, visual.rotorRadius, view.camera, dt.toDouble())
        camera.position.set(pose.position.toGdx())
        camera.fieldOfView = pose.fovDegrees.toFloat()
        camera.up.set(Vector3.Y)
        camera.lookAt(pose.target.toGdx())
        camera.update()

        batch.begin(camera)
        batch.render(ground, environment)
        batch.render(grid)
        batch.render(shadow)
        batch.render(windsockPole, environment)
        batch.render(windsock, environment)
        heli?.render(batch, environment)
        batch.end()
    }

    private val sockRotation = Quaternion()

    /** Points the sock downwind; it hangs down in calm air and lifts to horizontal at ~8 m/s. */
    private fun orientWindsock(wind: Vec3) {
        val horizontal = Vec3(wind.x, wind.y, 0.0)
        val speed = horizontal.length()
        val droop = Math.toRadians(85.0) * (1.0 - (speed / 8.0).coerceIn(0.0, 1.0))
        val dir = if (speed > 1e-6) horizontal / speed else Vec3.X
        val ned = Vec3(dir.x * kotlin.math.cos(droop), dir.y * kotlin.math.cos(droop), kotlin.math.sin(droop))
        sockDirection.set(ned.toGdx()).nor()
        sockRotation.setFromCross(Vector3.Y, sockDirection)
        val top = windsockTop.toGdx()
        windsock.transform.set(top.add(sockDirection.cpy().scl(SOCK_LENGTH / 2)), sockRotation)
    }

    override fun dispose() {
        heli?.dispose()
        batch.dispose()
        models.forEach(Model::dispose)
    }

    private companion object {
        val WINDSOCK = Vec3(9.0, -5.0, 0.0) // NED: 9 m ahead, 5 m to the left of the pilot
        const val WINDSOCK_HEIGHT = 3.0
        const val SOCK_LENGTH = 0.9f
    }
}

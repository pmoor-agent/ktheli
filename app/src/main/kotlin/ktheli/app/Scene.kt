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
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.Disposable
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3
import kotlin.math.max

/**
 * Converts physics NED world coordinates to libGDX's Y-up world: x = east, y = up, z = south.
 */
fun Vec3.toGdx() = Vector3(y.toFloat(), (-z).toFloat(), (-x).toFloat())

/**
 * The 3D view: the current [FlyingField] ([FieldRenderer]), the current model ([HeliRenderer]) with its
 * shadow, and the camera ([CameraRig]), plus the optional binocular inset.
 */
class Scene(private val view: ViewSettings) : Disposable {
    val camera = PerspectiveCamera(60f, 1f, 1f).apply {
        near = 0.05f
        far = 1000f
    }
    private val rig = CameraRig()
    private val inset = PerspectiveCamera(10f, 1f, 1f)

    private val batch = ModelBatch()
    private val environment = Environment().apply {
        set(ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.55f, 0.6f, 1f))
        add(DirectionalLight().set(0.75f, 0.75f, 0.7f, -0.3f, -1f, -0.5f))
    }

    // Unit-diameter shadow disc, scaled to each model's rotor.
    private val shadowModel: Model = ModelBuilder().createCylinder(
        1f, 0.001f, 1f, 24,
        Material(ColorAttribute.createDiffuse(Color.BLACK), BlendingAttribute(0.35f), IntAttribute.createCullFace(GL20.GL_NONE)),
        Usage.Position.toLong(),
    )
    private val shadow = ModelInstance(shadowModel)

    private var model: HelicopterModel? = null
    private var heli: HeliRenderer? = null
    private var field: FieldRenderer? = null

    /** Switches to drawing [next]; loads its assets the first time it's used. */
    fun use(next: HelicopterModel) {
        if (next === model) return
        heli?.dispose()
        heli = HeliRenderer.create(next.visual)
        model = next
    }

    /** Switches to [next] field; builds its geometry and textures the first time it's used. */
    fun useField(next: FlyingField) {
        if (next === field?.field) return
        field?.dispose()
        field = FieldRenderer(next)
    }

    /** World position of the windsock's top (current field), where the app should sample the wind for [render]. */
    val windsockTop: Vec3 get() = this.field?.windsockTop ?: Vec3.ZERO

    /** A viewport in back-buffer pixels, origin at the bottom left (OpenGL convention). */
    data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Renders the 3D view into [main] and, if given, the binocular inset into [binoculars]. [windAtSock] is the
     * wind (world NED) at [windsockTop]; [frameSeconds] the time since the last frame (camera smoothing, rotor
     * spin, blade blur).
     */
    fun render(
        state: RigidBodyState,
        rotors: RotorPose,
        windAtSock: Vec3,
        main: Viewport,
        binoculars: Viewport? = null,
        frameSeconds: Float = 1f / 60f,
    ) {
        val (x, y, width, height) = main
        val visual = model?.visual ?: return
        val field = field ?: return
        val dt = frameSeconds.coerceIn(0f, 0.1f)
        val fps = if (dt > 0f) 1f / dt else 60f
        field.update(windAtSock)
        heli?.update(state, rotors, dt, fps)

        Gdx.gl.glViewport(x, y, width, height)
        camera.viewportWidth = width.toFloat()
        camera.viewportHeight = height.toFloat()

        val p = state.position.toGdx()
        val radius = visual.rotorRadius.toFloat()
        val s = 2.2f * radius / (1f + max(0f, p.y) * 0.15f)
        shadow.transform.setToTranslation(p.x, FieldRenderer.SHADOW_Y, p.z).scale(s, 1f, s)

        val pose = rig.update(state, visual.rotorRadius, view.camera, dt.toDouble(), field.field.pilotEye)
        camera.position.set(pose.position.toGdx())
        camera.fieldOfView = pose.fovDegrees.toFloat()
        camera.up.set(Vector3.Y)
        camera.lookAt(pose.target.toGdx())
        camera.update()

        drawWorld(camera, field)

        if (binoculars != null) {
            // Same position and target as the main camera, narrower field of view sized to the model.
            val distance = (pose.target - pose.position).length()
            val fov = Binoculars.fov(
                distance, visual.rotorRadius, view.camera.binoculars.framing, pose.fovDegrees, height.toDouble(), binoculars.height.toDouble(),
            )
            view.binocularMagnification = Binoculars.magnification(pose.fovDegrees, height.toDouble(), fov, binoculars.height.toDouble())
            inset.position.set(camera.position)
            inset.fieldOfView = fov.toFloat()
            inset.viewportWidth = binoculars.width.toFloat()
            inset.viewportHeight = binoculars.height.toFloat()
            inset.near = maxOf(0.05f, (distance * 0.5).toFloat())
            inset.far = (distance + 500.0).toFloat()
            inset.up.set(Vector3.Y)
            inset.lookAt(pose.target.toGdx())
            inset.update()

            Gdx.gl.glViewport(binoculars.x, binoculars.y, binoculars.width, binoculars.height)
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            Gdx.gl.glScissor(binoculars.x, binoculars.y, binoculars.width, binoculars.height)
            Gdx.gl.glClearColor(SKY.r, SKY.g, SKY.b, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
            drawWorld(inset, field)
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        } else {
            view.binocularMagnification = 0.0
        }
    }

    private fun drawWorld(cam: PerspectiveCamera, field: FieldRenderer) {
        batch.begin(cam)
        field.render(batch, environment)
        batch.render(shadow)
        heli?.render(batch, environment)
        batch.end()
    }

    override fun dispose() {
        heli?.dispose()
        field?.dispose()
        batch.dispose()
        shadowModel.dispose()
    }

    companion object {
        val SKY = Color(0.55f, 0.72f, 0.92f, 1f)
    }
}

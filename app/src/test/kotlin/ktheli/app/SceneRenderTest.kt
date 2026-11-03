package ktheli.app

import com.badlogic.gdx.Input.Keys
import ktheli.app.harness.HeadlessGl
import ktheli.app.harness.Images
import ktheli.app.harness.Offscreen
import ktheli.app.harness.glTest
import ktheli.physics.body.RigidBodyState
import ktheli.physics.math.Vec3
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.awt.image.BufferedImage
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Renders the real 3D scene headlessly and checks what it shows. */
class SceneRenderTest {
    private val out = Paths.get("build/render-tests")

    @BeforeEach
    fun needGl() = assumeTrue(HeadlessGl.available, "no headless OpenGL")

    /**
     * Renders [Scene] with explicit poses, no simulation. Defaults to the open field: plain grass makes a clean
     * background for telling the model's pixels apart.
     */
    private class Probe(
        camera: CameraSettings,
        val width: Int = 640,
        val height: Int = 360,
        private val field: FlyingField = FlyingFields.OPEN,
    ) : AutoCloseable {
        val view = ViewSettings(camera)
        val scene = Scene(view)
        private val offscreen = Offscreen(width, height)

        fun render(
            state: RigidBodyState,
            rotors: RotorPose,
            binoculars: Scene.Viewport? = null,
            model: HelicopterModel = HelicopterModels.TREX_450,
        ): BufferedImage {
            scene.use(model)
            scene.useField(field)
            offscreen.begin()
            scene.render(state, rotors, Vec3.ZERO, Scene.Viewport(0, 0, width, height), binoculars, 1f / 60f)
            return offscreen.read()
        }

        override fun close() {
            scene.dispose()
            offscreen.close()
        }
    }

    private val parked = HelicopterModels.TREX_450.spec!!.parkedAt(FlyingFields.OPEN.start)
    private fun spinning(tiltRight: Double = 0.0) = RotorPose(rpm = 2700.0, tailRpm = 12150.0, tiltRight = tiltRight, bladeAzimuth = 0.0)

    @Test
    fun `a stopped rotor shows blades, a spinning one the blur disc`() = Probe(
        CameraSettings(mode = CameraMode.FOLLOW, followDistance = 1.3, followHeight = 1.0),
    ).use { p ->
        val stopped = p.render(parked, RotorPose(0.0, 0.0, bladeAzimuth = 0.0))
        val slow = p.render(parked, RotorPose(290.0, 0.0, bladeAzimuth = 0.0)) // tail held still: it blurs 4.5x sooner
        val fast = p.render(parked, spinning())
        Images.write(stopped, out.resolve("rotor-stopped.png"))
        Images.write(fast, out.resolve("rotor-spinning.png"))
        val pixels = p.width * p.height
        val disc = Images.changed(stopped, fast).size
        val creep = Images.changed(stopped, slow).size
        println("rotor: %.1f%% of pixels change when spinning up, %.2f%% at 290 rpm".format(100.0 * disc / pixels, 100.0 * creep / pixels))
        assertTrue(disc > 0.02 * pixels, "the disc covers a large area")
        assertTrue(creep < disc / 20, "below 300 rpm the main rotor still shows sharp blades, no disc")
    }

    /** Main and tail rotor fade independently: the tail turns 4.5x faster and blurs long before the main rotor. */
    @Test
    fun `main and tail rotor blur independently`() {
        val renderer = ObjHeliRenderer(HelicopterModels.TREX_450.visual as HelicopterVisual.Obj)
        try {
            val n = RotorNodes()
            renderer.update(parked, RotorPose(rpm = 250.0, tailRpm = 250.0 * 4.5, bladeAzimuth = 0.0), 1f / 60f, 60f)
            val opacities = listOf(n.mainBlades, n.mainDisc, n.tailBlades, n.tailDisc).associateWith { renderer.opacity(it) }
            println("opacities at 250 rpm main / 1125 rpm tail: $opacities")
            assertEquals(1f, opacities[n.mainBlades]!!, 1e-6f) // main rotor below 300 rpm: sharp blades
            assertEquals(0f, opacities[n.mainDisc]!!, 1e-6f) // ... and no disc
            assertEquals(0.1f, opacities[n.tailBlades]!!, 1e-6f) // tail above 700 rpm: blurred
            assertEquals(0.9f, opacities[n.tailDisc]!!, 1e-6f)
        } finally {
            renderer.dispose()
        }
    }

    @Test
    fun `the disc tilts with the tip-path plane`() = Probe(CameraSettings(mode = CameraMode.FOLLOW, followDistance = 1.5, followHeight = 0.2)).use { p ->
        // From behind: tilted right means the disc's right side drops (lower on screen = larger y).
        val baseline = p.render(parked, RotorPose(0.0, 0.0, bladeAzimuth = 0.0))
        fun discCentroidY(image: BufferedImage, rightHalf: Boolean): Double {
            val pts = Images.changed(baseline, image) { x, _ -> (x > p.width / 2) == rightHalf }
            return pts.map { it.second }.average()
        }
        val right = p.render(parked, spinning(tiltRight = Math.toRadians(10.0)))
        val left = p.render(parked, spinning(tiltRight = Math.toRadians(-10.0)))
        Images.write(right, out.resolve("disc-tilt-right.png"))
        val rightSideDrop = discCentroidY(right, rightHalf = true) - discCentroidY(left, rightHalf = true)
        val leftSideDrop = discCentroidY(right, rightHalf = false) - discCentroidY(left, rightHalf = false)
        println("tilt right vs left: right side %.1f px lower, left side %.1f px lower".format(rightSideDrop, leftSideDrop))
        assertTrue(rightSideDrop > 3.0)
        assertTrue(leftSideDrop < -3.0)
    }

    @Test
    fun `binoculars magnify the model in their inset`() = Probe(CameraSettings(binoculars = BinocularSettings(enabled = true))).use { p ->
        val r = Binoculars.layout(p.width.toFloat(), p.height.toFloat(), 0f, p.view.camera.binoculars)
        val inset = Scene.Viewport(r.x.toInt(), (p.height - r.y - r.height).toInt(), r.width.toInt(), r.height.toInt())
        val image = p.render(parked, RotorPose(0.0, 0.0, bladeAzimuth = 0.0), inset)
        Images.write(image, out.resolve("binoculars.png"))
        val insetRegion = Images.Region(r.x.toInt() + 2, r.y.toInt() + 2, (r.x + r.width).toInt() - 2, (r.y + r.height).toInt() - 2)
        val inInset = Images.objects(image, insetRegion).box!!
        val main = Images.objects(image, Images.Region(p.width / 2 - 50, p.height / 2 - 40, p.width / 2 + 50, p.height / 2 + 40)).box!!
        val ratio = inInset.height.toDouble() / main.height
        println("binoculars: model %d px tall in the inset, %d px in the main view: x%.1f (reported x%.1f)".format(
            inInset.height, main.height, ratio, p.view.binocularMagnification))
        assertTrue(ratio in 0.75 * p.view.binocularMagnification..1.25 * p.view.binocularMagnification, "label matches what you see")
    }

    @Test
    fun `the follow camera keeps the model framed through a flight`() = glTest(640, 360) { ui ->
        ui.core.selectField(FlyingFields.OPEN) // plain grass: only the model counts as an object
        ui.joystick.connected = false
        ui.press(Keys.F3) // HUD off: only the scene counts
        ui.press(Keys.V) // follow camera
        ui.press(Keys.H)
        ui.press(Keys.F)
        ui.seconds(8.0) // spool up
        ui.hold(Keys.W, 0.8) // collective to ~+5°: a steady climb
        ui.seconds(1.5)
        val samples = ArrayList<Images.Blob>()
        val view = Images.Region(0, 30, ui.width, ui.height) // below the menu bar
        repeat(8) { i ->
            ui.hold(Keys.D, 0.3) // pedal turn: the camera has to swing round
            if (i % 2 == 0) ui.hold(Keys.UP, 0.05) // a little forward cyclic now and then
            ui.seconds(0.3)
            samples += Images.objects(ui.image(), view)
            if (i == 7) ui.snapshot(out.resolve("follow-flight.png"))
        }
        val altitude = -ui.core.runner.sim.aircraft.body.position.z
        println("follow: altitude %.1f m, centroids %s".format(altitude, samples.map { "(%.0f,%.0f)".format(it.cx, it.cy) }))
        assertTrue(altitude > 1.0, "flying")
        for (s in samples) {
            assertTrue(s.count > 400, "model visible")
            assertTrue(s.cx in 0.3 * ui.width..0.7 * ui.width && s.cy in 0.25 * ui.height..0.75 * ui.height, "centred: (${s.cx}, ${s.cy})")
            assertTrue(s.box!!.height in (0.08 * ui.height).toInt()..(0.7 * ui.height).toInt(), "close up but whole: ${s.box}")
        }
    }

    @Test
    fun `the home field shows the helipad with its H around the parked model`() = Probe(
        CameraSettings(mode = CameraMode.FOLLOW, followDistance = 2.5, followHeight = 2.5), field = FlyingFields.HOME,
    ).use { p ->
        val image = p.render(parked, RotorPose(0.0, 0.0, bladeAzimuth = 0.0))
        Images.write(image, out.resolve("helipad.png"))
        var asphalt = 0
        var white = 0
        for (y in 0 until p.height) for (x in 0 until p.width) {
            val c = image.getRGB(x, y)
            val r = c shr 16 and 255
            val g = c shr 8 and 255
            val b = c and 255
            val grey = maxOf(r, g, b) - minOf(r, g, b) < 18
            if (grey && r in 35..95) asphalt++
            if (grey && r > 210) white++
        }
        println("helipad: %d asphalt pixels, %d white (ring and H)".format(asphalt, white))
        assertTrue(asphalt > 0.04 * p.width * p.height, "asphalt pad around the model")
        assertTrue(white > 0.01 * p.width * p.height, "white ring and H")
    }

    /**
     * Pad and shadow are both blended, and blended objects are drawn far to near: with the model just beyond the
     * pad's centre, the shadow used to be drawn first and hide the pad, showing a green disc of grass.
     */
    @Test
    fun `the shadow darkens the pad wherever the model hovers over it`() = Probe(CameraSettings(), field = FlyingFields.HOME).use { p ->
        for (north in listOf(0.4, -0.4)) { // beyond and in front of the pad centre, seen from the pilot
            val hovering = RigidBodyState(position = Vec3(north, 0.0, -0.5), attitude = parked.attitude)
            val image = p.render(hovering, RotorPose(0.0, 0.0, bladeAzimuth = 0.0))
            Images.write(image, out.resolve("shadow-on-pad-%+.1f.png".format(north)))
            for (dx in listOf(-0.25, 0.25)) { // inside the shadow, nearer and farther than the model
                val s = p.scene.camera.project(Vec3(north + dx, 0.0, 0.0).toGdx(), 0f, 0f, p.width.toFloat(), p.height.toFloat())
                val c = image.getRGB(s.x.toInt(), p.height - 1 - s.y.toInt())
                val r = c shr 16 and 255
                val g = c shr 8 and 255
                val b = c and 255
                assertTrue(maxOf(r, g, b) - minOf(r, g, b) < 18 && r < 90, "shadowed asphalt at ${north + dx} m north, not (%d, %d, %d)".format(r, g, b))
            }
        }
    }

    @Test
    fun `golden frames`() = glTest(800, 450) { ui ->
        ui.press(Keys.B) // binoculars
        ui.frame(3)
        Images.assertGolden("parked-pilot-binoculars", ui.image())
        ui.press(Keys.V) // follow
        ui.press(Keys.F1) // controls dialog over the scene
        ui.frame(3)
        Images.assertGolden("parked-follow-controls", ui.image())
    }
}

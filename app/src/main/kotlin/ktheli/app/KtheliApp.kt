package ktheli.app

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.ScreenUtils
import ktheli.physics.sim.Controls

class KtheliApp : ApplicationAdapter() {
    private lateinit var scene: Scene
    private lateinit var hudBatch: SpriteBatch
    private lateinit var shapes: ShapeRenderer
    private lateinit var font: BitmapFont
    private val runner = SimRunner()
    private val input = InputController()
    private var showOverlay = true
    private var lastNanos = 0L
    private var controls = Controls.NEUTRAL
    private var screenshotRequested = false

    // Dev hook: KTHELI_SCREENSHOT=file.png [KTHELI_SCREENSHOT_AFTER=seconds] captures one frame, then exits.
    private val autoScreenshot = System.getenv("KTHELI_SCREENSHOT")
    private val autoScreenshotAfter = System.getenv("KTHELI_SCREENSHOT_AFTER")?.toDoubleOrNull() ?: 2.0
    private var elapsed = 0.0

    override fun create() {
        scene = Scene()
        hudBatch = SpriteBatch()
        shapes = ShapeRenderer()
        font = BitmapFont()
        lastNanos = System.nanoTime()
        Gdx.app.log("ktheli", "GL: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + " / " + Gdx.gl.glGetString(GL20.GL_VERSION))
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean = when (keycode) {
                Keys.F1 -> { showOverlay = !showOverlay; true }
                Keys.R, Keys.BACKSPACE -> { runner.reset(); true }
                Keys.F12 -> { screenshotRequested = true; true }
                else -> input.keyDown(keycode)
            }
        }
    }

    override fun resize(width: Int, height: Int) {
        scene.resize(width, height)
        hudBatch.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
        shapes.projectionMatrix.setToOrtho2D(0f, 0f, width.toFloat(), height.toFloat())
    }

    override fun render() {
        val now = System.nanoTime()
        val frameSeconds = (now - lastNanos) / 1e9
        lastNanos = now

        controls = input.update(frameSeconds)
        runner.advance(frameSeconds, controls)

        Gdx.gl.glViewport(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
        Gdx.gl.glClearColor(0.55f, 0.72f, 0.92f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        scene.render(runner.renderState())
        drawHud()

        elapsed += frameSeconds
        if (screenshotRequested) {
            screenshotRequested = false
            input.showStatus("Saved " + saveScreenshot("ktheli-%d.png".format(System.currentTimeMillis())))
        }
        if (autoScreenshot != null && elapsed >= autoScreenshotAfter) {
            saveScreenshot(autoScreenshot)
            Gdx.app.exit()
        }
    }

    private fun saveScreenshot(path: String): String {
        val w = Gdx.graphics.backBufferWidth
        val h = Gdx.graphics.backBufferHeight
        val pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true)
        // Force opaque alpha; the back buffer's alpha channel isn't meaningful.
        for (i in 3 until pixels.size step 4) pixels[i] = 0xFF.toByte()
        val pixmap = Pixmap(w, h, Pixmap.Format.RGBA8888)
        pixmap.pixels.put(pixels).flip()
        val file = Gdx.files.absolute(java.io.File(path).absolutePath)
        PixmapIO.writePNG(file, pixmap)
        pixmap.dispose()
        return file.path()
    }

    private fun drawHud() {
        val h = Gdx.graphics.height.toFloat()
        val body = runner.sim.aircraft.body
        val lines = buildList {
            add("t=%.2fs  tick=%d  fps=%d".format(runner.sim.time, runner.sim.tick, Gdx.graphics.framesPerSecond))
            add("alt=%.2fm  speed=%.1fm/s".format(-body.position.z, body.velocity.length()))
            add(input.active?.let { "Device: ${it.name}" } ?: "No joystick: keyboard (W/S collective, A/D yaw, arrows cyclic, K zero collective)")
            add("R reset   F1 input overlay   F12 screenshot   C calibrate   1-4 assign channel   Tab next device")
            when (val m = input.mode) {
                is InputController.Mode.Calibrating -> add(">> CALIBRATING: move every stick to all extremes, then press C (Esc cancels)")
                is InputController.Mode.Assigning -> add(">> ${m.channel.prompt}  (Esc cancels)")
                InputController.Mode.Fly -> if (input.status.isNotEmpty()) add(input.status)
            }
        }
        hudBatch.begin()
        lines.forEachIndexed { i, s -> font.draw(hudBatch, s, 12f, h - 12f - i * 20f) }
        hudBatch.end()
        if (showOverlay) drawInputOverlay(h - 130f)
    }

    private fun drawInputOverlay(top: Float) {
        val joy = input.active
        val profile = input.activeProfile
        val calibrating = input.mode as? InputController.Mode.Calibrating
        val rows = mutableListOf<Triple<String, Double, Color>>()
        joy?.axes?.forEachIndexed { i, v ->
            val bound = profile?.bindings?.entries?.filter { it.value.axis == i }?.joinToString { it.key.name }.orEmpty()
            val travel = calibrating?.session?.travel(i)?.let { " travel=%.2f".format(it) }.orEmpty()
            rows += Triple("axis $i  %+.3f  $bound$travel".format(v), v.toDouble(), Color.LIGHT_GRAY)
        }
        rows += Triple("collective", controls.collective, Color.ORANGE)
        rows += Triple("roll", controls.roll, Color.ORANGE)
        rows += Triple("pitch", controls.pitch, Color.ORANGE)
        rows += Triple("yaw", controls.yaw, Color.ORANGE)

        val x = 12f
        val barX = 230f
        val barW = 200f
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        rows.forEachIndexed { i, (_, v, color) ->
            val y = top - i * 20f - 12f
            shapes.color = Color(0f, 0f, 0f, 0.35f)
            shapes.rect(barX, y, barW, 12f)
            shapes.color = color
            val c = barX + barW / 2
            val len = (v.coerceIn(-1.0, 1.0) * barW / 2).toFloat()
            shapes.rect(minOf(c, c + len), y, kotlin.math.abs(len), 12f)
        }
        shapes.end()
        hudBatch.begin()
        rows.forEachIndexed { i, (label, _, _) -> font.draw(hudBatch, label, x, top - i * 20f) }
        hudBatch.end()
    }

    override fun dispose() {
        scene.dispose()
        hudBatch.dispose()
        shapes.dispose()
        font.dispose()
    }
}

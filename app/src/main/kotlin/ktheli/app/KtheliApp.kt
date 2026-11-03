package ktheli.app

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import ktheli.input.ProfileStore
import ktheli.physics.sim.Controls
import java.nio.file.Files

class KtheliApp : ApplicationAdapter() {
    private lateinit var scene: Scene
    private lateinit var imgui: ImGuiLayer
    private lateinit var ui: Ui
    private val runner = SimRunner()
    private val input = InputController()
    private val telemetry = Telemetry()
    private var lastNanos = 0L
    private var controls = Controls.NEUTRAL
    private var screenshotRequested = false

    // Dev hook: KTHELI_SCREENSHOT=file.png [KTHELI_SCREENSHOT_AFTER=seconds] captures one frame, then exits.
    private val autoScreenshot = System.getenv("KTHELI_SCREENSHOT")
    private val autoScreenshotAfter = System.getenv("KTHELI_SCREENSHOT_AFTER")?.toDoubleOrNull() ?: 2.0
    private var elapsed = 0.0

    override fun create() {
        Gdx.app.log("ktheli", "GL: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + " / " + Gdx.gl.glGetString(GL20.GL_VERSION))
        scene = Scene()
        val configDir = ProfileStore.configDir().also { Files.createDirectories(it) }
        imgui = ImGuiLayer(configDir.resolve("imgui.ini"))
        ui = Ui(UiContext(runner, input, scene, telemetry, { controls }, { screenshotRequested = true }))
        // Dev hook for screenshots: KTHELI_SHOW=controls[:sticks|buttons|monitor],telemetry
        System.getenv("KTHELI_SHOW")?.split(',')?.forEach {
            when (it.trim()) {
                "controls", "controls:sticks" -> ui.controlsWindow.show(ControlsWindow.Tab.STICKS)
                "controls:buttons" -> ui.controlsWindow.show(ControlsWindow.Tab.BINDINGS)
                "controls:monitor" -> ui.controlsWindow.show(ControlsWindow.Tab.MONITOR)
                "telemetry" -> ui.showTelemetry.set(true)
            }
        }
        lastNanos = System.nanoTime()
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (imgui.wantsKeyboard) return false
                fun down(a: Int, b: Int) = Gdx.input.isKeyPressed(a) || Gdx.input.isKeyPressed(b)
                val action = input.keyDown(
                    keycode,
                    ctrl = down(Keys.CONTROL_LEFT, Keys.CONTROL_RIGHT),
                    shift = down(Keys.SHIFT_LEFT, Keys.SHIFT_RIGHT),
                    alt = down(Keys.ALT_LEFT, Keys.ALT_RIGHT),
                )
                action?.let(ui::perform)
                return true
            }
        }
    }

    override fun render() {
        val now = System.nanoTime()
        val frameSeconds = (now - lastNanos) / 1e9
        lastNanos = now

        val frame = input.update(frameSeconds, keyboardEnabled = !imgui.wantsKeyboard)
        controls = frame.controls
        frame.actions.forEach(ui::perform)
        runner.advance(frameSeconds, controls)
        telemetry.record(runner.sim.time, runner.sim.aircraft.body, controls)

        // Build the UI first so the menu bar height is known, then draw the 3D view below it.
        imgui.beginFrame()
        ui.draw()

        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        val menuPixels = (ui.menuBarHeight * bh / Gdx.graphics.height.coerceAtLeast(1)).toInt()
        Gdx.gl.glViewport(0, 0, bw, bh)
        Gdx.gl.glClearColor(0.55f, 0.72f, 0.92f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        scene.render(runner.renderState(), 0, 0, bw, (bh - menuPixels).coerceAtLeast(1))
        Gdx.gl.glViewport(0, 0, bw, bh)

        imgui.endFrame()

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

    override fun dispose() {
        imgui.dispose()
        scene.dispose()
    }
}

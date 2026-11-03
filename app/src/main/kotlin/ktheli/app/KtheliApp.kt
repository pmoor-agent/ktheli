package ktheli.app

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.utils.ScreenUtils
import ktheli.app.sound.AudioOutput
import ktheli.app.sound.SoundParams
import java.nio.file.Files

/** The windowed application: [AppCore] plus the GL scene and the ImGui renderer. */
class KtheliApp : ApplicationAdapter() {
    private lateinit var scene: Scene
    private lateinit var imgui: ImGuiLayer
    private lateinit var core: AppCore
    private lateinit var gpuTimer: GpuTimer
    private var audio: AudioOutput? = null
    private var minimized = false
    private var lastNanos = 0L
    private var screenshotRequested = false

    // Dev hook: KTHELI_SCREENSHOT=file.png [KTHELI_SCREENSHOT_AFTER=seconds] captures one frame, then exits.
    private val autoScreenshot = System.getenv("KTHELI_SCREENSHOT")
    private val autoScreenshotAfter = System.getenv("KTHELI_SCREENSHOT_AFTER")?.toDoubleOrNull() ?: 2.0
    private var elapsed = 0.0

    private val host = object : AppHost {
        override val fps: Int get() = Gdx.graphics.framesPerSecond
        override fun exit() = Gdx.app.exit()
        override fun requestScreenshot() {
            screenshotRequested = true
        }
    }

    override fun create() {
        Gdx.app.log("ktheli", "GL: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + " / " + Gdx.gl.glGetString(GL20.GL_VERSION))
        core = AppCore(Joysticks, GdxKeyboard, host)
        gpuTimer = GpuTimer()
        scene = Scene()
        audio = AudioOutput.open()
        if (audio == null) core.session.status.show("No sound output available")
        Files.createDirectories(ConfigPaths.dir)
        imgui = ImGuiLayer(ConfigPaths.imguiIni)
        // Dev hook for screenshots: KTHELI_SHOW=controls[:sticks|buttons|monitor],telemetry
        System.getenv("KTHELI_SHOW")?.split(',')?.forEach {
            when (it.trim()) {
                "controls", "controls:sticks" -> core.ui.controlsWindow.show(ControlsWindow.Tab.STICKS)
                "controls:buttons" -> core.ui.controlsWindow.show(ControlsWindow.Tab.BINDINGS)
                "controls:monitor" -> core.ui.controlsWindow.show(ControlsWindow.Tab.MONITOR)
                "telemetry" -> core.ui.showTelemetry.set(true)
            }
        }
        lastNanos = System.nanoTime()
        Gdx.input.inputProcessor = object : InputAdapter() {
            override fun keyDown(keycode: Int): Boolean {
                if (imgui.wantsKeyboard) return false
                core.keyDown(keycode)
                return true
            }
        }
    }

    override fun render() {
        val now = System.nanoTime()
        val frameSeconds = (now - lastNanos) / 1e9
        lastNanos = now

        core.update(frameSeconds, keyboardEnabled = !imgui.wantsKeyboard)

        // Build the UI first so the menu bar height is known, then draw the 3D view below it.
        imgui.beginFrame()
        core.drawUi()

        val bw = Gdx.graphics.backBufferWidth
        val bh = Gdx.graphics.backBufferHeight
        gpuTimer.begin()
        Gdx.gl.glViewport(0, 0, bw, bh)
        Gdx.gl.glClearColor(0.55f, 0.72f, 0.92f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
        core.profiler.measure(FrameProfiler.Section.WORLD) {
            WorldView.render(
                core.session, scene, core.ui.menuBarHeight, bw, bh,
                Gdx.graphics.width.toFloat(), Gdx.graphics.height.toFloat(), frameSeconds.toFloat(),
            )
        }
        Gdx.gl.glViewport(0, 0, bw, bh)

        core.profiler.measure(FrameProfiler.Section.UI_RENDER) { imgui.endFrame() }
        gpuTimer.end()
        core.profiler.gpu(gpuTimer.poll())
        core.profiler.endFrame(frameSeconds)
        audio?.params = if (minimized) SoundParams.SILENT else core.session.soundParams()

        elapsed += frameSeconds
        if (screenshotRequested) {
            screenshotRequested = false
            core.session.status.show("Saved " + saveScreenshot("ktheli-%d.png".format(System.currentTimeMillis())))
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

    // The window was minimized (libGDX stops rendering): fall silent until it's back.
    override fun pause() {
        minimized = true
        audio?.params = SoundParams.SILENT
    }

    override fun resume() {
        minimized = false
    }

    override fun dispose() {
        audio?.close()
        gpuTimer.close()
        imgui.dispose()
        scene.dispose()
    }

}

package ktheli.app.harness

import imgui.ImGui
import imgui.extension.implot.ImPlot
import imgui.gl3.ImGuiImplGl3
import ktheli.app.AppCore
import ktheli.app.Joysticks
import ktheli.app.render.GpuTimer
import ktheli.app.render.Scene
import ktheli.app.render.WorldView
import ktheli.app.session.AppHost
import ktheli.app.session.FrameProfiler
import ktheli.app.session.SettingsStore
import ktheli.app.ui.UiTrace
import ktheli.app.ui.applyKtheliStyle
import ktheli.input.Joystick
import ktheli.input.ProfileStore
import org.lwjgl.opengl.GL11
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

/**
 * A joystick whose axes, buttons and hats a script sets directly. Hats take GLFW bitmasks
 * (`GLFW_HAT_UP` etc.) and appear as four virtual buttons each after the [buttons], like real devices.
 */
class ScriptedJoystick(
    val name: String = "Scripted Pad",
    val guid: String = "scripted-pad",
    axisCount: Int = 6,
    buttonCount: Int = 12,
    hatCount: Int = 1,
) {
    val axes = FloatArray(axisCount)
    val buttons = BooleanArray(buttonCount)
    val hats = IntArray(hatCount)
    var connected = true

    fun snapshot() = Joystick(0, guid, name, axes.copyOf(), Joysticks.withHatButtons(buttons, hats), hats.size)
}

/**
 * Drives the real [AppCore] UI headlessly: no window (and, unless [gl], no GL). Each [frame] advances a fixed 1/60 s, input is
 * injected through ImGui's IO (mouse) or the app's own key path (keyboard), and widgets are found by their
 * [UiTrace] paths. Use [dump] to list what is clickable right now and [snapshot] to render the UI to a PNG.
 *
 * ImGui has global state: use one driver at a time and [close] it.
 */
class UiDriver(
    val width: Int = 1280,
    val height: Int = 800,
    /** Where profiles and settings live; a fresh temp directory (deleted on [close]) unless given. */
    configDir: Path? = null,
    /**
     * Render every frame for real (3D world + ImGui) into an offscreen framebuffer via [HeadlessGl], exactly
     * as the window does. Without it the UI logic runs without any rendering and [snapshot] rasterizes the
     * UI on the CPU over a flat sky.
     */
    val gl: Boolean = false,
) : AutoCloseable {
    val joystick = ScriptedJoystick()
    private val held = HashSet<Int>()
    private val ownsConfigDir = configDir == null
    val configDir: Path = configDir ?: Files.createTempDirectory("ktheli-ui")

    var exitRequested = false
        private set
    var screenshotRequests = 0
        private set
    var frames = 0
        private set

    private val host = object : AppHost {
        override val fps = 60
        override fun exit() {
            exitRequested = true
        }
        override fun requestScreenshot() {
            screenshotRequests++
        }
    }

    val core: AppCore
    private val io get() = ImGui.getIO()
    private var renderer: SoftwareRenderer? = null
    private var offscreen: Offscreen? = null
    private var gl3: ImGuiImplGl3? = null
    private var scene: Scene? = null
    private var gpuTimer: GpuTimer? = null

    init {
        if (gl) HeadlessGl.ensure()
        ImGui.createContext()
        ImPlot.createContext()
        io.setIniFilename(null)
        io.setDisplaySize(width.toFloat(), height.toFloat())
        io.setDisplayFramebufferScale(1f, 1f)
        applyKtheliStyle()
        if (gl) {
            offscreen = Offscreen(width, height)
            gl3 = ImGuiImplGl3().also { it.init() } // creates the font texture itself, like the window
            gpuTimer = GpuTimer()
        } else {
            io.fonts.addFontDefault()
            io.fonts.build()
            renderer = SoftwareRenderer(io.fonts)
        }
        UiTrace.enabled = true
        core = AppCore(
            joysticks = { if (joystick.connected) listOf(joystick.snapshot()) else emptyList() },
            keyboard = { it in held },
            host = host,
            store = ProfileStore(this.configDir.resolve("input.json")),
            settingsStore = SettingsStore(this.configDir.resolve("settings.json")),
        )
        if (gl) scene = Scene()
        frame()
    }

    // --- Time ------------------------------------------------------------------------------------

    fun frame(n: Int = 1) = repeat(n) {
        core.update(DT, keyboardEnabled = !io.wantCaptureKeyboard)
        io.setDeltaTime(DT.toFloat())
        UiTrace.beginFrame()
        gl3?.newFrame() // the GL renderer creates/updates the font texture, as in the window
        ImGui.newFrame()
        core.drawUi()
        ImGui.render()
        UiTrace.endFrame()
        if (gl) renderFrame()
        core.profiler.endFrame(DT)
        frames++
    }

    /** Draws this frame like the window: the world below the menu bar, then the UI on top. */
    private fun renderFrame() {
        val timer = gpuTimer!!
        timer.begin()
        offscreen!!.begin()
        core.profiler.measure(FrameProfiler.Section.WORLD) {
            WorldView.render(core.session, scene!!, core.ui.menuBarHeight, width, height, width.toFloat(), height.toFloat(), DT.toFloat())
        }
        GL11.glViewport(0, 0, width, height)
        core.profiler.measure(FrameProfiler.Section.UI_RENDER) { gl3!!.renderDrawData(ImGui.getDrawData()) }
        timer.end()
        core.profiler.gpu(timer.poll())
    }

    /** The last rendered frame (GL mode only). */
    fun image(): BufferedImage = checkNotNull(offscreen) { "image() needs UiDriver(gl = true)" }.read()

    fun seconds(s: Double) = frame(ceil(s / DT).toInt())

    // --- Finding things --------------------------------------------------------------------------

    fun exists(path: String) = path in UiTrace.items

    fun item(path: String): UiTrace.Item =
        UiTrace.items[path] ?: throw AssertionError("No widget '$path'. Available:\n${dump()}")

    /** Steps frames until [path] appears. */
    fun waitFor(path: String, maxFrames: Int = 30): UiTrace.Item {
        repeat(maxFrames) {
            if (path in UiTrace.items) {
                frame(SETTLE_FRAMES)
                val item = item(path)
                check(path !in UiTrace.ambiguous) { "Widget path '$path' is ambiguous" }
                return item
            }
            frame()
        }
        return item(path)
    }

    fun texts(): List<String> = UiTrace.texts.map { it.text }

    fun hasText(substring: String) = texts().any { substring in it }

    fun waitForText(substring: String, maxFrames: Int = 30) {
        repeat(maxFrames) {
            if (hasText(substring)) return
            frame()
        }
        throw AssertionError("No text containing '$substring'. Visible texts:\n${texts().joinToString("\n")}")
    }

    /** All widget paths of the last frame (optionally only those containing [filter]). */
    fun dump(filter: String = ""): String = UiTrace.items.values
        .filter { filter in it.path }
        .joinToString("\n") { it.path + (if (!it.enabled) "  [disabled]" else "") + (if (!it.visible) "  [hidden]" else "") }

    // --- Mouse -----------------------------------------------------------------------------------

    /** Scrolls [path] into view if it is inside a scrolled-away region. */
    fun reveal(path: String): UiTrace.Item {
        var item = waitFor(path)
        if (item.visible) return item
        UiTrace.reveal = path
        try {
            frame(SETTLE_FRAMES)
        } finally {
            UiTrace.reveal = null
        }
        item = item(path)
        return item
    }

    fun click(path: String) {
        val item = reveal(path)
        check(item.enabled) { "Widget '$path' is disabled" }
        check(item.visible) { "Widget '$path' is not visible" }
        io.addMousePosEvent(item.centerX, item.centerY)
        frame()
        io.addMouseButtonEvent(0, true)
        frame()
        io.addMouseButtonEvent(0, false)
        frame(SETTLE_FRAMES)
    }

    /** Clicks through a menu path, e.g. `menu("Input", "Controls...")`. */
    fun menu(vararg path: String) {
        for (i in path.indices) click(path.take(i + 1).joinToString("/"))
    }

    // --- Keyboard --------------------------------------------------------------------------------

    /** Presses and releases [keycode] while holding [modifiers] (libGDX `Input.Keys` codes). */
    fun press(keycode: Int, vararg modifiers: Int) {
        held += modifiers.toList()
        held += keycode
        core.keyDown(keycode)
        frame()
        held -= keycode
        held -= modifiers.toSet()
        frame()
    }

    fun hold(keycode: Int, seconds: Double) {
        held += keycode
        seconds(seconds)
        held -= keycode
    }

    // --- Joystick --------------------------------------------------------------------------------

    fun pressButton(index: Int) {
        joystick.buttons[index] = true
        frame()
        joystick.buttons[index] = false
        frame()
    }

    /** Pushes hat [hat] in [direction] (GLFW bitmask, e.g. `GLFW_HAT_UP or GLFW_HAT_RIGHT`) and releases it. */
    fun pressHat(direction: Int, hat: Int = 0) {
        joystick.hats[hat] = direction
        frame()
        joystick.hats[hat] = 0
        frame()
    }

    fun holdButton(index: Int, seconds: Double) {
        joystick.buttons[index] = true
        seconds(seconds)
        joystick.buttons[index] = false
        frame()
    }

    // --- Output ----------------------------------------------------------------------------------

    /** Saves the last frame: the real render in GL mode, else the UI rasterized on the CPU (no 3D scene). */
    fun snapshot(file: Path) {
        Files.createDirectories(file.toAbsolutePath().parent)
        if (gl) Images.write(image(), file) else renderer!!.render(ImGui.getDrawData(), width, height, file)
    }

    override fun close() {
        gpuTimer?.close()
        gl3?.shutdown()
        scene?.dispose()
        offscreen?.close()
        UiTrace.enabled = false
        ImPlot.destroyContext()
        ImGui.destroyContext()
        if (ownsConfigDir) configDir.toFile().deleteRecursively()
    }

    companion object {
        const val DT = 1.0 / 60

        /** New windows, tables and closing menus take a couple of frames to lay out. */
        const val SETTLE_FRAMES = 3
    }
}

/**
 * Runs [script] against a fresh [UiDriver]. Always returns Unit, so expression-bodied tests
 * (`fun x() = uiTest { ... }`) stay valid JUnit methods whatever the script's last expression is.
 */
fun uiTest(script: (UiDriver) -> Unit) = UiDriver().use(script)

/** Like [uiTest] with real headless rendering; skipped where no EGL/OpenGL is available. */
fun glTest(width: Int = 960, height: Int = 540, script: (UiDriver) -> Unit) {
    org.junit.jupiter.api.Assumptions.assumeTrue(HeadlessGl.available, "no headless OpenGL")
    UiDriver(width, height, gl = true).use(script)
}

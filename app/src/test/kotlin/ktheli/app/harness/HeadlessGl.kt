package ktheli.app.harness

import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.headless.HeadlessApplication
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files
import com.badlogic.gdx.graphics.GL20
import org.lwjgl.BufferUtils
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL10.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL10.EGL_NO_SURFACE
import org.lwjgl.egl.EGL10.EGL_SURFACE_TYPE
import org.lwjgl.egl.EGL10.eglChooseConfig
import org.lwjgl.egl.EGL10.eglCreateContext
import org.lwjgl.egl.EGL10.eglInitialize
import org.lwjgl.egl.EGL10.eglMakeCurrent
import org.lwjgl.egl.EGL12.EGL_RENDERABLE_TYPE
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL14.EGL_OPENGL_API
import org.lwjgl.egl.EGL14.EGL_OPENGL_BIT
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryStack
import java.awt.image.BufferedImage

/**
 * An OpenGL context without any window system: Mesa's EGL "surfaceless" platform, rendering into offscreen
 * framebuffers. Works on a GPU or, with `LIBGL_ALWAYS_SOFTWARE=1` (the test task's default), in software via
 * llvmpipe, so it runs on machines and CI runners without a display. Also brings up enough of libGDX
 * (headless backend for `Gdx.app`/`Gdx.graphics`, real GL and file access) to run the app's [ktheli.app.Scene].
 *
 * One context per JVM, current on the thread that first calls [ensure]; JUnit runs tests on one thread.
 */
object HeadlessGl {
    /** True if a context could be created; tests needing GL should `assumeTrue(HeadlessGl.available)`. */
    val available: Boolean by lazy { runCatching { init() }.onFailure { println("Headless GL unavailable: $it") }.isSuccess }

    var renderer: String = "none"
        private set

    fun ensure() = check(available) { "No headless OpenGL (EGL surfaceless) on this machine" }

    private fun init() {
        val platformDisplay = EGL.getCapabilities().eglGetPlatformDisplay
        check(platformDisplay != 0L) { "EGL 1.5 eglGetPlatformDisplay missing" }
        // Direct call: the surfaceless platform takes EGL_DEFAULT_DISPLAY (0), which the checked binding rejects.
        val attribs = BufferUtils.createPointerBuffer(1).put(0, EGL_NONE.toLong())
        val display = JNI.invokePPP(EGL_PLATFORM_SURFACELESS_MESA, 0L, org.lwjgl.system.MemoryUtil.memAddress(attribs), platformDisplay)
        check(display != 0L) { "no surfaceless EGL display" }
        MemoryStack.stackPush().use { stack ->
            val major = stack.mallocInt(1)
            val minor = stack.mallocInt(1)
            check(eglInitialize(display, major, minor)) { "eglInitialize failed" }
            EGL.createDisplayCapabilities(display, major[0], minor[0])
            check(eglBindAPI(EGL_OPENGL_API)) { "no desktop OpenGL API" }
            val configs = stack.mallocPointer(1)
            val count = stack.mallocInt(1)
            check(eglChooseConfig(display, stack.ints(EGL_SURFACE_TYPE, 0, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE), configs, count) && count[0] > 0) {
                "no EGL config"
            }
            val context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, stack.ints(EGL_NONE))
            check(context != EGL_NO_CONTEXT) { "eglCreateContext failed" }
            check(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) { "eglMakeCurrent failed" }
        }
        GL.createCapabilities()
        renderer = GL11.glGetString(GL11.GL_RENDERER) ?: "unknown"

        HeadlessApplication(object : ApplicationAdapter() {}, HeadlessApplicationConfiguration().apply { updatesPerSecond = -1 })
        // libGDX's LWJGL3 GL wrapper is package-private; the headless backend installed a mock in its place.
        val gl = Class.forName("com.badlogic.gdx.backends.lwjgl3.Lwjgl3GL20").getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance() as GL20
        Gdx.gl = gl
        Gdx.gl20 = gl
        Gdx.files = Lwjgl3Files()
    }

    private const val EGL_PLATFORM_SURFACELESS_MESA = 0x31DD
}

/** An offscreen colour + depth/stencil framebuffer to render into and read back. */
class Offscreen(val width: Int, val height: Int) : AutoCloseable {
    private val fbo: Int
    private val color: Int
    private val depth: Int

    init {
        HeadlessGl.ensure()
        fbo = GL30.glGenFramebuffers()
        color = GL30.glGenRenderbuffers()
        depth = GL30.glGenRenderbuffers()
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, color)
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height)
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, color)
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth)
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, width, height)
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depth)
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) { "incomplete framebuffer" }
    }

    /** Binds for drawing and clears to the app's sky colour. */
    fun begin() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL11.glViewport(0, 0, width, height)
        GL11.glClearColor(0.55f, 0.72f, 0.92f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
    }

    /** The current contents, top row first. */
    fun read(): BufferedImage {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GL11.glFinish()
        val pixels = BufferUtils.createByteBuffer(width * height * 4)
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = ((height - 1 - y) * width + x) * 4
                image.setRGB(x, y, ((pixels[i].toInt() and 255) shl 16) or ((pixels[i + 1].toInt() and 255) shl 8) or (pixels[i + 2].toInt() and 255))
            }
        }
        return image
    }

    override fun close() {
        GL30.glDeleteFramebuffers(fbo)
        GL30.glDeleteRenderbuffers(color)
        GL30.glDeleteRenderbuffers(depth)
    }
}

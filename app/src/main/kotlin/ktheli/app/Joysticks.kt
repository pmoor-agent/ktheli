package ktheli.app

import org.lwjgl.glfw.GLFW
import kotlin.math.sin

class Joystick(val jid: Int, val guid: String, val name: String, val axes: FloatArray)

/** Raw GLFW joystick access. GLFW requires these calls on the main (render) thread. */
object Joysticks {
    // Dev hook: KTHELI_FAKE_JOYSTICK=1 adds a synthetic device with slowly sweeping axes.
    private val fake = System.getenv("KTHELI_FAKE_JOYSTICK") != null
    private val start = System.nanoTime()

    fun poll(): List<Joystick> {
        val real = (GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST).mapNotNull { jid ->
            if (!GLFW.glfwJoystickPresent(jid)) return@mapNotNull null
            val buf = GLFW.glfwGetJoystickAxes(jid) ?: return@mapNotNull null
            val axes = FloatArray(buf.remaining()).also { buf.get(it) }
            Joystick(jid, GLFW.glfwGetJoystickGUID(jid) ?: "jid-$jid", GLFW.glfwGetJoystickName(jid) ?: "Joystick $jid", axes)
        }
        return if (fake) real + fakeJoystick() else real
    }

    private fun fakeJoystick(): Joystick {
        val t = (System.nanoTime() - start) / 1e9
        val axes = FloatArray(6) { i -> (0.9 * sin(t * (0.4 + 0.15 * i) + i)).toFloat() }
        return Joystick(-1, "fake-joystick", "Fake Joystick (KTHELI_FAKE_JOYSTICK)", axes)
    }
}

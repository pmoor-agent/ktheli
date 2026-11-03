package ktheli.app

import org.lwjgl.glfw.GLFW
import kotlin.math.sin

/** One polled device. GLFW reports D-pad (hat) directions as extra buttons at the end of [buttons]. */
class Joystick(val jid: Int, val guid: String, val name: String, val axes: FloatArray, val buttons: BooleanArray)

/** Raw GLFW joystick access. GLFW requires these calls on the main (render) thread. */
object Joysticks {
    // Dev hook: KTHELI_FAKE_JOYSTICK=1 adds a synthetic device with slowly sweeping axes and blinking buttons.
    private val fake = System.getenv("KTHELI_FAKE_JOYSTICK") != null
    private val start = System.nanoTime()

    fun poll(): List<Joystick> {
        val real = (GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST).mapNotNull { jid ->
            if (!GLFW.glfwJoystickPresent(jid)) return@mapNotNull null
            val axisBuf = GLFW.glfwGetJoystickAxes(jid) ?: return@mapNotNull null
            val axes = FloatArray(axisBuf.remaining()).also { axisBuf.get(it) }
            val buttonBuf = GLFW.glfwGetJoystickButtons(jid)
            val buttons = BooleanArray(buttonBuf?.remaining() ?: 0) { buttonBuf!!.get(it).toInt() == GLFW.GLFW_PRESS }
            Joystick(jid, GLFW.glfwGetJoystickGUID(jid) ?: "jid-$jid", GLFW.glfwGetJoystickName(jid) ?: "Joystick $jid", axes, buttons)
        }
        return if (fake) real + fakeJoystick() else real
    }

    private fun fakeJoystick(): Joystick {
        val t = (System.nanoTime() - start) / 1e9
        val axes = FloatArray(6) { i -> (0.9 * sin(t * (0.4 + 0.15 * i) + i)).toFloat() }
        val buttons = BooleanArray(14) { i -> ((t * 2).toInt() + i) % 5 == 0 }
        return Joystick(-1, "fake-joystick", "Fake Joystick (KTHELI_FAKE_JOYSTICK)", axes, buttons)
    }
}

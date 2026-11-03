package ktheli.app

import org.lwjgl.glfw.GLFW

class Joystick(val jid: Int, val guid: String, val name: String, val axes: FloatArray)

/** Raw GLFW joystick access. GLFW requires these calls on the main (render) thread. */
object Joysticks {
    fun poll(): List<Joystick> = (GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST).mapNotNull { jid ->
        if (!GLFW.glfwJoystickPresent(jid)) return@mapNotNull null
        val buf = GLFW.glfwGetJoystickAxes(jid) ?: return@mapNotNull null
        val axes = FloatArray(buf.remaining()).also { buf.get(it) }
        Joystick(jid, GLFW.glfwGetJoystickGUID(jid) ?: "jid-$jid", GLFW.glfwGetJoystickName(jid) ?: "Joystick $jid", axes)
    }
}

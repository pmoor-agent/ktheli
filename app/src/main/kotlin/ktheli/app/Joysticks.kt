package ktheli.app

import org.lwjgl.glfw.GLFW
import kotlin.math.sin

/**
 * One polled device. [buttons] holds the device's own buttons followed by four virtual buttons per hat
 * (D-pad): up, right, down, left. A diagonal presses two of them.
 */
class Joystick(
    val jid: Int,
    val guid: String,
    val name: String,
    val axes: FloatArray,
    val buttons: BooleanArray,
    val hatCount: Int = 0,
) {
    private val firstHatButton get() = buttons.size - hatCount * HAT_DIRECTIONS.size

    fun buttonLabel(index: Int): String {
        val hatIndex = index - firstHatButton
        if (hatIndex < 0) return "button $index"
        val hat = hatIndex / HAT_DIRECTIONS.size
        val direction = HAT_DIRECTIONS[hatIndex % HAT_DIRECTIONS.size]
        return if (hatCount == 1) "D-pad $direction" else "D-pad ${hat + 1} $direction"
    }

    /** Short label for the monitor's button grid. */
    fun buttonShortLabel(index: Int): String {
        val hatIndex = index - firstHatButton
        return if (hatIndex < 0) "%2d".format(index) else HAT_SHORT[hatIndex % HAT_SHORT.size]
    }

    companion object {
        val HAT_DIRECTIONS = listOf("up", "right", "down", "left")
        private val HAT_SHORT = listOf("up", "rt", "dn", "lt")
        private val HAT_BITS = intArrayOf(GLFW.GLFW_HAT_UP, GLFW.GLFW_HAT_RIGHT, GLFW.GLFW_HAT_DOWN, GLFW.GLFW_HAT_LEFT)

        /** Device buttons plus four virtual buttons per hat, from GLFW hat bitmasks. */
        fun withHatButtons(buttons: BooleanArray, hats: IntArray): BooleanArray =
            buttons + BooleanArray(hats.size * HAT_BITS.size) { i -> hats[i / HAT_BITS.size] and HAT_BITS[i % HAT_BITS.size] != 0 }
    }
}

/**
 * Raw GLFW joystick access. GLFW requires these calls on the main (render) thread.
 *
 * libGDX initializes GLFW with `GLFW_JOYSTICK_HAT_BUTTONS` off, so D-pads are only visible through the hat
 * API; they're read here and appended as virtual buttons.
 */
object Joysticks : JoystickSource {
    // Dev hook: KTHELI_FAKE_JOYSTICK=1 adds a synthetic device with sweeping axes, blinking buttons and a circling D-pad.
    private val fake = System.getenv("KTHELI_FAKE_JOYSTICK") != null
    private val start = System.nanoTime()

    override fun poll(): List<Joystick> {
        val real = (GLFW.GLFW_JOYSTICK_1..GLFW.GLFW_JOYSTICK_LAST).mapNotNull { jid ->
            if (!GLFW.glfwJoystickPresent(jid)) return@mapNotNull null
            val axisBuf = GLFW.glfwGetJoystickAxes(jid) ?: return@mapNotNull null
            val axes = FloatArray(axisBuf.remaining()).also { axisBuf.get(it) }
            val buttonBuf = GLFW.glfwGetJoystickButtons(jid)
            val buttons = BooleanArray(buttonBuf?.remaining() ?: 0) { buttonBuf!!.get(it).toInt() == GLFW.GLFW_PRESS }
            val hatBuf = GLFW.glfwGetJoystickHats(jid)
            val hats = IntArray(hatBuf?.remaining() ?: 0) { hatBuf!!.get(it).toInt() }
            Joystick(
                jid, GLFW.glfwGetJoystickGUID(jid) ?: "jid-$jid", GLFW.glfwGetJoystickName(jid) ?: "Joystick $jid",
                axes, Joystick.withHatButtons(buttons, hats), hats.size,
            )
        }
        return if (fake) real + fakeJoystick() else real
    }

    private fun fakeJoystick(): Joystick {
        val t = (System.nanoTime() - start) / 1e9
        val axes = FloatArray(6) { i -> (0.9 * sin(t * (0.4 + 0.15 * i) + i)).toFloat() }
        val buttons = BooleanArray(10) { i -> ((t * 2).toInt() + i) % 5 == 0 }
        val hat = intArrayOf(GLFW.GLFW_HAT_UP, GLFW.GLFW_HAT_RIGHT, GLFW.GLFW_HAT_DOWN, GLFW.GLFW_HAT_LEFT)[(t.toInt()) % 4]
        return Joystick(-1, "fake-joystick", "Fake Joystick (KTHELI_FAKE_JOYSTICK)", axes, Joystick.withHatButtons(buttons, intArrayOf(hat)), 1)
    }
}

package ktheli.input

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The input workflows on their own, with made-up key codes: no toolkit involved. */
class InputControllerTest {
    private val held = HashSet<Int>()
    private val messages = ArrayList<String>()
    private val keys = KeyLayout(
        defaults = mapOf(Action.PAUSE to KeyChord(P), Action.QUIT to KeyChord(Q, ctrl = true)),
        escape = ESC,
        ctrl = setOf(CTRL),
        shift = emptySet(),
        alt = emptySet(),
    ) { "k$it" }

    private fun controller() = InputController(
        ProfileStore(Files.createTempDirectory("ktheli-input").resolve("input.json")),
        { emptyList() },
        { it in held },
        keys,
        messages::add,
    )

    @Test
    fun `keys map to actions with the modifiers held at the time`() {
        val input = controller()
        assertEquals(Action.PAUSE, input.keyDown(P))
        assertNull(input.keyDown(Q), "Quit needs Ctrl")
        held += CTRL
        assertNull(input.keyDown(CTRL), "a modifier alone does nothing")
        assertEquals(Action.QUIT, input.keyDown(Q))
        assertEquals("Ctrl+k$Q", input.keyLabel(Action.QUIT))
    }

    @Test
    fun `capturing a key binds it, escape cancels`() {
        val input = controller()
        input.startCaptureKey(Action.RESET)
        assertIs<InputController.Mode.CapturingKey>(input.mode)
        assertNull(input.keyDown(P), "the captured press doesn't act")
        assertEquals(KeyChord(P), input.keyFor(Action.RESET))
        assertNull(input.keyFor(Action.PAUSE), "taken from the action that had it")
        assertEquals("Reset -> k$P", messages.last())

        input.startCaptureKey(Action.PAUSE)
        input.keyDown(ESC)
        assertEquals(InputController.Mode.Fly, input.mode)
        assertEquals("Cancelled", messages.last())
    }

    private companion object {
        const val P = 10
        const val Q = 11
        const val ESC = 20
        const val CTRL = 30
    }
}

package ktheli.app

import com.badlogic.gdx.Gdx

/** Source of joystick state, polled once per frame. */
fun interface JoystickSource {
    fun poll(): List<Joystick>
}

/** Which keys are currently held (toolkit key codes). */
fun interface KeyboardState {
    fun isPressed(keycode: Int): Boolean
}

/** Services the UI needs from whatever hosts it (the libGDX window, or a headless test driver). */
interface AppHost {
    val fps: Int
    fun exit()
    fun requestScreenshot()
}

/** View options shared by the UI (which toggles them) and the renderer (which reads them). */
class ViewSettings {
    /** When off, the camera keeps a fixed field of view instead of zooming with distance. */
    var autoZoom = true
}

object GdxKeyboard : KeyboardState {
    override fun isPressed(keycode: Int) = Gdx.input.isKeyPressed(keycode)
}

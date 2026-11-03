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

/** View options shared by the UI (which changes them) and the renderer (which reads them). */
class ViewSettings(var camera: CameraSettings = CameraSettings()) {
    /** Current binocular magnification, written by the renderer for the UI to show (0 = not rendered yet). */
    var binocularMagnification = 0.0
}

object GdxKeyboard : KeyboardState {
    override fun isPressed(keycode: Int) = Gdx.input.isKeyPressed(keycode)
}

package ktheli.app

import com.badlogic.gdx.Gdx
import ktheli.input.KeyboardState

/** Services the UI needs from whatever hosts it (the libGDX window, or a headless test driver). */
interface AppHost {
    val fps: Int
    fun exit()
    fun requestScreenshot()
}

object GdxKeyboard : KeyboardState {
    override fun isPressed(keycode: Int) = Gdx.input.isKeyPressed(keycode)
}

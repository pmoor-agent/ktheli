package ktheli.app

import com.badlogic.gdx.Gdx
import ktheli.input.KeyboardState

/** The keyboard as libGDX sees it. */
object GdxKeyboard : KeyboardState {
    override fun isPressed(keycode: Int) = Gdx.input.isKeyPressed(keycode)
}

package ktheli.app

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration

fun main() {
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle("ktheli")
        setWindowedMode(1600, 900)
        // KTHELI_NO_VSYNC=1 for displays whose compositor throttles vsync'd swaps (e.g. hidden windows).
        val vsync = System.getenv("KTHELI_NO_VSYNC") == null
        useVsync(vsync)
        setForegroundFPS(if (vsync) 0 else 240)
        setBackBufferConfig(8, 8, 8, 8, 24, 0, 4) // 4x MSAA
    }
    Lwjgl3Application(KtheliApp(), config)
}

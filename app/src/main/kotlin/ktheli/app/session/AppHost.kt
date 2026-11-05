package ktheli.app.session

/** Services the UI needs from whatever hosts it (the libGDX window, or a headless test driver). */
interface AppHost {
    val fps: Int
    fun exit()
    fun requestScreenshot()
}

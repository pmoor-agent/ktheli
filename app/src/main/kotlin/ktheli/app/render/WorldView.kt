package ktheli.app.render

import ktheli.app.session.AppSession
import ktheli.app.session.Binoculars

/**
 * Draws the 3D world for the current frame, from the session's camera, below [viewTop] (the menu bar); shared by
 * the window and the headless tests. Reads the [AppSession], writes nothing back.
 */
object WorldView {
    /**
     * @param viewTop where the 3D view starts, in logical pixels from the top of the window
     * @param backWidth back-buffer size in pixels
     * @param logicalWidth window size in logical (ImGui) pixels; differs from the back buffer on HiDPI screens
     */
    fun render(
        session: AppSession,
        scene: Scene,
        viewTop: Float,
        backWidth: Int,
        backHeight: Int,
        logicalWidth: Float,
        logicalHeight: Float,
        frameSeconds: Float,
    ) {
        val runner = session.runner
        val scale = backHeight / logicalHeight.coerceAtLeast(1f)
        val viewHeight = (backHeight - (viewTop * scale).toInt()).coerceAtLeast(1)
        scene.use(runner.model)
        scene.useField(runner.field)
        val sockWind = runner.sim.environment.windAt(runner.field.windsockTop)
        val binocularSettings = session.camera.binoculars
        val binoculars = binocularSettings.takeIf { it.enabled }?.let { b ->
            // Same layout the UI frames, converted from logical (top-left origin) to back-buffer pixels.
            val r = Binoculars.layout(logicalWidth, logicalHeight - viewTop, viewTop, b)
            Scene.Viewport((r.x * scale).toInt(), (backHeight - (r.y + r.height) * scale).toInt(), (r.width * scale).toInt(), (r.height * scale).toInt())
        }
        scene.render(
            runner.renderState(), session.cameraPose, session.aircraft.rotors, sockWind, Scene.Viewport(0, 0, backWidth, viewHeight),
            binoculars, binocularSettings.framing, frameSeconds,
        )
    }
}

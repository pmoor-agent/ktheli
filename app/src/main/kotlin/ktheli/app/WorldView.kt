package ktheli.app

import ktheli.physics.heli.Helicopter

/**
 * Draws the 3D world for the current frame, from the session's camera, below [viewTop] (the menu bar); shared by
 * the window and the headless tests. Reads the [AppSession], writes nothing back.
 */
object WorldView {
    /** The toy has no rotor physics: always shown spinning. */
    private const val TOY_ROTOR_RPM = 2500.0

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
        val crashed = runner.crash != null // frozen at the impact: show the blades standing still
        val rotors = (runner.sim.aircraft as? Helicopter)?.let {
            if (crashed) {
                RotorPose(0.0, 0.0, it.mainRotor.tiltBack, it.mainRotor.tiltRight, it.mainRotor.bladeAzimuth)
            } else {
                RotorPose(it.rotorRpm, it.tailRotorRpm, it.mainRotor.tiltBack, it.mainRotor.tiltRight, it.mainRotor.bladeAzimuth)
            }
        } ?: RotorPose(rpm = if (crashed) 0.0 else TOY_ROTOR_RPM, tailRpm = if (crashed) 0.0 else TOY_ROTOR_RPM * 4.5)
        val sockWind = runner.sim.environment.windAt(runner.field.windsockTop)
        val binocularSettings = session.camera.binoculars
        val binoculars = binocularSettings.takeIf { it.enabled }?.let { b ->
            // Same layout the UI frames, converted from logical (top-left origin) to back-buffer pixels.
            val r = Binoculars.layout(logicalWidth, logicalHeight - viewTop, viewTop, b)
            Scene.Viewport((r.x * scale).toInt(), (backHeight - (r.y + r.height) * scale).toInt(), (r.width * scale).toInt(), (r.height * scale).toInt())
        }
        scene.render(
            runner.renderState(), session.cameraPose, rotors, sockWind, Scene.Viewport(0, 0, backWidth, viewHeight),
            binoculars, binocularSettings.framing, frameSeconds,
        )
    }
}

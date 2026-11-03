package ktheli.app

import ktheli.app.sound.Listener
import ktheli.physics.heli.Helicopter

/** Draws the 3D world for the current frame below the menu bar; shared by the window and the headless tests. */
object WorldView {
    /** The toy has no rotor physics: always shown spinning. */
    private const val TOY_ROTOR_RPM = 2500.0

    /**
     * @param backWidth back-buffer size in pixels
     * @param logicalWidth window size in logical (ImGui) pixels; differs from the back buffer on HiDPI screens
     */
    fun render(
        core: AppCore,
        scene: Scene,
        backWidth: Int,
        backHeight: Int,
        logicalWidth: Float,
        logicalHeight: Float,
        frameSeconds: Float,
    ) = core.profiler.measure(FrameProfiler.Section.WORLD) {
        val menu = core.ui.menuBarHeight
        val scale = backHeight / logicalHeight.coerceAtLeast(1f)
        val viewHeight = (backHeight - (menu * scale).toInt()).coerceAtLeast(1)
        scene.use(core.runner.model)
        scene.useField(core.runner.field)
        val crashed = core.runner.crash != null // frozen at the impact: show the blades standing still
        val rotors = (core.runner.sim.aircraft as? Helicopter)?.let {
            if (crashed) {
                RotorPose(0.0, 0.0, it.mainRotor.tiltBack, it.mainRotor.tiltRight, it.mainRotor.bladeAzimuth)
            } else {
                RotorPose(it.rotorRpm, it.tailRotorRpm, it.mainRotor.tiltBack, it.mainRotor.tiltRight, it.mainRotor.bladeAzimuth)
            }
        } ?: RotorPose(rpm = if (crashed) 0.0 else TOY_ROTOR_RPM, tailRpm = if (crashed) 0.0 else TOY_ROTOR_RPM * 4.5)
        val sockWind = core.runner.sim.environment.windAt(scene.windsockTop)
        val binoculars = core.view.camera.binoculars.takeIf { it.enabled }?.let { b ->
            // Same layout the UI frames, converted from logical (top-left origin) to back-buffer pixels.
            val r = Binoculars.layout(logicalWidth, logicalHeight - menu, menu, b)
            Scene.Viewport((r.x * scale).toInt(), (backHeight - (r.y + r.height) * scale).toInt(), (r.width * scale).toInt(), (r.height * scale).toInt())
        }
        scene.render(core.runner.renderState(), rotors, sockWind, Scene.Viewport(0, 0, backWidth, viewHeight), binoculars, frameSeconds)
        scene.pose?.let { core.listener = Listener.facing(it.position, it.target, core.listener.right) }
    }
}

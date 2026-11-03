package ktheli.app

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics
import com.badlogic.gdx.utils.Disposable
import imgui.ImGui
import imgui.extension.implot.ImPlot
import imgui.gl3.ImGuiImplGl3
import imgui.glfw.ImGuiImplGlfw
import java.nio.file.Path

/**
 * Dear ImGui (plus ImPlot) on top of libGDX's GLFW window. The GLFW backend chains to the callbacks
 * libGDX already installed, so libGDX input keeps working; check [wantsKeyboard]/[wantsMouse] to avoid
 * acting on input that ImGui consumed.
 */
class ImGuiLayer(iniFile: Path) : Disposable {
    private val glfw = ImGuiImplGlfw()
    private val gl3 = ImGuiImplGl3()

    init {
        ImGui.createContext()
        ImPlot.createContext()
        ImGui.getIO().setIniFilename(iniFile.toString()) // window positions persist across runs
        ImGui.styleColorsClassic()
        ImGui.getStyle().apply {
            windowRounding = 0f
            frameRounding = 0f // the built-in pixel font stays crisp only at its native size
        }
        glfw.init((Gdx.graphics as Lwjgl3Graphics).window.windowHandle, true)
        gl3.init()
    }

    val wantsKeyboard: Boolean get() = ImGui.getIO().wantCaptureKeyboard
    val wantsMouse: Boolean get() = ImGui.getIO().wantCaptureMouse

    fun beginFrame() {
        gl3.newFrame()
        glfw.newFrame()
        ImGui.newFrame()
    }

    fun endFrame() {
        ImGui.render()
        gl3.renderDrawData(ImGui.getDrawData())
    }

    override fun dispose() {
        gl3.shutdown()
        glfw.shutdown()
        ImPlot.destroyContext()
        ImGui.destroyContext()
    }
}

package ktheli.app

import imgui.ImGui
import imgui.type.ImBoolean

/**
 * The ImGui calls the app uses, wrapped so [UiTrace] can record them. A widget's trace name is its visible
 * label (the part before `##`, or the part after it for hidden labels) unless given explicitly.
 * Containers (windows, menus, tabs, combos, popups, [pushId]) add a path segment for their contents.
 */
object Gui {
    fun traceName(label: String): String = label.substringBefore("##").ifEmpty { label.substringAfter("##") }

    // --- Containers ------------------------------------------------------------------------------

    /** Always pair with [end], whatever this returns. */
    fun begin(title: String, open: ImBoolean? = null, flags: Int = 0): Boolean {
        UiTrace.push(traceName(title))
        return if (open != null) ImGui.begin(title, open, flags) else ImGui.begin(title, flags)
    }

    fun end() {
        ImGui.end()
        UiTrace.pop()
    }

    fun beginMenu(label: String): Boolean {
        val open = ImGui.beginMenu(label)
        UiTrace.item(traceName(label))
        if (open) UiTrace.push(traceName(label))
        return open
    }

    /** Only when [beginMenu] returned true. */
    fun endMenu() {
        UiTrace.pop()
        ImGui.endMenu()
    }

    fun beginTabItem(label: String, flags: Int = 0): Boolean {
        val open = ImGui.beginTabItem(label, flags)
        UiTrace.item(traceName(label))
        if (open) UiTrace.push(traceName(label))
        return open
    }

    /** Only when [beginTabItem] returned true. */
    fun endTabItem() {
        UiTrace.pop()
        ImGui.endTabItem()
    }

    fun beginCombo(label: String, preview: String): Boolean {
        val open = ImGui.beginCombo(label, preview)
        UiTrace.item(traceName(label))
        if (open) UiTrace.push(traceName(label))
        return open
    }

    /** Only when [beginCombo] returned true. */
    fun endCombo() {
        UiTrace.pop()
        ImGui.endCombo()
    }

    fun beginPopupModal(name: String, flags: Int = 0): Boolean {
        val open = ImGui.beginPopupModal(name, flags)
        if (open) UiTrace.push(traceName(name))
        return open
    }

    /** Only when [beginPopupModal] returned true. */
    fun endPopup() {
        UiTrace.pop()
        ImGui.endPopup()
    }

    /** ImGui ID scope plus a trace path segment, e.g. one table row per channel. */
    fun pushId(name: String) {
        ImGui.pushID(name)
        UiTrace.push(name)
    }

    fun popId() {
        UiTrace.pop()
        ImGui.popID()
    }

    fun beginDisabled(disabled: Boolean) {
        ImGui.beginDisabled(disabled)
        UiTrace.pushDisabled(disabled)
    }

    fun endDisabled() {
        UiTrace.popDisabled()
        ImGui.endDisabled()
    }

    // --- Widgets ---------------------------------------------------------------------------------

    fun menuItem(label: String, shortcut: String = "", selected: Boolean = false, name: String = traceName(label)): Boolean =
        ImGui.menuItem(label, shortcut, selected).also { UiTrace.item(name) }

    fun menuItem(label: String, shortcut: String, selected: ImBoolean, name: String = traceName(label)): Boolean =
        ImGui.menuItem(label, shortcut, selected).also { UiTrace.item(name) }

    fun button(label: String, width: Float = 0f, height: Float = 0f, name: String = traceName(label)): Boolean =
        ImGui.button(label, width, height).also { UiTrace.item(name) }

    fun smallButton(label: String, name: String = traceName(label)): Boolean =
        ImGui.smallButton(label).also { UiTrace.item(name) }

    fun checkbox(label: String, value: Boolean, name: String = traceName(label)): Boolean =
        ImGui.checkbox(label, value).also { UiTrace.item(name) }

    fun selectable(label: String, selected: Boolean, name: String = traceName(label)): Boolean =
        ImGui.selectable(label, selected).also { UiTrace.item(name) }

    // --- Text ------------------------------------------------------------------------------------

    fun text(s: String) {
        ImGui.text(s)
        UiTrace.text(s)
    }

    fun textDisabled(s: String) {
        ImGui.textDisabled(s)
        UiTrace.text(s)
    }

    fun textWrapped(s: String) {
        ImGui.textWrapped(s)
        UiTrace.text(s)
    }

    fun textColored(r: Float, g: Float, b: Float, a: Float, s: String) {
        ImGui.textColored(r, g, b, a, s)
        UiTrace.text(s)
    }
}

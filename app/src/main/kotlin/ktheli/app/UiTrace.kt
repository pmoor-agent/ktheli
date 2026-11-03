package ktheli.app

import imgui.ImGui

/**
 * Optional per-frame record of where interactive widgets were drawn and which texts were shown, keyed by
 * readable paths like `Controls/Buttons & keys/Pause / resume/key`. Scripted UI tests use it to find and
 * click widgets; when [enabled] is false (the default) recording is skipped entirely.
 */
object UiTrace {
    var enabled = false

    data class Item(
        val path: String,
        val minX: Float, val minY: Float, val maxX: Float, val maxY: Float,
        val enabled: Boolean,
        val visible: Boolean,
    ) {
        val centerX: Float get() = (minX + maxX) / 2
        val centerY: Float get() = (minY + maxY) / 2
    }

    data class Text(val path: String, val text: String)

    /** Widgets of the last completed frame, in drawing order. */
    var items: Map<String, Item> = emptyMap()
        private set

    /** Paths that occurred more than once in the last completed frame. */
    var ambiguous: Set<String> = emptySet()
        private set

    /** Texts of the last completed frame, in drawing order. */
    var texts: List<Text> = emptyList()
        private set

    private val scope = ArrayList<String>()
    private val disabled = ArrayList<Boolean>()
    private var building = LinkedHashMap<String, Item>()
    private val duplicates = HashSet<String>()
    private var buildingTexts = ArrayList<Text>()

    fun beginFrame() {
        building = LinkedHashMap()
        buildingTexts = ArrayList()
        duplicates.clear()
        scope.clear()
        disabled.clear()
    }

    fun endFrame() {
        items = building
        texts = buildingTexts
        ambiguous = duplicates.toSet()
    }

    fun push(name: String) {
        if (enabled) scope += name
    }

    fun pop() {
        if (enabled && scope.isNotEmpty()) scope.removeAt(scope.lastIndex)
    }

    fun pushDisabled(value: Boolean) {
        if (enabled) disabled += value
    }

    fun popDisabled() {
        if (enabled && disabled.isNotEmpty()) disabled.removeAt(disabled.lastIndex)
    }

    /** A path to scroll into view (scripted tests): its enclosing scroll region scrolls when it is next drawn. */
    var reveal: String? = null

    /** Records the item ImGui submitted last under [name]. */
    fun item(name: String) {
        if (!enabled) return
        val path = path(name)
        val min = ImGui.getItemRectMin()
        val max = ImGui.getItemRectMax()
        if (path in building) duplicates += path
        val visible = ImGui.isItemVisible()
        building[path] = Item(path, min.x, min.y, max.x, max.y, disabled.none { it }, visible)
        if (!visible && path == reveal) ImGui.setScrollHereY(0.5f)
    }

    fun text(text: String) {
        if (enabled) buildingTexts += Text(scope.joinToString("/"), text)
    }

    private fun path(name: String) = if (scope.isEmpty()) name else scope.joinToString("/") + "/" + name
}

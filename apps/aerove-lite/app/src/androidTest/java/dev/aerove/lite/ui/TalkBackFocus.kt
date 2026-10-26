package dev.aerove.lite.ui

import java.util.Locale

internal data class ReaderNode(
    val id: Int,
    val parent: Int?,
    val children: List<Int>,
    val childCount: Int,
    val visible: Boolean,
    val screenReaderFocusable: Boolean,
    val actionable: Boolean,
    val pressable: Boolean,
    val scrolls: Boolean,
    val listRole: Boolean,
    val text: Boolean,
    val state: Boolean,
    val windowSized: Boolean,
    val description: String = "",
    val words: String = "",
    val stateDescription: String = "",
    val role: String = "",
    val enabled: Boolean = true,
)

/** Where TalkBack stops, after AccessibilityNodeInfoUtils.shouldFocusNode in google/talkback 229212fd. */
internal class TalkBackFocus(private val nodes: Map<Int, ReaderNode>) {
    private val speaking = HashMap<Int, Boolean>()

    private fun node(id: Int) = nodes.getValue(id)

    fun reachable(id: Int) = node(id).let { it.visible && (it.screenReaderFocusable || it.actionable) }

    private fun topLevelScrollItem(id: Int) = node(id).visible && node(id).parent?.let(nodes::get)?.let { it.scrolls || it.listRole } == true

    private fun focusable(id: Int) = reachable(id) || (topLevelScrollItem(id) && speaks(id))

    private fun coversWindow(id: Int) = node(id).windowSized && node(id).childCount > 0 && !reachable(id)

    fun speaks(id: Int): Boolean = speaking[id] ?: run {
        val node = node(id)
        val children = node.children.map(::node)
        val spoken = node.text || node.state || children.any { child ->
            child.visible && !reachable(child.id) && !(topLevelScrollItem(child.id) && speaks(child.id) && !node.pressable) &&
                speaks(child.id)
        } || (!node.scrolls && children.any { !it.visible && it.text && !it.screenReaderFocusable && !it.actionable })
        spoken.also { speaking[id] = it }
    }

    private fun ancestors(id: Int) = generateSequence(node(id).parent) { nodes[it]?.parent }.filter(nodes::containsKey)

    fun stops(id: Int): Boolean {
        val node = nodes[id] ?: return false
        if (!node.visible || coversWindow(id)) return false
        if (focusable(id)) return node.childCount == 0 || speaks(id)
        return ancestors(id).none { node(it).visible && !coversWindow(it) && focusable(it) } && (node.text || node.state)
    }

    /** What TalkBack says at a stop: TreeNodesDescription and RoleDescriptionExtractor with the default state-name-role order. */
    fun speech(id: Int): String = listOf(tree(id), if (node(id).enabled) "" else DISABLED).joined()

    private fun tree(id: Int): String {
        val node = node(id)
        val own = listOf(node.stateDescription, node.description.ifEmpty { node.words }, node.role)
            .filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }.joined()
        val children = if (node.description.isNotEmpty()) emptyList()
            else node.children.map(::node).filter { it.visible && !focusable(it.id) }.map { tree(it.id) }
        return (listOf(own) + children).joined()
    }

    private fun List<String>.joined() = filter(String::isNotEmpty).joinToString(SEPARATOR)

    companion object {
        const val SEPARATOR = ", "
        const val DISABLED = "‹disabled›"
        const val BUTTON = "‹button›"
    }
}

package dev.aerove.lite.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TalkBackFocusTest {
    private fun node(id: Int, parent: Int? = null, children: List<Int> = emptyList(), reader: Boolean = false,
        actionable: Boolean = false, text: Boolean = false, visible: Boolean = true, state: Boolean = false,
        scrolls: Boolean = false, windowSized: Boolean = false, pressable: Boolean = actionable, listRole: Boolean = false,
        description: String = "", words: String = "", stateDescription: String = "", role: String = "", enabled: Boolean = true) =
        ReaderNode(id, parent, children, children.size, visible, reader, actionable, pressable, scrolls = scrolls,
            listRole = listRole, text = text || description.isNotEmpty() || words.isNotEmpty(),
            state = state || stateDescription.isNotEmpty(), windowSized = windowSized, description = description,
            words = words, stateDescription = stateDescription, role = role, enabled = enabled)

    private fun stops(vararg nodes: ReaderNode): List<Int> {
        val focus = TalkBackFocus(nodes.associateBy { it.id })
        return nodes.map { it.id }.filter(focus::stops)
    }

    private fun speech(vararg nodes: ReaderNode) = TalkBackFocus(nodes.associateBy { it.id }).speech(nodes.first().id)

    @Test
    fun silentFocusableContainerIsSkippedForItsLabeledChild() = assertEquals(listOf(2), stops(
        node(1, children = listOf(2), reader = true, actionable = true),
        node(2, parent = 1, reader = true, actionable = true, text = true)))

    @Test
    fun unlabeledFocusableLeafRemainsAStop() = assertEquals(listOf(1), stops(node(1, reader = true, actionable = true)))

    @Test
    fun textStopsOnlyWithoutAFocusableAncestor() {
        assertEquals(listOf(2), stops(node(1, children = listOf(2)), node(2, parent = 1, text = true)))
        assertEquals(listOf(1), stops(node(1, children = listOf(2), reader = true, text = true), node(2, parent = 1, text = true)))
    }

    @Test
    fun labeledFocusableContainerWithSilentChildIsAStop() = assertEquals(listOf(1), stops(
        node(1, children = listOf(2), reader = true, actionable = true, text = true), node(2, parent = 1)))

    @Test
    fun windowSizedContainerIsSkippedForItsText() = assertEquals(listOf(2), stops(
        node(1, children = listOf(2), text = true, windowSized = true), node(2, parent = 1, text = true)))

    @Test
    fun speakingTopLevelScrollItemIsAStop() = assertEquals(listOf(2), stops(node(1, children = listOf(2), scrolls = true),
        node(2, parent = 1, children = listOf(3)), node(3, parent = 2, text = true)))

    @Test
    fun stateDescriptionAloneIsAStop() = assertEquals(listOf(1), stops(node(1, state = true)))

    @Test
    fun hiddenTextLetsItsFocusableContainerSpeak() = assertEquals(listOf(1), stops(
        node(1, children = listOf(2), reader = true, actionable = true), node(2, parent = 1, text = true, visible = false)))

    @Test
    fun hiddenNodeNeverStops() = assertEquals(emptyList<Int>(), stops(node(1, reader = true, actionable = true, visible = false)))

    @Test
    fun pressableScrollContainerSpeaksForItsItemWhileAPlainOneDoesNot() {
        assertEquals(listOf(1, 2), stops(node(1, children = listOf(2), reader = true, actionable = true, scrolls = true),
            node(2, parent = 1, text = true)))
        assertEquals(listOf(2), stops(node(1, children = listOf(2), reader = true, scrolls = true), node(2, parent = 1, text = true)))
    }

    @Test
    fun hiddenFocusableTextDoesNotSpeakForItsContainer() = assertEquals(emptyList<Int>(), stops(
        node(1, children = listOf(2), reader = true, actionable = true), node(2, parent = 1, reader = true, text = true, visible = false)))

    @Test
    fun scrollingContainerDoesNotSpeakThroughHiddenText() = assertEquals(emptyList<Int>(), stops(
        node(1, children = listOf(2), reader = true, actionable = true, scrolls = true), node(2, parent = 1, text = true, visible = false)))

    @Test
    fun windowSizedLeafTextStillStops() = assertEquals(listOf(1), stops(node(1, text = true, windowSized = true)))

    @Test
    fun focusableContainerWithOnlyAStateIsAStop() = assertEquals(listOf(1), stops(
        node(1, children = listOf(2), reader = true, actionable = true, state = true), node(2, parent = 1)))

    @Test
    fun windowSizedScrollItemDoesNotHideItsText() = assertEquals(listOf(2), stops(node(0, children = listOf(1), scrolls = true),
        node(1, parent = 0, children = listOf(2), windowSized = true), node(2, parent = 1, text = true)))

    @Test
    fun describedNodeWithoutChildrenIsReadOnce() =
        assertEquals("Total 4 KB", speech(node(1, reader = true, actionable = true, description = "Total 4 KB")))

    @Test
    fun movedDescriptionAndTheTextChildAreBothRead() = assertEquals("Total 4 KB, 4 KB", speech(
        node(1, children = listOf(2, 3), reader = true, actionable = true),
        node(2, parent = 1, description = "Total 4 KB"), node(3, parent = 1, words = "4 KB")))

    @Test
    fun ownDescriptionSilencesTheChildren() = assertEquals("Retry", speech(
        node(1, children = listOf(2), reader = true, description = "Retry"), node(2, parent = 1, words = "SG")))

    @Test
    fun focusableChildIsLeftForItsOwnStop() = assertEquals("Status", speech(
        node(1, children = listOf(2), reader = true, words = "Status"), node(2, parent = 1, actionable = true, words = "Running")))

    @Test
    fun hiddenChildIsNotRead() = assertEquals("Status", speech(
        node(1, children = listOf(2), reader = true, words = "Status"), node(2, parent = 1, words = "Running", visible = false)))

    @Test
    fun stateComesBeforeNameAndRoleWithoutRepeats() {
        assertEquals("SG, Retry, ${TalkBackFocus.BUTTON}", speech(
            node(1, reader = true, stateDescription = "SG", description = "Retry", role = TalkBackFocus.BUTTON)))
        assertEquals("Retry", speech(node(1, reader = true, description = "Retry", role = "retry")))
    }

    @Test
    fun disabledIsTheLastWord() = assertEquals("Retry, ${TalkBackFocus.BUTTON}, ${TalkBackFocus.DISABLED}", speech(
        node(1, reader = true, description = "Retry", role = TalkBackFocus.BUTTON, enabled = false)))

    @Test
    fun movedRoleFollowsTheOtherChildrenAndAnOwnStateLeads() {
        assertEquals("Stop, ${TalkBackFocus.BUTTON}", speech(node(1, children = listOf(2, 3, 4), reader = true, actionable = true),
            node(2, parent = 1, description = "Stop"), node(3, parent = 1), node(4, parent = 1, role = TalkBackFocus.BUTTON)))
        assertEquals("SG, Retry, ${TalkBackFocus.BUTTON}", speech(node(1, children = listOf(2, 3), reader = true, actionable = true,
            stateDescription = "SG"), node(2, parent = 1, description = "Retry"), node(3, parent = 1, role = TalkBackFocus.BUTTON)))
    }

    @Test
    fun descriptionReplacesTheOwnText() {
        assertEquals("Upload\n1 KB/s", speech(node(1, reader = true, description = "Upload\n1 KB/s", words = "Upload")))
        assertEquals("Upload", speech(node(1, reader = true, words = "Upload")))
    }
}

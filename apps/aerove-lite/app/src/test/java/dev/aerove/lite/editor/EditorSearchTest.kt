package dev.aerove.lite.editor

import dev.aerove.lite.data.MAX_PROFILE_BYTES
import org.junit.Assert.*
import org.junit.Test

class EditorSearchTest {
    @Test fun literalReplacementDoesNotInterpretDollarOrBackslash() {
        assertEquals("$1\\\n$1\\", EditorSearch.replaceAll("foo\nFOO", EditorQuery("foo"), "$1\\"))
        assertEquals("x FOO", EditorSearch.replaceAll("foo FOO", EditorQuery("foo", matchCase = true), "x"))
    }

    @Test fun regexGroupsAndMultilineAnchorsMatchTheFullFindBehavior() {
        assertEquals("DIRECT:80\nDIRECT:90", EditorSearch.replaceAll("node-80\nnode-90", EditorQuery("^node-(\\d+)$", regex = true), "DIRECT:$1"))
    }

    @Test fun invalidRegexAndOversizedReplacementAreRecoverable() {
        val original = "a\na"
        assertThrows(java.util.regex.PatternSyntaxException::class.java) {
            EditorSearch.replaceAll(original, EditorQuery("[", regex = true), "x")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditorSearch.replaceAll(original, EditorQuery("a"), "中".repeat(MAX_PROFILE_BYTES / 3))
        }
        assertEquals("a\na", original)
    }
}

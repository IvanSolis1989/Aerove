package dev.aerove.lite.editor

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorCompletionTest {
    private fun completion(): EditorCompletion = EditorCompletion.fromJson(File(
        "src/main/java/dev/aerove/lite/editor/assets/editor/full-schema.json",
    ).readText())

    @Test fun fullSchemaCompletesRootAndNestedDnsKeys() {
        assertTrue(completion().suggestions("mixed-p", 0, 7, EditorLanguage.Yaml).any { it.label == "mixed-port" })
        val text = "dns:\n  nameser"
        assertTrue(completion().suggestions(text, 1, 9, EditorLanguage.Yaml).any { it.label == "nameserver" })
    }

    @Test fun groupTypeVariantAndDynamicNamesAreAvailable() {
        val text = "proxies:\n  - name: my-node\nproxy-groups:\n  - name: group\n    type: sel"
        assertTrue(completion().suggestions(text, 4, 13, EditorLanguage.Yaml).any { it.label == "select" })
        val outbound = "proxies:\n  - name: my-node\nproxy-groups:\n  - name: group\n    type: select\n    proxies:\n      - my"
        assertTrue(completion().suggestions(outbound, 6, 10, EditorLanguage.Yaml).any { it.label == "my-node" })
    }

    @Test fun commentsHaveNoCompletionAndCrLfOffsetsRemainExact() {
        assertTrue(completion().suggestions("# mixed-p", 0, 9, EditorLanguage.Yaml).isEmpty())
        val text = "mode: rule\r\ndns:\r\n  nameser"
        val suggestion = completion().suggestions(text, 2, 9, EditorLanguage.Yaml).first { it.label == "nameserver" }
        assertEquals("nameser", text.substring(suggestion.start, suggestion.end))
    }
}

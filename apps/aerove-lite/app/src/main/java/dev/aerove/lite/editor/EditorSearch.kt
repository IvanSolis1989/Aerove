package dev.aerove.lite.editor

import dev.aerove.lite.data.MAX_PROFILE_BYTES
import dev.aerove.lite.data.checkEditorSize
import java.util.regex.Pattern

data class EditorQuery(val text: String = "", val matchCase: Boolean = false, val regex: Boolean = false)

internal object EditorSearch {
    fun replaceAll(source: String, query: EditorQuery, replacement: String): String {
        require(query.text.isNotEmpty() && query.text.length <= 1024) { "Invalid search length" }
        require(replacement.length <= MAX_PROFILE_BYTES) { "Replacement is too large" }
        val flags = Pattern.MULTILINE or if (query.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val matcher = Pattern.compile(if (query.regex) query.text else Pattern.quote(query.text), flags).matcher(source)
        val result = StringBuffer()
        val inserted = if (query.regex) replacement else java.util.regex.Matcher.quoteReplacement(replacement)
        while (matcher.find()) {
            check(!Thread.currentThread().isInterrupted) { "Replacement cancelled" }
            matcher.appendReplacement(result, inserted)
            require(result.length <= MAX_PROFILE_BYTES) { "Replacement exceeds the configuration limit" }
        }
        matcher.appendTail(result)
        return result.toString().also(::checkEditorSize)
    }
}

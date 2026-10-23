package dev.aerove.lite.regexp2

import org.json.JSONObject

/** Bound IPC and native output allocations without changing global filter-part appearance order. */
object FilterChunks {
    fun names(values: List<String>): List<IntRange> {
        if (values.isEmpty()) return listOf(IntRange.EMPTY)
        val chunks = mutableListOf<IntRange>()
        var start = 0
        var bytes = 0
        values.forEachIndexed { index, name ->
            val size = FilterProtocol.utf8(JSONObject.quote(name)).size + 1
            require(size <= 3 * 1024 * 1024) { "A filter candidate exceeds the batch transport budget" }
            if (index > start && (bytes + size > 3 * 1024 * 1024 || index - start >= 100000)) {
                chunks += start until index; start = index; bytes = 0
            }
            bytes += size
        }
        chunks += start until values.size
        return chunks
    }
}

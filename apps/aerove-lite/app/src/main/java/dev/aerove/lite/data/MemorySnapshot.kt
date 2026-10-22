package dev.aerove.lite.data

import java.io.IOException

data class MemorySnapshot(
    val processRssBytes: ULong?,
    val configuredLimitBytes: ULong?,
)

internal fun decodeMemorySnapshot(source: String): MemorySnapshot {
    val input = ProbeJson(source)
    val fields = input.fields()
    input.end()
    if (fields.keys != setOf("inuse", "oslimit")) throw IOException("Core returned an invalid memory schema")
    return MemorySnapshot(fields.unsigned("inuse").takeIf { it != 0uL }, fields.unsigned("oslimit").takeIf { it != 0uL })
}

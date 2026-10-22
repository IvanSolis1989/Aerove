package dev.aerove.lite.connections

import dev.aerove.lite.ConnectionInfo
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException

/** Decodes the fixed core's TrackerInfo/Session.as_map without inventing absent metadata. */
internal fun decodeConnectionSnapshot(value: JSONObject): List<ConnectionInfo> {
    val rows = value.getJSONArray("connections")
    val observedAtNanos = System.nanoTime()
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val metadata = if (row.isNull("metadata")) JSONObject() else row.getJSONObject("metadata")
        val id = row.text("id")?.takeIf(String::isNotBlank) ?: throw IOException("Connection has no id")
        val host = metadata.text("host")?.takeIf(String::isNotBlank)
        val destinationIP = metadata.text("destinationIP")?.takeIf(String::isNotBlank)
        val port = metadata.integer("destinationPort", 65535)?.toInt()
        val start = row.text("start")?.let {
            try { Instant.parse(it) } catch (error: DateTimeParseException) {
                throw IOException("Invalid connection start time", error)
            }
        }
        val chains = if (row.isNull("chains")) emptyList() else row.getJSONArray("chains").let { array ->
            (0 until array.length()).map { i -> array.get(i) as? String ?: throw IOException("Invalid connection chain") }
        }
        ConnectionInfo(id, connectionEndpoint(host ?: destinationIP, port).orEmpty(),
            metadata.text("network").orEmpty(), row.text("rule").orEmpty(), chains,
            row.integer("upload"), row.integer("download"),
            sourceIP = metadata.text("sourceIP"), sourcePort = metadata.integer("sourcePort", 65535)?.toInt(),
            destinationIP = destinationIP, destinationPort = port, host = host, startedAt = start,
            inboundType = metadata.text("type"), process = metadata.text("process"),
            processPath = metadata.text("processPath"), uid = metadata.integer("uid"),
            rulePayload = row.text("rulePayload"), country = metadata.text("country"),
            asn = metadata.text("asn"), inboundUser = metadata.text("inboundUser"), observedAtNanos = observedAtNanos)
    }
}

internal fun connectionEndpoint(address: String?, port: Int?): String? {
    if (address.isNullOrBlank()) return port?.let { ":$it" }
    if (port == null) return address
    return if (':' in address && !address.startsWith('[')) "[$address]:$port" else "$address:$port"
}

private fun JSONObject.text(key: String): String? {
    if (isNull(key)) return null
    return get(key) as? String ?: throw IOException("Invalid connection $key")
}

private fun JSONObject.integer(key: String, maximum: Long = Long.MAX_VALUE): Long? {
    if (isNull(key)) return null
    val raw = get(key)
    val value = when (raw) {
        is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
        is String -> raw.toLongOrNull()
        else -> null
    } ?: throw IOException("Invalid connection $key")
    if (value !in 0..maximum) throw IOException("Invalid connection $key")
    return value
}

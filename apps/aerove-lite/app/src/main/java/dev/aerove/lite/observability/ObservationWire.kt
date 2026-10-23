package dev.aerove.lite.observability

import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

data class ObservationWindow(val sessionId: String, val generation: ULong, val cursor: ULong,
    val dropped: ULong, val events: List<ObservationEvent>)

sealed interface ObservationEvent {
    val cursor: ULong
    data class Request(override val cursor: ULong, val value: RequestHistoryEntry) : ObservationEvent
    data class Traffic(override val cursor: ULong, val id: String, val upload: ULong, val download: ULong) : ObservationEvent
    data class Closed(override val cursor: ULong, val id: String, val end: Instant,
        val upload: ULong, val download: ULong) : ObservationEvent
    data class Dns(override val cursor: ULong, val value: DnsHistoryEntry) : ObservationEvent
}

fun decodeObservationWindow(source: String): ObservationWindow = try {
    require(source.toByteArray(Charsets.UTF_8).size <= 65536)
    val root = JSONObject(source)
    require(root.opt("type") == "observations")
    val session = root.text("sessionId").also { require(UUID.fromString(it).toString() == it) }
    val generation = root.u64("generation").also { require(it > 0uL) }
    val cursor = root.u64("cursor")
    val dropped = root.u64("dropped")
    val values = root.getJSONArray("events")
    require(values.length() <= 32)
    var previous = 0uL
    val events = (0 until values.length()).map { index ->
        val event = values.getJSONObject(index)
        val number = event.u64("cursor").also { require(it > previous && it <= cursor); previous = it }
        val data = event.getJSONObject("data")
        when (event.text("kind")) {
            "request" -> {
                val meta = data.getJSONObject("metadata")
                ObservationEvent.Request(number, RequestHistoryEntry(
                    id = data.uuid("id"), time = Instant.parse(data.text("start")), network = meta.text("network"),
                    host = meta.optionalText("host"), sourceIP = meta.optionalText("sourceIP"), sourcePort = meta.port("sourcePort"),
                    destinationIP = meta.optionalText("destinationIP"), destinationPort = meta.port("destinationPort"),
                    inbound = meta.optionalText("type"), process = meta.optionalText("process"), processPath = meta.optionalText("processPath"),
                    uid = meta.optionalScalar("uid"), chains = data.strings("chains"), rule = data.text("rule"),
                    rulePayload = data.text("rulePayload"), upload = data.u64("upload"), download = data.u64("download")))
            }
            "requestTraffic" -> ObservationEvent.Traffic(number, data.uuid("id"), data.u64("upload"), data.u64("download"))
            "requestClosed" -> ObservationEvent.Closed(number, data.uuid("id"), Instant.parse(data.text("end")), data.u64("upload"), data.u64("download"))
            "dns" -> ObservationEvent.Dns(number, DnsHistoryEntry(id = "$generation:$number", time = Instant.parse(data.text("time")),
                domain = data.text("domain"), type = data.text("type"), initiator = data.text("initiator").also {
                    require(it in setOf("App", "Rule", "Direct", "Proxy", "Other"))
                }, upstream = data.text("upstream"), cached = data.get("cached").let { require(it is Boolean); it },
                answers = data.strings("answers"), rcode = data.text("rcode"), error = data.text("error"), delayMillis = data.number("delay")))
            else -> error("Unknown observation")
        }
    }
    ObservationWindow(session, generation, cursor, dropped, events)
} catch (_: Exception) { throw IOException("Core returned an invalid observation window") }

private fun JSONObject.text(key: String): String = get(key).let { require(it is String); it }
private fun JSONObject.uuid(key: String): String = text(key).also { require(UUID.fromString(it).toString() == it) }
private fun JSONObject.optionalText(key: String): String = if (!has(key) || isNull(key)) "" else text(key)
private fun JSONObject.optionalScalar(key: String): String = if (!has(key) || isNull(key)) "" else get(key).let {
    require(it is String || it is Int || it is Long); it.toString()
}
private fun JSONObject.u64(key: String): ULong = text(key).let { value ->
    value.toULongOrNull()?.also { require(it.toString() == value) } ?: error("Invalid unsigned value")
}
private fun JSONObject.number(key: String): ULong = get(key).let {
    require(it is Int || it is Long); (it as Number).toLong().also { number -> require(number >= 0) }.toULong()
}
private fun JSONObject.port(key: String): Int? = if (!has(key) || isNull(key)) null else get(key).let {
    val number = when (it) { is Int -> it; is Long -> it.toInt().also { result -> require(result.toLong() == it) }; is String -> it.toInt(); else -> error("Invalid port") }
    require(number in 0..65535); number
}
private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { array ->
    (0 until array.length()).map { index -> array.get(index).let { require(it is String); it } }
}

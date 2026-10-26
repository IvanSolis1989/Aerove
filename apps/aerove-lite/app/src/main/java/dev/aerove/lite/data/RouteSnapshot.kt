package dev.aerove.lite.data

import java.io.IOException

data class RouteSnapshot(
    val coreEpoch: ULong,
    val routeVersion: ULong,
    val picks: Map<String, String>,
)

internal fun decodeRouteSnapshot(source: String): RouteSnapshot {
    val input = ProbeJson(source)
    val fields = input.fields()
    input.end()
    if (fields.keys != setOf("core-epoch", "route-version", "picks")) {
        throw IOException("Core returned an invalid route schema")
    }
    val rawPicks = fields["picks"] as? Map<*, *>
        ?: throw IOException("Core returned invalid route picks")
    val picks = rawPicks.entries.associate { (key, value) ->
        val group = key as? String
        val selected = value as? String
        if (group.isNullOrEmpty() || selected.isNullOrEmpty() ||
            group.any(Char::isISOControl) || selected.any(Char::isISOControl)
        ) throw IOException("Core returned an invalid route pick")
        group to selected
    }
    // Native RouteSnapshot serializes u64 identities as decimal strings; never coerce via Double/Long.
    fun counter(key: String): ULong {
        val text = fields[key] as? String
        return text?.toULongOrNull()?.takeIf { it.toString() == text }
            ?: throw IOException("Core returned an invalid $key counter")
    }
    val epoch = counter("core-epoch")
    if (epoch == 0uL) throw IOException("Core returned an invalid route epoch")
    return RouteSnapshot(epoch, counter("route-version"), picks)
}

data class RouteHit(val host: String, val proxy: String = "", val exit: String)
data class RouteCheck(val coreEpoch: ULong, val routeVersion: ULong, val held: List<Boolean>)

internal fun decodeRouteHits(value: Any?): List<RouteHit> = (value as? List<*>)?.map { raw ->
    val row = raw as? Map<*, *> ?: throw IOException("Core returned an invalid route hit")
    if (row.keys != setOf("host", "exit") && row.keys != setOf("host", "proxy", "exit")) throw IOException("Core returned an invalid route hit schema")
    fun field(name: String, optional: Boolean = false): String = (row[name] as? String ?: if (optional && name !in row) "" else
        throw IOException("Core returned an invalid route hit $name")).also {
            if (it.length > 16384 || it.any(Char::isISOControl)) throw IOException("Core returned an invalid route hit $name")
        }
    RouteHit(field("host").also { if (it.isEmpty()) throw IOException("Core returned an empty route hit host") }, field("proxy", true), field("exit"))
} ?: throw IOException("Core returned missing route hits")

internal fun decodeRouteCheck(source: String, count: Int): RouteCheck {
    val input = ProbeJson(source)
    val fields = input.fields(); input.end()
    if (fields.keys != setOf("core-epoch", "route-version", "held")) throw IOException("Core returned an invalid route check schema")
    fun counter(key: String): ULong {
        val text = fields[key] as? String
        return text?.toULongOrNull()?.takeIf { it.toString() == text } ?: throw IOException("Core returned invalid $key")
    }
    val held = (fields["held"] as? List<*>)?.map { it as? Boolean ?: throw IOException("Core returned invalid route verdict") }
        ?: throw IOException("Core returned missing route verdicts")
    if (held.size != count) throw IOException("Core returned mismatched route verdicts")
    return RouteCheck(counter("core-epoch").also { if (it == 0uL) throw IOException("Core returned invalid epoch") }, counter("route-version"), held)
}

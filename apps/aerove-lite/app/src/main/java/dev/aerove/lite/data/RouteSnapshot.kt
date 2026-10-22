package dev.aerove.lite.data

import java.io.IOException

data class RouteSnapshot(
    val coreEpoch: ULong,
    val picksVersion: ULong,
    val picks: Map<String, String>,
)

internal fun decodeRouteSnapshot(source: String): RouteSnapshot {
    val input = ProbeJson(source)
    val fields = input.fields()
    input.end()
    if (fields.keys != setOf("core-epoch", "picks-version", "picks")) {
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
    val epoch = fields.unsigned("core-epoch")
    if (epoch == 0uL) throw IOException("Core returned an invalid route epoch")
    return RouteSnapshot(epoch, fields.unsigned("picks-version"), picks)
}

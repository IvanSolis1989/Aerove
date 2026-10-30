package dev.aerove.lite.flutter

import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.SubscriptionDownloader
import java.util.Collections
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.json.JSONTokener

class MeowNativeDelayHistory(private val client: CoreApiClient, private val owner: String,
    private val generation: Long, private val coreEpoch: ULong) {
    init {
        require(owner.isNotBlank() && owner.length <= 16384 && owner.none(Char::isISOControl)) { "Invalid runtime owner" }
        require(generation > 0 && coreEpoch > 0uL) { "Invalid runtime history identity" }
    }

    suspend fun read(): MeowDelayHistorySnapshot {
        val source = client.protocolDelayHistory(coreEpoch)
        currentCoroutineContext().ensureActive()
        try {
            val json = JSONTokener(source)
            val row = json.nextValue() as? JSONObject ?: invalid("history", "Expected a native history object")
            if (json.nextClean() != '\u0000') invalid("history", "Trailing native history data")
            exact(row, setOf("complete", "core-epoch", "route-version", "history-revision", "delays"), "history")
            if (row.get("complete") != true) invalid("complete", "Native history is incomplete")
            val actualEpoch = counter(row, "core-epoch", positive = true)
            val routeVersion = counter(row, "route-version", positive = false)
            val historyRevision = counter(row, "history-revision", positive = false)
            if (actualEpoch != coreEpoch) throw MeowQueryException("stale_session", "Delay history belongs to another core epoch",
                mapOf("expected" to coreEpoch.toString(), "actual" to actualEpoch.toString()))
            val rows = row.getJSONArray("delays")
            val keys = HashSet<Pair<String, String>>()
            val delays = List(rows.length()) { index ->
                val item = rows.getJSONObject(index)
                exact(item, setOf("name", "url", "value"), "delays[$index]")
                val name = text(item, "name", index)
                val url = text(item, "url", index)
                try { SubscriptionDownloader.parseUrl(url) } catch (failure: IllegalArgumentException) {
                    invalid("delays[$index].url", "Invalid history URL", failure)
                }
                val raw = item.get("value")
                if (raw !is Int && raw !is Long) invalid("delays[$index].value", "Expected an integer")
                val value = (raw as Number).toLong()
                if (value != -2L && value !in 1..65535) invalid("delays[$index].value", "Invalid native delay value")
                if (!keys.add(name to url)) invalid("delays[$index]", "Duplicate proxy/URL history key")
                Collections.unmodifiableMap(linkedMapOf<String, Any?>("name" to name, "url" to url, "value" to value.toInt()))
            }
            currentCoroutineContext().ensureActive()
            return MeowDelayHistorySnapshot(owner, generation, actualEpoch, routeVersion, historyRevision,
                Collections.unmodifiableList(delays))
        } catch (failure: MeowQueryException) { throw failure }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            invalid("history", "Invalid native delay history response", failure)
        }
    }

    private fun exact(row: JSONObject, keys: Set<String>, field: String) {
        if (row.keys().asSequence().toSet() != keys) invalid(field, "Unexpected history fields")
    }

    private fun counter(row: JSONObject, key: String, positive: Boolean): ULong {
        val text = row.get(key) as? String ?: invalid(key, "Expected a canonical unsigned string")
        return text.toULongOrNull()?.takeIf { it.toString() == text && (!positive || it > 0uL) }
            ?: invalid(key, "Invalid native history counter")
    }

    private fun text(row: JSONObject, key: String, index: Int): String {
        val value = row.get(key) as? String ?: invalid("delays[$index].$key", "Expected a string")
        if (value.isBlank() || value.length > 16384 || value.any(Char::isISOControl)) invalid("delays[$index].$key", "Invalid history text")
        return value
    }

    private fun invalid(field: String, message: String, cause: Throwable? = null): Nothing =
        throw MeowQueryException("invalid_core_response", message, mapOf("field" to field), cause)
}

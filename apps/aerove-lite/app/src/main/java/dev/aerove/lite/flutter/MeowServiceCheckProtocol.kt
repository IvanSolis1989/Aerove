package dev.aerove.lite.flutter

import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.ServiceCheckException
import dev.aerove.lite.data.ServiceCheckRequestException
import dev.aerove.lite.data.ServiceTarget

internal class MeowServiceCheckProtocol(private val client: CoreApiClient, private val coreEpoch: ULong,
    private val publishRoute: (ULong, ULong) -> Unit) {
    suspend fun invoke(arguments: Any?): List<Map<String, Any?>> {
        val args = protocolMap(arguments, "arguments")
        protocolKeys(args, setOf("names", "proxy-name", "timeout"))
        val names = protocolList(args["names"], "names").map { protocolText(it, "names") }
        val proxy = protocolText(args["proxy-name"], "proxy-name")
        if (proxy.toByteArray(Charsets.UTF_8).size > 4096 || proxy.any(Char::isISOControl))
            badQueryArgument("proxy-name", "Invalid proxy name")
        val timeout = protocolInteger(args["timeout"], "timeout")
        if (timeout > 60_000) badQueryArgument("timeout", "Expected at most 60000 milliseconds; non-positive uses 10000")
        val budget = if (timeout <= 0) 10_000 else timeout.toInt()
        val wanted = if (names.isEmpty()) ServiceTarget.entries.toList() else names.mapNotNull(ServiceTarget::byId)
        if (wanted.isEmpty()) return emptyList()

        val results = try { client.serviceChecks(wanted, proxy, budget) }
        catch (failure: ServiceCheckRequestException) {
            badQueryArgument(failure.field, failure.message ?: "Invalid service request")
        } catch (failure: ServiceCheckException) {
            throw MeowQueryException(failure.reason?.code ?: "core_http_error", failure.message ?: "Service check failed",
                mapOf("status" to failure.statusCode), failure)
        }
        results.forEach { checkEpoch(it.coreEpoch) }
        val version = results.first().routeVersion
        val current = client.routeSnapshot()
        checkEpoch(current.coreEpoch)
        if (current.routeVersion != version) throw MeowQueryException("route_superseded",
            "Service check receipt no longer describes the current route",
            mapOf("check-route" to version.toString(), "current-route" to current.routeVersion.toString()))
        publishRoute(current.coreEpoch, current.routeVersion)
        return results.map { item ->
            protocolMap(protocolCopy(mapOf("name" to item.target.id, "status" to item.status.id,
                "region" to item.region.orEmpty(), "delay" to item.delayMillis, "chains" to item.chains,
                "checked-at" to item.checkedAtMillis, "core-epoch" to protocolNumber(item.coreEpoch),
                "route-version" to protocolNumber(item.routeVersion), "hits" to item.hits.map {
                    mapOf("host" to it.host, "proxy" to it.proxy, "exit" to it.exit)
                })), "service check")
        }
    }

    private fun checkEpoch(actual: ULong) {
        if (actual != coreEpoch) throw MeowQueryException("stale_session", "Service check core epoch changed",
            mapOf("expected" to coreEpoch.toString(), "actual" to actual.toString()))
    }
}

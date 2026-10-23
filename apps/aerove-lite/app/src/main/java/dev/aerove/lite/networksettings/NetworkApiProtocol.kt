package dev.aerove.lite.networksettings

import java.io.IOException
import org.json.JSONObject

internal fun networkApplyBody(requestId: String, epoch: ULong, plan: NetworkRuntimePlan): JSONObject =
    JSONObject().put("requestId", requestId).put("expectedCoreEpoch", epoch.toString())
        .put("network", JSONObject(plan.userConfigFields()))

internal fun decodeNetworkReceipt(source: String, requestId: String, expectedEpoch: ULong): ULong =
    networkWire("Core returned an invalid network receipt") {
        val receipt = JSONObject(source)
        require(receipt.networkKeys() == setOf("requestId", "coreEpoch"))
        require(receipt.opt("requestId") == requestId)
        canonicalEpoch(receipt.opt("coreEpoch")).also { require(it > expectedEpoch) }
    }

internal fun verifyNetworkReadback(source: String, epoch: ULong, plan: NetworkRuntimePlan) {
    networkWire("Core network readback did not match the applied listeners") {
        val root = JSONObject(source)
        require(root.networkKeys() == setOf("coreEpoch", "network", "activePorts"))
        require(canonicalEpoch(root.opt("coreEpoch")) >= epoch)
        val actual = root.getJSONObject("network")
        require(actual.networkKeys() == setOf("mixed-port", "port", "socks-port", "allow-lan", "bind-address", "authEnabled"))
        val expected = plan.userConfigFields()
        for (key in listOf("mixed-port", "port", "socks-port")) require(port(actual.opt(key)) == (expected[key] ?: 0))
        require(actual.opt("allow-lan") is Boolean && actual.opt("allow-lan") == expected["allow-lan"])
        require(actual.opt("bind-address") is String && actual.opt("bind-address") == expected["bind-address"])
        val authEnabled = (expected["authentication"] as List<*>).isNotEmpty()
        require(actual.opt("authEnabled") is Boolean && actual.opt("authEnabled") == authEnabled)
        val active = root.getJSONArray("activePorts")
        require(active.length() == plan.inbounds.size)
        val actualPorts = (0 until active.length()).map { port(active.get(it)) }
        require(actualPorts == plan.inbounds.map { it.port }.sorted())
    }
}

private fun canonicalEpoch(value: Any?): ULong {
    val text = value as? String ?: error("epoch type")
    val parsed = text.toULongOrNull() ?: error("epoch range")
    require(parsed != 0uL && parsed.toString() == text)
    return parsed
}

private fun port(value: Any?): Int {
    require(value is Int || value is Long)
    val number = (value as Number).toLong()
    require(number in 0..65535)
    return number.toInt()
}

private inline fun <T> networkWire(message: String, decode: () -> T): T = try { decode() }
catch (_: Exception) { throw IOException(message) }

private fun JSONObject.networkKeys(): Set<String> = keys().asSequence().toSet()

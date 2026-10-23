package dev.aerove.lite.networksettings

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkReadbackEpochTest {
    private val receipt = 9_007_199_254_740_993uL
    private val plan = planNetwork(NetworkSettings(ports = UserProxyPorts(14000, 14001, 14002), allowLan = true,
        authentication = ProxyAuthentication(true, "fixture", "private-fixture")), 18000, true)
    private fun readback(epoch: Any): JSONObject = JSONObject().put("coreEpoch", epoch)
        .put("network", JSONObject().put("mixed-port", 14000).put("port", 14001).put("socks-port", 14002)
            .put("allow-lan", true).put("bind-address", "::").put("authEnabled", true))
        .put("activePorts", JSONArray(listOf(14000, 14001, 14002)))

    @Test fun sameOrNewerCanonicalEpochAcceptsExactlyMatchingNetworkAfterUnrelatedCoreMutation() {
        for (actual in listOf(receipt, receipt + 1uL, ULong.MAX_VALUE)) {
            verifyNetworkReadback(readback(actual.toString()).toString(), receipt, plan)
        }
        verifyNetworkReadback(readback(ULong.MAX_VALUE.toString()).toString(), ULong.MAX_VALUE, plan)
    }

    @Test fun staleAndNonCanonicalEpochsStillRejectWithoutRoundingOrWrapping() {
        val rejected = listOf<Any>((receipt - 1uL).toString(), "0", "01", "+9007199254740993", "-1",
            "18446744073709551616", "9007199254740993.0", receipt.toDouble(), JSONObject.NULL)
        for (epoch in rejected) {
            assertThrows(IOException::class.java) { verifyNetworkReadback(readback(epoch).toString(), receipt, plan) }
        }
        assertThrows(IOException::class.java) {
            verifyNetworkReadback(readback((ULong.MAX_VALUE - 1uL).toString()).toString(), ULong.MAX_VALUE, plan)
        }
    }

    @Test fun newerEpochNeverExcusesChangedPortsLanBindAuthenticationOrUnknownFields() {
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("network").put("mixed-port", 15000) },
            { it.getJSONObject("network").put("port", 0) },
            { it.getJSONObject("network").put("socks-port", "14002") },
            { it.getJSONObject("network").put("allow-lan", false) },
            { it.getJSONObject("network").put("bind-address", "0.0.0.0") },
            { it.getJSONObject("network").put("authEnabled", false) },
            { it.put("activePorts", JSONArray(listOf(14002, 14001, 14000))) },
            { it.put("activePorts", JSONArray(listOf(14000, 14001))) },
            { it.getJSONObject("network").put("unexpected", true) },
            { it.put("unexpected", true) },
        )
        for (corrupt in corruptions) {
            val source = readback((receipt + 10uL).toString()).also(corrupt).toString()
            val error = assertThrows(IOException::class.java) { verifyNetworkReadback(source, receipt, plan) }
            assertFalse(error.stackTraceToString().contains("private-fixture"))
        }
    }
}

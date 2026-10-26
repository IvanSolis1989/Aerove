package dev.aerove.lite.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RouteHitsTest {
    @Test fun oneBatchCarriesEveryHostWithExactUnsignedCountersAndOrderedVerdicts() = runBlocking<Unit> {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setBody("""{"core-epoch":"9223372036854775808","route-version":"18446744073709551615","held":[true,false]}"""))
            CoreApiClient(ControlEndpoint(server.port,"0123456789abcdef0123456789abcdef")).use{api->
                val first=listOf(RouteHit("a.example:443",exit="leaf-A"),RouteHit("b.example:443","LOAD","load-balance"))
                val result=api.checkRoutes(listOf(first,emptyList()))
                assertEquals(9223372036854775808uL,result.coreEpoch);assertEquals(ULong.MAX_VALUE,result.routeVersion);assertEquals(listOf(true,false),result.held)
                val request=server.takeRequest();assertEquals("POST",request.method);assertEquals("/aerove/check-routes",request.path)
                val rows=JSONObject(request.body.readUtf8()).getJSONArray("hits");assertEquals(2,rows.getJSONArray(0).length());assertEquals("LOAD",rows.getJSONArray(0).getJSONObject(1).getString("proxy"));assertEquals(0,rows.getJSONArray(1).length())
            }
        }
    }
    @Test fun malformedOrMissingHitsNeverBecomeRouteProof() {
        listOf("{}","{\"core-epoch\":\"1\",\"route-version\":\"2\",\"held\":[1]}",
            "{\"core-epoch\":\"1\",\"route-version\":\"2\",\"held\":[true,false]}",
            "{\"core-epoch\":\"01\",\"route-version\":\"2\",\"held\":[true]}").forEach{raw->assertThrows(IOException::class.java){decodeRouteCheck(raw,1)}}
        assertThrows(IOException::class.java){decodeRouteHits(listOf(mapOf("host" to "", "exit" to "x")))}
        val parsed=ProbeJson("""{"hits":[{"host":"a.example:443","proxy":"LB","exit":"actual"}]}""").fields()
        assertEquals(listOf(RouteHit("a.example:443","LB","actual")),decodeRouteHits(parsed["hits"]))
    }
    @Test fun modeIsARouteMoveWhileIpv6StillRequiresConfigurationApply() {
        val before = dev.aerove.lite.LiteSettings()
        assertFalse(dev.aerove.lite.requiresCoreReconfiguration(before, before.copy(mode = dev.aerove.lite.ProxyMode.Direct)))
        assertTrue(dev.aerove.lite.requiresCoreReconfiguration(before, before.copy(ipv6 = !before.ipv6)))
    }

}

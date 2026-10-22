package dev.aerove.lite.ipquality

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IpQualityTest {
    private val ip = "203.0.113.8"
    @Test fun exactStandardUrlsAndDeclaredTypes() {
        assertEquals(listOf("https://ident.me/json", "http://ip-api.com/json/$ip?fields=status,isp,org,as,mobile,proxy,hosting",
            "https://api.ipquery.io/$ip?format=json", "https://iplocate.io/api/lookup/$ip", "https://proxycheck.io/v3/$ip",
            "https://api.ipapi.is/?q=$ip"), IpQualitySource.entries.map { it.url(ip) })
        for ((declared, expected) in listOf("isp" to IpType.Residential, "business" to IpType.Business,
            "education" to IpType.Business, "government" to IpType.Business, "banking" to IpType.Business, "hosting" to IpType.Hosting)) {
            val value = parseIpQuality(IpQualitySource.IdentMe, ip, JSONObject().put("ip", ip).put("type", declared).put("asn", "AS123 Example").put("aso", "Real org"))
            assertEquals(expected, value.quality.type); assertEquals(123L, value.quality.asn); assertFalse(value.inferred)
        }
        assertThrows(IllegalArgumentException::class.java) { IpQualitySource.IpQuery.url("example.com/path") }
    }

    @Test fun standardAllSixSourceParsersAndFlags() {
        val sources = listOf(
            IpQualitySource.IdentMe to """{"ip":"$ip","type":"hosting","aso":"A","asn":123}""",
            IpQualitySource.IpApiCom to """{"status":"success","hosting":true,"mobile":true,"proxy":true,"org":"A","as":"AS123"}""",
            IpQualitySource.IpQuery to """{"risk":{"is_datacenter":true,"is_mobile":true,"is_proxy":true,"is_vpn":true,"is_tor":true},"isp":{"org":"A","asn":123}}""",
            IpQualitySource.IpLocate to """{"privacy":{"is_hosting":true,"is_proxy":true,"is_vpn":true,"is_tor":true,"is_abuser":true},"company":{"name":"A","type":"isp"},"asn":{"asn":123}}""",
            IpQualitySource.ProxyCheck to """{"status":"warning","$ip":{"network":{"type":"Hosting","organisation":"A","asn":"AS123"},"detections":{"proxy":true,"vpn":true,"tor":true}}}""",
            IpQualitySource.IpApiIs to """{"is_datacenter":true,"is_mobile":true,"is_proxy":true,"is_vpn":true,"is_tor":true,"is_abuser":true,"company":{"name":"A","type":"isp"},"asn":{"asn":123}}""",
        )
        for ((source, body) in sources) {
            val result = parseIpQuality(source, ip, JSONObject(body)).quality
            assertEquals(source, result.source); assertEquals(IpType.Hosting, result.type)
            assertEquals("A", result.organization); assertEquals(123L, result.asn)
        }
        assertEquals(IpQualityLevel.Risky, parseIpQuality(IpQualitySource.IpQuery, ip, JSONObject(sources[2].second)).quality.level)
        assertEquals(IpQualityLevel.Normal, parseIpQuality(IpQualitySource.IdentMe, ip, JSONObject(sources[0].second)).quality.level)
        assertEquals(IpQualityLevel.Good, IpQuality(ip, IpQualitySource.IdentMe, IpType.Business, isVpn = true, isProxy = true).level)
    }

    @Test fun noInventedTypeOrIspAndOnlyTrueIsAFlag() {
        assertEquals(IpQualitySourceStatus.IpMismatch, assertThrows(IpQualitySourceException::class.java) {
            parseIpQuality(IpQualitySource.IdentMe, ip, JSONObject("""{"ip":"1.1.1.1","type":"isp"}"""))
        }.status)
        assertEquals(IpQualitySourceStatus.RateLimited, assertThrows(IpQualitySourceException::class.java) {
            parseIpQuality(IpQualitySource.ProxyCheck, ip, JSONObject("""{"status":"denied"}"""))
        }.status)
        for (source in listOf(IpQualitySource.IpLocate, IpQualitySource.IpApiIs)) assertThrows(IpQualitySourceException::class.java) {
            parseIpQuality(source, ip, JSONObject())
        }
        val inferred = parseIpQuality(IpQualitySource.IpQuery, ip, JSONObject("""{"risk":{"is_datacenter":"true","is_proxy":1}}"""))
        assertTrue(inferred.inferred); assertNull(inferred.quality.organization); assertNull(inferred.quality.asn); assertFalse(inferred.quality.isProxy)
    }

    @Test fun hedgesStandardWavesAndPrefersDeclaredOverInferred() = runBlocking {
        val calls = mutableListOf<IpQualitySource>()
        val result = lookupIpQuality(ip, hedgeMillis = 10) { source, address ->
            calls += source
            if (source == IpQualitySource.IpLocate) IpQualityAnswer(IpQuality(address, source, IpType.Business))
            else if (source == IpQualitySource.IpQuery) IpQualityAnswer(IpQuality(address, source, IpType.Residential), true)
            else awaitCancellation()
        }
        assertEquals(IpQualitySource.IpLocate, result.source)
        assertEquals(listOf(IpQualitySource.IdentMe, IpQualitySource.IpApiCom, IpQualitySource.IpQuery, IpQualitySource.IpLocate), calls)
    }

    @Test fun inferredFallbackWaitsForAllSourcesAndFailuresRemainOrdered() = runBlocking {
        val calls = mutableListOf<IpQualitySource>()
        val result = lookupIpQuality(ip, hedgeMillis = 10) { source, address ->
            calls += source
            if (source == IpQualitySource.IpApiCom) IpQualityAnswer(IpQuality(address, source, IpType.Residential), true)
            else throw IpQualitySourceException(IpQualitySourceStatus.NoType)
        }
        assertEquals(IpQualitySource.IpApiCom, result.source); assertEquals(6, calls.size)
        try { lookupIpQuality(ip, timeoutMillis = 10, hedgeMillis = 1) { _, _ -> awaitCancellation() }; fail("Expected failed lookup") }
        catch (failure: IpQualityLookupException) {
            assertEquals(IpQualitySource.entries, failure.failures.map { it.source })
            assertTrue(failure.failures.all { it.status == IpQualitySourceStatus.Timeout })
        }
    }

    @Test fun cancellationCancelsAllActualSourceJobs() = runBlocking {
        val started = CompletableDeferred<Unit>(); var cancelled = 0
        val task = async { lookupIpQuality(ip) { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled++ }
        } }
        started.await(); delay(1); task.cancel()
        try { withTimeout(1000) { task.await() }; fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(3, cancelled)
    }
}

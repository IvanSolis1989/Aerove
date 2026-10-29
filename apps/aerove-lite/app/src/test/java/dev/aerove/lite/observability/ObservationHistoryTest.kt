package dev.aerove.lite.observability

import dev.aerove.lite.LogLine
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationHistoryTest {
    private val runtime = UUID.randomUUID().toString()
    private val id = UUID.randomUUID().toString()
    private val time = Instant.parse("2026-10-23T00:00:00Z")
    private fun request(key: String = id) = RequestHistoryEntry(key, time, "tcp", host = "example.com", process = "Browser", chains = listOf("Node", "Select"))
    private fun event(cursor: Int, kind: String, data: JSONObject) = JSONObject().put("cursor", "$cursor").put("kind", kind).put("data", data)
    private fun envelope(events: List<JSONObject>, generation: String = ULong.MAX_VALUE.toString()) = JSONObject()
        .put("type", "observations").put("sessionId", runtime).put("generation", generation)
        .put("cursor", events.size.toString()).put("dropped", "0").put("events", JSONArray(events))
    private fun traffic() = JSONObject().put("id", id).put("upload", ULong.MAX_VALUE.toString()).put("download", "9")

    @Test fun actualWirePreservesUnsignedTrafficAndDnsProvenanceAndOnlyRealClose() {
        val entry = JSONObject().put("id", id).put("start", time.toString()).put("metadata", JSONObject()
            .put("network", "tcp").put("host", "example.com").put("sourceIP", "127.0.0.1").put("sourcePort", "1234")
            .put("destinationIP", "1.1.1.1").put("destinationPort", 443)).put("chains", JSONArray(listOf("Node", "Select")))
            .put("rule", "DOMAIN").put("rulePayload", "example.com").put("upload", "0").put("download", "0")
        val dns = JSONObject().put("domain", "example.com").put("type", "A").put("initiator", "Proxy").put("upstream", "udp://1.1.1.1:53")
            .put("cached", true).put("answers", JSONArray(listOf("1.1.1.1"))).put("rcode", "NOERROR").put("error", "").put("delay", 7).put("time", time.toString())
        val wire = decodeObservationWindow(envelope(listOf(event(1, "request", entry), event(2, "requestTraffic", traffic()),
            event(3, "requestClosed", traffic().put("end", time.plusSeconds(2).toString())), event(4, "dns", dns))).toString())
        assertEquals(ULong.MAX_VALUE, wire.generation)
        val repository = HistoryRepository(); repository.bindSession(1); assertTrue(repository.accept(1, wire))
        val snapshot = repository.snapshot()
        assertEquals(ULong.MAX_VALUE, snapshot.requests.single().upload)
        assertEquals(time.plusSeconds(2), snapshot.requests.single().end)
        assertEquals(1234, snapshot.requests.single().sourcePort)
        assertEquals(listOf("Node", "Select"), snapshot.requests.single().chains)
        assertEquals("Proxy", snapshot.dns.single().initiator); assertTrue(snapshot.dns.single().cached)
        assertEquals(7uL, snapshot.dns.single().delayMillis); assertFalse(snapshot.dns.single().failed)
    }

    @Test fun requestCopyPreservesOriginalIdentityClosureAndEveryCollectedField() {
        val entry = RequestHistoryEntry("original-request-id", time, "tcp", host = "example.test",
            sourceIP = "2001:db8::2", sourcePort = 54321, destinationIP = "2001:db8::3", destinationPort = 443,
            inbound = "tun", process = "Browser", processPath = "/real/browser", uid = "10042",
            chains = listOf("Node", "Select"), rule = "DOMAIN", rulePayload = "example.test",
            upload = ULong.MAX_VALUE, download = 9u, end = time.plusSeconds(2))
        assertEquals("""
            2026-10-23T00:00:00Z
            id=original-request-id
            end=2026-10-23T00:00:02Z
            tcp
            example.test
            2001:db8::2
            54321
            2001:db8::3
            443
            tun
            Browser
            /real/browser
            10042
            DOMAIN
            example.test
            Node
            Select
            upload=18446744073709551615
            download=9
        """.trimIndent(), requestHistoryText(entry))
    }

    @Test fun requestCopyDoesNotInventClosureOrMissingOptionalMetadata() {
        val entry = RequestHistoryEntry("open-request-id", Instant.EPOCH, "udp")
        assertEquals("1970-01-01T00:00:00Z\nid=open-request-id\nudp\nupload=0\ndownload=0", requestHistoryText(entry))
    }

    @Test fun invalidUnsignedSequencesTypesAndOversizedWindowsFailWithoutExposingPayload() {
        val cases = listOf(
            envelope(emptyList(), "018").toString(), envelope(emptyList(), "18446744073709551616").toString(),
            envelope(emptyList()).put("generation", 4).toString(), envelope(listOf(event(1, "requestTraffic", traffic()), event(1, "requestTraffic", traffic()))).toString(),
            envelope((1..33).map { event(it, "requestTraffic", traffic()) }).toString(),
            envelope(listOf(event(1, "private-secret-kind", traffic()))).toString(), " ".repeat(65537),
        )
        cases.forEach { value ->
            val error = assertThrows(IOException::class.java) { decodeObservationWindow(value) }
            assertFalse(error.stackTraceToString().contains("private-secret"))
        }
    }

    @Test fun leaseGenerationPreservesHistoryButRejectsOldCursorsTokensAndRetiredRuntime() {
        val repository = HistoryRepository(); repository.bindSession(1)
        assertTrue(repository.accept(1, ObservationWindow(runtime, 1u, 1u, 0u, listOf(ObservationEvent.Request(1u, request())))))
        val sessionRevision = repository.snapshot().sessionRevision
        assertTrue(repository.accept(1, ObservationWindow(runtime, 2u, 50u, 0u, emptyList())))
        assertEquals(1, repository.snapshot().requests.size)
        assertEquals(sessionRevision, repository.snapshot().sessionRevision)
        assertFalse(repository.accept(1, ObservationWindow(runtime, 1u, 51u, 0u, listOf(ObservationEvent.Traffic(51u, id, 99u, 99u)))))
        assertTrue(repository.accept(1, ObservationWindow(runtime, 2u, 52u, 1u, listOf(ObservationEvent.Traffic(52u, id, 4u, 5u)))))
        assertTrue(repository.snapshot().truncated)
        repository.clear(HistoryKind.Requests)
        repository.accept(1, ObservationWindow(runtime, 2u, 52u, 1u, listOf(ObservationEvent.Request(52u, request()))))
        assertTrue(repository.snapshot().requests.isEmpty())
        val other = UUID.randomUUID().toString()
        repository.accept(1, ObservationWindow(other, 1u, 0u, 0u, emptyList()))
        assertTrue(repository.snapshot().sessionRevision > sessionRevision)
        assertFalse(repository.accept(1, ObservationWindow(runtime, 3u, 0u, 0u, emptyList())))
        repository.bindSession(2)
        assertFalse(repository.accept(1, ObservationWindow(other, 1u, 1u, 0u, listOf(ObservationEvent.Request(1u, request())))))
        assertTrue(repository.snapshot().requests.isEmpty())
    }

    @Test fun realRepositoryCapsAllThreeSourcesAndIgnoresUnknownRequestTraffic() {
        val repository = HistoryRepository(); repository.bindSession(1)
        var cursor = 0uL
        repeat(5002) { i ->
            val event = if (i < 2001) ObservationEvent.Request(++cursor, request(UUID.randomUUID().toString()))
            else ObservationEvent.Dns(++cursor, DnsHistoryEntry("$cursor", time, "d$i.test", "A"))
            repository.accept(1, ObservationWindow(runtime, 1u, cursor, 0u, listOf(event)))
        }
        repeat(5001) { repository.acceptLog(1, LogLine(it.toLong(), "info", "row $it")) }
        repository.accept(1, ObservationWindow(runtime, 1u, ++cursor, 0u, listOf(ObservationEvent.Traffic(cursor, UUID.randomUUID().toString(), 1u, 1u))))
        assertEquals(2000, repository.snapshot().requests.size); assertEquals(3000, repository.snapshot().dns.size)
        assertEquals(5000, repository.snapshot().logs.size); assertEquals("row 1", repository.snapshot().logs.first().message)
    }

    @Test fun filtersMatchEverySearchTermAndExactKeywordsAndTrafficSortCannotOverflow() {
        val a = request().copy(upload = ULong.MAX_VALUE, download = ULong.MAX_VALUE)
        val b = request(UUID.randomUUID().toString()).copy(process = "Other", upload = 1u)
        assertEquals(listOf(a), projectRequests(listOf(a, b), "browser EXAMPLE", setOf("Select")).rows)
        assertTrue(projectRequests(listOf(a), "browser missing").rows.isEmpty())
        assertEquals(a, projectRequests(listOf(b, a), sort = RequestHistorySort.Traffic).rows.first())
        assertEquals(listOf(b, a), projectRequests(listOf(a, b)).rows)
        assertEquals(listOf(b, a), projectRequests(listOf(a, b), sort = RequestHistorySort.Time).rows)
        val dns = DnsHistoryEntry("1", time, "example.com", "AAAA", "Rule", cached = true, rcode = "SERVFAIL")
        assertEquals(listOf(dns), projectDns(listOf(dns), "EXAMPLE servfail", setOf("Rule", "AAAA")).rows)
        val logs = listOf(HistoryLogEntry(1, 0, "warning", "Example failure"), HistoryLogEntry(2, 0, "info", "Example"))
        assertEquals(listOf(logs.first()), projectLogs(logs, "example fail", setOf("warning")))
        val anchor = HistoryAnchor<Int>(2) { it }
        assertEquals(listOf(1, 2), anchor.update(listOf(1, 2), true))
        assertEquals(listOf(1, 2, 3), anchor.update(listOf(2, 3), false))
        assertEquals(listOf(2, 3), anchor.update(listOf(2, 3), true))
    }
    @Test fun activationWatermarkIsStrictAndLegacyWindowsDoNotInventKnownCounts() {
        val legacy = decodeObservationWindow(envelope(emptyList()).toString())
        assertNull(legacy.activationCursor)
        val source = envelope(emptyList(), "1").put("cursor", "7").put("activationCursor", "7")
        assertEquals(7uL, decodeObservationWindow(source.toString()).activationCursor)
        for (bad in listOf<Any>("08", "8", 7, JSONObject.NULL)) {
            assertThrows(IOException::class.java) { decodeObservationWindow(source.put("activationCursor", bad).toString()) }
        }
        val repository = HistoryRepository(); repository.bindSession(1)
        repository.accept(1, legacy)
        assertNull(repository.snapshot().requestCount)
        assertNull(repository.snapshot().dnsCount)
    }

    @Test fun firstWindowSeparatesRealNewEventsFromBackfillAndDeduplicatesCursor() {
        val repository = HistoryRepository(); repository.bindSession(1)
        val backfill = request("old-request")
        val live = request("live-request")
        val oldDns = DnsHistoryEntry("old", time, "old.test", "A")
        val newDns = DnsHistoryEntry("new", time, "new.test", "A")
        val first = ObservationWindow(runtime, 1u, 12u, 0u, listOf(
            ObservationEvent.Request(9u, backfill), ObservationEvent.Dns(10u, oldDns),
            ObservationEvent.Request(11u, live), ObservationEvent.Dns(12u, newDns)), 10u)
        assertTrue(repository.accept(1, first))
        assertEquals(2, repository.snapshot().requests.size)
        assertEquals(2, repository.snapshot().dns.size)
        assertEquals(1L, repository.snapshot().requestCount)
        assertEquals(1L, repository.snapshot().dnsCount)
        assertTrue(repository.accept(1, first))
        assertEquals(1L, repository.snapshot().requestCount)
        repository.accept(1, ObservationWindow(runtime, 1u, 14u, 0u, listOf(
            ObservationEvent.Traffic(13u, live.id, 8u, 9u),
            ObservationEvent.Closed(14u, live.id, time.plusSeconds(1), 8u, 9u)), 10u))
        assertEquals(1L, repository.snapshot().requestCount)
        assertEquals(1L, repository.snapshot().dnsCount)
    }

    @Test fun cumulativeLiveCountsSurviveRetentionEvictionAndExplicitListClear() {
        val repository = HistoryRepository(); repository.bindSession(1)
        repository.accept(1, ObservationWindow(runtime, 1u, 0u, 0u, emptyList(), 0u))
        assertEquals(0L, repository.snapshot().requestCount)
        repeat(2002) { index ->
            val cursor = (index + 1).toULong()
            repository.accept(1, ObservationWindow(runtime, 1u, cursor, 0u,
                listOf(ObservationEvent.Request(cursor, request("flow-$index"))), 0u))
        }
        assertEquals(2000, repository.snapshot().requests.size)
        assertEquals(2002L, repository.snapshot().requestCount)
        repository.clear(HistoryKind.Requests); repository.clear(HistoryKind.Dns)
        assertTrue(repository.snapshot().requests.isEmpty())
        assertEquals(2002L, repository.snapshot().requestCount)
        assertEquals(0L, repository.snapshot().dnsCount)
    }

    @Test fun resumeUsesNewActivationInsteadOfCountingHiddenBackfillAndFullSetupResets() {
        val repository = HistoryRepository(); repository.bindSession(1)
        repository.accept(1, ObservationWindow(runtime, 1u, 0u, 0u, emptyList(), 0u))
        repository.accept(1, ObservationWindow(runtime, 1u, 1u, 0u, listOf(ObservationEvent.Request(1u, request("one"))), 0u))
        repository.accept(1, ObservationWindow(runtime, 2u, 51u, 0u, listOf(
            ObservationEvent.Request(49u, request("hidden")), ObservationEvent.Request(51u, request("new"))), 50u))
        assertEquals(2L, repository.snapshot().requestCount)
        assertFalse(repository.accept(1, ObservationWindow(runtime, 1u, 52u, 0u, emptyList(), 0u)))
        assertFalse(repository.accept(1, ObservationWindow(runtime, 2u, 51u, 0u, emptyList(), 49u)))
        assertEquals(2L, repository.snapshot().requestCount)
        repository.bindSession(2)
        assertNull(repository.snapshot().requestCount)
        assertFalse(repository.accept(1, ObservationWindow(runtime, 2u, 52u, 0u, emptyList(), 50u)))
        repository.accept(2, ObservationWindow(runtime, 3u, 52u, 0u, emptyList(), 52u))
        assertEquals(0L, repository.snapshot().requestCount)
        assertEquals(0L, repository.snapshot().dnsCount)
    }

    @Test fun missingLiveCursorMakesCountsUnknownInsteadOfSilentlyReturningPartialTotals() {
        val repository = HistoryRepository(); repository.bindSession(1)
        repository.accept(1, ObservationWindow(runtime, 1u, 10u, 0u, emptyList(), 10u))
        repository.accept(1, ObservationWindow(runtime, 1u, 12u, 1u,
            listOf(ObservationEvent.Request(12u, request())), 10u))
        assertTrue(repository.snapshot().truncated)
        assertNull(repository.snapshot().requestCount)
        assertNull(repository.snapshot().dnsCount)
        repository.clear(HistoryKind.Requests)
        assertNull(repository.snapshot().requestCount)
    }

    @Test fun newRuntimeEmptyActivationGapIsRejectedWithoutPublishingKnownZero() {
        val repository = HistoryRepository(); repository.bindSession(1)
        val before = repository.snapshot()
        assertFalse(repository.accept(1, ObservationWindow(runtime, 1u, 12u, 0u, emptyList(), 10u)))
        assertEquals(before, repository.snapshot())
        assertNull(repository.snapshot().requestCount)
        assertTrue(repository.accept(1, ObservationWindow(runtime, 1u, 10u, 0u, emptyList(), 10u)))
        assertEquals(0L, repository.snapshot().requestCount)
        assertEquals(0L, repository.snapshot().dnsCount)
        val other = UUID.randomUUID().toString()
        val confirmed = repository.snapshot()
        assertFalse(repository.accept(1, ObservationWindow(other, 1u, 12u, 0u, emptyList(), 10u)))
        assertEquals(confirmed, repository.snapshot())
        assertTrue(repository.accept(1, ObservationWindow(other, 1u, 11u, 0u,
            listOf(ObservationEvent.Request(11u, request("new-runtime"))), 10u)))
        assertEquals(1L, repository.snapshot().requestCount)
    }

    @Test fun resumeEmptyActivationGapCannotAdvanceGenerationOrHideLostEvents() {
        val repository = HistoryRepository(); repository.bindSession(1)
        assertTrue(repository.accept(1, ObservationWindow(runtime, 1u, 1u, 0u,
            listOf(ObservationEvent.Request(1u, request("first"))), 0u)))
        val confirmed = repository.snapshot()
        assertFalse(repository.accept(1, ObservationWindow(runtime, 2u, 12u, 0u, emptyList(), 10u)))
        assertEquals(confirmed, repository.snapshot())
        assertTrue(repository.accept(1, ObservationWindow(runtime, 2u, 11u, 0u,
            listOf(ObservationEvent.Request(11u, request("after-resume"))), 10u)))
        assertEquals(2L, repository.snapshot().requestCount)
        assertEquals(0L, repository.snapshot().dnsCount)
        assertTrue(repository.accept(1, ObservationWindow(runtime, 3u, 20u, 0u, emptyList(), 20u)))
        assertEquals(2L, repository.snapshot().requestCount)
    }

}

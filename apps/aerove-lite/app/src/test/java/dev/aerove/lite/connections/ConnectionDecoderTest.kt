package dev.aerove.lite.connections

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ConnectionDecoderTest {
    private fun decode(row: String) = decodeConnectionSnapshot(JSONObject("""{"connections":[$row]}""")).single()

    @Test fun fixedCoreSessionAndTrackerFieldsRoundTripWithoutLosingPrecision() {
        val row = decode("""{"id":"one","upload":9007199254740993,"download":24,"start":"2026-10-23T01:02:03.123456789+09:00",
          "rule":"DomainSuffix","rulePayload":"example.org","chains":["node","group"],
          "metadata":{"network":"tcp","type":"Tun","sourceIP":"10.0.0.2","sourcePort":51900,
          "destinationIP":"2001:db8::1","destinationPort":443,"host":"example.org","country":"JP","asn":"Example ASN","inboundUser":"alice"}}""")
        assertEquals(9007199254740993L, row.upload)
        assertEquals(Instant.parse("2026-10-22T16:02:03.123456789Z"), row.startedAt)
        assertEquals("example.org:443", row.destination)
        assertEquals("10.0.0.2", row.sourceIP)
        assertEquals(51900, row.sourcePort)
        assertEquals("[2001:db8::1]:443", connectionEndpoint(row.destinationIP, row.destinationPort))
        assertEquals("Tun", row.inboundType)
        assertEquals("example.org", row.rulePayload)
        assertEquals(listOf("node", "group"), row.chains)
        assertEquals("JP", row.country); assertEquals("Example ASN", row.asn); assertEquals("alice", row.inboundUser)
        assertNull(row.process); assertNull(row.processPath); assertNull(row.uid)
    }

    @Test fun absentOptionalMetadataIsUnknownRatherThanZeroOrLiteralNull() {
        val row = decode("""{"id":"one","metadata":{"host":null,"country":null}}""")
        assertEquals("", row.destination); assertEquals("", row.network)
        assertNull(row.sourcePort); assertNull(row.destinationPort); assertNull(row.startedAt)
        assertNull(row.upload); assertNull(row.download); assertNull(row.uid); assertNull(row.country)
        assertTrue(row.chains.isEmpty())
        assertNull(decode("""{"id":"two"}""").host)
    }

    @Test fun zeroValuesAreRetainedWhenActuallyReturned() {
        val row = decode("""{"id":"one","upload":0,"download":"0","metadata":{"uid":0,"sourcePort":"0","destinationPort":0}}""")
        assertEquals(0L, row.upload); assertEquals(0L, row.download); assertEquals(0L, row.uid)
        assertEquals(0, row.sourcePort); assertEquals(0, row.destinationPort)
    }

    @Test fun optionalProcessFieldsArePreservedIfTheHostSuppliesThem() {
        val row = decode("""{"id":"one","metadata":{"process":"browser","processPath":"/app/browser","uid":10234,"host":"","destinationIP":"2001:db8::2","destinationPort":"80"}}""")
        assertEquals("browser", row.process); assertEquals("/app/browser", row.processPath); assertEquals(10234L, row.uid)
        assertEquals("[2001:db8::2]:80", row.destination)
    }

    @Test fun invalidTimeAndTypedMetadataDoNotProduceFakeValues() {
        listOf("""{"id":"one","start":"yesterday"}""", """{"id":"one","metadata":{"uid":-1}}""",
            """{"id":"one","metadata":{"destinationPort":65536}}""", """{"id":"one","metadata":{"sourcePort":1.5}}""",
            """{"id":"one","metadata":{"process":34}}""", """{"id":"one","upload":-1}""",
            """{"id":"one","download":"18446744073709551615"}""", """{"id":"one","chains":[null]}""",
            """{"id":""}""").forEach { row ->
            try { decode(row); fail("Accepted invalid connection: $row") } catch (_: IOException) { }
        }
    }

    @Test fun ipv6IsBracketedOnlyWhenAPortExists() {
        assertEquals("2001:db8::2", connectionEndpoint("2001:db8::2", null))
        assertEquals("[2001:db8::2]:80", connectionEndpoint("[2001:db8::2]", 80))
        assertNull(connectionEndpoint(null, null))
    }

    @Test fun eachRealSnapshotRetainsAHostReceiptStampEvenWhenAllCountersAreIdle() {
        val snapshot = JSONObject("""{"connections":[{"id":"one"},{"id":"two"}]}""")
        val first = decodeConnectionSnapshot(snapshot)
        val second = decodeConnectionSnapshot(snapshot)
        assertNotNull(first.first().observedAtNanos)
        assertEquals(first.first().observedAtNanos, first.last().observedAtNanos)
        assertTrue(second.first().observedAtNanos!! >= first.first().observedAtNanos!!)
    }
}

package dev.aerove.lite.connections

import dev.aerove.lite.ConnectionInfo
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConnectionProjectionTest {
    private fun info(id: String, bytes: Long? = 0, process: String? = null, chain: String = "group") =
        ConnectionInfo(id, "$id.example:443", "tcp", "DOMAIN", listOf("node", chain), bytes, bytes,
            process = process, sourceIP = "10.0.0.2", startedAt = Instant.parse("2026-10-23T00:00:00Z"))

    @Test fun searchTermsMatchDifferentFieldsAndExactKeywordsRequireEverySelection() {
        val rows = listOf(info("a", process = "Browser"), info("b", process = "Other", chain = "other"))
        val projector = ConnectionProjector()
        assertEquals(listOf("a"), projector.project(rows, 0, "BROWSER 10.0.0.2 tcp", setOf("Browser", "group")).rows.map { it.connection.id })
        assertTrue(projector.project(rows, 1, filters = setOf("Brows")).rows.isEmpty())
        assertEquals(listOf("Browser", "group", "node", "Other", "other").sortedWith(String.CASE_INSENSITIVE_ORDER), projector.project(rows, 2).keywords)
    }

    @Test fun countersBecomeSpeedsOnlyAfterARealSecondSnapshot() {
        val projector = ConnectionProjector()
        assertNull(projector.project(listOf(info("a", 100)), 1000).rows.single().downloadSpeed)
        val next = projector.project(listOf(info("a", 300)), 2000).rows.single()
        assertEquals(200L, next.downloadSpeed); assertEquals(200L, next.uploadSpeed)
        assertEquals(0L, projector.project(listOf(info("a", 50)), 3000).rows.single().uploadSpeed)
    }

    @Test fun shortOutOfBandRefreshDoesNotSpikeOrLoseTheSampleBaseline() {
        val projector = ConnectionProjector()
        projector.project(listOf(info("a", 100)), 1000)
        projector.project(listOf(info("a", 120)), 1100)
        assertEquals(200L, projector.project(listOf(info("a", 300)), 2000).rows.single().uploadSpeed)
    }

    @Test fun queryChangesReuseTheSnapshotAndDoNotResampleRates() {
        val projector = ConnectionProjector(); val rows = listOf(info("a", 100))
        projector.project(rows, 1000)
        assertNull(projector.project(rows, 9000, "tcp").rows.single().uploadSpeed)
        assertEquals(100L, projector.project(listOf(info("a", 300)), 3000).rows.single().uploadSpeed)
    }

    @Test fun numericSortKeepsUnknownLastInEitherDirectionAndTiesDeterministic() {
        val projector = ConnectionProjector(); val rows = listOf(info("unknown", null), info("b", 50), info("a", 50), info("c", 100))
        assertEquals(listOf("c", "a", "b", "unknown"), projector.project(rows, 0, sort = ConnectionSort.Download, descending = true).rows.map { it.connection.id })
        assertEquals(listOf("a", "b", "c", "unknown"), projector.project(rows, 1, sort = ConnectionSort.Download).rows.map { it.connection.id })
    }

    @Test fun defaultRankUsesRealTrafficThenLiveSpeedAndForgetsClosedConnections() {
        val projector = ConnectionProjector()
        assertEquals("b", projector.project(listOf(info("a", 100), info("b", 200)), 1000).rows.first().connection.id)
        assertEquals("a", projector.project(listOf(info("a", 1000), info("b", 201)), 2000).rows.first().connection.id)
        projector.project(emptyList(), 3000)
        assertNull(projector.project(listOf(info("a", 2000)), 4000).rows.single().uploadSpeed)
    }

    @Test fun reusedIdWithANewStartDoesNotInheritOldSpeed() {
        val projector = ConnectionProjector()
        projector.project(listOf(info("a", 100)), 1000)
        val replacement = info("a", 500).copy(startedAt = Instant.parse("2026-10-23T00:01:00Z"))
        assertNull(projector.project(listOf(replacement), 2000).rows.single().downloadSpeed)
    }

    @Test fun realReceiptTimesProduceZeroOnIdleSnapshotsWithoutATimer() {
        val projector = ConnectionProjector()
        projector.project(listOf(info("a", 100).copy(observedAtNanos = 1_000_000_000)), 10000)
        assertEquals(100L, projector.project(listOf(info("a", 200).copy(observedAtNanos = 2_000_000_000)), 30000).rows.single().uploadSpeed)
        assertEquals(0L, projector.project(listOf(info("a", 200).copy(observedAtNanos = 3_000_000_000)), 30001).rows.single().uploadSpeed)
    }
}

package dev.aerove.lite.observability

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoryTablesTest {
    @Test fun strictCodecRoundTripsLogicalPixelWidthsAndRejectsWrongTypesAndUnknownKeys() {
        val value = HistoryTablePreferences(mapOf("requests" to mapOf("time" to 81.25, "target" to 24.0), "dns" to mapOf("domain" to 200.0)))
        assertEquals(value, decodeHistoryTables(JSONObject(encodeHistoryTables(value).toString())))
        listOf("""{"requests":{"time":"80"}}""", """{"requests":{"time":0}}""", """{"requests":{"time":-1}}""",
            """{"requests":{"password":80}}""", """{"logs":{"message":80}}""", """{"dns":[]} """).forEach {
            assertThrows(Exception::class.java) { decodeHistoryTables(JSONObject(it)) }
        }
        assertThrows(IllegalArgumentException::class.java) { HistoryTablePreferences(mapOf("dns" to mapOf("domain" to Double.NaN))) }
    }

    @Test fun queuedColumnAndTableEditsMergeAgainstTheLatestSavedSettingsAndExplicitResetIsScoped() {
        val baseline = HistoryTablePreferences(mapOf("requests" to mapOf("time" to 80.0)))
        val first = baseline.apply(HistoryTableChange("requests", mapOf("process" to 200.0)))
        val second = baseline.apply(HistoryTableChange("dns", mapOf("domain" to 260.0)))
        val latest = first.apply(HistoryTableChange("requests", mapOf("time" to 95.0)))
        val merged = mergeHistoryTables(baseline, second, latest)
        assertEquals(mapOf("time" to 95.0, "process" to 200.0), merged.widths["requests"])
        assertEquals(mapOf("domain" to 260.0), merged.widths["dns"])
        val reset = merged.apply(HistoryTableChange("requests", reset = true))
        assertFalse(reset.widths.containsKey("requests")); assertEquals(merged.widths["dns"], reset.widths["dns"])
        assertEquals(reset, decodeHistoryTables(JSONObject(encodeHistoryTables(reset).toString())))
    }

    @Test fun actualStandardResizeTakesSlackFromSuccessiveRightColumnsAndScalesToTheWindow() {
        val columns = listOf(HistoryColumn("time", 80.0), HistoryColumn("domain", flex = 1.0), HistoryColumn("type", 72.0))
        val widths = historyColumnWidths(columns, 400.0, emptyMap())
        assertEquals(listOf(80.0, 248.0, 72.0), widths)
        val grown = resizeHistoryColumns(columns, widths, emptyMap(), 0, 500.0)
        assertEquals(mapOf("time" to 304.0, "domain" to 48.0, "type" to 48.0), grown)
        val shrunk = resizeHistoryColumns(columns, widths, emptyMap(), 0, -500.0)
        assertEquals(mapOf("time" to 48.0, "domain" to 280.0), shrunk)
        assertEquals(200.0, historyColumnWidths(columns, 200.0, grown).sum(), 0.000001)
        assertEquals(listOf("time", "process", "target", "rule", "exit", "traffic"), requestHistoryColumns(false).map { it.id })
        assertTrue(requestHistoryColumns(true).any { it.id == "download" })
        assertFalse(dnsHistoryColumns(false).any { it.id == "upstream" })
        assertTrue(dnsHistoryColumns(true).any { it.id == "upstream" })
    }

    @Test fun thirdSortTapRestoresArrivalOrderForEitherInitialDirectionAndChangingColumnStartsItsOwnCycle() {
        for (descendingFirst in listOf(false, true)) {
            val first = nextHistorySort(null, "time", descendingFirst)
            assertEquals(descendingFirst, first!!.descending)
            val second = nextHistorySort(first, "time", descendingFirst)
            assertEquals(!descendingFirst, second!!.descending)
            assertNull(nextHistorySort(second, "time", descendingFirst))
            assertEquals(HistorySortSelection("domain", false), nextHistorySort(second, "domain", false))
        }
    }
}

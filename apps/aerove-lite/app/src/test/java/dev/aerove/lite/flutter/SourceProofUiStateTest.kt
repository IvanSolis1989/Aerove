package dev.aerove.lite.flutter

import org.junit.Assert.*
import org.junit.Test

/** Presentation writes must not revoke a graph whose routing inputs are unchanged. */
class SourceProofUiStateTest {
    private val profile = mapOf<String, Any?>("id" to 1L, "type" to "custom", "label" to "Selected",
        "url" to "", "extend_type" to "standard", "overrides" to null, "selected_map" to "{}",
        "current_group_name" to null, "unfold_set" to "[]")
    private fun tables(row: Map<String, Any?>) = nativeLiteBusinessTables.associateWith { name ->
        if (name == "profiles") listOf(row) else emptyList()
    }
    private fun current(row: Map<String, Any?>) = nativeLiteJsonHash(nativeLiteSelectedRows(tables(row), 1L))

    @Test fun changingVisibleProxyGroupDoesNotRevokeSourceProof() {
        assertEquals(current(profile), current(profile + ("current_group_name" to "Other group")))
    }

    @Test fun expandingProxyGroupDoesNotRevokeSourceProof() {
        assertEquals(current(profile), current(profile + ("unfold_set" to "[\"Group\"]")))
    }

    @Test fun actualSelectionAndProfileDefinitionRemainBound() {
        for ((column, value) in mapOf("selected_map" to "{\"Group\":\"Node\"}", "label" to "Changed",
            "url" to "https://example.invalid/changed", "overrides" to "{\"hosts\":{\"example.invalid\":\"127.0.0.2\"}}")) {
            assertNotEquals(column, current(profile), current(profile + (column to value)))
        }
    }

    @Test fun legacyProofsKeepTheirOriginalFullRowContract() {
        val edited = profile + mapOf("current_group_name" to "Other group", "unfold_set" to "[\"Group\"]")
        for (schema in listOf(2, 3)) {
            assertNotEquals(nativeLiteJsonHash(nativeLiteSelectedRows(tables(profile), 1L, schema)),
                nativeLiteJsonHash(nativeLiteSelectedRows(tables(edited), 1L, schema)))
        }
        assertThrows(IllegalArgumentException::class.java) { nativeLiteSelectedRows(tables(profile), 1L, 5) }
    }

    @Test fun schemaFourGeoGuardStillBindsRuleSqlInfrastructure() {
        val proof = org.json.JSONObject().put("schema", 4).put("databaseSchema", 23)
            .put("setupSha256", "a".repeat(64)).put("tables", org.json.JSONObject())
            .put("files", org.json.JSONArray()).put("ruleSql", org.json.JSONObject().put("incarnation", "first"))
        val before = meowGeoSourceGuard(proof)
        proof.getJSONObject("ruleSql").put("incarnation", "changed")
        assertNotEquals(before, meowGeoSourceGuard(proof))
        proof.put("databaseSchema", 22)
        assertThrows(IllegalArgumentException::class.java) { meowGeoSourceGuard(proof) }
    }
}

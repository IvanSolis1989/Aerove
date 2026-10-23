package dev.aerove.lite.profilesource

import dev.aerove.lite.data.ProfileLinkDecoder
import dev.aerove.lite.data.ProfileLinkErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NamedInputIssueDetailsTest {
    @Test fun unsupported_field_and_reason_remain_associated_with_single_and_batch_source_line() = runBlocking {
        val url = "hysteria://private-test-credential@node.example:443#Example"
        val expected = ProfileLinkDecoder().decode(url).errors.single()
        assertEquals(ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY, expected.code)
        assertEquals("$.scheme", expected.field)
        assertTrue(expected.reason.contains("hysteria"))
        val planner = NamedImportPlanner()
        AgeSecret("synthetic-test-identity".toByteArray()).use { secret ->
            val single = planner.single(NamedProfileInput("Provider", url, secret)).issues.single()
            assertEquals(NamedInputIssue(1, expected.code.name.lowercase(), expected.field, expected.reason), single)
            assertFalse(single.toString().contains("private-test-credential"))
            assertFalse(single.toString().contains("synthetic-test-identity"))
        }
        val batch = planner.batch("https://valid.example/sub\n\n- '$url',")
        assertEquals(1, batch.items.size)
        assertEquals(NamedInputIssue(3, expected.code.name.lowercase(), expected.field, expected.reason), batch.issues.single())
        val legacy = NamedInputIssue(7, "invalid_url")
        assertNull(legacy.field); assertNull(legacy.reason)
    }
}

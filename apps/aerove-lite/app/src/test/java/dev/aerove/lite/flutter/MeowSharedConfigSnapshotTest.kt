package dev.aerove.lite.flutter

import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowSharedConfigSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun directWriteCanBeIncompleteButOnlyStableFullSnapshotIsReturned() {
        val file = temporary.newFile()
        val expected = "synthetic committed value"
        file.writeText(expected)
        FileOutputStream(file).use { writer ->
            assertEquals("", file.readText())
            var pauses = 0
            val actual = nativeLiteSharedConfigSnapshot({ file.readText().takeIf { it.isNotEmpty() } }) {
                pauses++
                writer.write(expected.toByteArray()); writer.flush()
            }
            assertEquals(expected, actual); assertEquals(1, pauses)
        }
    }
    @Test fun persistentMismatchAndMissingValuesHaveStrictReadAndPauseBudgets() {
        var reads = 0
        var pauses = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            nativeLiteSharedConfigSnapshot({ reads++; if (reads % 2 == 0) "private-new" else "private-old" }, { pauses++ })
        }
        assertEquals(6, reads); assertEquals(2, pauses)
        assertEquals("shared_config_unstable", failure.message)
        assertThrows(IllegalStateException::class.java) { nativeLiteSharedConfigSnapshot({ null }, {}) }
    }
    @Test fun changingDiskIsRetriedAndNeverReusesFirstValue() {
        val values = ArrayDeque(listOf("old", "new", "new", "new"))
        assertEquals("new", nativeLiteSharedConfigSnapshot({ values.removeFirst() }, {}))
        assertTrue(values.isEmpty())
    }
    @Test fun stableNewValueCannotSatisfyAnOldProof() {
        val first = nativeLiteSharedConfigSnapshot({ "old" }, {})
        val second = nativeLiteSharedConfigSnapshot({ "new" }, {})
        assertNotEquals(first, second)
        assertThrows(IllegalStateException::class.java) {
            check(dev.aerove.lite.data.sha256(first) == dev.aerove.lite.data.sha256(second)) { "shared_source_config_changed" }
        }
    }
    @Test fun ownershipFailuresAndInterruptionAreNotRetriedAway() {
        var pauses = 0
        val ownership = IllegalStateException("shared_config_missing")
        try { nativeLiteSharedConfigSnapshot({ throw ownership }, { pauses++ }); fail() }
        catch (actual: IllegalStateException) { assertSame(ownership, actual) }
        assertEquals(0, pauses)
        assertThrows(InterruptedException::class.java) { nativeLiteSharedConfigSnapshot({ null }, { throw InterruptedException() }) }
    }
}

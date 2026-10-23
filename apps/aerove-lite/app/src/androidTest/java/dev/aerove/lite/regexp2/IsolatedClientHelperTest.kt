package dev.aerove.lite.regexp2

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Requires the real packaged JNI+Go libraries and declared isolated service; no JVM substitute. */
class IsolatedClientHelperTest {
    @Before fun install() { Regexp2Filters.install(InstrumentationRegistry.getInstrumentation().targetContext) }
    @Test fun exactSemanticsNulAndSharedMemoryRoundTrip() = runBlocking<Unit> {
        assertEquals(listOf(1), Regexp2Filters.indices(listOf("^[a-z-[aeiou]]$"), emptyList(), listOf("a", "b")))
        assertEquals(listOf(0), Regexp2Filters.indices(listOf("^(?<N>a)(b)\\1$"), emptyList(), listOf("abb", "aba")))
        val names = List(20000) { "候選node-$it-😀" } + "nul\u0000end"
        assertEquals(listOf(20000), Regexp2Filters.indices(listOf("\u0000end$"), emptyList(), names))
        assertTrue(runCatching { Regexp2Filters.indices(listOf("["), emptyList(), names) }.isFailure)
    }
    @Test fun cancelledRequestCanRebindOnlyAfterConfirmedExit() = runBlocking<Unit> {
        val operation = launch(Dispatchers.Default) {
            Regexp2Filters.indices(listOf("^(a+)+$"), emptyList(), listOf("a".repeat(50000) + "!"))
        }
        delay(200)
        operation.cancelAndJoin()
        assertEquals(listOf(0), Regexp2Filters.indices(listOf("^OK$"), emptyList(), listOf("OK")))
    }
}

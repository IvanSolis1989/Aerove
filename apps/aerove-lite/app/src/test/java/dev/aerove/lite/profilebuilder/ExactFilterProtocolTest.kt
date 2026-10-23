package dev.aerove.lite.profilebuilder

import dev.aerove.lite.regexp2.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExactFilterProtocolTest {
    private val engine = RealGoFilterExecutor()
    @Test fun dotNetCharacterSubtractionAndCaptureNumberingAreExact() = runBlocking<Unit> {
        assertEquals(listOf(1), engine.indices(listOf("^[a-z-[aeiou]]$"), emptyList(), listOf("a", "b"), false))
        assertEquals(listOf(0), engine.indices(listOf("^(?<N>a)(b)\\1$"), emptyList(), listOf("abb", "aba"), false))
    }
    @Test fun orderedEmptyPartsAndUnicodeNulUseRealProductionEngine() = runBlocking<Unit> {
        val names = listOf("JP", "SG", "x\u0000😀")
        assertEquals(listOf(1, 0), engine.indices(listOf("^SG`^JP"), emptyList(), names, false))
        assertEquals(listOf(0, 1, 2), engine.indices(listOf("`^SG"), emptyList(), names, false))
        assertEquals(listOf(2), engine.indices(listOf("\u0000😀$"), emptyList(), names, false))
        assertEquals(emptyList<Int>(), engine.indices(listOf("jp"), emptyList(), listOf("JP"), false))
    }
    @Test fun compileErrorIsAnErrorNotEmptyMatches() = runBlocking<Unit> {
        val failure = runCatching { engine.indices(listOf("["), emptyList(), listOf("x"), false) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("$.include"))
    }
    @Test fun malformedUnicodeAndMismatchedResponsesAreRejected() {
        assertTrue(runCatching { FilterProtocol.request(1, listOf("\uD800"), emptyList(), emptyList(), true) }.isFailure)
        assertTrue(runCatching { FilterProtocol.indices(1, "{}".toByteArray(), 0) }.isFailure)
    }
    @Test fun chunkBudgetsKeepEveryCandidateInOriginalOrder() {
        val values = List(100001) { "name$it" }
        val chunks = FilterChunks.names(values)
        assertEquals(2, chunks.size)
        assertEquals(values.indices.toList(), chunks.flatMap { it.toList() })
        assertTrue(chunks.all { it.count() <= 100000 })
    }
}

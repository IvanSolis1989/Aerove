package com.follow.clash.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenerSlotTest {
    private val installed = mutableListOf<String?>()
    private val slot = ListenerSlot<String> { installed += it }

    @Test
    fun `set hands the listener to the target`() {
        slot.set("first")

        assertEquals(listOf<String?>("first"), installed)
    }

    @Test
    fun `the listener in the slot clears it`() {
        slot.set("first")

        assertTrue(slot.clear("first"))
        assertEquals(listOf("first", null), installed)
    }

    @Test
    fun `a replaced listener cannot clear its replacement`() {
        slot.set("first")
        slot.set("second")

        assertFalse(slot.clear("first"))
        assertEquals(listOf<String?>("first", "second"), installed)

        assertTrue(slot.clear("second"))
        assertEquals(listOf("first", "second", null), installed)
    }

    @Test
    fun `clearing an empty slot touches nothing`() {
        assertFalse(slot.clear("first"))
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `identity, not equality, owns the slot`() {
        val owner = String(charArrayOf('a'))
        slot.set(owner)

        assertFalse(slot.clear(String(charArrayOf('a'))))
        assertTrue(slot.clear(owner))
    }
}

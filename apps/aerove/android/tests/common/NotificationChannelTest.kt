package com.follow.clash.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelTest {
    @Test
    fun `an unchanged channel name is not written again`() {
        assertFalse(channelNeedsRename("AEROVE", "AEROVE"))
        assertFalse(channelNeedsRename(StringBuilder("AEROVE"), "AEROVE"))
    }

    @Test
    fun `a new locale still renames the channel`() {
        assertTrue(channelNeedsRename("Proxy service", "代理服务"))
        assertTrue(channelNeedsRename(null, "AEROVE"))
    }
}

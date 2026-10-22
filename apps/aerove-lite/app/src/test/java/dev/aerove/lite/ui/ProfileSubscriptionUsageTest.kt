package dev.aerove.lite.ui

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class ProfileSubscriptionUsageTest {
    @Test fun parsesTheStoredHeaderWithoutAnotherRequestAndUsesSecondsForExpiry() {
        val usage = requireNotNull(parseProfileSubscriptionUsage("Upload=1048576; download=2097152; total=8388608; expire=1700000000"))
        assertEquals(BigInteger.valueOf(3145728), usage.used)
        assertEquals(0.375, usage.share!!, 0.000001)
        assertEquals(1700000000000L, usage.expireMillis)
    }

    @Test fun absentOrInvalidHeadersDoNotCreateSubscriptionInformation() {
        assertNull(parseProfileSubscriptionUsage(null))
        assertNull(parseProfileSubscriptionUsage("content=unknown; total=NaN; download=Infinity"))
        assertNull(parseProfileSubscriptionUsage("total=999999999999999999999999999999"))
    }

    @Test fun unknownTotalsAndNegativeCountersHaveNoUsageRatio() {
        assertNull(requireNotNull(parseProfileSubscriptionUsage("upload=4; download=8")).share)
        assertNull(requireNotNull(parseProfileSubscriptionUsage("upload=-1; download=8; total=10")).progress)
        assertNull(requireNotNull(parseProfileSubscriptionUsage("total=0")).progress)
    }

    @Test fun excessUsageClampsOnlyTheRenderingAndNeverOverflowsTheUsedAmount() {
        val usage = requireNotNull(parseProfileSubscriptionUsage("upload=${Long.MAX_VALUE}; download=${Long.MAX_VALUE}; total=100"))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), usage.used)
        assertTrue(usage.share!! > 1)
        assertEquals(1f, usage.progress!!, 0f)
    }

    @Test fun decimalCountsFollowTheStandardTruncationAndExpiryCannotOverflow() {
        val usage = requireNotNull(parseProfileSubscriptionUsage("upload=3.9; download=4e1; expire=${Long.MAX_VALUE}"))
        assertEquals(3L, usage.upload)
        assertEquals(40L, usage.download)
        assertNull(usage.expireMillis)
    }

    @Test fun automaticUpdateAcceptsOnlyPositiveWholeMinutesWithinTheStorageRange() {
        assertEquals(60_000L, profileIntervalMillis("1"))
        assertEquals(86_400_000L, profileIntervalMillis("1440"))
        listOf("", "0", "-1", "1.5", "1e3", Long.MAX_VALUE.toString()).forEach { assertNull(profileIntervalMillis(it)) }
    }
}

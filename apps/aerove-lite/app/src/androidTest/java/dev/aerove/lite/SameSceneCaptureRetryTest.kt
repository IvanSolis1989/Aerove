package dev.aerove.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SameSceneCaptureRetryTest {
    @Test fun onlyObservedSoftFrostDashboardMatrixCanRetry() {
        for (brightness in listOf("light", "dark")) {
            val label = "matrix-soft-frost-classic-monochrome-$brightness"
            assertTrue(isSoftFrostScreenshotRetryCheckpoint(label, productWindow = true, themeMatrix = true))
            assertFalse(isSoftFrostScreenshotRetryCheckpoint(label, productWindow = true, themeMatrix = false))
            assertFalse(isSoftFrostScreenshotRetryCheckpoint(label, productWindow = false, themeMatrix = true))
        }
        for (label in listOf("first-consent", "settings-11", "failure", "matrix-soft-frost-living-aurora-light",
            "matrix-clear-shimmer-classic-monochrome-light")) {
            assertFalse(isSoftFrostScreenshotRetryCheckpoint(label, productWindow = true, themeMatrix = true))
        }
    }

    @Test fun firstSuccessDoesNotRetry() {
        assertEquals("first", captureWithOneSameSceneRetry(
            take = { "first" }, beforeRetry = { error("retried") },
            afterRetry = { error("retried") }, discard = { error("discarded") },
        ))
    }

    @Test fun twoNullsRemainAFailureWithNoThirdCapture() {
        val events = mutableListOf<String>()
        assertNull(captureWithOneSameSceneRetry<String>(
            take = { events += "capture"; null }, beforeRetry = { events += "before" },
            afterRetry = { assertNull(it); events += "after" }, discard = { error("discarded") },
        ))
        assertEquals(listOf("capture", "before", "capture", "after"), events)
    }

    @Test fun sameSceneRetryReturnsOnlyTheRealSecondImage() {
        val image = Any()
        var attempts = 0
        assertSame(image, captureWithOneSameSceneRetry(
            take = { if (++attempts == 1) null else image }, beforeRetry = {},
            afterRetry = { assertSame(image, it) }, discard = { error("discarded") },
        ))
        assertEquals(2, attempts)
    }

    @Test fun originalCaptureExceptionIsNotRetried() {
        val failure = IllegalArgumentException("capture error")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            captureWithOneSameSceneRetry<String>(
                take = { throw failure }, beforeRetry = { error("retried") },
                afterRetry = { error("retried") }, discard = { error("discarded") },
            )
        })
    }

    @Test fun sceneChangeBeforeRetryPreventsSecondCapture() {
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            captureWithOneSameSceneRetry<String>(
                take = { attempts++; null }, beforeRetry = { error("scene changed") },
                afterRetry = { error("retried") }, discard = { error("discarded") },
            )
        }
        assertEquals(1, attempts)
    }

    @Test fun sceneChangeAfterRetryRejectsAndDiscardsSecondBitmap() {
        var attempts = 0
        val image = Any()
        var discarded: Any? = null
        assertThrows(IllegalStateException::class.java) {
            captureWithOneSameSceneRetry(
                take = { if (++attempts == 1) null else image }, beforeRetry = {},
                afterRetry = { error("scene changed") }, discard = { discarded = it },
            )
        }
        assertSame(image, discarded)
        assertEquals(2, attempts)
    }
}

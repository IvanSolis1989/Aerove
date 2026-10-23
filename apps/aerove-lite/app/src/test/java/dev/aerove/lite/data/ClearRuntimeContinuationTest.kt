package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.integration.*
import dev.aerove.lite.vpn.MaintenanceRuntimeContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClearRuntimeContinuationTest {
    @Test fun committedClearReleasesHeldRuntimeWithoutResumingPreviousIntent() = runBlocking<Unit> {
        val settings = LiteSettings()
        val receipt = MaintenanceRuntimeContext(true, null, null)
        var resumed: Boolean? = null
        val owner = RestoreRuntimeOwner(stop = { receipt },
            capture = { RestoreRuntimeBaseline(RestoreRuntimeTarget("old", settings), null) },
            target = { RestoreRuntimeTarget(null, LiteSettings()) },
            resume = { actual, target, run -> assertSame(receipt, actual); assertNull(target!!.profileId); resumed = run },
            rollbackSafe = { _, _ -> false }, resumeAfterSuccess = false)
        owner.execute { stopped, loaded -> stopped(); loaded() }
        assertEquals(false, resumed)
    }
    @Test fun failedClearCanResumeOnlyVerifiedOriginalBaseline() = runBlocking<Unit> {
        val settings = LiteSettings()
        val expected = IllegalStateException("original")
        var resumed: Boolean? = null
        val owner = RestoreRuntimeOwner(stop = { MaintenanceRuntimeContext(true, null, null) },
            capture = { RestoreRuntimeBaseline(RestoreRuntimeTarget("old", settings), null) },
            target = { error("Failed write cannot reach target") },
            resume = { _, target, run -> assertEquals("old", target!!.profileId); resumed = run },
            rollbackSafe = { failure, _ -> failure === expected }, resumeAfterSuccess = false)
        try { owner.execute { stopped, _ -> stopped(); throw expected }; fail() }
        catch (failure: IllegalStateException) { assertSame(expected, failure) }
        assertEquals(true, resumed)
    }
}

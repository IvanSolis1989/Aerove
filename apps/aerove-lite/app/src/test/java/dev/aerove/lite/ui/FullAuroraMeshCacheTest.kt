package dev.aerove.lite.ui

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FullAuroraMeshCacheTest {
    private fun hash(mesh: FullAuroraMesh): String {
        val bytes = ByteBuffer.allocate(mesh.colors.size * 4)
        mesh.colors.forEach { bytes.putInt(it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
    }

    @Test fun rowCancellationPreservesTheExistingLightAndDarkGoldens() {
        // Captured from the same fixed motion-A baseline before the row cancellation/recording change.
        assertEquals("975df8a35077607e90d8302cf96d02fc42437ccbc7475f6a2ca8eee67c1874c0",
            hash(fullAuroraMesh(271828L, FullAuroraColors, false)))
        assertEquals("5aec0573f0e14bd67f68604bc06f3b231442538752eb954f9fa3b40127e7eb69",
            hash(fullAuroraMesh(271828L, FullAuroraColors, true)))
    }

    @Test fun cancelledPartialMeshNeverEntersTheBoundedCache() {
        val seed = 911199L
        var cancelledRows = 0
        assertThrows(CancellationException::class.java) {
            fullAuroraMesh(seed, FullAuroraColors, false) {
                if (++cancelledRows == 17) throw CancellationException("test cancellation")
            }
        }
        assertEquals(17, cancelledRows)
        var completedRows = 0
        val complete = fullAuroraMesh(seed, FullAuroraColors, false) { completedRows++ }
        assertEquals(97, completedRows)
        assertEquals(6305, complete.colors.size)
        assertSame(complete, fullAuroraMesh(seed, FullAuroraColors, false) { fail("cached mesh rebuilt") })
    }
}

package dev.aerove.lite.scan

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanSessionTest {
    @Test fun inactiveRouteNeverStartsCameraAndCloseIsIdempotent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            var closed = 0
            val session = ScanSession(ApplicationProvider.getApplicationContext(), { fail("Unexpected result") }, { closed++ })
            session.attach(ScanPreview(ApplicationProvider.getApplicationContext()))
            session.update(false, true)
            assertEquals(Lifecycle.State.CREATED, session.lifecycle.currentState)
            assertFalse(session.cameraRunning)
            session.close()
            session.close()
            assertEquals(1, closed)
            session.dispose()
            assertEquals(Lifecycle.State.DESTROYED, session.lifecycle.currentState)
        }
    }

    @Test fun deniedPermissionStaysRecoverableAndAlbumCancellationDoesNotStartCamera() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val session = ScanSession(ApplicationProvider.getApplicationContext(), {}, {})
            session.update(true, false)
            assertEquals(ScanFailure.Permission, session.failure)
            assertTrue(session.beginAlbum())
            assertFalse(session.beginAlbum())
            session.album(null)
            assertFalse(session.cameraRunning)
            assertEquals(ScanFailure.Permission, session.failure)
            session.update(false, true)
            assertNull(session.failure)
            session.dispose()
        }
    }
}

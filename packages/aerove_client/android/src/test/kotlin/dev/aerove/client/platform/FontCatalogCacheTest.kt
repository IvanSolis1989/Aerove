package dev.aerove.client.platform

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class FontCatalogCacheTest {
    @Test fun invalidationCannotPublishAnOldInFlightScan() {
        val cache = FontCatalogCache()
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                cache.read {
                    started.countDown()
                    check(resume.await(2, TimeUnit.SECONDS))
                    listOf("old")
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        cache.invalidate()
        resume.countDown()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertEquals(listOf("new"), cache.read { listOf("new") })
        assertEquals(listOf("new"), cache.read { error("cached read must not scan") })
    }

    @Test fun aSupersededScanStopsAtItsNextBoundary() {
        val cache = FontCatalogCache()
        var scanned = 0
        assertThrows(CancellationException::class.java) {
            cache.read { checkpoint ->
                for (font in 0 until 1024) {
                    checkpoint()
                    scanned++
                    if (font == 2) cache.invalidate()
                }
                listOf("stale")
            }
        }
        assertEquals(3, scanned)
        assertEquals(listOf("replacement"), cache.read { listOf("replacement") })
    }

    @Test fun engineDetachClosesTheOwnerAndCancelsItsScan() {
        val cache = FontCatalogCache()
        assertThrows(CancellationException::class.java) {
            cache.read { checkpoint ->
                cache.close()
                checkpoint()
                error("detached scan must not continue")
            }
        }
        assertThrows(CancellationException::class.java) { cache.read { listOf("late") } }
    }

    @Test fun aBilingualFallbackCannotStandInForTheDeclaredPrimary() {
        val declared = File("system/fonts/Primary.ttf") to 0
        assertFalse(sameFontFace(declared, File("system/fonts/BilingualFallback.ttf") to 0))
        assertFalse(sameFontFace(declared, declared.first to 1))
        assertTrue(sameFontFace(declared, declared))
    }
}

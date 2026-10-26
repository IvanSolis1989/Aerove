package dev.aerove.lite.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The production JniResourceFetch.preparedValidator composition with fake JNI edges; no native library is loaded. */
class PreparedResourceValidatorTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val outside = TemporaryFolder()
    private fun generation(name: String) = File(temporary.root, "runtime/resources/bundles/profile/$name").apply { check(mkdirs()) }.canonicalFile

    private class Recording(private val result: String?) : OfflineResourceNative {
        val calls = mutableListOf<String>()
        @Volatile var cache: File? = null
        override fun create(plan: String): Long { calls += "create offline=" + JSONObject(plan).getBoolean("offline"); return 71 }
        override fun cancel(handle: Long) { calls += "cancel" }
        override fun closeJoin(handle: Long) { assertEquals(71L, handle); calls += "join cache=" + cache!!.isDirectory }
        override fun validatePrepared(handle: Long, candidate: String, readRoot: String, cacheRoot: String): String? {
            assertEquals(71L, handle); cache = File(cacheRoot); calls += "validate cache=" + cache!!.isDirectory; return result
        }
    }

    @Test fun validAndDiagnosticResultsReturnAfterJoinAndThenRemoveTheCache() = runBlocking<Unit> {
        val cacheRoot = temporary.newFolder("cache")
        for ((index, result) in listOf(null, "\$.rules[0]: native fixture rejection").withIndex()) {
            val native = Recording(result)
            val actual = preparedResourceValidator(temporary.root, cacheRoot, native).validate("rules: ['MATCH,DIRECT']\n", generation("g$index"))
            assertEquals(result, actual)
            assertEquals(listOf("create offline=true", "validate cache=true", "join cache=true"), native.calls)
            assertEquals(cacheRoot.canonicalFile, native.cache!!.parentFile); assertFalse(native.cache!!.exists())
        }
        assertEquals(0, cacheRoot.listFiles()!!.size)
    }

    @Test fun unsealedOrForeignRootsAreRejectedBeforeAnyNativeOwnerOrCacheExists() = runBlocking<Unit> {
        val cacheRoot = temporary.newFolder("cache"); val native = Recording(null)
        val validator = preparedResourceValidator(temporary.root, cacheRoot, native)
        val bundles = File(temporary.root, "runtime/resources/bundles").apply { check(mkdirs()) }.canonicalFile
        val unsealed = File(temporary.root, "runtime/resources/imports").apply { check(mkdirs()) }.canonicalFile
        for (root in listOf(bundles, unsealed, outside.newFolder("foreign"))) {
            assertTrue(runCatching { validator.validate("{}", root) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(native.calls.isEmpty()); assertEquals(0, cacheRoot.listFiles()!!.size)
    }

    @Test fun cancellationJoinsTheConversionBeforeTheNonCancellableCacheRemoval() = runBlocking<Unit> {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1); val exit = CountDownLatch(1)
        val exited = AtomicBoolean(); val joined = AtomicBoolean(); val cacheAtJoin = AtomicBoolean()
        val cache = java.util.concurrent.atomic.AtomicReference<File>()
        val native = object : OfflineResourceNative {
            override fun create(plan: String) = 72L
            override fun cancel(handle: Long) { assertEquals(72L, handle); cancelled.countDown() }
            override fun closeJoin(handle: Long) { assertTrue(exited.get()); cacheAtJoin.set(cache.get().isDirectory); joined.set(true) }
            override fun validatePrepared(handle: Long, candidate: String, readRoot: String, cacheRoot: String): String? {
                cache.set(File(cacheRoot)); entered.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS) && exit.await(5, TimeUnit.SECONDS))
                exited.set(true); return "stale result"
            }
        }
        val validator = preparedResourceValidator(temporary.root, temporary.newFolder("cache"), native)
        var published = false
        val job = launch(Dispatchers.Default) { validator.validate("{}", generation("cancel")); published = true }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            job.cancel(); assertTrue(withContext(Dispatchers.IO) { cancelled.await(5, TimeUnit.SECONDS) })
            assertFalse(job.isCompleted); assertFalse(joined.get()); assertTrue(cache.get().isDirectory)
        } finally { exit.countDown(); withTimeout(5000) { job.join() } }
        assertTrue(job.isCancelled); assertFalse(published)
        assertTrue(joined.get()); assertTrue(cacheAtJoin.get()); assertFalse(cache.get().exists())
    }
}

package dev.aerove.lite.data

import dev.aerove.lite.*
import dev.aerove.lite.integration.RouteModeIntent
import dev.aerove.lite.integration.RouteModeRecovery
import dev.aerove.lite.vpn.*
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouteModeFailureTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Fixture(root: File) : AutoCloseable {
        val server = MockWebServer()
        val actual = AtomicReference(ProxyMode.Rule)
        val requests = AtomicInteger()
        var answer: (ProxyMode, Int) -> Int = { _, _ -> 204 }
        val store = LiteSettingsStore(root)
        val owner = RouteModeIntent()
        val available = AtomicBoolean(true)
        val shown = AtomicReference(LiteSettings())
        var write: suspend (LiteSettings) -> LiteSettings = { store.save(it) }
        val updates = ControllerSettingsUpdates(store::read, { write(it) }, { store.read() }, { store.read() }, shown::get,
            { saved, _ -> shown.set(saved) })
        val core: CoreApiClient
        val endpoint: CoreEndpoint
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val requested = JSONObject(request.body.readUtf8()).getString("mode")
                    val mode = ProxyMode.entries.single { it.name.lowercase() == requested }
                    val status = answer(mode, requests.incrementAndGet())
                    if (status == 204) actual.set(mode)
                    return MockResponse().setResponseCode(status)
                }
            }
            server.start()
            endpoint = CoreEndpoint(server.port, "0123456789abcdef0123456789abcdef")
            core = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret))
        }
        fun recovery(token: Long, stop: suspend (CoreEndpoint, String) -> Boolean = { _, _ -> error("Unexpected stop") }) =
            RouteModeRecovery(endpoint, updates::savedForModeRecovery, { true },
                { force -> if (force || owner.current(token)) updates.reload() }, stop)
        suspend fun apply(token: Long, mode: ProxyMode, patch: suspend () -> Unit = { core.setMode(mode) },
            stop: suspend (CoreEndpoint, String) -> Boolean = { _, _ -> error("Unexpected stop") }): Boolean =
            owner.apply(token, available::get, patch, {
                updates.save(LiteSettings(), LiteSettings(mode = mode), { owner.current(token) }, explicitMode = true)
            }, { recovery(token, stop).recover(it) })
        suspend fun assertMode(mode: ProxyMode) {
            assertEquals(mode, actual.get()); assertEquals(mode, store.read().mode); assertEquals(mode, shown.get().mode)
        }
        override fun close() { core.close(); server.shutdown() }
    }

    @Test fun patchSuccessThenForegroundClientClosesCompensatesWithFreshOwnedClient() = runBlocking<Unit> {
        Fixture(temporary.newFolder()).use { f ->
            f.store.save(LiteSettings()); val token = checkNotNull(f.owner.reserve(true))
            val result = runCatching { f.apply(token, ProxyMode.Global, {
                f.core.setMode(ProxyMode.Global); f.available.set(false); f.core.close()
            }) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            f.assertMode(ProxyMode.Rule); assertEquals(2, f.requests.get())
        }
    }

    @Test fun persistFailuresBeforeAndAfterActualAtomicSaveRecoverFromDiskRatherThanOldUi() = runBlocking<Unit> {
        for (afterWrite in listOf(false, true)) Fixture(temporary.newFolder()).use { f ->
            f.store.save(LiteSettings())
            f.write = { value -> if (afterWrite) f.store.save(value); throw IOException("Injected settings persistence failure") }
            val result = runCatching { f.apply(checkNotNull(f.owner.reserve(true)), ProxyMode.Global) }
            assertTrue(result.exceptionOrNull() is IOException)
            f.assertMode(if (afterWrite) ProxyMode.Global else ProxyMode.Rule)
            assertEquals(2, f.requests.get())
        }
    }

    @Test fun heldRealSaveCannotEraseReturnToAAndConcurrentUnrelatedPreference() = runBlocking<Unit> {
        Fixture(temporary.newFolder()).use { f ->
            f.store.save(LiteSettings()); val saved = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.write = { value -> f.store.save(value).also {
                if (value.mode == ProxyMode.Global) withContext(NonCancellable) { saved.complete(Unit); release.await() }
            } }
            val b = checkNotNull(f.owner.reserve(true))
            val old = async { runCatching { f.apply(b, ProxyMode.Global) } }
            try {
                withTimeout(3000) { saved.await() }
                assertEquals(ProxyMode.Global, f.store.read().mode); assertEquals(ProxyMode.Rule, f.shown.get().mode)
                val a = checkNotNull(f.owner.reserve(false))
                val latest = async { f.apply(a, ProxyMode.Rule) }
                val otherPreference = async { f.updates.update(restart = false) { it.copy(pureBlack = true) } }
                delay(50); assertFalse(latest.isCompleted); release.complete(Unit)
                assertTrue(withTimeout(4000) { old.await() }.exceptionOrNull() is CancellationException)
                assertTrue(withTimeout(4000) { latest.await() }); otherPreference.await()
                f.assertMode(ProxyMode.Rule); assertTrue(f.store.read().pureBlack); assertTrue(f.shown.get().pureBlack)
            } finally { release.complete(Unit); old.cancelAndJoin() }
        }
    }

    @Test fun failedReturnToAPatchStillEndsAtTheActualSavedMode() = runBlocking<Unit> {
        Fixture(temporary.newFolder()).use { f ->
            f.store.save(LiteSettings()); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val b = checkNotNull(f.owner.reserve(true))
            val old = async { f.apply(b, ProxyMode.Global, { f.core.setMode(ProxyMode.Global); entered.complete(Unit); release.await() }) }
            try {
                withTimeout(3000) { entered.await() }
                val a = checkNotNull(f.owner.reserve(false)); val aRequests = AtomicInteger()
                f.answer = { mode, _ -> if (mode == ProxyMode.Rule && aRequests.incrementAndGet() == 2) 500 else 204 }
                val latest = async { runCatching { f.apply(a, ProxyMode.Rule) } }
                release.complete(Unit); assertFalse(withTimeout(4000) { old.await() })
                assertTrue(withTimeout(4000) { latest.await() }.exceptionOrNull() is IOException)
                f.assertMode(ProxyMode.Rule); assertEquals(4, f.requests.get()); assertEquals(3, aRequests.get())
            } finally { release.complete(Unit); old.cancelAndJoin() }
        }
    }

    @Test fun failedCompensationStopsOnlyCapturedRuntimeAndWaitsForItsActualJoin() = runBlocking<Unit> {
        Fixture(temporary.newFolder()).use { f ->
            f.store.save(LiteSettings()); f.answer = { mode, _ -> if (mode == ProxyMode.Rule) 500 else 204 }
            f.write = { throw IOException("Injected persistence failure") }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val stopping = CompletableDeferred<Unit>(); val joined = CompletableDeferred<Unit>()
            val coordinator = VpnCoordinator(scope, TunnelBackend { object : TunnelSession {
                override val endpoint = f.endpoint
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() { stopping.complete(Unit); joined.await() }
            } })
            try {
                coordinator.start(VpnPlan("profile", LiteSettings()))
                withTimeout(3000) { while (coordinator.state.value.phase != VpnPhase.Running) delay(5) }
                val task = async { runCatching { f.apply(checkNotNull(f.owner.reserve(true)), ProxyMode.Global, stop = coordinator::stopIfCurrent) } }
                withTimeout(3000) { stopping.await() }; assertFalse(task.isCompleted)
                assertEquals(VpnPhase.Stopping, coordinator.state.value.phase)
                joined.complete(Unit); assertTrue(withTimeout(4000) { task.await() }.isFailure)
                withTimeout(3000) { while (coordinator.state.value.phase != VpnPhase.Stopped) delay(5) }
                assertTrue(coordinator.state.value.error!!.contains("mode recovery failed"))
                assertEquals(ProxyMode.Rule, f.store.read().mode); assertEquals(ProxyMode.Rule, f.shown.get().mode)
            } finally { joined.complete(Unit); coordinator.close(); scope.cancel() }
        }
    }
}

package dev.aerove.lite

import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.data.atomicDomainRestore
import dev.aerove.lite.localization.*
import java.io.IOException
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControllerSettingsRestoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Owner(val store: LiteSettingsStore, var shown: LiteSettings, val beforeSave: suspend () -> Unit = {}) {
        var automaticWrites = 0
        val updates = ControllerSettingsUpdates(store::read, { beforeSave(); store.save(it) },
            { automaticWrites++; store.rotateAuroraForDay(it) }, { automaticWrites++; store.shuffleAuroraWallpaper() },
            { shown }, { value, _ -> shown = value })
    }

    @Test fun acceptedWriteFinishesBeforeRestoreAndOlderQueuedAndDuringRestoreSavesCannotOverwriteIt() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(locale = "en", aurora = true))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var saves = 0
        val owner = Owner(store, initial) { if (++saves == 1) { entered.complete(Unit); release.await() } }
        owner.updates.load()
        val accepted = async(start = CoroutineStart.UNDISPATCHED) { owner.updates.save(initial, initial.copy(locale = "fr")) }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { owner.updates.save(initial, initial.copy(locale = "ja")) }
        val queuedShuffle = async(start = CoroutineStart.UNDISPATCHED) { owner.updates.shuffle() }
        val now = ZonedDateTime.parse("2026-10-23T05:00+09:00[Asia/Tokyo]")
        val queuedRotation = async(start = CoroutineStart.UNDISPATCHED) { owner.updates.rotate(now) { true } }
        val restored = async(start = CoroutineStart.UNDISPATCHED) {
            owner.updates.restore({ atomicDomainRestore(root) { store.save(initial) } }) {
                assertEquals("en", owner.shown.locale)
                assertEquals("en", LiteSettingsStore(root).read().locale)
            }
        }
        owner.updates.save(initial, initial.copy(locale = "ko"))
        owner.updates.shuffle(); owner.updates.rotate(now) { true }
        release.complete(Unit)
        accepted.await(); queued.await(); queuedShuffle.await(); queuedRotation.await(); restored.await()
        assertEquals(1, saves)
        assertEquals(0, owner.automaticWrites)
        assertEquals("en", LiteSettingsStore(root).read().locale)
        owner.updates.save(owner.shown, owner.shown.copy(locale = "es", pureBlack = true))
        assertEquals("es", LiteSettingsStore(root).read().locale)
        assertTrue(LiteSettingsStore(root).read().pureBlack)
    }

    @Test fun restoreBeforeAtomicAcceptanceStillCancelsTheOlderSave() = runBlocking<Unit> {
        val root = temporary.newFolder(); val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(locale = "en"))
        val reading = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var shown = initial; var saves = 0
        val updates = ControllerSettingsUpdates({ reading.complete(Unit); release.await(); store.read() },
            { saves++; store.save(it) }, store::rotateAuroraForDay, store::shuffleAuroraWallpaper,
            { shown }, { value, _ -> shown = value })
        val older = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { updates.save(initial, initial.copy(locale = "fr")) }
        }
        reading.await()
        val restored = async(start = CoroutineStart.UNDISPATCHED) {
            updates.restore({ atomicDomainRestore(root) { store.save(initial) } }) {}
        }
        release.complete(Unit)
        assertTrue(older.await().exceptionOrNull() is CancellationException)
        restored.await()
        assertEquals(0, saves)
        assertEquals("en", LiteSettingsStore(root).read().locale)
        assertEquals(initial, shown)
    }

    @Test fun acceptedExplicitModeStillRejectsAnIntentSupersededByRestore() = runBlocking<Unit> {
        val root = temporary.newFolder(); val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(mode = ProxyMode.Rule))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = Owner(store, initial) { entered.complete(Unit); release.await() }
        owner.updates.load()
        val accepted = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { owner.updates.save(initial, initial.copy(mode = ProxyMode.Global), explicitMode = true) }
        }
        entered.await()
        val restored = async(start = CoroutineStart.UNDISPATCHED) {
            owner.updates.restore({
                assertEquals(ProxyMode.Global, LiteSettingsStore(root).read().mode)
                assertEquals(ProxyMode.Rule, owner.shown.mode)
                atomicDomainRestore(root) { store.save(initial) }
            }) {}
        }
        release.complete(Unit)
        assertTrue(accepted.await().exceptionOrNull() is CancellationException)
        restored.await()
        assertEquals(ProxyMode.Rule, LiteSettingsStore(root).read().mode)
        assertEquals(ProxyMode.Rule, owner.shown.mode)
    }

    @Test fun cancellingAcceptedOrdinarySaveKeepsCallerCancellationAndFinishesAtomicWrite() = runBlocking<Unit> {
        val root = temporary.newFolder(); val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(locale = "en"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = Owner(store, initial) { entered.complete(Unit); release.await() }
        owner.updates.load()
        val accepted = async(start = CoroutineStart.UNDISPATCHED) {
            owner.updates.save(initial, initial.copy(locale = "fr"))
        }
        entered.await(); accepted.cancel(CancellationException("caller-cancelled")); release.complete(Unit)
        accepted.join()
        assertTrue(accepted.isCancelled)
        val failure = runCatching { accepted.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("caller-cancelled", failure?.message)
        assertEquals("fr", LiteSettingsStore(root).read().locale)
        owner.updates.restore({ atomicDomainRestore(root) { store.save(initial) } }) {}
        owner.updates.save(owner.shown, owner.shown.copy(locale = "es"))
        assertEquals("es", LiteSettingsStore(root).read().locale)
    }

    @Test fun sameValueBackupRestoreWinsOverOlderOsCallbackWhileDomainIsHeld() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(locale = "en"))
        val owner = Owner(store, initial); owner.updates.load()
        var native: String? = "en"
        var applied = AppliedLocale("en")
        val preference = MutableStateFlow<LocalePreference?>(LocalePreference("en"))
        val localeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val callback = CompletableDeferred<Unit>()
        val coordinator = LocaleCoordinator(preference, localeScope, object : LocalePlatform {
            override fun selected() = native
            override fun apply(tag: String?) { native = tag }
        }, object : LocaleAppliedCache {
            override suspend fun read() = applied
            override suspend fun write(choice: AppliedLocale) { applied = choice }
        }, { tag ->
            val baseline = owner.shown
            owner.updates.save(baseline, baseline.copy(locale = tag))
            callback.complete(Unit)
        }, { throw it }, latestPreference = { store.changes.value?.let { LocalePreference(it.locale) } })
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            native = "fr"
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                owner.updates.restore({ atomicDomainRestore(root) {
                    store.save(initial); held.complete(Unit); release.await()
                } }) { coordinator.restoreSaved() }
            }
            held.await()
            coordinator.foreground(); callback.await()
            release.complete(Unit); restore.await()
            assertEquals("en", LiteSettingsStore(root).read().locale)
            assertEquals("en", native)
            assertEquals(AppliedLocale("en"), applied)
        } finally { release.complete(Unit); coordinator.close(); localeScope.cancel() }
    }

    @Test fun failedAtomicRestoreRollsBackAndRepublishesActualStoreWithoutMaskingTheFailure() = runBlocking<Unit> {
        val root = temporary.newFolder(); val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(locale = "en")); val owner = Owner(store, initial)
        owner.updates.load()
        val failure = IOException("restore-fixture")
        var hook = false
        val error = runCatching { owner.updates.restore({ atomicDomainRestore(root) {
            store.save(initial.copy(locale = "ru")); throw failure
        } }) { hook = true } }.exceptionOrNull()
        assertEquals(failure.javaClass, error?.javaClass)
        assertEquals(failure.message, error?.message)
        // Debug stack recovery copies IOException and keeps the original as its cause.
        val original = if (error?.stackTrace?.any { it.className == "_COROUTINE._BOUNDARY" } == true)
            error.cause else error
        assertSame(failure, original); assertFalse(hook)
        assertEquals("en", LiteSettingsStore(root).read().locale)
        assertEquals("en", store.changes.value!!.locale)
        assertEquals("en", owner.shown.locale)
        owner.updates.save(owner.shown, owner.shown.copy(locale = "ko"))
        assertEquals("ko", LiteSettingsStore(root).read().locale)
    }

    @Test fun cancelledAtomicRestoreRollsBackAndReleasesTheOwnerForNewIntent() = runBlocking<Unit> {
        val root = temporary.newFolder(); val store = LiteSettingsStore(root)
        val initial = store.save(LiteSettings(locale = "en")); val owner = Owner(store, initial)
        owner.updates.load()
        val written = CompletableDeferred<Unit>()
        val restoring = launch { owner.updates.restore({ atomicDomainRestore(root) {
            store.save(initial.copy(locale = "ru")); written.complete(Unit); awaitCancellation()
        } }) { error("Cancelled restore cannot refresh") } }
        written.await(); restoring.cancelAndJoin()
        assertEquals("en", LiteSettingsStore(root).read().locale)
        assertEquals("en", store.changes.value!!.locale)
        owner.updates.save(owner.shown, owner.shown.copy(locale = "ja"))
        assertEquals("ja", LiteSettingsStore(root).read().locale)
    }
}

package dev.aerove.lite.localization

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LocaleCoordinatorTest {
    private class Platform(var tag: String?) : LocalePlatform {
        val writes = mutableListOf<String?>()
        var reads = 0
        override fun selected(): String? { reads++; return tag }
        override fun apply(tag: String?) { writes += tag; this.tag = tag }
    }
    private class Cache(var value: AppliedLocale?) : LocaleAppliedCache {
        var reads = 0
        val writes = mutableListOf<AppliedLocale>()
        override suspend fun read(): AppliedLocale? { reads++; return value }
        override suspend fun write(choice: AppliedLocale) { value = choice; writes += choice }
    }
    private class Fixture(initial: LocalePreference?, native: String?, last: AppliedLocale?) : AutoCloseable {
        val saved = MutableStateFlow(initial)
        val platform = Platform(native)
        val cache = Cache(last)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val platformChoices = mutableListOf<String?>()
        val failures = mutableListOf<Exception>()
        val coordinator = LocaleCoordinator(saved, scope, platform, cache, {
            platformChoices += it
            saved.value = (saved.value ?: error("Not loaded")).copy(tag = it)
        }, failures::add)
        override fun close() { coordinator.close(); scope.cancel() }
    }
    @Test fun supportedTagsMatchAllSevenStandardLocalesAndCanonicalizeVariants() {
        assertEquals(listOf("en", "es", "fr", "ja", "ko", "ru", "zh_CN"), LocaleChoice.entries.map { it.tag })
        assertEquals("en", canonicalLocale("en-US"))
        assertEquals("es", canonicalLocale("es_MX"))
        for (tag in listOf("zh", "zh_CN", "zh-Hans-CN", "zh-Hant-TW", "zh_TW")) {
            assertEquals("zh_CN", canonicalLocale(tag)); assertEquals("zh-CN", localePlatformTag(tag))
        }
        assertNull(canonicalLocale(null)); assertNull(canonicalLocale("de")); assertNull(canonicalLocale("en,fr"))
    }
    @Test fun noNativeOrCacheAccessBeforeRealLoadAndNoDefaultOverwritesOs() {
        Fixture(null, "fr", null).use { f ->
            f.coordinator.foreground()
            assertEquals(0, f.platform.reads); assertEquals(0, f.cache.reads)
            f.saved.value = LocalePreference(null)
            assertEquals(listOf("fr"), f.platformChoices)
            assertEquals("fr", f.saved.value!!.tag)
            assertTrue(f.platform.writes.isEmpty())
        }
    }
    @Test fun coldStartPreservesExternalOsChoiceInsteadOfReapplyingOldSavedValue() {
        Fixture(LocalePreference(tag = "en"), "ja", AppliedLocale("en")).use { f ->
            assertEquals(listOf("ja"), f.platformChoices)
            assertEquals("ja", f.saved.value!!.tag)
            repeat(4) { f.coordinator.foreground() }
            assertEquals(listOf("ja"), f.platformChoices)
            assertTrue(f.platform.writes.isEmpty())
        }
    }
    @Test fun restoredSettingsOverrideOldAppliedCacheAndSameLocaleChangesDoNotRecreate() {
        Fixture(LocalePreference(tag = "es"), "en", AppliedLocale("en")).use { f ->
            assertEquals(listOf("es"), f.platform.writes)
            f.saved.value = f.saved.value!!.copy(tag = "es")
            repeat(4) { f.coordinator.foreground() }
            assertEquals(listOf("es"), f.platform.writes)
            assertTrue(f.platformChoices.isEmpty())
            f.saved.value = f.saved.value!!.copy(tag = "ru")
            assertEquals(listOf("es", "ru"), f.platform.writes)
        }
    }
    @Test fun liveExternalChangeAndExternalSystemChoiceAreSavedThroughTheSingleOwner() {
        Fixture(LocalePreference(tag = "en"), "en", AppliedLocale("en")).use { f ->
            f.platform.tag = "ko"; f.coordinator.foreground()
            assertEquals("ko", f.saved.value!!.tag)
            f.platform.tag = null; f.coordinator.foreground()
            assertNull(f.saved.value!!.tag)
            assertEquals(listOf("ko", null), f.platformChoices)
            assertTrue(f.platform.writes.isEmpty())
        }
    }
    @Test fun manualSystemChoiceClearsApplicationLocalesOnlyOnceAndChineseVariantsActuallyNormalizeNative() {
        Fixture(LocalePreference(tag = "zh_CN"), "zh-Hant-TW", AppliedLocale("zh_CN")).use { f ->
            assertEquals(listOf("zh-CN"), f.platform.writes)
            f.saved.value = f.saved.value!!.copy(tag = null)
            repeat(4) { f.coordinator.foreground() }
            assertEquals(listOf("zh-CN", null), f.platform.writes)
            assertEquals(AppliedLocale(null), f.cache.value)
        }
    }
    @Test fun failedExternalSaveDoesNotChangeCacheOrPretendItSucceeded() {
        val saved = MutableStateFlow<LocalePreference?>(LocalePreference(tag = "en"))
        val platform = Platform("fr"); val cache = Cache(AppliedLocale("en"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val failures = mutableListOf<Exception>()
        val coordinator = LocaleCoordinator(saved, scope, platform, cache, { error("disk failed") }, failures::add)
        try {
            assertEquals(1, failures.size)
            assertEquals(AppliedLocale("en"), cache.value)
            assertEquals("en", saved.value!!.tag)
            assertTrue(platform.writes.isEmpty())
        } finally { coordinator.close(); scope.cancel() }
    }
    @Test fun closeCancelsSuspendedExternalSaveAndRemovesAllFurtherWork() {
        val saved = MutableStateFlow<LocalePreference?>(LocalePreference(tag = "en"))
        val platform = Platform("fr"); val cache = Cache(AppliedLocale("en"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var cancelled = false
        val coordinator = LocaleCoordinator(saved, scope, platform, cache, {
            try { awaitCancellation() } finally { cancelled = true }
        }, { throw it })
        coordinator.close()
        assertTrue(cancelled)
        saved.value = LocalePreference(tag = "ru"); coordinator.foreground()
        assertEquals(AppliedLocale("en"), cache.value); assertTrue(platform.writes.isEmpty())
        scope.cancel()
    }
    @Test fun newerExternalChoiceDuringSuspendedSaveIsNotOverwritten() {
        val saved = MutableStateFlow<LocalePreference?>(LocalePreference(tag = "en"))
        val platform = Platform("en"); val cache = Cache(AppliedLocale("en"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val choices = mutableListOf<String?>()
        val coordinator = LocaleCoordinator(saved, scope, platform, cache, {
            choices += it
            if (it == "fr") release.await()
            saved.value = saved.value!!.copy(tag = it)
        }, { throw it })
        try {
            platform.tag = "fr"; coordinator.foreground()
            platform.tag = "ru"; coordinator.foreground()
            release.complete(Unit)
            assertEquals(listOf("fr", "ru"), choices)
            assertEquals("ru", saved.value!!.tag)
            assertTrue(platform.writes.isEmpty())
        } finally { coordinator.close(); scope.cancel() }
    }

    @Test fun explicitSameValueRestoreWinsOverAnUnacknowledgedExternalChoice() {
        Fixture(LocalePreference("en"), "en", AppliedLocale("en")).use { f ->
            f.platform.tag = "fr"
            f.coordinator.restoreSaved()
            assertEquals(listOf("en"), f.platform.writes)
            assertTrue(f.platformChoices.isEmpty())
            repeat(4) { f.coordinator.foreground() }
            assertEquals(listOf("en"), f.platform.writes)
        }
    }
    @Test fun explicitRestoreBeforeLoadWaitsForRealSavedState() {
        Fixture(null, "fr", AppliedLocale("en")).use { f ->
            f.coordinator.restoreSaved()
            assertTrue(f.platform.writes.isEmpty())
            f.saved.value = LocalePreference("en")
            assertEquals(listOf("en"), f.platform.writes)
            assertTrue(f.platformChoices.isEmpty())
        }
    }

}

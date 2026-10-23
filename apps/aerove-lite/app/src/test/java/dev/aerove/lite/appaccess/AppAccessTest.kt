package dev.aerove.lite.appaccess

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private val applications = listOf(
        InstalledApplication("org.zeta", "Zeta", false, true, 10),
        InstalledApplication("com.tencent.app", "Alpha", false, true, 20),
        InstalledApplication("org.system", "System", true, true, 40),
        InstalledApplication("org.offline", "Offline", false, false, 30),
    )
    private class Loader(var applications: List<InstalledApplication>) : ApplicationLoader {
        var pending: CompletableDeferred<Unit>? = null
        var loads = 0
        var cancelled = false
        var changed: (() -> Unit)? = null
        var closed = false
        var smartPending: CompletableDeferred<Unit>? = null
        var smartCancelled = false
        override suspend fun load(): InstalledApplicationSnapshot {
            loads++
            try { pending?.await() } catch (cancelled: CancellationException) { this.cancelled = true; throw cancelled }
            return InstalledApplicationSnapshot(applications, true)
        }
        override suspend fun chinaPackages(applications: List<InstalledApplication>): Set<String> {
            try { smartPending?.await() } catch (cancelled: CancellationException) { smartCancelled = true; throw cancelled }
            return applications.filter { StandardChinaPackageMatcher.matchesKnownPrefix(it.packageName) }.map { it.packageName }.toSet()
        }
        override fun observeChanges(changed: () -> Unit): AutoCloseable { this.changed = changed; return AutoCloseable { this.changed = null } }
        override fun close() { closed = true }
    }

    @Test fun independentSelectionsAndDisplayPreferencesPersistWithStrictCodec() {
        val preferences = AppAccessPreferences(true, AppAccessMode.Include, setOf("org.zeta"), setOf("com.tencent.app"), AppAccessSort.Time, false, true)
        val file = temporary.newFile("access.json").apply { writeText(AppAccessPreferencesJson.encode(preferences).toString()) }
        assertEquals(preferences, AppAccessPreferencesJson.decode(JSONObject(file.readText())))
        listOf("{\"enabled\":\"false\"}", "{\"includePackages\":[1]}", "{\"excludePackages\":false}", "{\"extra\":1}", "{\"sort\":null}").forEach {
            assertThrows(Exception::class.java) { AppAccessPreferencesJson.decode(JSONObject(it)) }
        }
    }

    @Test fun sourceFiltersAndOnlyActiveSelectionNormalizeAtSaveNotSearch() {
        val preferences = AppAccessPreferences(true, AppAccessMode.Include,
            setOf("org.zeta", "org.system", "org.offline", "org.uninstalled"), setOf("org.system", "org.uninstalled"))
        val normalized = preferences.forSave(applications)
        assertEquals(setOf("org.zeta"), normalized.includePackages)
        assertEquals(preferences.excludePackages, normalized.excludePackages)
        assertEquals(preferences, preferences.forSave(emptyList()))
        assertEquals(listOf("com.tencent.app"), viewApplications(applications, preferences, emptySet(), "ALPHA tencent").map { it.packageName })
    }

    @Test fun pinnedOrderIsStableAndTimeSortUsesActualLastUpdateNotInstallTime() {
        val prefs = AppAccessPreferences(hideSystem = false, hideNoInternet = false)
        assertEquals(listOf("org.zeta", "com.tencent.app", "org.system", "org.offline"), viewApplications(applications, prefs, emptySet(), "").map { it.packageName })
        assertEquals("org.zeta", viewApplications(applications, prefs.copy(sort = AppAccessSort.Name), setOf("org.zeta"), "").first().packageName)
        assertEquals(listOf("org.system", "org.offline", "com.tencent.app", "org.zeta"), viewApplications(applications, prefs.copy(sort = AppAccessSort.Time), emptySet(), "").map { it.packageName })
    }

    @Test fun draftCancelClipboardSmartAndSavedReadbackDoNotWriteUntilSave() = runBlocking<Unit> {
        val loader = Loader(applications)
        val file = temporary.newFile("settings.json")
        var saves = 0
        val feature = AppAccessFeature(this, AppAccessPreferences(), loader) { saves++; file.writeText(AppAccessPreferencesJson.encode(it).toString()) }
        assertEquals(0, loader.loads)
        feature.setForeground(true); yield()
        feature.change(feature.state.value.draft.copy(enabled = true, mode = AppAccessMode.Include))
        feature.toggle("org.zeta")
        feature.change(feature.state.value.draft.copy(mode = AppAccessMode.Exclude))
        feature.toggle("com.tencent.app")
        assertEquals(setOf("org.zeta"), feature.state.value.draft.includePackages)
        assertEquals(0, saves)
        feature.resetDraft()
        assertFalse(feature.state.value.dirty)
        feature.intelligentSelect(); yield()
        assertEquals(setOf("com.tencent.app"), feature.state.value.draft.excludePackages)
        assertEquals(setOf("org.zeta", "org.system", "org.offline"), feature.state.value.draft.includePackages)
        feature.importClipboard("org.zeta\r\norg.zeta\ncom.tencent.app\n")
        assertEquals(setOf("org.zeta", "com.tencent.app"), feature.state.value.draft.selected)
        feature.save(); yield()
        assertEquals(1, saves)
        assertEquals(feature.state.value.saved, AppAccessPreferencesJson.decode(JSONObject(file.readText())))
        assertFalse(feature.state.value.dirty)
        feature.close()
        assertTrue(loader.closed)
    }

    @Test fun backgroundCancelsLoadEventInvalidationCoalescesAndClosedCannotResume() = runBlocking<Unit> {
        val loader = Loader(applications).apply { pending = CompletableDeferred() }
        val feature = AppAccessFeature(this, AppAccessPreferences(), loader) { }
        feature.setForeground(true); yield()
        feature.setForeground(false); yield()
        assertTrue(loader.cancelled)
        assertFalse(feature.state.value.loading)
        loader.changed?.invoke(); yield()
        assertEquals(1, loader.loads)
        loader.pending = null
        feature.setForeground(true); yield()
        assertTrue(feature.state.value.loaded)
        assertEquals(2, loader.loads)
        feature.close()
        assertNull(loader.changed)
        feature.setForeground(true); feature.reload(); yield()
        assertEquals(2, loader.loads)
    }

    @Test fun failureKeepsDraftAndClipboardRejectsUnsafeOrUnboundedInput() = runBlocking<Unit> {
        val feature = AppAccessFeature(this, AppAccessPreferences(), Loader(applications)) { error("private store error") }
        feature.setForeground(true); yield()
        feature.change(feature.state.value.draft.copy(enabled = true))
        feature.toggleVisible(setOf("org.zeta", "com.tencent.app"))
        feature.save(); yield()
        assertEquals(AppAccessNotice.SaveFailed, feature.state.value.notice)
        assertTrue(feature.state.value.dirty)
        feature.importClipboard("https://secret.invalid/?token=secret")
        assertEquals(AppAccessNotice.InvalidClipboard, feature.state.value.notice)
        assertEquals(setOf("org.zeta", "com.tencent.app"), feature.state.value.draft.selected)
        assertThrows(IllegalArgumentException::class.java) { importAccessClipboard("x".repeat(1024 * 1024 + 1)) }
        feature.toggleVisible(setOf("org.zeta"))
        assertEquals(setOf("com.tencent.app"), feature.state.value.draft.selected)
        feature.close()
    }

    @Test fun copiedStandardMatcherRetainsLoosePrefixesSkipBoundariesAndNoCountryGuess() {
        assertTrue(StandardChinaPackageMatcher.matchesKnownPrefix("com.qihoo360.app"))
        assertTrue(StandardChinaPackageMatcher.matchesKnownPrefix("com.aliyun.sdk"))
        assertTrue(StandardChinaPackageMatcher.isSkipped("com.mxtech.videoplayer"))
        assertFalse(StandardChinaPackageMatcher.isSkipped("com.googleish.app"))
        assertFalse(StandardChinaPackageMatcher.matchesKnownPrefix("cn.unrelated.app"))
        assertEquals("com.tencent.App.Inner", StandardChinaPackageMatcher.classNameOf("Lcom/tencent/App\$Inner;"))
    }

    @Test fun backgroundCancelsSmartWorkWithoutPublishingOrLosingDraftAndCanRetry() = runBlocking<Unit> {
        val loader = Loader(applications).apply { smartPending = CompletableDeferred() }
        val feature = AppAccessFeature(this, AppAccessPreferences(), loader) { }
        feature.setForeground(true); yield()
        feature.toggle("org.zeta")
        val previous = feature.state.value.draft
        feature.intelligentSelect(); yield()
        feature.setForeground(false); yield()
        assertTrue(loader.smartCancelled)
        assertEquals(previous, feature.state.value.draft)
        assertFalse(feature.state.value.classifying)
        loader.smartPending = null
        feature.setForeground(true); feature.intelligentSelect(); yield()
        assertEquals(setOf("com.tencent.app"), feature.state.value.draft.excludePackages)
        feature.close()
    }

    @Test fun menuActionWaitsForCoveredGateToResumeInsteadOfDroppingExplicitSmartTap() = runBlocking<Unit> {
        val feature = AppAccessFeature(this, AppAccessPreferences(), Loader(applications)) { }
        feature.setForeground(true); yield()
        feature.setForeground(false)
        feature.intelligentSelect()
        assertFalse(feature.state.value.classifying)
        assertTrue(feature.state.value.draft.selected.isEmpty())
        feature.setForeground(true); yield()
        assertEquals(setOf("com.tencent.app"), feature.state.value.draft.excludePackages)
        feature.close()
    }

    private fun dex(descriptor: String): ByteArray {
        val text = descriptor.toByteArray()
        val bytes = ByteArray(152 + text.size + 2)
        "dex\n035\u0000".toByteArray().copyInto(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(32, bytes.size); buffer.putInt(36, 112); buffer.putInt(40, 0x12345678)
        buffer.putInt(56, 1); buffer.putInt(60, 112)
        buffer.putInt(64, 1); buffer.putInt(68, 116)
        buffer.putInt(96, 1); buffer.putInt(100, 120)
        buffer.putInt(112, 152); buffer.putInt(116, 0); buffer.putInt(120, 0)
        bytes[152] = descriptor.length.toByte(); text.copyInto(bytes, 153)
        return bytes
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
    }

    @Test fun classifierUsesComponentAndActualZipDexClassEvidenceWithFirebasePriority() {
        val matchingDex = dex("Lcom/tencent/Library;")
        assertTrue(dexMatchesKnownClass(matchingDex))
        assertFalse(dexMatchesKnownClass(dex("Lorg/example/Unrelated;")))
        assertThrows(IllegalArgumentException::class.java) { dexMatchesKnownClass(matchingDex.copyOf(120)) }
        assertEquals(ChinaMatchSource.ComponentPrefix, classifyApplication("org.normal", sequenceOf("com.umeng.Service"), null) { }.source)
        val apk = archive("classes.dex" to matchingDex)
        assertEquals(ChinaMatchSource.DexClass, classifyApplication("org.normal", emptySequence(), apk) { }.source)
        val firebase = archive("firebase-messaging.properties" to byteArrayOf(), "classes.dex" to matchingDex)
        assertEquals(ChinaMatchSource.FirebaseArchive, classifyApplication("org.normal", emptySequence(), firebase) { }.source)
        assertEquals(ChinaMatchSource.Skipped, classifyApplication("com.google.app", sequenceOf("com.umeng.Service"), apk) { }.source)
        val invalidIndex = matchingDex.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(120, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { dexMatchesKnownClass(invalidIndex) }
        assertThrows(CancellationException::class.java) { classifyApplication("org.normal", emptySequence(), apk) { throw CancellationException() } }
    }

    @Test fun oversizedDexPreservesStandardClassificationGuardWithoutParsingOrLoadingCode() {
        val apk = archive("classes2.dex" to ByteArray(MAX_CLASSIFICATION_DEX_BYTES.toInt() + 1))
        assertEquals(ChinaClassification(true, ChinaMatchSource.OversizedDex), classifyApplication("org.normal", emptySequence(), apk) { })
    }
}

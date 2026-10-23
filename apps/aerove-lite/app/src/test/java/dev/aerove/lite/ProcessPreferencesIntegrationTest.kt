package dev.aerove.lite

import dev.aerove.lite.data.LiteSettingsStore
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProcessPreferencesIntegrationTest {
    @Test fun appDefaultAlwaysAndEveryTypedWireSurviveRealStoreReadback() = runBlocking<Unit> {
        val root = Files.createTempDirectory("process-mode").toFile()
        try {
            val store = LiteSettingsStore(root)
            assertEquals(FindProcessMode.Always, store.read().findProcessMode)
            for (mode in FindProcessMode.entries) {
                store.save(LiteSettings(findProcessMode = mode))
                assertEquals(mode, LiteSettingsStore(root).read().findProcessMode)
            }
            val file = root.resolve("settings/settings.json")
            val saved = JSONObject(file.readText())
            file.writeText(JSONObject(saved.toString()).put("findProcessMode", "ALWAYS").toString())
            assertEquals(FindProcessMode.Always, store.read().findProcessMode)
            file.writeText(JSONObject(saved.toString()).apply { remove("findProcessMode") }.toString())
            assertEquals(FindProcessMode.Always, store.read().findProcessMode)
            for (bad in listOf<Any>("default", "unknown", 1, JSONObject.NULL)) {
                file.writeText(JSONObject(saved.toString()).put("findProcessMode", bad).toString())
                try { store.read(); fail("Invalid present wire must be rejected") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun queuedWholeSettingsChangePreservesLatestProcessChoiceThroughSoleOwner() = runBlocking<Unit> {
        val root = Files.createTempDirectory("process-queue").toFile()
        try {
            val store = LiteSettingsStore(root)
            var visible = store.save(LiteSettings())
            val baseline = visible
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var writes = 0
            val owner = ControllerSettingsUpdates(store::read, { value ->
                if (++writes == 1) { entered.complete(Unit); release.await() }
                store.save(value)
            }, { _: ZonedDateTime -> error("Unused wallpaper") }, { error("Unused wallpaper") },
                { visible }, { actual, _ -> visible = actual })
            owner.load()
            val process = launch { owner.save(baseline, baseline.copy(findProcessMode = FindProcessMode.Off)) }
            entered.await()
            val unrelated = launch(start = CoroutineStart.UNDISPATCHED) { owner.save(baseline, baseline.copy(unifiedDelay = false)) }
            release.complete(Unit); joinAll(process, unrelated)
            val actual = LiteSettingsStore(root).read()
            assertEquals(FindProcessMode.Off, actual.findProcessMode)
            assertFalse(actual.unifiedDelay)
            assertEquals(actual, visible)
        } finally { root.deleteRecursively() }
    }
}

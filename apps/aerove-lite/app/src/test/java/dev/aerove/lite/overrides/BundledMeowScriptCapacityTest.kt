package dev.aerove.lite.overrides

import dev.aerove.lite.data.PrivateStorage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BundledMeowScriptCapacityTest {
    private fun bytes() = File(System.getProperty("bundled.script.asset") ?: "src/main/assets/${BundledMeowScript.ASSET}").readBytes()
    private suspend fun nearLimit(root: File): OverrideSnapshot {
        val prefix = "function main(config){return config;}/*"
        val source = prefix + "x".repeat(4_194_000 - prefix.length - 2) + "*/"
        val old = OverrideRepository(root)
        repeat(4) { old.putScript(ScriptResource("u$it", "User $it", content = source, updatedAt = 0)) }
        old.setProfile(ProfileOverride("p", OverrideMode.Script, "u1"))
        val file = File(root, "settings/${OverrideRepository.FILE_NAME}")
        file.writeText(JSONObject(file.readText()).apply { remove("bundledScriptInitialized") }.toString())
        assertTrue(file.length() < OverrideRepository.MAX_LIBRARY_BYTES)
        assertTrue(file.length() + bytes().size > OverrideRepository.MAX_LIBRARY_BYTES)
        return old.snapshot()
    }
    @Test fun legal_near_limit_library_remains_readable_editable_deletable_then_seeds_and_respects_deletion() = runBlocking {
        val root = Files.createTempDirectory("bundled-capacity").toFile()
        val old = nearLimit(root)
        val file = File(root, "settings/${OverrideRepository.FILE_NAME}")
        val original = file.readBytes()
        val loads = AtomicInteger()
        val repo = OverrideRepository(root) { loads.incrementAndGet(); bytes() }
        repeat(3) {
            val pending = repo.snapshot()
            assertEquals(old, pending)
            assertFalse(pending.bundledScriptInitialized)
            assertArrayEquals(original, file.readBytes())
        }
        assertEquals(1, loads.get())
        repo.putScript(old.scripts.first().copy(name = "Renamed"))
        val edited = repo.snapshot()
        assertEquals("Renamed", edited.scripts.first().name)
        assertFalse(edited.bundledScriptInitialized)
        assertEquals(old.profiles, edited.profiles)
        repo.deleteScript("u0")
        val initialized = repo.snapshot()
        assertTrue(initialized.bundledScriptInitialized)
        assertEquals(4, initialized.scripts.size)
        assertArrayEquals(bytes(), initialized.scripts.single { it.id == BundledMeowScript.ID }.content.toByteArray())
        assertEquals(old.scripts.drop(1), initialized.scripts.filter { it.id != BundledMeowScript.ID })
        assertEquals(old.profiles, initialized.profiles)
        assertEquals(1, loads.get())
        repo.deleteScript(BundledMeowScript.ID)
        assertTrue(repo.snapshot().scripts.none { it.id == BundledMeowScript.ID })
        assertTrue(OverrideRepository(root, ::bytes).snapshot().scripts.none { it.id == BundledMeowScript.ID })
        assertTrue(file.length() <= OverrideRepository.MAX_LIBRARY_BYTES)
    }
    @Test fun exactly_full_legacy_record_can_edit_without_false_marker_overhead_then_release_space() = runBlocking {
        val root = Files.createTempDirectory("bundled-exact-capacity").toFile()
        nearLimit(root)
        val old = OverrideRepository(root)
        val file = File(root, "settings/${OverrideRepository.FILE_NAME}")
        // Keep the real update timestamp width stable; growing 0 to a 13-digit timestamp legitimately costs capacity.
        old.putScript(old.snapshot().scripts.first().copy(updatedAt = System.currentTimeMillis()))
        var remaining = OverrideRepository.MAX_LIBRARY_BYTES - file.length().toInt()
        for (script in old.snapshot().scripts) {
            val extra = minOf(remaining, OverrideRepository.MAX_SCRIPT_BYTES - script.byteSize)
            if (extra > 0) old.putScript(script.copy(content = script.content.dropLast(2) + "x".repeat(extra) + "*/"))
            remaining -= extra
        }
        assertEquals(0, remaining)
        assertEquals(OverrideRepository.MAX_LIBRARY_BYTES.toLong(), file.length())
        val repo = OverrideRepository(root, ::bytes)
        assertFalse(repo.snapshot().bundledScriptInitialized)
        val script = repo.snapshot().scripts.first()
        val original = file.readBytes()
        try { repo.putScript(script.copy(name = script.name + "x")); fail() } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, file.readBytes())
        repo.putScript(script.copy(name = "Edit 0"))
        val replacement = script.content.dropLast(3) + "y*/"
        repo.replaceContent(script.id, script.content, replacement)
        assertEquals(replacement, repo.snapshot().scripts.first().content)
        assertFalse(JSONObject(file.readText()).has("bundledScriptInitialized"))
        assertEquals(OverrideRepository.MAX_LIBRARY_BYTES.toLong(), file.length())
        repo.deleteScript(script.id)
        assertTrue(repo.snapshot().bundledScriptInitialized)
        assertEquals(1, repo.snapshot().scripts.count { it.id == BundledMeowScript.ID })
    }
    @Test fun atomic_restore_replacement_retries_pending_seed_without_reloading_asset() = runBlocking {
        val root = Files.createTempDirectory("bundled-capacity-restore").toFile()
        nearLimit(root)
        val loads = AtomicInteger()
        val repo = OverrideRepository(root) { loads.incrementAndGet(); bytes() }
        assertFalse(repo.snapshot().bundledScriptInitialized)
        val restored = OverrideSnapshot(scripts = listOf(ScriptResource("restored", "Restored", content = "function main(c){return c;}")))
        val storage = PrivateStorage(File(root, "settings"))
        storage.transaction { storage.write(OverrideRepository.FILE_NAME, OverrideRepository.encode(restored)) }
        val initialized = repo.snapshot()
        assertTrue(initialized.bundledScriptInitialized)
        assertEquals(restored.scripts.single(), initialized.scripts.first())
        assertEquals(BundledMeowScript.ID, initialized.scripts.last().id)
        assertEquals(1, loads.get())
    }
}

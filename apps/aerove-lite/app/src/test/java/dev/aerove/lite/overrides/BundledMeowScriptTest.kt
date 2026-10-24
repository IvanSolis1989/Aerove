package dev.aerove.lite.overrides

import java.io.File
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import dev.aerove.lite.data.SubscriptionDownloader
import org.junit.Assert.*
import org.junit.Test

class BundledMeowScriptTest {
    private fun bytes() = File(System.getProperty("bundled.script.asset") ?: "src/main/assets/${BundledMeowScript.ASSET}").readBytes()
    @Test fun first_install_seeds_exact_asset_once_without_profile_activation() = runBlocking {
        val root = Files.createTempDirectory("bundled-first").toFile()
        val repo = OverrideRepository(root, ::bytes)
        val first = repo.snapshot()
        assertTrue(first.bundledScriptInitialized)
        assertTrue(first.profiles.isEmpty())
        val script = first.scripts.single()
        assertEquals(BundledMeowScript.ID, script.id)
        assertEquals(BundledMeowScript.UPDATE_URL, script.url)
        assertArrayEquals(bytes(), script.content.toByteArray(Charsets.UTF_8))
        assertEquals(first, OverrideRepository(root, ::bytes).snapshot())
        repo.putScript(script.copy(content = "user edit", url = "https://example.test/custom.js"))
        assertEquals("user edit", OverrideRepository(root, ::bytes).snapshot().scripts.single().content)
        assertEquals("https://example.test/custom.js", repo.snapshot().scripts.single().url)
    }
    @Test fun old_library_keeps_users_names_and_all_profile_bindings() = runBlocking {
        val root = Files.createTempDirectory("bundled-upgrade").toFile()
        val old = OverrideRepository(root)
        val user = ScriptResource("user", BundledMeowScript.NAME, content = "custom")
        old.putScript(user)
        old.setProfile(ProfileOverride("p", OverrideMode.Script, "user"))
        val before = old.snapshot()
        val json = File(root, "settings/${OverrideRepository.FILE_NAME}")
        json.writeText(org.json.JSONObject(json.readText()).apply { remove("bundledScriptInitialized") }.toString())
        val after = OverrideRepository(root, ::bytes).snapshot()
        assertEquals(before.profiles, after.profiles)
        assertEquals(user, after.scripts.first())
        assertEquals("${BundledMeowScript.NAME} (2)", after.scripts.last().name)
        assertEquals(2, after.scripts.size)
    }
    @Test fun deletion_survives_reopen_and_user_can_reimport_by_existing_url_path() = runBlocking {
        val root = Files.createTempDirectory("bundled-delete").toFile()
        val repo = OverrideRepository(root, ::bytes)
        repo.snapshot()
        repo.setProfile(ProfileOverride("p", OverrideMode.Script, BundledMeowScript.ID))
        repo.deleteScript(BundledMeowScript.ID)
        val reopened = OverrideRepository(root, ::bytes)
        assertTrue(reopened.snapshot().scripts.isEmpty())
        assertNull(reopened.snapshot().profiles.single().scriptId)
        var downloaded: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloaded = chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("reimported".toResponseBody()).build()
        }.build()
        val session = ScriptLibrarySession(reopened, SubscriptionDownloader(client))
        session.put(ScriptResource("imported", "Imported", BundledMeowScript.UPDATE_URL, "template"))
        assertEquals(BundledMeowScript.UPDATE_URL, downloaded)
        assertEquals("reimported", reopened.snapshot().scripts.single().content)
        assertNull(reopened.snapshot().profiles.single().scriptId)
    }
    @Test fun existing_stable_id_and_restore_preserve_user_content_and_local_install_marker() = runBlocking {
        val root = Files.createTempDirectory("bundled-existing").toFile()
        val old = OverrideRepository(root)
        val edited = ScriptResource(BundledMeowScript.ID, "Edited", "https://example.test/own.js", "keep")
        old.putScript(edited)
        val repo = OverrideRepository(root) { error("Existing identity must never load/reset asset") }
        assertEquals(edited, repo.snapshot().scripts.single())
        val empty = repo.snapshot().copy(scripts = emptyList())
        val incoming = OverrideSnapshot()
        for (replace in listOf(false, true)) {
            val restored = OverrideRepository.mergeRestore(empty, incoming, emptyMap(), replace, false, emptySet())
            assertTrue(restored.bundledScriptInitialized)
            assertTrue(restored.scripts.isEmpty())
            assertEquals(restored, OverrideRepository.decode(OverrideRepository.encode(restored)))
        }
    }
    @Test fun corrupt_asset_fails_without_changing_existing_library() = runBlocking {
        val root = Files.createTempDirectory("bundled-integrity").toFile()
        val old = OverrideRepository(root)
        old.putScript(ScriptResource("user", "User", content = "keep"))
        val file = File(root, "settings/${OverrideRepository.FILE_NAME}")
        val original = file.readBytes()
        val corrupt = bytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try { OverrideRepository(root) { corrupt }.snapshot(); fail() } catch (_: IllegalArgumentException) { }
        assertArrayEquals(original, file.readBytes())
        assertEquals("keep", old.snapshot().scripts.single().content)
    }
    @Test fun builtin_update_uses_dedicated_url_failure_preserves_and_racing_edit_wins() = runBlocking {
        val root = Files.createTempDirectory("bundled-update").toFile()
        val repo = OverrideRepository(root, ::bytes)
        val original = repo.snapshot().scripts.single()
        val failed = AtomicBoolean(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var url: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            url = chain.request().url.toString()
            if (failed.get()) throw IOException("offline")
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("stale".toResponseBody()).build()
        }.build()
        val session = ScriptLibrarySession(repo, SubscriptionDownloader(client))
        assertEquals(ScriptUpdateReceipt(false, "update_failed"), session.updateWithReceipt(original.id))
        assertEquals(BundledMeowScript.UPDATE_URL, url)
        assertEquals(original, repo.snapshot().scripts.single())
        failed.set(false)
        val pending = async(Dispatchers.IO) { session.updateWithReceipt(original.id) }
        check(entered.await(3, TimeUnit.SECONDS))
        repo.putScript(original.copy(content = "newer user intent"))
        release.countDown()
        assertEquals(ScriptUpdateReceipt(false, "edit_conflict"), pending.await())
        assertEquals("newer user intent", repo.snapshot().scripts.single().content)
        assertTrue(session.state.value.busyIds.isEmpty())
    }
}

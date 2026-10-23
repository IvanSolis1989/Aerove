package dev.aerove.lite.updates

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdatePromptResetTest {
    private fun preferences(file: File, commit: Boolean = true, stale: Boolean = false): SharedPreferences {
        val removals = linkedSetOf<String>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "remove" -> { removals += args!![0] as String; proxy }
                "commit" -> {
                    if (commit && !stale) {
                        val json = JSONObject(file.readText())
                        removals.forEach(json::remove)
                        file.writeText(json.toString())
                    }
                    commit
                }
                "toString" -> "Prompt editor fixture"
                else -> error("Unexpected preference editor operation")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "contains" -> JSONObject(file.readText()).has(args!![0] as String)
                "toString" -> "Prompt preference fixture"
                else -> error("Unexpected preference operation")
            }
        } as SharedPreferences
    }

    @Test fun actualTagRemoveCommitAndReadbackPreserveOtherPreferences() {
        val directory = Files.createTempDirectory("update-prompt-reset").toFile()
        try {
            val file = directory.resolve("preferences.json").apply { writeText("""{"tag":"old-release","unknown":"retain"}""") }
            clearUpdatePromptTag(preferences(file))
            val saved = JSONObject(file.readText())
            assertFalse(saved.has("tag"))
            assertEquals("retain", saved.getString("unknown"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedCommitOrStaleReadbackCannotReportCleanupSuccess() {
        val directory = Files.createTempDirectory("update-prompt-failure").toFile()
        try {
            val file = directory.resolve("preferences.json").apply { writeText("""{"tag":"old-release"}""") }
            assertThrows(IllegalStateException::class.java) { clearUpdatePromptTag(preferences(file, commit = false)) }
            assertThrows(IllegalStateException::class.java) { clearUpdatePromptTag(preferences(file, stale = true)) }
            assertTrue(JSONObject(file.readText()).has("tag"))
        } finally { directory.deleteRecursively() }
    }
}

package dev.aerove.lite.data

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppliedConfigSnapshot(val profileId: String, private val file: File) {
    suspend fun read(): String = withContext(Dispatchers.IO) {
        val text = try {
            file.inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
        } catch (missing: FileNotFoundException) {
            throw IOException("Applied runtime configuration is no longer available", missing)
        }
        if (text.isBlank()) throw IOException("Applied runtime configuration is empty")
        text
    }

    override fun toString(): String = "AppliedConfigSnapshot(<redacted>)"
}

package dev.aerove.lite.editor

import android.content.ContentResolver
import android.net.Uri
import dev.aerove.lite.data.MAX_PROFILE_BYTES
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.strictUtf8
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EditorInput {
    suspend fun readFile(file: File): String = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() <= MAX_PROFILE_BYTES) { "Invalid or oversized configuration file" }
        file.inputStream().use { it.readBounded(MAX_PROFILE_BYTES).strictUtf8() }
    }

    suspend fun readUri(resolver: ContentResolver, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a document URI" }
        (resolver.openInputStream(uri) ?: throw IOException("Cannot open configuration document")).use {
            it.readBounded(MAX_PROFILE_BYTES).strictUtf8()
        }
    }
}

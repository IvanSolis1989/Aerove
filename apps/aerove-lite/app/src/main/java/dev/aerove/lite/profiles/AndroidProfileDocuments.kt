package dev.aerove.lite.profiles

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** ActivityResult grants remain valid while its owner performs the operation; no persistent grant is retained. */
class AndroidProfileDocuments(private val resolver: ContentResolver) : ProfileDocuments {
    override suspend fun <T> read(reference: String, action: suspend (InputStream) -> T): T =
        checkNotNull(resolver.openInputStream(uri(reference))) { "Document cannot be opened" }.use { action(it) }

    override suspend fun write(reference: String, bytes: ByteArray) {
        checkNotNull(resolver.openOutputStream(uri(reference), "wt")) { "Document cannot be opened" }.use { output ->
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(65536, bytes.size - offset)
                output.write(bytes, offset, count)
                offset += count
            }
            output.flush()
        }
    }

    override suspend fun deleteCreated(reference: String): Boolean = DocumentsContract.deleteDocument(resolver, uri(reference))

    private fun uri(reference: String): Uri = Uri.parse(reference).also {
        require(it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrEmpty()) { "SAF document URI required" }
    }
}

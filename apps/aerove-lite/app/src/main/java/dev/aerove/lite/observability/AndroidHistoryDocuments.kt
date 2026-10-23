package dev.aerove.lite.observability

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidHistoryDocuments(private val resolver: ContentResolver) : HistoryDocuments {
    private fun uri(reference: String): Uri = Uri.parse(reference).also {
        require(it.scheme == "content" && !it.authority.isNullOrBlank()) { "Invalid document reference" }
    }
    override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val current = { context.ensureActive(); isCurrent() }
        val target = uri(reference)
        val expected = checkNotNull(resolver.openOutputStream(target, "wt")).use { writeHistoryExport(it, lines, current) }
        checkNotNull(resolver.openInputStream(target)).use { verifyHistoryExport(it, expected, current) }
    }
    override suspend fun deleteCreated(reference: String): Boolean = withContext(Dispatchers.IO) {
        DocumentsContract.deleteDocument(resolver, uri(reference))
    }
}

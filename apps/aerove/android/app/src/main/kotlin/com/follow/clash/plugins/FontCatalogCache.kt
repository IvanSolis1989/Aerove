package com.follow.clash.plugins

import java.util.concurrent.CancellationException
import java.io.File

internal fun sameFontFace(declared: Pair<File, Int>, resolved: Pair<File, Int>): Boolean =
    declared.second == resolved.second && declared.first.canonicalPath == resolved.first.canonicalPath

/** Outlives an engine, so a reopened activity reuses the catalog until the configuration changes. */
internal class FontCatalogStore {
    val lock = Any()
    var generation = 0L
    var value: List<String>? = null

    fun invalidate() = synchronized(lock) {
        generation++
        value = null
    }
}

internal class FontCatalogCache(private val store: FontCatalogStore = FontCatalogStore()) {
    private var closed = false

    fun read(scan: (() -> Unit) -> List<String>): List<String> {
        val request = synchronized(store.lock) {
            checkActive(store.generation)
            store.value?.let { return it }
            store.generation
        }
        val result = scan { synchronized(store.lock) { checkActive(request) } }
        return synchronized(store.lock) {
            checkActive(request)
            result.toList().also { store.value = it }
        }
    }

    fun invalidate() = store.invalidate()

    fun close() = synchronized(store.lock) {
        closed = true
    }

    private fun checkActive(request: Long) {
        if (closed || request != store.generation) throw CancellationException("Font catalog superseded")
    }
}

package com.follow.clash.plugins

import java.util.concurrent.CancellationException
import java.io.File

internal fun sameFontFace(declared: Pair<File, Int>, resolved: Pair<File, Int>): Boolean =
    declared.second == resolved.second && declared.first.canonicalPath == resolved.first.canonicalPath

internal class FontCatalogCache {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var value: List<String>? = null

    fun read(scan: (() -> Unit) -> List<String>): List<String> {
        val request = synchronized(lock) {
            checkActive(generation)
            value?.let { return it }
            generation
        }
        val result = scan { synchronized(lock) { checkActive(request) } }
        return synchronized(lock) {
            checkActive(request)
            result.toList().also { value = it }
        }
    }

    fun invalidate() = synchronized(lock) {
        generation++
        value = null
    }

    fun close() = synchronized(lock) {
        closed = true
        generation++
        value = null
    }

    private fun checkActive(request: Long) {
        if (closed || request != generation) throw CancellationException("Font catalog superseded")
    }
}

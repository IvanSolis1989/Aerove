package dev.aerove.lite.data

import java.io.File

/** Copies only a current Host catalog asset into the manager's already owned destination. */
fun interface HostGeoResourceReader {
    suspend fun copyInto(key: String, url: String, destination: File, maxBytes: Long): HostGeoResourceCopy?
}

class HostGeoResourceCopy(val sha256: String, val bytes: Long, val updatedAt: Long?) {
    init { require(sha256.matches(Regex("[0-9a-f]{64}")) && bytes >= 0 && (updatedAt == null || updatedAt >= 0)) }
}

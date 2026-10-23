package dev.aerove.lite.appaccess

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

enum class ChinaMatchSource { Skipped, PackagePrefix, ComponentPrefix, DexClass, OversizedDex, FirebaseArchive, NoMatch }
data class ChinaClassification(val matches: Boolean, val source: ChinaMatchSource)

/** Same ordered evidence as Standard PackageResolver; package names are never guessed by country suffix. */
internal fun classifyApplication(packageName: String, componentNames: Sequence<String>, sourceArchive: File?,
    checkCancellation: () -> Unit): ChinaClassification {
    checkCancellation()
    if (StandardChinaPackageMatcher.isSkipped(packageName)) return ChinaClassification(false, ChinaMatchSource.Skipped)
    if (StandardChinaPackageMatcher.matchesKnownPrefix(packageName)) return ChinaClassification(true, ChinaMatchSource.PackagePrefix)
    if (componentNames.any { checkCancellation(); StandardChinaPackageMatcher.matchesKnownPrefix(it) })
        return ChinaClassification(true, ChinaMatchSource.ComponentPrefix)
    if (sourceArchive == null) return ChinaClassification(false, ChinaMatchSource.NoMatch)
    return ZipFile(sourceArchive).use archiveUse@ { archive ->
        if (archive.entries().asSequence().any { checkCancellation(); it.name.startsWith("firebase-") })
            return@archiveUse ChinaClassification(false, ChinaMatchSource.FirebaseArchive)
        archive.entries().asSequence().filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }.forEach { entry ->
            checkCancellation()
            if (entry.size > MAX_CLASSIFICATION_DEX_BYTES) return@archiveUse ChinaClassification(true, ChinaMatchSource.OversizedDex)
            val bytes = archive.getInputStream(entry).use inputUse@ { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancellation()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size().toLong() + count > MAX_CLASSIFICATION_DEX_BYTES)
                        return@inputUse null
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } ?: return@archiveUse ChinaClassification(true, ChinaMatchSource.OversizedDex)
            if (dexMatchesKnownClass(bytes, checkCancellation)) return@archiveUse ChinaClassification(true, ChinaMatchSource.DexClass)
        }
        ChinaClassification(false, ChinaMatchSource.NoMatch)
    }
}

internal const val MAX_CLASSIFICATION_DEX_BYTES = 15_000_000L

/** Reads only DEX class-definition/type/string tables; never loads or executes third-party application code. */
internal fun dexMatchesKnownClass(bytes: ByteArray, checkCancellation: () -> Unit = {}): Boolean {
    require(bytes.size >= 112 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)) && bytes[7] == 0.toByte()) { "Invalid DEX" }
    val version = String(bytes, 4, 3, Charsets.US_ASCII).toIntOrNull()
    require(version != null && version in 35..40) { "Unsupported DEX version" }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun integer(offset: Int): Int {
        require(offset >= 0 && offset.toLong() + 4 <= bytes.size)
        return buffer.getInt(offset).also { require(it >= 0) }
    }
    require(integer(36) >= 112 && integer(40) == 0x12345678 && integer(32) <= bytes.size) { "Invalid DEX header" }
    fun table(countOffset: Int, addressOffset: Int, width: Int): Pair<Int, Int> {
        val count = integer(countOffset)
        val address = integer(addressOffset)
        require(address.toLong() + count.toLong() * width <= bytes.size) { "Invalid DEX table" }
        return count to address
    }
    val (strings, stringOffset) = table(56, 60, 4)
    val (types, typeOffset) = table(64, 68, 4)
    val (classes, classOffset) = table(96, 100, 32)
    repeat(classes) { index ->
        if (index % 128 == 0) checkCancellation()
        val type = integer(classOffset + index * 32)
        require(type < types)
        val string = integer(typeOffset + type * 4)
        require(string < strings)
        var start = integer(stringOffset + string * 4)
        // Skip the unsigned LEB128 UTF-16 length, at most five bytes.
        var lengthBytes = 0
        do {
            require(start < bytes.size && lengthBytes++ < 5)
            val next = bytes[start++].toInt()
        } while (next and 0x80 != 0)
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) {
            if ((end - start) % 4096 == 0) checkCancellation()
            end++
        }
        require(end < bytes.size) { "Invalid DEX class descriptor" }
        val descriptor = String(bytes, start, end - start, Charsets.UTF_8)
        if (StandardChinaPackageMatcher.matchesKnownPrefix(StandardChinaPackageMatcher.classNameOf(descriptor))) return true
    }
    return false
}

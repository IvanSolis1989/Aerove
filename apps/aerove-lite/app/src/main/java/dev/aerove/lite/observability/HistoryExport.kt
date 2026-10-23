package dev.aerove.lite.observability

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class HistoryExportDigest(val bytes: Long, val sha256: String)

fun writeHistoryExport(output: OutputStream, lines: List<String>, isCurrent: () -> Boolean): HistoryExportDigest {
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    lines.forEachIndexed { index, line ->
        check(isCurrent()) { "History export was cancelled" }
        val bytes = ((if (index == 0) "" else "\n") + line).toByteArray(Charsets.UTF_8)
        output.write(bytes); digest.update(bytes); count += bytes.size
    }
    output.flush()
    check(isCurrent()) { "History export was cancelled" }
    return HistoryExportDigest(count, digest.digest().joinToString("") { "%02x".format(it) })
}

fun verifyHistoryExport(input: InputStream, expected: HistoryExportDigest, isCurrent: () -> Boolean) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var count = 0L
    while (true) {
        check(isCurrent()) { "History export was cancelled" }
        val n = input.read(buffer)
        if (n < 0) break
        count += n; check(count <= expected.bytes) { "History export read-back mismatch" }
        digest.update(buffer, 0, n)
    }
    check(count == expected.bytes && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) {
        "History export read-back mismatch"
    }
}

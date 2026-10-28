package dev.aerove.lite

import android.app.UiAutomation
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd

/** Failure-only shell snapshot; a missing row or unavailable table never proves release. */
internal object NetworkPortTcpEvidence {
    private const val BYTE_LIMIT = 64 * 1024
    private val separator = Regex("\\s+")
    private val hex = Regex("[0-9A-Fa-f]+")
    private val decimal = Regex("[0-9]+")

    // Reuses NativeSmokeTest's fixed toybox timeout + nonblocking Os.poll/read ownership pattern.
    fun capture(automation: UiAutomation, ports: List<Int>): List<String> {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 2500
        return listOf("tcp", "tcp6").map { family ->
            if (SystemClock.elapsedRealtime() >= deadline) return@map "family=$family unavailable=deadline"
            try {
                val raw = ByteArray(BYTE_LIMIT)
                var size = 0
                var eof = false
                var expired = false
                automation.executeShellCommand("toybox timeout -s KILL 2 toybox head -c 65536 /proc/net/$family").use { pipe ->
                    val fd = pipe.fileDescriptor
                    Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
                    val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                    while (size < raw.size) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) { expired = true; break }
                        val ready = try { Os.poll(arrayOf(poll), minOf(100L, remaining).toInt()) }
                        catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EINTR) throw error
                            continue
                        }
                        if (ready == 0) continue
                        check(poll.revents.toInt() and OsConstants.POLLNVAL == 0)
                        val count = try { Os.read(fd, raw, size, raw.size - size) }
                        catch (error: ErrnoException) {
                            if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                            continue
                        }
                        if (count == 0) { eof = true; break }
                        size += count
                    }
                }
                "sampleStartElapsedMs=$started elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                    "deadline=$expired byteLimit=${size == BYTE_LIMIT} " + parse(family, raw.copyOf(size), eof, ports.toSet())
            } catch (failure: Exception) {
                "family=$family unavailable=${failure.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - started}"
            }
        }
    }

    internal fun parse(family: String, raw: ByteArray, complete: Boolean, wanted: Set<Int>): String {
        require(family == "tcp" || family == "tcp6")
        require(raw.size <= BYTE_LIMIT)
        val text = raw.toString(Charsets.US_ASCII)
        val whole = if (complete || text.endsWith('\n')) text else text.substringBeforeLast('\n', "")
        val rows = mutableListOf<String>()
        var omitted = 0
        var malformed = 0
        var count = 0
        var header = false
        val lines = whole.lineSequence().iterator()
        while (count < 512 && lines.hasNext()) {
            val fields = lines.next().trim().split(separator)
            count++
            if (fields.size >= 4 && fields[0] == "sl" && fields[1] == "local_address" && fields[3] == "st") {
                header = true
                continue
            }
            if (fields.size == 1 && fields[0].isEmpty()) continue
            if (fields.size < 10) { malformed++; continue }
            val endpoint = fields[1].split(':')
            val width = if (family == "tcp") 8 else 32
            if (endpoint.size != 2 || endpoint[0].length != width || !hex.matches(endpoint[0]) ||
                endpoint[1].length != 4 || !hex.matches(endpoint[1]) || fields[3].length != 2 || !hex.matches(fields[3]) ||
                !decimal.matches(fields[7]) || fields[7].toUIntOrNull() == null ||
                !decimal.matches(fields[9]) || fields[9].toULongOrNull() == null) {
                malformed++
                continue
            }
            val port = endpoint[1].toInt(16)
            if (port !in wanted) continue
            if (rows.size == 16) { omitted++; continue }
            val state = fields[3].uppercase()
            val kind = when (state) { "0A" -> "LISTEN"; "06" -> "TIME_WAIT"; else -> "OTHER" }
            rows += "localPort=$port state=$state kind=$kind uid=${fields[7].toUInt()} inode=${fields[9].toULong()}"
        }
        val evidence = if (!header) "UNAVAILABLE" else if (!complete || lines.hasNext()) "PARTIAL" else "OBSERVED"
        return "family=$family evidence=$evidence bytes=${raw.size} complete=$complete header=$header lines=$count " +
            "lineLimit=${lines.hasNext()} matched=${rows.size} omitted=$omitted malformed=$malformed rows=${rows.joinToString(";")}"
    }
}

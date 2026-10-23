package dev.aerove.lite.networksettings

import java.math.BigInteger
import java.net.InetAddress

val standardPrivateRoutes = listOf("0.0.0.0/8", "10.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16",
    "172.16.0.0/12", "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32", "fc00::/7", "fe80::/10", "ff00::/8")

private data class RouteRange(val bits: Int, val first: BigInteger, val last: BigInteger)
private val one = BigInteger.ONE
private fun whole(bits: Int) = RouteRange(bits, BigInteger.ZERO, one.shiftLeft(bits) - one)

/** No hostname lookup: only validated decimal IPv4 or hexadecimal IPv6 literals reach InetAddress. */
internal fun validateRouteCidr(text: String): Boolean = runCatching { parseRoute(text); true }.getOrDefault(false)

private fun parseRoute(text: String): RouteRange {
    val parts = text.split('/')
    require(parts.size == 2 && text.length <= 128)
    val host = parts[0]
    val ipv6 = ':' in host
    val bytes = if (ipv6) {
        require(host.all { it in "0123456789abcdefABCDEF:." })
        val parsed = InetAddress.getByName(host).address
        if (parsed.size == 4) ByteArray(16).apply { this[10] = -1; this[11] = -1; parsed.copyInto(this, 12) } else parsed
    } else {
        val octets = host.split('.')
        require(octets.size == 4)
        octets.map {
            require(it.isNotEmpty() && it.all(Char::isDigit) && (it.length == 1 || it[0] != '0'))
            val number = it.toInt(); require(number in 0..255); number.toByte()
        }.toByteArray()
    }
    val bits = if (ipv6) 128 else 32
    require(bytes.size * 8 == bits)
    val prefix = parts[1].toInt(); require(prefix in 0..bits)
    val number = BigInteger(1, bytes)
    val first = number.shiftRight(bits - prefix).shiftLeft(bits - prefix)
    return RouteRange(bits, first, first + one.shiftLeft(bits - prefix) - one)
}

private fun mergeRoutes(ranges: List<RouteRange>): List<RouteRange> {
    val result = ArrayList<RouteRange>()
    ranges.sortedWith(compareBy<RouteRange> { it.bits }.thenBy { it.first }).forEach { next ->
        val previous = result.lastOrNull()
        if (previous != null && previous.bits == next.bits && next.first <= previous.last + one)
            result[result.lastIndex] = previous.copy(last = maxOf(previous.last, next.last))
        else result += next
    }
    return result
}

private fun subtractRoutes(included: List<RouteRange>, excluded: List<RouteRange>): List<RouteRange> {
    return buildList {
        val input = mergeRoutes(included)
        val exclusions = mergeRoutes(excluded)
        listOf(32, 128).forEach { family ->
            val cuts = exclusions.filter { it.bits == family }
            var cursor = 0
            input.filter { it.bits == family }.forEach { route ->
                var start = route.first
                while (cursor < cuts.size && cuts[cursor].last < start) cursor++
                var index = cursor
                while (index < cuts.size) {
                    val cut = cuts[index]
                    if (cut.first > route.last) break
                    if (cut.first > start) add(route.copy(first = start, last = cut.first - one))
                    start = maxOf(start, cut.last + one)
                    if (start > route.last) break
                    index++
                }
                cursor = index
                if (start <= route.last) add(route.copy(first = start))
            }
        }
    }
}

private fun address(number: BigInteger, bits: Int): String {
    val raw = number.toByteArray()
    val bytes = ByteArray(bits / 8)
    raw.takeLast(bytes.size).toByteArray().copyInto(bytes, bytes.size - minOf(raw.size, bytes.size))
    return if (bits == 32) bytes.joinToString(".") { (it.toInt() and 255).toString() }
    else (0 until 8).joinToString(":") { i -> (((bytes[i * 2].toInt() and 255) shl 8) or (bytes[i * 2 + 1].toInt() and 255)).toString(16) }
}

private fun toCidrs(ranges: List<RouteRange>): List<String> = buildList {
    ranges.forEach { range ->
        var start = range.first
        while (start <= range.last) {
            val alignment = if (start == BigInteger.ZERO) range.bits else start.lowestSetBit
            val remaining = (range.last - start + one).bitLength() - 1
            val size = minOf(alignment, remaining)
            add("${address(start, range.bits)}/${range.bits - size}")
            start += one.shiftLeft(size)
        }
    }
}

private data class RouteKey(val included: List<String>, val excluded: List<String>, val bypassPrivate: Boolean,
    val ipv6: Boolean, val reserved: List<String>)
private val routeCache = object : LinkedHashMap<RouteKey, List<String>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RouteKey, List<String>>?) = size > 16
}

/** Run uncached large edits on the existing worker; repeated effective comparisons reuse a bounded cache. */
fun planVpnRoutes(settings: NetworkSettings, ipv6: Boolean, reservedFakeIpRanges: List<String> = emptyList()): List<String> {
    val key = RouteKey(settings.routeAddress.toList(), settings.routeExcludeAddress.toList(), settings.bypassPrivateRoute,
        ipv6, reservedFakeIpRanges.toList())
    synchronized(routeCache) { routeCache[key]?.let { return it.toList() } }
    val included = if (key.included.isEmpty()) listOf(whole(32), whole(128)) else key.included.map(::parseRoute)
    val private = if (key.bypassPrivate) subtractRoutes(standardPrivateRoutes.map(::parseRoute), key.reserved.map(::parseRoute)) else emptyList()
    val excluded = key.excluded.map(::parseRoute) + private
    val result = toCidrs(subtractRoutes(included.filter { ipv6 || it.bits == 32 }, excluded))
    synchronized(routeCache) { routeCache[key] = result.toList() }
    return result
}

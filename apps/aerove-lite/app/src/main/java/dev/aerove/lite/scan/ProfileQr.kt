package dev.aerove.lite.scan

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object ProfileQr {
    private val webSchemes = setOf("http", "https", "ftp")
    private val installSchemes = setOf("clash", "clashmeta", "flclash")
    private val shareSchemes = setOf(
        "hysteria", "hysteria2", "hy2", "hysteria2+realm", "hy2+realm", "tuic", "trojan",
        "vless", "vmess", "ss", "ssr", "socks", "socks5", "socks5h", "anytls", "mierus",
    )

    fun first(values: Iterable<String?>): String? = values.firstNotNullOfOrNull(::parse)

    fun parse(value: String?): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 65536 } ?: return null
        if (text.any { it.isISOControl() }) return null
        val separator = text.indexOf("://")
        if (separator > 0 && text.length > separator + 3 && text.substring(0, separator).lowercase() in shareSchemes) {
            return text
        }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (isWeb(uri)) return text
        if (uri.scheme?.lowercase() !in installSchemes || uri.host != "install-config") return null
        val parameter = uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "url" } ?: return null
        val url = runCatching {
            URLDecoder.decode(parameter.substringAfter('=', ""), StandardCharsets.UTF_8).trim()
        }.getOrNull() ?: return null
        val nested = runCatching { URI(url) }.getOrNull() ?: return null
        return url.takeIf { isWeb(nested) }
    }

    private fun isWeb(uri: URI): Boolean = uri.scheme?.lowercase() in webSchemes && runCatching {
        val url = uri.toURL()
        url.host.isNotBlank() && url.port in -1..65535
    }.getOrDefault(false)
}

internal class ScanResultGate {
    private var delivered = false
    private var lastValues: List<String?> = emptyList()

    fun accept(values: List<String?>): ScanMatch {
        if (delivered || values.isEmpty() || values == lastValues) return ScanMatch.Ignored
        lastValues = values.toList()
        val url = ProfileQr.first(values) ?: return ScanMatch.Invalid
        delivered = true
        return ScanMatch.Found(url)
    }

    fun close(): Boolean {
        if (delivered) return false
        delivered = true
        return true
    }
}

internal sealed interface ScanMatch {
    data object Ignored : ScanMatch
    data object Invalid : ScanMatch
    data class Found(val url: String) : ScanMatch
}

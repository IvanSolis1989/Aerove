package dev.aerove.lite.ui

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale

internal data class ProfileSubscriptionUsage(val upload: Long, val download: Long, val total: Long, val expireSeconds: Long) {
    val used: BigInteger get() = BigInteger.valueOf(upload) + BigInteger.valueOf(download)
    val share: Double? get() = if (total > 0 && upload >= 0 && download >= 0) used.toDouble() / total else null
    val progress: Float? get() = share?.coerceIn(0.0, 1.0)?.toFloat()
    val expireMillis: Long? get() = expireSeconds.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000)
}

internal fun parseProfileSubscriptionUsage(header: String?): ProfileSubscriptionUsage? {
    if (header.isNullOrBlank()) return null
    val values = mutableMapOf<String, Long>()
    header.split(';').forEach { field ->
        val separator = field.indexOf('=')
        if (separator > 0) {
            val key = field.substring(0, separator).trim().lowercase(Locale.ROOT)
            val number = runCatching { BigDecimal(field.substring(separator + 1).trim()).toBigInteger().longValueExact() }.getOrNull()
            if (key in setOf("upload", "download", "total", "expire") && number != null) values[key] = number
        }
    }
    if (values.isEmpty()) return null
    return ProfileSubscriptionUsage(values["upload"] ?: 0, values["download"] ?: 0, values["total"] ?: 0, values["expire"] ?: 0)
}

internal fun profileIntervalMillis(minutes: String): Long? = minutes.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    ?.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / 60_000 }?.times(60_000)

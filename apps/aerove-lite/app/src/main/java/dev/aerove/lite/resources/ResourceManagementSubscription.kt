package dev.aerove.lite.resources

/** HTTP Subscription-Userinfo metadata; malformed metadata never substitutes invented usage. */
data class ResourceSubscriptionInfo(val upload: Long, val download: Long, val total: Long, val expireSeconds: Long?) {
    val used: Long get() = upload + download
    val usage: Float get() = (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    fun header(): String = "upload=$upload; download=$download; total=$total" + (expireSeconds?.let { "; expire=$it" } ?: "")
    companion object {
        fun parse(header: String?): ResourceSubscriptionInfo? {
            if (header == null || header.length > 8192 || header.any { it.isISOControl() }) return null
            val fields = mutableMapOf<String, Long>()
            for (token in header.split(';')) {
                val pair = token.trim().split('=', limit = 2)
                if (pair.size != 2) return null
                val key = pair[0].trim().lowercase(java.util.Locale.ROOT)
                if (key !in setOf("upload", "download", "total", "expire")) continue
                val raw = pair[1].trim()
                if (!raw.matches(Regex("[0-9]+"))) return null
                val value = raw.toLongOrNull() ?: return null
                if (fields.put(key, value) != null) return null
            }
            val upload = fields["upload"] ?: return null
            val download = fields["download"] ?: return null
            val total = fields["total"]?.takeIf { it > 0 } ?: return null
            if (upload > Long.MAX_VALUE - download) return null
            val expire = fields["expire"]?.takeIf { it > 0 }
            if (expire != null && expire > Long.MAX_VALUE / 1000) return null
            return ResourceSubscriptionInfo(upload, download, total, expire)
        }
    }
}

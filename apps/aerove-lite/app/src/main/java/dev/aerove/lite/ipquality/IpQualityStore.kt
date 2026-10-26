package dev.aerove.lite.ipquality

import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.strictUtf8
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import org.json.JSONObject

data class LastQualityExit(val profileId: String, val proxied: Boolean, val ip: String, val quality: IpQuality? = null,
    val countryCode: String? = null)
data class IpQualityCheck(val ip: String, val quality: IpQuality, val checkedAtWallClock: Long)
data class SavedIpQuality(val hidden: Boolean = false, val lastExit: LastQualityExit? = null, val cache: List<IpQualityCheck> = emptyList())

class IpQualityStore(private val file: File) {
    private var revision = -1L
    private var cleared = false

    @Synchronized fun read(): SavedIpQuality = try {
        if (!file.isFile) SavedIpQuality() else {
            val value = JSONObject(file.inputStream().use { it.readBounded(1024 * 1024).strictUtf8() })
            val hidden = value.optBoolean("hidden", false)
            val raw = value.optJSONObject("lastExit")
            val last = if (hidden || raw == null) null else {
                val ip = raw.getString("ip")
                require(validQualityIp(ip))
                val quality = raw.optJSONObject("quality")?.let { decodeQuality(it, ip) }
                val country = (raw.opt("countryCode") as? String)?.takeIf { it.matches(Regex("[A-Z]{2}")) }
                LastQualityExit(raw.getString("profileId"), raw.getBoolean("proxied"), ip, quality, country)
            }
            val cache = value.optJSONArray("cache")?.let { rows ->
                (0 until rows.length()).mapNotNull { index -> runCatching {
                    val row = rows.getJSONObject(index)
                    val ip = row.getString("ip")
                    require(validQualityIp(ip))
                    val checkedAt = row.getLong("checkedAtWallClock")
                    IpQualityCheck(ip, decodeQuality(row.getJSONObject("quality"), ip), checkedAt)
                }.getOrNull() }.distinctBy { it.ip }.take(256)
            }.orEmpty()
            SavedIpQuality(hidden, last, cache)
        }
    } catch (_: Exception) { SavedIpQuality() }

    @Synchronized fun write(value: SavedIpQuality, generation: Long, wallNow: Long = System.currentTimeMillis()) {
        if (cleared || generation < revision) return
        val json = JSONObject().put("hidden", value.hidden)
        value.lastExit?.takeUnless { value.hidden }?.let { exit ->
            val raw = JSONObject().put("profileId", exit.profileId).put("proxied", exit.proxied).put("ip", exit.ip)
                .put("countryCode", exit.countryCode)
            exit.quality?.takeIf { it.ip == exit.ip }?.let { raw.put("quality", encodeQuality(it)) }
            json.put("lastExit", raw)
        }
        val rows = value.cache.asSequence().filter {
            validQualityIp(it.ip) && it.quality.ip == it.ip && it.checkedAtWallClock in 0..wallNow &&
                wallNow - it.checkedAtWallClock < CACHE_TTL_MS
        }.distinctBy { it.ip }.sortedByDescending { it.checkedAtWallClock }.take(MAX_CACHE_ROWS).toList()
        val cache = org.json.JSONArray()
        json.put("cache", cache)
        var encodedBytes = json.toString().toByteArray(Charsets.UTF_8).size
        rows.forEach { row ->
            val encoded = JSONObject().put("ip", row.ip).put("checkedAtWallClock", row.checkedAtWallClock)
                .put("quality", encodeQuality(row.quality))
            val rowBytes = encoded.toString().toByteArray(Charsets.UTF_8).size
            val separatorBytes = if (cache.length() == 0) 0 else 1
            if (encodedBytes + rowBytes + separatorBytes <= MAX_STATE_BYTES) {
                cache.put(encoded)
                encodedBytes += rowBytes + separatorBytes
            }
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STATE_BYTES) { "IP quality state exceeds the persistence size limit" }
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.outputStream().use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            revision = generation
        } finally { temporary.delete() }
    }

    /** Terminal for this store instance; deletes only its known JSON and atomic-write temporary. */
    @Synchronized fun clearUserState() {
        cleared = true
        val parent = checkNotNull(file.absoluteFile.parentFile) { "Invalid IP quality state path" }
        check(!Files.isSymbolicLink(parent.toPath())) { "IP quality directory was redirected" }
        val targets = listOf(file.absoluteFile, File(parent, "${file.name}.tmp"))
        targets.forEach { target ->
            check(!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "IP quality state is not a file" }
            Files.deleteIfExists(target.toPath())
        }
        check(targets.none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) { "IP quality state cleanup did not complete" }
    }

    private companion object {
        const val CACHE_TTL_MS = 3L * 24 * 60 * 60 * 1000
        const val MAX_STATE_BYTES = 1024 * 1024
        const val MAX_CACHE_ROWS = 256
    }

    private fun encodeQuality(value: IpQuality) = JSONObject().put("ip", value.ip).put("source", value.source.name)
        .put("type", value.type.name).put("organization", value.organization).put("asn", value.asn)
        .put("proxy", value.isProxy).put("vpn", value.isVpn).put("tor", value.isTor).put("abuser", value.isAbuser)

    private fun decodeQuality(value: JSONObject, ip: String): IpQuality {
        require(value.getString("ip") == ip)
        return IpQuality(ip, IpQualitySource.valueOf(value.getString("source")), IpType.valueOf(value.getString("type")),
            value.opt("organization") as? String, (value.opt("asn") as? Number)?.toLong(),
            value.getBoolean("proxy"), value.getBoolean("vpn"), value.getBoolean("tor"), value.getBoolean("abuser"))
    }
}

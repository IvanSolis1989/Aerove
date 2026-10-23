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
data class SavedIpQuality(val hidden: Boolean = false, val lastExit: LastQualityExit? = null)

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
            SavedIpQuality(hidden, last)
        }
    } catch (_: Exception) { SavedIpQuality() }

    @Synchronized fun write(value: SavedIpQuality, generation: Long) {
        if (cleared || generation < revision) return
        val json = JSONObject().put("hidden", value.hidden)
        value.lastExit?.takeUnless { value.hidden }?.let { exit ->
            val raw = JSONObject().put("profileId", exit.profileId).put("proxied", exit.proxied).put("ip", exit.ip)
                .put("countryCode", exit.countryCode)
            exit.quality?.takeIf { it.ip == exit.ip }?.let { raw.put("quality", encodeQuality(it)) }
            json.put("lastExit", raw)
        }
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

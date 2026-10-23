package dev.aerove.lite.data

import dev.aerove.lite.profilesource.AgeSecret
import dev.aerove.lite.profilesource.DecodedProfileSource
import dev.aerove.lite.profilesource.MAX_IDENTITY_BYTES
import java.util.Base64
import org.json.JSONObject

internal const val MAX_PROFILE_INDEX_BYTES = 128 * 1024 * 1024
internal const val MAX_SOURCE_METADATA_BYTES = 128 * 1024

class ProfileSourceLineage(val encrypted: Boolean, val downloadSha256: String?, val logicalSourceSha256: String, val downloadedSourceSha256: String?)

/** Private database value; identity material never participates in public profile summaries. */
internal class ProfileSourceMetadata private constructor(
    private val identity: String?,
    val encrypted: Boolean,
    val downloadSha256: String?,
    val logicalSourceSha256: String,
    val downloadedSourceSha256: String?,
) {
    fun secret(): AgeSecret? = identity?.let { encoded ->
        val bytes = Base64.getDecoder().decode(encoded)
        try { AgeSecret(bytes) } finally { bytes.fill(0) }
    }

    fun withSecret(secret: AgeSecret?): ProfileSourceMetadata =
        ProfileSourceMetadata(encodeIdentity(secret), encrypted, downloadSha256, logicalSourceSha256, downloadedSourceSha256)

    fun rewritten(source: String): ProfileSourceMetadata = ProfileSourceMetadata(identity, encrypted, downloadSha256, sha256(source), downloadedSourceSha256)
    fun sameSecret(other: ProfileSourceMetadata): Boolean = identity == other.identity
    fun sameSource(other: ProfileSourceMetadata): Boolean = sameSecret(other) && encrypted == other.encrypted &&
        downloadSha256 == other.downloadSha256 && logicalSourceSha256 == other.logicalSourceSha256 &&
        downloadedSourceSha256 == other.downloadedSourceSha256
    fun verify(source: String) { require(sha256(source) == logicalSourceSha256) { "Profile logical source checksum mismatch" } }
    fun lineage() = ProfileSourceLineage(encrypted, downloadSha256, logicalSourceSha256, downloadedSourceSha256)
    fun json(): JSONObject = JSONObject().put("schema", 1).put("ageIdentity", identity ?: JSONObject.NULL)
        .put("encrypted", encrypted).put("downloadSha256", downloadSha256 ?: JSONObject.NULL)
        .put("logicalSourceSha256", logicalSourceSha256).put("downloadedSourceSha256", downloadedSourceSha256 ?: JSONObject.NULL)
    override fun toString(): String = "ProfileSourceMetadata([private])"

    companion object {
        private val hash = Regex("[0-9a-f]{64}")
        fun plain(source: String): ProfileSourceMetadata = ProfileSourceMetadata(null, false, null, sha256(source), null)
        fun decoded(source: DecodedProfileSource, secret: AgeSecret?): ProfileSourceMetadata {
            require(source.downloadSha256.matches(hash) && source.sourceSha256.matches(hash) && sha256(source.yaml) == source.sourceSha256) {
                "Invalid decoded profile source identity"
            }
            return ProfileSourceMetadata(encodeIdentity(secret), source.encrypted, source.downloadSha256, source.sourceSha256, source.sourceSha256)
        }
        fun parse(text: String): ProfileSourceMetadata = try { parse(JSONObject(text)) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid private profile source metadata") }

        fun parse(json: JSONObject): ProfileSourceMetadata = try { parseChecked(json) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid private profile source metadata") }

        private fun parseChecked(json: JSONObject): ProfileSourceMetadata {
            require(json.keys().asSequence().all { it in setOf("schema", "ageIdentity", "encrypted", "downloadSha256", "logicalSourceSha256", "downloadedSourceSha256") } &&
                json.get("schema").let { (it is Int && it == 1) || (it is Long && it == 1L) } && json.get("encrypted") is Boolean) { "Invalid private profile source metadata" }
            val key = if (json.isNull("ageIdentity")) null else (json.get("ageIdentity") as? String
                ?: throw IllegalArgumentException("Invalid private profile identity"))
            if (key != null) {
                require(key.length <= ((MAX_IDENTITY_BYTES + 2) / 3) * 4) { "Invalid private profile identity size" }
                val bytes = try { Base64.getDecoder().decode(key) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid private profile identity encoding") }
                try { require(bytes.size in 1..MAX_IDENTITY_BYTES) { "Invalid private profile identity size" } } finally { bytes.fill(0) }
            }
            val download = if (json.isNull("downloadSha256")) null else json.getString("downloadSha256").also {
                require(it.matches(hash)) { "Invalid profile download identity" }
            }
            val logical = json.getString("logicalSourceSha256").also { require(it.matches(hash)) { "Invalid logical profile identity" } }
            require(!json.getBoolean("encrypted") || download != null) { "Missing encrypted download identity" }
            val downloadedSource = if (json.isNull("downloadedSourceSha256")) null else json.getString("downloadedSourceSha256").also {
                require(it.matches(hash)) { "Invalid downloaded logical identity" }
            }
            return ProfileSourceMetadata(key, json.getBoolean("encrypted"), download, logical, downloadedSource)
        }
        private fun encodeIdentity(secret: AgeSecret?): String? {
            val bytes = secret?.copyBytes() ?: return null
            try { return if (bytes.isEmpty()) null else Base64.getEncoder().encodeToString(bytes) } finally { bytes.fill(0) }
        }
    }
}

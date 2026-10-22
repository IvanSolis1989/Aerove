package dev.aerove.lite.updates

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

const val LITE_REPOSITORY = "IvanSolis1989/Aerove"
const val LITE_APK_NAME = "AEROVE-Lite-android-arm64.apk"
const val LITE_SIGNER_SHA256 = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8"
const val LITE_PACKAGE = "dev.aerove.lite"
internal const val MAX_APK_BYTES = 256L * 1024 * 1024

data class UpdateIdentity(val version: String, val versionCode: Long, val supportedAbis: List<String>)
data class LiteRelease(val tag: String, val version: String, val versionCode: Long, val notes: String,
    val apkUrl: String, val sumsUrl: String, val buildInfoUrl: String, val bytes: Long)

internal data class ProductVersion(val parts: List<Int>, val pre: List<String>) : Comparable<ProductVersion> {
    override fun compareTo(other: ProductVersion): Int {
        parts.zip(other.parts).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (pre.isEmpty() || other.pre.isEmpty()) return if (pre.isEmpty() == other.pre.isEmpty()) 0 else if (pre.isEmpty()) 1 else -1
        pre.zip(other.pre).forEach { (a, b) ->
            val numericA = a.all(Char::isDigit); val numericB = b.all(Char::isDigit)
            val result = when {
                numericA && numericB -> a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
                numericA != numericB -> if (numericA) -1 else 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(value: String): ProductVersion {
            val match = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?").matchEntire(value)
                ?: throw IllegalArgumentException("Invalid Lite version")
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            require(pre.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) { "Invalid prerelease version" }
            return ProductVersion((1..3).map { match.groupValues[it].toInt() }, pre)
        }
    }
}

internal fun trustedAssetUrl(raw: String, tag: String, name: String): String {
    val url = raw.toHttpUrl()
    require(url.isHttps && url.host == "github.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        url.fragment == null && url.query == null && url.pathSegments == listOf("IvanSolis1989", "Aerove", "releases", "download", tag, name)) {
        "Untrusted Lite release asset"
    }
    return url.toString()
}

internal fun parseLiteReleases(array: JSONArray): List<LiteRelease> = (0 until array.length()).mapNotNull { index ->
    val row = array.getJSONObject(index)
    if (row.optBoolean("draft", false)) return@mapNotNull null
    val tag = row.optString("tag_name")
    val match = Regex("lite-v(.+)\\+([1-9][0-9]{0,9})").matchEntire(tag) ?: return@mapNotNull null
    val version = match.groupValues[1]
    runCatching { ProductVersion.parse(version) }.getOrNull() ?: return@mapNotNull null
    val code = match.groupValues[2].toLong().also { require(it <= 2_100_000_000L) { "Invalid Lite Android version code" } }
    val assets = row.getJSONArray("assets")
    fun asset(name: String): JSONObject {
        val found = (0 until assets.length()).map(assets::getJSONObject).filter { it.optString("name") == name }
        require(found.size == 1) { "Lite release is missing an unambiguous $name" }
        return found.single()
    }
    val apk = asset(LITE_APK_NAME)
    val bytes = apk.getLong("size").also { require(it in 1..MAX_APK_BYTES) { "Invalid Lite APK size" } }
    LiteRelease(tag, version, code, row.optString("body").take(100_000),
        trustedAssetUrl(apk.getString("browser_download_url"), tag, LITE_APK_NAME),
        trustedAssetUrl(asset("SHA256SUMS").getString("browser_download_url"), tag, "SHA256SUMS"),
        trustedAssetUrl(asset("BUILD_INFO.json").getString("browser_download_url"), tag, "BUILD_INFO.json"), bytes)
}

internal fun selectLiteRelease(releases: List<LiteRelease>, identity: UpdateIdentity): LiteRelease? {
    val current = ProductVersion.parse(identity.version)
    val newer = releases.filter { it.versionCode > identity.versionCode && ProductVersion.parse(it.version) > current }
    val release = newer.maxWithOrNull(compareBy<LiteRelease> { ProductVersion.parse(it.version) }.thenBy { it.versionCode })
    require(release == null || "arm64-v8a" in identity.supportedAbis) { "No compatible Lite update asset for this device ABI" }
    return release
}

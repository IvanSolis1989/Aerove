package dev.aerove.lite.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import dev.aerove.lite.data.LiteSettingsStore
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidUpdateVerifier(private val context: Context) : UpdateApkVerifier {
    override suspend fun verify(file: File, release: LiteRelease) = withContext(Dispatchers.IO) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(context.packageName == LITE_PACKAGE && installed.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) } == listOf(LITE_SIGNER_SHA256)) {
            "Current installation does not use the Lite release identity"
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("Invalid Android application package")
        require(archive.packageName == LITE_PACKAGE && archive.versionName == release.version && archive.longVersionCode == release.versionCode &&
            archive.longVersionCode > installed.longVersionCode &&
            archive.applicationInfo?.minSdkVersion == 33 &&
            archive.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) } == listOf(LITE_SIGNER_SHA256)) { "Unexpected APK package, version or signing identity" }
        require("arm64-v8a" in Build.SUPPORTED_ABIS) { "APK ABI is not supported on this device" }
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= 100_000 && entries.map { it.name }.distinct().size == entries.size) { "Invalid APK entries" }
            val libraries = entries.filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            require(libraries.any { it.name == "lib/arm64-v8a/libaerove_core.so" }) { "Lite native core is missing" }
            libraries.forEach { library ->
                require(library.name.split('/').getOrNull(1) == "arm64-v8a") { "Unexpected native ABI" }
                val header = zip.getInputStream(library).use { it.readNBytes(20) }
                require(header.size == 20 && header.take(6) == listOf<Byte>(0x7f, 0x45, 0x4c, 0x46, 2, 1) &&
                    header[18].toInt() and 255 == 183 && header[19] == 0.toByte()) { "Invalid native ELF ABI" }
            }
        }
    }
}

interface UpdatePlatform {
    suspend fun identity(): UpdateIdentity
    suspend fun automaticEnabled(): Boolean
    suspend fun promptedTag(): String?
    suspend fun savePromptedTag(tag: String)
    suspend fun canInstall(): Boolean
    suspend fun installUri(file: File): String
    val packageName: String
}

class AndroidUpdatePlatform(private val context: Context, private val settings: LiteSettingsStore) : UpdatePlatform {
    private val preferences = context.getSharedPreferences("lite-update-prompts", Context.MODE_PRIVATE)
    override val packageName: String get() = context.packageName
    override suspend fun identity(): UpdateIdentity = withContext(Dispatchers.IO) {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        UpdateIdentity(checkNotNull(info.versionName).removeSuffix("+debug"), info.longVersionCode, Build.SUPPORTED_ABIS.toList())
    }
    override suspend fun automaticEnabled(): Boolean = settings.read().autoCheckUpdate
    override suspend fun promptedTag(): String? = preferences.getString("tag", null)
    override suspend fun savePromptedTag(tag: String) { check(preferences.edit().putString("tag", tag).commit()) { "Cannot save update prompt" } }
    override suspend fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    override suspend fun installUri(file: File): String = FileProvider.getUriForFile(context, "${context.packageName}.updates", file).toString()
}

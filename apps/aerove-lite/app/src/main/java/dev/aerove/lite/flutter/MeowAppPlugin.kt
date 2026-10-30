package dev.aerove.lite.flutter

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.aerove.lite.appaccess.AndroidApplicationLoader
import dev.aerove.lite.appaccess.InstalledApplication
import dev.aerove.lite.appaccess.InstalledApplicationSnapshot
import dev.aerove.lite.appaccess.validAccessPackage
import dev.aerove.lite.platform.LauncherAction
import dev.aerove.lite.updates.LITE_APK_NAME
import dev.aerove.lite.updates.ProductVersion
import dev.aerove.lite.updates.trustedAssetUrl
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Client platform operations only; engine/activity detachment does not stop the owned VPN. */
class MeowAppPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private lateinit var scope: CoroutineScope
    private lateinit var applications: AndroidApplicationLoader
    private var packageChanges: AutoCloseable? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var toast: Toast? = null
    @Volatile private var attached = false
    private val main = Handler(Looper.getMainLooper())
    private val replies = ConcurrentHashMap.newKeySet<Reply>()
    private val packageMutex = Mutex()
    @Volatile private var packageGeneration = 0L
    private var snapshot: Pair<Long, InstalledApplicationSnapshot>? = null
    private val permissions = mutableMapOf<Int, (Boolean) -> Unit>()
    private val iconLock = Any()
    private var floatingIme = false
    private val permissionListener = PluginRegistry.RequestPermissionsResultListener { code, _, results ->
        val callback = permissions.remove(code) ?: return@RequestPermissionsResultListener false
        callback(results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED)
        true
    }
    private val imeListener = OnApplyWindowInsetsListener { view, insets ->
        val ime = WindowInsetsCompat.Type.ime()
        reportFloatingIme(insets.isVisible(ime) && insets.getInsets(ime).bottom == 0)
        ViewCompat.onApplyWindowInsets(view, insets)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        check(!attached) { "App platform plugin is already attached" }
        context = binding.applicationContext
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        applications = AndroidApplicationLoader(context)
        snapshot = null
        packageGeneration++
        channel = MethodChannel(binding.binaryMessenger, "com.follow.clash/app")
        attached = true
        channel.setMethodCallHandler(this)
        packageChanges = applications.observeChanges {
            packageGeneration++
            onMain { if (attached) channel.invokeMethod("packagesChanged", null) }
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val reply = Reply(result).also(replies::add)
        if (!attached) return reply.error("detached", "App platform engine is detached")
        try {
            when (call.method) {
                "moveTaskToBack" -> reply.success(activityBinding?.activity?.moveTaskToBack(true) ?: false)
                "getSupportedAbis" -> reply.success(Build.SUPPORTED_ABIS.toList())
                "getPackages" -> background(reply) { meowPackagesJson(installed().applications) }
                "getChinaPackageNames" -> background(reply) {
                    JSONArray(applications.chinaPackages(installed().applications).sorted()).toString()
                }
                "isInstalledAppsPermissionGranted" -> background(reply) { installedAppsPermissionGranted() }
                "requestInstalledAppsPermission" -> {
                    if (installedAppsPermissionGranted()) reply.success(true)
                    else requestPermission(AndroidApplicationLoader.INSTALLED_APPS_PERMISSION, APPS_PERMISSION) {
                        packageGeneration++
                        reply.success(it)
                    }
                }
                "requestLocalNetworkPermission" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) reply.success(true)
                    else requestPermission(Manifest.permission.ACCESS_LOCAL_NETWORK, LOCAL_NETWORK_PERMISSION, reply::success)
                }
                "getPackageIcon" -> {
                    val name = call.argument<String>("packageName") ?: ""
                    require(name.isEmpty() || validAccessPackage(name)) { "Invalid packageName" }
                    background(reply) { packageIcon(name) }
                }
                "tip" -> {
                    val message = requireNotNull(call.argument<String>("message")) { "Missing message" }
                    toast?.cancel()
                    toast = Toast.makeText(context, message, Toast.LENGTH_LONG).also(Toast::show)
                    reply.success(true)
                }
                "openAppSettings" -> reply.success(openAppSettings())
                "getLastExitInfo" -> background(reply) { lastExitInfo() }
                "updateExcludeFromRecents" -> reply.success(excludeFromRecents(
                    requireNotNull(call.argument<Boolean>("value")) { "Missing value" },
                ))
                "initShortcuts" -> {
                    meowShortcutLabels(call.arguments)
                    reply.error("platform_integration_required",
                        "Lite quick actions still read the retired Kotlin profile/settings stores; runtime-handoff dispatch must be connected before publishing")
                }
                "downloadUpdate" -> {
                    val url = requireNotNull(call.argument<String>("url")) { "Missing url" }
                    val fileName = requireNotNull(call.argument<String>("fileName")) { "Missing fileName" }
                    val trusted = meowUpdateAsset(url, fileName)
                    background(reply) { enqueueUpdate(trusted, fileName) }
                }
                else -> reply.notImplemented()
            }
        } catch (error: Exception) {
            reply.error("bad_argument", error.message ?: error.toString())
        }
    }

    private suspend fun installed(): InstalledApplicationSnapshot = packageMutex.withLock {
        val generation = packageGeneration
        snapshot?.takeIf { it.first == generation }?.second ?: applications.load().also {
            currentCoroutineContext().ensureActive()
            if (generation != packageGeneration) throw CancellationException("Package catalog superseded")
            snapshot = generation to it
        }
    }

    private fun installedAppsPermissionGranted(): Boolean = try {
        context.packageManager.getPermissionInfo(AndroidApplicationLoader.INSTALLED_APPS_PERMISSION, 0)
        context.checkSelfPermission(AndroidApplicationLoader.INSTALLED_APPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    } catch (_: PackageManager.NameNotFoundException) { true }

    fun requestNotificationPermission(callback: (Boolean) -> Unit) = onMain {
        requestPermission(Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_PERMISSION, callback)
    }

    private fun requestPermission(permission: String, code: Int, callback: (Boolean) -> Unit) = onMain {
        if (!attached) return@onMain callback(false)
        val previous = permissions.put(code, callback)
        previous?.invoke(false)
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            permissions.remove(code)?.invoke(true)
            return@onMain
        }
        if (previous != null) return@onMain
        val activity = activityBinding?.activity
        if (activity == null) {
            permissions.remove(code)?.invoke(false)
            return@onMain
        }
        try {
            activity.requestPermissions(arrayOf(permission), code)
        } catch (_: RuntimeException) {
            permissions.remove(code)?.invoke(false)
        }
    }

    private fun openAppSettings(): Boolean {
        val activity = activityBinding?.activity ?: return false
        return try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
            true
        } catch (_: RuntimeException) { false }
    }

    private fun excludeFromRecents(exclude: Boolean): Boolean {
        val activity = activityBinding?.activity ?: return false
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val task = manager.appTasks.firstOrNull { it.taskInfo?.taskId == activity.taskId } ?: return false
        task.setExcludeFromRecents(exclude)
        return true
    }

    private fun lastExitInfo(): Map<String, Any?>? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 0)
            .firstOrNull { it.processName == context.packageName } ?: return null
        return mapOf("reason" to exit.reason, "timestamp" to exit.timestamp, "description" to exit.description)
    }

    private suspend fun packageIcon(packageName: String): String {
        val iconDirectory = File(context.cacheDir, "flutter-package-icons")
        check(iconDirectory.isDirectory || iconDirectory.mkdirs()) { "Cannot create icon cache" }
        val updated = runCatching { context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrNull()
        val name = if (updated == null) "default_icon" else "${packageName}_$updated"
        val target = File(iconDirectory, "$name.webp")
        if (target.isFile && System.currentTimeMillis() - target.lastModified() < ICON_TTL_MS) return target.path
        val bitmap = applications.icon(packageName) ?: context.packageManager.defaultActivityIcon.toBitmap(128, 128)
        currentCoroutineContext().ensureActive()
        synchronized(iconLock) {
            val temporary = File.createTempFile(".icon-", ".tmp", iconDirectory)
            try {
                temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it)) }
                iconDirectory.listFiles()?.filter { it != temporary && it != target &&
                    (System.currentTimeMillis() - it.lastModified() >= ICON_TTL_MS ||
                        packageName.isNotEmpty() && it.name.startsWith("${packageName}_")) }?.forEach(File::delete)
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        currentCoroutineContext().ensureActive()
        return target.path
    }

    @Synchronized
    private fun enqueueUpdate(url: String, fileName: String): Long {
        val preferences = context.getSharedPreferences("meow-flutter-update-download", Context.MODE_PRIVATE)
        val manager = context.getSystemService(DownloadManager::class.java) ?: error("DownloadManager unavailable")
        val previousId = preferences.getLong("id", -1)
        if (previousId > 0 && preferences.getString("url", null) == url) {
            manager.query(DownloadManager.Query().setFilterById(previousId))?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED) ||
                        status == DownloadManager.STATUS_SUCCESSFUL && runCatching {
                            manager.openDownloadedFile(previousId)?.use { true } ?: false
                        }.getOrDefault(false)) return previousId
                }
            }
        }
        val request = DownloadManager.Request(Uri.parse(url)).setTitle(fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${System.currentTimeMillis()}-$fileName")
        return manager.enqueue(request).also { id ->
            check(id > 0) { "DownloadManager returned an invalid download ID" }
            if (!preferences.edit().putString("url", url).putLong("id", id).commit()) {
                manager.remove(id)
                error("Cannot save download receipt")
            }
        }
    }

    private fun background(reply: Reply, block: suspend () -> Any?) {
        scope.launch {
            try {
                val value = block()
                currentCoroutineContext().ensureActive()
                reply.success(value)
            } catch (cancelled: CancellationException) {
                reply.error("cancelled", "App platform operation cancelled")
            } catch (error: Exception) {
                reply.error("platform_error", error.message ?: error.toString())
            }
        }
    }

    private inner class Reply(private val result: MethodChannel.Result) {
        private val finished = AtomicBoolean(false)
        fun success(value: Any?) = finish { result.success(value) }
        fun error(code: String, message: String) = finish { result.error(code, message, null) }
        fun notImplemented() = finish(result::notImplemented)
        private fun finish(action: () -> Unit) {
            onMain {
                if (finished.compareAndSet(false, true)) {
                    replies.remove(this)
                    action()
                }
            }
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        detachActivity()
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissionListener)
        if (isTelevision()) ViewCompat.setOnApplyWindowInsetsListener(binding.activity.window.decorView, imeListener)
    }

    override fun onDetachedFromActivityForConfigChanges() = detachActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) = onAttachedToActivity(binding)
    override fun onDetachedFromActivity() {
        detachActivity()
        resolvePermissions()
    }

    private fun detachActivity() {
        activityBinding?.let { binding ->
            binding.removeRequestPermissionsResultListener(permissionListener)
            if (isTelevision()) ViewCompat.setOnApplyWindowInsetsListener(binding.activity.window.decorView, null)
        }
        activityBinding = null
        reportFloatingIme(false)
    }

    private fun isTelevision(): Boolean = context.packageManager.let {
        @Suppress("DEPRECATION")
        it.hasSystemFeature(PackageManager.FEATURE_TELEVISION) || it.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private fun reportFloatingIme(value: Boolean) {
        if (value == floatingIme) return
        floatingIme = value
        if (attached) channel.invokeMethod("floatingImeChanged", value)
    }

    private fun resolvePermissions() {
        val pending = permissions.values.toList()
        permissions.clear()
        pending.forEach { it(false) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        if (!attached) return
        attached = false
        channel.setMethodCallHandler(null)
        detachActivity()
        resolvePermissions()
        packageChanges?.close()
        packageChanges = null
        scope.cancel()
        applications.close()
        snapshot = null
        toast?.cancel()
        toast = null
        replies.toList().forEach { it.error("detached", "App platform engine is detached") }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val APPS_PERMISSION = 7711
        const val LOCAL_NETWORK_PERMISSION = 7712
        const val NOTIFICATION_PERMISSION = 7713
        const val ICON_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

internal fun meowPackagesJson(applications: List<InstalledApplication>): String = JSONArray(applications.map {
    JSONObject().put("packageName", it.packageName).put("label", it.label).put("system", it.system)
        .put("internet", it.internet).put("lastUpdateTime", it.lastUpdateTime)
}).toString()

internal fun meowShortcutLabels(arguments: Any?): Map<LauncherAction, String> {
    val values = arguments as? Map<*, *> ?: throw IllegalArgumentException("Shortcut labels must be a map")
    return LauncherAction.entries.associateWith { action ->
        values[action.name.lowercase()] as? String ?: throw IllegalArgumentException("Missing ${action.name.lowercase()} label")
    }
}

internal fun meowUpdateAsset(raw: String, fileName: String): String {
    require(fileName == LITE_APK_NAME) { "Only the Lite release APK is supported" }
    val url = raw.toHttpUrl()
    require(url.pathSegments.size == 6) { "Invalid Lite release asset path" }
    val tag = url.pathSegments[4]
    val parts = Regex("lite-v(.+)\\+([1-9][0-9]{0,9})").matchEntire(tag)
        ?: throw IllegalArgumentException("Invalid Lite release tag")
    ProductVersion.parse(parts.groupValues[1])
    require(parts.groupValues[2].toLong() <= 2_100_000_000L) { "Invalid Lite install version code" }
    return trustedAssetUrl(raw, tag, fileName)
}

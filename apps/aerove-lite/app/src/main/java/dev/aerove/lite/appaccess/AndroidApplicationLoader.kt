package dev.aerove.lite.appaccess

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class InstalledApplicationSnapshot(val applications: List<InstalledApplication>, val permissionGranted: Boolean)

interface ApplicationLoader : AutoCloseable {
    suspend fun load(): InstalledApplicationSnapshot
    suspend fun chinaPackages(applications: List<InstalledApplication>): Set<String>
    suspend fun icon(packageName: String): Bitmap? = null
    fun observeChanges(changed: () -> Unit): AutoCloseable = AutoCloseable { }
    override fun close() = Unit
}

class AndroidApplicationLoader(context: Context) : ApplicationLoader {
    private val application = context.applicationContext
    private val packages = application.packageManager
    private val icons = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override suspend fun load(): InstalledApplicationSnapshot = withContext(Dispatchers.IO) {
        val granted = runCatching { packages.getPermissionInfo(INSTALLED_APPS_PERMISSION, 0) }.isFailure ||
            packages.checkPermission(INSTALLED_APPS_PERMISSION, application.packageName) == PackageManager.PERMISSION_GRANTED
        val result = try { packages.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())) }
        catch (_: SecurityException) { return@withContext InstalledApplicationSnapshot(emptyList(), false) }
        require(result.size <= MAX_ACCESS_PACKAGES) { "Too many installed applications" }
        val applications = result.mapNotNull { info ->
            currentCoroutineContext().ensureActive()
            if (info.packageName == application.packageName || info.packageName == "android") null else InstalledApplication(
                info.packageName, info.applicationInfo?.loadLabel(packages)?.toString() ?: info.packageName,
                info.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 } == true,
                info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true,
                info.lastUpdateTime,
            )
        }
        InstalledApplicationSnapshot(applications, applications.isNotEmpty() || granted)
    }

    override suspend fun chinaPackages(applications: List<InstalledApplication>): Set<String> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        applications.mapNotNull { application ->
            context.ensureActive()
            val match = try {
                if (StandardChinaPackageMatcher.isSkipped(application.packageName)) false
                else if (StandardChinaPackageMatcher.matchesKnownPrefix(application.packageName)) true
                else {
                    val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
                    val info = packages.getPackageInfo(application.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
                    val components = sequence {
                        yieldAll(info.services.orEmpty().asSequence().map(ComponentInfo::name))
                        yieldAll(info.activities.orEmpty().asSequence().map(ComponentInfo::name))
                        yieldAll(info.receivers.orEmpty().asSequence().map(ComponentInfo::name))
                        yieldAll(info.providers.orEmpty().asSequence().map(ComponentInfo::name))
                    }
                    classifyApplication(application.packageName, components, info.applicationInfo?.publicSourceDir?.let(::File)) { context.ensureActive() }.matches
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false } // Standard's runCatching fallback when an installed package disappears/is unreadable.
            application.packageName.takeIf { match }
        }.toSet()
    }

    override suspend fun icon(packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(icons) { icons.get(packageName) }?.let { return@withContext it }
        currentCoroutineContext().ensureActive()
        val drawable = try { packages.getApplicationIcon(packageName) }
        catch (_: PackageManager.NameNotFoundException) { return@withContext null }
        catch (_: SecurityException) { return@withContext null }
        val edge = (48 * application.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, edge, edge)
        drawable.draw(Canvas(bitmap))
        currentCoroutineContext().ensureActive()
        synchronized(icons) { icons.put(packageName, bitmap) }
        bitmap
    }

    override fun observeChanges(changed: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.data?.schemeSpecificPart == application.packageName) return
                if (intent?.action == Intent.ACTION_PACKAGE_ADDED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                synchronized(icons) { icons.evictAll() }
                changed()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        application.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        return AutoCloseable { application.unregisterReceiver(receiver) }
    }

    override fun close() { synchronized(icons) { icons.evictAll() } }

    companion object { const val INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS" }
}

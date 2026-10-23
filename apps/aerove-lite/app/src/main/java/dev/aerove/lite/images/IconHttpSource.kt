package dev.aerove.lite.images

import dev.aerove.lite.data.SubscriptionDownloader
import dev.aerove.lite.data.awaitResponse
import dev.aerove.lite.requests.DownloadCancellation
import dev.aerove.lite.requests.RequestPreferences
import dev.aerove.lite.requests.forDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Properties
import java.security.MessageDigest
import okhttp3.CacheControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class IconHttpBytes(val bytes: ByteArray, val validUntil: Long, val cacheable: Boolean)

internal class IconHttpSource(client: OkHttpClient, cacheDirectory: File, private val clock: () -> Long = System::currentTimeMillis,
    private val routeClient: (OkHttpClient, okhttp3.HttpUrl) -> OkHttpClient = { supplied, _ -> supplied }) : AutoCloseable {
    private val touchedFile = File(cacheDirectory, "icon-touched.properties")
    private val touched = LinkedHashMap<String, Long>()
    private var indexLoaded = false
    private val cache = Cache(cacheDirectory, 16L shl 20)
    private val http = client.newBuilder().cache(cache)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            response.newBuilder().apply {
                if (!response.isSuccessful && response.code != 304) header("Cache-Control", "no-store")
                else if (response.code != 304 && response.header("Cache-Control") == null && response.header("Expires") == null) header("Cache-Control", "max-age=604800")
                removeHeader("X-Aerove-Icon-Cache-Control")
                val original = if (!response.isSuccessful && response.code != 304) "no-store" else response.header("Cache-Control")
                if (original != null) {
                    header("X-Aerove-Icon-Cache-Control", original)
                    if (!response.cacheControl.noStore) {
                        val storedControl = if (response.cacheControl.noCache) "max-age=0" else original
                            .split(',').filterNot { it.trim().equals("must-revalidate", true) || it.trim().equals("proxy-revalidate", true) }.joinToString(",")
                        header("Cache-Control", storedControl)
                    }
                }
                val vary = response.headers("Vary").joinToString(",")
                if (vary != "*" && !vary.split(',').any { it.trim().equals("User-Agent", true) })
                    header("Vary", listOf(vary, "User-Agent").filter(String::isNotBlank).joinToString(", "))
            }.build()
        }.build()

    suspend fun read(key: IconRequestKey): ByteArray = readResult(key)!!.bytes

    suspend fun preview(key: IconRequestKey): IconHttpBytes? = if (!key.checkCertificate) null else readResult(key, true)

    suspend fun readResult(key: IconRequestKey, cachedOnly: Boolean = false): IconHttpBytes? = withTimeout(20_000) {
        withContext(Dispatchers.IO) {
            if (key.checkCertificate) maintain(null, true)
            val scoped = http.forDownload(RequestPreferences(globalUserAgent = key.userAgent, checkCertificate = key.checkCertificate))
                .let { if (key.checkCertificate) it else it.newBuilder().cache(null).build() }
            val cancellation = DownloadCancellation(currentCoroutineContext())
            try {
                var target = SubscriptionDownloader.parseUrl(key.url)
                repeat(6) { redirect ->
                    currentCoroutineContext().ensureActive()
                    val request = Request.Builder().url(target).header("User-Agent", key.userAgent)
                        .header("Accept", "image/svg+xml,image/*;q=0.9").apply {
                            if (cachedOnly) cacheControl(CacheControl.Builder().onlyIfCached().maxStale(Int.MAX_VALUE, TimeUnit.SECONDS).build())
                        }.build()
                    cancellation.track(routeClient(scoped, target).newCall(request)).awaitResponse().use { response ->
                        if (cachedOnly && response.code == 504) return@withContext null
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            if (redirect == 5) throw IOException("Icon redirect limit exceeded")
                            val next = response.header("Location")?.let(target::resolve) ?: throw IOException("Invalid icon redirect")
                            SubscriptionDownloader.parseUrl(next.toString())
                            if (target.isHttps && !next.isHttps) throw IOException("Icon HTTPS downgrade refused")
                            target = next
                        } else {
                            if (!response.isSuccessful) throw IOException("Icon HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty icon response")
                            if (body.contentLength() > MAX_ICON_BYTES) throw IOException("Icon body exceeds limit")
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size().toLong() + count > MAX_ICON_BYTES) throw IOException("Icon body exceeds limit")
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (output.size() == 0) throw IOException("Empty icon body")
                            val control = response.header("X-Aerove-Icon-Cache-Control")?.let {
                                CacheControl.parse(okhttp3.Headers.Builder().add("Cache-Control", it).build())
                            } ?: response.cacheControl
                            val cacheable = !control.noStore
                            val now = clock()
                            val age = response.header("Age")?.toLongOrNull()?.coerceIn(0, Long.MAX_VALUE / 1000) ?: 0
                            val date = response.headers.getDate("Date")?.time ?: response.receivedResponseAtMillis
                            val lifetime = when {
                                control.noCache -> 0L
                                control.maxAgeSeconds >= 0 -> control.maxAgeSeconds * 1000L
                                response.headers.getDate("Expires") != null -> (response.headers.getDate("Expires")!!.time - date).coerceAtLeast(0)
                                else -> 604800000L
                            }
                            val expiry = now + (lifetime - maxOf(age * 1000, now - date)).coerceAtLeast(0)
                            val requiresValidation = control.mustRevalidate || control.toString().split(',')
                                .any { it.trim().equals("proxy-revalidate", true) }
                            if (cachedOnly && (control.noCache || (expiry <= now && requiresValidation))) return@withContext null
                            if (key.checkCertificate) maintain(target.toString(), cacheable)
                            return@withContext IconHttpBytes(output.toByteArray(), expiry, cacheable)
                        }
                    }
                }
                throw IOException("Icon redirect limit exceeded")
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } finally {
                cancellation.close()
                if (scoped !== http) scoped.connectionPool.evictAll()
            }
        }
    }

    @Synchronized private fun maintain(url: String?, cacheable: Boolean) {
        val now = clock()
        if (!indexLoaded) {
            indexLoaded = true
            if (touchedFile.isFile && touchedFile.length() <= 256 * 1024) {
                val properties = Properties()
                runCatching { touchedFile.inputStream().use(properties::load) }
                properties.stringPropertyNames().filter { it.matches(Regex("[0-9a-f]{64}")) }.take(200).forEach { name ->
                    properties.getProperty(name).toLongOrNull()?.let { touched[name] = it }
                }
            }
        }
        if (url != null) { if (cacheable) touched[identity(url)] = now else touched.remove(identity(url)) }
        cache.flush()
        val entries = cache.urls()
        val retained = ArrayList<String>()
        while (entries.hasNext()) {
            val entry = entries.next()
            val last = touched[identity(entry)] ?: now.also { touched[identity(entry)] = it }
            if ((!cacheable && entry == url) || now - last > 30L * 86400000) {
                entries.remove(); touched.remove(identity(entry))
            } else retained.add(entry)
        }
        val evict = retained.sortedBy { touched[identity(it)] ?: 0 }.take((retained.size - 200).coerceAtLeast(0)).toSet()
        if (evict.isNotEmpty()) {
            val iterator = cache.urls()
            while (iterator.hasNext()) if (iterator.next() in evict) iterator.remove()
        }
        touched.keys.retainAll((retained.toSet() - evict).map(::identity).toSet())
        val properties = Properties()
        touched.forEach { (entry, time) -> properties.setProperty(entry, time.toString()) }
        touchedFile.outputStream().use { properties.store(it, null) }
    }

    private fun identity(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override fun close() = cache.close()
    companion object { const val MAX_ICON_BYTES = 16L shl 20 }
}

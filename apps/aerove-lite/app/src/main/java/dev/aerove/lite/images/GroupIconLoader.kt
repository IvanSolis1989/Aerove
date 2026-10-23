package dev.aerove.lite.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.graphics.drawable.AnimatedImageDrawable
import com.caverock.androidsvg.SVG
import dev.aerove.lite.data.SubscriptionDownloader
import dev.aerove.lite.requests.RequestPolicySource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface GroupIconImage {
    data class Raster(val bitmap: Bitmap) : GroupIconImage
    class Encoded(val state: Drawable.ConstantState, val width: Int, val height: Int) : GroupIconImage
    class Animated(val bytes: ByteArray) : GroupIconImage
}

fun interface GroupIconLoader {
    suspend fun load(url: String): GroupIconImage
    fun observe(url: String): Flow<GroupIconImage> = flow { emit(load(url)) }
}

internal class NetworkGroupIconLoader(client: okhttp3.OkHttpClient, private val policySource: RequestPolicySource,
    ownerScope: CoroutineScope, cacheDirectory: File,
    routeClient: (okhttp3.OkHttpClient, okhttp3.HttpUrl) -> okhttp3.OkHttpClient = { supplied, _ -> supplied }) : GroupIconLoader, AutoCloseable {
    private val scope = CoroutineScope(ownerScope.coroutineContext + SupervisorJob(ownerScope.coroutineContext[Job]) + Dispatchers.IO)
    private val http = IconHttpSource(client, cacheDirectory, routeClient = routeClient)
    private val store = IconRequestStore(scope, preview = { key ->
        try { if (key.url.startsWith("data:")) null else http.preview(key)?.let { WeightedIcon(decodeGroupIcon(it.bytes), it.bytes.size + DECODE_EXTENT * DECODE_EXTENT * 4, it.validUntil, it.cacheable) } }
        catch (failure: Exception) { currentCoroutineContext().ensureActive(); null }
    }) { key ->
        val inline = key.url.startsWith("data:")
        val response = if (inline) null else http.readResult(key)!!
        val bytes = if (inline) decodeIconDataUri(key.url) else response!!.bytes
        currentCoroutineContext().ensureActive()
        val image = decodeGroupIcon(bytes)
        WeightedIcon(image, bytes.size + DECODE_EXTENT * DECODE_EXTENT * 4 + if (inline) key.url.length * 2 else 0,
            response?.validUntil ?: Long.MAX_VALUE, response?.cacheable ?: true)
    }

    override suspend fun load(url: String): GroupIconImage = observe(url).last()

    override fun observe(url: String): Flow<GroupIconImage> = flow {
        if (url.startsWith("data:")) {
            require(url.length <= (IconHttpSource.MAX_ICON_BYTES + 2) / 3 * 4 + 128) { "Icon data URI exceeds limit" }
            store.observe(IconRequestKey(url, "", true)).collect { emit(it) }
            return@flow
        }
        require(url.length <= 8192) { "Icon URL exceeds limit" }
        val target = SubscriptionDownloader.parseUrl(url)
        val policy = policySource.snapshot()
        store.observe(IconRequestKey(target.toString(), policy.globalUserAgent ?: policySource.defaultUserAgent, policy.checkCertificate)).collect { emit(it) }
    }

    override fun close() { scope.cancel(); http.close() }
    suspend fun closeAndJoin() { scope.coroutineContext[Job]?.cancelAndJoin(); http.close() }
}

internal fun decodeIconDataUri(value: String): ByteArray {
    val comma = value.indexOf(',')
    require(comma in 1..128 && value.substring(0, comma).matches(Regex("data:image/(png|svg\\+xml|jpeg|gif|webp);base64", RegexOption.IGNORE_CASE))) { "Invalid icon data URI" }
    val encoded = value.substring(comma + 1)
    require(encoded.length <= ((IconHttpSource.MAX_ICON_BYTES + 2) / 3 * 4)) { "Icon data URI exceeds limit" }
    val decoded = try { Base64.getDecoder().decode(encoded) } catch (failure: IllegalArgumentException) { throw IOException("Invalid icon base64", failure) }
    require(decoded.isNotEmpty() && decoded.size <= IconHttpSource.MAX_ICON_BYTES) { "Icon data URI exceeds limit" }
    return decoded
}

internal const val DECODE_EXTENT = 192

internal suspend fun decodeGroupIcon(bytes: ByteArray): GroupIconImage = withTimeout(20_000) { withContext(Dispatchers.IO) {
    val first = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    val svg = first.startsWith('<') || bytes.take(2).toByteArray().contentEquals(byteArrayOf(0xff.toByte(), 0xfe.toByte())) ||
        bytes.take(2).toByteArray().contentEquals(byteArrayOf(0xfe.toByte(), 0xff.toByte()))
    if (svg) {
        val context = currentCoroutineContext()
        val safe = IconSvgDocument.parse(bytes) { context.ensureActive() }
        var pixels = 0L
        safe.embeddedImages.forEach { image ->
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid embedded SVG image")
            pixels += bounds.outWidth.toLong() * bounds.outHeight
            if (pixels > 4L * 1024 * 1024) throw IOException("Embedded SVG pixel budget exceeded")
        }
        val picture = SVG.getFromString(safe.text)
        val bitmap = Bitmap.createBitmap(DECODE_EXTENT, DECODE_EXTENT, Bitmap.Config.ARGB_8888)
        if (picture.documentViewBox == null && picture.documentWidth > 0 && picture.documentHeight > 0)
            picture.setDocumentViewBox(0f, 0f, picture.documentWidth, picture.documentHeight)
        picture.setDocumentWidth(DECODE_EXTENT.toFloat())
        picture.setDocumentHeight(DECODE_EXTENT.toFloat())
        currentCoroutineContext().ensureActive()
        val canvas = object : Canvas(bitmap) {
            var operations = 0
            override fun save(): Int {
                context.ensureActive()
                if (++operations > 10000) throw IOException("SVG render operation budget exceeded")
                return super.save()
            }
        }
        picture.renderToCanvas(canvas)
        GroupIconImage.Raster(bitmap)
    } else {
        val drawable = decodeRasterIcon(bytes)
        val state = drawable.constantState
        when {
            state != null -> GroupIconImage.Encoded(state, drawable.intrinsicWidth, drawable.intrinsicHeight)
            drawable is AnimatedImageDrawable -> GroupIconImage.Animated(bytes)
            else -> throw IOException("Image cannot be independently drawn")
        }
    }
} }

internal suspend fun decodeRasterIcon(bytes: ByteArray): Drawable = withContext(Dispatchers.IO) {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            if (info.size.width <= 0 || info.size.height <= 0 || info.size.width.toLong() * info.size.height > 64L * 1024 * 1024)
                throw IOException("Icon dimensions exceed limit")
            val scale = minOf(1f, DECODE_EXTENT.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
        }
        currentCoroutineContext().ensureActive()
        drawable
}

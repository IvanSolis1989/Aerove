package dev.aerove.lite.images

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.drawable.AnimatedImageDrawable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupIconRenderingTest {
    @Test fun inlinePngAndSvgUseRealDecoderWithoutOpeningHttp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.cacheDir, "group-icon-inline-test").apply { mkdirs() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val policy = object : dev.aerove.lite.requests.RequestPolicySource {
            override val defaultUserAgent = "test"
            override fun snapshot() = dev.aerove.lite.requests.RequestPreferences()
        }
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { error("Inline image opened HTTP") }.build()
        val loader = NetworkGroupIconLoader(client, policy, scope, directory)
        try {
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val png = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
            assertTrue(loader.load("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)) is GroupIconImage.Encoded)
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1 1"><path d="M0 0H1V1H0Z" fill="#0000ff"/></svg>""".encodeToByteArray()
            val image = loader.load("data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(svg)) as GroupIconImage.Raster
            assertEquals(Color.BLUE, image.bitmap.getPixel(96, 96))
        } finally { loader.closeAndJoin(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun actualPngDecoderDrawsTheDownloadedPixels() = runBlocking {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().apply { source.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val image = decodeGroupIcon(bytes) as GroupIconImage.Encoded
        val target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        image.state.newDrawable().apply { setBounds(0, 0, 32, 32); draw(Canvas(target)) }
        assertEquals(Color.BLUE, target.getPixel(16, 16))
    }
    @Test fun actualSvgRendererDrawsGradientAndClipGeometry() = runBlocking {
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><defs>
            <linearGradient id="gradient"><stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/></linearGradient>
            <clipPath id="clip"><circle cx="12" cy="12" r="9"/></clipPath></defs>
            <path d="M0 0H24V24H0Z" fill="url(#gradient)" clip-path="url(#clip)"/></svg>""".toByteArray()
        val image = decodeGroupIcon(bytes) as GroupIconImage.Raster
        assertEquals(0, Color.alpha(image.bitmap.getPixel(0, 0)))
        val left = image.bitmap.getPixel(64, 96)
        val right = image.bitmap.getPixel(128, 96)
        assertTrue(Color.alpha(left) > 200)
        assertTrue(Color.red(left) > Color.red(right))
        assertTrue(Color.blue(right) > Color.blue(left))
    }
    @Test fun animatedRasterKeepsIndependentNativeDrawableAndStops() = runBlocking {
        val hex = "47494638396101000100800000000000ffffff21f904000a0000002c000000000100010000020244010021f904000a0000002c00000000010001000002024c01003b"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val image = decodeGroupIcon(bytes) as GroupIconImage.Animated
        val one = decodeRasterIcon(image.bytes) as AnimatedImageDrawable
        val two = decodeRasterIcon(image.bytes) as AnimatedImageDrawable
        assertNotSame(one, two)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertFalse(one.isRunning)
            one.start()
            assertTrue(one.isRunning)
            one.stop()
            assertFalse(one.isRunning)
            assertFalse(two.isRunning)
        }
    }
}

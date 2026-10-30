package dev.aerove.client.platform

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.fonts.SystemFonts
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build
import android.util.Xml
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

class SystemFontPlugin : FlutterPlugin {
    private var channel: MethodChannel? = null
    @Volatile private var catalog: FontCatalogCache? = null

    companion object {
        private val store = FontCatalogStore()
        private val watchingConfiguration = AtomicBoolean(false)

        // Process wide like the store, so a change while detached still lands.
        private fun watchConfiguration(context: Context) {
            if (!watchingConfiguration.compareAndSet(false, true)) return
            context.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = store.invalidate()

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() {}
            })
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val context = binding.applicationContext
        val cache = FontCatalogCache(store)
        catalog = cache
        watchConfiguration(context)
        channel = MethodChannel(binding.binaryMessenger, "com.follow.clash/fonts", StandardMethodCodec.INSTANCE,
            binding.binaryMessenger.makeBackgroundTaskQueue()).also { channel ->
            channel.setMethodCallHandler { call, result ->
                if (call.method == "list") {
                    runCatching { cache.read(::readFamilies) }
                        .onSuccess(result::success)
                        .onFailure { result.error("font_catalog", it.message, null) }
                } else result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        catalog?.close()
        catalog = null
    }

    private fun readFamilies(checkpoint: () -> Unit): List<String> {
        checkpoint()
        val available = if (Build.VERSION.SDK_INT >= 29) {
            SystemFonts.getAvailableFonts().mapNotNull { font ->
                checkpoint()
                font.file?.let { it.canonicalPath to font.ttcIndex }
            }.toSet()
        } else null
        val families = linkedMapOf<String, Pair<File, Int>>()
        for (root in listOf("/system", "/product", "/vendor", "/system_ext")) {
            for (configName in listOf("fonts.xml", "font_fallback.xml", "fonts_customization.xml")) {
                checkpoint()
                val config = File("$root/etc/$configName")
                if (!config.isFile || config.length() > 2L * 1024 * 1024) continue
                runCatching {
                    config.inputStream().use { stream ->
                        val xml = Xml.newPullParser().apply { setInput(stream, null) }
                        var family: String? = null
                        var primary: Pair<File, Int>? = null
                        var best = Int.MAX_VALUE
                        while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                            checkpoint()
                            if (xml.eventType == XmlPullParser.START_TAG) when (xml.name) {
                                "family" -> {
                                    family = xml.getAttributeValue(null, "name")
                                    primary = null
                                    best = Int.MAX_VALUE
                                }
                                "font" -> if (family != null) {
                                    val weight = xml.getAttributeValue(null, "weight")?.toIntOrNull() ?: 400
                                    val style = xml.getAttributeValue(null, "style")
                                    val score = kotlin.math.abs(weight - 400) / 100 + if (style == "italic") 2 else 0
                                    val index = xml.getAttributeValue(null, "index")?.toIntOrNull() ?: 0
                                    val name = buildString {
                                        while (xml.next() != XmlPullParser.END_TAG || xml.name != "font") {
                                            checkpoint()
                                            if (xml.eventType == XmlPullParser.TEXT) append(xml.text)
                                            if (xml.eventType == XmlPullParser.END_DOCUMENT) break
                                        }
                                    }.trim()
                                    val file = if (name.startsWith('/')) File(name) else File("$root/fonts/$name")
                                    if (score < best && file.isFile) {
                                        primary = file to index
                                        best = score
                                    }
                                }
                            }
                            if (xml.eventType == XmlPullParser.END_TAG && xml.name == "family") {
                                val name = family
                                val face = primary
                                if (name != null && face != null &&
                                    (families.size < 1024 || name in families)) families[name] = face
                                family = null
                            }
                            xml.next()
                        }
                    }
                }.onFailure { if (it is CancellationException) throw it }
            }
        }
        return families.filter { (name, declaredFace) ->
            checkpoint()
            val face = if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    val paint = Paint().apply { typeface = Typeface.create(name, Typeface.NORMAL) }
                    val run = TextRunShaper.shapeTextRun("A", 0, 1, 0, 1, 0f, 0f, false, paint)
                    if (run.glyphCount() == 0) null else run.getFont(0).let { font ->
                        font.file?.let { it to font.ttcIndex }
                    }
                }.getOrNull() ?: return@filter false
            } else declaredFace
            val (file, index) = face
            if (!sameFontFace(declaredFace, face)) return@filter false
            (available == null || (file.canonicalPath to index) in available) &&
                runCatching {
                    FileInputStream(file).channel.use { input ->
                        checkpoint()
                        val size = input.size()
                        size in 1..(64L * 1024 * 1024) &&
                            FontCoverage.supports(input.map(FileChannel.MapMode.READ_ONLY, 0, size), index)
                    }
                }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
        }.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}

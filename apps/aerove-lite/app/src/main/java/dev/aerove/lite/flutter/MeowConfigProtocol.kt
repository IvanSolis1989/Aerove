package dev.aerove.lite.flutter

import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.ConfigValidationException
import dev.aerove.lite.data.MAX_PROFILE_BYTES
import dev.aerove.lite.data.MeowProfileValidator
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativePreparedConfigValidator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MeowConfigProtocol(homeDir: File,
    private val validator: NativeConfigValidator = NativeConfigValidator { yaml ->
        JniResourceFetch.offlineRead { handle -> MeowNativeCore.validate(handle, yaml) }
    },
    private val verifiedNativeFeatures: () -> Set<String> = { MeowNativeCore.readVerifiedFeatures() },
    spillBudget: MeowSpillBudget = MeowSpillBudget(),
    private val maxInputBytes: Int = MAX_PROFILE_BYTES,
) : AutoCloseable {
    private val spill = MeowFlutterSpill(homeDir, spillBudget)
    private val home = spill.home
    @Volatile private var closed = false

    init { require(maxInputBytes in 1..MAX_PROFILE_BYTES) }

    suspend fun getConfig(path: String): String = fileResult("json") { MeowProfileCodec.scriptInput(read(path).second) }

    suspend fun getProfileBase(path: String): Map<String, Any?> = io {
        val (bytes, source) = read(path)
        MeowProfileCodec.profileBase(source, bytes)
    }

    suspend fun getProfileOutbounds(path: String): Map<String, Any?> = io { MeowProfileCodec.outbounds(read(path).second) }

    suspend fun buildProfile(path: String, overlay: String): String = fileResult("yaml") {
        if (overlay.toByteArray(Charsets.UTF_8).size > maxInputBytes)
            throw MeowConfigProtocolException("bad_argument", "Overlay exceeds the configuration byte budget", mapOf("field" to "overlay"))
        MeowProfileCodec.merge(read(path).second, overlay)
    }

    suspend fun validateConfig(path: String): String = io {
        val source = read(path).second
        try {
            MeowProfileValidator(validator,
                NativePreparedConfigValidator { _, _ -> error("Prepared validation requires a sealed resource handoff") },
                home, verifiedNativeFeatures).validate(source, managed = false, bundleDirectory = null)
            ""
        } catch (failure: ConfigValidationException) { failure.message ?: "$: Configuration validation failed" }
    }

    suspend fun invoke(method: String, arguments: Any?): Any = when (method) {
        "getConfig" -> getConfig(pathArgument(arguments))
        "getProfileBase" -> getProfileBase(pathArgument(arguments))
        "getProfileOutbounds" -> getProfileOutbounds(pathArgument(arguments))
        "validateConfig" -> validateConfig(pathArgument(arguments))
        "buildProfile" -> {
            val row = arguments as? Map<*, *> ?: badArgument("arguments", "Expected an object")
            if (row.keys != setOf("path", "overlay")) badArgument("arguments", "Expected exactly path and overlay")
            buildProfile(pathArgument(row["path"]), row["overlay"] as? String ?: badArgument("overlay", "Expected YAML text"))
        }
        else -> throw MeowConfigProtocolException("unsupported_method", "Unknown configuration method: $method")
    }

    fun discard(path: String) = spill.discard(path)

    override fun close() { closed = true; spill.close() }

    private suspend fun fileResult(extension: String, produce: suspend () -> String): String {
        var path: String? = null
        try {
            return io { spill.write(produce(), extension).also { path = it } }
        } catch (failure: Throwable) {
            path?.let { try { spill.discard(it) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
            throw failure
        }
    }

    private suspend fun <T> io(work: suspend () -> T): T = withContext(Dispatchers.IO) {
        if (closed) throw MeowConfigProtocolException("closed", "Configuration protocol is closed")
        currentCoroutineContext().ensureActive()
        work().also {
            currentCoroutineContext().ensureActive()
            if (closed) throw MeowConfigProtocolException("closed", "Configuration protocol is closed")
        }
    }

    private suspend fun read(path: String): Pair<ByteArray, String> {
        MeowFlutterSpill.checkedHome(home)
        val file = File(path)
        val normalized = file.absoluteFile
        if (!file.isAbsolute || normalized != file.canonicalFile || !normalized.toPath().startsWith(home.toPath()) ||
            normalized == home || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
            normalized.toPath() != normalized.toPath().toRealPath()) pathFailure()
        var component: File? = normalized
        while (component != home) {
            if (component == null || Files.isSymbolicLink(component.toPath())) pathFailure()
            component = component.parentFile
        }
        if (Files.size(file.toPath()) > maxInputBytes) throw MeowConfigProtocolException("input_too_large", "Configuration exceeds the byte budget")
        val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { stream ->
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (result.size().toLong() + count > maxInputBytes) throw MeowConfigProtocolException("input_too_large", "Configuration exceeds the byte budget")
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        val source = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw MeowConfigProtocolException("invalid_encoding", "Configuration must be UTF-8", cause = failure)
        }
        return bytes to source
    }

    private fun pathArgument(value: Any?): String = (value as? String)?.takeIf { it.isNotBlank() }
        ?: badArgument("path", "Expected a non-empty path")
    private fun badArgument(field: String, message: String): Nothing =
        throw MeowConfigProtocolException("bad_argument", message, mapOf("field" to field))
    private fun pathFailure(): Nothing = throw MeowConfigProtocolException("path_error", "Configuration must be a canonical private-home file without symbolic links")
}

package dev.aerove.lite.flutter

import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.ConfigValidationException
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowCompatibilityValidator
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.ProfileLinkDecoder
import dev.aerove.lite.data.ProfileLinkEncoder
import dev.aerove.lite.data.ProfileLinkEntry
import dev.aerove.lite.data.ProfileLinkErrorCode
import dev.aerove.lite.data.ProfileLinkLimits
import dev.aerove.lite.profilesource.AgeDecryptTransport
import dev.aerove.lite.profilesource.AgeProfileSourceDecoder
import dev.aerove.lite.profilesource.AgeSecret
import dev.aerove.lite.profilesource.AgeSourceException
import dev.aerove.lite.profilesource.MAX_CIPHERTEXT_BYTES
import dev.aerove.lite.profilesource.MAX_IDENTITY_BYTES
import dev.aerove.lite.regexp2.ExactFilterExecutor
import dev.aerove.lite.regexp2.FilterProtocol
import dev.aerove.lite.regexp2.IsolatedClientHelper
import dev.aerove.lite.regexp2.Regexp2Filters
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MeowOfflineMethods(homeDir: File,
    private val validator: NativeConfigValidator = NativeConfigValidator { yaml -> JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) } },
    private val features: () -> Set<String> = { MeowNativeCore.readVerifiedFeatures() },
    ageTransport: AgeDecryptTransport = AgeDecryptTransport { bytes, identities -> IsolatedClientHelper.decryptAge(bytes, identities) },
    private val filters: ExactFilterExecutor = ExactFilterExecutor { include, exclude, names, validate -> Regexp2Filters.indices(include, exclude, names, validate) },
    private val decoder: ProfileLinkDecoder = ProfileLinkDecoder(),
    private val encoder: ProfileLinkEncoder = ProfileLinkEncoder(),
) {
    private val home = MeowFlutterSpill.checkedHome(homeDir)
    private val age = AgeProfileSourceDecoder(ageTransport)
    private val limits = ProfileLinkLimits()
    val methods: Set<String> get() = METHODS

    suspend fun invoke(method: String, arguments: Any?): Any = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        when (method) {
            "decryptConfig" -> decrypt(arguments)
            "validateProxies" -> validateProxies(mappings(arguments))
            "validateFilters" -> validateFilters(strings(arguments, 4096, FilterProtocol.MAX_BYTES))
            "convertProxies" -> decodeLinks(text(read(path(arguments), limits.maxInputBytes), "$.path"), "$.path")
            "decodeShareLinks" -> {
                val lines = strings(arguments, limits.maxLinks, limits.maxTotalBytes)
                var count = 0
                lines.mapIndexed { index, line ->
                    val decoded = if (line.isBlank()) emptyList() else decodeLinks(line, "$.lines[$index]")
                    count += decoded.size
                    if (count > limits.maxNodes) bad("$.lines", "Decoded nodes exceed the batch budget")
                    decoded
                }
            }
            "encodeShareLinks" -> encodeLinks(mappings(arguments))
            else -> throw MeowProtocolFailure("bad_method", "Unknown offline method", mapOf("method" to method))
        }
    }

    private suspend fun decrypt(arguments: Any?): String {
        val row = arguments as? Map<*, *> ?: bad("$", "Expected path and age-secret-key object")
        if (row.keys != setOf("path", "age-secret-key")) bad("$", "Expected exactly path and age-secret-key")
        val key = row["age-secret-key"] as? String ?: bad("$.age-secret-key", "Expected a string")
        if (key.length > MAX_IDENTITY_BYTES) bad("$.age-secret-key", "Age identity exceeds the private byte budget")
        val identities = utf8(key, "$.age-secret-key")
        if (identities.size > MAX_IDENTITY_BYTES) { identities.fill(0); bad("$.age-secret-key", "Age identity exceeds the private byte budget") }
        val ciphertext = try { read(path(row["path"]), MAX_CIPHERTEXT_BYTES) }
        catch (failure: Throwable) { identities.fill(0); throw failure }
        try {
            AgeSecret(identities).use { secret ->
                identities.fill(0)
                return age.decode(ciphertext, secret).yaml
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AgeSourceException) {
            throw MeowProtocolFailure("age_${failure.code}", "Age source decoding failed: ${failure.code}",
                mapOf("field" to if (failure.code in setOf("secret_required", "invalid_identity", "wrong_key", "identity_size")) "$.age-secret-key" else "$.path"))
        } catch (failure: LinkageError) {
            throw MeowProtocolFailure("age_helper_unavailable", "Isolated age helper is unavailable", mapOf("field" to "$.path"), failure)
        } finally { identities.fill(0); ciphertext.fill(0) }
    }

    private suspend fun validateProxies(proxies: List<Map<String, Any?>>): List<String> = proxies.mapIndexed { index, proxy ->
        currentCoroutineContext().ensureActive()
        try { checkedProxy(proxy, "$.proxies[$index]"); "" }
        catch (failure: ProxyDiagnostic) { failure.message ?: "$.proxies[$index]: Invalid proxy" }
    }

    private suspend fun validateFilters(values: List<String>): List<String> = values.mapIndexed { index, filter ->
        currentCoroutineContext().ensureActive()
        try {
            val result = filters.indices(listOf(filter), emptyList(), emptyList(), true)
            if (result.isNotEmpty()) throw MeowProtocolFailure("filter_engine_failed", "Validation-only helper returned match indices", mapOf("field" to "$.filters[$index]"))
            ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: MeowProtocolFailure) { throw failure }
        catch (failure: Exception) {
            val message = failure.message.orEmpty()
            if (failure is IllegalStateException && FILTER_DIAGNOSTIC.containsMatchIn(message))
                message.replaceFirst("$.include[0]", "$.filters[$index]")
            else throw MeowProtocolFailure("filter_engine_failed", "Exact isolated filter validation failed", mapOf("field" to "$.filters[$index]"), failure)
        } catch (failure: LinkageError) {
            throw MeowProtocolFailure("filter_engine_unavailable", "Exact isolated filter engine is unavailable", mapOf("field" to "$.filters[$index]"), failure)
        }
    }

    private suspend fun encodeLinks(proxies: List<Map<String, Any?>>): List<String> {
        if (proxies.size > limits.maxLinks) bad("$.proxies", "Too many links")
        return proxies.mapIndexed { index, proxy ->
            val field = "$.proxies[$index]"
            val normalized = proxyForLinks(proxy, field)
            if (normalized["type"] == "http") blocked("$field.type", "Existing decoder treats HTTP(S) links as subscriptions; HTTP node round-trip is not connected")
            val link = try { encoder.encode(listOf(normalized)).single() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw MeowProtocolFailure("share_encode_error", "Existing share encoder rejected this node", mapOf("field" to field), failure) }
            if (link.isEmpty()) blocked("$field.type", "Existing share encoder has no lossless link form for this node")
            val recovered = decodeLinks(link, field).singleOrNull() ?: blocked(field, "Encoded node did not decode to exactly one proxy")
            retained(normalized, recovered, field)
            link
        }
    }

    private suspend fun decodeLinks(input: String, field: String): List<Map<String, Any?>> {
        val batch = decoder.decode(input)
        if (batch.errors.isNotEmpty()) {
            val first = batch.errors.first()
            val details = mapOf("field" to "$field${first.field.removePrefix("$")}", "line" to first.source.line,
                "decoderCode" to first.code.name)
            if (first.code == ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
                throw MeowProtocolFailure("implementation_blocked", "Existing share decoder cannot represent this option for the fixed Meow schema", details)
            throw MeowProtocolFailure("share_decode_error", first.reason, details)
        }
        if (batch.entries.isEmpty() || batch.entries.any { it !is ProfileLinkEntry.Inline || it.kind != ProfileLinkEntry.Inline.Kind.SHARE_LINKS })
            blocked(field, "Expected local proxy share links; subscriptions and full configurations are separate import flows")
        return batch.entries.flatMap { entry ->
            val root = ConfigYaml.parse((entry as ProfileLinkEntry.Inline).config)
            val rows = root["proxies"] as? List<*> ?: throw MeowProtocolFailure("invalid_result", "Share decoder returned no proxies", mapOf("field" to field))
            rows.mapIndexed { index, row ->
                @Suppress("UNCHECKED_CAST")
                val proxy = (row as? Map<String, Any?>)?.toMutableMap() ?: throw MeowProtocolFailure("invalid_result", "Share decoder returned a non-object proxy", mapOf("field" to field))
                // The legacy decoder inserts an inert TLS flag for plain SOCKS and V links; no caller map is changed.
                if (proxy["type"] == "socks5" || (proxy["tls"] != true && proxy["skip-cert-verify"] == false)) proxy.remove("skip-cert-verify")
                proxyForLinks(proxy, "$field.proxies[$index]")
            }
        }
    }

    private suspend fun checkedProxy(proxy: Map<String, Any?>, field: String): Map<String, Any?> {
        val verified = try { features().toSet() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LinkageError) { throw MeowProtocolFailure("native_unavailable", "Pinned Meow validator is unavailable", mapOf("field" to field), failure) }
        catch (failure: Exception) { throw MeowProtocolFailure("native_unavailable", "Pinned Meow capability verification failed", mapOf("field" to field), failure) }
        val root = try { MeowCompatibilityValidator.normalize(mapOf("proxies" to listOf(proxy), "rules" to listOf("MATCH,DIRECT")), verified) }
        catch (failure: ConfigValidationException) { throw ProxyDiagnostic(shift(failure.message.orEmpty(), field)) }
        val diagnostic = try { validator.validate(ConfigYaml.dump(root)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: LinkageError) { throw MeowProtocolFailure("native_unavailable", "Pinned Meow validator is unavailable", mapOf("field" to field), failure) }
        catch (failure: Exception) { throw MeowProtocolFailure("native_validation_failed", "Pinned Meow validation failed", mapOf("field" to field), failure) }
        if (diagnostic != null) throw ProxyDiagnostic(if (diagnostic.isBlank()) "$field: Native validator returned an empty diagnostic" else shift(diagnostic, field))
        @Suppress("UNCHECKED_CAST")
        return ((root["proxies"] as List<*>).single() as Map<String, Any?>)
    }

    private suspend fun proxyForLinks(proxy: Map<String, Any?>, field: String): Map<String, Any?> =
        try { checkedProxy(proxy, field) }
        catch (failure: ProxyDiagnostic) { throw MeowProtocolFailure("proxy_validation", failure.message ?: "Invalid proxy", mapOf("field" to field), failure) }

    private fun retained(original: Any?, decoded: Any?, field: String) {
        when (original) {
            is Map<*, *> -> {
                if (decoded !is Map<*, *>) blocked(field, "Share link would lose a node option object")
                original.forEach { (key, value) -> retained(value, decoded[key], "$field.$key") }
            }
            is List<*> -> {
                if (decoded !is List<*> || original.size != decoded.size) blocked(field, "Share link would lose a node option")
                original.forEachIndexed { index, value -> retained(value, decoded[index], "$field[$index]") }
            }
            else -> {
                val same = if (field.endsWith(".server") && original is String && decoded is String) original.equals(decoded, true) else original == decoded
                if (!same) blocked(field, "Share link would lose or change a node option")
            }
        }
    }

    private suspend fun mappings(value: Any?): List<Map<String, Any?>> {
        val rows = value as? List<*> ?: bad("$", "Expected a proxy list")
        if (rows.size > limits.maxNodes) bad("$.proxies", "Too many nodes")
        val context = currentCoroutineContext()
        val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var count = 0
        var bytes = 0L
        fun copy(item: Any?, field: String, depth: Int): Any? {
            context.ensureActive()
            if (depth > 32 || ++count > 100000) bad(field, "Node structure exceeds the value budget")
            if (item is Map<*, *> || item is List<*>) {
                if (!ancestors.add(item)) bad(field, "Recursive node structure")
                try {
                    return if (item is Map<*, *>) item.entries.associateTo(linkedMapOf()) { (key, child) ->
                        if (key !is String) bad(field, "Proxy field names must be strings")
                        bytes += utf8(key, field).size
                        if (bytes > limits.maxTotalBytes) bad(field, "Proxy batch exceeds the byte budget")
                        key to copy(child, "$field.$key", depth + 1)
                    } else (item as List<*>).mapIndexed { index, child -> copy(child, "$field[$index]", depth + 1) }
                } finally { ancestors.remove(item) }
            }
            if (item !is String && item !is Number && item !is Boolean && item != null) bad(field, "Unsupported proxy value")
            if ((item is Double && !item.isFinite()) || (item is Float && !item.isFinite())) bad(field, "Non-finite proxy value")
            bytes += if (item is String) utf8(item, field).size else 8
            if (bytes > limits.maxTotalBytes) bad(field, "Proxy batch exceeds the byte budget")
            return item
        }
        return rows.mapIndexed { index, row ->
            if (row !is Map<*, *>) bad("$.proxies[$index]", "Expected a proxy object")
            @Suppress("UNCHECKED_CAST")
            (copy(row, "$.proxies[$index]", 0) as Map<String, Any?>)
        }
    }

    private fun strings(value: Any?, maximum: Int, byteBudget: Int): List<String> {
        val rows = value as? List<*> ?: bad("$", "Expected a string list")
        if (rows.size > maximum) bad("$", "Too many input values")
        var bytes = 0L
        return rows.mapIndexed { index, item ->
            val text = item as? String ?: bad("$[$index]", "Expected a string")
            if (text.length > byteBudget) bad("$[$index]", "Input exceeds the byte budget")
            bytes += utf8(text, "$[$index]").size
            if (bytes > byteBudget) bad("$[$index]", "Input exceeds the byte budget")
            text
        }
    }

    private fun path(value: Any?): File {
        val requested = value as? String ?: bad("$.path", "Expected a private file path")
        if (requested.isBlank() || '\u0000' in requested) bad("$.path", "Expected a private file path")
        utf8(requested, "$.path")
        val file = File(requested)
        if (!file.isAbsolute || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.toPath() != file.toPath().toRealPath() || !file.toPath().startsWith(home.toPath()))
            bad("$.path", "Expected a canonical private-home file without symbolic links or junctions")
        return file
    }

    private suspend fun read(file: File, maximum: Int): ByteArray {
        val result = ByteArrayOutputStream()
        Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(16384)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                if (result.size().toLong() + count > maximum) bad("$.path", "Input file exceeds the byte budget")
                result.write(buffer, 0, count)
            }
        }
        return result.toByteArray()
    }
    private fun text(bytes: ByteArray, field: String): String = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { bad(field, "Expected UTF-8 input") }
    private fun utf8(text: String, field: String): ByteArray = try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text)).let { ByteArray(it.remaining()).also(it::get) } }
        catch (_: java.nio.charset.CharacterCodingException) { bad(field, "Invalid Unicode input") }
    private fun shift(value: String, field: String) = value.replace("$.proxies[0]", field)
    private class ProxyDiagnostic(message: String) : Exception(message)
    private fun bad(field: String, reason: String): Nothing = throw MeowProtocolFailure("bad_argument", reason, mapOf("field" to field))
    private fun blocked(field: String, reason: String): Nothing = throw MeowProtocolFailure("implementation_blocked", reason, mapOf("field" to field))
    companion object {
        val METHODS: Set<String> = setOf("decryptConfig", "validateProxies", "validateFilters", "convertProxies", "encodeShareLinks", "decodeShareLinks")
        private val FILTER_DIAGNOSTIC = Regex("^\\$\\.include\\[0]\\.parts\\[[0-9]+]:")
    }
}

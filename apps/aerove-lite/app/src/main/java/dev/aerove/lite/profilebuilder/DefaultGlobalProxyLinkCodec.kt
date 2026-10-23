package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.ProfileLinkDecoder
import dev.aerove.lite.data.ProfileLinkEncoder
import dev.aerove.lite.data.ProfileLinkEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DefaultGlobalProxyLinkCodec(
    private val validator: suspend (String) -> String?,
    private val decoder: ProfileLinkDecoder = ProfileLinkDecoder(),
    private val encoder: ProfileLinkEncoder = ProfileLinkEncoder(),
) : GlobalProxyLinkCodec {
    override suspend fun validate(entries: List<BuilderEntry>): Unit = withContext(Dispatchers.Default) {
        // Encoding bounds the recursive input before YAML serialization or the native validator sees it.
        encoder.encode(mappings(entries))
        ensureActive()
        val text = ConfigYaml.dump(mapOf("proxies" to mappings(entries)))
        validator(text)?.let { throw IllegalArgumentException(it) }
    }

    override suspend fun encode(entries: List<BuilderEntry>): List<String> = encoder.encode(mappings(entries))

    override suspend fun decode(text: String): List<BuilderEntry> = withContext(Dispatchers.Default) {
        val result = decoder.decode(text)
        require(result.errors.isEmpty()) {
            result.errors.joinToString("\n") { "${it.source.line}:${it.field}: ${it.reason}" }
        }
        require(result.entries.isNotEmpty() && result.entries.all {
            it is ProfileLinkEntry.Inline && it.kind == ProfileLinkEntry.Inline.Kind.SHARE_LINKS
        }) { "Expected proxy share links" }
        val entries = result.entries.flatMap { entry ->
            ensureActive()
            val proxies = ConfigYaml.parse((entry as ProfileLinkEntry.Inline).config)["proxies"] as? List<*>
                ?: throw IllegalArgumentException("Expected decoded proxies")
            proxies.map { value ->
                val fields = (value as? Map<*, *>)?.entries?.associate { (key, item) ->
                    require(key is String); key to item
                } ?: throw IllegalArgumentException("Expected decoded proxy mapping")
                BuilderEntry(fields["name"] as? String ?: throw IllegalArgumentException("Missing proxy name"), fields)
            }
        }
        validate(entries)
        entries
    }

    private fun mappings(entries: List<BuilderEntry>) = entries.map { it.fields + ("name" to it.name) }
}

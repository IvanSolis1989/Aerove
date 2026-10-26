package dev.aerove.lite.data

import java.io.File

/** Final native conversion requires the real sealed generation, including its trusted geo resources. */
fun interface NativePreparedConfigValidator {
    suspend fun validate(yaml: String, resourceReadRoot: File): String?
}

internal interface ProfileCompatibilitySchema {
    val coreVersion: String
    fun resourceInput(yaml: String): MutableMap<String, Any?>
    suspend fun preflightPreparedRule(path: File, behavior: String, format: String): RuleResourcePreflight?
    fun providerDownloadInput(yaml: String): String
    suspend fun validate(yaml: String, managed: Boolean, bundleDirectory: File?)
    fun validateProxyPayload(source: String)
    fun ruleLineIssue(line: String): String?
    fun validateRulePayload(lines: List<String>, path: String) = RuleLineCheck(path, ::ruleLineIssue).run { lines.forEachIndexed(::line); finish() }
}

/** Issues of one payload, collected line by line so a streamed payload is never held as a list. */
internal class RuleLineCheck(private val path: String, private val issue: (String) -> String?) {
    private val problems = mutableListOf<ConfigIssue>()
    fun line(index: Int, line: String) { issue(line)?.let { problems += ConfigIssue("$path[$index]", it) } }
    fun finish() { if (problems.isNotEmpty()) throw ConfigValidationException(problems.toList()) }
}

internal class MeowProfileValidator(private val native: NativeConfigValidator,
    private val preparedNative: NativePreparedConfigValidator, private val resourceDirectory: File,
    verifiedFeatures: () -> Set<String>, private val preparedRules: NativePreparedRuleValidator? = null) : ProfileCompatibilitySchema {
    override val coreVersion = "meow-rs/0.26.1/727ddcd06f592e2f12c5222c4de6cce328df808c"
    private val features by lazy { verifiedFeatures().toSet() }

    override fun resourceInput(yaml: String): MutableMap<String, Any?> {
        val (root, namedListeners) = MeowSourceConfig.input(yaml)
        if (namedListeners.isNotEmpty()) root["listeners"] = namedListeners
        @Suppress("UNCHECKED_CAST")
        val geo = root["geodata"] as? Map<String, Any?>
        val urls = geo?.get("url") as? Map<*, *>
        mapOf("mmdb" to "mmdb", "asn" to "asn-mmdb", "geosite" to "geosite").forEach { (key, resource) ->
            urls?.get(key)?.let { value ->
                if (root.containsKey("$resource-download-url") && root["$resource-download-url"] != value)
                    fail("$.geodata.url.$key", "Conflicts with $resource-download-url")
                root["$resource-download-url"] = value
            }
        }
        return root
    }

    override suspend fun preflightPreparedRule(path: File, behavior: String, format: String): RuleResourcePreflight? =
        preparedRules?.preflight(path, behavior, format)

    override fun providerDownloadInput(yaml: String): String = ConfigYaml.dump(engineView(yaml))

    private fun engineView(yaml: String): MutableMap<String, Any?> {
        val root = resourceInput(yaml)
        root.remove("listeners")
        for (key in listOf("unified-delay", "tcp-concurrent", "check-certificate")) root.remove(key)?.let {
            if (it !is Boolean) fail("$.$key", "Expected Boolean host policy")
        }
        root.remove("find-process-mode")?.let { if (it !in setOf("strict", "always", "off")) fail("$.find-process-mode", "Expected strict, always or off") }
        root.remove("global-ua")?.let { if (it !is String) fail("$.global-ua", "Expected string host policy") }
        root.remove("cors-allow-origins")?.let { value ->
            if (value !is List<*> || value.any { it !is String }) fail("$.cors-allow-origins", "Expected string list host policy")
        }
        root.remove("authentication")?.let { value ->
            if (value !is List<*> || value.any { it !is String }) fail("$.authentication", "Expected string list; actual inbound credentials belong to the host")
        }
        root["tun"] = linkedMapOf("enable" to false, "auto-route" to false)
        @Suppress("UNCHECKED_CAST")
        val geo = (root["geodata"] as? MutableMap<String, Any?>) ?: linkedMapOf()
        mapOf("mmdb" to "mmdb-path", "asn-mmdb" to "asn-path", "geosite" to "geosite-path").forEach { (old, key) ->
            root.remove(old)?.let { geo[key] = it }
            root.remove("$old-download-url")
        }
        if (geo.isNotEmpty()) root["geodata"] = geo
        return MeowCompatibilityValidator.normalize(root, features)
    }

    override suspend fun validate(yaml: String, managed: Boolean, bundleDirectory: File?) {
        val engine = engineView(yaml)
        val problems = mutableListOf<ConfigIssue>()
        fun check(value: Any?, path: String) {
            if (value is String) checkProfileResource(value, path, problems, resourceDirectory, bundleDirectory)
        }
        for (kind in listOf("proxy-providers", "rule-providers")) {
            (engine[kind] as? Map<*, *>)?.forEach { (name, raw) ->
                val provider = raw as Map<*, *>
                if (!managed) problems += ConfigIssue("$.$kind.$name", "Providers require the complete resource preparation path")
                if (bundleDirectory != null && provider["type"] == "http") problems += ConfigIssue("$.$kind.$name", "Unsealed remote provider in prepared bundle")
                if (!managed || bundleDirectory != null) check(provider["path"], "$.$kind.$name.path")
            }
        }
        (engine["geodata"] as? Map<*, *>)?.let { geo ->
            if (!managed || bundleDirectory != null) for (key in listOf("mmdb-path", "asn-path", "geosite-path")) check(geo[key], "$.geodata.$key")
        }
        (engine["proxies"] as? List<*>)?.forEachIndexed { i, raw ->
            val proxy = raw as Map<*, *>
            if (!managed || bundleDirectory != null) for (key in listOf("tls-cert", "tls-key", "ca")) check(proxy[key], "$.proxies[$i].$key")
        }
        if (problems.isNotEmpty()) throw ConfigValidationException(problems)
        // HTTP providers/geodata cannot be fully converted before acquisition.
        // The same candidate must pass the real native converter after sealing.
        if (!managed || bundleDirectory != null) {
            val source = ConfigYaml.dump(engine)
            val diagnostic = if (bundleDirectory != null) preparedNative.validate(source, bundleDirectory.canonicalFile) else native.validate(source)
            diagnostic?.let { nativeFailure(it) }
        }
    }

    override fun validateProxyPayload(source: String) {
        val parsed = ConfigYaml.parse(source)
        if (parsed.keys != setOf("proxies") || parsed["proxies"] !is List<*>) fail("$.proxies", "Proxy provider must contain only a proxies list")
        // Native conversion needs the complete materialized provider graph.
        MeowCompatibilityValidator.normalize(parsed, features)
    }
    // Native checked conversion owns logical expressions and Android metadata after materialization.
    override fun ruleLineIssue(line: String): String? = if (line.isBlank()) "Empty rule payload" else null
    private fun nativeFailure(diagnostic: String): Nothing {
        val field = diagnostic.substringBefore(": ").takeIf { it.startsWith("$") } ?: "$"
        throw ConfigValidationException(listOf(ConfigIssue(field, diagnostic.take(2000))))
    }
    private fun fail(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
}

package dev.aerove.lite.flutter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

const val MEOW_RUNTIME_CORE_SHA = "727ddcd06f592e2f12c5222c4de6cce328df808c"

data class RuntimeHandoffResource(val relativePath: String, val sourcePath: String, val sha256: String)

/** A compiled projection from Dart; no editable profile or preference database is owned here. */
class RuntimeHandoff(
    val coreSha: String,
    val sourceId: String,
    val sourceRevision: String,
    val sourceSha256: String,
    val compiledYaml: String,
    val compiledSha256: String,
    val setupRecordJson: String,
    val runtimeSettingsJson: String,
    val vpnOptionsJson: String,
    val notificationJson: String,
    resources: List<RuntimeHandoffResource> = emptyList(),
) {
    val resources: List<RuntimeHandoffResource> = java.util.Collections.unmodifiableList(ArrayList(resources))
    override fun toString() = "RuntimeHandoff(source=<private>, revision=$sourceRevision, content=<redacted>)"
    internal fun json(): JSONObject = JSONObject().put("coreSha", coreSha).put("sourceId", sourceId)
        .put("sourceRevision", sourceRevision).put("sourceSha256", sourceSha256)
        .put("compiledYaml", compiledYaml).put("compiledSha256", compiledSha256)
        .put("setupRecord", JSONObject(setupRecordJson)).put("runtimeSettings", JSONObject(runtimeSettingsJson))
        .put("vpnOptions", JSONObject(vpnOptionsJson)).put("notification", JSONObject(notificationJson))
        .put("resources", JSONArray(resources.map { JSONObject().put("relativePath", it.relativePath)
            .put("sourcePath", it.sourcePath).put("sha256", it.sha256) }))

    internal fun validate(expectedCore: String) {
        require(coreSha == expectedCore) { "runtime_source_mismatch: coreSha" }
        require(sourceId.isNotBlank() && sourceId.length <= 1024 && sourceRevision.isNotBlank() && sourceRevision.length <= 1024) { "runtime_source_invalid" }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) { "runtime_source_invalid: sourceSha256" }
        require(compiledYaml.toByteArray().size in 1..4 * 1024 * 1024 && runtimeSha256(compiledYaml.toByteArray()) == compiledSha256) { "runtime_hash_mismatch: compiledYaml" }
        listOf(setupRecordJson, runtimeSettingsJson, vpnOptionsJson, notificationJson).forEach {
            require(it.toByteArray().size <= 128 * 1024) { "runtime_projection_too_large" }; JSONObject(it)
        }
        val settings = JSONObject(runtimeSettingsJson)
        listOf("network", "requests", "backgroundPolicy", "externalController", "dnsPreferences", "geoResources").forEach { settings.getJSONObject(it) }
        listOf("ipv6", "unifiedDelay", "tcpConcurrent", "autoStart").forEach { require(settings.get(it) is Boolean) { "runtime_setting_invalid: $it" } }
        require(settings.getString("mode") in setOf("Rule", "Global", "Direct") &&
            settings.getString("appFilterMode") in setOf("All", "Include", "Exclude") &&
            settings.getString("findProcessMode") in setOf("strict", "always", "off")) { "runtime_setting_invalid" }
        settings.getJSONArray("packages").let { array -> repeat(array.length()) { require(array.get(it) is String) } }
        val record = JSONObject(setupRecordJson)
        require(record.getString("digest").isNotBlank() && record.getString("yaml-md5").matches(Regex("[0-9a-f]{32}"))) { "runtime_setup_record_invalid" }
        listOf("groups", "proxies", "rules").forEach { require(record.getLong(it) >= 0) { "runtime_setup_record_invalid" } }
        val options = JSONObject(vpnOptionsJson)
        listOf("enable", "ipv6", "dnsHijacking", "allowBypass", "systemProxy").forEach { require(options.get(it) is Boolean) { "runtime_vpn_option_invalid: $it" } }
        require(options.getInt("port") in 0..65535) { "runtime_vpn_option_invalid: port" }
        options.getJSONObject("accessControlProps"); options.getJSONArray("bypassDomain"); options.getString("stack")
        val notification = JSONObject(notificationJson)
        listOf("stopTip", "startTip", "localNetworkTip", "currentProfileName", "stopText").forEach { require(notification.get(it) is String) { "runtime_notification_invalid: $it" } }
        require(notification.get("showStopAction") is Boolean && notification.get("onlyStatisticsProxy") is Boolean) { "runtime_notification_invalid" }
        require(resources.size <= 4096 && resources.map { it.relativePath }.distinct().size == resources.size) { "runtime_resources_invalid" }
        resources.forEach {
            require(it.relativePath.matches(Regex("[a-zA-Z0-9_./-]+")) && !it.relativePath.startsWith('/') &&
                it.relativePath.split('/').all { part -> part.isNotEmpty() && part != "." && part != ".." }) { "runtime_resource_path_invalid" }
            require(it.sha256.matches(Regex("[0-9a-f]{64}"))) { "runtime_resource_hash_invalid" }
        }
    }

    internal fun withSetupRecord(recordJson: String): RuntimeHandoff {
        val runtime = JSONObject(runtimeSettingsJson)
        if (runtime.has("flutterSetup")) runtime.getJSONObject("flutterSetup").put("applied", JSONObject(recordJson))
        return RuntimeHandoff(coreSha, sourceId, sourceRevision, sourceSha256, compiledYaml, compiledSha256,
            recordJson, if (runtime.has("flutterSetup")) runtime.toString() else runtimeSettingsJson,
            vpnOptionsJson, notificationJson, resources)
    }

    companion object {
        internal fun fromJson(row: JSONObject): RuntimeHandoff {
            val resources = row.getJSONArray("resources")
            return RuntimeHandoff(row.getString("coreSha"), row.getString("sourceId"), row.getString("sourceRevision"),
                row.getString("sourceSha256"), row.getString("compiledYaml"), row.getString("compiledSha256"),
                row.getJSONObject("setupRecord").toString(), row.getJSONObject("runtimeSettings").toString(),
                row.getJSONObject("vpnOptions").toString(), row.getJSONObject("notification").toString(),
                java.util.Collections.unmodifiableList(List(resources.length()) { resources.getJSONObject(it).let { item ->
                    RuntimeHandoffResource(item.getString("relativePath"), item.getString("sourcePath"), item.getString("sha256")) } }))
        }
    }
}

data class RuntimeAppliedProof(val serviceOwner: Long, val sessionGeneration: Long, val coreEpoch: String)

data class RuntimeAppliedIdentity(val revision: String, val intentEpoch: Long, val compiledSha256: String,
    val proof: RuntimeAppliedProof, val expectedSetupRecordJson: String)

class MeowRuntimeSnapshot internal constructor(val revision: String, val intentEpoch: Long, val userRequested: Boolean,
    val handoff: RuntimeHandoff, val resourceDirectory: File) {
    val profileId: String get() = "flutter:$revision"
    override fun toString() = "MeowRuntimeSnapshot(revision=$revision, epoch=$intentEpoch, requested=$userRequested)"
}

internal fun runtimeSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

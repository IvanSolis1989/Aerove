package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import org.json.JSONObject

/** Captured by the actual setup/runtime owner, never accepted from a Flutter request. */
internal class MeowGeoResourceIntent(val settings: LiteSettings, val runtime: MeowSetupRuntime,
    val snapshot: MeowRuntimeSnapshot, val proofJson: String, val producer: MeowGeoProducer? = null) {
    val sourceGuard: String get() = meowGeoSourceGuard(JSONObject(proofJson))
}

internal fun meowGeoSourceGuard(proof: JSONObject): String {
    require(proof.getInt("schema") == 2) { "Current business source proof schema is required" }
    require(proof.getString("setupSha256").matches(Regex("[0-9a-f]{64}"))) { "Invalid setup source hash" }
    proof.getJSONObject("tables");proof.getJSONArray("files")
    return nativeLiteJsonHash(JSONObject().apply {
        listOf("schema","setupSha256","tables","files").forEach { put(it,proof.get(it)) }
    })
}

/** Same YAML cannot attest a changed provider/certificate payload read through a stable path. */
internal fun meowMetadataResourcesUnchanged(previous: JSONObject, current: JSONObject): Boolean {
    fun opaque(proof: JSONObject): Map<String, Pair<Long, String>> {
        val files = proof.getJSONArray("files")
        return buildMap {
            for (index in 0 until files.length()) {
                val row = files.getJSONObject(index)
                val path = row.getString("path")
                if (path.matches(Regex("(?:profiles/[0-9]+\\.yaml|scripts/[0-9]+\\.js)"))) continue
                check(put(path, row.getLong("bytes") to row.getString("sha256")) == null) { "Duplicate business source file" }
            }
        }
    }
    return opaque(previous) == opaque(current)
}


internal fun meowVerifyMetadataSource(context: android.content.Context, previous: RuntimeHandoff,
    params: JSONObject): Map<String,String> {
    val proof=params.getJSONObject("data-source-proof")
    var selected:Map<String,String>?=null
    verifyNativeLiteBusinessSource(context,proof) { selected=it }
    val actual=checkNotNull(selected)
    val requested=params.getJSONObject("selected-map").let { row -> row.keys().asSequence().associateWith(row::getString) }
    require(actual == requested) { "shared_source_selected_map_changed" }
    val oldSetup=JSONObject(previous.runtimeSettingsJson).getJSONObject("flutterSetup")
    require(meowMetadataResourcesUnchanged(oldSetup.getJSONObject("data-source-proof"),proof)) { "shared_source_runtime_resources_changed" }
    require(JSONObject(previous.setupRecordJson).getString("yaml-md5") == params.getJSONObject("applied").getString("yaml-md5")) { "shared_source_yaml_changed" }
    require(oldSetup.getString("test-url") == params.getString("test-url") &&
        oldSetup.getBoolean("skip-cert-verify") == params.getBoolean("skip-cert-verify")) { "shared_source_runtime_policy_changed" }
    return actual
}

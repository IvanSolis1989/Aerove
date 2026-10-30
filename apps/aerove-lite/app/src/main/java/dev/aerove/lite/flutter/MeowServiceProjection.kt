package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import org.json.JSONObject

internal fun meowServiceProjection(previous: RuntimeHandoff, settings: LiteSettings, shared: JSONObject?, update: String?,
    setup: String?, testUrl: String): RuntimeHandoff = meowServiceProjection(previous, settings, shared, update, setup, testUrl, null)

internal fun meowServiceProjection(previous: RuntimeHandoff, settings: LiteSettings, shared: JSONObject?, update: String?,
    setup: String?, testUrl: String, graphController: dev.aerove.lite.core.MeowControllerPolicy?): RuntimeHandoff {
    val runtime = JSONObject(previous.runtimeSettingsJson)
    runtime.remove("flutterGraphPolicy")
    if (graphController != null) runtime.put("flutterGraphPolicy", JSONObject().put("privateOnly", true)
        .put("userController", JSONObject().put("enabled", graphController.enabled)
            .put("address", graphController.address ?: JSONObject.NULL).put("authenticated", graphController.authenticated)
            .put("privateRoutesExcluded", graphController.privateRoutesExcluded).put("coreEpoch", graphController.coreEpoch)))
    val encoded = MeowRuntimeSnapshotSettings.encode(settings)
    encoded.keys().forEach { key -> runtime.put(key, encoded.get(key)) }
    runtime.put("flutterDefaultTestUrl", testUrl)
    if (update != null) runtime.put("flutterUpdate", JSONObject(update))
    if (setup != null) runtime.put("flutterSetup", JSONObject(setup))
    val notification = JSONObject(previous.notificationJson)
    if (shared != null) notification.keys().asSequence().toList().forEach { key ->
        notification.put(key, if (key == "showStopAction" && !shared.has(key)) true else shared.get(key))
    }
    val record = setup?.let { JSONObject(it).optJSONObject("applied")?.toString() } ?: previous.setupRecordJson
    return RuntimeHandoff(previous.coreSha, previous.sourceId, previous.sourceRevision, previous.sourceSha256,
        previous.compiledYaml, previous.compiledSha256, record, runtime.toString(),
        shared?.optJSONObject("vpnOptions")?.toString() ?: previous.vpnOptionsJson, notification.toString(), previous.resources)
}

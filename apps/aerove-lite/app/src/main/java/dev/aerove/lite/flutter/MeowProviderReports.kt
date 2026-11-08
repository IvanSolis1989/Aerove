package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowUnsupportedOutbounds
import dev.aerove.lite.data.sha256
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

internal fun meowProviderMetadata(home: File, snapshot: MeowRuntimeSnapshot, name: String, kind: String,
    raw: Map<String, Any?>, engineYaml: String): MeowAppliedProvider {
    require(kind in setOf("proxies", "rules"))
    val namespace = if (kind == "proxies") "proxy-providers" else "rule-providers"
    val manifestAsset = snapshot.handoff.resources.single { it.relativePath == "manifest.json" }
    fun owned(relative: String): File {
        val asset = snapshot.handoff.resources.single { it.relativePath == relative }
        val file = File(snapshot.resourceDirectory, relative)
        require(file.canonicalFile.toPath().startsWith(snapshot.resourceDirectory.canonicalFile.toPath()) &&
            !Files.isSymbolicLink(file.toPath()) && file.isFile && sha256(file) == asset.sha256) { "Applied provider resource changed" }
        return file
    }
    val manifest = JSONObject(owned(manifestAsset.relativePath).readText())
    val records = manifest.getJSONArray("records")
    val rows = (0 until records.length()).map(records::getJSONObject)
    val key = "$.$namespace.$name"
    val expectedKey = if (kind == "proxies") "$key.prepared" else key
    val expectedKind = if (kind == "proxies") "proxy-provider" else "ruleset"
    val derived = rows.single { it.getString("key") == expectedKey && it.getString("kind") == expectedKind }
    val report = if (kind == "proxies") derived.optJSONObject("compatibility") else null
    val source = if (kind == "proxies") rows.single { it.getString("key") == key && it.getString("kind") == "source-proxy" } else derived
    if (report != null) require(report.getString("coreIdentity") == MeowUnsupportedOutbounds.CORE &&
        report.getString("filterIdentity") in MeowUnsupportedOutbounds.readableFilters && report.getString("sourceSha256") == source.getString("sha256") &&
        report.getString("derivedSha256") == derived.getString("sha256")) { "Provider report lineage differs from its manifest" }
    require(sha256(owned(source.getString("file"))) == source.getString("sha256") &&
        sha256(owned(derived.getString("file"))) == derived.getString("sha256"))
    val providers = ConfigYaml.parse(engineYaml)[namespace] as? Map<*, *> ?: error("Actual engine has no provider namespace")
    val provider = providers[name] as? Map<*, *> ?: error("Actual engine has no named provider")
    require(provider["type"] == "file") { "Applied provider is not a sealed file" }
    val runtime = File(protocolText(provider["path"], "provider.path"))
    val authority = File(home, "runtime/meow-read").canonicalFile.toPath()
    require(runtime.toPath() == runtime.toPath().toRealPath() && runtime.toPath().startsWith(authority) && runtime.isFile) { "Provider runtime path is outside its relocation owner" }
    val root = generateSequence(runtime.parentFile) { it.parentFile }.firstOrNull { it.parentFile?.canonicalFile?.toPath() == authority }
        ?: error("Provider has no relocation owner")
    val receiptFile = File(root, "relocation-receipt.json")
    require(!Files.isSymbolicLink(receiptFile.toPath()) && receiptFile.isFile)
    val receipt = JSONObject(receiptFile.readText())
    require(receipt.getString("revision") == snapshot.revision)
    if (receipt.getString("compiledSha256") != snapshot.handoff.compiledSha256) {
        require(sha256(owned("config.yaml")) == manifest.getString("configSha256") &&
            receipt.getString("compiledSha256") == manifest.getString("configSha256")) { "Provider relocation belongs to another compiled source" }
    }
    val relocated = receipt.getJSONArray("records")
    val row = (0 until relocated.length()).map(relocated::getJSONObject).single { it.getString("file") == derived.getString("file") }
    require(File(root, row.getString("file")).canonicalFile == runtime && row.getString("sourceSha256") == derived.getString("sha256") &&
        row.getString("runtimeSha256") == sha256(runtime)) { "Provider relocation receipt differs from actual runtime bytes" }
    if (kind == "proxies") {
        val names = (ConfigYaml.parse(runtime.readText())["proxies"] as List<*>).map { (it as Map<*, *>)["name"] }
        val actual = protocolList(raw["proxies"], "provider.proxies").map { protocolMap(it, "provider.proxy")["name"] }
        require((report == null || names.size == report.getInt("retained")) && actual.all { it in names } && actual.distinct().size == actual.size) { "Provider report differs from the running node set" }
    }
    return MeowAppliedProvider(owned(source.getString("file")).path, snapshot.revision,
        report?.let { protocolMap(protocolCopy(it), "compatibility") })
}

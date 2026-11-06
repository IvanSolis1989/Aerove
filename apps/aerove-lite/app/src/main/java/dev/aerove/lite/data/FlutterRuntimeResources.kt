package dev.aerove.lite.data

import dev.aerove.lite.flutter.MeowRuntimeSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Session-owned relocation. The Store's original manifest, policies and byte receipts stay immutable. */
class FlutterRuntimeResources private constructor(private val privateRoot: File, val geoReadRootAuthority: File, val readRoot: File, val yaml: String,
    private val profileId: String, private val revision: String, private val compiledSha256: String,
    private val receiptSha256: String) {
    val resourceLeaseId: String get() = readRoot.name
    @Volatile private var closed = false
    suspend fun closeAfterNativeJoin() = withContext(NonCancellable + Dispatchers.IO) {
        closed = true
        deletePrivateTree(privateRoot, readRoot)
    }

    internal fun verifyReadLease(prepared: PreparedProfile): File {
        check(!closed) { "Flutter runtime resource lease is closed" }
        require(prepared.profileId == profileId && prepared.revision == revision && prepared.generation == compiledSha256 &&
            prepared.resourceDirectory == readRoot && readRoot.toPath() == readRoot.toPath().toRealPath()) { "Foreign Flutter runtime resource lease" }
        val original = ConfigYaml.parse(yaml)
        val supplied = ConfigYaml.parse(prepared.yaml)
        val groups = original["proxy-groups"] as? List<*>
        val projected = if (groups == null) original else original + ("proxy-groups" to groups.mapIndexed { index, value ->
            val row = value as? Map<*, *>
            if (row != null && row["type"] in setOf("url-test", "fallback", "load-balance") && (row["url"] == null || row["url"] == "")) {
                val actual = (supplied["proxy-groups"] as? List<*>)?.getOrNull(index) as? Map<*, *>
                val url = actual?.get("url") as? String ?: error("Missing runtime group test URL")
                SubscriptionDownloader.parseUrl(url)
                row + ("url" to url)
            } else value
        })
        require(projected == supplied) { "Flutter runtime YAML differs from its owned source" }
        val receiptFile = File(readRoot, "relocation-receipt.json")
        require(receiptFile.toPath() == receiptFile.toPath().toRealPath() && Files.isRegularFile(receiptFile.toPath(), NOFOLLOW_LINKS)) { "Unsafe runtime receipt" }
        val bytes = Files.newInputStream(receiptFile.toPath(), NOFOLLOW_LINKS).use { it.readBounded(4 * 1024 * 1024) }
        require(dev.aerove.lite.flutter.runtimeSha256(bytes) == receiptSha256) { "Runtime receipt changed" }
        val receipt = JSONObject(String(bytes, Charsets.UTF_8))
        require(receipt.getString("revision") == revision && receipt.getString("compiledSha256") == compiledSha256) { "Runtime receipt lineage changed" }
        val records = receipt.getJSONArray("records")
        repeat(records.length()) { index ->
            val row = records.getJSONObject(index)
            val path = File(readRoot, row.getString("file"))
            require(path.toPath() == path.toPath().toRealPath() && Files.isRegularFile(path.toPath(), NOFOLLOW_LINKS) &&
                sha256(path) == row.getString("runtimeSha256")) { "Relocated runtime resource changed" }
        }
        check(!closed) { "Flutter runtime resource lease is closed" }
        return readRoot
    }

    /** A Geo generation retains the unique initial non-Geo paths and has an explicit join lifetime. */
    class Geo internal constructor(private val privateRoot: File, val readRoot: File,
        val compiledYaml: String, val engineYaml: String, val assets: List<MeowGeoAsset>,
        val resources: List<dev.aerove.lite.flutter.RuntimeHandoffResource>, val records: JSONObject,
        val catalogKinds: Set<dev.aerove.lite.resources.GeoResourceKind>) {
        val resourceLeaseId: String get() = readRoot.name
        @Volatile internal var nativeOwner = false
        suspend fun closeIfUnsubmitted() { if(!nativeOwner) closeAfterNativeJoin() }
        suspend fun closeAfterNativeJoin() = withContext(NonCancellable + Dispatchers.IO) {
            deletePrivateTree(privateRoot,readRoot)
        }
    }

    companion object {
        /** Reuses this relocation owner; it neither rebuilds providers nor acquires a second Core. */
        internal suspend fun createGeo(privateDirectory: File, snapshot: MeowRuntimeSnapshot, actualEngineYaml: String,
            preferences: dev.aerove.lite.resources.GeoResourcePreferences, requestedKind: dev.aerove.lite.resources.GeoResourceKind?,
            reader: HostGeoResourceReader, current: () -> Boolean): Geo = withContext(Dispatchers.IO) {
            val privateRoot=privateDirectory.toPath().toRealPath().toFile()
            require(Files.isDirectory(privateRoot.toPath(), NOFOLLOW_LINKS))
            val authority=File(privateRoot,"runtime/meow-read").apply { check(isDirectory || mkdirs()) }
            require(authority.toPath() == authority.toPath().toRealPath())
            val root=File(authority,UUID.randomUUID().toString()).apply { check(mkdir()) }
            try {
                val compiled=ConfigYaml.parse(snapshot.handoff.compiledYaml).toMutableMap()
                val engine=ConfigYaml.parse(actualEngineYaml).toMutableMap()
                val engineGeo=(engine["geodata"] as? Map<*,*>)?.entries?.associate { (key,value) -> key as String to value }?.toMutableMap() ?: linkedMapOf()
                val metadata=JSONObject(snapshot.handoff.runtimeSettingsJson)
                val previous=metadata.optJSONObject("flutterGeoAssets")
                val manifestAsset=snapshot.handoff.resources.singleOrNull { it.relativePath == "manifest.json" }
                val original=manifestAsset?.let { asset ->
                    val file=File(snapshot.resourceDirectory,asset.relativePath)
                    require(sha256(file) == asset.sha256)
                    JSONObject(file.readText()).getJSONArray("records")
                }
                val keys=mapOf(dev.aerove.lite.resources.GeoResourceKind.MMDB to "mmdb-path",
                    dev.aerove.lite.resources.GeoResourceKind.ASN to "asn-path",dev.aerove.lite.resources.GeoResourceKind.GEOSITE to "geosite-path")
                val kinds=keys.filter { (_,key) -> engineGeo[key] is String }.keys +
                    (requestedKind?.let(::setOf) ?: emptySet())
                val assets=mutableListOf<MeowGeoAsset>()
                val resources=mutableListOf<dev.aerove.lite.flutter.RuntimeHandoffResource>()
                val records=JSONObject();val catalogKinds=linkedSetOf<dev.aerove.lite.resources.GeoResourceKind>()
                var total=0L
                for(kind in kinds) {
                    currentCoroutineContext().ensureActive();check(current()) { "Geo resource source was superseded" }
                    val sourceKey="$.${kind.sourceKey}"
                    val prior=previous?.optJSONObject(kind.wire) ?: original?.let { array ->
                        (0 until array.length()).map(array::getJSONObject).singleOrNull { it.optString("key") == sourceKey }
                    }
                    val host=prior == null || prior.optString("transportOrigin") == "host-catalog"
                    val relative="geo/${root.name}/${kind.wire}.bin"
                    val target=File(root,"${kind.wire}.bin")
                    val row=JSONObject().put("transportOrigin",if(host) "host-catalog" else "private-pin")
                    if(host && (preferences.autoUpdate || requestedKind == kind)) {
                        catalogKinds+=kind
                        val url=preferences.urls.getValue(kind)
                        val copy=checkNotNull(reader.copyInto(sourceKey,url,target,64L*1024*1024)) { "Current Host Geo catalog did not supply its asset" }
                        require(copy.updatedAt != null && copy.updatedAt > 0 && copy.updatedAt <= System.currentTimeMillis() &&
                            copy.bytes == target.length() && copy.sha256 == sha256(target)) { "Host Geo copy receipt differs from actual bytes" }
                        row.put("url",url).put("updatedAt",copy.updatedAt)
                    } else {
                        val retained=checkNotNull(prior) { "No existing Geo bytes can be retained while automatic downloads are off" }
                        if(host) {
                            row.put("url",retained.getString("url")).put("updatedAt",retained.getLong("updatedAt"))
                                .put("retainedFromRuntime",true)
                        }
                        val sourceRelative=retained.getString("file")
                        val owned=snapshot.handoff.resources.single { it.relativePath == sourceRelative }
                        val source=File(snapshot.resourceDirectory,sourceRelative)
                        require(source.toPath() == source.toPath().toRealPath() && Files.isRegularFile(source.toPath(),NOFOLLOW_LINKS))
                        val bytes=Files.newInputStream(source.toPath(),NOFOLLOW_LINKS).use { it.readBounded(64*1024*1024) }
                        require(dev.aerove.lite.flutter.runtimeSha256(bytes) == owned.sha256) { "Pinned Geo source changed" }
                        java.io.FileOutputStream(target).use { it.write(bytes);it.fd.sync() }
                    }
                    require(target.toPath() == target.toPath().toRealPath() && Files.isRegularFile(target.toPath(),NOFOLLOW_LINKS))
                    val bytes=target.length();total+=bytes;require(bytes > 0 && total <= 256L*1024*1024)
                    val digest=sha256(target)
                    row.put("file",relative).put("sha256",digest).put("bytes",bytes)
                    records.put(kind.wire,row)
                    assets+=MeowGeoAsset(if(kind == dev.aerove.lite.resources.GeoResourceKind.MMDB) "geoip" else kind.wire,
                        target.name,bytes.toString(),digest)
                    resources+=dev.aerove.lite.flutter.RuntimeHandoffResource(relative,target.path,digest)
                    engineGeo[keys.getValue(kind)]=target.path
                    compiled.remove(kind.sourceKey)
                    if(compiled.containsKey("${kind.sourceKey}-download-url")) compiled["${kind.sourceKey}-download-url"]=preferences.urls.getValue(kind)
                }
                require(engineGeo["auto-update"] != true) { "The Core Geo scheduler must remain disabled" }
                val projected=engineGeo
                if(projected.isNotEmpty())engine["geodata"]=projected
                MeowGeoPolicy.project(compiled,preferences.copy(autoUpdate=false))
                val compiledGeo=(compiled["geodata"] as Map<*,*>).entries.associate { (key,value)->key as String to value }.toMutableMap()
                keys.values.forEach { key -> projected[key]?.let { compiledGeo[key]=it } }
                compiled["geodata"]=compiledGeo
                currentCoroutineContext().ensureActive();check(current())
                Geo(privateRoot,root,ConfigYaml.dump(compiled),ConfigYaml.dump(engine),assets.toList(),resources.toList(),records,catalogKinds)
            } catch(failure:Throwable) { deletePrivateTree(privateRoot,root);throw failure }
        }

        suspend fun create(privateDirectory: File, snapshot: MeowRuntimeSnapshot, current: () -> Boolean): FlutterRuntimeResources = withContext(Dispatchers.IO) {
            val privateRoot = privateDirectory.toPath().toRealPath().toFile()
            require(Files.isDirectory(privateRoot.toPath(), NOFOLLOW_LINKS)) { "Private directory required" }
            val parent = File(privateRoot, "runtime/meow-read").apply { check(isDirectory || mkdirs()) }
            require(parent.toPath() == parent.toPath().toRealPath()) { "Redirected runtime resource parent" }
            val root = File(parent, UUID.randomUUID().toString()).apply { check(mkdir()) }
            try {
                val resources = snapshot.handoff.resources
                val paths = resources.flatMap { asset -> listOf(asset.sourcePath, File(snapshot.resourceDirectory, asset.relativePath).path)
                    .map { it to File(root, asset.relativePath).path } }.toMap()
                val receipts = JSONArray()
                var total = 0L
                suspend fun checkCurrent() { currentCoroutineContext().ensureActive(); check(current()) { "Runtime resources were superseded" } }
                for (asset in resources) {
                    checkCurrent()
                    val source = File(snapshot.resourceDirectory, asset.relativePath)
                    require(source.toPath() == source.toPath().toRealPath() && Files.isRegularFile(source.toPath(), NOFOLLOW_LINKS) &&
                        source.toPath().startsWith(snapshot.resourceDirectory.toPath())) { "Unsafe Store resource" }
                    val bytes = Files.newInputStream(source.toPath(), NOFOLLOW_LINKS).use { it.readBounded(64 * 1024 * 1024) }
                    total += bytes.size; require(total <= 256L * 1024 * 1024) { "Runtime resource budget exceeded" }
                    require(dev.aerove.lite.flutter.runtimeSha256(bytes) == asset.sha256) { "Store resource changed before runtime relocation" }
                    val target = File(root, asset.relativePath).apply { checkNotNull(parentFile).mkdirs() }
                    java.io.FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
                    require(sha256(target) == asset.sha256) { "Runtime resource copy readback failed" }
                }
                fun remap(value: Any?): Any? = when (value) {
                    is Map<*, *> -> value.entries.associate { it.key to remap(it.value) }
                    is List<*> -> value.map(::remap)
                    is String -> paths[value] ?: value
                    else -> value
                }
                val input = ConfigYaml.parse(snapshot.handoff.compiledYaml)
                val providers = input["proxy-providers"] as? Map<*, *>
                val relocated = mutableSetOf<String>()
                providers?.values?.forEach { raw ->
                    val provider = raw as Map<*, *>
                    if (provider["type"] == "file") {
                        val path = provider["path"] as String
                        val targetPath = paths[path] ?: error("Proxy provider has no owned Store resource")
                        if (relocated.add(targetPath)) {
                            @Suppress("UNCHECKED_CAST")
                            val payload = remap(ConfigYaml.parse(File(targetPath).readText())) as Map<String, Any?>
                            val text = ConfigYaml.dump(payload)
                            java.io.FileOutputStream(targetPath).use { it.write(text.toByteArray()); it.fd.sync() }
                            require(sha256(File(targetPath)) == sha256(text)) { "Relocated provider readback failed" }
                        }
                    }
                }
                for (asset in resources) receipts.put(JSONObject().put("file", asset.relativePath).put("sourceSha256", asset.sha256)
                    .put("runtimeSha256", sha256(File(root, asset.relativePath))))
                val receipt = JSONObject().put("revision", snapshot.revision)
                    .put("compiledSha256", snapshot.handoff.compiledSha256).put("records", receipts).toString()
                PrivateStorage(root).write("relocation-receipt.json", receipt)
                checkCurrent()
                @Suppress("UNCHECKED_CAST")
                FlutterRuntimeResources(privateRoot, parent, root, ConfigYaml.dump(remap(input) as Map<String, Any?>),
                    snapshot.profileId, snapshot.revision, snapshot.handoff.compiledSha256, sha256(receipt))
            } catch (failure: Throwable) { deletePrivateTree(privateRoot, root); throw failure }
        }
    }
}

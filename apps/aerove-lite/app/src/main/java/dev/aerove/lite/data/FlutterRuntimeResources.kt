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
class FlutterRuntimeResources private constructor(private val privateRoot: File, val readRoot: File, val yaml: String,
    private val profileId: String, private val revision: String, private val compiledSha256: String,
    private val receiptSha256: String) {
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

    companion object {
        suspend fun create(privateDirectory: File, snapshot: MeowRuntimeSnapshot, current: () -> Boolean): FlutterRuntimeResources = withContext(Dispatchers.IO) {
            val privateRoot = privateDirectory.toPath().toRealPath().toFile()
            require(privateRoot == privateDirectory.absoluteFile) { "Canonical private directory required" }
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
                FlutterRuntimeResources(privateRoot, root, ConfigYaml.dump(remap(input) as Map<String, Any?>),
                    snapshot.profileId, snapshot.revision, snapshot.handoff.compiledSha256, sha256(receipt))
            } catch (failure: Throwable) { deletePrivateTree(privateRoot, root); throw failure }
        }
    }
}

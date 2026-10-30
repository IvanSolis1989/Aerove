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
class FlutterRuntimeResources private constructor(private val privateRoot: File, val readRoot: File, val yaml: String) {
    suspend fun closeAfterNativeJoin() = withContext(NonCancellable + Dispatchers.IO) { deletePrivateTree(privateRoot, readRoot) }

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
                PrivateStorage(root).write("relocation-receipt.json", JSONObject().put("revision", snapshot.revision)
                    .put("compiledSha256", snapshot.handoff.compiledSha256).put("records", receipts).toString())
                checkCurrent()
                @Suppress("UNCHECKED_CAST")
                FlutterRuntimeResources(privateRoot, root, ConfigYaml.dump(remap(input) as Map<String, Any?>))
            } catch (failure: Throwable) { deletePrivateTree(privateRoot, root); throw failure }
        }
    }
}

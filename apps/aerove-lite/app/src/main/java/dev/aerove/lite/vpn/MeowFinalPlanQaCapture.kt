package dev.aerove.lite.vpn

import dev.aerove.lite.data.sha256
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.json.JSONArray
import org.json.JSONObject

/** Only a signed, fresh-owned-emulator synthetic Android test registers this sink. No production log switch. */
internal fun interface MeowFinalPlanQaSink {
    fun capture(finalPlan: String, fd: Int, lease: MeowTunnelBackend.Lease)
}

class MeowFinalPlanQaSources internal constructor(
    val revision: String, val intentEpoch: Long, val profileId: String,
    val heldHandoffJson: String, val runtimeSettingsJson: String,
    val storeRoot: File, val readRoot: File, val geoAuthority: File, val capabilitiesJson: String,
    val appliedFile: File, val userRequested: Boolean,
    val setupRecordJson: String, val vpnOptionsJson: String, val notificationJson: String, val compiledYaml: String,
)

/** Private raw synthetic inputs only. This packet makes no original312 or host-replay equivalence claim. */
internal object MeowFinalPlanQaPacket {
    private const val MAX_FILE = 64L * 1024 * 1024
    private const val MAX_TOTAL = 640L * 1024 * 1024
    private data class Stamp(val bytes: Long, val sha: String, val modified: Long, val directory: Boolean = false)
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun tree(root: File, live: () -> Unit): Map<String, Stamp> {
        require(root.isDirectory && root.toPath() == root.toPath().toRealPath()) { "QA closure root is missing or redirected" }
        val rows = linkedMapOf<String, Stamp>()
        Files.walk(root.toPath()).use { paths -> paths.forEach { path ->
            live()
            require(!Files.isSymbolicLink(path)) { "QA closure contains a symbolic link" }
            if (path == root.toPath()) return@forEach
            val relative = root.toPath().relativize(path).joinToString("/")
            require(relative.isNotBlank() && relative.split('/').all { it !in setOf("", ".", "..") } && ':' !in relative && '\\' !in relative)
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                require(rows.size < 4096)
                rows[relative] = Stamp(0, "", Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis(), true)
                return@forEach
            }
            require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "QA closure has a non-regular capability" }
            require(rows.size < 4096 && Files.size(path) <= MAX_FILE) { "QA closure input budget exceeded" }
            require(rows.values.sumOf { it.bytes } + Files.size(path) <= MAX_TOTAL)
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val chunk = ByteArray(65536); var count = 0L
                while (true) { live(); val n = input.read(chunk); if (n < 0) break
                    count += n; require(count <= MAX_FILE); hash.update(chunk, 0, n) }
            }
            rows[relative] = Stamp(Files.size(path), hash.digest().joinToString("") { "%02x".format(it) }, Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis())
        } }
        return rows
    }
    fun write(output: File, identity: JSONObject, finalPlan: String, fd: Int,
        sources: MeowFinalPlanQaSources, current: () -> Boolean, protector: Boolean, connectionOwner: Boolean,
        verifyLease: () -> Unit,
    ): File {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
        fun live() { require(current() && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) { "QA lease or capture budget expired" } }
        require(finalPlan.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == finalPlan) { "Final plan is not lossless UTF-8" }
        require(identity.getBoolean("syntheticQA") && !identity.getBoolean("original312Input") &&
            identity.getString("selector") == "freshDefaultFinalPlanCapture") { "Synthetic QA identity required" }
        // Fail before raw bytes/output creation if any actual instrumentation identity is absent or malformed.
        for (key in listOf("source", "tree")) require(identity.getString(key).matches(Regex("[0-9a-f]{40}"))) { "Invalid QA Git identity" }
        for (key in listOf("apkSha256", "meowOverlaySha256", "meowFileManifestSha256", "hostOwnerSha256", "signerSha256"))
            require(identity.getString(key).matches(Regex("[0-9a-f]{64}"))) { "Invalid QA sealed identity" }
        // BUILD_INFO.workflow retains the producer's full run URL (verify_apk.py contract), never a run-number substitute.
        require(identity.getString("workflow").matches(Regex("""https://github\.com/IvanSolis1989/Aerove/actions/runs/[0-9]+"""))) { "Invalid QA producer workflow URL" }
        for (key in listOf("runAttempt", "executorRun", "executorAttempt"))
            require(identity.getString(key).matches(Regex("[1-9][0-9]*"))) { "Invalid QA run identity" }
        require(identity.getString("qaToken").matches(Regex("[a-zA-Z0-9_-]{1,80}")) &&
            identity.getLong("uid") >= 0 && identity.getLong("firstInstallTime") > 0) { "Invalid QA installation identity" }
        require(!output.exists() && current()) { "QA output must be new and lease current" }
        val plan = JSONObject(finalPlan)
        require(plan.getString("runtimeMode") == "proxy" && plan.getBoolean("initiallyParked") && fd == -1) { "QA input is not the fresh parked graph" }
        require(plan.getString("profileId") == sources.profileId && plan.getString("revision") == sources.revision)
        require(File(plan.getString("resourceReadRoot")) == sources.readRoot)
        val settings = JSONObject(sources.runtimeSettingsJson)
        val proof = settings.getJSONObject("flutterSetup").getJSONObject("data-source-proof")
        require(proof.getInt("schema") in 3..4 && proof.getJSONObject("tables").getJSONObject("profiles").getInt("rows") == 0) { "QA source projection is not fresh-null" }
        require(proof.getString("configSha256").matches(Regex("[0-9a-f]{64}")))
        val cache = File(plan.getString("cacheRoot"))
        val roots = linkedMapOf("store" to sources.storeRoot, "read-root" to sources.readRoot, "cache-baseline" to cache)
        val before = roots.mapValues { tree(it.value, ::live) }
        require(before.values.sumOf { rows -> rows.values.sumOf { it.bytes } } <= MAX_TOTAL)
        require(sources.appliedFile.isFile && !Files.isSymbolicLink(sources.appliedFile.toPath()) && sources.appliedFile.length() <= MAX_FILE)
        val applied = sources.appliedFile.readBytes()
        require(applied.contentEquals(plan.getString("engineYaml").toByteArray(Charsets.UTF_8))) { "Actual applied bytes differ from final engine" }
        val handoff = JSONObject(sources.heldHandoffJson)
        require(digest(sources.compiledYaml.toByteArray(Charsets.UTF_8)) == handoff.getString("compiledSha256"))
        require(plan.getString("resourceGeneration") == handoff.getString("compiledSha256"))
        val assets = handoff.getJSONArray("resources")
        val relocation = File(sources.readRoot, "relocation-receipt.json")
        require(relocation.isFile) { "QA relocation receipt missing" }
        val receipt = JSONObject(relocation.readText())
        require(receipt.getString("revision") == sources.revision && receipt.getString("compiledSha256") == handoff.getString("compiledSha256"))
        val relocationRows = receipt.getJSONArray("records")
        val seen = mutableSetOf<String>()
        repeat(assets.length()) { index ->
            val asset = assets.getJSONObject(index); val name = asset.getString("relativePath")
            require(seen.add(name) && before.getValue("store")[name]?.sha == asset.getString("sha256")) { "QA store source binding changed" }
            val matches = (0 until relocationRows.length()).map(relocationRows::getJSONObject).filter { it.getString("file") == name }
            require(matches.size == 1 && matches.single().getString("sourceSha256") == asset.getString("sha256") &&
                before.getValue("read-root")[name]?.sha == matches.single().getString("runtimeSha256")) { "QA relocated payload binding changed" }
        }
        require(relocationRows.length() == seen.size)
        require(output.mkdirs())
        var total = 0L
        val entries = JSONArray()
        val directories = JSONArray()
        fun writeBytes(relative: String, bytes: ByteArray) {
            total += bytes.size
            require(bytes.size <= MAX_FILE && total <= MAX_TOTAL && System.nanoTime() < deadline && current()) { "QA copy budget or lease expired" }
            val target = File(output, relative); check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
            require(!target.exists())
            FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
            check(sha256(target) == digest(bytes))
            entries.put(JSONObject().put("path", relative).put("bytes", target.length()).put("sha256", sha256(target)))
        }
        try {
            writeBytes("final-plan.json", finalPlan.toByteArray(Charsets.UTF_8))
            writeBytes("engine.yaml", plan.getString("engineYaml").toByteArray(Charsets.UTF_8))
            writeBytes("applied-engine.yaml", applied)
            writeBytes("host-policy.yaml", plan.getString("hostPolicyYaml").toByteArray(Charsets.UTF_8))
            writeBytes("inbounds.yaml", plan.getJSONObject("inbounds").getString("configYaml").toByteArray(Charsets.UTF_8))
            writeBytes("runtime-settings.json", sources.runtimeSettingsJson.toByteArray(Charsets.UTF_8))
            writeBytes("setup-record.json", sources.setupRecordJson.toByteArray(Charsets.UTF_8))
            writeBytes("vpn-options.json", sources.vpnOptionsJson.toByteArray(Charsets.UTF_8))
            writeBytes("notification.json", sources.notificationJson.toByteArray(Charsets.UTF_8))
            writeBytes("compiled-source.yaml", sources.compiledYaml.toByteArray(Charsets.UTF_8))
            writeBytes("held-handoff.json", sources.heldHandoffJson.toByteArray(Charsets.UTF_8))
            writeBytes("capabilities.json", sources.capabilitiesJson.toByteArray(Charsets.UTF_8))
            for ((group, root) in roots) {
                check(File(output, "closure/$group").mkdirs()); directories.put("closure/$group")
                for ((relative, stamp) in before.getValue(group)) {
                if (stamp.directory) {
                    val directory = File(output, "closure/$group/$relative")
                    check(directory.mkdirs() || directory.isDirectory); directories.put("closure/$group/$relative"); continue
                }
                require(current() && System.nanoTime() < deadline)
                val file = File(root, relative)
                require(file.length() <= MAX_FILE)
                val data = java.io.ByteArrayOutputStream()
                Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                    val chunk = ByteArray(65536)
                    while (true) { live(); val n = input.read(chunk); if (n < 0) break
                        require(data.size().toLong() + n <= MAX_FILE); data.write(chunk, 0, n) }
                }
                val bytes = data.toByteArray()
                require(bytes.size.toLong() == stamp.bytes && bytes.size <= MAX_FILE)
                writeBytes("closure/$group/$relative", bytes)
                require(sha256(File(output, "closure/$group/$relative")) == stamp.sha) { "QA binary copy hash differs" }
            }
            }
            verifyLease(); live()
            require(sources.appliedFile.readBytes().contentEquals(applied) && roots.all { (name, root) -> tree(root, ::live) == before.getValue(name) }) { "QA closure changed during capture" }
            val filtered = JSONArray(checkNotNull(sources.geoAuthority.listFiles()) { "Geo authority listing unavailable" }.filter { it.canonicalFile != sources.readRoot.canonicalFile }.map { it.name })
            val row = JSONObject().put("schema", 1).put("captureCompleted", true).put("identity", identity)
                .put("newSyntheticInput", true).put("original312Input", false).put("accepted", false).put("hostReplayReady", false)
                .put("revision", sources.revision).put("intentEpoch", sources.intentEpoch).put("opaqueProfileId", sources.profileId)
                .put("userRequested", sources.userRequested).put("resourceGeneration", plan.getString("resourceGeneration"))
                .put("compiledSha256", handoff.getString("compiledSha256")).put("appliedFile", sources.appliedFile.path).put("appliedSha256", digest(applied))
                .put("relocationReceiptSha256", before.getValue("read-root").getValue("relocation-receipt.json").sha)
                .put("capturedDirectories", directories)
                .put("sourceProof", proof).put("sourceProofRepresentation", "derived object inside exact runtime-settings bytes")
                .put("storeHandoffRepresentation", "held immutable object serialized by existing RuntimeHandoff.json; not disk state-file bytes")
                .put("directories", JSONObject(roots.mapValues { it.value.path })).put("geoReadRootAuthority", sources.geoAuthority.path)
                .put("filteredAuthoritySiblings", filtered).put("missing", JSONArray()).put("transformsApplied", JSONArray())
                .put("capabilities", JSONObject().put("fdArgument", fd).put("protectorPresent", protector).put("connectionOwnerPresent", connectionOwner)
                    .put("portable", false).put("rebindRequired", JSONArray(listOf("absolute roots", "physical network", "host binding/authority"))))
                .put("cacheBaselineEmpty", before.getValue("cache-baseline").isEmpty()).put("files", entries)
                .put("nativeStartOutcome", "not observed at this pre-start boundary")
            val manifest = File(output, "capture-manifest.json")
            FileOutputStream(manifest).use { it.write((row.toString(2) + "\n").toByteArray(Charsets.UTF_8)); it.fd.sync() }
            return manifest
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(File(output, "capture-manifest.json").toPath()) }.onFailure { if (it !== failure) failure.addSuppressed(it) }
            runCatching { File(output, "capture-failed.json").writeText(JSONObject().put("captureCompleted", false).put("failureClass", failure.javaClass.name).toString()) }
                .onFailure { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }
}

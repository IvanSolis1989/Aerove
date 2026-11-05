package dev.aerove.lite.platformqa

import dev.aerove.lite.flutter.withNativeLiteReadOnlyDatabase
import android.util.Base64
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Synthetic source bytes only; production Launcher/channel/Target own every migration transition. */
class NativeLegacyMigrationFixtureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val harness = instrumentation.context
    private val arguments = InstrumentationRegistry.getArguments()
    private val assets = "native-migration-fixture"
    private data class Record(val path: String, val bytes: Long, val digest: String)

    private fun argument(name: String, pattern: String): String =
        requireNotNull(arguments.getString(name)) { "Missing $name" }.also {
            require(it.matches(Regex(pattern))) { "Invalid $name" }
        }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun digest(file: File) = file.inputStream().use { streamDigest(it).second }
    private fun streamDigest(input: InputStream): Pair<Long, String> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            count += size
            check(count <= 512L * 1024 * 1024) { "Fixture stream limit" }
            hash.update(buffer, 0, size)
        }
        return count to hex(hash.digest())
    }
    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    private fun assetBytes(path: String): ByteArray = harness.assets.open("$assets/$path").use {
        val buffer = ByteArray(8 * 1024 * 1024 + 1)
        var count = 0
        while (count < buffer.size) {
            val size = it.read(buffer, count, buffer.size - count)
            if (size < 0) break
            count += size
        }
        check(count < buffer.size) { "Fixture JSON limit" }
        buffer.copyOf(count)
    }
    private fun json(file: File) = JSONObject(text(file.readBytes()))
    private fun relative(path: String): String = path.also {
        require(it.length <= 1024 && !File(it).isAbsolute && '\\' !in it && ':' !in it &&
            it.none { character -> character.isISOControl() } && it.split('/').size <= 32 &&
            it.split('/').none { part -> part.isEmpty() || part == "." || part == ".." }) { "Unsafe relative path" }
    }
    private fun privateFile(root: File, path: String): File {
        val file = File(root, relative(path)).absoluteFile
        require(file.toPath().startsWith(root.toPath()) && file.canonicalFile == file) { "Indirect private path" }
        var cursor: File? = file
        while (cursor != null && cursor != root) {
            require(!Files.isSymbolicLink(cursor.toPath())) { "Private symbolic link" }
            cursor = cursor.parentFile
        }
        require(cursor == root) { "Private path escaped" }
        return file
    }
    private fun assetFiles(path: String = assets, depth: Int = 0): Set<String> {
        check(depth <= 35) { "Fixture asset depth" }
        val children = requireNotNull(harness.assets.list(path))
        if (children.isEmpty()) return setOf(path.removePrefix("$assets/"))
        return children.flatMap { assetFiles("$path/$it", depth + 1) }.toSet().also {
            check(it.size <= 12001) { "Fixture asset count" }
        }
    }
    private fun records(manifest: JSONObject): List<Record> {
        require(manifest.getString("format") == "aerove-lite-native-source-export" &&
            manifest.getInt("schema") == 1 && manifest.getString("purpose") == "source-recovery-candidate" &&
            !manifest.getBoolean("migrationComplete")) { "Not a Native recovery export" }
        val rows = manifest.getJSONArray("files")
        require(rows.length() in 1..12000)
        val result = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val path = relative(row.getString("path"))
            require(path.startsWith("profiles/") || path.startsWith("settings/") ||
                path == "backups/webdav.json" || path.startsWith("runtime/resources/")) { "Non-source fixture record" }
            Record(path, row.getLong("bytes"), row.getString("sha256")).also {
                require(it.bytes in 0..64L * 1024 * 1024 && it.digest.matches(Regex("[0-9a-f]{64}")))
            }
        }
        require(result.map { it.path }.distinct().size == result.size && result.sumOf { it.bytes } <= 512L * 1024 * 1024)
        return result
    }
    @Suppress("DEPRECATION")
    private fun identity(context: Context): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = requireNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1 && context.applicationInfo.splitSourceDirs.isNullOrEmpty())
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        return JSONObject().put("package", context.packageName).put("uid", context.applicationInfo.uid)
            .put("pid", Process.myPid()).put("processUid", Process.myUid()).put("versionCode", info.longVersionCode).put("versionName", info.versionName)
            .put("packageCodePath", context.packageCodePath).put("apkSha256", digest(File(context.packageCodePath)))
            .put("signerSha256", digest(signers.single().toByteArray()))
    }
    private fun verifyOriginals(root: File, source: List<Record>) {
        source.forEach { row ->
            val file = privateFile(root, row.path)
            check(file.isFile && file.length() == row.bytes && digest(file) == row.digest) { "Original source changed: ${row.path}" }
        }
    }

    @Test fun syntheticNativeSourceMigratesThroughProductionLauncher() = runScenario(false)

    @Test fun receiptPathFailureRecoversThroughProductionLauncherWithoutChangingSource() = runScenario(true)

    private fun verifyReadOnlyDatabaseConsistency() {
        val directory = File(target.cacheDir,
            "native-readonly-" + java.util.UUID.randomUUID()).apply { check(mkdir()) }
        val database = File(directory, "fixture.sqlite")
        var primaryFailure: Throwable? = null
        try {
            SQLiteDatabase.openOrCreateDatabase(database, null).use { writer ->
                check(writer.enableWriteAheadLogging())
                writer.execSQL("CREATE TABLE fixture (id INTEGER PRIMARY KEY, value INTEGER NOT NULL)")
                writer.execSQL("INSERT INTO fixture (id, value) VALUES (1, 17)")
                val wal = File(database.path + "-wal")
                check(wal.isFile)
                val databaseBefore = database.readBytes()
                val walBefore = wal.readBytes()
                val stable = withNativeLiteReadOnlyDatabase(database, "migration_target_database_changed") { reader ->
                    check(reader.isReadOnly)
                    reader.rawQuery("SELECT value FROM fixture WHERE id = 1", null).use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getInt(0).also { check(!cursor.moveToNext()) }
                    }
                }
                check(stable == 17)
                check(databaseBefore.contentEquals(database.readBytes()))
                check(walBefore.contentEquals(wal.readBytes()))

                var writerCommitted = false
                var returned = false
                val failure = runCatching {
                    withNativeLiteReadOnlyDatabase(database, "shared_source_database_changed") { reader ->
                        check(reader.isReadOnly)
                        reader.rawQuery("SELECT value FROM fixture WHERE id = 1", null).use { cursor ->
                            check(cursor.moveToFirst())
                            check(cursor.getInt(0) == 17)
                        }
                        writer.beginTransaction()
                        try {
                            writer.execSQL("UPDATE fixture SET value = 29 WHERE id = 1")
                            writer.setTransactionSuccessful()
                        } finally { writer.endTransaction() }
                        writerCommitted = true
                        reader.rawQuery("SELECT value FROM fixture WHERE id = 1", null).use { cursor ->
                            check(cursor.moveToFirst())
                            check(cursor.getInt(0) == 29)
                        }
                    }
                    returned = true
                }.exceptionOrNull()
                check(writerCommitted)
                check(!returned)
                check(failure is IllegalStateException)
                check(failure.message == "shared_source_database_changed")
                writer.rawQuery("SELECT value FROM fixture WHERE id = 1", null).use { cursor ->
                    check(cursor.moveToFirst())
                    check(cursor.getInt(0) == 29)
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                check(SQLiteDatabase.deleteDatabase(database))
                check(directory.delete())
            } catch (cleanup: Throwable) {
                val primary = primaryFailure
                if (primary == null) throw cleanup
                primary.addSuppressed(cleanup)
            }
        }
    }

    // Test reference for models/common.dart's v1 accidental-disclosure format, not encryption.
    private fun decodeSharedDavPassword(value: String): String {
        val parts = value.split('.')
        check(parts.size == 3 && parts[0] == "v1") { "Invalid shared DAV password format" }
        fun decode(part: String): ByteArray {
            val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_WRAP)
            check(Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP) == part) { "Invalid shared DAV password encoding" }
            return bytes
        }
        val nonce = decode(parts[1])
        check(nonce.size == 16) { "Invalid shared DAV password nonce" }
        val encoded = decode(parts[2])
        check(encoded.isNotEmpty()) { "Invalid shared DAV password payload" }
        val mask = intArrayOf(
            0x9d,
            0x42,
            0xe7,
            0x1b,
            0x68,
            0xb4,
            0x35,
            0xca,
            0x7f,
            0x20,
            0xd1,
            0x56,
            0x83,
            0xfa,
            0x0c,
            0xa9,
        )
        val decoded = ByteArray(encoded.size) { index ->
            (encoded[index].toInt() xor nonce[index % nonce.size].toInt() xor mask[index % mask.size]).toByte()
        }
        return text(decoded)
    }

    private fun runScenario(recoverReceiptPath: Boolean) {
        val token = argument("qaToken", "[a-zA-Z0-9_-]{1,80}")
        val output = File(requireNotNull(target.getExternalFilesDir(null)).canonicalFile, "native-legacy-fixture-qa/$token")
        check(!output.exists() && output.mkdirs()) { "Evidence token must be new" }
        val evidence = JSONObject().put("schema", 1).put("producer", javaClass.name).put("qaToken", token)
            .put("synthetic", true).put("visualAccepted", false).put("userDataAcceptance", false)
            .put("method", if (recoverReceiptPath) "receiptPathFailureRecoversThroughProductionLauncherWithoutChangingSource" else "syntheticNativeSourceMigratesThroughProductionLauncher")
            .put("cooperativeCancellationTested", false).put("startedElapsedMs", SystemClock.elapsedRealtime())
        var failure: Throwable? = null
        var originalIdentity: JSONObject? = null
        var fixtureRoot: File? = null
        try {
            val sourceBindings = JSONObject()
            listOf("rootSourceSha", "productionSourceSha", "harnessSourceSha", "fixtureSourceSha").forEach {
                sourceBindings.put(it, argument(it, "[0-9a-f]{40}"))
            }
            sourceBindings.put("fixtureProducerRun", argument("fixtureProducerRun", "[1-9][0-9]*"))
                .put("productionBuildInfoSha256", argument("productionBuildInfoSha256", "[0-9a-f]{64}"))
                .put("requiresExternalSourceBuildInfoBinding", true)
            evidence.put("sourceParameters", sourceBindings)
            check(target.packageName == "dev.aerove.lite" && harness.packageName == "dev.aerove.lite.platform.test")
            check(android.app.Application.getProcessName() == target.packageName)
            check(harness.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0)
            evidence.put("processName", android.app.Application.getProcessName())
            val app = identity(target)
            originalIdentity = app
            val runner = identity(harness)
            evidence.put("installedAppBefore", app).put("installedHarness", runner)
            check(app.getString("apkSha256") == argument("productionApkSha256", "[0-9a-f]{64}"))
            check(runner.getString("apkSha256") == argument("harnessApkSha256", "[0-9a-f]{64}"))
            check(app.getString("signerSha256") == argument("productionSignerSha256", "[0-9a-f]{64}") &&
                runner.getString("signerSha256") == app.getString("signerSha256"))
            check(app.getLong("versionCode") == argument("productionVersionCode", "[1-9][0-9]*").toLong())
            check(app.getString("versionName") == argument("productionVersionName", "[0-9A-Za-z.+_-]{1,80}"))
            check(app.getInt("uid") == Process.myUid())
            if (!recoverReceiptPath) {
                verifyReadOnlyDatabaseConsistency()
                evidence.put("readonlyDatabasePreflight", true)
            }
            val manifestBytes = assetBytes("manifest.json")
            val fixtureDigest = argument("fixtureManifestSha256", "[0-9a-f]{64}")
            check(digest(manifestBytes) == fixtureDigest)
            evidence.put("fixtureManifestSha256", fixtureDigest)
            val source = records(JSONObject(text(manifestBytes)))
            check(assetFiles() == setOf("manifest.json") + source.map { "payload/${it.path}" })
            source.forEach { row ->
                val actual = harness.assets.open("$assets/payload/${row.path}").use { streamDigest(it) }
                check(actual.first == row.bytes && actual.second == row.digest) { "Fixture payload mismatch: ${row.path}" }
            }
            val index = JSONObject(text(assetBytes("payload/profiles/index.json")))
            val library = JSONObject(text(assetBytes("payload/settings/overrides.json")))
            check(index.getJSONArray("profiles").length() == 2 && library.getJSONArray("scripts").length() == 1)
            check(index.getJSONArray("profiles").getJSONObject(0).getString("name") == "Fixture subscription" &&
                index.getJSONArray("profiles").getJSONObject(1).getString("name") == "Fixture local" &&
                library.getJSONArray("scripts").getJSONObject(0).getString("name") == "Edited script")
            check(index.getJSONObject("fixtureUnknown").getBoolean("preserve") && library.getBoolean("bundledScriptInitialized"))
            val root = target.filesDir.canonicalFile
            evidence.put("privateRoot", JSONObject().put("absolute", target.filesDir.absolutePath).put("canonical", root.path))
            check(root.listFiles()?.isEmpty() == true) { "Fresh dedicated files root required" }
            check(target.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).all.isEmpty()) { "Shared preferences already exist" }
            check(target.resources.configuration.locales[0].language == "en") { "Fix target locale to en-US before this scenario" }
            fixtureRoot = root
            source.forEach { row ->
                val file = privateFile(root, row.path)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                check(file.createNewFile())
                FileOutputStream(file).use { out ->
                    harness.assets.open("$assets/payload/${row.path}").use { it.copyTo(out) }
                    out.fd.sync()
                }
            }
            val lock = privateFile(root, ".domain.lock")
            check(lock.createNewFile())
            FileOutputStream(lock).use { it.fd.sync() }
            verifyOriginals(root, source)
            evidence.put("seededRecords", JSONArray(source.map { JSONObject().put("path", it.path).put("bytes", it.bytes).put("sha256", it.digest) }))
                .put("seedCompleteElapsedMs", SystemClock.elapsedRealtime())
            File(output, "seed.json").writeText(evidence.toString(2))
            val launch = requireNotNull(target.packageManager.getLaunchIntentForPackage(target.packageName))
            val component = requireNotNull(launch.component)
            check(component.className == "dev.aerove.lite.FlutterMainActivity")
            evidence.put("launcher", component.flattenToString())
            // Connect before phase waits so startup errors can publish accessibility semantics.
            val ui = checkNotNull(instrumentation.uiAutomation) { "UiAutomation connection failed before production Launcher" }
            var recoveryDigest: String? = null
            var recoveryIds: JSONObject? = null
            if (recoverReceiptPath) {
                val receiptLink = privateFile(root, "native-lite-migration/target-receipt.json")
                check(receiptLink.parentFile!!.mkdirs())
                val missingTarget = File(output, "owned-missing-receipt-target").toPath()
                check(!Files.exists(missingTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                Files.createSymbolicLink(receiptLink.toPath(), missingTarget)
                val activity = launchActivity(launch)
                val failedNative = privateFile(root, "native-lite-migration-native/state.json")
                val failedJournal = privateFile(root, "native-lite-migration/journal.json")
                val failedCommitted = privateFile(root, "native-lite-migration-native/committed-receipt.json")
                val failureDeadline = SystemClock.elapsedRealtime() + 60_000
                while (true) {
                    if (failedNative.isFile && failedJournal.isFile &&
                        json(failedNative).getString("phase") == "pending" &&
                        json(failedJournal).getString("phase") == "preferencesCommitted" &&
                        ui.rootInActiveWindow?.let { containsLabel(it, "Startup failed") } == true) break
                    check(SystemClock.elapsedRealtime() < failureDeadline) { "No real pending/preferencesCommitted failure before 60s" }
                    Thread.sleep(100)
                }
                val failedState = json(failedNative)
                val failedDart = json(failedJournal)
                check(failedState.getInt("schema") == 1 && failedDart.getInt("schema") == 1)
                recoveryDigest = failedState.getString("sourceDigest")
                check(failedDart.getString("sourceDigest") == recoveryDigest)
                val failedIds = JSONObject(failedDart.getJSONObject("ids").toString())
                recoveryIds = failedIds
                check(failedIds.length() > 0 && !failedState.has("receiptSha256") && !failedDart.has("receiptSha256"))
                check(!Files.exists(failedCommitted.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                check(Files.isSymbolicLink(receiptLink.toPath()) && Files.readSymbolicLink(receiptLink.toPath()) == missingTarget)
                check(!Files.exists(missingTarget, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                verifyOriginals(root, source)
                val saved = privateFile(root, "native-lite-migration/source/manifest.json")
                check(digest(saved) == recoveryDigest && records(json(saved)) == source)
                source.forEach { row ->
                    val copied = privateFile(root, "native-lite-migration/source/payload/${row.path}")
                    check(copied.length() == row.bytes && digest(copied) == row.digest)
                }
                check(target.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).contains("flutter.config"))
                File(output, "failure-native-state.json").writeBytes(failedNative.readBytes())
                File(output, "failure-dart-journal.json").writeBytes(failedJournal.readBytes())
                evidence.put("receiptPathFailure", JSONObject().put("nativePhase", "pending")
                    .put("dartPhase", "preferencesCommitted").put("startupFailureVisible", true)
                    .put("committedReceiptAbsent", true).put("sourceUnchanged", true)
                    .put("ownedLink", receiptLink.path).put("ownedLinkTarget", missingTarget.toString()))
                    .put("failureObservedElapsedMs", SystemClock.elapsedRealtime())
                // Remove only this fixture's verified link. No journal, plan, IDs or target bytes are edited.
                check(Files.isSymbolicLink(receiptLink.toPath()) && Files.readSymbolicLink(receiptLink.toPath()) == missingTarget)
                Files.delete(receiptLink.toPath())
                instrumentation.runOnMainSync { activity.finish() }
                val destroyDeadline = SystemClock.elapsedRealtime() + 10_000
                while (!activity.isDestroyed) {
                    check(SystemClock.elapsedRealtime() < destroyDeadline) { "Owned Launcher did not finish before 10s" }
                    Thread.sleep(100)
                }
                check(!Files.exists(receiptLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                launchActivity(launch)
                evidence.put("recoveryAction", "remove-owned-link-then-finish-and-relaunch-Launcher")
            } else {
                target.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            val nativeState = privateFile(root, "native-lite-migration-native/state.json")
            val journal = privateFile(root, "native-lite-migration/journal.json")
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (true) {
                if (nativeState.isFile && journal.isFile && json(nativeState).getString("phase") == "shared" &&
                    json(journal).getString("phase") == "complete" &&
                    ui.rootInActiveWindow?.let { containsAgree(it) } == true) break
                check(SystemClock.elapsedRealtime() < deadline) { "No real shared/complete migration before 60s" }
                Thread.sleep(100)
            }
            val state = json(nativeState)
            val dart = json(journal)
            if (recoverReceiptPath) {
                check(state.getString("sourceDigest") == recoveryDigest && dart.getString("sourceDigest") == recoveryDigest)
                val beforeIds = checkNotNull(recoveryIds)
                val afterIds = dart.getJSONObject("ids")
                check(beforeIds.keys().asSequence().toSet() == afterIds.keys().asSequence().toSet())
                beforeIds.keys().forEach { key -> check(beforeIds.getLong(key) == afterIds.getLong(key)) }
                evidence.put("sameJournalSourceAndIdsRecovered", true)
            }
            check(state.getInt("schema") == 1 && dart.getInt("schema") == 1)
            val savedManifest = privateFile(root, "native-lite-migration/source/manifest.json")
            val androidDigest = digest(savedManifest)
            check(records(json(savedManifest)) == source)
            check(state.getString("sourceDigest") == androidDigest && dart.getString("sourceDigest") == androidDigest)
            val receiptFile = privateFile(root, "native-lite-migration/target-receipt.json")
            val committed = privateFile(root, "native-lite-migration-native/committed-receipt.json")
            val receiptDigest = digest(receiptFile)
            check(receiptFile.readBytes().contentEquals(committed.readBytes()) &&
                state.getString("receiptSha256") == receiptDigest && dart.getString("receiptSha256") == receiptDigest)
            val proof = json(receiptFile)
            check(proof.getInt("schema") == 1 && proof.getString("sourceDigest") == androidDigest)
            val files = proof.getJSONArray("files")
            for (i in 0 until files.length()) {
                val row = files.getJSONObject(i)
                val file = privateFile(root, row.getString("path"))
                check(file.isFile && file.length() == row.getLong("bytes") && digest(file) == row.getString("sha256"))
            }
            val preferences = target.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            val configText = checkNotNull(preferences.getString("flutter.config", null))
            check(digest(configText.toByteArray(Charsets.UTF_8)) == proof.getString("configSha256"))
            check(preferences.getLong("flutter.version", -1) == proof.getLong("version") &&
                preferences.getLong("flutter.upgradeVersion", -1) == proof.getLong("upgradeVersion"))
            val config = JSONObject(configText)
            check(config.getJSONObject("appSettingProps").getInt("acceptedDisclaimerVersion") == 0)
            val expectedDavPassword = JSONObject(text(assetBytes("payload/backups/webdav.json"))).getString("password")
            val storedDavPassword = config.getJSONObject("davProps").getString("password")
            check(decodeSharedDavPassword(storedDavPassword) == expectedDavPassword)
            val defaultsFile = privateFile(root, "xclash-defaults.json")
            check(digest(defaultsFile) == proof.getString("defaultsSha256"))
            val defaults = json(defaultsFile)
            check(defaults.getBoolean("complete") && defaults.has("scriptId") && defaults.isNull("scriptId") &&
                defaults.getBoolean("nativeBundledScriptInitialized") && defaults.getString("nativeSourceDigest") == androidDigest)
            verifyDatabase(root, index, library, dart.getJSONObject("ids"), config, proof.getInt("databaseSchema"))
            verifyOriginals(root, source)
            source.forEach { row ->
                val saved = privateFile(root, "native-lite-migration/source/payload/${row.path}")
                check(saved.length() == row.bytes && digest(saved) == row.digest) { "App recovery source changed" }
            }
            check(ui.rootInActiveWindow?.let { containsAgree(it) } == true) { "Production Agree not visible" }
            listOf("native-state" to nativeState, "dart-journal" to journal, "android-source-manifest" to savedManifest,
                "target-receipt" to receiptFile, "committed-receipt" to committed).forEach { (name, file) ->
                File(output, "$name.json").writeBytes(file.readBytes())
            }
            evidence.put("androidSourceDigest", androidDigest).put("receiptSha256", receiptDigest)
                .put("sourceUnchanged", true).put("targetVerified", true).put("status", "passed")
        } catch (problem: Throwable) {
            failure = problem
            evidence.put("status", "failed").put("error", problem.stackTraceToString())
        } finally {
            try {
                val after = identity(target)
                evidence.put("installedAppAfter", after)
                originalIdentity?.let { check(after.toString() == it.toString()) { "Installed App identity changed" } }
            } catch (problem: Throwable) {
                evidence.put("status", "failed").put("identityReadbackError", problem.stackTraceToString())
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
            if (failure != null) captureFailureWindow(output, evidence)
            val statePaths = JSONArray()
            if (failure != null) evidence.put("failureStatePaths", statePaths)
            fixtureRoot?.let { root ->
                runCatching {
                    listOf("native-lite-migration-native/state.json", "native-lite-migration/journal.json").forEach { path ->
                        if (failure != null) {
                            val pathFile = File(root, path)
                            statePaths.put(JSONObject().put("path", path)
                                .put("exists", Files.exists(pathFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                .put("symbolicLink", Files.isSymbolicLink(pathFile.toPath()))
                                .put("regularFile", Files.isRegularFile(pathFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                .put("directory", Files.isDirectory(pathFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)))
                        }
                        val file = privateFile(root, path)
                        if (file.isFile && file.length() <= 8L * 1024 * 1024)
                            File(output, "final-${file.parentFile!!.name}-${file.name}").writeBytes(file.readBytes())
                    }
                }.onFailure { evidence.put("diagnosticCaptureError", it.stackTraceToString()) }
            }
            evidence.put("finishedElapsedMs", SystemClock.elapsedRealtime())
            File(output, "receipt.json").writeText(evidence.toString(2))
        }
        failure?.let { throw it }
    }

    private fun captureFailureWindow(output: File, evidence: JSONObject) {
        val errors = JSONArray()
        evidence.put("failureWindowCaptureErrors", errors)
        runCatching {
            val rows = JSONArray()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int) {
                if (node == null) return
                check(depth < 80 && rows.length() < 4000) { "Accessibility hierarchy exceeds bounded traversal" }
                val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                rows.put(JSONObject().put("depth", depth).put("text", node.text?.toString())
                    .put("description", node.contentDescription?.toString()).put("class", node.className?.toString())
                    .put("package", node.packageName?.toString()).put("visible", node.isVisibleToUser)
                    .put("boundsPx", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow, 0)
            File(output, "failure-window-hierarchy.json").writeText(rows.toString(2) + "\n")
        }.onFailure { errors.put("hierarchy: ${it.stackTraceToString()}") }
        runCatching {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "SurfaceFlinger screenshot failed" }
            try {
                check(bitmap.width > 0 && bitmap.height > 0)
                File(output, "failure-window.png").outputStream().use {
                    check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }.onFailure { errors.put("screenshot: ${it.stackTraceToString()}") }
    }
    private fun launchActivity(launch: Intent): android.app.Activity {
        val monitor = instrumentation.addMonitor("dev.aerove.lite.FlutterMainActivity", null, false)
        try {
            target.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            return checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000)) { "Owned Launcher not observed before 10s" }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun containsLabel(node: android.view.accessibility.AccessibilityNodeInfo, label: String): Boolean {
        if (node.isVisibleToUser && node.packageName?.toString() == target.packageName &&
            (node.text?.toString() == label || node.contentDescription?.toString() == label)) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let { containsLabel(it, label) } == true }
    }

    private fun containsAgree(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        if (node.isVisibleToUser && node.packageName?.toString() == target.packageName &&
            (node.text?.toString() == "Agree" || node.contentDescription?.toString() == "Agree")) return true
        return (0 until node.childCount).any { index -> node.getChild(index)?.let { containsAgree(it) } == true }
    }
    private fun verifyDatabase(root: File, index: JSONObject, library: JSONObject, ids: JSONObject, config: JSONObject, schema: Int) {
        SQLiteDatabase.openDatabase(privateFile(root, "database.sqlite").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == schema)
            val profiles = index.getJSONArray("profiles")
            db.rawQuery("SELECT id,label,url,age_secret_key,script_id,\"order\" FROM profiles ORDER BY \"order\",id", null).use { rows ->
                check(rows.count == profiles.length())
                for (i in 0 until profiles.length()) {
                    check(rows.moveToNext())
                    val old = profiles.getJSONObject(i)
                    val id = ids.getLong("profile:${old.getString("id")}")
                    check(rows.getLong(0) == id && rows.getString(1) == old.getString("name") && !rows.isNull(5) && rows.getInt(5) == i)
                    check(rows.getString(2) == if (old.isNull("url")) "" else old.getString("url"))
                    val metadata = old.optJSONObject("sourceMetadata")
                    val key = if (metadata != null && metadata.has("ageIdentity") && !metadata.isNull("ageIdentity"))
                        text(android.util.Base64.decode(metadata.getString("ageIdentity"), android.util.Base64.DEFAULT)) else ""
                    if (key.isEmpty()) check(rows.isNull(3))
                    else check(!rows.isNull(3) && rows.getString(3) == key)
                    val overrides = library.getJSONArray("profiles")
                    val override = (0 until overrides.length()).map { overrides.getJSONObject(it) }
                        .singleOrNull { it.getString("profileId") == old.getString("id") }
                    if (override == null || override.isNull("scriptId")) check(rows.isNull(4))
                    else check(rows.getLong(4) == ids.getLong("script:${override.getString("scriptId")}"))
                    val original = privateFile(root, "profiles/${old.getString("current")}")
                    check(digest(privateFile(root, "profiles/$id.yaml")) == digest(original))
                }
            }
            check(config.getLong("currentProfileId") == ids.getLong("profile:${index.getString("selected")}"))
            val scripts = library.getJSONArray("scripts")
            db.rawQuery("SELECT id,label,\"order\" FROM scripts ORDER BY \"order\",id", null).use { rows ->
                check(rows.count == scripts.length())
                for (i in 0 until scripts.length()) {
                    check(rows.moveToNext())
                    val old = scripts.getJSONObject(i)
                    val id = ids.getLong("script:${old.getString("id")}")
                    check(rows.getLong(0) == id && rows.getString(1) == old.getString("name") && !rows.isNull(2) && rows.getInt(2) == i)
                    check(privateFile(root, "scripts/$id.js").readBytes().contentEquals(old.getString("content").toByteArray(Charsets.UTF_8)))
                }
            }
            db.rawQuery("SELECT id,definition FROM custom_proxies", null).use { rows ->
                check(rows.count == 1 && rows.moveToFirst())
                val keys = ids.keys().asSequence().filter { it.startsWith("builder-proxy:") }.toList()
                check(keys.size == 1 && rows.getLong(0) == ids.getLong(keys.single()))
                val definition = JSONObject(rows.getString(1))
                check(definition.getString("name") == "Fixture SS" && definition.getString("type") == "ss" &&
                    definition.getString("server") == "127.0.0.2" && definition.getInt("port") == 443 &&
                    definition.getString("cipher") == "aes-128-gcm" && definition.getString("password") == "fixture-only")
            }
        }
    }
}

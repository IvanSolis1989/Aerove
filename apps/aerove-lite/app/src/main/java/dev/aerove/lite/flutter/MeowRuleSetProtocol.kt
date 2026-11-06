package dev.aerove.lite.flutter

import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.MAX_RULE_INPUT_BYTES
import dev.aerove.lite.data.MAX_RULE_TEXT_BYTES
import dev.aerove.lite.data.NativeJsonRulePayloadInspector
import dev.aerove.lite.data.NativeRulePayloadInspector
import dev.aerove.lite.data.copyLocalRuleResource
import dev.aerove.lite.data.sha256
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.utf8Size

/** A file preview owns its immutable input and joined offline readers, independently of the VPN epoch. */
internal class MeowRuleSetProtocol(homeDir: File,
    private val inspector: NativeRulePayloadInspector = nativeRulePayloadInspector()) {
    private val home = MeowFlutterSpill.checkedHome(homeDir)

    suspend fun compile(arguments: Any?): Map<String, String> {
        val row = meowObject(arguments)
        val name = row["name"] as? String
        val empty = row["allow-empty"] as? Boolean
        if (row.keys != setOf("name", "url", "allow-empty") || row["url"] != "" || empty == null ||
            name == null || !name.matches(Regex("[0-9a-f]{32}\\.[0-9]+\\.new")))
            throw MeowProtocolFailure("bad_argument", "Compile requires an owned SQL cache candidate", mapOf("field" to "name"))
        val source = File(home, "providers/rules/$name")
        return snapshot(source.path) { file ->
            val inspection = inspector.inspect(file.path)
            if (!empty && inspection.count == 0)
                throw MeowProtocolFailure("rule_set_empty", "Remote rule set has no usable entries")
            mapOf("behavior" to inspection.behavior, "format" to inspection.format, "sourceSha256" to sha256(file))
        }
    }

    suspend fun dump(arguments: Any?): String {
        val requested = arguments as? String ?: badArgument("Expected a private rule-set path string")
        return snapshot(requested) { file ->
            val inspection = inspector.inspect(file.path)
            val text = inspector.dump(file.path, inspection.behavior, inspection.format)
            if (text.utf8Size() > MAX_RULE_TEXT_BYTES)
                throw MeowProtocolFailure("output_too_large", "Rule-set preview exceeds $MAX_RULE_TEXT_BYTES UTF-8 bytes")
            text
        }
    }

    private suspend fun <T> snapshot(requested: String, read: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (requested.isBlank() || '\u0000' in requested) badArgument("Expected a non-empty private rule-set path")
        val source = checkedFile(requested)
        val before = attributes(source)
        if (before.size() > MAX_RULE_INPUT_BYTES)
            throw MeowProtocolFailure("input_too_large", "Rule-set input exceeds $MAX_RULE_INPUT_BYTES bytes")
        val directory = Files.createTempDirectory(home.toPath(), ".ruleset-preview-").toFile()
        var failure: Throwable? = null
        try {
            val snapshot = File(directory, "payload.bin")
            Files.newInputStream(source.toPath(), READ, NOFOLLOW_LINKS).use {
                copyLocalRuleResource(it, snapshot, MAX_RULE_INPUT_BYTES)
            }
            val after = attributes(checkedFile(requested))
            if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime())
                throw MeowProtocolFailure("source_changed", "Rule-set source changed while taking its preview snapshot")
            val snapshotIdentity = attributes(checkedFile(snapshot.path))
            val result = read(snapshot)
            currentCoroutineContext().ensureActive()
            val inspectedIdentity = attributes(checkedFile(snapshot.path))
            if (snapshotIdentity.fileKey() != inspectedIdentity.fileKey() || snapshotIdentity.size() != inspectedIdentity.size() ||
                snapshotIdentity.lastModifiedTime() != inspectedIdentity.lastModifiedTime())
                throw MeowProtocolFailure("source_changed", "Rule-set preview snapshot changed during inspection")
            result
        } catch (cancelled: CancellationException) { failure = cancelled; throw cancelled }
        catch (error: MeowProtocolFailure) { failure = error; throw error }
        catch (error: Exception) {
            val reported = MeowProtocolFailure("rule_set_error", error.message ?: "Rule-set preview failed",
                mapOf("field" to "path"), error)
            failure = reported
            throw reported
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { withContext(NonCancellable + Dispatchers.IO) {
                MeowFlutterSpill.checkedHome(home)
                MeowFlutterSpill.checkedHome(directory)
                Files.deleteIfExists(File(directory, "payload.bin").toPath())
                Files.deleteIfExists(directory.toPath())
            } }
            catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
        }
    }

    private fun checkedFile(path: String): File {
        try {
            MeowFlutterSpill.checkedHome(home)
            val file = File(path)
            if (!file.isAbsolute || file.absoluteFile != file.canonicalFile || file == home ||
                !file.toPath().startsWith(home.toPath()) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) ||
                file.toPath() != file.toPath().toRealPath()) pathFailure()
            var component: File? = file
            while (component != home) {
                if (component == null || Files.isSymbolicLink(component.toPath())) pathFailure()
                component = component.parentFile
            }
            return file
        } catch (error: MeowProtocolFailure) { throw error }
        catch (error: Exception) { pathFailure(error) }
    }

    private fun attributes(file: File): BasicFileAttributes = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (error: Exception) { pathFailure(error) }
    private fun badArgument(reason: String): Nothing = throw MeowProtocolFailure("bad_argument", reason, mapOf("field" to "path"))
    private fun pathFailure(cause: Throwable? = null): Nothing = throw MeowProtocolFailure("path_error",
        "Rule set must be a canonical private-home file without symbolic links or junctions", mapOf("field" to "path"), cause)
}

internal fun nativeRulePayloadInspector(): NativeRulePayloadInspector = NativeJsonRulePayloadInspector(
    { path -> JniResourceFetch.offlineRead { MeowNativeCore.inspectRuleSet(it, path) } },
    { path, behavior, format -> JniResourceFetch.offlineRead { MeowNativeCore.dumpRuleSet(it, path, behavior, format) } },
)

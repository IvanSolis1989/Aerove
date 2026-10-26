package dev.aerove.lite.integration

import dev.aerove.lite.data.GlobalRulePayloadRepository
import dev.aerove.lite.data.MAX_RULE_INPUT_BYTES
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.profilebuilder.*

/** One shared library, including source-header migration and transactional reference deletion. */
class GlobalResourceWorkspace(
    library: BuilderResourceLibrary,
    profiles: ProfileRepository,
    payloads: GlobalRulePayloadRepository,
    validator: suspend (String) -> String?,
    committed: (BuilderResourceSnapshot) -> Unit,
) {
    val session = GlobalResourceLibrarySession(library, GlobalResourceDeletion { expected, kind, ids, replacement ->
        profiles.commitGlobalResourceMutation(library, expected, replacement, mapOf(kind to ids))
    }, committed)
    val proxyLinks: GlobalProxyLinkCodec = DefaultGlobalProxyLinkCodec(validator)
    val rules: GlobalRuleLibraryAccess = object : GlobalRuleLibraryAccess {
        override suspend fun importUrl(expected: BuilderResourceSnapshot, name: String, url: String) = payloads.importUrl(expected, name, url)
        override suspend fun importFile(expected: BuilderResourceSnapshot, name: String, file: GlobalLibraryFile) =
            file.open(MAX_RULE_INPUT_BYTES).use { payloads.importFile(expected, name, it) }
        override suspend fun saveText(expected: BuilderResourceSnapshot, previousId: String?, name: String, text: String) = payloads.saveText(expected, previousId, name, text)
        override suspend fun preview(expected: BuilderResourceSnapshot, name: String) = payloads.preview(expected, name).let { GlobalRuleLibraryPreview(it.text, it.editable, it.oversize) }
        override suspend fun info(expected: BuilderResourceSnapshot, name: String) = payloads.info(expected, name).let {
            GlobalRuleLibraryInfo(it.behavior, it.format, it.count, it.bytes, it.updatedAt, it.remote)
        }
        override suspend fun options(expected: BuilderResourceSnapshot, previousId: String, name: String, url: String) = payloads.options(expected, previousId, name, url)
        override suspend fun sync(expected: BuilderResourceSnapshot, name: String) = payloads.sync(expected, name)
        override suspend fun delete(expected: BuilderResourceSnapshot, name: String) = payloads.delete(expected, name)
    }
    private val prepareLibrary: suspend () -> BuilderResourceSnapshot = { profiles.migrateGlobalResourceIdentity(library) }
    // perform registers migration in the same session barrier before any domain lock is taken.
    suspend fun prepare(): Boolean = session.perform { prepareLibrary() }
    suspend fun quiesce() = session.quiesce()
    suspend fun resumeAfterMaintenance() {
        session.resumeAfterMaintenance()
        prepare()
    }
    suspend fun closeAndJoin() = session.closeAndJoin()
    fun close() = session.close()
}

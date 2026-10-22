package dev.aerove.lite.backup

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.data.BackupRepository
import dev.aerove.lite.data.BackupRestoreScope
import dev.aerove.lite.data.BackupRestoreStrategy
import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ResourceBundleManager
import dev.aerove.lite.data.WebDavBackupConfig
import dev.aerove.lite.data.WebDavBackupEntry
import dev.aerove.lite.data.WebDavBackupException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFeatureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sessions = mutableListOf<Session>()
    private val simple = "rules: ['MATCH,DIRECT']"

    private data class Data(val root: File, val profiles: ProfileRepository, val settings: LiteSettingsStore, val backups: BackupRepository)
    private data class Session(val data: Data, val platform: Documents, val remote: Remote, val parent: Job,
        val feature: BackupFeature, val factories: AtomicInteger)

    private fun data(): Data {
        val root = temporary.newFolder()
        val runtime = File(root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val profiles = ProfileRepository(root, validator, resources = ResourceBundleManager(runtime, validator,
            NativeResourceValidator { _, _, _, _ -> null }))
        val settings = LiteSettingsStore(root)
        return Data(root, profiles, settings, BackupRepository(root, profiles, settings))
    }

    private fun session(data: Data = data(),
        restoreTransaction: suspend (suspend () -> Unit, suspend () -> Unit) -> Unit = { write, refresh -> write(); refresh() },
        restored: suspend () -> Unit = {}): Session {
        val platform = Documents(temporary.newFolder())
        val remote = Remote()
        val parent = SupervisorJob()
        val factories = AtomicInteger()
        val feature = BackupFeature(data.backups, platform, CoroutineScope(parent + Dispatchers.Default), restored, restoreTransaction) {
            factories.incrementAndGet(); remote
        }
        return Session(data, platform, remote, parent, feature, factories).also(sessions::add)
    }

    @After fun closeSessions() { sessions.forEach { it.feature.close(); it.parent.cancel() } }

    private suspend fun BackupFeature.idle() = withTimeout(10000) { state.first { it.progress == null } }
    private suspend fun Session.open() { feature.open(); feature.idle(); assertTrue(feature.state.value.loaded) }
    private suspend fun Session.configured() {
        open()
        feature.saveWebDav("https://example.com/dav", "account", "private-password", "main.zip")
        feature.idle()
        assertNotNull(feature.state.value.webDav)
    }

    private suspend fun sourceArchive(autoStart: Boolean = false): File {
        val source = data()
        source.profiles.importText("Restored profile", simple)
        source.settings.save(LiteSettings(mode = ProxyMode.Direct, autoStart = autoStart), vpnAuthorized = true)
        return temporary.newFile().also { source.backups.createBackup(it) }
    }

    private suspend fun Session.chooseRestore(file: File): BackupConfirmation {
        feature.requestLocalRestore()
        val request = feature.state.value.document!!
        assertTrue(feature.documentLaunched(request.id))
        assertFalse(feature.documentLaunched(request.id))
        feature.documentReferenceResult(request.id, file.path)
        feature.idle()
        return feature.state.value.confirmation!!
    }

    @Test fun openingIsIdempotentLoadsChoicesAndDoesNotRequestTheNetwork() = runBlocking<Unit> {
        val session = session()
        session.platform.options = BackupOptions(BackupRestoreScope.ProfilesOnly, BackupRestoreStrategy.Override)
        session.data.backups.saveWebDav(WebDavBackupConfig("https://example.com/dav", "user", "hidden"))
        repeat(3) { session.feature.open() }
        session.feature.idle()
        assertEquals(1, session.platform.loads)
        assertEquals(session.platform.options, session.feature.state.value.options)
        assertEquals(0, session.factories.get())
        assertFalse(session.feature.state.value.toString().contains("hidden"))
    }

    @Test fun failedOpenCanRetryWithoutOpeningASecondSession() = runBlocking<Unit> {
        val session = session()
        session.platform.failLoad = true
        session.feature.open(); session.feature.idle()
        assertEquals(BackupNoticeCode.LoadFailed, session.feature.state.value.notice!!.code)
        assertFalse(session.feature.state.value.loaded)
        session.platform.failLoad = false
        session.feature.retry(); session.feature.idle()
        assertTrue(session.feature.state.value.loaded)
        assertEquals(2, session.platform.loads)
    }

    @Test fun optionChangesPersistOnlyOnSuccessfulSaveAndMakeNoNetworkRequest() = runBlocking<Unit> {
        val session = session(); session.open()
        val wanted = BackupOptions(BackupRestoreScope.ProfilesOnly, BackupRestoreStrategy.Override)
        session.platform.failSave = true
        session.feature.changeOptions(wanted); session.feature.idle()
        assertEquals(BackupOptions(), session.feature.state.value.options)
        assertEquals(BackupNoticeCode.OptionsFailed, session.feature.state.value.notice!!.code)
        session.platform.failSave = false
        session.feature.retry(); session.feature.idle()
        assertEquals(wanted, session.feature.state.value.options)
        assertEquals(wanted, session.platform.options)
        assertEquals(0, session.factories.get())
    }

    @Test fun cancelledAndStaleDocumentResultsDoNoIo() = runBlocking<Unit> {
        val session = session(); session.open()
        session.feature.requestLocalBackup()
        val request = session.feature.state.value.document!!
        session.feature.documentReferenceResult(request.id + 1, "not-a-document")
        assertEquals(request, session.feature.state.value.document)
        session.feature.documentReferenceResult(request.id, null)
        assertEquals(BackupNoticeCode.Cancelled, session.feature.state.value.notice!!.code)
        assertEquals(0, session.platform.writes)
        assertEquals(0, session.platform.reads)
        assertEquals(0, session.platform.deletes)
    }

    @Test fun localExportWritesAnActualApplicationBackupAndCleansTemporaryFiles() = runBlocking<Unit> {
        val session = session(); session.open()
        val profile = session.data.profiles.importText("Local source", simple)
        val document = temporary.newFile()
        session.feature.requestLocalBackup()
        session.feature.documentReferenceResult(session.feature.state.value.document!!.id, document.path)
        session.feature.idle()
        assertEquals(BackupNoticeCode.Saved, session.feature.state.value.notice!!.code)
        ZipFile(document).use { zip ->
            assertNotNull(zip.getEntry("manifest.json"))
            assertNotNull(zip.getEntry("settings.json"))
            assertNotNull(zip.getEntry("profiles/${profile.id}/config.yaml"))
        }
        assertEquals(1, session.platform.writes)
        assertEquals(0, session.platform.deletes)
        assertTrue(session.platform.cacheDirectory.listFiles()!!.isEmpty())
    }

    @Test fun failedExportDeletesOnlyItsNewDocumentAndRetryRequestsANewDestination() = runBlocking<Unit> {
        val session = session(); session.open()
        session.platform.failWrite = true
        val document = temporary.newFile()
        val untouched = temporary.newFile().apply { writeText("keep") }
        session.feature.requestLocalBackup()
        val old = session.feature.state.value.document!!
        session.feature.documentReferenceResult(old.id, document.path)
        session.feature.idle()
        assertEquals(BackupNoticeCode.WriteFailed, session.feature.state.value.notice!!.code)
        assertFalse(document.exists())
        assertEquals("keep", untouched.readText())
        assertTrue(session.platform.cacheDirectory.listFiles()!!.isEmpty())
        assertFalse(session.feature.state.value.toString().contains("provider-secret"))
        session.feature.retry()
        assertNotEquals(old.id, session.feature.state.value.document!!.id)
        assertEquals(BackupDocumentKind.Create, session.feature.state.value.document!!.kind)
        assertEquals(1, session.platform.writes)
    }

    @Test fun cleanupFailureIsReportedAndRetryCleansBeforeReopeningThePicker() = runBlocking<Unit> {
        val session = session(); session.open()
        session.platform.failWrite = true; session.platform.failDelete = true
        val document = temporary.newFile()
        session.feature.requestLocalBackup()
        session.feature.documentReferenceResult(session.feature.state.value.document!!.id, document.path)
        session.feature.idle()
        assertEquals(BackupNoticeCode.PartialDocument, session.feature.state.value.notice!!.code)
        assertTrue(document.exists())
        session.platform.failDelete = false
        session.feature.retry(); session.feature.idle()
        assertFalse(document.exists())
        assertEquals(BackupDocumentKind.Create, session.feature.state.value.document!!.kind)
    }

    @Test fun cancellingExportReleasesTheOperationAndDeletesThePartialDocument() = runBlocking<Unit> {
        val session = session(); session.open()
        session.platform.writeGate = CompletableDeferred()
        val document = temporary.newFile()
        session.feature.requestLocalBackup()
        session.feature.documentReferenceResult(session.feature.state.value.document!!.id, document.path)
        withTimeout(10000) { session.platform.writeEntered.await() }
        session.feature.cancel(); session.feature.idle()
        assertEquals(BackupNoticeCode.Cancelled, session.feature.state.value.notice!!.code)
        assertFalse(document.exists())
        assertTrue(session.platform.cacheDirectory.listFiles()!!.isEmpty())
        assertTrue(session.parent.isActive)
    }

    @Test fun localRestoreRequiresConfirmationAndCallsMainOnlyAfterRealPublication() = runBlocking<Unit> {
        val archive = sourceArchive()
        val target = data()
        target.profiles.importText("Old profile", simple)
        var callbacks = 0
        val session = session(target) {
            assertEquals(listOf("Restored profile"), target.profiles.list().map { it.name })
            assertEquals(ProxyMode.Direct, target.settings.read().mode)
            callbacks++
        }
        session.open()
        session.feature.changeOptions(BackupOptions(strategy = BackupRestoreStrategy.Override)); session.feature.idle()
        val confirmation = session.chooseRestore(archive)
        assertEquals(0, session.platform.reads)
        assertEquals(0, callbacks)
        session.feature.confirm(confirmation.id)
        session.feature.confirm(confirmation.id)
        session.feature.idle()
        assertEquals(1, session.platform.reads)
        assertEquals(1, callbacks)
        assertEquals(BackupNoticeCode.Restored, session.feature.state.value.notice!!.code)
    }

    @Test fun profilesOnlyOverrideKeepsSettingsAndRunningLeaseUntilItsOwnerReleasesIt() = runBlocking<Unit> {
        val archive = sourceArchive()
        val session = session(); session.open()
        val old = session.data.profiles.importText("Running profile", simple)
        val running = session.data.profiles.prepareForStart(old.id)
        val lease = session.data.profiles.retain(running)
        val before = session.data.settings.read()
        session.feature.changeOptions(BackupOptions(BackupRestoreScope.ProfilesOnly, BackupRestoreStrategy.Override))
        session.feature.idle()
        session.feature.confirm(session.chooseRestore(archive).id); session.feature.idle()
        assertEquals(before, session.data.settings.read())
        assertEquals(listOf("Restored profile"), session.data.profiles.list().map { it.name })
        val leased = File(session.data.root, "profiles/${running.revision}")
        assertTrue(leased.isFile)
        session.data.profiles.release(lease)
        assertFalse(leased.exists())
    }

    @Test fun failedOrCancelledConfirmationDoesNotNotifyMainOrMutateTheRepository() = runBlocking<Unit> {
        var callbacks = 0
        val session = session { callbacks++ }; session.open()
        val old = session.data.profiles.importText("Keep", simple)
        val corrupt = temporary.newFile().apply { writeText("not an app backup / secret-path") }
        val cancelled = session.chooseRestore(corrupt)
        session.feature.cancelConfirmation(cancelled.id)
        assertEquals(0, session.platform.reads)
        val confirmed = session.chooseRestore(corrupt)
        session.feature.confirm(confirmed.id); session.feature.idle()
        assertEquals(BackupNoticeCode.InvalidArchive, session.feature.state.value.notice!!.code)
        assertEquals(listOf(old), session.data.profiles.list())
        assertEquals(0, callbacks)
        assertFalse(session.feature.state.value.toString().contains("secret-path"))
        session.feature.retry()
        assertEquals(BackupDocumentKind.Open, session.feature.state.value.document!!.kind)
    }

    @Test fun restoringBootAutoStartRequiresExistingVpnPermissionAndProfilesOnlyRemainsAvailable() = runBlocking<Unit> {
        var callbacks = 0
        val session = session { callbacks++ }; session.open()
        val archive = sourceArchive(autoStart = true)
        val before = session.data.settings.read()
        session.feature.confirm(session.chooseRestore(archive).id); session.feature.idle()
        assertEquals(BackupNoticeCode.VpnAuthorizationRequired, session.feature.state.value.notice!!.code)
        assertEquals(before, session.data.settings.read())
        assertTrue(session.data.profiles.list().isEmpty())
        assertEquals(0, callbacks)
        session.feature.changeOptions(BackupOptions(BackupRestoreScope.ProfilesOnly)); session.feature.idle()
        session.feature.confirm(session.chooseRestore(archive).id); session.feature.idle()
        assertEquals(BackupNoticeCode.Restored, session.feature.state.value.notice!!.code)
        assertEquals(before, session.data.settings.read())
        assertEquals(1, callbacks)
    }

    @Test fun refreshFailureRetriesOnlyMainRefreshAndNeverReappliesTheArchive() = runBlocking<Unit> {
        var callbacks = 0
        val session = session { if (++callbacks == 1) throw IOException("main-private-path") }; session.open()
        session.feature.confirm(session.chooseRestore(sourceArchive()).id); session.feature.idle()
        val ids = session.data.profiles.list().map { it.id }
        assertEquals(BackupNoticeCode.RefreshAfterRestoreFailed, session.feature.state.value.notice!!.code)
        session.feature.dismissNotice()
        session.feature.requestLocalBackup()
        session.feature.changeOptions(BackupOptions(BackupRestoreScope.ProfilesOnly))
        assertNull(session.feature.state.value.document)
        assertEquals(BackupNoticeCode.RefreshAfterRestoreFailed, session.feature.state.value.notice!!.code)
        assertEquals(BackupOptions(), session.feature.state.value.options)
        session.feature.retry(); session.feature.idle()
        assertEquals(2, callbacks)
        assertEquals(1, session.platform.reads)
        assertEquals(ids, session.data.profiles.list().map { it.id })
        assertEquals(BackupNoticeCode.Restored, session.feature.state.value.notice!!.code)
    }

    @Test fun transactionReloadFailureRetriesRefreshWithoutReplayingTheCommittedArchive() = runBlocking<Unit> {
        var transactions = 0
        var refreshes = 0
        val session = session(restoreTransaction = { write, refresh ->
            write()
            if (++transactions == 1) throw IOException("reload-fixture")
            refresh()
        }, restored = { refreshes++ })
        session.open()
        session.feature.confirm(session.chooseRestore(sourceArchive()).id); session.feature.idle()
        val ids = session.data.profiles.list().map { it.id }
        assertEquals(BackupNoticeCode.RefreshAfterRestoreFailed, session.feature.state.value.notice!!.code)
        assertEquals(0, refreshes)
        session.feature.retry(); session.feature.idle()
        assertEquals(2, transactions)
        assertEquals(1, session.platform.reads)
        assertEquals(ids, session.data.profiles.list().map { it.id })
        assertEquals(1, refreshes)
        assertEquals(BackupNoticeCode.Restored, session.feature.state.value.notice!!.code)
    }

    @Test fun restoreCancellationButtonCannotInterruptPublicationOrStartAnotherOperation() = runBlocking<Unit> {
        val session = session(); session.open()
        session.platform.readGate = CompletableDeferred()
        session.feature.confirm(session.chooseRestore(sourceArchive()).id)
        withTimeout(10000) { session.platform.readEntered.await() }
        assertFalse(session.feature.state.value.progress!!.cancellable)
        session.feature.cancel(); session.feature.requestLocalBackup()
        assertNull(session.feature.state.value.document)
        assertEquals(BackupProgress.Restoring, session.feature.state.value.progress)
        session.platform.readGate!!.complete(Unit)
        session.feature.idle()
        assertEquals(BackupNoticeCode.Restored, session.feature.state.value.notice!!.code)
    }

    @Test fun credentialsStayPrivateAndCannotBeReusedForADifferentServer() = runBlocking<Unit> {
        val session = session(); session.configured()
        assertEquals(0, session.factories.get())
        session.feature.saveWebDav("https://example.com/dav", "account", null, "second.zip")
        session.feature.idle()
        assertEquals("private-password", session.data.backups.readWebDav()!!.password)
        assertEquals("second.zip", session.feature.state.value.webDav!!.fileName)
        assertFalse(session.feature.state.value.toString().contains("private-password"))
        session.feature.saveWebDav("https://another.example.com/dav", "account", null, "other.zip")
        assertEquals(BackupNoticeCode.PasswordRequired, session.feature.state.value.notice!!.code)
        assertEquals("https://example.com/dav", session.data.backups.readWebDav()!!.url)
        session.feature.saveWebDav("http://example.com/dav", "account", "secret", "other.zip")
        assertEquals(BackupNoticeCode.InvalidWebDav, session.feature.state.value.notice!!.code)
        session.feature.requestUnbind()
        session.feature.cancelConfirmation(session.feature.state.value.confirmation!!.id)
        assertNotNull(session.data.backups.readWebDav())
        session.feature.requestUnbind(); session.feature.confirm(session.feature.state.value.confirmation!!.id)
        session.feature.idle()
        assertNull(session.data.backups.readWebDav())
        assertEquals(0, session.factories.get())
    }

    @Test fun explicitRemoteActionsUseRealArchivesAndConfirmEveryDestructiveTransfer() = runBlocking<Unit> {
        var callbacks = 0
        val session = session { callbacks++ }; session.configured()
        session.data.profiles.importText("Remote profile", simple)
        session.feature.requestRemoteBackup()
        assertEquals(0, session.remote.uploads)
        session.feature.confirm(session.feature.state.value.confirmation!!.id); session.feature.idle()
        assertEquals(1, session.remote.uploads)
        val saved = temporary.newFile().apply { writeBytes(session.remote.files.getValue("main.zip")) }
        ZipFile(saved).use { assertNotNull(it.getEntry("manifest.json")); assertNotNull(it.getEntry("settings.json")) }
        session.feature.listRemote(); session.feature.idle()
        assertEquals(listOf("main.zip"), session.feature.state.value.remoteFiles.map { it.name })
        assertTrue(session.feature.state.value.listed)
        session.feature.checkConnection(); session.feature.idle()
        assertEquals(BackupConnection.Connected, session.feature.state.value.connection)
        session.feature.requestRemoteRestore("main.zip")
        assertEquals(0, session.remote.downloads)
        session.feature.confirm(session.feature.state.value.confirmation!!.id); session.feature.idle()
        assertEquals(1, session.remote.downloads)
        assertEquals(1, callbacks)
        session.feature.requestRemoteDelete("main.zip")
        assertEquals(0, session.remote.deletes)
        session.feature.confirm(session.feature.state.value.confirmation!!.id); session.feature.idle()
        assertEquals(1, session.remote.deletes)
        assertTrue(session.remote.files.isEmpty())
        assertTrue(session.platform.cacheDirectory.listFiles()!!.isEmpty())
    }

    @Test fun remoteErrorsRemainSanitizedAndRetryRequiresFreshConfirmation() = runBlocking<Unit> {
        val session = session(); session.configured()
        session.remote.uploadFailure = WebDavBackupException("upload", 401)
        session.feature.requestRemoteBackup(); session.feature.confirm(session.feature.state.value.confirmation!!.id)
        session.feature.idle()
        assertEquals(BackupNoticeCode.WebDavCredentials, session.feature.state.value.notice!!.code)
        assertEquals(1, session.remote.uploads)
        session.remote.uploadFailure = null
        session.feature.retry()
        assertEquals(BackupConfirmationKind.UploadRemote, session.feature.state.value.confirmation!!.kind)
        assertEquals(1, session.remote.uploads)
        session.feature.confirm(session.feature.state.value.confirmation!!.id); session.feature.idle()
        assertEquals(2, session.remote.uploads)
        assertTrue(session.platform.cacheDirectory.listFiles()!!.isEmpty())
    }

    @Test fun closingCancelsOnlyOwnedJobsAndStillCleansThePartialDocument() = runBlocking<Unit> {
        val session = session(); session.open()
        val siblingStarted = CompletableDeferred<Unit>()
        val sibling = CoroutineScope(session.parent + Dispatchers.Default).launch { siblingStarted.complete(Unit); awaitCancellation() }
        siblingStarted.await()
        session.platform.writeGate = CompletableDeferred()
        val document = temporary.newFile()
        session.feature.requestLocalBackup()
        session.feature.documentReferenceResult(session.feature.state.value.document!!.id, document.path)
        session.platform.writeEntered.await()
        session.feature.close()
        withTimeout(10000) { session.platform.deleted.await() }
        assertTrue(session.parent.isActive)
        assertTrue(sibling.isActive)
        assertTrue(session.platform.closed)
        assertFalse(document.exists())
        assertNull(session.feature.state.value.progress)
        assertNull(session.feature.state.value.webDav)
        session.feature.open(); session.feature.requestLocalRestore()
        assertNull(session.feature.state.value.document)
        sibling.cancel()
    }

    private class Documents(override val cacheDirectory: File) : BackupPlatform {
        var options = BackupOptions()
        var loads = 0
        var reads = 0
        var writes = 0
        var deletes = 0
        var closed = false
        var failLoad = false
        var failSave = false
        var failWrite = false
        var failDelete = false
        var writeGate: CompletableDeferred<Unit>? = null
        var readGate: CompletableDeferred<Unit>? = null
        val writeEntered = CompletableDeferred<Unit>()
        val readEntered = CompletableDeferred<Unit>()
        val deleted = CompletableDeferred<Unit>()
        override suspend fun loadOptions(): BackupOptions { loads++; if (failLoad) throw IOException(); return options }
        override suspend fun saveOptions(options: BackupOptions) { if (failSave) throw IOException(); this.options = options }
        override suspend fun <T> readDocument(reference: String, action: suspend (InputStream) -> T): T {
            reads++; readEntered.complete(Unit); readGate?.await()
            return File(reference).inputStream().use { action(it) }
        }
        override suspend fun writeDocument(reference: String, source: File) {
            writes++
            File(reference).writeText("partial")
            writeEntered.complete(Unit); writeGate?.await()
            if (failWrite) throw IOException("provider-secret")
            source.inputStream().use { input -> File(reference).outputStream().use { input.copyTo(it) } }
        }
        override suspend fun deleteCreatedDocument(reference: String): Boolean {
            deletes++
            if (failDelete) return false
            return (!File(reference).exists() || File(reference).delete()).also { deleted.complete(Unit) }
        }
        override suspend fun documentName(reference: String) = File(reference).name
        override suspend fun vpnAuthorized() = false
        override fun close() { closed = true }
    }

    private class Remote : BackupRemote {
        val files = linkedMapOf<String, ByteArray>()
        var uploads = 0
        var downloads = 0
        var deletes = 0
        var uploadFailure: Exception? = null
        override suspend fun ping() = Unit
        override suspend fun list() = files.map { (name, bytes) -> WebDavBackupEntry(name, bytes.size.toLong(), null, null) }
        override suspend fun upload(source: File, name: String) {
            uploads++; uploadFailure?.let { throw it }; files[name] = source.readBytes()
        }
        override suspend fun download(destination: File, name: String) { downloads++; destination.writeBytes(files.getValue(name)) }
        override suspend fun delete(name: String) { deletes++; files.remove(name) }
    }
}

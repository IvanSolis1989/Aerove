package dev.aerove.lite.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.data.BackupRepository
import dev.aerove.lite.data.BackupRestoreScope
import dev.aerove.lite.data.BackupRestoreStrategy
import dev.aerove.lite.data.WebDavBackupClient
import dev.aerove.lite.data.WebDavBackupConfig
import dev.aerove.lite.data.WebDavBackupEntry
import dev.aerove.lite.data.WebDavBackupException
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class BackupOptions(
    val scope: BackupRestoreScope = BackupRestoreScope.All,
    val strategy: BackupRestoreStrategy = BackupRestoreStrategy.Compatible,
)

enum class BackupProgress(val cancellable: Boolean = true) {
    Loading, SavingOptions, SavingWebDav, CheckingConnection, Listing, Packaging, WritingDocument,
    ReadingDocument, Uploading, Downloading, Restoring(false), Refreshing(false), Deleting,
}
enum class BackupConnection { NotChecked, Connected, Failed }
enum class BackupDocumentKind { Create, Open }
enum class BackupConfirmationKind { RestoreLocal, RestoreRemote, UploadRemote, DeleteRemote, UnbindWebDav }
enum class BackupNoticeCode {
    Saved, Uploaded, Restored, Deleted, Configured, Unbound, Cancelled,
    LoadFailed, OptionsFailed, WebDavSaveFailed, InvalidWebDav, PasswordRequired, WebDavCredentials, RemoteMissing,
    RemoteConflict, RemoteFailed, DocumentPermission, ReadFailed, WriteFailed, InvalidArchive,
    VpnAuthorizationRequired, RefreshAfterRestoreFailed, PartialDocument,
}

data class BackupWebDavUi(val address: String, val user: String, val fileName: String, val hasPassword: Boolean, val anonymous: Boolean)
data class BackupRemoteFile(val name: String, val bytes: Long?)
data class BackupDocumentRequest(val id: Long, val kind: BackupDocumentKind, val suggestedName: String, val dispatched: Boolean = false)
data class BackupConfirmation(val id: Long, val kind: BackupConfirmationKind, val fileName: String?, val options: BackupOptions)
data class BackupNotice(val id: Long, val code: BackupNoticeCode, val error: Boolean, val retryable: Boolean)
data class BackupUiState(
    val loaded: Boolean = false,
    val options: BackupOptions = BackupOptions(),
    val webDav: BackupWebDavUi? = null,
    val connection: BackupConnection = BackupConnection.NotChecked,
    val remoteFiles: List<BackupRemoteFile> = emptyList(),
    val listed: Boolean = false,
    val progress: BackupProgress? = null,
    val document: BackupDocumentRequest? = null,
    val confirmation: BackupConfirmation? = null,
    val notice: BackupNotice? = null,
) {
    val busy: Boolean get() = progress != null || document != null || confirmation != null
}

interface BackupActions {
    fun open()
    fun changeOptions(options: BackupOptions)
    fun requestLocalBackup()
    fun requestLocalRestore()
    fun saveWebDav(address: String, user: String, password: String?, fileName: String, anonymous: Boolean = false)
    fun requestUnbind()
    fun checkConnection()
    fun listRemote()
    fun selectRemoteFile(name: String)
    fun requestRemoteBackup()
    fun requestRemoteRestore(name: String)
    fun requestRemoteDelete(name: String)
    fun confirm(id: Long)
    fun cancelConfirmation(id: Long)
    fun cancel()
    fun retry()
    fun dismissNotice()
}

internal interface BackupPlatform : Closeable {
    val cacheDirectory: File
    suspend fun loadOptions(): BackupOptions
    suspend fun saveOptions(options: BackupOptions)
    suspend fun <T> readDocument(reference: String, action: suspend (InputStream) -> T): T
    suspend fun writeDocument(reference: String, source: File)
    suspend fun deleteCreatedDocument(reference: String): Boolean
    suspend fun documentName(reference: String): String?
    suspend fun vpnAuthorized(): Boolean
}

internal interface BackupRemote {
    suspend fun ping()
    suspend fun list(): List<WebDavBackupEntry>
    suspend fun upload(source: File, name: String)
    suspend fun download(destination: File, name: String)
    suspend fun delete(name: String)
}

class BackupFeature internal constructor(
    private val repository: BackupRepository,
    private val platform: BackupPlatform,
    scope: CoroutineScope,
    private val onRestored: suspend () -> Unit,
    private val remoteFactory: (WebDavBackupConfig) -> BackupRemote,
) : BackupActions, Closeable {
    constructor(application: LiteApplication, scope: CoroutineScope, onRestored: suspend () -> Unit) : this(
        BackupRepository(application.filesDir, application.profiles, application.settings),
        AndroidBackupPlatform(application), scope, onRestored,
    )

    private constructor(repository: BackupRepository, platform: AndroidBackupPlatform, scope: CoroutineScope,
        onRestored: suspend () -> Unit) : this(repository, platform, scope, onRestored,
        { config -> HttpBackupRemote(WebDavBackupClient(config, platform.http)) })

    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val mutableState = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutableState.asStateFlow()
    private val gate = Any()
    private var active: Job? = null
    private var opened = false
    @Volatile private var closed = false
    private var serial = 0L
    private var config: WebDavBackupConfig? = null
    private var pending: Action? = null
    private var retryAction: Action? = null
    private var refreshRequired = false

    private sealed interface Action {
        data object Load : Action
        data class Options(val value: BackupOptions) : Action
        data class Configure(val value: WebDavBackupConfig) : Action
        data object Unbind : Action
        data object Ping : Action
        data object ListFiles : Action
        data class Select(val name: String) : Action
        data class Export(val reference: String) : Action
        data class Inspect(val reference: String, val options: BackupOptions) : Action
        data class RestoreLocal(val reference: String, val options: BackupOptions) : Action
        data class Upload(val name: String) : Action
        data class RestoreRemote(val name: String, val options: BackupOptions) : Action
        data class Delete(val name: String) : Action
        data object Refresh : Action
        data class Cleanup(val reference: String) : Action
        data class Pick(val kind: BackupDocumentKind) : Action
    }

    override fun open() = synchronized(gate) {
        if (!opened && !closed) { opened = true; start(Action.Load) }
    }

    override fun changeOptions(options: BackupOptions) { start(Action.Options(options)) }
    override fun requestLocalBackup() { pick(BackupDocumentKind.Create) }
    override fun requestLocalRestore() { pick(BackupDocumentKind.Open) }

    private fun pick(kind: BackupDocumentKind) = synchronized(gate) {
        if (!available()) return@synchronized
        retryAction = null
        val name = "aerove-lite-${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))}.zip"
        publish { it.copy(document = BackupDocumentRequest(++serial, kind, name), notice = null) }
    }

    fun documentLaunched(id: Long): Boolean = synchronized(gate) {
        val request = state.value.document
        if (closed || request?.id != id || request.dispatched) false else {
            publish { it.copy(document = request.copy(dispatched = true)) }
            true
        }
    }

    fun documentResult(id: Long, uri: Uri?) { documentReferenceResult(id, uri?.toString()) }

    internal fun documentReferenceResult(id: Long, reference: String?) = synchronized(gate) {
        val request = state.value.document
        if (closed || request?.id != id) return@synchronized
        publish { it.copy(document = null) }
        if (reference == null) { notice(BackupNoticeCode.Cancelled); return@synchronized }
        start(if (request.kind == BackupDocumentKind.Create) Action.Export(reference) else Action.Inspect(reference, state.value.options))
    }

    fun documentLaunchFailed(id: Long) = synchronized(gate) {
        val request = state.value.document
        if (closed || request?.id != id) return@synchronized
        publish { it.copy(document = null) }
        problem(BackupNoticeCode.DocumentPermission, Action.Pick(request.kind))
    }

    override fun saveWebDav(address: String, user: String, password: String?, fileName: String, anonymous: Boolean) = synchronized(gate) {
        if (!available()) return@synchronized
        val previous = config
        val secret = if (anonymous) "" else password ?: previous?.takeIf { it.url == address.trim() && it.user == user }?.password
        if (!anonymous && (user.isEmpty() || secret.isNullOrEmpty())) {
            problem(BackupNoticeCode.PasswordRequired, null)
            return@synchronized
        }
        val next = try { WebDavBackupConfig(address.trim(), if (anonymous) "" else user, secret.orEmpty(), fileName) }
        catch (_: IllegalArgumentException) { problem(BackupNoticeCode.InvalidWebDav, null); return@synchronized }
        start(Action.Configure(next))
    }

    override fun requestUnbind() { confirmation(Action.Unbind, BackupConfirmationKind.UnbindWebDav, null) }
    override fun checkConnection() { start(Action.Ping) }
    override fun listRemote() { start(Action.ListFiles) }
    override fun selectRemoteFile(name: String) { start(Action.Select(name)) }
    override fun requestRemoteBackup() = synchronized(gate) {
        config?.let { confirmation(Action.Upload(it.fileName), BackupConfirmationKind.UploadRemote, it.fileName) }
        Unit
    }
    override fun requestRemoteRestore(name: String) { confirmation(Action.RestoreRemote(name, state.value.options), BackupConfirmationKind.RestoreRemote, name) }
    override fun requestRemoteDelete(name: String) { confirmation(Action.Delete(name), BackupConfirmationKind.DeleteRemote, name) }

    private fun confirmation(action: Action, kind: BackupConfirmationKind, name: String?) = synchronized(gate) {
        if (!available() || (kind != BackupConfirmationKind.RestoreLocal && config == null)) return@synchronized
        pending = action
        val options = when (action) {
            is Action.RestoreLocal -> action.options
            is Action.RestoreRemote -> action.options
            else -> state.value.options
        }
        publish { it.copy(confirmation = BackupConfirmation(++serial, kind, name, options), notice = null) }
    }

    override fun confirm(id: Long) = synchronized(gate) {
        if (closed || state.value.confirmation?.id != id) return@synchronized
        val action = pending ?: return@synchronized
        pending = null
        publish { it.copy(confirmation = null) }
        start(action)
    }

    override fun cancelConfirmation(id: Long) = synchronized(gate) {
        if (state.value.confirmation?.id == id) { pending = null; publish { it.copy(confirmation = null) } }
    }

    override fun cancel() = synchronized(gate) {
        if (closed) return@synchronized
        if (state.value.progress?.cancellable == true) active?.cancel()
    }

    override fun retry() = synchronized(gate) {
        if (retryAction == Action.Load) { start(Action.Load); return@synchronized }
        if (retryAction == Action.Refresh) { start(Action.Refresh); return@synchronized }
        if (!available()) return@synchronized
        when (val action = retryAction) {
            is Action.Pick -> pick(action.kind)
            is Action.Upload -> confirmation(action, BackupConfirmationKind.UploadRemote, action.name)
            is Action.RestoreRemote -> confirmation(action, BackupConfirmationKind.RestoreRemote, action.name)
            is Action.Delete -> confirmation(action, BackupConfirmationKind.DeleteRemote, action.name)
            Action.Unbind -> requestUnbind()
            null -> Unit
            else -> start(action)
        }
    }

    override fun dismissNotice() = synchronized(gate) {
        if (!refreshRequired) { retryAction = null; publish { it.copy(notice = null) } }
    }

    private fun available(): Boolean = !closed && owner.isActive && active == null && !state.value.busy && state.value.loaded && !refreshRequired

    private fun start(action: Action): Unit = synchronized(gate) {
        if (closed || !owner.isActive || active != null || state.value.busy || (!state.value.loaded && action != Action.Load)) return@synchronized
        if (refreshRequired && action != Action.Refresh) return@synchronized
        retryAction = null
        publish { it.copy(progress = initialProgress(action), notice = null) }
        val job = jobs.launch(start = CoroutineStart.LAZY) {
            try { execute(action) }
            catch (cancelled: CancellationException) {
                if (!closed) {
                    if (action == Action.Load) problem(BackupNoticeCode.LoadFailed, Action.Load)
                    else notice(BackupNoticeCode.Cancelled)
                }
            }
            catch (failure: PartialDocumentFailure) { if (!closed) problem(BackupNoticeCode.PartialDocument, Action.Cleanup(failure.reference)) }
            catch (failure: Exception) {
                if (!closed) {
                    if (action == Action.Ping) publish { it.copy(connection = BackupConnection.Failed) }
                    val retry = when (action) {
                        is Action.Export -> Action.Pick(BackupDocumentKind.Create)
                        is Action.Inspect, is Action.RestoreLocal -> Action.Pick(BackupDocumentKind.Open)
                        else -> action
                    }
                    problem(errorCode(action, failure), retry)
                }
            } finally { synchronized(gate) { active = null; publish { it.copy(progress = null) } } }
        }
        active = job
        job.start()
    }

    private suspend fun execute(action: Action) {
        when (action) {
            Action.Load -> {
                val options = withContext(Dispatchers.IO) { platform.loadOptions() }
                reloadConfig()
                publish { it.copy(loaded = true, options = options) }
            }
            is Action.Options -> {
                withContext(Dispatchers.IO) { platform.saveOptions(action.value) }
                publish { it.copy(options = action.value) }
            }
            is Action.Configure -> { repository.saveWebDav(action.value); reloadConfig(); notice(BackupNoticeCode.Configured) }
            Action.Unbind -> { repository.saveWebDav(null); reloadConfig(); notice(BackupNoticeCode.Unbound) }
            Action.Ping -> { remote().ping(); publish { it.copy(connection = BackupConnection.Connected) } }
            Action.ListFiles -> {
                val files = remote().list().map { BackupRemoteFile(it.name, it.bytes) }
                publish { it.copy(remoteFiles = files, listed = true, connection = BackupConnection.Connected) }
            }
            is Action.Select -> {
                val old = checkNotNull(repository.readWebDav())
                repository.saveWebDav(WebDavBackupConfig(old.url, old.user, old.password, action.name))
                reloadConfig(clearListing = false)
            }
            is Action.Export -> export(action.reference)
            is Action.Inspect -> {
                val name = withContext(Dispatchers.IO) { platform.documentName(action.reference) }
                synchronized(gate) {
                    if (closed) return@synchronized
                    pending = Action.RestoreLocal(action.reference, action.options)
                    publish { it.copy(confirmation = BackupConfirmation(++serial, BackupConfirmationKind.RestoreLocal, name, action.options)) }
                }
            }
            is Action.RestoreLocal -> {
                val authorized = withContext(Dispatchers.IO) { platform.vpnAuthorized() }
                withContext(Dispatchers.IO) { platform.readDocument(action.reference) { input ->
                    repository.restore(input, action.options.scope, action.options.strategy, authorized)
                } }
                refreshAfterRestore()
            }
            is Action.Upload -> temporary { file ->
                repository.createBackup(file)
                progress(BackupProgress.Uploading)
                remote().upload(file, action.name)
                publish { state -> state.copy(connection = BackupConnection.Connected,
                    remoteFiles = (state.remoteFiles.filterNot { it.name == action.name } + BackupRemoteFile(action.name, file.length())).sortedBy { it.name }) }
                notice(BackupNoticeCode.Uploaded)
            }
            is Action.RestoreRemote -> temporary { file ->
                remote().download(file, action.name)
                progress(BackupProgress.Restoring)
                val authorized = withContext(Dispatchers.IO) { platform.vpnAuthorized() }
                withContext(Dispatchers.IO) { file.inputStream().use { repository.restore(it, action.options.scope, action.options.strategy, authorized) } }
                refreshAfterRestore()
            }
            is Action.Delete -> {
                remote().delete(action.name)
                publish { it.copy(remoteFiles = it.remoteFiles.filterNot { file -> file.name == action.name }) }
                notice(BackupNoticeCode.Deleted)
            }
            Action.Refresh -> refreshAfterRestore()
            is Action.Cleanup -> {
                if (!withContext(Dispatchers.IO) { platform.deleteCreatedDocument(action.reference) }) throw PartialDocumentFailure(action.reference)
                synchronized(gate) {
                    publish { it.copy(document = BackupDocumentRequest(++serial, BackupDocumentKind.Create, "aerove-lite-backup.zip")) }
                }
            }
            is Action.Pick -> Unit
        }
    }

    private suspend fun remote(): BackupRemote = withContext(Dispatchers.IO) {
        remoteFactory(checkNotNull(repository.readWebDav()) { "WebDAV is not configured" })
    }

    private suspend fun reloadConfig(clearListing: Boolean = true) {
        val next = repository.readWebDav()
        synchronized(gate) {
            if (closed) return@synchronized
            config = next
            publish { it.copy(webDav = next?.let { item -> BackupWebDavUi(item.url, item.user, item.fileName,
                item.password.isNotEmpty(), item.user.isEmpty() && item.password.isEmpty()) },
                connection = if (clearListing) BackupConnection.NotChecked else it.connection,
                remoteFiles = if (clearListing) emptyList() else it.remoteFiles, listed = !clearListing && it.listed) }
        }
    }

    private suspend fun export(reference: String) {
        var completed = false
        try {
            temporary { file ->
                repository.createBackup(file)
                progress(BackupProgress.WritingDocument)
                withContext(Dispatchers.IO) { platform.writeDocument(reference, file) }
                completed = true
            }
            notice(BackupNoticeCode.Saved)
        } finally {
            if (!completed) withContext(NonCancellable + Dispatchers.IO) {
                if (!runCatching { platform.deleteCreatedDocument(reference) }.getOrDefault(false)) throw PartialDocumentFailure(reference)
            }
        }
    }

    private suspend fun refreshAfterRestore() {
        synchronized(gate) { refreshRequired = true }
        progress(BackupProgress.Refreshing)
        try {
            reloadConfig()
            currentCoroutineContext().ensureActive()
            onRestored()
            synchronized(gate) { refreshRequired = false }
            notice(BackupNoticeCode.Restored)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { problem(BackupNoticeCode.RefreshAfterRestoreFailed, Action.Refresh) }
    }

    private suspend fun <T> temporary(action: suspend (File) -> T): T {
        val file = withContext(Dispatchers.IO) {
            check(platform.cacheDirectory.isDirectory || platform.cacheDirectory.mkdirs())
            File(platform.cacheDirectory, "backup-${UUID.randomUUID()}.zip")
        }
        try { return action(file) } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
    }

    private fun publish(change: (BackupUiState) -> BackupUiState) = synchronized(gate) {
        if (!closed) mutableState.update(change)
    }

    private fun progress(value: BackupProgress) { if (!closed) publish { it.copy(progress = value) } }
    private fun notice(code: BackupNoticeCode) = synchronized(gate) {
        if (!closed) { retryAction = null; publish { it.copy(notice = BackupNotice(++serial, code, false, false)) } }
    }
    private fun problem(code: BackupNoticeCode, retry: Action?) = synchronized(gate) {
        if (!closed) { retryAction = retry; publish { it.copy(notice = BackupNotice(++serial, code, true, retry != null)) } }
    }

    override fun close() = synchronized(gate) {
        if (closed) return@synchronized
        closed = true
        pending = null
        retryAction = null
        refreshRequired = false
        config = null
        owner.cancel()
        platform.close()
        mutableState.update { it.copy(loaded = false, progress = null, document = null, confirmation = null,
            webDav = null, remoteFiles = emptyList(), listed = false, notice = null) }
    }

    private class PartialDocumentFailure(val reference: String) : IOException()

    private fun initialProgress(action: Action): BackupProgress = when (action) {
        Action.Load -> BackupProgress.Loading
        is Action.Options -> BackupProgress.SavingOptions
        is Action.Configure, Action.Unbind, is Action.Select -> BackupProgress.SavingWebDav
        Action.Ping -> BackupProgress.CheckingConnection
        Action.ListFiles -> BackupProgress.Listing
        is Action.Export, is Action.Upload -> BackupProgress.Packaging
        is Action.Inspect -> BackupProgress.ReadingDocument
        is Action.RestoreLocal -> BackupProgress.Restoring
        is Action.RestoreRemote -> BackupProgress.Downloading
        is Action.Delete, is Action.Cleanup -> BackupProgress.Deleting
        Action.Refresh -> BackupProgress.Refreshing
        is Action.Pick -> BackupProgress.Loading
    }

    private fun errorCode(action: Action, error: Exception): BackupNoticeCode = when {
        error is SecurityException -> BackupNoticeCode.DocumentPermission
        action == Action.Load -> BackupNoticeCode.LoadFailed
        action is Action.Options -> BackupNoticeCode.OptionsFailed
        (action is Action.Configure || action is Action.Select) && error is IllegalArgumentException -> BackupNoticeCode.InvalidWebDav
        action is Action.Configure || action is Action.Select || action == Action.Unbind -> BackupNoticeCode.WebDavSaveFailed
        action is Action.Export -> BackupNoticeCode.WriteFailed
        error.message == "Boot auto-start requires an existing VPN authorization" -> BackupNoticeCode.VpnAuthorizationRequired
        error is WebDavBackupException -> when (error.statusCode) {
            401, 403 -> BackupNoticeCode.WebDavCredentials
            404 -> BackupNoticeCode.RemoteMissing
            409, 412 -> BackupNoticeCode.RemoteConflict
            else -> BackupNoticeCode.RemoteFailed
        }
        action is Action.RestoreLocal || (action is Action.RestoreRemote && state.value.progress == BackupProgress.Restoring) -> BackupNoticeCode.InvalidArchive
        action is Action.Inspect -> BackupNoticeCode.ReadFailed
        else -> BackupNoticeCode.RemoteFailed
    }
}

private class HttpBackupRemote(private val client: WebDavBackupClient) : BackupRemote {
    override suspend fun ping() = client.ping()
    override suspend fun list() = client.list()
    override suspend fun upload(source: File, name: String) = client.upload(source, name, overwrite = true)
    override suspend fun download(destination: File, name: String) { client.download(destination, name) }
    override suspend fun delete(name: String) = client.delete(name)
}

private class AndroidBackupPlatform(private val application: LiteApplication) : BackupPlatform {
    override val cacheDirectory = File(application.cacheDir, "backup-feature")
    private val network = lazy { OkHttpClient() }
    val http: OkHttpClient get() = network.value
    private val resolver get() = application.contentResolver
    private val options get() = application.getSharedPreferences("backup-operation-options", 0)

    override suspend fun loadOptions(): BackupOptions = BackupOptions(
        runCatching { BackupRestoreScope.valueOf(options.getString("scope", null).orEmpty()) }.getOrDefault(BackupRestoreScope.All),
        runCatching { BackupRestoreStrategy.valueOf(options.getString("strategy", null).orEmpty()) }.getOrDefault(BackupRestoreStrategy.Compatible),
    )

    override suspend fun saveOptions(options: BackupOptions) {
        check(this.options.edit().putString("scope", options.scope.name).putString("strategy", options.strategy.name).commit()) { "Cannot save backup options" }
    }

    override suspend fun <T> readDocument(reference: String, action: suspend (InputStream) -> T): T = grant(reference, Intent.FLAG_GRANT_READ_URI_PERMISSION) { uri ->
        checkNotNull(resolver.openInputStream(uri)) { "Document cannot be opened" }.use { action(it) }
    }

    override suspend fun writeDocument(reference: String, source: File) = grant(reference, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) { uri ->
        checkNotNull(resolver.openOutputStream(uri, "wt")) { "Document cannot be opened" }.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    output.write(buffer, 0, size)
                    bytes += size
                }
                check(bytes == source.length()) { "Document write was incomplete" }
                output.flush()
            }
        }
    }

    override suspend fun deleteCreatedDocument(reference: String): Boolean {
        val uri = documentUri(reference)
        return DocumentsContract.isDocumentUri(application, uri) && DocumentsContract.deleteDocument(resolver, uri)
    }

    override suspend fun documentName(reference: String): String? = grant(reference, Intent.FLAG_GRANT_READ_URI_PERMISSION) { uri ->
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.filterNot(Char::isISOControl)?.take(255) else null
        }
    }

    override suspend fun vpnAuthorized(): Boolean = VpnService.prepare(application) == null

    private suspend fun <T> grant(reference: String, flags: Int, action: suspend (Uri) -> T): T {
        val uri = documentUri(reference)
        val prior = resolver.persistedUriPermissions.filter { it.uri == uri }.fold(0) { bits, permission -> bits or
            (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) }
        val requested = flags and prior.inv()
        var acquired = 0
        try {
            if (requested != 0) try { resolver.takePersistableUriPermission(uri, requested); acquired = requested } catch (_: SecurityException) { }
            return action(uri)
        } finally { if (acquired != 0) runCatching { resolver.releasePersistableUriPermission(uri, acquired) } }
    }

    private fun documentUri(reference: String): Uri = Uri.parse(reference).also {
        require(it.scheme == ContentResolver.SCHEME_CONTENT && !it.authority.isNullOrEmpty()) { "Document URI required" }
    }

    override fun close() {
        if (network.isInitialized()) {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}

package dev.aerove.lite

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.vpn.LiteVpnService
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class LiteController(private val application: LiteApplication) : ViewModel(), LiteActions {
    private val mutableState = MutableStateFlow(LiteState())
    val state = mutableState.asStateFlow()
    private val visible = MutableStateFlow(false)
    private var api: CoreApiClient? = null
    var filePicker: (() -> Unit)? = null

    init {
        perform("load") {
            val settings = application.settings.read()
            mutableState.update { it.copy(settings = settings) }
            reloadProfiles()
        }
        viewModelScope.launch {
            application.vpn.collect { snapshot ->
                mutableState.update { it.copy(phase = snapshot.phase, message = snapshot.error ?: it.message) }
            }
        }
        viewModelScope.launch {
            combine(visible, application.vpn) { foreground, snapshot -> if (foreground) snapshot.endpoint else null }
                .collectLatest { endpoint ->
                    api = null
                    mutableState.update { it.copy(groups = emptyList(), connections = emptyList(), traffic = Traffic()) }
                    if (endpoint == null) return@collectLatest
                    val client = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret))
                    api = client
                    try {
                        supervisorScope {
                            launch {
                                client.logs().flowOn(Dispatchers.Default).retryWhen { _, attempt -> delay(1000); attempt < 3 }
                                    .catch { report(it) }.collect { line ->
                                        mutableState.update { it.copy(logs = (it.logs + line).takeLast(2000)) }
                                    }
                            }
                            launch {
                                client.traffic().flowOn(Dispatchers.Default).retryWhen { _, attempt -> delay(1000); attempt < 3 }
                                    .catch { report(it) }.collect { traffic ->
                                        mutableState.update { it.copy(traffic = traffic) }
                                    }
                            }
                            launch {
                                client.connectionUpdates().flowOn(Dispatchers.Default).retryWhen { _, attempt -> delay(1000); attempt < 3 }
                                    .catch { report(it) }.collect { connections ->
                                        mutableState.update { it.copy(connections = connections) }
                                    }
                            }
                            launch {
                                while (true) {
                                    try {
                                        val groups = withContext(Dispatchers.Default) { client.groups() }
                                        mutableState.update { it.copy(groups = groups) }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: IOException) {
                                        report(failure)
                                    }
                                    delay(5000)
                                }
                            }
                        }
                    } finally {
                        if (api === client) api = null
                        client.close()
                    }
                }
        }
    }

    fun setVisible(value: Boolean) { visible.value = value }

    override fun connect() {
        if (VpnService.prepare(application) != null) {
            showMessage(application.getString(R.string.error_vpn_permission))
            return
        }
        if (state.value.selectedProfile == null) {
            showMessage(application.getString(R.string.error_profile_required))
            return
        }
        ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
            .setAction(LiteVpnService.ACTION_START))
    }

    override fun disconnect() {
        application.startService(Intent(application, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
    }

    override fun importFile() { filePicker?.invoke() }

    fun importFile(uri: Uri) = perform("import") {
        val name = withContext(Dispatchers.IO) {
            application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: application.getString(R.string.default_profile_name)
        }
        val input = withContext(Dispatchers.IO) {
            checkNotNull(application.contentResolver.openInputStream(uri)) { application.getString(R.string.error_read_file) }
        }
        application.profiles.importFile(name, input)
        reloadProfiles()
    }

    override fun addSubscription(name: String, url: String) = perform("import") {
        application.profiles.addSubscription(name, url)
        reloadProfiles()
    }

    override fun updateProfile(id: String) = perform("profile:$id") {
        application.profiles.update(id)
        reloadProfiles()
        reconnectSelected(id)
    }

    override fun selectProfile(id: String) = perform("profile:$id") {
        application.profiles.select(id)
        reloadProfiles()
        reconnectSelected(id)
    }

    override fun deleteProfile(id: String) = perform("profile:$id") {
        if (state.value.selectedProfile == id) disconnect()
        application.profiles.delete(id)
        reloadProfiles()
    }

    override fun selectProxy(group: String, proxy: String) = perform("group:$group") {
        val client = requireApi()
        client.select(group, proxy)
        val groups = withContext(Dispatchers.Default) { client.groups() }
        mutableState.update { it.copy(groups = groups) }
    }

    override fun testGroup(group: String) = perform("group:$group") {
        val client = requireApi()
        val nodes = state.value.groups.first { it.name == group }.nodes
        val limit = Semaphore(4)
        coroutineScope {
            nodes.map { node -> async {
                limit.withPermit {
                    val measured = try {
                        client.delay(node.name)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IOException) {
                        -1
                    }
                    mutableState.update { state -> state.copy(groups = state.groups.map { current ->
                        if (current.name != group) current else current.copy(nodes = current.nodes.map {
                            if (it.name == node.name) it.copy(delayMillis = measured) else it
                        })
                    }) }
                }
            } }.awaitAll()
        }
    }

    override fun closeConnection(id: String) = perform("connection:$id") {
        val client = requireApi()
        client.closeConnection(id)
        val connections = withContext(Dispatchers.Default) { client.connections() }
        mutableState.update { it.copy(connections = connections) }
    }

    override fun closeAllConnections() = perform("connections") {
        val client = requireApi()
        client.closeAllConnections()
        val connections = withContext(Dispatchers.Default) { client.connections() }
        mutableState.update { it.copy(connections = connections) }
    }

    override fun changeSettings(settings: LiteSettings) = perform("settings") {
        val old = state.value.settings
        application.settings.save(settings, VpnService.prepare(application) == null)
        mutableState.update { it.copy(settings = settings) }
        if (state.value.phase == VpnPhase.Running || state.value.phase == VpnPhase.Starting) {
            val restart = old.ipv6 != settings.ipv6 || old.dnsServers != settings.dnsServers ||
                old.appFilterMode != settings.appFilterMode || old.packages != settings.packages || old.mode != settings.mode
            if (restart) connect()
        }
    }

    override fun loadInstalledApps() = perform("applications") {
        val apps = withContext(Dispatchers.IO) {
            application.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .filterNot { it.packageName == application.packageName }
                .map { InstalledApp(it.packageName, application.packageManager.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
        mutableState.update { it.copy(installedApps = apps) }
    }

    override fun clearLogs() { mutableState.update { it.copy(logs = emptyList()) } }
    override fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }

    private fun requireApi(): CoreApiClient = checkNotNull(api) { application.getString(R.string.error_api_unavailable) }

    private suspend fun reloadProfiles() {
        val snapshot = application.profiles.snapshot()
        mutableState.update { it.copy(profiles = snapshot.profiles, selectedProfile = snapshot.selectedId) }
    }

    private fun reconnectSelected(id: String) {
        if (state.value.selectedProfile == id && state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) connect()
    }

    private fun perform(key: String, action: suspend () -> Unit) {
        if (key in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
            } finally {
                mutableState.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    private fun report(failure: Throwable) { showMessage(failure.message ?: failure.javaClass.simpleName) }
}

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
import dev.aerove.lite.telemetry.CoreTelemetry
import dev.aerove.lite.telemetry.GroupRefresh
import dev.aerove.lite.telemetry.OutboundProbe
import dev.aerove.lite.telemetry.TelemetrySession
import dev.aerove.lite.telemetry.TelemetryVisibility
import dev.aerove.lite.telemetry.sessionBindings
import dev.aerove.lite.telemetry.routePicks
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.LiteVpnService
import dev.aerove.lite.platform.LocalNetworkMonitor
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class LiteController(private val application: LiteApplication) : ViewModel(), LiteActions {
    private val mutableState = MutableStateFlow(LiteState())
    val state = mutableState.asStateFlow()
    private val visible = MutableStateFlow(TelemetryVisibility())
    private val connectionTelemetryActive = MutableStateFlow(false)
    private var api: CoreApiClient? = null
    private var currentSession: TelemetrySession? = null
    private data class OutboundKey(val endpoint: CoreEndpoint, val profileId: String, val mode: ProxyMode,
        val configuration: Long)
    private var outboundConfiguration = 0L
    private var outboundBlockedEndpoint: CoreEndpoint? = null
    private var outboundGroupEndpoint: CoreEndpoint? = null
    private val outbound = OutboundProbe<OutboundKey>(viewModelScope, { key ->
        CoreApiClient(ControlEndpoint(key.endpoint.port, key.endpoint.secret)).use { it.outboundProbe() }
    }, { it == outboundKey() })
    var filePicker: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            outbound.state.collect { snapshot ->
                mutableState.update { it.copy(outboundIdentity = snapshot.identity,
                    outboundLoading = snapshot.loading, outboundError = snapshot.error) }
            }
        }
        perform("load") {
            val settings = application.settings.read()
            mutableState.update { it.copy(settings = settings) }
            reloadProfiles()
        }
        viewModelScope.launch {
            application.vpn.collect { snapshot ->
                currentSession?.takeUnless { it.current }?.close()
                mutableState.update { it.copy(phase = snapshot.phase, message = snapshot.error ?: it.message) }
                if (outboundGroupEndpoint !== snapshot.endpoint) outboundGroupEndpoint = null
                if (snapshot.endpoint != null && outboundBlockedEndpoint !== snapshot.endpoint) outboundBlockedEndpoint = null
                bindOutbound()
            }
        }
        viewModelScope.launch {
            visible.collectLatest { foreground ->
                if (foreground.active) LocalNetworkMonitor(application).addresses().collect { address ->
                    mutableState.update { it.copy(localAddress = address) }
                }
            }
        }
        viewModelScope.launch {
            combine(visible, application.vpn) { foreground, snapshot ->
                if (foreground.active && snapshot.phase == VpnPhase.Running) snapshot.runningSinceNanos else 0L
            }.distinctUntilChanged().collectLatest { started ->
                if (started == 0L) {
                    if (application.vpn.value.phase != VpnPhase.Running) mutableState.update { it.copy(uptimeMillis = 0) }
                } else while (true) {
                    val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                    mutableState.update { it.copy(uptimeMillis = elapsed) }
                    delay(1000)
                }
            }
        }
        viewModelScope.launch {
            sessionBindings(visible, application.vpn.map { it.endpoint })
                .collectLatest { binding ->
                    api = null
                    mutableState.update { it.copy(groups = emptyList(), connections = emptyList(), traffic = Traffic()) }
                    outboundGroupEndpoint = null
                    outbound.awaitingGroups()
                    if (binding == null) return@collectLatest
                    val (endpoint, generation) = binding
                    val client = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret))
                    api = client
                    try {
                        supervisorScope {
                            val session = TelemetrySession(this) {
                                visible.value.active && visible.value.generation == generation &&
                                    api === client && application.vpn.value.endpoint == endpoint
                            }
                            currentSession = session
                            try {
                                val groups = GroupRefresh(session, client::groups, { failure ->
                                    outbound.groupsFailed(failure)
                                    report(failure)
                                }, { snapshot ->
                                    mutableState.update { it.copy(groups = snapshot) }
                                    outboundGroupEndpoint = endpoint
                                    val picks = snapshot.routePicks()
                                    bindOutbound(picks)
                                    outbound.synchronize(picks)
                                }, { it.isEmpty() })
                                session.observe("logs", mutableState.map { it.settings.openLogs }, {
                                    client.logs().flowOn(Dispatchers.Default)
                                }, ::report) { line ->
                                    if (state.value.settings.openLogs) {
                                        mutableState.update { it.copy(logs = (it.logs + line).takeLast(2000)) }
                                    }
                                }
                                session.observe("telemetry", flowOf(true), client::telemetry, ::report) { event ->
                                    when (event) {
                                        is CoreTelemetry.TrafficSample -> mutableState.update { it.copy(traffic = event.value) }
                                        is CoreTelemetry.GroupsChanged -> if (groups.changed(event.revision)) {
                                            outboundGroupEndpoint = null
                                            outbound.awaitingGroups()
                                        }
                                    }
                                }
                                session.observe("connections", connectionTelemetryActive, client::connectionUpdates, ::report) { connections ->
                                    if (connectionTelemetryActive.value) {
                                        mutableState.update { it.copy(connections = connections) }
                                    }
                                }
                                awaitCancellation()
                            } finally {
                                session.close()
                                if (currentSession === session) currentSession = null
                            }
                        }
                    } finally {
                        if (api === client) api = null
                        client.close()
                    }
                }
        }
    }

    fun setVisible(value: Boolean) {
        visible.update { it.changed(value) }
        outbound.setActive(value)
        if (!value) currentSession?.close()
    }

    override fun setConnectionTelemetryActive(active: Boolean) { connectionTelemetryActive.value = active }

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
        if (state.value.selectedProfile == id) invalidateOutboundConfiguration()
        reloadProfiles()
        reconnectSelected(id)
    }

    override fun selectProfile(id: String) = perform("profile:$id") {
        application.profiles.select(id)
        invalidateOutboundConfiguration()
        reloadProfiles()
        reconnectSelected(id)
    }

    override fun deleteProfile(id: String) = perform("profile:$id") {
        if (state.value.selectedProfile == id) {
            invalidateOutboundConfiguration()
            disconnect()
        }
        application.profiles.delete(id)
        reloadProfiles()
    }

    override fun selectProxy(group: String, proxy: String) = perform("group:$group") {
        val client = requireApi()
        val selection = outbound.beginSelection()
        var succeeded = false
        try {
            client.select(group, proxy)
            succeeded = true
        } finally {
            outbound.endSelection(selection, group, proxy, succeeded)
        }
    }

    override fun testGroup(group: String) {
        val session = currentSession
        val client = api
        if (session == null || client == null || !session.current) {
            showMessage(application.getString(R.string.error_api_unavailable))
            return
        }
        val nodes = state.value.groups.firstOrNull { it.name == group }?.nodes ?: return
        performInSession("group:$group", session) {
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
                        session.accept {
                            mutableState.update { state -> state.copy(groups = state.groups.map { current ->
                                if (current.name != group) current else current.copy(nodes = current.nodes.map {
                                    if (it.name == node.name) it.copy(delayMillis = measured) else it
                                })
                            }) }
                        }
                    }
                } }.awaitAll()
            }
        }
    }

    override fun refreshOutbound() { outbound.refresh() }

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
        val saved = application.settings.save(settings, VpnService.prepare(application) == null)
        val restart = old.ipv6 != saved.ipv6 || old.overrideDns != saved.overrideDns ||
            old.dnsServers != saved.dnsServers || old.appFilterMode != saved.appFilterMode ||
            old.packages != saved.packages || old.mode != saved.mode
        if (restart) invalidateOutboundConfiguration()
        mutableState.update { it.copy(settings = saved) }
        if (state.value.phase == VpnPhase.Running || state.value.phase == VpnPhase.Starting) {
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
        bindOutbound()
    }

    private fun outboundKey(): OutboundKey? {
        val endpoint = application.vpn.value.endpoint ?: return null
        if (outboundBlockedEndpoint === endpoint) return null
        return OutboundKey(endpoint, state.value.selectedProfile ?: return null, state.value.settings.mode,
            outboundConfiguration)
    }

    private fun bindOutbound(snapshotPicks: Map<String, String>? = null) {
        val key = outboundKey()
        val picks = if (key != null && outboundGroupEndpoint === key.endpoint && currentSession?.current == true) {
            snapshotPicks ?: state.value.groups.routePicks()
        } else null
        outbound.bind(key, picks)
    }

    private fun invalidateOutboundConfiguration() {
        outboundConfiguration++
        outboundBlockedEndpoint = application.vpn.value.endpoint
        outbound.bind(null)
    }

    private fun reconnectSelected(id: String) {
        if (state.value.selectedProfile == id && state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) connect()
    }

    private fun performInSession(key: String, session: TelemetrySession, action: suspend CoroutineScope.() -> Unit) {
        if (!session.current || key in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + key) }
        val job = session.launch(key, { mutableState.update { it.copy(busy = it.busy - key) } }) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                session.accept { report(failure) }
            }
        }
        if (job == null) mutableState.update { it.copy(busy = it.busy - key) }
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

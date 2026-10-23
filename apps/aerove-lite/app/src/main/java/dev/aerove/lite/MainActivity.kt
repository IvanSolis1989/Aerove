package dev.aerove.lite

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import dev.aerove.lite.platform.LauncherAction
import dev.aerove.lite.platform.LauncherActions
import dev.aerove.lite.platform.ExternalProfileIntent
import dev.aerove.lite.platform.QuickActionActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aerove.lite.ui.LiteApp
import dev.aerove.lite.ui.LocalLiteFeatures
import dev.aerove.lite.ui.LocalClientNotices

class MainActivity : ComponentActivity() {
    private var ownedFilePicker: (() -> Unit)? = null
    private var pendingStartToken: Long? = null
    private var pendingLauncher: Pair<String, LauncherAction>? = null
    private var lastHidden: Boolean? = null
    private var ownedLocationRequest: (() -> Unit)? = null
    private var ownedBatteryRequest: (() -> Unit)? = null
    private var ownedAppsAuthorization: (() -> Unit)? = null
    private var ownedExit: (() -> Unit)? = null
    private val controller by viewModels<LiteController> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LiteController(application as LiteApplication) as T
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        consumeVpnAuthorization(it.resultCode == RESULT_OK)
        launchNextVpnAuthorization()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val token = pendingStartToken
        if (token != null && controller.currentStartRequest(token)) {
            if (granted || controller.state.value.settings.network.vpnEnabled) completeConnect()
            else { pendingStartToken = null; controller.showMessage(getString(R.string.integration_notifications_required)) }
        } else pendingStartToken = null
    }
    private val location = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller.refreshPlatformPermissions() }
    private val backgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshPlatformPermissions() }
    private val installedApplications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshPlatformPermissions() }
    private val platformSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller.refreshPlatformPermissions() }
    private val file = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::importFile)
    }
    private val actions by lazy {
        object : LiteActions by controller {
            override fun connect() {
                if ((application as LiteApplication).maintenanceActive) return
                pendingStartToken = controller.beginStartRequest()
                if (!controller.state.value.settings.network.vpnEnabled) { requestNotificationsAndConnect(); return }
                controller.offerVpnAuthorization(checkNotNull(pendingStartToken))
                launchNextVpnAuthorization()
            }
            override fun changeSettings(settings: LiteSettings) {
                val needsGrant = settings.backgroundPolicy.autoRun && settings.network.vpnEnabled &&
                    !controller.state.value.settings.backgroundPolicy.autoRun
                val intent = if (needsGrant) VpnService.prepare(this@MainActivity) else null
                if (intent == null) { controller.cancelSettingsAuthorization(); controller.changeSettings(settings) }
                else {
                    pendingStartToken = null
                    controller.offerVpnAuthorization(controller.beginStartRequest(), settings)
                    launchNextVpnAuthorization()
                }
            }
        }
    }
    private val timeChanges: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIME_CHANGED || intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
                auroraLifecycle.onTimeChanged()
            }
        }
    }
    private val auroraLifecycle: ActivityAuroraLifecycle by lazy {
        ActivityAuroraLifecycle(
            initialize = { controller.initializeAurora(AuroraLifecycleState.Inactive) },
            transition = controller::setAuroraLifecycle,
            visible = controller::setVisible,
            registerTimeReceiver = {
                ContextCompat.registerReceiver(this, timeChanges, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            },
            unregisterTimeReceiver = { unregisterReceiver(timeChanges) },
            checkTime = controller::checkAuroraTime,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controller.state.value.settings.backgroundPolicy.minimizeOnExit) moveTaskToBack(true)
                else controller.exitApplication { finishAndRemoveTask() }
            }
        })
        enableEdgeToEdge()
        ownedFilePicker = { file.launch(arrayOf("application/yaml", "application/x-yaml", "application/zip", "application/x-zip-compressed", "text/*", "application/octet-stream")) }
        controller.filePicker = ownedFilePicker
        ownedLocationRequest = {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                    location.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                    backgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else -> platformSettings.launch(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        ownedBatteryRequest = { platformSettings.launch(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        ownedAppsAuthorization = {
            val name = dev.aerove.lite.appaccess.AndroidApplicationLoader.INSTALLED_APPS_PERMISSION
            if (runCatching { packageManager.getPermissionInfo(name, 0) }.isSuccess)
                installedApplications.launch(name)
            else platformSettings.launch(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        controller.onDemandLocationRequest = ownedLocationRequest
        controller.batteryExemptionRequest = ownedBatteryRequest
        controller.applicationsAuthorization = ownedAppsAuthorization
        ownedExit = { finishAndRemoveTask() }
        controller.exitAfterStop = ownedExit
        pendingStartToken = savedInstanceState?.getLong("pending-start", 0L)?.takeIf { it != 0L }
        savedInstanceState?.getString("pending-launcher-action")?.let { action ->
            LauncherActions.parseAction(action)?.let { pendingLauncher = savedInstanceState.getString("pending-launcher-id").orEmpty() to it }
        }
        savedInstanceState?.getString("pending-external-profile")?.takeIf { controller.pendingExternalProfile() == null }?.let(controller::offerExternalProfile)
        if (savedInstanceState == null) handleIntent(intent)
        auroraLifecycle.onCreate()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                controller.state.collect { state ->
                    if (state.initialized && state.settings.acceptedDisclaimerVersion >= 1) {
                        if (lastHidden != state.settings.backgroundPolicy.hidden) {
                            getSystemService(android.app.ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo.taskId == taskId }
                                ?.setExcludeFromRecents(state.settings.backgroundPolicy.hidden)
                            lastHidden = state.settings.backgroundPolicy.hidden
                        }
                        val command = pendingLauncher.also { pendingLauncher = null }
                        if (command != null && controller.acceptPlatformRequest(command.first)) {
                            controller.consumeAutoRun()
                            when (command.second) {
                                LauncherAction.START -> actions.connect()
                                LauncherAction.STOP -> controller.disconnect()
                                LauncherAction.TOGGLE -> if (state.phase == VpnPhase.Running || state.phase == VpnPhase.Starting) controller.disconnect() else actions.connect()
                            }
                        } else if (controller.consumeAutoRun()) actions.connect()
                    }
                }
            }
        }
        LauncherActions.publishDynamicShortcuts(this, mapOf(
            LauncherAction.START to (getString(R.string.platform_action_start) to getString(R.string.platform_action_start_long)),
            LauncherAction.STOP to (getString(R.string.platform_action_stop) to getString(R.string.platform_action_stop_long)),
            LauncherAction.TOGGLE to (getString(R.string.platform_action_toggle) to getString(R.string.platform_action_toggle_long))))
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val features: () -> LiteFeatureHost = remember(controller) { { controller } }
            CompositionLocalProvider(LocalLiteFeatures provides features, LocalClientNotices provides controller.notices) { LiteApp(state, actions) }
        }
    }

    override fun onResume() {
        super.onResume()
        auroraLifecycle.onResume()
        controller.refreshPlatformPermissions()
    }

    override fun onPause() {
        auroraLifecycle.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        auroraLifecycle.onStop(isChangingConfigurations)
    }

    override fun onDestroy() {
        auroraLifecycle.onDestroy(isChangingConfigurations)
        if (controller.filePicker === ownedFilePicker) controller.filePicker = null
        ownedFilePicker = null
        if (controller.onDemandLocationRequest === ownedLocationRequest) controller.onDemandLocationRequest = null
        if (controller.batteryExemptionRequest === ownedBatteryRequest) controller.batteryExemptionRequest = null
        if (controller.applicationsAuthorization === ownedAppsAuthorization) controller.applicationsAuthorization = null
        if (controller.exitAfterStop === ownedExit) controller.exitAfterStop = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingStartToken?.let { outState.putLong("pending-start", it) }
        pendingLauncher?.let { (id, action) -> outState.putString("pending-launcher-id", id); outState.putString("pending-launcher-action", action.name) }
        controller.pendingExternalProfile()?.let { outState.putString("pending-external-profile", it) }
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(source: Intent?) {
        if (source?.action == QuickActionActivity.ACTION_QUICK_ACTION) {
            val id = source.getStringExtra(LauncherActions.EXTRA_REQUEST_ID)
            val action = LauncherActions.parseAction(source.getStringExtra(LauncherActions.EXTRA_ACTION))
            if (!id.isNullOrBlank() && action != null) {
                val running = (application as LiteApplication).vpn.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)
                if (action == LauncherAction.STOP || action == LauncherAction.TOGGLE && running) {
                    pendingLauncher = null
                    if (controller.acceptPlatformRequest(id)) controller.stopFromPlatform()
                } else { pendingLauncher = id to action; dispatchPendingLauncher() }
            }
        } else if (source?.action == Intent.ACTION_VIEW) {
            when (val parsed = ExternalProfileIntent.parse(source.dataString)) {
                is ExternalProfileIntent.Valid -> controller.offerExternalProfile(parsed.link)
                is ExternalProfileIntent.Invalid -> controller.showMessage(getString(R.string.platform_external_link_invalid))
            }
        }
    }

    private fun dispatchPendingLauncher() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !controller.state.value.initialized || controller.state.value.settings.acceptedDisclaimerVersion < 1) return
        val command = pendingLauncher.also { pendingLauncher = null } ?: return
        if (!controller.acceptPlatformRequest(command.first)) return
        controller.consumeAutoRun()
        when (command.second) {
            LauncherAction.START -> actions.connect()
            LauncherAction.STOP -> controller.disconnect()
            LauncherAction.TOGGLE -> if (controller.state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) controller.disconnect() else actions.connect()
        }
    }

    private fun launchNextVpnAuthorization() {
        while (true) {
            controller.nextVpnAuthorization() ?: return
            val intent = VpnService.prepare(this)
            if (intent != null) { permission.launch(intent); return }
            consumeVpnAuthorization(true)
        }
    }

    private fun consumeVpnAuthorization(granted: Boolean) {
        val result = controller.completeVpnAuthorization(granted) ?: return
        if (!result.granted) {
            pendingStartToken = null
            controller.showMessage(getString(R.string.error_vpn_denied))
        } else if (result.request.settings != null) controller.applyAuthorizedSettings(result.request)
        else {
            pendingStartToken = result.request.token
            requestNotificationsAndConnect()
        }
    }

    private fun requestNotificationsAndConnect() {
        val token = pendingStartToken ?: return
        if (!controller.currentStartRequest(token)) { pendingStartToken = null; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else completeConnect()
    }

    private fun completeConnect() {
        val token = pendingStartToken.also { pendingStartToken = null } ?: return
        if (controller.currentStartRequest(token)) controller.connect()
    }
}

internal class ActivityAuroraLifecycle(
    private val initialize: () -> Unit,
    private val transition: (AuroraLifecycleState) -> Unit,
    private val visible: (Boolean) -> Unit,
    private val registerTimeReceiver: () -> Unit,
    private val unregisterTimeReceiver: () -> Unit,
    private val checkTime: () -> Unit,
) {
    private var registered = false
    private var destroyed = false

    fun onCreate() {
        if (destroyed || registered) return
        initialize()
        registerTimeReceiver()
        registered = true
    }

    fun onResume() {
        if (destroyed) return
        transition(AuroraLifecycleState.Resumed)
        visible(true)
    }

    fun onPause() {
        if (destroyed) return
        visible(false)
        transition(AuroraLifecycleState.Inactive)
    }

    fun onStop(changingConfigurations: Boolean) {
        if (!destroyed && !changingConfigurations) transition(AuroraLifecycleState.Paused)
    }

    fun onDestroy(changingConfigurations: Boolean) {
        if (destroyed) return
        destroyed = true
        if (!changingConfigurations) transition(AuroraLifecycleState.Detached)
        if (registered) {
            registered = false
            unregisterTimeReceiver()
        }
    }

    fun onTimeChanged() {
        if (registered && !destroyed) checkTime()
    }
}

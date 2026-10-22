package dev.aerove.lite

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aerove.lite.ui.LiteApp
import dev.aerove.lite.ui.LocalLiteFeatures

class MainActivity : ComponentActivity() {
    private var ownedFilePicker: (() -> Unit)? = null
    private val controller by viewModels<LiteController> {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LiteController(application as LiteApplication) as T
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) requestNotificationsAndConnect()
        else controller.showMessage(getString(R.string.error_vpn_denied))
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        controller.connect()
    }
    private val file = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(controller::importFile)
    }
    private val actions by lazy {
        object : LiteActions by controller {
            override fun connect() {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) permission.launch(intent) else requestNotificationsAndConnect()
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
        enableEdgeToEdge()
        ownedFilePicker = { file.launch(arrayOf("application/yaml", "application/x-yaml", "application/zip", "application/x-zip-compressed", "text/*", "application/octet-stream")) }
        controller.filePicker = ownedFilePicker
        auroraLifecycle.onCreate()
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val features: () -> LiteFeatureHost = remember(controller) { { controller } }
            CompositionLocalProvider(LocalLiteFeatures provides features) { LiteApp(state, actions) }
        }
    }

    override fun onResume() {
        super.onResume()
        auroraLifecycle.onResume()
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
        super.onDestroy()
    }

    private fun requestNotificationsAndConnect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else controller.connect()
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

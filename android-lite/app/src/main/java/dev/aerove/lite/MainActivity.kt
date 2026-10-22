package dev.aerove.lite

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aerove.lite.ui.LiteApp

class MainActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller.filePicker = { file.launch(arrayOf("application/yaml", "application/x-yaml", "text/*", "application/octet-stream")) }
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            LiteApp(state, actions)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.setVisible(true)
    }

    override fun onStop() {
        controller.setVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        controller.filePicker = null
        super.onDestroy()
    }

    private fun requestNotificationsAndConnect() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else controller.connect()
    }
}

package dev.aerove.lite.platform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.aerove.lite.MainActivity

class QuickActionActivity : Activity() {
    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LauncherActions.shouldForwardOnCreate(savedInstanceState?.getBoolean(STATE_FORWARDED) == true)) {
            forward(intent)
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FORWARDED, forwarded)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward(intent)
    }

    private fun forward(source: Intent?) {
        val action = source?.let {
            LauncherActions.parseIntentAction(packageName, it.action, it.getStringExtra(LauncherActions.EXTRA_ACTION))
        } ?: return
        val requestId = LauncherActions.requestIdForLaunch(source.getStringExtra(LauncherActions.EXTRA_REQUEST_ID)) ?: return
        forwarded = true
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(ACTION_QUICK_ACTION)
            .putExtra(LauncherActions.EXTRA_ACTION, action.name)
            .putExtra(LauncherActions.EXTRA_REQUEST_ID, requestId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    companion object {
        const val ACTION_QUICK_ACTION = "dev.aerove.lite.platform.action.QUICK_ACTION"
        private const val STATE_FORWARDED = "dev.aerove.lite.platform.state.FORWARDED"
    }
}

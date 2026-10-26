package dev.aerove.lite.platform

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import java.util.UUID

enum class LauncherAction { STOP, START, TOGGLE }

object LauncherActions {
    const val EXTRA_ACTION = "dev.aerove.lite.platform.extra.QUICK_ACTION"
    const val EXTRA_REQUEST_ID = "dev.aerove.lite.platform.extra.REQUEST_ID"
    private val order = listOf(LauncherAction.START, LauncherAction.STOP, LauncherAction.TOGGLE)
    private val seenRequestIds = linkedSetOf<String>()

    fun intent(context: Context, action: LauncherAction): Intent =
        Intent(context, QuickActionActivity::class.java)
            .setAction("${context.packageName}.action.${action.name}")
            .putExtra(EXTRA_ACTION, action.name)
            .putExtra(EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    fun shortcutIntent(context: Context, action: LauncherAction): Intent =
        Intent(context, QuickActionActivity::class.java)
            .setAction("${context.packageName}.action.${action.name}")
            .putExtra(EXTRA_ACTION, action.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    fun requestIdForLaunch(requestId: String?): String? {
        val candidate = requestId ?: UUID.randomUUID().toString()
        return candidate.takeIf(::acceptRequest)
    }

    fun shouldForwardOnCreate(restoredForwardedState: Boolean): Boolean = !restoredForwardedState

    fun parseAction(value: String?): LauncherAction? =
        LauncherAction.entries.firstOrNull { it.name == value }

    fun parseIntentAction(packageName: String, action: String?, extra: String?): LauncherAction? {
        val parsed = LauncherAction.entries.firstOrNull { action == "$packageName.action.${it.name}" } ?: return null
        return if (extra == null || extra == parsed.name) parsed else null
    }

    @Synchronized
    fun acceptRequest(requestId: String?): Boolean {
        if (requestId.isNullOrBlank() || requestId.length > 80 || !seenRequestIds.add(requestId)) return false
        while (seenRequestIds.size > 128) seenRequestIds.remove(seenRequestIds.first())
        return true
    }

    fun publishDynamicShortcuts(context: Context, labels: Map<LauncherAction, Pair<String, String>>) {
        val shortcuts = order.mapIndexed { rank, action ->
            val (shortLabel, longLabel) = requireNotNull(labels[action]) { "Missing shortcut labels for $action" }
            ShortcutInfoCompat.Builder(context, action.name.lowercase())
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setRank(rank)
                .setIntent(shortcutIntent(context, action))
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

}

package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.backup.BackupActions
import dev.aerove.lite.backup.BackupConfirmation
import dev.aerove.lite.backup.BackupConfirmationKind
import dev.aerove.lite.backup.BackupNotice
import dev.aerove.lite.backup.BackupNoticeCode
import dev.aerove.lite.backup.BackupOptions
import dev.aerove.lite.backup.BackupProgress
import dev.aerove.lite.backup.BackupRemoteFile
import dev.aerove.lite.backup.BackupUiState
import dev.aerove.lite.backup.BackupWebDavUi
import dev.aerove.lite.data.BackupRestoreScope
import dev.aerove.lite.data.BackupRestoreStrategy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BackupScreenUiTest(private val material: MaterialTier) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val connection = BackupWebDavUi("https://example.com/dav", "account", "main.zip", true, false)
    private fun text(id: Int) = context.getString(id)

    @Composable private fun Surface(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalLiteMotionOverride provides true) {
            LiteTheme(LiteSettings(material = material)) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(LocalLiteVisuals.current.page).layerBackdrop(backdrop))
                    CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                        LocalLiteListState provides rememberLazyListState()) { content() }
                }
            }
        }
    }

    private fun tap(id: Int) {
        compose.onNodeWithTag("backup-content").performScrollToNode(hasText(text(id)) and hasClickAction())
        compose.onNode(hasText(text(id)) and hasClickAction()).performClick()
    }

    @Test fun optionsAndAllLocalAndRemoteActionsAreReachableWithoutAutomaticRequests() {
        val state = mutableStateOf(BackupUiState(loaded = true, webDav = connection,
            remoteFiles = listOf(BackupRemoteFile("older.zip", 1024)), listed = true))
        val actions = Actions { state.value = state.value.copy(options = it) }
        compose.replaceAppContent { Surface { BackupScreenContent(state.value, actions, PaddingValues(16.dp)) {} } }
        compose.runOnIdle { assertTrue(actions.events.isEmpty()) }
        tap(R.string.backup_profiles_only); tap(R.string.backup_override)
        compose.runOnIdle { assertEquals(BackupOptions(BackupRestoreScope.ProfilesOnly, BackupRestoreStrategy.Override), state.value.options) }
        tap(R.string.backup_create_local); tap(R.string.backup_restore_local)
        tap(R.string.backup_check); tap(R.string.backup_upload); tap(R.string.backup_restore_remote)
        tap(R.string.backup_list); tap(R.string.backup_use_file); tap(R.string.backup_restore); tap(R.string.backup_delete)
        tap(R.string.backup_unbind)
        compose.runOnIdle {
            assertEquals(listOf("options", "options", "local-save", "local-restore", "check", "upload", "restore:main.zip",
                "list", "select:older.zip", "restore:older.zip", "delete:older.zip", "unbind"), actions.events)
        }
    }

    @Test fun errorRetryAndBusyControlsFollowTheReportedOperation() {
        val state = mutableStateOf(BackupUiState(loaded = true,
            notice = BackupNotice(1, BackupNoticeCode.WriteFailed, true, true)))
        val actions = Actions()
        compose.replaceAppContent { Surface { BackupScreenContent(state.value, actions, PaddingValues(16.dp)) {} } }
        tap(R.string.backup_retry)
        compose.runOnUiThread { state.value = state.value.copy(notice = null, progress = BackupProgress.Downloading) }
        tap(R.string.backup_cancel)
        compose.runOnUiThread { state.value = state.value.copy(progress = BackupProgress.Restoring) }
        compose.onNodeWithText(text(R.string.backup_cancel)).assertDoesNotExist()
        compose.onNodeWithTag("backup-content").performScrollToNode(hasText(text(R.string.backup_create_local)))
        compose.onNodeWithText(text(R.string.backup_create_local)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("retry", "cancel"), actions.events) }
    }

    @Test fun restoreConfirmationShowsTheChosenScopeAndReplacementStrategy() {
        val actions = Actions()
        val confirmation = BackupConfirmation(7, BackupConfirmationKind.RestoreRemote, "main.zip",
            BackupOptions(BackupRestoreScope.ProfilesOnly, BackupRestoreStrategy.Override))
        compose.replaceAppContent { Surface { BackupConfirmationDialog(confirmation, actions) } }
        compose.onNodeWithText(text(R.string.backup_profiles_detail)).assertExists()
        compose.onNodeWithText(text(R.string.backup_override_detail)).assertExists()
        compose.onNodeWithText(text(R.string.backup_confirm)).performClick()
        compose.runOnIdle { assertEquals(listOf("confirm:7"), actions.events) }
    }

    @Test fun webDavPasswordIsMaskedAndTheDraftDoesNotSubmitUntilSave() {
        val actions = Actions()
        compose.replaceAppContent { Surface {
            LiteModal(text(R.string.backup_webdav_settings), {}) {
                BackupWebDavEditor(connection, false, null, null, actions, {})
            }
        } }
        val password = compose.onNode(hasContentDescription(text(R.string.backup_password)))
        password.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        password.performTextInput("entered-password")
        compose.runOnIdle { assertTrue(actions.events.isEmpty()) }
        compose.onNodeWithText(text(R.string.backup_save)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("entered-password", actions.password); assertEquals(listOf("configure"), actions.events) }
    }

    private class Actions(private val options: (BackupOptions) -> Unit = {}) : BackupActions {
        val events = mutableListOf<String>()
        var password: String? = null
        override fun open() { events += "open" }
        override fun changeOptions(options: BackupOptions) { events += "options"; this.options(options) }
        override fun requestLocalBackup() { events += "local-save" }
        override fun requestLocalRestore() { events += "local-restore" }
        override fun saveWebDav(address: String, user: String, password: String?, fileName: String, anonymous: Boolean) {
            events += "configure"; this.password = password
        }
        override fun requestUnbind() { events += "unbind" }
        override fun checkConnection() { events += "check" }
        override fun listRemote() { events += "list" }
        override fun selectRemoteFile(name: String) { events += "select:$name" }
        override fun requestRemoteBackup() { events += "upload" }
        override fun requestRemoteRestore(name: String) { events += "restore:$name" }
        override fun requestRemoteDelete(name: String) { events += "delete:$name" }
        override fun confirm(id: Long) { events += "confirm:$id" }
        override fun cancelConfirmation(id: Long) { events += "dismiss:$id" }
        override fun cancel() { events += "cancel" }
        override fun retry() { events += "retry" }
        override fun dismissNotice() { events += "dismiss" }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun materials(): List<Array<MaterialTier>> = MaterialTier.entries.map { arrayOf(it) }
    }
}

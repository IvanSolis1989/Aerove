package dev.aerove.lite.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R
import dev.aerove.lite.profiles.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.abs

private val ProfileListSortGlyph = LiteGlyph(listOf(glyphPath("M6 7H18M6 12H18M6 17H18", role = LiteGlyphRole.Line)))

internal class ProfileListUiBinding(
    val state: ProfileListState,
    val updateAll: () -> Unit,
    val cancel: () -> Unit,
    val sort: () -> Unit,
    val export: (String) -> Unit,
    val replace: (ProfileSummary) -> Unit,
    val copy: (ProfileSummary) -> Unit,
    val dismissNotice: () -> Unit,
    val copied: Boolean,
)

@Composable
internal fun rememberProfileListUi(state: LiteState): ProfileListUiBinding {
    val factory = LocalLiteFeatures.current
    var activated by rememberSaveable { mutableStateOf(false) }
    val feature = if (activated) remember(factory) { factory().profileList } else null
    val listState = if (feature == null) ProfileListState() else feature.state.collectAsState().value
    val current by rememberUpdatedState(state)
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    fun activate(): ProfileListFeature { activated = true; copied = false; return factory().profileList }
    fun close() { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    fun open(content: @Composable () -> Unit) { owned[0] = content; overlay.content = content }
    if (feature != null) ProfileDocumentHost(feature)
    DisposableEffect(overlay) { onDispose { if (overlay.content === owned[0]) overlay.dismiss() } }
    return ProfileListUiBinding(listState, { activate().updateAll() }, { activate().cancel() }, {
        val owner = activate()
        owner.dismissNotice()
        open { ProfileOrderSheet(current.profiles, owner, ::close) }
    }, { id -> activate().requestExport(id) }, { selected ->
        val latest = current.profiles.firstOrNull { it.id == selected.id }
        if (latest != null) {
            if (latest.url != null && latest.autoUpdate) open {
                LiteModal(stringResource(R.string.profile_list_replace), ::close) {
                    LiteText(stringResource(R.string.profile_list_replace_auto_prompt))
                    LiteButton(stringResource(R.string.profile_list_disable_auto), { close(); activate().requestReplacement(latest.id, true) }, Modifier.fillMaxWidth())
                    LiteButton(stringResource(R.string.profile_list_keep_auto), { close(); activate().requestReplacement(latest.id, false) }, Modifier.fillMaxWidth())
                    LiteButton(stringResource(R.string.lite_cancel), ::close, Modifier.fillMaxWidth())
                }
            } else activate().requestReplacement(latest.id)
        }
    }, { selected ->
        current.profiles.firstOrNull { it.id == selected.id }?.url?.let { clipboard.setText(AnnotatedString(it)); copied = true }
    }, { feature?.dismissNotice(); copied = false }, copied)
}

@Composable
private fun ProfileDocumentHost(feature: ProfileListFeature) {
    val state by feature.state.collectAsState()
    val active = LocalLitePageActive.current
    val currentActive by rememberUpdatedState(active)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var createId by rememberSaveable { mutableLongStateOf(0L) }
    var openId by rememberSaveable { mutableLongStateOf(0L) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
        if (!currentActive) feature.screenDetached()
        feature.documentResult(createId, uri?.toString()); createId = 0L
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (!currentActive) feature.screenDetached()
        feature.documentResult(openId, uri?.toString()); openId = 0L
    }
    LaunchedEffect(feature, active, resumed, state.document?.id) {
        if (!active) { feature.screenDetached(); return@LaunchedEffect }
        if (!resumed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val request = state.document ?: return@LaunchedEffect
        if (!request.dispatched && (createId != 0L || openId != 0L)) {
            feature.documentLaunchFailed(request.id)
            return@LaunchedEffect
        }
        if (feature.documentLaunched(request.id)) try {
            if (request.kind == ProfileDocumentKind.Create) { createId = request.id; create.launch(request.suggestedName) }
            else { openId = request.id; open.launch(arrayOf("application/yaml", "application/x-yaml", "text/*", "application/octet-stream")) }
        } catch (_: RuntimeException) {
            if (createId == request.id) createId = 0L
            if (openId == request.id) openId = 0L
            feature.documentLaunchFailed(request.id)
        }
    }
    val context = LocalContext.current
    val activity = remember(context) {
        var item = context
        while (item is ContextWrapper && item !is Activity) item = item.baseContext
        item as? Activity
    }
    DisposableEffect(feature, activity) { onDispose { if (activity?.isChangingConfigurations != true) feature.screenDetached() } }
}

@Composable
internal fun ProfileListToolbar(ui: ProfileListUiBinding, state: LiteState) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.state.busy) LiteButton(stringResource(R.string.profile_list_cancel), ui.cancel,
            Modifier.weight(1f).testTag("profile-list-cancel"), FullGlyphs.close)
        else LiteButton(stringResource(R.string.profile_list_update_all), ui.updateAll,
            Modifier.weight(1f).testTag("profile-list-update-all"), FullGlyphs.refresh,
            enabled = state.profiles.any { it.url != null } && state.busy.isEmpty())
        LiteButton(stringResource(R.string.profile_list_sort), ui.sort, Modifier.weight(1f).testTag("profile-list-sort"),
            enabled = state.profiles.size > 1 && !ui.state.busy && state.busy.isEmpty())
    }
    if (ui.copied) LiteText(stringResource(R.string.profile_list_copied), style = LiteType.Footnote)
    ui.state.progress?.let {
        LiteText(stringResource(when (it) {
            ProfileListProgress.Updating -> R.string.profile_list_updating
            ProfileListProgress.Reordering -> R.string.profile_list_reordering
            ProfileListProgress.Exporting -> R.string.profile_list_exporting
            ProfileListProgress.Replacing -> R.string.profile_list_replacing
        }), style = LiteType.Footnote)
    }
    ui.state.notice?.let { notice ->
        val label = stringResource(when (notice) {
            ProfileListNotice.Updated -> R.string.profile_list_updated
            ProfileListNotice.Reordered -> R.string.profile_list_reordered
            ProfileListNotice.Exported -> R.string.profile_list_exported
            ProfileListNotice.Replaced -> R.string.profile_list_replaced
            ProfileListNotice.Cancelled -> R.string.profile_list_cancelled
            ProfileListNotice.Failed -> R.string.profile_list_failed
            ProfileListNotice.PartialDocument -> R.string.profile_list_partial_document
        })
        LitePane(Modifier.fillMaxWidth().testTag("profile-list-result")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiteText(label)
                ui.state.error?.let { LiteText(it, color = MaterialTheme.colorScheme.error) }
                ui.state.batch?.let { batch ->
                    LiteText(stringResource(R.string.profile_list_batch_summary, batch.updated, batch.skipped, batch.failures.size), style = LiteType.Footnote)
                    batch.failures.forEach { failure -> LiteText("${failure.name}: ${failure.message}", style = LiteType.Footnote, color = MaterialTheme.colorScheme.error) }
                }
                LiteButton(stringResource(R.string.lite_dismiss), ui.dismissNotice)
            }
        }
    }
}

@Composable
private fun ProfileOrderSheet(profiles: List<ProfileSummary>, feature: ProfileListFeature, close: () -> Unit) {
    val current by rememberUpdatedState(profiles)
    val state by feature.state.collectAsState()
    var ids by rememberSaveable { mutableStateOf(profiles.map { it.id }.toTypedArray()) }
    LaunchedEffect(profiles.map { it.id }) {
        val latest = profiles.map { it.id }
        ids = (ids.filter { it in latest } + latest.filterNot { it in ids }).toTypedArray()
    }
    var submitted by remember { mutableStateOf(false) }
    val shown = ids.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
    var held by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { held = null; distance = 0f }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun move(from: Int, to: Int) {
        if (from in ids.indices && to in ids.indices && from != to) ids = ids.toMutableList().apply { add(to, removeAt(from)) }.toTypedArray()
    }
    fun reorderHeld() {
        val id = held ?: return
        val info = list.layoutInfo.visibleItemsInfo.find { it.key == id } ?: return
        val center = origin + distance + info.size / 2
        val target = list.layoutInfo.visibleItemsInfo.minByOrNull { abs(center - it.offset - it.size / 2) } ?: return
        move(ids.indexOf(id), ids.indexOf(target.key))
    }
    LaunchedEffect(held) {
        if (held != null) snapshotFlow {
            list.layoutInfo.visibleItemsInfo.find { it.key == held }?.let { item ->
                when {
                    origin + distance < list.layoutInfo.viewportStartOffset + edge && list.canScrollBackward -> -speed
                    origin + distance + item.size > list.layoutInfo.viewportEndOffset - edge && list.canScrollForward -> speed
                    else -> 0f
                }
            } ?: 0f
        }.collectLatest { amount -> if (amount != 0f) while (isActive) { list.scrollBy(amount); reorderHeld(); delay(16) } }
    }
    LaunchedEffect(state.notice, state.busy) {
        if (submitted && !state.busy) {
            if (state.notice == ProfileListNotice.Reordered) close()
            else if (state.notice == ProfileListNotice.Failed || state.notice == ProfileListNotice.Cancelled) submitted = false
        }
    }
    ProfileMaintenanceSheet(stringResource(R.string.profile_list_sort), close) {
        state.error?.let { LiteText(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 400.dp).testTag("profile-order-list"), state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = held == null) {
            itemsIndexed(shown, key = { _, item -> item.id }) { index, profile ->
                val up = stringResource(R.string.profile_list_move_up)
                val down = stringResource(R.string.profile_list_move_down)
                val dragging = held == profile.id
                LitePane(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f).graphicsLayer {
                    translationY = if (dragging) origin + distance - (list.layoutInfo.visibleItemsInfo.find { it.key == profile.id }?.offset ?: 0) else 0f
                    scaleX = if (dragging) 1.03f else 1f; scaleY = scaleX
                }.testTag("profile-order-${profile.id}").semantics {
                    customActions = if (state.busy || submitted) emptyList() else buildList {
                        if (index > 0) add(CustomAccessibilityAction(up) { move(index, index - 1); true })
                        if (index < shown.lastIndex) add(CustomAccessibilityAction(down) { move(index, index + 1); true })
                    }
                }.pointerInput(profile.id, state.busy) {
                    if (!state.busy) detectDragGesturesAfterLongPress(onDragStart = {
                        list.layoutInfo.visibleItemsInfo.find { it.key == profile.id }?.let { origin = it.offset.toFloat(); distance = 0f; held = profile.id }
                    }, onDragEnd = { held = null; distance = 0f }, onDragCancel = { held = null; distance = 0f },
                        onDrag = { change, amount -> change.consume(); distance += amount.y; reorderHeld() })
                }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiteText(profile.name, Modifier.weight(1f))
                        LiteIcon(ProfileListSortGlyph, color = LocalLiteVisuals.current.secondaryInk)
                    }
                }
            }
        }
        LiteButton(stringResource(R.string.profile_save), {
            submitted = true
            val saved = ids.filter { id -> current.any { it.id == id } }
            feature.reorder(saved + current.map { it.id }.filterNot { it in saved })
        }, Modifier.fillMaxWidth().testTag("profile-order-save"), selected = true, enabled = !state.busy && !submitted)
    }
}

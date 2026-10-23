package dev.aerove.lite.appaccess

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppAccessNotice { LoadFailed, SaveFailed, ClassificationFailed, InvalidClipboard, Copied }
data class AppAccessState(val saved: AppAccessPreferences, val draft: AppAccessPreferences = saved,
    val applications: List<InstalledApplication> = emptyList(), val loaded: Boolean = false,
    val permissionGranted: Boolean = true, val loading: Boolean = false, val classifying: Boolean = false,
    val saving: Boolean = false, val query: String = "", val pinned: Set<String> = draft.selected,
    val notice: AppAccessNotice? = null) {
    val dirty: Boolean get() = draft.forSave(applications) != saved.forSave(applications)
}

/** One screen owner: event-driven metadata load, no polling; drafts do not write VPN settings until Save. */
class AppAccessFeature(private val scope: CoroutineScope, initial: AppAccessPreferences,
    private val loader: ApplicationLoader, private val save: suspend (AppAccessPreferences) -> Unit) : AutoCloseable {
    private val mutableState = MutableStateFlow(AppAccessState(initial))
    val state = mutableState.asStateFlow()
    private var foreground = false
    private var stale = true
    private var closed = false
    private var epoch = 0L
    private var loadJob: Job? = null
    private var smartJob: Job? = null
    private var saveJob: Job? = null
    private var pendingSmartSelection = false
    private val observer = loader.observeChanges { scope.launch { if (!closed) { stale = true; if (foreground) reload() } } }

    fun setForeground(active: Boolean) {
        if (closed || foreground == active) return
        foreground = active
        if (active && (stale || !state.value.loaded)) reload()
        else if (active && pendingSmartSelection) intelligentSelect()
        else if (!active) {
            pendingSmartSelection = false
            epoch++; loadJob?.cancel(); smartJob?.cancel()
            mutableState.value = state.value.copy(loading = false, classifying = false)
        }
    }

    fun reload() {
        if (closed || !foreground) { stale = true; return }
        loadJob?.cancel(); smartJob?.cancel()
        val generation = ++epoch
        mutableState.value = state.value.copy(loading = true, classifying = false, notice = null)
        loadJob = scope.launch {
            try {
                val loaded = loader.load()
                if (!closed && foreground && epoch == generation) {
                    stale = false
                    mutableState.value = state.value.copy(applications = loaded.applications, permissionGranted = loaded.permissionGranted,
                        loaded = true, loading = false)
                    if (pendingSmartSelection) intelligentSelect()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!closed && epoch == generation) mutableState.value = state.value.copy(loading = false, notice = AppAccessNotice.LoadFailed) }
        }
    }

    fun change(value: AppAccessPreferences) {
        if (closed || state.value.saving || state.value.classifying) return
        val old = state.value
        mutableState.value = old.copy(draft = value, pinned = if (value.mode != old.draft.mode) value.selected else old.pinned, notice = null)
    }
    fun query(value: String) {
        if (closed) return
        mutableState.value = state.value.copy(query = value.take(1024), pinned = state.value.draft.selected)
    }
    fun toggle(packageName: String) {
        val selected = state.value.draft.selected
        change(state.value.draft.withSelected(if (packageName in selected) selected - packageName else selected + packageName))
    }
    fun toggleVisible(packages: Set<String>) {
        val selected = state.value.draft.selected
        change(state.value.draft.withSelected(if (selected.containsAll(packages)) selected - packages else selected + packages))
    }
    fun resetDraft() { if (!closed && !state.value.saving) mutableState.value = state.value.copy(draft = state.value.saved, pinned = state.value.saved.selected, notice = null) }
    fun importClipboard(text: String) {
        try { change(state.value.draft.withSelected(importAccessClipboard(text))) }
        catch (_: IllegalArgumentException) { mutableState.value = state.value.copy(notice = AppAccessNotice.InvalidClipboard) }
    }
    fun exportClipboard(): String = state.value.draft.selected.joinToString("\n")
    fun copied() { mutableState.value = state.value.copy(notice = AppAccessNotice.Copied) }
    suspend fun icon(packageName: String) = loader.icon(packageName)

    fun cancelSmartSelection() {
        if (closed) return
        pendingSmartSelection = false
        if (state.value.classifying) { epoch++; smartJob?.cancel(); mutableState.value = state.value.copy(classifying = false) }
    }

    fun intelligentSelect() {
        val current = state.value
        if (closed) return
        // The menu dismisses before Root's covered gate resumes this screen. Retain that explicit tap only.
        if (!foreground || current.loading) { pendingSmartSelection = true; return }
        pendingSmartSelection = false
        if (current.classifying || current.saving || current.applications.isEmpty()) return
        val generation = epoch
        mutableState.value = current.copy(classifying = true, notice = null)
        smartJob = scope.launch {
            try {
                val china = loader.chinaPackages(current.applications)
                if (!closed && foreground && generation == epoch) {
                    val all = current.applications.map { it.packageName }.toSet()
                    mutableState.value = state.value.copy(draft = state.value.draft.copy(includePackages = all - china, excludePackages = all.intersect(china)), classifying = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!closed && generation == epoch) mutableState.value = state.value.copy(classifying = false, notice = AppAccessNotice.ClassificationFailed) }
        }
    }

    fun save(afterSaved: (() -> Unit)? = null) {
        if (closed || state.value.saving || state.value.classifying || state.value.loading) return
        val value = state.value.draft.forSave(state.value.applications)
        mutableState.value = state.value.copy(saving = true, notice = null)
        saveJob = scope.launch {
            try {
                save(value)
                if (!closed) {
                    mutableState.value = state.value.copy(saved = value, draft = value, saving = false, pinned = value.selected)
                    afterSaved?.invoke()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (!closed) mutableState.value = state.value.copy(saving = false, notice = AppAccessNotice.SaveFailed) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true; epoch++; pendingSmartSelection = false
        loadJob?.cancel(); smartJob?.cancel(); saveJob?.cancel()
        observer.close(); loader.close()
    }
}

package dev.aerove.lite.profilebuilder

import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** A picked document; [open] streams it under the consumer's bound with the picker's original error text. */
class GlobalLibraryFile(val name: String, private val source: suspend () -> InputStream) {
    constructor(name: String, bytes: ByteArray) : this(name, { bytes.inputStream() })
    suspend fun open(limitBytes: Long, label: String = "Rule resource"): InputStream = PickedDocumentInput(source(), limitBytes, label)
}

private class PickedDocumentInput(input: InputStream, private val limit: Long, private val label: String) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it >= 0) charge(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) charge(it.toLong()) }
    override fun skip(count: Long): Long = super.skip(count).also(::charge)
    private fun charge(bytes: Long) {
        count += bytes
        require(count <= limit) { "$label exceeds ${limit / (1024 * 1024)} MiB" }
    }
}

private const val MAX_ICON_FILE_BYTES = 32L * 1024 * 1024

data class GlobalResourceTarget(val kind: BuilderResourceKind, val id: String)
data class GlobalResourceItem(val id: String, val name: String, val fields: List<String>)
data class GlobalResourceLibraryState(val snapshot: BuilderResourceSnapshot? = null, val busy: Boolean = false,
    val error: String? = null, val category: BuilderResourceKind? = null, val query: String = "",
    val selected: Set<String> = emptySet(), val sorting: Boolean = false, val target: GlobalResourceTarget? = null)

interface GlobalProxyLinkCodec {
    suspend fun validate(entries: List<BuilderEntry>)
    suspend fun encode(entries: List<BuilderEntry>): List<String>
    suspend fun decode(text: String): List<BuilderEntry>
}

data class GlobalRuleLibraryPreview(val text: String, val editable: Boolean, val oversize: Boolean = false) {
    fun editorText(): String = if (oversize) dev.aerove.lite.data.editorSizeFailure(dev.aerove.lite.data.MAX_RULE_PREVIEW_BYTES) else text
}
data class GlobalRuleLibraryInfo(val behavior: String, val format: String, val count: Int?, val bytes: Long,
    val updatedAt: Long, val remote: Boolean)
interface GlobalRuleLibraryAccess {
    suspend fun importUrl(expected: BuilderResourceSnapshot, name: String, url: String): BuilderResourceSnapshot
    suspend fun importFile(expected: BuilderResourceSnapshot, name: String, file: GlobalLibraryFile): BuilderResourceSnapshot
    suspend fun saveText(expected: BuilderResourceSnapshot, previousId: String?, name: String, text: String): BuilderResourceSnapshot
    suspend fun preview(expected: BuilderResourceSnapshot, name: String): GlobalRuleLibraryPreview
    suspend fun info(expected: BuilderResourceSnapshot, name: String): GlobalRuleLibraryInfo
    suspend fun options(expected: BuilderResourceSnapshot, previousId: String, name: String, url: String): BuilderResourceSnapshot
    suspend fun sync(expected: BuilderResourceSnapshot, name: String): BuilderResourceSnapshot
    suspend fun delete(expected: BuilderResourceSnapshot, name: String): BuilderResourceSnapshot
}

fun interface GlobalResourceDeletion {
    suspend fun delete(expected: BuilderResourceSnapshot, kind: BuilderResourceKind, ids: Set<String>, replacement: BuilderResources): BuilderResourceSnapshot
}

class GlobalResourceLibrarySession(private val library: BuilderResourceLibrary,
    private val deletion: GlobalResourceDeletion,
    private val committed: (BuilderResourceSnapshot) -> Unit = {}) {
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val cleanups = mutableSetOf<CompletableDeferred<Unit>>()
    private var active: Job? = null
    private var revision = 0L
    private var closed = false
    private var quiesced = false
    private val mutable = MutableStateFlow(GlobalResourceLibraryState())
    val state: StateFlow<GlobalResourceLibraryState> = mutable.asStateFlow()

    suspend fun load(): Boolean = perform { library.snapshot() }
    fun open(kind: BuilderResourceKind, name: String? = null) {
        if (!state.value.busy) mutable.update { it.copy(category = kind, query = "", selected = emptySet(), sorting = false,
            target = name?.let { value -> GlobalResourceTarget(kind, value) }, error = null) }
    }
    fun clearTarget() { mutable.update { it.copy(target = null) } }
    fun query(value: String) { if (!state.value.busy) mutable.update { it.copy(query = value, sorting = false) } }
    fun sort(value: Boolean) { if (!state.value.busy && state.value.query.isBlank()) mutable.update { it.copy(sorting = value, selected = emptySet()) } }
    fun toggleSelected(name: String) { if (!state.value.busy) mutable.update { it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name) } }
    fun selectVisible() {
        val names = visible().map { it.id }.toSet()
        if (!state.value.busy) mutable.update { it.copy(selected = if (it.selected.containsAll(names)) it.selected - names else it.selected + names) }
    }
    fun back(): Boolean {
        val before = state.value
        when {
            before.busy -> return true
            before.selected.isNotEmpty() -> mutable.update { it.copy(selected = emptySet()) }
            before.query.isNotEmpty() -> query("")
            before.sorting -> sort(false)
            before.category != null -> mutable.update { it.copy(category = null, target = null) }
            else -> return false
        }
        return true
    }
    fun ids(kind: BuilderResourceKind): List<String> = items(kind).map { it.id }
    fun visible(): List<GlobalResourceItem> {
        val kind = state.value.category ?: return emptyList()
        val terms = state.value.query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        return items(kind).filter { item -> val text = item.fields.joinToString("\n").lowercase(Locale.ROOT); terms.all(text::contains) }
    }
    private fun items(kind: BuilderResourceKind): List<GlobalResourceItem> {
        val resources = state.value.snapshot?.resources ?: return emptyList()
        return when (kind) {
            BuilderResourceKind.Proxy -> resources.proxies.map { GlobalResourceItem(requireNotNull(it.id), it.name, listOf(it.name) + it.fields.values.map(Any?::toString)) }
            BuilderResourceKind.RuleProvider -> resources.ruleProviders.map { GlobalResourceItem(requireNotNull(it.id), it.name, listOf(it.name) + it.fields.values.map(Any?::toString)) }
            BuilderResourceKind.Filter -> resources.filters.map { GlobalResourceItem(it.id, it.label, listOf(it.label, it.regex)) }
            BuilderResourceKind.IconSet -> resources.iconSets.map { GlobalResourceItem(it.id, it.name, listOf(it.name, it.url)) }
        }
    }
    suspend fun putProxy(previousId: String?, name: String, fields: Map<String, Any?>,
        validate: suspend (List<BuilderEntry>) -> Unit = {}): Boolean = perform { expected ->
        val before = requireNotNull(expected)
        val proxies = withContext(Dispatchers.Default) {
            val resources = before.resources
            val index = previousId?.let { old -> resources.proxies.indexOfFirst { it.id == old }.also { require(it >= 0) { "Node no longer exists" } } }
            graph(resources).put(BuilderSection.Proxies, index, name.trim(), fields).entries(BuilderSection.Proxies).map { entry ->
                entry.copy(id = if (index != null && entry.name == name.trim()) resources.proxies[index].id else resources.proxies.singleOrNull { it.name == entry.name }?.id)
            }
        }
        validate(proxies)
        library.replace(before, before.resources.copy(proxies = proxies))
    }
    suspend fun putFilter(previousId: String?, label: String, regex: String): Boolean = change { resources ->
        val filters = resources.filters.toMutableList(); val next = BuilderFilter(label.trim(), regex.trim())
        if (previousId == null) filters.add(next) else {
            val index = filters.indexOfFirst { it.id == previousId }; require(index >= 0) { "Filter no longer exists" }; filters[index] = next.copy(id = filters[index].id)
        }
        resources.copy(filters = filters)
    }
    suspend fun replaceProxies(entries: List<BuilderEntry>, expectedEncoded: String? = null): Boolean = perform { expected ->
        val before = requireNotNull(expected)
        require(expectedEncoded == null || before.encoded == expectedEncoded) { "Resource library changed; reopen quick edit" }
        val replacement = before.resources.copy(proxies = entries.map { entry ->
            entry.copy(id = before.resources.proxies.singleOrNull { old -> old.name == entry.name }?.id)
        }).also { graph(it); it.validate() }
        val removed = before.resources.proxies.map { requireNotNull(it.id) }.toSet() - replacement.proxies.map { requireNotNull(it.id) }.toSet()
        if (removed.isEmpty()) library.replace(before, replacement)
        else deletion.delete(before, BuilderResourceKind.Proxy, removed, replacement)
    }
    suspend fun remove(kind: BuilderResourceKind, ids: Set<String>): Boolean = perform { expected ->
        val before = requireNotNull(expected)
        val resources = before.resources
        require(ids.isNotEmpty() && this.ids(kind).containsAll(ids)) { "Resource no longer exists" }
        val replacement = when (kind) {
            BuilderResourceKind.Proxy -> resources.copy(proxies = resources.proxies.filterNot { it.id in ids })
            BuilderResourceKind.Filter -> resources.copy(filters = resources.filters.filterNot { it.id in ids })
            BuilderResourceKind.IconSet -> resources.copy(iconSets = resources.iconSets.filterNot { it.id in ids })
            BuilderResourceKind.RuleProvider -> error("Rule payload removal requires the shared rule repository")
        }
        deletion.delete(before, kind, ids, replacement)
    }
    suspend fun reorder(kind: BuilderResourceKind, order: List<String>): Boolean = change { resources ->
        val existing = ids(kind)
        require(order.size == existing.size && order.toSet() == existing.toSet()) { "Resource order changed; reload before sorting" }
        when (kind) {
            BuilderResourceKind.Proxy -> resources.copy(proxies = order.map { name -> resources.proxies.single { it.id == name } })
            BuilderResourceKind.RuleProvider -> resources.copy(ruleProviders = order.map { name -> resources.ruleProviders.single { it.id == name } })
            BuilderResourceKind.Filter -> resources.copy(filters = order.map { name -> resources.filters.single { it.id == name } })
            BuilderResourceKind.IconSet -> resources.copy(iconSets = order.map { name -> resources.iconSets.single { it.id == name } })
        }
    }
    suspend fun importIcons(file: GlobalLibraryFile): Boolean = perform { expected ->
        val bytes = withContext(Dispatchers.IO) {
            file.open(MAX_ICON_FILE_BYTES, "Icon set").use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        library.importIconSet(requireNotNull(expected), bytes.toString(Charsets.UTF_8), fallbackName = file.name.substringBeforeLast('.'))
    }
    suspend fun importIconsUrl(name: String, url: String): Boolean = perform { expected -> library.importRemoteIconSet(requireNotNull(expected), url.trim(), name.trim()) }
    suspend fun editIcons(previousId: String, name: String, url: String): Boolean = perform { expected ->
        library.editIconSet(requireNotNull(expected), previousId, name.trim(), url.trim())
    }
    suspend fun syncIcons(name: String): Boolean = perform { expected -> library.syncIconSet(requireNotNull(expected), name) }
    private suspend fun change(edit: (BuilderResources) -> BuilderResources): Boolean = perform { expected ->
        val before = requireNotNull(expected) { "Resource library is not loaded" }
        val next = withContext(Dispatchers.Default) { edit(before.resources) }
        library.replace(before, next)
    }
    fun cancel() = synchronized(gate) {
        revision++
        active?.cancel(); active = null
        mutable.update { it.copy(busy = false) }
    }
    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val (pending, receipts) = synchronized(gate) {
            quiesced = true; cancel()
            jobs.toList() to cleanups.toList()
        }
        pending.forEach { it.cancel() }; pending.joinAll()
        receipts.forEach { it.await() }
    } }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) { maintenance.withLock { synchronized(gate) {
        check(!closed) { "Global resource session is closed" }
        quiesced = false
    } } }
    fun close() = synchronized(gate) {
        closed = true; quiesced = true; cancel(); jobs.toList().forEach { it.cancel() }
    }
    suspend fun closeAndJoin() { close(); quiesce() }
    private fun current(token: Long): Boolean = !closed && !quiesced && revision == token
    suspend fun perform(action: suspend (BuilderResourceSnapshot?) -> BuilderResourceSnapshot): Boolean {
        val parent = currentCoroutineContext()[Job]
        val child = Job(parent)
        val receipt = CompletableDeferred<Unit>()
        val admitted = synchronized(gate) {
            if (closed || quiesced || mutable.value.busy) null
            else {
                val token = ++revision
                active = child; jobs += child; cleanups += receipt
                child.invokeOnCompletion {
                    receipt.complete(Unit)
                    synchronized(gate) { jobs -= child; cleanups -= receipt }
                }
                mutable.update { it.copy(busy = true, error = null) }
                token to mutable.value.snapshot
            }
        }
        if (admitted == null) { child.complete(); return false }
        val (token, before) = admitted
        try {
            return withContext(child) {
                try {
                    val saved = action(before)
                    currentCoroutineContext().ensureActive()
                    synchronized(gate) { if (current(token)) publish(saved, before) }
                    true
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        runCatching { library.snapshot() }.getOrNull()?.let { saved ->
                            synchronized(gate) { if (current(token)) publish(saved, before) }
                        }
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    synchronized(gate) { if (current(token)) mutable.update { it.copy(error = failure.message ?: failure.javaClass.simpleName) } }
                    false
                } finally {
                    synchronized(gate) { if (current(token)) { active = null; mutable.update { it.copy(busy = false) } } }
                }
            }
        } finally {
            synchronized(gate) { if (current(token)) { active = null; mutable.update { it.copy(busy = false) } } }
            child.complete()
        }
    }
    private fun publish(saved: BuilderResourceSnapshot, before: BuilderResourceSnapshot?) {
        mutable.update { it.copy(snapshot = saved, selected = it.selected.intersect(when (it.category) {
            BuilderResourceKind.Proxy -> saved.resources.proxies.map { requireNotNull(it.id) }.toSet()
            BuilderResourceKind.RuleProvider -> saved.resources.ruleProviders.map { requireNotNull(it.id) }.toSet()
            BuilderResourceKind.Filter -> saved.resources.filters.map(BuilderFilter::id).toSet()
            BuilderResourceKind.IconSet -> saved.resources.iconSets.map(BuilderIconSet::id).toSet()
            null -> emptySet()
        })) }
        if (before != null && before.encoded != saved.encoded) committed(saved)
    }
    companion object {
        fun graph(resources: BuilderResources): ProfileBuilderDocument {
            var graph = ProfileBuilderDocument.empty()
            resources.proxies.forEach { graph = graph.put(BuilderSection.Proxies, null, it.name, it.fields) }
            return graph
        }
    }
}

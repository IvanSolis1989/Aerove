package dev.aerove.lite.ipquality

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IpQualityState(
    val ip: String? = null, val quality: IpQuality? = null, val loading: Boolean = false,
    val failures: List<IpQualityFailure> = emptyList(), val provisional: Boolean = false,
    val hidden: Boolean = false, val persistenceError: Boolean = false,
    val countryCode: String? = null,
    val failed: Boolean = false,
)

class IpQualityFeature(
    parent: CoroutineScope,
    private val lookup: suspend (String) -> IpQuality,
    private val closeLookup: () -> Unit = {},
    private val store: IpQualityStore? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val readSaved: suspend () -> SavedIpQuality = { withContext(Dispatchers.IO) { store?.read() ?: SavedIpQuality() } },
) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val maintenanceContext = parent.coroutineContext.minusKey(Job)
    private val maintenance = Mutex()
    private val mutableState = MutableStateFlow(IpQualityState(hidden = store != null))
    val state = mutableState.asStateFlow()
    private data class Failure(val failures: List<IpQualityFailure>, val expires: Long)
    private val successes = mutableMapOf<String, IpQuality>()
    private val failures = mutableMapOf<String, Failure>()
    private var last: LastQualityExit? = null
    private var profile: String? = null
    private var proxied = false
    private var confirmedIp: String? = null
    private var confirmedCountry: String? = null
    private var foreground = false
    private var observers = 0
    private var loaded = store == null
    @Volatile private var closed = false
    private var hidden = store != null
    private var task: Job? = null
    @Volatile private var run = 0L
    private var saveGeneration = 0L
    @Volatile private var maintenanceGeneration = 0L
    @Volatile private var quiesced = false
    private var loadJob: Job? = null

    init { loadSaved() }

    private fun loadSaved() {
        if (store != null && !loaded && loadJob?.isActive != true) {
            val token = maintenanceGeneration
            loadJob = scope.launch {
                val saved = readSaved()
                if (closed || quiesced || token != maintenanceGeneration) return@launch
                hidden = saved.hidden
                last = saved.lastExit
                loaded = true
                rememberConfirmed()
                update()
            }
        }
    }

    fun bind(profileId: String?, proxied: Boolean, confirmedIp: String?, countryCode: String? = null) {
        check(!closed)
        require(confirmedIp == null || validQualityIp(confirmedIp))
        require(countryCode == null || countryCode.matches(Regex("[A-Z]{2}")))
        if (profile == profileId && this.proxied == proxied && this.confirmedIp == confirmedIp && confirmedCountry == countryCode) return
        cancelTask()
        profile = profileId
        this.proxied = proxied
        this.confirmedIp = confirmedIp
        confirmedCountry = countryCode
        if (loaded && !quiesced) rememberConfirmed()
        update()
    }

    fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        foreground = value
        if (!value) cancelTask()
        update()
    }

    fun observe(): Closeable {
        check(!closed)
        observers++
        update()
        var released = false
        return Closeable {
            if (!released) {
                released = true
                observers--
                if (observers == 0) cancelTask()
                if (!closed) update()
            }
        }
    }

    fun retry() {
        if (closed || quiesced || task != null) return
        confirmedIp?.let { successes.remove(it); failures.remove(it) }
        update()
    }

    fun retryIfFailed() { if (confirmedIp in failures) retry() }

    fun setHidden(value: Boolean) {
        if (closed || quiesced || !loaded || hidden == value) return
        hidden = value
        if (value) last = null
        persist()
        update()
    }

    private fun rememberConfirmed() {
        val ip = confirmedIp ?: return
        val id = profile ?: return
        if (hidden) return
        val quality = successes[ip] ?: last?.takeIf { it.ip == ip }?.quality
        val next = LastQualityExit(id, proxied, ip, quality, confirmedCountry)
        if (last != next) { last = next; persist() }
    }

    private fun update() {
        if (closed || quiesced) return
        val ip = confirmedIp
        val cached = ip?.let(successes::get)
        val failure = ip?.let(failures::get)
        val previous = last?.takeIf { it.profileId == profile && it.proxied == proxied && (ip == null || it.ip == ip) }
        val eligible = loaded && foreground && observers > 0 && profile != null && ip != null
        if (eligible && cached == null && task == null && (failure == null || now() >= failure.expires)) {
            failures.remove(ip)
            start(requireNotNull(ip))
            return
        }
        mutableState.value = IpQualityState(ip ?: previous?.ip, cached ?: if (task != null || ip == null) previous?.quality else null,
            task != null, failure?.failures.orEmpty(), ip == null && previous != null, hidden, mutableState.value.persistenceError,
            if (ip != null) confirmedCountry else previous?.countryCode, failure != null)
    }

    private fun start(ip: String) {
        val token = ++run
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val value = lookup(ip)
                require(value.ip == ip)
                if (token != run || closed || quiesced || confirmedIp != ip || !foreground || observers == 0) return@launch
                successes[ip] = value
                task = null
                rememberConfirmed()
                update()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token != run || closed || quiesced || confirmedIp != ip) return@launch
                val rows = (failure as? IpQualityLookupException)?.failures.orEmpty()
                failures[ip] = Failure(rows, now() + 5 * 60_000)
                task = null
                update()
            }
        }
        task = pending
        update()
        pending.start()
    }

    private fun cancelTask() { run++; task?.cancel(); task = null }

    private fun persist() {
        if (closed || quiesced) return
        val snapshot = SavedIpQuality(hidden, last)
        val generation = ++saveGeneration
        val epoch = maintenanceGeneration
        val storage = store ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable + Dispatchers.IO) { storage.write(snapshot, generation) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (!closed && !quiesced && epoch == maintenanceGeneration) mutableState.value = mutableState.value.copy(persistenceError = true)
            }
        }
    }

    suspend fun quiesce() = withContext(NonCancellable + maintenanceContext) {
        maintenance.withLock { quiesceOwned() }
    }

    private suspend fun quiesceOwned() {
        quiesced = true
        maintenanceGeneration++
        cancelTask()
        val children = scope.coroutineContext[Job]!!.children.toList()
        children.forEach { it.cancel() }
        children.joinAll()
        loadJob = null
        mutableState.value = mutableState.value.copy(loading = false)
    }

    suspend fun resumeAfterMaintenance() = withContext(NonCancellable + maintenanceContext) {
        maintenance.withLock {
            check(!closed && scope.coroutineContext[Job]!!.isActive) { "IP quality owner must be replaced after clear" }
            quiesced = false
            if (!loaded) loadSaved() else { rememberConfirmed(); update() }
        }
    }

    /** Secondary cleanup after the primary domain commits. Failure stays terminal and propagates. */
    suspend fun clearUserState(): Unit = withContext(NonCancellable + maintenanceContext) {
        maintenance.withLock {
            quiesceOwned()
            val wasClosed = closed
            closed = true
            scope.cancel()
            successes.clear(); failures.clear(); last = null; profile = null
            confirmedIp = null; confirmedCountry = null
            mutableState.value = IpQualityState()
            var failure: Throwable? = null
            if (!wasClosed) try { closeLookup() } catch (error: Throwable) { failure = error }
            try { withContext(Dispatchers.IO) { store?.clearUserState() } }
            catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
            failure?.let { throw it }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelTask()
        closeLookup()
        scope.cancel()
    }
}

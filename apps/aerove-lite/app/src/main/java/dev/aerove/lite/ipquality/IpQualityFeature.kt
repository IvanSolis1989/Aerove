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
import kotlinx.coroutines.delay
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
    private val wallClockNow: () -> Long = System::currentTimeMillis,
    private val readSaved: suspend () -> SavedIpQuality = { withContext(Dispatchers.IO) { store?.read() ?: SavedIpQuality() } },
    private val awaitExpiry: suspend (Long) -> Unit = { delay(it) },
) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val maintenanceContext = parent.coroutineContext.minusKey(Job)
    private val maintenance = Mutex()
    private val mutableState = MutableStateFlow(IpQualityState(hidden = store != null))
    val state = mutableState.asStateFlow()
    private data class Failure(val failures: List<IpQualityFailure>, val expires: Long)
    private val successes = mutableMapOf<String, IpQualityCheck>()
    private val failures = mutableMapOf<String, Failure>()
    private var last: LastQualityExit? = null
    private var staleQualityIp: String? = null
    private var profile: String? = null
    private var proxied = false
    private var confirmedIp: String? = null
    private var confirmedCountry: String? = null
    private var foreground = false
    private var readActive = true
    private var manualPending = false
    private var taskManual = false
    private val retiring = mutableListOf<Job>()
    private var observers = 0
    private var loaded = store == null
    @Volatile private var closed = false
    private var hidden = store != null
    private var task: Job? = null
    private var expiryTask: Job? = null
    private var expiryEntry: IpQualityCheck? = null
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
                successes.clear()
                val wallNow = wallClockNow()
                val validCache = saved.cache.filter { validCacheEntry(it, wallNow) }
                validCache.forEach { successes[it.ip] = it }
                if (saved.cache.any { it.ip == confirmedIp && !validCacheEntry(it, wallNow) }) staleQualityIp = confirmedIp
                loaded = true
                rememberConfirmed()
                if (validCache.size != saved.cache.size) persist()
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
        cancelExpiry()
        profile = profileId
        this.proxied = proxied
        this.confirmedIp = confirmedIp
        confirmedCountry = countryCode
        if (loaded && !quiesced) rememberConfirmed()
        update()
    }

    fun readDemand(value: Boolean) {
        if (closed || readActive == value) return
        readActive = value
        if (!value) { if (!manualPending) cancelTask(resetManual = false); cancelExpiry() }
        update()
    }

    fun setForeground(value: Boolean) {
        if (closed || foreground == value) return
        foreground = value
        if (!value) { cancelTask(); cancelExpiry() }
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
                if (observers == 0) { if (!manualPending) cancelTask(resetManual = false); cancelExpiry() }
                if (!closed) update()
            }
        }
    }

    fun retry() {
        if (closed || quiesced) return
        manualPending = true
        if (task != null) { taskManual = true; return }
        confirmedIp?.let { successes.remove(it); failures.remove(it) }
        persist()
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
        val quality = cachedQuality(ip) ?: last?.takeIf { it.ip == ip }?.quality
        val next = LastQualityExit(id, proxied, ip, quality, confirmedCountry)
        if (last != next) { last = next; persist() }
    }

    private fun cachedQuality(ip: String): IpQuality? {
        val entry = successes[ip] ?: return null
        if (!validCacheEntry(entry, wallClockNow())) { staleQualityIp = ip; successes.remove(ip); return null }
        if (foreground && readActive && observers > 0) scheduleExpiry(entry)
        return entry.quality
    }

    private fun scheduleExpiry(entry: IpQualityCheck) {
        if (expiryEntry == entry && expiryTask?.isActive == true) return
        cancelExpiry()
        expiryEntry = entry
        val age = wallClockNow() - entry.checkedAtWallClock
        val waitMs = (CACHE_TTL_MS - age).coerceAtLeast(0)
        expiryTask = scope.launch {
            try {
                awaitExpiry(waitMs)
                if (closed || quiesced || !foreground || !readActive || observers == 0 || successes[entry.ip] != entry) return@launch
                if (validCacheEntry(entry, wallClockNow())) {
                    expiryEntry = null
                    expiryTask = null
                    scheduleExpiry(entry)
                    return@launch
                }
                successes.remove(entry.ip)
                staleQualityIp = entry.ip
                expiryEntry = null
                expiryTask = null
                persist()
                update()
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }

    private fun cancelExpiry() {
        expiryTask?.cancel()
        expiryTask = null
        expiryEntry = null
    }

    private fun validCacheEntry(entry: IpQualityCheck, wallNow: Long): Boolean =
        entry.checkedAtWallClock in 0..wallNow && wallNow - entry.checkedAtWallClock < CACHE_TTL_MS && entry.quality.ip == entry.ip

    private fun update() {
        if (closed || quiesced) return
        val ip = confirmedIp
        val cached = ip?.let(::cachedQuality)
        val failure = ip?.let(failures::get)
        val previous = last?.takeIf { it.profileId == profile && it.proxied == proxied && (ip == null || it.ip == ip) }
        val eligible = loaded && foreground && (manualPending || (readActive && observers > 0)) && profile != null && ip != null
        if (eligible && cached == null && task == null && (failure == null || now() >= failure.expires)) {
            failures.remove(ip)
            start(requireNotNull(ip))
            return
        }
        if (cached == null && ip != null) persistExpiredCacheIfNeeded(ip)
        val bridge = previous?.quality?.takeUnless { staleQualityIp == ip }
        mutableState.value = IpQualityState(ip ?: previous?.ip, cached ?: if (task != null || ip == null) bridge else null,
            task != null, failure?.failures.orEmpty(), ip == null && previous != null, hidden, mutableState.value.persistenceError,
            if (ip != null) confirmedCountry else previous?.countryCode, failure != null)
    }

    private fun persistExpiredCacheIfNeeded(ip: String) {
        if (successes.remove(ip) != null) persist()
    }

    private fun start(ip: String) {
        val token = ++run
        taskManual = manualPending
        retiring.removeAll { it.isCompleted }
        val predecessors = retiring.toList()
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                predecessors.joinAll()
                val value = lookup(ip)
                require(value.ip == ip)
                if (token != run || closed || quiesced || confirmedIp != ip || !foreground || (!taskManual && (!readActive || observers == 0))) return@launch
                successes[ip] = IpQualityCheck(ip, value, wallClockNow())
                if (staleQualityIp == ip) staleQualityIp = null
                task = null; taskManual = false; manualPending = false
                rememberConfirmed()
                persist()
                update()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token != run || closed || quiesced || confirmedIp != ip) return@launch
                val rows = (failure as? IpQualityLookupException)?.failures.orEmpty()
                failures[ip] = Failure(rows, now() + 5 * 60_000)
                task = null; taskManual = false; manualPending = false
                update()
            }
        }
        task = pending
        update()
        pending.start()
    }

    private fun cancelTask(resetManual: Boolean = true) {
        run++; task?.let { it.cancel(); retiring += it }; task = null; taskManual = false
        if (resetManual) manualPending = false
    }

    private fun persist() {
        if (closed || quiesced) return
        val wallNow = wallClockNow()
        successes.entries.removeAll { !validCacheEntry(it.value, wallNow) }
        val snapshot = SavedIpQuality(hidden, last, successes.values.toList())
        val generation = ++saveGeneration
        val epoch = maintenanceGeneration
        val storage = store ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable + Dispatchers.IO) { storage.write(snapshot, generation, wallClockNow()) } }
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
        cancelExpiry()
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
            successes.clear(); failures.clear(); last = null; staleQualityIp = null; profile = null
            confirmedIp = null; confirmedCountry = null
            mutableState.value = IpQualityState()
            var failure: Throwable? = null
            if (!wasClosed) try { closeLookup() } catch (error: Throwable) { failure = error }
            try { withContext(Dispatchers.IO) { store?.clearUserState() } }
            catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
            failure?.let { throw it }
        }
    }

    private companion object { const val CACHE_TTL_MS = 3L * 24 * 60 * 60 * 1000 }

    override fun close() {
        if (closed) return
        closed = true
        cancelTask()
        cancelExpiry()
        closeLookup()
        scope.cancel()
    }
}

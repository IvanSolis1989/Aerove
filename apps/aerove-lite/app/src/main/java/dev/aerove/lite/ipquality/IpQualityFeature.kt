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
    private var closed = false
    private var hidden = store != null
    private var task: Job? = null
    private var run = 0L
    private var saveGeneration = 0L

    init {
        if (store != null) scope.launch {
            val saved = readSaved()
            hidden = saved.hidden
            last = saved.lastExit
            loaded = true
            rememberConfirmed()
            update()
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
        if (loaded) rememberConfirmed()
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
        if (closed || task != null) return
        confirmedIp?.let { successes.remove(it); failures.remove(it) }
        update()
    }

    fun retryIfFailed() { if (confirmedIp in failures) retry() }

    fun setHidden(value: Boolean) {
        if (closed || !loaded || hidden == value) return
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
        if (closed) return
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
                if (token != run || closed || confirmedIp != ip || !foreground || observers == 0) return@launch
                successes[ip] = value
                task = null
                rememberConfirmed()
                update()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token != run || closed || confirmedIp != ip) return@launch
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
        val snapshot = SavedIpQuality(hidden, last)
        val generation = ++saveGeneration
        val storage = store ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { withContext(NonCancellable + Dispatchers.IO) { storage.write(snapshot, generation) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(persistenceError = true) }
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

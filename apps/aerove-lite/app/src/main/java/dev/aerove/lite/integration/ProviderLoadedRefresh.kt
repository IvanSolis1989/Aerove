package dev.aerove.lite.integration

import dev.aerove.lite.providers.ProviderKind
import dev.aerove.lite.providers.RuntimeProvider
import dev.aerove.lite.telemetry.ProviderLoaded
import dev.aerove.lite.telemetry.CoreTelemetry
import kotlinx.coroutines.*

/** The existing 500ms loaded-event debounce; successful GET revisions are the only acknowledgement. */
internal class ProviderLoadedRefresh(private val scope: CoroutineScope,
    private val cached: () -> Boolean,
    private val read: suspend (ProviderKind, String) -> RuntimeProvider,
    private val readAll: suspend () -> List<RuntimeProvider>,
    private val publish: (List<RuntimeProvider>, Boolean) -> Unit,
    private val failure: (Exception) -> Unit) {
    private val gate = Any()
    private var session: Any? = null
    private var active = false
    private var closed = false
    private var reading = false
    private var manual = 0
    private var job: Job? = null
    private val seen = mutableMapOf<Pair<ProviderKind,String>, ULong>()
    private val pending = mutableMapOf<Pair<ProviderKind,String>, ProviderLoaded>()
    fun session(value: Any?) = synchronized(gate) {
        if (session === value) return@synchronized
        session = value; job?.cancel(); job = null; reading = false; manual = 0
        seen.clear(); pending.clear()
    }
    fun active(value: Boolean) = synchronized(gate) {
        if (active == value || closed) return@synchronized
        active = value
        if (!value) { job?.cancel(); job = null; reading = false } else schedule()
    }
    fun accept(owner:Any,event:CoreTelemetry) { if(event is CoreTelemetry.ProvidersLoaded) accept(owner,event.providers) }
    fun accept(owner: Any, values: List<ProviderLoaded>) = synchronized(gate) {
        if (closed || session !== owner) return@synchronized
        var advanced = false
        values.forEach { value ->
            val key=value.kind to value.name
            if (value.revision > (seen[key] ?: 0uL) && value.revision > (pending[key]?.revision ?: 0uL)) {
                pending[key]=value
                advanced = true
            }
        }
        if (advanced) schedule()
    }
    fun acknowledge(owner: Any, values: List<RuntimeProvider>) = synchronized(gate) {
        if (closed || session !== owner) return@synchronized
        values.forEach { value ->
            val revision=value.revision?.toULongOrNull() ?: return@forEach
            val key=value.kind to value.name
            if (revision > (seen[key] ?: 0uL)) seen[key]=revision
            if ((pending[key]?.revision ?: ULong.MAX_VALUE) <= revision) pending.remove(key)
        }
        if (pending.isEmpty() && !reading) { job?.cancel(); job=null }
    }
    fun manualStarted(owner: Any) = synchronized(gate) { if (session === owner && !closed) { manual++; if (!reading) { job?.cancel();job=null } } }
    fun manualFinished(owner: Any) = synchronized(gate) { if (session === owner && !closed) { check(manual>0);manual--;schedule() } }
    fun hasPending():Boolean = synchronized(gate) { pending.isNotEmpty() }
    fun refreshPending() = synchronized(gate) { schedule() }
    private fun schedule() {
        if (closed || !active || session==null || pending.isEmpty() || manual>0 || reading) return
        job?.cancel()
        val owner=checkNotNull(session)
        lateinit var launched: Job
        launched=scope.launch(start=CoroutineStart.LAZY) {
            var completed=false
            var capturedRevisions=emptyMap<Pair<ProviderKind,String>,ULong>()
            try {
                delay(500)
                val captured=synchronized(gate) {
                    if (session !== owner || !active || manual>0 || closed || job !== launched || pending.isEmpty()) return@launch
                    reading=true;pending.toMap().also { values -> capturedRevisions=values.mapValues{it.value.revision} }
                }
                val all=captured.size>1 || !cached()
                val result=if(all) readAll() else captured.keys.single().let { listOf(read(it.first,it.second)) }
                synchronized(gate) {
                    if (session !== owner || !active || closed || job !== launched) return@synchronized
                    val fresh=result.filter{row ->
                        val acknowledged=seen[row.kind to row.name]?:0uL
                        val revision=row.revision?.toULongOrNull()
                        if(revision==null) acknowledged==0uL else revision>=acknowledged
                    }
                    if(fresh.isNotEmpty() || (all && result.isEmpty())) publish(fresh,all && fresh.size==result.size)
                    acknowledge(owner,result)
                    val notAcknowledged=captured.any { (key,value) -> (seen[key] ?: 0uL)<value.revision }
                    if (notAcknowledged) throw java.io.IOException("Provider readback did not acknowledge loaded revision")
                    completed=true
                }
            } catch (cancelled:CancellationException) { throw cancelled }
            catch (error:Exception) { synchronized(gate) { if (session === owner && active && !closed && job === launched) failure(error) } }
            finally { synchronized(gate) {
                if (job === launched) {
                    job=null;reading=false
                    val newer=pending.any{(key,value)->value.revision>(capturedRevisions[key]?:0uL)}
                    if(completed || newer) schedule()
                }
            } }
        }
        job=launched;launched.start()
    }
    fun close() = synchronized(gate) { closed=true;active=false;job?.cancel();job=null;pending.clear();seen.clear() }
}

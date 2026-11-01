package dev.aerove.lite.flutter

import dev.aerove.lite.integration.CoalescedSourceApply
import dev.aerove.lite.resources.GeoResourceCatalog
import dev.aerove.lite.resources.GeoResourceKind
import dev.aerove.lite.resources.ResourceApplyReceipt
import dev.aerove.lite.resources.ResourceManagementMaintenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Adapter composed by the attachment around the existing maintenance and source-apply owners. */
internal class MeowGeoResourceUpdates(scope: CoroutineScope, catalog: GeoResourceCatalog,
    private val capture: suspend () -> MeowGeoResourceIntent,
    private val verifyCurrent: (MeowGeoResourceIntent) -> Boolean,
    apply: suspend (MeowGeoResourceIntent, () -> Boolean) -> ResourceApplyReceipt,
    private val emit: (GeoResourceKind, Boolean, Boolean, String?) -> Unit,
    warning: (Throwable) -> Unit,
    private val capturePrepared: (suspend (org.json.JSONObject) -> MeowGeoResourceIntent)? = null) {
    @Volatile private var closed=false
    @Volatile private var captured:MeowGeoResourceIntent?=null
    @Volatile private var foreground=true
    private data class PendingSource(val sourceGuard:String,val revision:String,val epoch:Long,val compiled:String,
        val owner:Long,val generation:Long,val geo:dev.aerove.lite.resources.GeoResourcePreferences,
        val requests:dev.aerove.lite.requests.RequestPreferences)
    private fun key(value:MeowGeoResourceIntent)=PendingSource(value.sourceGuard,value.runtime.revision,value.runtime.intentEpoch,
        value.runtime.compiledSha256,value.runtime.serviceOwner,value.runtime.sessionGeneration,value.settings.geoResources,value.settings.requests)
    @Volatile private var pendingSource:PendingSource?=null
    private val events=Any()
    private val started=linkedSetOf<GeoResourceKind>()
    private fun current(value:MeowGeoResourceIntent)=!closed && captured === value && runCatching { verifyCurrent(value) }.getOrDefault(false)
    private val sourceApply=CoalescedSourceApply(scope,{captured?.let(::current)==true},{ active ->
        val value=checkNotNull(captured)
        check(current(value)) { "Geo source was superseded" }
        val receipt=apply(value) { active() && current(value) }
        val applied=capture()
        check(receipt.appliedGeneration == applied.runtime.compiledSha256 && applied.sourceGuard == value.sourceGuard &&
            applied.runtime.serviceOwner == value.runtime.serviceOwner && applied.runtime.sessionGeneration == value.runtime.sessionGeneration &&
            applied.runtime.intentEpoch == value.runtime.intentEpoch && applied.snapshot.userRequested == value.snapshot.userRequested) {
            "Geo application has no matching actual runtime receipt"
        }
        captured=applied
        pendingSource=null
        receipt
    },warning)
    private val maintenance=ResourceManagementMaintenance(catalog,null,null,
        {checkNotNull(captured).settings.geoResources}, {
            pendingSource=key(checkNotNull(captured));sourceApply.requestAndAwait();Unit
        },geoOnly=true,
        requestPreferences={checkNotNull(captured).settings.requests},onGeoStarted={ kind ->
            synchronized(events) { started+=kind };emit(kind,true,false,null)
        },hasPendingGeoApply={captured?.let(::key) == pendingSource && pendingSource != null})

    suspend fun update(type:String, producerRequest:org.json.JSONObject? = null):String {
        val automatic=type == "automatic"
        val kind=if(automatic)null else GeoResourceKind.entries.firstOrNull { it.wire == type }
            ?: if(type == "geoip") throw MeowConfigProtocolException("implementation_blocked",
                "The fixed Meow core has no geoip.dat source-format consumer",mapOf("field" to "$.geodata.url.geoip"))
            else throw MeowConfigProtocolException("bad_argument","Unknown Geo resource kind")
        val result=maintenance.updateGeoDataCaptured(kind,automatic,onCompleted={ result,problem ->
            if(problem is CancellationException && foreground) sourceApply.cancelPending()
            val error=problem?.message ?: result?.failed?.takeIf { it.isNotEmpty() }?.let { "Geo resource update failed: ${it.sorted().joinToString()}" }
            val pending=synchronized(events) { started.toSet().also { started.clear() } }
            pending.forEach { entry -> emit(entry,false,result == null || "geo:${entry.wire}" !in result.updated,error) }
        }) {
            // Capture only after predecessor cancellation has joined, within maintenance's single admission lane.
            sourceApply.quiesce();sourceApply.resumeAfterMaintenance()
            currentCoroutineContext().ensureActive()
            val next=if(producerRequest == null) capture() else checkNotNull(capturePrepared)(producerRequest)
            captured=next
            if(producerRequest != null) pendingSource=key(next)
            synchronized(events) { started.clear() }
            val guard:()->Boolean={current(next)}
            guard
        }
        return if(result.failed.isEmpty()) "" else "Geo resource update failed: ${result.failed.sorted().joinToString()}"
    }

    suspend fun cancelCurrentAndJoin()=withContext(NonCancellable) {
        maintenance.cancelGeoAndJoin {
            sourceApply.quiesce();sourceApply.resumeAfterMaintenance()
        }
    }

    suspend fun automaticDue(value:MeowGeoResourceIntent):Boolean =
        pendingSource == key(value) || maintenance.automaticGeoDue(value.settings.geoResources)

    fun foreground(active:Boolean) { foreground=active;sourceApply.foreground(active) }
    suspend fun closeAndJoin()=withContext(NonCancellable) {
        closed=true;maintenance.quiesce();sourceApply.quiesce();sourceApply.close();captured=null
    }
}

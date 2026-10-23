package dev.aerove.lite.data
import dev.aerove.lite.telemetry.*
import dev.aerove.lite.providers.ProviderKind
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProviderLoadedWireTest {
    private fun frame(row:String)="""{"type":"providersLoaded","providers":[$row]}"""
    private fun row(revision:String="\"18446744073709551615\"",kind:String="proxy",phase:String="update",changed:String="false")=
        """{"kind":"$kind","name":"exact / 名","revision":$revision,"phase":"$phase","changed":$changed}"""
    private suspend fun receive(value:String):CoreTelemetry=withTimeout(5000){MockWebServer().use { server->
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){override fun onOpen(socket:WebSocket,response:Response){socket.send(value)}}))
        server.start();CoreApiClient(ControlEndpoint(server.port,"fixture-private-secret-00000000000000")).use { it.telemetry().first() }
    }}
    @Test fun actualSharedWsDecodesCanonicalU64AndExactKindPhase()=runBlocking<Unit>{
        val event=receive(frame(row())) as CoreTelemetry.ProvidersLoaded
        assertEquals(ULong.MAX_VALUE,event.providers.single().revision)
        assertEquals(ProviderKind.Proxy,event.providers.single().kind)
        assertEquals(ProviderLoadPhase.Update,event.providers.single().phase)
        assertFalse(event.providers.single().changed)
    }
    @Test fun malformedProviderFramesAreRejectedWithoutDoubleLongCoercion()=runBlocking<Unit>{
        val malformed=listOf(row("1"),row("\"0\""),row("\"01\""),row("\"18446744073709551616\""),row(kind="group"),row(phase="replay"),row(changed="\"false\""))
        for(value in malformed){try {receive(frame(value));fail("Accepted $value")}catch(_:IOException){}}
        try{receive(frame(row()+","+row()));fail("Duplicate identity accepted")}catch(_:IOException){}
    }    @Test fun sameNameInDifferentKindsRemainsTwoExactIdentities()=runBlocking<Unit>{
        val event=receive(frame(row("\"1\"","proxy","initial","true")+","+row("\"2\"","rule","update","false"))) as CoreTelemetry.ProvidersLoaded
        assertEquals(setOf(ProviderKind.Proxy,ProviderKind.Rule),event.providers.map{it.kind}.toSet())
        assertEquals(listOf(1uL,2uL),event.providers.map{it.revision})
    }

}

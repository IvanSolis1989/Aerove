package dev.aerove.lite.ui
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProviderTelemetryGateUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun coveredRootPausesAndRepeatedUncoveredCompositionDoesNotRedeclareGate(){
        val actions=UiTestActions{}
        var state by mutableStateOf(LiteState(settings=LiteSettings(material=MaterialTier.Solid,aurora=false)))
        compose.replaceAppContent { IpQualityFixture(state){ LiteApp(state,actions) } }
        compose.waitUntil(3000){actions.providerTelemetry.lastOrNull()==true}
        compose.runOnUiThread{state=state.copy(message="Covered actual root")}
        compose.waitUntil(3000){actions.providerTelemetry.lastOrNull()==false}
        compose.runOnUiThread{state=state.copy(message=null)}
        compose.waitUntil(3000){actions.providerTelemetry.lastOrNull()==true}
        val declarations=actions.providerTelemetry.size
        compose.runOnUiThread{state=state.copy(traffic=Traffic(upload=1))}
        compose.waitForIdle()
        assertEquals(declarations,actions.providerTelemetry.size)
        assertEquals(listOf(true,false,true),actions.providerTelemetry.filterIndexed{index,value->index==0||value!=actions.providerTelemetry[index-1]})
    }
}

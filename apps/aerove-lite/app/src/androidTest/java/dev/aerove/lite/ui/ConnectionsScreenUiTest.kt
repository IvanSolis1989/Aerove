package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.connections.decodeConnectionSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production Tools -> Connections route; fixtures use the actual core decoder. */
class ConnectionsScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val rows = decodeConnectionSnapshot(JSONObject("""{"connections":[
        {"id":"alpha","upload":100,"download":200,"start":"2026-10-23T00:00:00Z","rule":"DOMAIN","rulePayload":"example.org","chains":["node-A","group-A"],
          "metadata":{"host":"alpha.example.org","destinationIP":"203.0.113.1","destinationPort":443,"sourceIP":"10.0.0.2","sourcePort":52000,"network":"tcp","type":"Tun","process":"Browser","processPath":"/app/browser","uid":10234}},
        {"id":"beta","upload":10,"download":20,"rule":"MATCH","chains":["DIRECT"],"metadata":{"host":"beta.example.org","destinationPort":53,"network":"udp"}}
    ]}"""))
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false), connections = rows))
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }

    private fun open() {
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) }
        }
        compose.onNodeWithText(context.getString(R.string.lite_tab_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.lite_tab_connections)).performClick()
        compose.waitUntil { compose.onAllNodesWithTag("connection-alpha").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun rootSearchFiltersActualMetadataAndCloseWaitsForStateReadBack() {
        open()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_search)).performTextInput("BROWSER tcp")
        compose.waitUntil { compose.onAllNodesWithTag("connection-beta").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("connection-alpha").performScrollTo().performClick()
        compose.onNodeWithText("10.0.0.2:52000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("/app/browser").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("10234").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-detail-close").performScrollTo().performClick()
        assertTrue(actions.events.contains("closeConnection:alpha"))
        compose.onNodeWithTag("connection-detail-close").assertIsEnabled()
        compose.runOnUiThread { state.value = state.value.copy(connections = listOf(rows[1])) }
        compose.onNodeWithText(context.getString(R.string.connections_ended)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-detail-close").assertIsNotEnabled()
    }

    @Test fun rootExactChainFilterCanBeClearedAndSortSurvivesReadBack() {
        open()
        compose.onNodeWithText(context.getString(R.string.connections_filter)).performClick()
        compose.waitUntil { compose.onAllNodesWithText("group-A").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("group-A").performScrollTo().performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil { compose.onAllNodesWithTag("connection-beta").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(context.getString(R.string.connections_filter)).performClick()
        compose.onNodeWithText(context.getString(R.string.connections_clear_filters)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.connections_sort)).performClick()
        compose.onNodeWithText(context.getString(R.string.connections_target)).performScrollTo().performClick()
        compose.runOnUiThread { state.value = state.value.copy(connections = rows.reversed()) }
        compose.waitUntil { compose.onAllNodesWithTag("connection-alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connections-close-all").performScrollTo().performClick()
        assertTrue(actions.events.contains("closeAllConnections"))
        compose.runOnUiThread { state.value = state.value.copy(connections = emptyList()) }
        compose.onNodeWithText(context.getString(R.string.lite_no_connections)).assertIsDisplayed()
        assertTrue(actions.connectionTelemetry.contains(true))
    }

    @Test fun detailReflectsLatestMetadataAndBusyStateDisablesClose() {
        open()
        compose.onNodeWithTag("connection-alpha").performScrollTo().performClick()
        compose.runOnUiThread { state.value = state.value.copy(connections = listOf(rows.first().copy(sourcePort = 53000)), busy = setOf("close")) }
        compose.onNodeWithText("10.0.0.2:53000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-detail-close").performScrollTo().assertIsNotEnabled()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("connection-close-alpha").performScrollTo().assertIsNotEnabled()
    }
}

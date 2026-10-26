package dev.aerove.lite.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkOrderTest {
    @Test fun singleNetworkDoesNotNeedActiveIdentity() {
        assertEquals(listOf("cell"), orderLocalNetworks(setOf("cell"), null) { false })
    }
    @Test fun validatedDefaultChangeIsReevaluatedWithoutSuppressingCapsEvent() {
        val networks = linkedSetOf("wifi", "cell")
        assertEquals(listOf("cell", "wifi"), orderLocalNetworks(networks, "cell") { it == "wifi" })
        assertEquals(listOf("wifi", "cell"), orderLocalNetworks(networks, "wifi") { it == "wifi" })
    }
}

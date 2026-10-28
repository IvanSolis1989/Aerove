package dev.aerove.lite.ui

import dev.aerove.lite.DashboardWidget
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardEditorCompatibilityTest {
    @Test fun editorEntryPreservesEveryRawMemberWithoutInsertingStatusOrDroppingLegacyItems() {
        val method = Class.forName("dev.aerove.lite.ui.DashboardEditorKt")
            .getDeclaredMethod("editorWidgets", List::class.java).apply { isAccessible = true }
        val vectors = listOf(emptyList(), listOf(DashboardWidget.IntranetIp, DashboardWidget.Profiles,
            DashboardWidget.NetworkSpeed, DashboardWidget.VpnButton, DashboardWidget.ConnectionStats))
        vectors.forEach { raw -> assertEquals(raw, method.invoke(null, raw)) }
    }
}

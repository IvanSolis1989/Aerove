package dev.aerove.lite.flutter

import dev.aerove.lite.appaccess.InstalledApplication
import dev.aerove.lite.platform.LauncherAction
import dev.aerove.lite.updates.LITE_APK_NAME
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MeowAppPluginTest {
    private val official = "https://github.com/IvanSolis1989/Aerove/releases/download/" +
        "lite-v0.1.0-beta.26%2B26/$LITE_APK_NAME"

    @Test fun packageProjectionPreservesTheSharedDtoFieldsAndEscapesLabels() {
        val packageInfo = InstalledApplication("example.app", "中文 \"应用\"", true, false, 123L)
        val rows = JSONArray(meowPackagesJson(listOf(packageInfo)))
        val row = rows.getJSONObject(0)
        assertEquals(1, rows.length())
        assertEquals(setOf("packageName", "label", "system", "internet", "lastUpdateTime"), row.keySet())
        assertEquals(packageInfo.packageName, row.getString("packageName"))
        assertEquals(packageInfo.label, row.getString("label"))
        assertTrue(row.getBoolean("system"))
        assertFalse(row.getBoolean("internet"))
        assertEquals(123L, row.getLong("lastUpdateTime"))
    }

    @Test fun shortcutLabelsMustCoverEverySharedActionWithAString() {
        val labels = mapOf("start" to "启动", "stop" to "停止", "toggle" to "切换")
        assertEquals(LauncherAction.entries.toSet(), meowShortcutLabels(labels).keys)
        assertEquals("启动", meowShortcutLabels(labels)[LauncherAction.START])
        assertThrows(IllegalArgumentException::class.java) { meowShortcutLabels(labels - "stop") }
        assertThrows(IllegalArgumentException::class.java) { meowShortcutLabels(labels + ("stop" to false)) }
        assertThrows(IllegalArgumentException::class.java) { meowShortcutLabels(null) }
    }

    @Test fun updateDownloadAcceptsTheExistingOfficialLiteAssetIdentity() {
        assertTrue(meowUpdateAsset(official, LITE_APK_NAME).startsWith("https://github.com/IvanSolis1989/Aerove/releases/download/"))
    }

    @Test fun updateDownloadRejectsOtherProductsAndAmbiguousOrUntrustedUrls() {
        val invalid = listOf(
            official.replace("IvanSolis1989/Aerove", "other/Aerove"),
            official.replace("github.com", "github.com.example.org"),
            official.replace("https://", "http://"),
            official.replace("github.com/", "github.com:444/"),
            "$official?download=1",
            "$official#fragment",
            official.replace("lite-v", "v"),
            official.replace("%2B26", "%2B0"),
            official.replace("%2B26", "%2B2100000001"),
            official.replace(LITE_APK_NAME, "Aerove-android-arm64.apk"),
        )
        invalid.forEach { url -> assertThrows(url, IllegalArgumentException::class.java) { meowUpdateAsset(url, LITE_APK_NAME) } }
        assertThrows(IllegalArgumentException::class.java) { meowUpdateAsset(official, "../$LITE_APK_NAME") }
        assertThrows(IllegalArgumentException::class.java) { meowUpdateAsset(official, "Aerove-android-arm64.apk") }
    }
}

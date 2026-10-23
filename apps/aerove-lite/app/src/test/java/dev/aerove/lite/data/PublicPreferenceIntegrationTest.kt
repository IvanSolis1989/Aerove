package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.requests.RequestPreferences
import dev.aerove.lite.requests.RequestPreferencesJson
import dev.aerove.lite.requests.productUserAgent
import dev.aerove.lite.proxies.ProxyPresentationSort
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicPreferenceIntegrationTest {
    @Test fun storeRoundTripsBothDomainsAndRejectsMalformedNestedFields() = runBlocking {
        val root = Files.createTempDirectory("public-preferences").toFile()
        try {
            val store = LiteSettingsStore(root)
            val value = LiteSettings(requests = RequestPreferences("Custom/2", listOf("Custom/2"), false))
                .let { it.copy(proxyPresentation = it.proxyPresentation.copy(sort = ProxyPresentationSort.byName, timeoutMillis = 1200),
                    historyTables = dev.aerove.lite.observability.HistoryTablePreferences(mapOf("dns" to mapOf("domain" to 140.0)))) }
            store.save(value)
            assertEquals(value, LiteSettingsStore(root).read())
            val file = File(root, "settings/settings.json")
            val saved = JSONObject(file.readText())
            for ((domain, malformed) in listOf("requests" to JSONObject().put("checkCertificate", "false"),
                "requests" to JSONObject().put("userAgents", "bad"), "proxyPresentation" to JSONObject().put("iconStyle", "unknown"))) {
                file.writeText(JSONObject(saved.toString()).put(domain, malformed).toString())
                assertThrows(IllegalArgumentException::class.java) { runBlocking { store.read() } }
            }
            file.writeText(JSONObject(saved.toString()).also { it.remove("requests"); it.remove("proxyPresentation") }.toString())
            assertEquals(RequestPreferences(), store.read().requests)
        } finally { root.deleteRecursively() }
    }

    @Test fun realStoreBackedPolicyUpdatesBothDownloadersAndRestoresPackageDefault() = runBlocking<Unit> {
        val root = Files.createTempDirectory("request-policy-owner").toFile()
        try {
            val store = LiteSettingsStore(root)
            val identity = productUserAgent("AEROVE Lite", "0.1.0-preview.1")
            val policy = store.requestPolicy(identity)
            val subscription = SubscriptionDownloader(policy)
            val resource = ResourceDownloader(policy)
            MockWebServer().use { server ->
                var serial = 0
                suspend fun request(expected: String) {
                    server.enqueue(MockResponse().setBody("mode: rule"))
                    subscription.download(server.url("/subscription").toString())
                    assertEquals(expected, server.takeRequest().getHeader("User-Agent"))
                    server.enqueue(MockResponse().setBody("payload"))
                    resource.download(server.url("/resource").toString(), File(root, "resource-${serial++}"), 1024)
                    assertEquals(expected, server.takeRequest().getHeader("User-Agent"))
                }
                val before = store.save(LiteSettings())
                request(identity)
                store.save(before.copy(requests = before.requests.copy(globalUserAgent = "Saved/3")))
                request("Saved/3")
                store.deferPublication { store.save(before) }
                request(identity)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun rawDownloadPreservesBinaryAndSameResponseHeadersWhileTextWrapperStaysStrict() = runBlocking<Unit> {
        val downloader = SubscriptionDownloader()
        val bytes = byteArrayOf(0xff.toByte(), 0x00, 0x80.toByte(), 0x41)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes))
                .setHeader("subscription-userinfo", "upload=123").setHeader("content-disposition", "attachment; filename=private.age"))
            val raw = downloader.downloadRaw(server.url("/binary").toString())
            assertArrayEquals(bytes, raw.bytes)
            assertEquals("upload=123", raw.subscriptionUserInfo)
            assertEquals("attachment; filename=private.age", raw.contentDisposition)
            assertFalse(raw.toString().contains("upload=123"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            assertThrows(java.nio.charset.CharacterCodingException::class.java) {
                runBlocking { downloader.download(server.url("/text").toString()) }
            }
        }
    }

    @Test fun requestCodecRejectsUnknownFieldsNullContainersAndHeaderInjection() {
        for (row in listOf(JSONObject().put("extra", 1), JSONObject().put("userAgents", JSONObject.NULL),
            JSONObject().put("checkCertificate", JSONObject.NULL), JSONObject().put("globalUserAgent", "x\r\ny"))) {
            assertThrows(IllegalArgumentException::class.java) { RequestPreferencesJson.decode(row) }
        }
    }
}

package dev.aerove.lite

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSourceAdmissionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun admit(source: String): String? = runBlocking {
        JniResourceFetch.offlineRead { MeowNativeCore.validateSource(it, source) }
    }

    @Test
    fun providerDeclarationsNeedNoDownloadButPreparedRuntimeStillNeedsRealResources() = runBlocking {
        assertTrue(Build.VERSION.SDK_INT >= 33)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { counter ->
            counter.soTimeout = 100
            val missing = File(context.cacheDir, "source-admission-missing-${UUID.randomUUID()}")
            val source = """
                ipv6: false
                proxy-providers:
                  remote: {type: http, url: 'http://127.0.0.1:${counter.localPort}/nodes', path: '${File(missing, "nodes.yaml").path}', interval: 3600}
                rule-providers:
                  domains: {type: http, behavior: domain, url: 'http://127.0.0.1:${counter.localPort}/rules', path: '${File(missing, "rules.yaml").path}', interval: 3600}
                proxy-groups: [{name: PICK, type: select, use: [remote]}]
                rules: ['RULE-SET,domains,PICK', 'MATCH,DIRECT']
            """.trimIndent()
            val original = source.toByteArray().toList()
            assertNull(JniResourceFetch.offlineRead { MeowNativeCore.validateSource(it, source) })
            assertEquals(original, source.toByteArray().toList())
            assertFalse(missing.exists())
            try {
                counter.accept().use { fail("Source admission attempted a provider request") }
            } catch (_: SocketTimeoutException) { }

            val bundle = File(context.filesDir, "runtime/resources/bundles/source-admission-${UUID.randomUUID()}")
            check(bundle.mkdirs())
            try {
                val runtime = """
                    proxy-providers: {remote: {type: file, path: '${File(bundle, "missing-nodes.yaml").path}'}}
                    proxy-groups: [{name: PICK, type: select, use: [remote]}]
                    rules: ['GEOSITE,cn,PICK', 'MATCH,PICK']
                """.trimIndent()
                assertNull(JniResourceFetch.offlineRead { MeowNativeCore.validateSource(it, runtime) })
                val diagnostic = JniResourceFetch.preparedValidator(context).validate(runtime, bundle)
                assertNotNull(diagnostic)
                assertTrue(diagnostic!!.contains("$.geodata.geosite-path"))
            } finally {
                assertTrue(bundle.deleteRecursively())
            }
        }
    }

    @Test
    fun actualJniPreservesBooleanTypesDiagnosticsAndOwnerLifetime() {
        for (value in listOf("true", "false")) {
            assertNull(admit("ipv6: $value\nrules: ['MATCH,DIRECT']"))
        }
        for (source in listOf(
            "ipv6: [false]",
            "unknown-root: true",
            "proxy-groups: [{name: PICK, type: select, use: [missing]}]",
            "rules: ['RULE-SET,missing,DIRECT']",
            "rules: ['MATCH,missing']",
            "rules: ['IP-CIDR,broken,DIRECT']",
            "proxy-groups: [{name: A, type: select, proxies: [B]}, {name: B, type: select, proxies: [A]}]",
        )) assertNotNull(source, admit(source))
        var endedHandle = 0L
        runBlocking {
            JniResourceFetch.offlineRead { handle ->
                endedHandle = handle
                assertNull(MeowNativeCore.validateSource(handle, "rules: ['MATCH,DIRECT']"))
            }
        }
        try {
            MeowNativeCore.validateSource(endedHandle, "rules: ['MATCH,DIRECT']")
            fail("Closed source owner was reused")
        } catch (failure: RuntimeException) {
            assertTrue(failure.message.orEmpty().contains("invalid resource scope handle"))
        }
    }
}

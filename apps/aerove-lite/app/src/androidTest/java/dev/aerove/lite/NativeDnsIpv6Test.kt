package dev.aerove.lite

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDnsIpv6Test {
    @Test
    fun actualJniParserAcceptsDnsIpv6BooleansAndOmissionButRejectsInvalidTypes() = runBlocking {
        assertTrue(Build.VERSION.SDK_INT >= 33)
        for (root in listOf(true, false)) for (dns in listOf("true", "false", null)) {
            val field = dns?.let { "  ipv6: $it\n" }.orEmpty()
            val yaml = "strict: true\nipv6: $root\ndns:\n  enable: true\n${field}  use-system-hosts: false\n  nameserver: ['udp://1.1.1.1']\nrules: ['MATCH,DIRECT']\n"
            assertNull("root=$root dns=$dns", JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) })
        }
        for (value in listOf("'true'", "1", "[]", "{}")) {
            val yaml = "strict: true\nipv6: true\ndns: {enable: true, ipv6: $value, nameserver: ['udp://1.1.1.1']}\nrules: ['MATCH,DIRECT']\n"
            assertNotNull("dns.ipv6=$value", JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) })
        }
    }
}

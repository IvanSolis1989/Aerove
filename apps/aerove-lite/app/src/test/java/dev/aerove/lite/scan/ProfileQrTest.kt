package dev.aerove.lite.scan

import org.junit.Assert.*
import org.junit.Test

class ProfileQrTest {
    @Test fun acceptsFullWebAndShareSchemes() {
        for (value in listOf("https://example.com/p?a=1", "http://localhost:8080/config", "ftp://example.com/p", "https://[::1]:443/p", "https://例子.测试/p", "HTTP://example.com/p")) {
            assertEquals(value, ProfileQr.parse(" $value "))
        }
        for (scheme in listOf("hysteria", "hysteria2", "hy2", "hysteria2+realm", "hy2+realm", "tuic", "trojan", "vless", "vmess", "ss", "ssr", "socks", "socks5", "socks5h", "anytls", "mierus")) {
            assertEquals("$scheme://payload", ProfileQr.parse("$scheme://payload"))
        }
        assertEquals("VMESS://payload", ProfileQr.parse("VMESS://payload"))
    }

    @Test fun unwrapsInstallConfigOnlyForFullSchemesAndWebUrls() {
        for (scheme in listOf("clash", "clashmeta", "flclash")) {
            assertEquals("https://example.com/p?name=a+b", ProfileQr.parse("$scheme://install-config?url=https%3A%2F%2Fexample.com%2Fp%3Fname%3Da%2Bb&name=foo"))
        }
        assertNull(ProfileQr.parse("other://install-config?url=https%3A%2F%2Fexample.com"))
        assertNull(ProfileQr.parse("clash://other?url=https%3A%2F%2Fexample.com"))
        assertNull(ProfileQr.parse("clash://install-config?url=ss%3A%2F%2Fpayload"))
    }

    @Test fun ignoresEmptyMalformedAndNonProfileInput() {
        for (value in listOf(null, "", "  ", "https://", "https:///path", "https://:443", "https://host:bad", "https://host:99999", "https://[broken", "https://host/%zz", "https://host/\nother", "ss://", "ss://a\nb", "file:///a", "hello", "clash://install-config?url=%zz", "clash://install-config?url=", "clash://install-config")) {
            assertNull("Unexpected profile: $value", ProfileQr.parse(value))
        }
        assertNull(ProfileQr.parse("a".repeat(65537)))
        assertEquals("https://example.com/p", ProfileQr.first(listOf(null, "bad", "https://example.com/p", "ss://next")))
    }

    @Test fun emitsOnlyOneSuccessfulResultAcrossCameraAndAlbum() {
        val gate = ScanResultGate()
        assertEquals(ScanMatch.Ignored, gate.accept(emptyList()))
        assertEquals(ScanMatch.Invalid, gate.accept(listOf("bad")))
        assertEquals(ScanMatch.Ignored, gate.accept(listOf("bad")))
        assertEquals(ScanMatch.Found("https://example.com"), gate.accept(listOf("https://example.com")))
        assertEquals(ScanMatch.Ignored, gate.accept(listOf("ss://different")))
        assertFalse(gate.close())
    }

    @Test fun closingSuppressesAllLateCallbacks() {
        val gate = ScanResultGate()
        assertTrue(gate.close())
        assertFalse(gate.close())
        assertEquals(ScanMatch.Ignored, gate.accept(listOf("https://example.com")))
    }
}

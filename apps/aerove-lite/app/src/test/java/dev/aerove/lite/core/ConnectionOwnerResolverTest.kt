package dev.aerove.lite.core

import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class ConnectionOwnerResolverTest {
    private val source = InetSocketAddress("10.0.0.2", 12000)
    private val target = InetSocketAddress("1.1.1.1", 443)

    @Test fun usesOriginalEndpointsAndStandardFirstPackage() {
        var count = 0
        val resolver = ConnectionOwnerResolver({ protocol, local, remote ->
            assertEquals(17, protocol); assertEquals(source, local); assertEquals(target, remote); 10042
        }, { count++; arrayOf("dev.first", "dev.second") }, { true }, { false })
        repeat(2) { assertEquals(ConnectionOwnerResolver.Identity(10042, "dev.first", ""), resolver.resolve(17, source, target)) }
        assertEquals(1, count)
        resolver.clearPackageCache(); resolver.resolve(17, source, target); assertEquals(2, count)
    }

    @Test fun unknownUidMainThreadRevocationAndSecurityNeverBecomeRoot() {
        var admitted = true
        var main = false
        var calls = 0
        val resolver = ConnectionOwnerResolver({ _, _, _ -> calls++; -1 }, { fail("unknown UID has no package lookup"); null }, { admitted }, { main })
        assertNull(resolver.resolve(6, source, target))
        admitted = false; assertNull(resolver.resolve(6, source, target))
        admitted = true; main = true; assertNull(resolver.resolve(6, source, target)); assertEquals(1, calls)
        val denied = ConnectionOwnerResolver({ _, _, _ -> throw SecurityException("revoked") }, { null }, { true }, { false })
        assertNull(denied.resolve(6, source, target))
    }

    @Test fun revokeDuringLookupAndEmptyPackageFollowStandard() {
        var admitted = true
        val revoked = ConnectionOwnerResolver({ _, _, _ -> admitted = false; 10042 }, { arrayOf("wrong") }, { admitted }, { false })
        assertNull(revoked.resolve(6, source, target))
        val noPackage = ConnectionOwnerResolver({ _, _, _ -> 10042 }, { arrayOf("", "must.not.select.second") }, { true }, { false })
        assertEquals("", noPackage.resolve(6, source, target)?.process)
    }

    @Test fun numericEndpointRejectsHostnamesAndAcceptsIpv6() {
        assertNull(ConnectionOwnerResolver.numericEndpoint("example.com:443"))
        assertNull(ConnectionOwnerResolver.numericEndpoint("1.1.1.1:0"))
        assertEquals(443, ConnectionOwnerResolver.numericEndpoint("[2001:db8::1]:443")?.port)
        assertEquals(443, ConnectionOwnerResolver.numericEndpoint("1.1.1.1:443")?.port)
    }
}

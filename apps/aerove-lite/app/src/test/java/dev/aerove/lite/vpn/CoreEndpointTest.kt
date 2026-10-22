package dev.aerove.lite.vpn

import dev.aerove.lite.data.AppliedConfigSnapshot
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoreEndpointTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun originalKotlinAndJvmTwoArgumentConstructorsRemainAvailable() {
        val kotlin = CoreEndpoint(49152, "legacy-secret")
        assertEquals(49152, kotlin.port)
        assertEquals("legacy-secret", kotlin.secret)
        assertNull(kotlin.configuration)
        val java = CoreEndpoint::class.java.getConstructor(Int::class.javaPrimitiveType, String::class.java)
            .newInstance(49153, "java-secret")
        assertEquals(49153, java.port)
        assertNull(java.configuration)
    }

    @Test fun optionalSnapshotHasIdentityButDoesNotAppearInEndpointOrStateStrings() {
        val file = File(temporary.root, "private-runtime.yaml")
        val snapshot = AppliedConfigSnapshot("private-profile", file)
        val endpoint = CoreEndpoint(49152, "private-secret", snapshot)
        assertSame(snapshot, endpoint.configuration)
        assertEquals("private-profile", endpoint.configuration!!.profileId)
        assertFalse(file.exists())
        assertEquals("CoreEndpoint(127.0.0.1:49152, secret=<redacted>)", endpoint.toString())
        assertEquals("AppliedConfigSnapshot(<redacted>)", snapshot.toString())
        for (value in listOf("private-profile", "private-runtime", "private-secret", "AppliedConfigSnapshot")) {
            assertFalse(endpoint.toString().contains(value))
            assertFalse(VpnSnapshot(endpoint = endpoint).toString().contains(value))
        }
    }
}

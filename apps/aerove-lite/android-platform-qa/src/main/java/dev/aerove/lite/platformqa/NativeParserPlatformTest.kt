package dev.aerove.lite.platformqa

import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Existing NativeSmoke parser case, separated from its removed MainActivity TUN entry. */
class NativeParserPlatformTest {
    @Test fun nativeParserRejectsUnknownConfiguration() {
        fun validate(yaml: String) = runBlocking { JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) } }
        assertNotNull(validate("unknown-aerove-field: true\n"))
        assertNull(validate("mode: direct\nrules: ['MATCH,DIRECT']\n"))
    }
}

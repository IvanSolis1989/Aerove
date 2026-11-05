package dev.aerove.lite.platformqa

import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.ConfigValidationException
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowProfileValidator
import dev.aerove.lite.data.NativeConfigValidator
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Existing NativeSmoke parser case, separated from its removed MainActivity TUN entry. */
class NativeParserPlatformTest {
    @Test fun nativeParserRejectsUnknownConfiguration() {
        fun validate(yaml: String) = runBlocking { JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) } }
        assertNotNull(validate("unknown-aerove-field: true\n"))
        assertNull(validate("mode: direct\nrules: ['MATCH,DIRECT']\n"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "native-parser-source-").toFile()
        try {
            var nativeCalls = 0
            val validator = MeowProfileValidator(NativeConfigValidator { yaml ->
                nativeCalls++
                assertEquals(mapOf("enable" to false, "auto-route" to false), ConfigYaml.parse(yaml)["tun"])
                JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) }
            }, JniResourceFetch.preparedValidator(context), directory, MeowNativeCore::readVerifiedFeatures)
            val source = "mode: direct\ntun: {enable: true, stack: lwip}\nrules: ['MATCH,DIRECT']\n"
            runBlocking {
                validator.validate(source, false, null, sourceAdmission = true)
                assertEquals(1, nativeCalls)
                for (managed in listOf(false, true)) {
                    try {
                        validator.validate(source, managed, null, sourceAdmission = managed)
                        fail("Runtime TUN requires its actual Builder receipt")
                    } catch (failure: ConfigValidationException) {
                        assertEquals("$.tun.enable", failure.issues.single().path)
                    }
                }
                assertEquals(1, nativeCalls)
            }
        } finally { assertTrue(directory.deleteRecursively()) }
    }
}

package dev.aerove.lite.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val runtime get() = File(temporary.root, "runtime")
    private val simple = "rules: ['MATCH,DIRECT']"

    private fun repository(native: NativeResourceValidator = NativeResourceValidator { _, _, _, _ -> null }): ProfileRepository {
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        return ProfileRepository(temporary.root, validator, resources = ResourceBundleManager(runtime, validator, native))
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun reject(bytes: ByteArray) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository().importFile("Rejected", ByteArrayInputStream(bytes)) } }
        assertTrue(File(runtime, "resources/imports").listFiles().orEmpty().isEmpty())
        assertTrue(File(runtime, "resources/archives").listFiles().orEmpty().isEmpty())
        assertTrue(runBlocking { repository().list() }.isEmpty())
    }

    private fun central(bytes: ByteArray): Int = (0..bytes.size - 46).first {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
    }

    @Test fun firstImportCanIncludeLocalRulesAndProviderTlsWithoutAnExistingProfileId() = runBlocking<Unit> {
        val source = """
            proxy-providers:
              local: {type: file, path: proxies/local.yaml}
            rule-providers:
              local: {type: file, path: rules/local.yaml, behavior: domain}
            proxy-groups: [{name: Auto, type: select, use: [local]}]
            rules: ['RULE-SET,local,Auto', 'MATCH,Auto']
        """.trimIndent()
        var loadedProxy = false
        val repo = repository(NativeResourceValidator { kind, path, _, _ ->
            if (kind == "proxy-provider") {
                val proxy = (ConfigYaml.parse(File(path).readText())["proxies"] as List<*>).single() as Map<*, *>
                val cert = File(proxy["tls-cert"] as String)
                assertTrue(cert.isAbsolute && cert.isFile)
                assertFalse(cert.path.contains(".pending-"))
                assertEquals("test-certificate-fixture", cert.readText())
                loadedProxy = true
            }
            null
        })
        val bytes = archive("config.yaml" to source, "rules/local.yaml" to "payload: ['+.example.com']",
            "proxies/local.yaml" to "proxies: [{name: node, type: trojan, server: example.com, port: 443, password: secret, tls-cert: certs/client.pem}]",
            "certs/client.pem" to "test-certificate-fixture")
        val profile = repo.importFile("ZIP", ByteArrayInputStream(bytes))
        val prepared = repo.prepareForStart(profile.id)
        assertEquals(source, repo.readConfig(profile.id))
        assertTrue(loadedProxy)
        assertTrue(File(runtime, "resources/imports/${profile.id}/rules/local.yaml").isFile)
        assertEquals(0, File(runtime, "resources/archives").listFiles()!!.size)
        val lease = repo.retain(prepared)
        repo.delete(profile.id)
        assertTrue(prepared.resourceDirectory!!.isDirectory)
        repo.release(lease)
        assertFalse(File(runtime, "resources/imports/${profile.id}").exists())
    }

    @Test fun nativeFailureDoesNotPublishSourceOrLeaveExtractedImports() = runBlocking<Unit> {
        val repo = repository(NativeResourceValidator { _, _, _, _ -> "actual loader rejection fixture" })
        repo.importText("Existing", simple)
        val index = File(temporary.root, "profiles/index.json")
        val before = index.readBytes()
        val source = "rule-providers: {local: {type: file, path: local.yaml, behavior: domain}}"
        assertThrows(ConfigValidationException::class.java) {
            runBlocking { repo.importFile("Bad", ByteArrayInputStream(archive("config.yaml" to source, "local.yaml" to "payload: ['+.example.com']"))) }
        }
        assertArrayEquals(before, index.readBytes())
        assertTrue(File(runtime, "resources/imports").listFiles().orEmpty().isEmpty())
        assertTrue(File(runtime, "resources/archives").listFiles().orEmpty().isEmpty())
        assertEquals(1, repo.list().size)
    }

    @Test fun rootProxyTlsFilesAlsoReachHandlerValidationBeforePublish() = runBlocking<Unit> {
        var loaded = false
        val repo = repository(NativeResourceValidator { kind, path, _, _ ->
            assertEquals("proxy-provider", kind)
            val proxy = (ConfigYaml.parse(File(path).readText())["proxies"] as List<*>).single() as Map<*, *>
            assertEquals("root-certificate-fixture", File(proxy["tls-cert"] as String).readText())
            loaded = true
            "TLS loader failure fixture"
        })
        val source = "proxies: [{name: node, type: trojan, server: example.com, port: 443, password: secret, tls-cert: certs/client.pem}]"
        assertThrows(ConfigValidationException::class.java) {
            runBlocking { repo.importFile("Root TLS", ByteArrayInputStream(archive("config.yaml" to source, "certs/client.pem" to "root-certificate-fixture"))) }
        }
        assertTrue(loaded)
        assertTrue(repo.list().isEmpty())
    }

    @Test fun traversalAbsolutePathsAndFileDirectoryCollisionsAreRefused() {
        listOf("../escape", "/absolute", "C:/absolute", "a/../../escape", "a\\escape").forEach { path ->
            reject(archive("config.yaml" to simple, path to "unsafe"))
        }
        reject(archive("config.yaml" to simple, "rules" to "file", "rules/local.yaml" to "nested"))
        assertFalse(File(temporary.root, "escape").exists())
    }

    @Test fun duplicateCasePathsAndSymlinkModeAreRefused() {
        reject(archive("config.yaml" to simple, "rules.yaml" to "one", "RULES.yaml" to "two"))
        val linked = archive("config.yaml" to simple)
        put32(linked, central(linked) + 38, 0xa1ff0000L)
        reject(linked)
    }

    @Test fun oversizedBombMetadataAndExcessiveEntriesAreRefusedBeforeExtraction() {
        val oversized = archive("config.yaml" to simple)
        put32(oversized, central(oversized) + 24, 65L * 1024 * 1024)
        reject(oversized)
        val many = (0 until 256).map { "resource$it" to "x" }
        reject(archive("config.yaml" to simple, *many.toTypedArray()))
    }

    @Test fun missingRootConfigTruncatedZipAndCrcMismatchAreRefused() {
        reject(archive("folder/config.yaml" to simple))
        reject(archive("config.yaml" to simple).dropLast(8).toByteArray())
        val corrupt = archive("config.yaml" to simple)
        put32(corrupt, central(corrupt) + 16, 0)
        reject(corrupt)
    }

    @Test fun regularYamlStillImportsByContentMagic() = runBlocking<Unit> {
        val repo = repository()
        val profile = repo.importFile("Named zip but YAML", ByteArrayInputStream(simple.toByteArray()))
        assertEquals(simple, repo.readConfig(profile.id))
        assertNull(profile.url)
    }
}

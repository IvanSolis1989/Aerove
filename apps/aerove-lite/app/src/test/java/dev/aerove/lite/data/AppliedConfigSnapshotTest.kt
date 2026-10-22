package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppliedConfigSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsTheWrittenComposedConfigRatherThanAnUpdatedProfileSource() = runBlocking<Unit> {
        val source = temporary.newFile("profile.yaml").apply { writeText("mode: rule\nrules: [\"MATCH,DIRECT\"]\n") }
        val settings = LiteSettings(mode = ProxyMode.Global, ipv6 = true, overrideDns = true, dnsServers = listOf("1.1.1.1"))
        val secret = "0123456789abcdef0123456789abcdef"
        val composed = RuntimeConfigComposer(temporary.root).compose(source.readText(), settings, 42, 49152, secret)
        val runtime = temporary.newFile("runtime.yaml").apply { writeText(composed, Charsets.UTF_8) }
        val snapshot = AppliedConfigSnapshot("active-profile", runtime)
        source.writeText("mode: direct\n")
        val actual = snapshot.read()
        assertEquals("active-profile", snapshot.profileId)
        assertEquals(composed, actual)
        assertNotEquals(source.readText(), actual)
        val parsed = ConfigYaml.parse(actual)
        assertEquals("global", parsed["mode"])
        assertEquals("127.0.0.1:49152", parsed["external-controller"])
        assertEquals(secret, parsed["secret"])
        assertEquals("fd://42", (parsed["tun"] as Map<*, *>)["device-id"])
    }

    @Test fun constructionDoesNotReadAndActualFileOpenRunsOffTheCallingThread() = runBlocking<Unit> {
        val actual = File(temporary.root, "not-written-yet.yaml")
        val openedOn = AtomicLong(-1)
        val file = object : File(actual.path) {
            override fun getPath(): String {
                openedOn.set(Thread.currentThread().id)
                return super.getPath()
            }
        }
        val callingThread = Thread.currentThread().id
        val snapshot = AppliedConfigSnapshot("active-profile", file)
        assertEquals(-1, openedOn.get())
        assertFalse(actual.exists())
        actual.writeText("mode: rule\n", Charsets.UTF_8)
        assertEquals("mode: rule\n", snapshot.read())
        assertTrue(openedOn.get() >= 0)
        assertNotEquals(callingThread, openedOn.get())
    }

    @Test fun utf8BomAndUnicodeAreDecodedWithoutReplacementCharacters() = runBlocking<Unit> {
        val file = temporary.newFile("unicode.yaml")
        file.writeBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "name: 中文 😀\r\n".toByteArray(Charsets.UTF_8))
        assertEquals("name: 中文 😀\r\n", AppliedConfigSnapshot("active-profile", file).read())
    }

    @Test fun malformedAndTruncatedUtf8AreExplicitFailures() {
        val file = temporary.newFile("invalid.yaml")
        val snapshot = AppliedConfigSnapshot("active-profile", file)
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xe4.toByte(), 0xb8.toByte()))) {
            file.writeBytes(bytes)
            assertThrows(CharacterCodingException::class.java) { runBlocking { snapshot.read() } }
        }
    }

    @Test fun exactByteLimitIsAcceptedAndOneMoreByteIsRejected() = runBlocking<Unit> {
        val file = temporary.newFile("large.yaml")
        val text = "#" + "x".repeat(MAX_PROFILE_BYTES - 2) + "\n"
        file.writeText(text, Charsets.UTF_8)
        val snapshot = AppliedConfigSnapshot("active-profile", file)
        assertEquals(text, snapshot.read())
        RandomAccessFile(file, "rw").use { it.setLength(MAX_PROFILE_BYTES.toLong() + 1) }
        val failure = assertThrows(IOException::class.java) { runBlocking { snapshot.read() } }
        assertTrue(failure.message!!.contains("exceeds"))
    }

    @Test fun multibyteLimitIsMeasuredInBytes() {
        val file = temporary.newFile("multibyte.yaml")
        val text = "中".repeat(MAX_PROFILE_BYTES / 3 + 1)
        assertTrue(text.length < MAX_PROFILE_BYTES)
        file.writeText(text, Charsets.UTF_8)
        assertThrows(IOException::class.java) { runBlocking { AppliedConfigSnapshot("active-profile", file).read() } }
    }

    @Test fun deletedSessionFileNeverFallsBackToCachedOrEmptyText() = runBlocking<Unit> {
        val file = temporary.newFile("stopped-session.yaml").apply { writeText("mode: rule\n") }
        val snapshot = AppliedConfigSnapshot("active-profile", file)
        assertEquals("mode: rule\n", snapshot.read())
        assertTrue(file.delete())
        val failure = assertThrows(IOException::class.java) { runBlocking { snapshot.read() } }
        assertEquals("Applied runtime configuration is no longer available", failure.message)
    }

    @Test fun missingAndEmptyFilesFailWithoutManufacturingAConfiguration() {
        val absent = File(temporary.root, "missing.yaml")
        assertThrows(IOException::class.java) { runBlocking { AppliedConfigSnapshot("active-profile", absent).read() } }
        val file = temporary.newFile("empty.yaml")
        for (text in listOf("", "\uFEFF", " \r\n\t")) {
            file.writeText(text, Charsets.UTF_8)
            val failure = assertThrows(IOException::class.java) { runBlocking { AppliedConfigSnapshot("active-profile", file).read() } }
            assertEquals("Applied runtime configuration is empty", failure.message)
        }
    }
}

package dev.aerove.lite.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val valid = "mode: rule\nrules:\n - MATCH,DIRECT"
    private val validator = ProfileValidator {
        if (it.startsWith("<")) throw ConfigValidationException(listOf(ConfigIssue("$", "Not a configuration")))
    }

    @Test fun subscriptionImportAndRefreshUseActualResponseBodies() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.enqueue(MockResponse().setBody(valid.replace("rule", "global")))
            val repo = ProfileRepository(temporary.root, validator)
            val profile = repo.addSubscription("Remote", server.url("/subscription?key=private").toString())
            assertEquals(valid, repo.readConfig(profile.id))
            repo.update(profile.id)
            assertEquals(valid.replace("rule", "global"), repo.readConfig(profile.id))
            assertEquals(valid, repo.readPreviousConfig(profile.id))
            assertEquals(profile.url, repo.list().single().url)
        }
    }

    @Test fun failedHttpAndInvalidConfigCannotOverwriteLastProfile() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(valid))
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("<html>subscription expired</html>"))
            val repo = ProfileRepository(temporary.root, validator)
            val profile = repo.addSubscription("Remote", server.url("/subscription").toString())
            assertThrows(IOException::class.java) { runBlocking { repo.update(profile.id) } }
            assertThrows(ConfigValidationException::class.java) { runBlocking { repo.update(profile.id) } }
            assertEquals(valid, repo.readConfig(profile.id))
            assertEquals(listOf(profile), repo.list())
        }
    }

    @Test fun boundedInputRefusesOversizedResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(MAX_PROFILE_BYTES + 1)))
            assertThrows(IOException::class.java) {
                runBlocking { SubscriptionDownloader().download(server.url("/large").toString()) }
            }
        }
    }

    @Test fun redirectStaysHttpAndOriginalUrlRemainsTheUpdateSource() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
            server.enqueue(MockResponse().setBody(valid))
            val repo = ProfileRepository(temporary.root, validator)
            val source = server.url("/source").toString()
            val profile = repo.addSubscription("Redirect", source)
            assertEquals(source, profile.url)
            assertEquals(valid, repo.readConfig(profile.id))
        }
    }

    @Test fun credentialsFragmentsAndNonHttpUrlsAreRejected() {
        listOf("file:///private/config", "https://user:pass@example.com/list", "https://example.com/list#part").forEach {
            assertThrows(IllegalArgumentException::class.java) { SubscriptionDownloader.parseUrl(it) }
        }
    }
}

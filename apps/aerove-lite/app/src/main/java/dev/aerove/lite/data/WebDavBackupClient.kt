package dev.aerove.lite.data

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

class WebDavBackupConfig(val url: String, val user: String, val password: String, val fileName: String = "aerove-lite.zip") {
    init {
        baseUrl(url)
        require(user.length <= 1024 && ':' !in user && user.none(Char::isISOControl) &&
            password.length <= 8192 && password.none(Char::isISOControl)) { "Invalid WebDAV credentials" }
        backupName(fileName)
    }

    internal fun encode(): String = JSONObject().put("url", url).put("user", user).put("password", password).put("fileName", fileName).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size <= 16384) { "WebDAV settings are too large" }
    }
    override fun toString(): String = "WebDavBackupConfig(credentials=<redacted>)"

    companion object {
        internal fun decode(value: String): WebDavBackupConfig {
            try {
                val json = JSONObject(value)
                return WebDavBackupConfig(json.getString("url"), json.getString("user"), json.getString("password"), json.getString("fileName"))
            } catch (_: Exception) { throw IllegalArgumentException("Invalid WebDAV backup settings") }
        }
    }
}

data class WebDavBackupEntry(val name: String, val bytes: Long?, val modified: String?, val etag: String?)

class WebDavBackupException(val operation: String, val statusCode: Int? = null) :
    IOException("WebDAV $operation failed${statusCode?.let { " (HTTP $it)" } ?: ""}")

class WebDavBackupClient(
    private val config: WebDavBackupConfig,
    client: OkHttpClient = OkHttpClient(),
    collectionName: String = "AEROVE-Lite",
    private val maxArchiveBytes: Long = 256L * 1024 * 1024,
) {
    private val base = baseUrl(config.url)
    private val collection = base.newBuilder().addPathSegment(backupName(collectionName)).addPathSegment("").build()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    init { require(maxArchiveBytes in 1..256L * 1024 * 1024) { "Invalid WebDAV archive limit" } }

    suspend fun ping() { exchange("OPTIONS", base).close() }

    suspend fun list(): List<WebDavBackupEntry> = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
        exchange("PROPFIND", collection, body.toRequestBody("application/xml; charset=utf-8".toMediaType()), mapOf("Depth" to "1")).use { response ->
            val xml = consuming(response) { response.body?.byteStream()?.use { it.readBounded(2 * 1024 * 1024).strictUtf8() }
                ?: throw WebDavBackupException("PROPFIND") }
            parseListing(xml)
        }
    }

    suspend fun upload(source: File, name: String = config.fileName, overwrite: Boolean = true) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath()) && source.length() in 1..maxArchiveBytes) { "Invalid backup upload file" }
        exchange("MKCOL", collection, accepted = setOf(200, 201, 204, 405)).close()
        exchange("PUT", fileUrl(name), source.asRequestBody("application/zip".toMediaType()),
            if (overwrite) emptyMap() else mapOf("If-None-Match" to "*")).close()
    }

    suspend fun download(destination: File, name: String = config.fileName) = withContext(Dispatchers.IO) {
        val target = destination.absoluteFile
        require(!Files.isSymbolicLink(target.toPath())) { "Unsafe download destination" }
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val temporary = File(target.parentFile, ".dav-${UUID.randomUUID()}")
        try {
            exchange("GET", fileUrl(name), accepted = setOf(200)).use { response ->
                val body = response.body ?: throw WebDavBackupException("GET")
                require(body.contentLength() <= maxArchiveBytes) { "WebDAV backup is too large" }
                consuming(response) { body.byteStream().use { copyResource(it, temporary, maxArchiveBytes) } }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WebDavBackupException) { throw failure }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); throw WebDavBackupException("GET") }
        finally { temporary.delete() }
    }

    suspend fun delete(name: String = config.fileName) { exchange("DELETE", fileUrl(name), accepted = setOf(200, 202, 204)).close() }

    private fun fileUrl(name: String): HttpUrl = collection.newBuilder().addPathSegment(backupName(name)).build()

    private suspend fun exchange(
        method: String,
        origin: HttpUrl,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        accepted: Set<Int> = setOf(200, 201, 204, 207),
    ): Response {
        var target = origin
        var challenge: String? = null
        var authAttempts = 0
        var redirects = 0
        try {
            while (true) {
                val authorization = if (config.user.isEmpty() && config.password.isEmpty()) null
                else if (challenge == null) Credentials.basic(config.user, config.password, Charsets.UTF_8)
                else digestAuthorization(method, target, challenge, ++authAttempts)
                val request = Request.Builder().url(target).method(method, body)
                    .header("User-Agent", "AEROVE-Lite").apply {
                        if (authorization != null) header("Authorization", authorization)
                        headers.forEach { (key, value) -> header(key, value) }
                    }.build()
                val response = http.newCall(request).awaitResponse()
                if (response.code in accepted) return response
                if (response.code == 401 && authorization != null && authAttempts < 2) {
                    val digest = response.headers("WWW-Authenticate").firstOrNull { it.startsWith("Digest ", ignoreCase = true) }
                    if (digest != null) { challenge = digest; response.close(); continue }
                }
                if (response.code in setOf(301, 302, 303, 307, 308) && redirects++ < 5) {
                    val next = response.header("Location")?.let(target::resolve)
                    response.close()
                    require(next != null && next.isHttps && next.host == base.host && next.port == base.port &&
                        next.username.isEmpty() && next.password.isEmpty() && next.query == null && next.fragment == null &&
                        (next.encodedPath.removeSuffix("/") == origin.encodedPath.removeSuffix("/") ||
                            next.encodedPath.startsWith(collection.encodedPath))) { "Unsafe WebDAV redirect" }
                    baseUrl(next.toString())
                    target = next
                    continue
                }
                val status = response.code
                response.close()
                throw WebDavBackupException(method, status)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: WebDavBackupException) { throw failure }
        catch (_: Exception) { throw WebDavBackupException(method) }
    }

    private suspend fun <T> consuming(response: Response, action: suspend () -> T): T = coroutineScope {
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { response.close() }
        }
        try { action() } finally { cancellation.cancel() }
    }

    private fun parseListing(xml: String): List<WebDavBackupEntry> {
        try {
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "Unsafe XML declaration" }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw IllegalArgumentException("External XML entity forbidden") }
                setErrorHandler(object : ErrorHandler {
                    override fun warning(error: SAXParseException) { throw error }
                    override fun error(error: SAXParseException) { throw error }
                    override fun fatalError(error: SAXParseException) { throw error }
                })
            }
            val document = builder.parse(InputSource(xml.reader()))
            require(document.documentElement.localName == "multistatus" && document.documentElement.namespaceURI == "DAV:")
            val responses = document.getElementsByTagNameNS("DAV:", "response")
            require(responses.length <= 2000) { "Too many WebDAV entries" }
            val result = linkedMapOf<String, WebDavBackupEntry>()
            for (index in 0 until responses.length) {
                val element = responses.item(index) as Element
                fun text(name: String): String? = element.getElementsByTagNameNS("DAV:", name).item(0)?.textContent
                val href = text("href") ?: continue
                val resolved = collection.resolve(href) ?: throw IllegalArgumentException("Invalid WebDAV href")
                require(resolved.isHttps && resolved.host == base.host && resolved.port == base.port &&
                    resolved.username.isEmpty() && resolved.password.isEmpty() && resolved.query == null && resolved.fragment == null)
                if (resolved.encodedPath.removeSuffix("/") == collection.encodedPath.removeSuffix("/")) continue
                require(resolved.encodedPath.startsWith(collection.encodedPath))
                if (element.getElementsByTagNameNS("DAV:", "collection").length > 0) continue
                val name = resolved.pathSegments.last()
                backupName(name)
                require(fileUrl(name).encodedPath == resolved.encodedPath) { "WebDAV entry escapes the collection" }
                val status = text("status") ?: continue
                if (!Regex("HTTP/\\S+ 2\\d\\d(?: .*)?").matches(status.trim())) continue
                val size = text("getcontentlength")?.trim()?.toLong()?.also { require(it >= 0) }
                require(result.put(name, WebDavBackupEntry(name, size, text("getlastmodified"), text("getetag"))) == null) { "Duplicate WebDAV entry" }
            }
            return result.values.sortedBy { it.name }
        } catch (_: Exception) { throw WebDavBackupException("PROPFIND") }
    }

    private fun digestAuthorization(method: String, url: HttpUrl, challenge: String, count: Int): String {
        val values = Regex("(\\w+)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^,\\s]+))").findAll(challenge.substringAfter(' '))
            .associate { it.groupValues[1].lowercase(Locale.ROOT) to (it.groups[2]?.value ?: it.groupValues[3]).replace(Regex("\\\\(.)"), "$1") }
        val algorithm = values["algorithm"] ?: "MD5"
        require(algorithm.uppercase(Locale.ROOT) in setOf("MD5", "SHA-256")) { "Unsupported WebDAV authentication" }
        val realm = values["realm"] ?: error("Missing digest realm")
        val nonce = values["nonce"] ?: error("Missing digest nonce")
        val qop = values["qop"]?.split(',')?.map(String::trim)
        require(qop == null || "auth" in qop) { "Unsupported WebDAV authentication" }
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        fun hash(value: String): String = MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8)).hex()
        fun quote(value: String): String {
            require(value.none(Char::isISOControl))
            return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        val nc = count.toString(16).padStart(8, '0')
        val cnonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
        val first = hash("${config.user}:$realm:${config.password}")
        val second = hash("$method:$path")
        val response = hash(if (qop == null) "$first:$nonce:$second" else "$first:$nonce:$nc:$cnonce:auth:$second")
        return "Digest " + listOfNotNull("username=${quote(config.user)}", "realm=${quote(realm)}", "nonce=${quote(nonce)}",
            "uri=${quote(path)}", "response=${quote(response)}", "algorithm=$algorithm", values["opaque"]?.let { "opaque=${quote(it)}" },
            if (qop != null) "qop=auth, nc=$nc, cnonce=${quote(cnonce)}" else null).joinToString(", ")
    }
}

private fun baseUrl(value: String): HttpUrl {
    require(value.length in 1..4096) { "Invalid WebDAV URL" }
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid WebDAV URL")
    val raw = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid WebDAV URL") }
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.pathSegments.none { it.contains('%') || it.contains('/') || it.contains('\\') || it.any(Char::isISOControl) } &&
        raw.rawUserInfo == null && raw.rawPath.orEmpty().split('/').none { it.lowercase(Locale.ROOT) in setOf(".", "..", "%2e", "%2e%2e", ".%2e", "%2e.") } &&
        !Regex("%2f|%5c|\\\\|[\\x00-\\x20]", RegexOption.IGNORE_CASE).containsMatchIn(value)) { "WebDAV requires a safe HTTPS collection URL" }
    return if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
}

private fun backupName(value: String): String {
    require(value.isNotBlank() && value.length <= 255 && value !in setOf(".", "..") &&
        value.none { it.isISOControl() || it in "/\\:%?#" }) { "Invalid WebDAV backup name" }
    return value
}

package dev.aerove.lite.platformqa

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

private const val RUNNER_PACKAGE = "dev.aerove.lite.platform.test"
private const val RUNNER_CLASS = "dev.aerove.lite.platformqa.AcceptanceCheckpointRunner"
internal const val PHYSICAL_RUNNER_COMPONENT = "$RUNNER_PACKAGE/$RUNNER_CLASS"

internal class PhysicalRunInput private constructor(
    val identity: JSONObject,
    val pin: PhysicalSocketDriver.Pin,
    val challenges: List<PhysicalSocketDriver.Challenge>
) {
    companion object {
        fun parse(encoded: String): PhysicalRunInput {
            require(encoded.length in 1..8192 && encoded.matches(Regex("[A-Za-z0-9_-]+")))
            return fromJson(JSONObject(String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)))
        }

        internal fun fromJson(row: JSONObject): PhysicalRunInput {
            val ids = JSONObject()
            fun field(key: String, pattern: Regex): String = row.getString(key).also {
                require(pattern.matches(it)); ids.put(key, it)
            }
            for (key in listOf("source", "tree")) field(key, Regex("[a-f0-9]{40}"))
            fun digest(key: String) = field(key, Regex("[a-f0-9]{64}"))
            val pin = PhysicalSocketDriver.Pin(digest("apkSha256"), digest("apkSignerSha256"),
                digest("fixtureApkSha256"), digest("fixtureSignerSha256"))
            digest("profileSourceSha256")
            if (row.has("expectedProxyGroup")) {
                require(row.get("expectedProxyGroup") == "AEROVE-LAB-READBACK")
                ids.put("expectedProxyGroup", "AEROVE-LAB-READBACK")
            }
            digest("runnerApkSha256")
            require(row.getString("runnerComponent") == PHYSICAL_RUNNER_COMPONENT)
            ids.put("runnerComponent", PHYSICAL_RUNNER_COMPONENT)
            field("deviceSerial", Regex("[A-Za-z0-9_-]{3,80}"))
            for (key in listOf("runId", "bootId")) require(UUID.fromString(row.getString(key)).toString() ==
                field(key, Regex("[a-f0-9-]{36}")))
            fun literal(key: String): String {
                val host = row.getString(key)
                val parts = host.split('.')
                require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) })
                val v = parts.map { it.toInt() }
                require(v.all { it <= 255 } && host != "10.0.2.2" && (v[0] == 10 ||
                    v[0] == 172 && v[1] in 16..31 || v[0] == 192 && v[1] == 168))
                return host
            }
            val host = literal("host")
            require(literal("phone") != host)
            val port = row.getInt("port")
            require(row.get("port") is Int && port in 1..65535)
            val nonces = row.getJSONArray("nonces")
            require(nonces.length() == 3)
            val values = (0..2).map { nonces.getString(it) }
            require(values.distinct().size == 3 && values.all { UUID.fromString(it).toString() == it })
            return PhysicalRunInput(ids, pin, listOf("off-before", "on", "off-after").zip(values).map { (stage, nonce) ->
                PhysicalSocketDriver.Challenge(stage, host, port, nonce)
            })
        }
    }
}

internal fun readInstalledPhysicalRunner(instrumentation: Instrumentation, expected: PhysicalRunInput): JSONObject {
    val context = instrumentation.context
    check(context.packageName == RUNNER_PACKAGE && instrumentation.javaClass.name == RUNNER_CLASS)
    val packageInfo = context.packageManager.getPackageInfo(
        RUNNER_PACKAGE, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    val info = checkNotNull(packageInfo.applicationInfo)
    check(info.splitSourceDirs.isNullOrEmpty() && info.sourceDir == context.applicationInfo.sourceDir)
    val apk = File(info.sourceDir)
    check(apk.isFile && apk.length() in 1L..48L * 1024 * 1024)
    val md = MessageDigest.getInstance("SHA-256")
    apk.inputStream().buffered().use { source ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            total += count
            check(total <= 48L * 1024 * 1024)
            md.update(buffer, 0, count)
        }
        check(total == apk.length())
    }
    val sha = md.digest().joinToString("") { "%02x".format(it) }
    val signer = checkNotNull(packageInfo.signingInfo).apkContentsSigners.single()
    val cert = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
    check(sha == expected.identity.getString("runnerApkSha256") && cert == expected.pin.appSigner)
    return JSONObject().put("component", PHYSICAL_RUNNER_COMPONENT).put("apkSha256", sha).put("signerSha256", cert)
}

internal fun writePhysicalRun(root: File, expected: PhysicalRunInput, aeroveUid: Int, result: PhysicalVpnScenario.Result,
    runnerBefore: JSONObject, runnerAfter: JSONObject, observedProxyGroup: String? = null) {
    val expectedGroup = if (expected.identity.has("expectedProxyGroup"))
        expected.identity.getString("expectedProxyGroup") else null
    check(observedProxyGroup == expectedGroup) { "Missing requested proxy-group observation" }
    for (row in listOf(runnerBefore, runnerAfter)) {
        check(row.getString("component") == expected.identity.getString("runnerComponent") &&
            row.getString("apkSha256") == expected.identity.getString("runnerApkSha256") &&
            row.getString("signerSha256") == expected.identity.getString("apkSignerSha256"))
    }
    check(result.records.length() == 3)
    val fixtureUid = result.records.getJSONObject(0).getInt("fixtureUid")
    check(fixtureUid > 0 && fixtureUid != aeroveUid && (0..2).all { i ->
        val row = result.records.getJSONObject(i)
        row.getInt("fixtureUid") == fixtureUid && row.getString("stage") == expected.challenges[i].stage &&
            row.getString("nonce") == expected.challenges[i].nonce
    })
    val nativeRaw = result.nativeJson.toByteArray(Charsets.UTF_8)
    check(nativeRaw.size in 1..2 * 1024 * 1024)
    val digest = MessageDigest.getInstance("SHA-256").digest(nativeRaw).joinToString("") { "%02x".format(it) }
    check(result.records.getJSONObject(1).getString("nativeSha256") == digest)
    val envelope = JSONObject().put("identity", expected.identity).put("fixtureUid", fixtureUid)
        .put("aeroveUid", aeroveUid).put("fixturePackage", "dev.aerove.fixture.socketowner")
        .put("runnerBefore", runnerBefore).put("runnerAfter", runnerAfter)
        .put("stages", result.records).put("releaseAccepted", false)
    if (observedProxyGroup != null) {
        envelope.put("proxyGroupReadback", JSONObject()
            .put("name", observedProxyGroup)
            .put("runId", expected.identity.getString("runId"))
            .put("profileSourceSha256", expected.identity.getString("profileSourceSha256")))
    }
    val publication = File(root, "physical-evidence")
    val staging = File(root, ".physical-evidence.partial")
    check(!publication.exists() && staging.mkdir())
    try {
        fun write(name: String, bytes: ByteArray) {
            val path = File(staging, name)
            check(path.createNewFile())
            FileOutputStream(path).use { it.write(bytes); it.fd.sync() }
        }
        write("android.json", (envelope.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        write("connections.json", nativeRaw)
        Files.move(staging.toPath(), publication.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        if (staging.exists()) staging.deleteRecursively()
    }
}

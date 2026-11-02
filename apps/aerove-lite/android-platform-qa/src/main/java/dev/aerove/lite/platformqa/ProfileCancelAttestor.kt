package dev.aerove.lite.platformqa

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.FileObserver
import android.os.SystemClock
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.security.MessageDigest
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Read-only real Flutter SQLite/files + actual host socket terminal. No production test API. */
internal class ProfileCancelAttestor(private val app: LiteApplication, private val root: File, private val args: Bundle) {
    companion object {
        val stages = listOf("profile-cancel-baseline", "profile-single-pending", "profile-single-cancelled",
            "profile-single-retried", "profile-batch-pending", "profile-batch-cancelled", "profile-batch-retried")
    }
    private var nextStage = 0
    private val baseline = linkedMapOf<String, JSONObject>()
    private var batchCompletedA: JSONObject? = null
    private var pair: String? = null
    private val history = linkedMapOf<String, JSONObject>()
    private val changes = ArrayBlockingQueue<Boolean>(1)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun canonical(row: JSONObject, exclude: Set<String> = emptySet()): String =
        row.keys().asSequence().filter { it !in exclude }.sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + row.get(key).let { value ->
                if (value is String) JSONObject.quote(value) else value.toString()
            }
        }
    private fun invariant(row: JSONObject) = canonical(row, setOf("last_update_date", "last_update_date_millis", "subscription_info"))
    private fun rows(profiles: JSONArray): Map<String, JSONObject> {
        val database = File(app.filesDir, "database.sqlite")
        check(database.isFile && database.canonicalFile == database.absoluteFile)
        return SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 22)
            (0 until profiles.length()).associate { index ->
                val expected = profiles.getJSONObject(index); val key = expected.getString("key")
                val row = db.rawQuery("SELECT * FROM profiles WHERE label = ? AND url = ?", arrayOf(expected.getString("label"), expected.getString("url"))).use { cursor ->
                    check(cursor.count == 1 && cursor.moveToFirst()) { "Expected one actual stored Flutter profile" }
                    JSONObject(TreeMap<String, Any>().apply {
                        cursor.columnNames.forEachIndexed { column, name -> put(name, when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            else -> error("Unexpected stored profile SQL value")
                        }) }
                    })
                }
                check(row.getString("type") == "url")
                key to row
            }
        }
    }
    private fun snapshot(profiles: JSONArray): Map<String, JSONObject> {
        val first = rows(profiles)
        val result = first.mapValues { (_, row) ->
            val id = row.getLong("id").also { check(it > 0) }
            val file = File(app.filesDir, "profiles/$id.yaml")
            check(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..4*1024*1024)
            JSONObject().put("sql", row).put("sqlSha256", sha(canonical(row).toByteArray()))
                .put("sourceSha256", sha(invariant(row).toByteArray())).put("file", "profiles/$id.yaml")
                .put("fileSha256", sha(file.readBytes())).put("fileBytes", file.length())
        }
        check(first.mapValues { canonical(it.value) } == rows(profiles).mapValues { canonical(it.value) }) { "SQL changed while reading actual profile files" }
        return result
    }
    private fun noTemporary(profiles: JSONArray) {
        val ids = rows(profiles).values.map { it.getLong("id").toString() }
        val temporary = File(app.filesDir, "profiles").listFiles().orEmpty().filter { file ->
            ids.any { file.name.startsWith("$it.yaml.") } && (file.name.endsWith(".update") || file.name.endsWith(".previous"))
        } + app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("temp") }
        check(temporary.isEmpty()) { "Profile update stage/backup/cache temporary output remains" }
    }
    private fun server(pair: String, stage: String, waitMs: Long): JSONObject {
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(1, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5500, TimeUnit.MILLISECONDS).build()
        try {
            val url = "http://10.0.2.2:18080/profile-cancel-state/$pair?stage=$stage&waitMs=${minOf(4500, waitMs)}"
            return client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful)
                val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(65537) }; check(bytes.size <= 65536)
                JSONObject(bytes.toString(Charsets.UTF_8)).also { value ->
                    check(value.getInt("schema") == 1 && value.getString("pair") == pair && value.getString("stage") == stage && value.getBoolean("ready"))
                    check(value.getString("sourceSha256") == args.getString("profileCancelServerSha256"))
                }
            }
        } finally { client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    private fun record(state: JSONObject, key: String, ordinal: Int): JSONObject = state.getJSONObject("requests").getJSONArray(key).getJSONObject(ordinal-1)
    private fun completed(actual: JSONObject, event: JSONObject, key: String) {
        check(event.getBoolean("completed") && event.getBoolean("handlerJoined") && !event.getBoolean("slow"))
        check(event.getLong("bytesSent") == event.getLong("bodyBytes") && actual.getString("fileSha256") == event.getString("bodySha256"))
        check(actual.getString("sourceSha256") == baseline.getValue(key).getString("sourceSha256"))
        val sql = actual.getJSONObject("sql")
        check(sql.getLong("last_update_date_millis") > baseline.getValue(key).getJSONObject("sql").getLong("last_update_date_millis"))
        val usage = JSONObject(sql.getString("subscription_info")); val expected = event.getJSONObject("userinfo")
        listOf("upload", "download", "total", "expire").forEach { check(usage.getLong(it) == expected.getLong(it)) }
    }
    private fun same(actual: JSONObject, expected: JSONObject) {
        check(actual.getString("sqlSha256") == expected.getString("sqlSha256") && actual.getString("fileSha256") == expected.getString("fileSha256"))
    }
    private fun aborted(event: JSONObject) {
        check(event.getBoolean("slow") && !event.getBoolean("completed") && event.getLong("bytesSent") == 1L &&
            event.getString("peerClose") in setOf("FIN", "RST") && !event.getBoolean("serverInitiatedClose") && event.getBoolean("handlerJoined"))
        check(event.getLong("peerClosedMonotonicNs") - event.getLong("startedMonotonicNs") in 0 until 18_000_000_000L)
    }
    private fun verify(stage: String, actual: Map<String, JSONObject>, state: JSONObject) {
        when (stage) {
            stages[0] -> {
                listOf("A", "B").forEach { key ->
                    val event = record(state,key,1);check(event.getBoolean("completed") && event.getBoolean("handlerJoined"))
                    check(actual.getValue(key).getString("fileSha256") == event.getString("bodySha256"))
                }
            }
            stages[1], stages[2] -> {
                listOf("A", "B").forEach { same(actual.getValue(it),baseline.getValue(it)) }
                if (stage == stages[2]) aborted(record(state,"A",2))
            }
            stages[3] -> { completed(actual.getValue("A"),record(state,"A",3),"A");same(actual.getValue("B"),baseline.getValue("B")) }
            stages[4] -> { completed(actual.getValue("A"),record(state,"A",4),"A");same(actual.getValue("B"),baseline.getValue("B")) }
            stages[5] -> { same(actual.getValue("A"),checkNotNull(batchCompletedA));same(actual.getValue("B"),baseline.getValue("B"));aborted(record(state,"B",2)) }
            stages[6] -> { completed(actual.getValue("A"),record(state,"A",5),"A");completed(actual.getValue("B"),record(state,"B",3),"B") }
            else -> error("Unknown profile cancel stage")
        }
    }
    fun capture(request: JSONObject, out: JSONObject) {
        val stage = request.getString("stage");check(stage == stages.getOrNull(nextStage)) { "Profile cancel stage was missing/replayed/out of order" }
        check(args.getString("profileCancelPrerequisiteGreen") == "true")
        check(app.vpn.value.phase == VpnPhase.Stopped && app.vpn.value.endpoint == null)
        val manager = app.getSystemService(ConnectivityManager::class.java)
        check(manager.allNetworks.none { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true })
        val payload = request.getJSONObject("profileCancel")
        listOf("implementationManifestSha256", "greenEvidenceSha256").forEach { key ->
            val argument = if (key == "implementationManifestSha256") "profileCancelImplementationManifestSha256" else "profileCancelGreenEvidenceSha256"
            check(payload.getString(key) == args.getString(argument))
        }
        val profiles = payload.getJSONArray("profiles");check(profiles.length() == 2)
        val pairs = (0..1).map { index ->
            val p = profiles.getJSONObject(index);val key = if (index == 0) "A" else "B";check(p.getString("key") == key)
            check(p.getString("label") == args.getString("cancelProfile${key}Label") && p.getString("url") == args.getString("cancelProfile${key}Url"))
            val uri = android.net.Uri.parse(p.getString("url"))
            check(uri.scheme == "http" && uri.host == "10.0.2.2" && uri.port == 18080 && uri.query == null && uri.fragment == null && uri.userInfo == null)
            check(uri.pathSegments.size == 3 && uri.pathSegments[0] == "profile-cancel" && uri.pathSegments[2] == "$key.yaml")
            UUID.fromString(uri.pathSegments[1]).toString().also { check(it == uri.pathSegments[1]) }
        }
        check(pairs[0] == pairs[1] && (pair == null || pair == pairs[0]));pair=pairs[0]
        val hierarchyName = payload.getString("uiHierarchy");check(hierarchyName == "$stage-hierarchy.json")
        val hierarchyFile = File(root,hierarchyName);check(hierarchyFile.canonicalFile == hierarchyFile.absoluteFile)
        val hierarchyBytes = hierarchyFile.readBytes();check(sha(hierarchyBytes) == payload.getString("uiHierarchySha256"))
        val nodes = JSONArray(hierarchyBytes.toString(Charsets.UTF_8))
        fun visible(label: String) = (0 until nodes.length()).any { i -> nodes.getJSONObject(i).let { n ->
            n.optBoolean("visible") && n.optString("package") == app.packageName && (n.optString("text") == label || n.optString("description") == label) } }
        check(visible("Update") == payload.getBoolean("toolbarUpdateVisible") && visible("Cancel") == payload.getBoolean("toolbarCancelVisible"))
        val pending = stage in setOf(stages[1],stages[4]);check(visible("Cancel") == pending && visible("Update") != pending)
        val started = payload.optLong("updateStartedElapsedRealtimeMs",0)
        val deadline = if (payload.has("cancelDeadlineElapsedRealtimeMs")) payload.getLong("cancelDeadlineElapsedRealtimeMs") else SystemClock.elapsedRealtime()+45_000
        if (started != 0L) check(deadline-started == 18_000L && SystemClock.elapsedRealtime() < deadline)
        val budget = minOf(if (started != 0L) 5800 else 30_000,deadline-SystemClock.elapsedRealtime());check(budget>0)
        val end = SystemClock.elapsedRealtime()+budget
        val state = server(checkNotNull(pair),stage,budget)
        val observers = listOf(app.filesDir,File(app.filesDir,"profiles"),app.cacheDir).filter { it.isDirectory }.map { directory ->
            object:FileObserver(directory,FileObserver.MODIFY or FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.CLOSE_WRITE) {
                override fun onEvent(event:Int,path:String?) { changes.offer(true) }
            }.also { it.startWatching() }
        }
        var actual:Map<String,JSONObject>
        try {
            while (true) {
                val observed=runCatching { snapshot(profiles).also { verify(stage,it,state);noTemporary(profiles) } }
                if (observed.isSuccess) { actual=observed.getOrThrow();break }
                val reason=observed.exceptionOrNull()?.message
                val left=end-SystemClock.elapsedRealtime();check(left>0) { "Actual SQL/file/cleanup did not converge: $reason" }
                check(changes.poll(left,TimeUnit.MILLISECONDS)!=null) { "No file/SQL event before deadline: $reason" }
            }
        } finally { observers.forEach { it.stopWatching() } }
        check(SystemClock.elapsedRealtime()<deadline)
        if (stage == stages[0]) baseline.putAll(actual)
        if (stage == stages[4]) batchCompletedA=actual.getValue("A")
        val readback = File(root,"native-profile-state/${request.getString("nonce")}")
        check(!readback.exists() && readback.mkdirs())
        actual.forEach { (key,value) ->
            val original=File(app.filesDir,value.getString("file"));val bytes=original.readBytes()
            check(sha(bytes)==value.getString("fileSha256")) { "Profile file changed before evidence capture" }
            val copied=File(readback,"$key.yaml");java.io.FileOutputStream(copied).use { it.write(bytes);it.fd.sync() }
            value.put("fileEvidence","native-profile-state/${request.getString("nonce")}/$key.yaml")
        }
        out.put("actualVpnStopped",true).put("actualVpnNetworkHandles",JSONArray())
        out.put("checkpointKind","profile-update-cancel").put("profileCancel",JSONObject()
            .put("pair",pair).put("profiles",profiles).put("actualProfiles",JSONObject(actual)).put("server",state)
            .put("implementationManifestSha256",payload.getString("implementationManifestSha256"))
            .put("greenEvidenceSha256",payload.getString("greenEvidenceSha256"))
            .put("uiHierarchySha256",sha(hierarchyBytes)).put("temporaryOutputsAbsent",true)
            .put("toolbarUpdateVisible",visible("Update")).put("toolbarCancelVisible",visible("Cancel")))
        history[stage]=out;nextStage++
    }
}

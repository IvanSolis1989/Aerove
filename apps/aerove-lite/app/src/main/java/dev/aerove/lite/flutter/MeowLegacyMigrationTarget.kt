package dev.aerove.lite.flutter

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.aerove.lite.data.sha256
import dev.aerove.lite.data.SealedFlutterResource
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

internal fun verifyNativeLiteTarget(context: Context, receipt: File, digest: String) {
    check(sha256(receipt) == digest) { "migration_receipt_changed" }
    val root = context.filesDir.canonicalFile
    val proof = JSONObject(receipt.readText())
    val preferences = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    val config = checkNotNull(preferences.getString("flutter.config", null)) { "migration_target_config_missing" }
    check(sha256(config) == proof.getString("configSha256") && preferences.getLong("flutter.version", -1) == proof.getLong("version") &&
        preferences.getLong("flutter.upgradeVersion", -1) == proof.getLong("upgradeVersion")) { "migration_target_preferences_changed" }
    check(JSONObject(config).getJSONObject("appSettingProps").getInt("acceptedDisclaimerVersion") == 0) { "migration_consent_not_inherited" }
    val defaults = File(root, "xclash-defaults.json")
    check(defaults.isFile && !Files.isSymbolicLink(defaults.toPath()) && sha256(defaults) == proof.getString("defaultsSha256")) { "migration_target_defaults_changed" }
    verifyNativeLiteFiles(root, proof.getJSONArray("files"))
    verifyNativeLiteTables(root, proof.getJSONObject("tables"), proof.getInt("databaseSchema"), nativeLiteBusinessTables + setOf("ip_qualities", "icon_records"))
}

internal val nativeLiteBusinessTables = setOf("profiles", "scripts", "rules", "disabled_rules", "proxy_groups", "icon_sets", "clash_providers", "custom_proxies", "proxy_dialers")

internal fun nativeLiteCanonical(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> JSONObject.NULL
    is JSONObject -> nativeLiteCanonical(value.keys().asSequence().associateWith { value.get(it) })
    is org.json.JSONArray -> nativeLiteCanonical((0 until value.length()).map { value.get(it) })
    is Map<*, *> -> JSONObject().apply { value.keys.map { it as String }.sorted().forEach { put(it, nativeLiteCanonical(value[it])) } }
    is List<*> -> org.json.JSONArray(value.map { nativeLiteCanonical(it) })
    is Number -> if (value.toDouble() == value.toLong().toDouble()) "i:${value.toLong()}" else "d:${java.lang.Double.doubleToRawLongBits(value.toDouble()).toULong().toString(16).padStart(16, '0')}"
    else -> value
}
internal fun nativeLiteJsonHash(value: Any?): String {
    fun encoded(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> encoded(nativeLiteMap(value))
        is org.json.JSONArray -> encoded(nativeLiteList(value))
        is Map<*, *> -> value.keys.map { it as String }.sorted().joinToString(",", "{", "}") { JSONObject.quote(Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))) + ":" + encoded(value[it]) }
        is List<*> -> value.joinToString(",", "[", "]") { encoded(it) }
        is Number -> JSONObject.quote(nativeLiteCanonical(value).toString())
        is Boolean -> value.toString()
        else -> JSONObject.quote("s:" + Base64.getEncoder().encodeToString(value.toString().toByteArray(Charsets.UTF_8)))
    }
    return sha256(encoded(value))
}

private fun nativeLiteObject(value: Any?): Map<String, Any?> = when (value) {
    null, JSONObject.NULL -> emptyMap()
    is String -> nativeLiteMap(JSONObject(value))
    is Map<*, *> -> @Suppress("UNCHECKED_CAST") (value as Map<String, Any?>)
    else -> error("shared_source_object_invalid")
}
private fun nativeLiteMap(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key ->
    when (val child = value.get(key)) {
        JSONObject.NULL -> null
        is JSONObject -> nativeLiteMap(child)
        is org.json.JSONArray -> nativeLiteList(child)
        else -> child
    }
}
private fun nativeLiteList(value: org.json.JSONArray): List<Any?> = (0 until value.length()).map { index ->
    when (val child = value.get(index)) { JSONObject.NULL -> null; is JSONObject -> nativeLiteMap(child); is org.json.JSONArray -> nativeLiteList(child); else -> child }
}
private fun list(value: Any?) = value as? List<*> ?: emptyList<Any?>()

internal fun nativeLiteCurrentProfileId(config: JSONObject): Long? {
    require(config.has("currentProfileId")) { "shared_source_selection_invalid" }
    if (config.isNull("currentProfileId")) return null
    val value = config.get("currentProfileId")
    require(value is Int || value is Long) { "shared_source_selection_invalid" }
    return (value as Number).toLong()
}
internal fun nativeLiteSelectedRows(all: Map<String, List<Map<String, Any?>>>, id: Long?): Map<String, List<Map<String, Any?>>> {
    if (id == null) return all.mapValues { emptyList() }
    fun same(value: Any?, id: Any?) = value is Number && id is Number && value.toLong() == id.toLong()
    val profile = all.getValue("profiles").single { same(it["id"], id) }
    val custom = profile["type"] == "custom"
    val overrides = nativeLiteObject(profile["overrides"])
    val standard = profile["extend_type"] == "standard"
    val global = standard && (!custom || overrides["inheritGlobalRules"] == true)
    val rules = all.getValue("rules").filter { (custom || standard) && (same(it["profile_id"], id) || global && it["profile_id"] == null) }
    val groups = if (custom) all.getValue("proxy_groups").filter { same(it["profile_id"], id) } else emptyList()
    val usedProfiles = groups.flatMap { list(nativeLiteList(org.json.JSONArray(it["use"] as? String ?: "[]"))) }.toSet()
    fun label(row: Map<String, Any?>): String {
        val label = row["label"] as String
        if (label.isNotEmpty()) return label
        val host = runCatching { java.net.URI(row["url"] as String).host }.getOrNull()
        return host?.takeIf { it.isNotEmpty() } ?: row["id"].toString()
    }
    val ordered = all.getValue("profiles").sortedWith(compareBy<Map<String, Any?>> { it["order"] == null }.thenBy { (it["order"] as? Number)?.toLong() }.thenBy { (it["id"] as Number).toLong() })
    val providers = ordered.filter { it["type"] != "custom" }.associateBy(::label)
    val profileIds = setOf(id) + usedProfiles.mapNotNull { (providers[it]?.get("id") as? Number)?.toLong() }
    val scope = (overrides["scopedCustomProxyIds"] as? List<*>)?.map { (it as Number).toLong() }
    val owned = all.getValue("profiles").flatMap { list(nativeLiteObject(it["overrides"])["ownedCustomProxyIds"]).map { (it as Number).toLong() } }.toSet()
    val proxies = if (custom) all.getValue("custom_proxies").filter { val key = (it["id"] as Number).toLong(); if (scope == null) key !in owned else key in scope } else emptyList()
    val labels = rules.mapNotNull { it["rule_provider"] as? String }.toMutableSet()
    val bindings = nativeLiteObject(overrides["ruleProviderBindings"])
    labels.addAll(bindings.keys)
    fun references(value: Any?) {
        if (value is Map<*, *>) value.forEach { (key, child) -> if (key.toString().startsWith("rule-set:")) labels.addAll(key.toString().substring(9).split(',')); references(child) }
        if (value is List<*>) value.forEach(::references)
        if (value is String) {
            Regex("(?:^|\\()\\s*RULE-SET\\s*,\\s*([^,()]+)", RegexOption.IGNORE_CASE).findAll(value).forEach { labels.add(it.groupValues[1].trim()) }
            if (value.startsWith("rule-set:")) labels.addAll(value.substring(9).substringBefore(':').split(','))
        }
    }
    references(overrides)
    rules.forEach { references(it["content"]) }
    val iconIds = nativeLiteObject(overrides["iconBindings"]).values.mapNotNull { (nativeLiteObject(it)["set"] as? Number)?.toLong() }.toSet()
    val script = if (profile["extend_type"] == "script" && (!custom || overrides["applyExtensionToCustom"] == true)) profile["script_id"] else null
    return linkedMapOf(
        "profiles" to all.getValue("profiles").filter { (it["id"] as Number).toLong() in profileIds },
        "scripts" to all.getValue("scripts").filter { same(it["id"], script) },
        "rules" to rules,
        "disabled_rules" to all.getValue("disabled_rules").filter { (custom || standard) && same(it["profile_id"], id) },
        "proxy_groups" to groups,
        "custom_proxies" to proxies,
        "proxy_dialers" to all.getValue("proxy_dialers").filter { custom && same(it["profile_id"], id) },
        "clash_providers" to all.getValue("clash_providers").filter { row -> custom && (bindings.values.any { same(it, row["id"]) } || row["label"] in labels && !nativeLiteObject(overrides["sourceRuleProviders"]).containsKey(row["label"])) },
        "icon_sets" to all.getValue("icon_sets").filter { custom && (it["id"] as Number).toLong() in iconIds }
    )
}
private val nativeLiteLiveKeys = setOf("tun", "mixed-port", "allow-lan", "find-process-mode", "mode", "log-level", "ipv6", "tcp-concurrent", "external-controller", "secret", "unified-delay", "geo-auto-update", "geo-update-interval", "geox-url")
internal fun nativeLiteRuntimeConfig(config: Map<String, Any?>, filters: Set<Long>): Map<String, Any?> {
    val app = nativeLiteObject(config["appSettingProps"])
    return listOf("currentProfileId", "patchClashConfig", "networkProps", "vpnProps", "excludeSSIDs").associateWith { config[it] } +
        ("appSettingProps" to (listOf("testUrl", "checkCertificate").associateWith { app[it] } +
            ("filters" to list(app["filters"]).filter { (nativeLiteObject(it)["id"] as? Number)?.toLong() in filters })))
}
internal fun nativeLiteSetupConfig(runtime: Map<String, Any?>): Map<String, Any?> = runtime + mapOf(
    "patchClashConfig" to (nativeLiteObject(runtime["patchClashConfig"]).filterKeys { it !in nativeLiteLiveKeys } + ("tun" to nativeLiteObject(nativeLiteObject(runtime["patchClashConfig"])["tun"]).filterKeys { it !in setOf("enable", "stack") })),
    "networkProps" to nativeLiteObject(runtime["networkProps"]).filterKeys { it != "authentication" },
    "appSettingProps" to nativeLiteObject(runtime["appSettingProps"]).filterKeys { it != "checkCertificate" }
)
internal fun nativeLiteLiveConfig(config: Map<String, Any?>): Map<String, Any?> {
    val patch = nativeLiteObject(config["patchClashConfig"])
    val app = nativeLiteObject(config["appSettingProps"])
    val auth = nativeLiteObject(nativeLiteObject(config["networkProps"])["authentication"])
    val tun = nativeLiteObject(patch["tun"])
    return nativeLiteLiveKeys.associateWith { patch[it] } + mapOf(
        "tun" to mapOf("enable" to tun["enable"], "stack" to tun["stack"]),
        "geox-url" to nativeLiteObject(patch["geox-url"]).filter { (key, value) ->
            if (key != "geoip") true else {
                require(value == "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat") {
                    "$.geox-url.geoip: fixed Meow has no geoip.dat download-source consumer"
                }
                false
            }
        },
        "authentication" to if (auth["enable"] == true && (auth["username"] as String).isNotEmpty()) listOf("${auth["username"]}:${auth["password"]}") else emptyList<String>(),
        "skip-cert-verify" to (app["checkCertificate"] == false)
    )
}

private fun nativeLiteCurrentConfig(context: Context): JSONObject {
    nativeLiteConfigInput(File(context.applicationInfo.dataDir)).use { input ->
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string" && parser.getAttributeValue(null, "name") == "flutter.config") return JSONObject(parser.nextText())
            parser.next()
        }
    }
    error("shared_config_missing")
}

internal fun nativeLiteConfigInput(systemDataDir: File): java.io.InputStream {
    val root = systemDataDir.canonicalFile
    val directory = File(root, "shared_prefs")
    check(Files.isDirectory(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
        Files.isDirectory(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile == directory.absoluteFile &&
        directory.canonicalFile.parentFile == root) { "shared_config_missing" }
    val file = File(directory, "FlutterSharedPreferences.xml")
    check(Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile &&
        file.canonicalFile.parentFile == directory) { "shared_config_missing" }
    return Files.newInputStream(file.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)
}

internal class NativeLiteVerifiedSource(val config: JSONObject, val selectedMap: Map<String,String>, val definitionSha256: String,
    val providerBindings: Map<String, SealedFlutterResource> = emptyMap())

internal fun nativeLiteProviderOwnerKey(profileId: Long?): String = nativeLiteJsonHash(
    mapOf("schema" to 1, "scope" to "flutter-provider-owner", "profileId" to profileId))

internal fun verifyNativeLiteProviderBindings(context: Context, proof: JSONObject): Map<String, SealedFlutterResource> =
    verifyNativeLiteBusinessSourceReceipt(context, proof).providerBindings

internal fun nativeLiteDetachedProviderBindings(root: File, profileId: Long?, overrides: Map<String, Any?>): Map<String, SealedFlutterResource> {
    val bindings = nativeLiteObject(overrides["localProviderSources"])
    if (bindings.isEmpty()) return emptyMap()
    require(profileId != null) { "shared_provider_binding_owner_missing" }
    val home = root.canonicalFile
    return bindings.map { (key, value) ->
        val section = listOf("proxy-providers", "rule-providers").singleOrNull { key.startsWith("$it/") }
        require(section != null && key.length > section.length + 1) { "shared_provider_binding_key_invalid" }
        val name = key.substring(section.length + 1)
        val row = nativeLiteObject(value)
        require(row.keys == setOf("schema", "kind", "relativePath", "sha256", "originalDefinition") &&
            row["schema"] == 1 && row["kind"] == "detached" && row["originalDefinition"] is Map<*, *>) { "shared_provider_binding_invalid" }
        val hash = MessageDigest.getInstance("MD5").digest("detached/$section/$name".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val directory = if (section == "proxy-providers") "proxies" else "rules"
        val relative = "profiles/providers/$profileId/$directory/$hash"
        val expected = row["sha256"] as? String
        require(row["relativePath"] == relative && expected != null && expected.matches(Regex("[0-9a-f]{64}"))) { "shared_provider_binding_path_invalid" }
        var component = home
        for (part in relative.split('/')) {
            component = File(component, part)
            require(component.canonicalFile == component.absoluteFile && !Files.isSymbolicLink(component.toPath())) { "shared_provider_binding_linked" }
        }
        val limit = (if (section == "proxy-providers") 4L else 64L) * 1024 * 1024
        require(Files.isRegularFile(component.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            component.length() <= limit && sha256(component) == expected) { "shared_provider_binding_file_changed" }
        "$.${section}.${name}" to SealedFlutterResource(relative, component.absolutePath, expected)
    }.toMap()
}

internal fun nativeLiteRegisteredProviderFiles(root: File, profileIds: Set<Long>): Set<String> {
    val home = root.canonicalFile
    fun checked(relative: String): File {
        var current = home
        for (part in relative.split('/')) {
            require(part.isNotEmpty() && part != "." && part != "..") { "shared_provider_source_path_invalid" }
            current = File(current, part)
            require(current.canonicalFile == current.absoluteFile && !Files.isSymbolicLink(current.toPath())) { "shared_provider_source_linked" }
        }
        return current
    }
    val paths = linkedSetOf<String>()
    for (id in profileIds) for (kind in listOf("proxies", "rules")) {
        val directoryRelative = "profiles/providers/$id/$kind"
        val directory = checked(directoryRelative)
        if (!directory.isDirectory) continue
        for (entry in checkNotNull(directory.listFiles())) {
            if (!entry.name.matches(Regex("[0-9a-f]{32}\\.source\\.json"))) continue
            val markerRelative = "$directoryRelative/${entry.name}"
            val marker = checked(markerRelative)
            require(Files.isRegularFile(marker.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "shared_provider_source_marker_invalid" }
            val row = JSONObject(marker.readText())
            val section = if (kind == "proxies") "proxy-providers" else "rule-providers"
            require(row.keys().asSequence().toSet() == setOf("schema", "kind", "profileId", "section", "name", "relativePath") &&
                row.get("schema") == 1 && row.getString("kind") == "file-provider-source" &&
                row.get("profileId") is Number && row.get("profileId") !is Double && row.getLong("profileId") == id &&
                row.getString("section") == section && row.get("name") is String && row.getString("name").isNotEmpty()) { "shared_provider_source_marker_invalid" }
            val name = row.getString("name")
            val allowed = listOf("$section/$name", "detached/$section/$name").map { key ->
                val hash = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                "$directoryRelative/$hash"
            }
            val relative = row.getString("relativePath")
            require(relative in allowed && markerRelative == "$relative.source.json") { "shared_provider_source_marker_path_invalid" }
            val source = checked(relative)
            if (!source.exists()) continue
            require(Files.isRegularFile(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "shared_provider_source_file_invalid" }
            paths += relative
            paths += markerRelative
        }
    }
    return paths
}

internal fun verifyNativeLiteBusinessSource(context: Context, proof: JSONObject): JSONObject =
    verifyNativeLiteBusinessSourceReceipt(context,proof).config

internal fun verifyNativeLiteBusinessSource(context: Context, proof: JSONObject,
    verifiedSelectedMap: (Map<String, String>) -> Unit): JSONObject {
    val verified=verifyNativeLiteBusinessSourceReceipt(context,proof)
    verifiedSelectedMap(verified.selectedMap)
    return verified.config
}

internal fun verifyNativeLiteBusinessSourceReceipt(context: Context, proof: JSONObject): NativeLiteVerifiedSource {
    var selectedMap: Map<String, String>? = null
    var definitionSha256: String? = null
    var providerBindings: Map<String, SealedFlutterResource> = emptyMap()
    require(proof.keys().asSequence().toSet() == setOf("schema", "databaseSchema", "configSha256", "setupSha256", "liveSha256", "tables", "files") && proof.getInt("schema") == 2) { "shared_source_proof_invalid" }
    val phase = NativeLiteDataAuthority.phase(context.filesDir)
    check(phase == "shared" || phase == null && listOf("profiles/index.json", "settings/settings.json", "settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json").none { File(context.filesDir, it).exists() }) { "native_data_migration_required" }
    val config = nativeLiteCurrentConfig(context)
    val root = context.filesDir.canonicalFile
    val database = File(root, "database.sqlite")
    check(database.isFile && database.canonicalFile == database.absoluteFile && !Files.isSymbolicLink(database.toPath())) { "shared_source_database_missing" }
    withNativeLiteReadOnlyDatabase(database, "shared_source_database_changed") { db ->
        check(db.version == 22 && db.version == proof.getInt("databaseSchema")) { "shared_source_schema_changed" }
        val all = nativeLiteBusinessTables.associateWith { table ->
            db.rawQuery("SELECT * FROM \"$table\" ORDER BY rowid", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.columnNames.mapIndexed { i, name -> name to when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i); Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i); Cursor.FIELD_TYPE_STRING -> cursor.getString(i); else -> error("shared_source_value_invalid")
                } }.toMap()) }
            }
        }
        val selectedId = nativeLiteCurrentProfileId(config)
        val rows = nativeLiteSelectedRows(all, selectedId)
        val tables = proof.getJSONObject("tables")
        check(tables.keys().asSequence().toSet() == rows.keys) { "shared_source_tables_invalid" }
        rows.forEach { (name, values) -> val expected = tables.getJSONObject(name); check(expected.getInt("rows") == values.size && expected.getString("sha256") == nativeLiteJsonHash(values)) { "shared_source_table_changed:$name" } }
        val selected = if (selectedId == null) null else rows.getValue("profiles").single { (it["id"] as Number).toLong() == selectedId }
        val overrides = nativeLiteObject(selected?.get("overrides"))
        val filters = nativeLiteObject(overrides["filterBindings"]).values.flatMap { list(it).map { (it as Number).toLong() } }.toSet()
        val map = nativeLiteMap(config)
        val runtime = nativeLiteRuntimeConfig(map, filters)
        check(nativeLiteJsonHash(runtime) == proof.getString("configSha256") && nativeLiteJsonHash(nativeLiteSetupConfig(runtime)) == proof.getString("setupSha256") && nativeLiteJsonHash(nativeLiteLiveConfig(map)) == proof.getString("liveSha256")) { "shared_source_config_changed" }
        val paths = mutableSetOf<String>()
        rows.getValue("profiles").filter { it["type"] != "custom" }.forEach { paths += "profiles/${it["id"]}.yaml" }
        paths += nativeLiteRegisteredProviderFiles(root, rows.getValue("profiles").map { (it["id"] as Number).toLong() }.toSet())
        rows.getValue("scripts").forEach { paths += "scripts/${it["id"]}.js" }
        rows.getValue("clash_providers").forEach { val hash = MessageDigest.getInstance("MD5").digest("${it["id"]}@${it["url"]}".toByteArray()).joinToString("") { "%02x".format(it) }; paths += "providers/rules/$hash" }
        fun resources(value: Any?) {
            if (value is Map<*, *>) value.forEach { (key, child) ->
                if (key == "localProviderSources") {
                    nativeLiteObject(child).values.forEach { input ->
                        val relative = nativeLiteObject(input)["relativePath"]
                        require(relative is String) { "shared_provider_binding_path_invalid" }
                        paths += relative
                    }
                } else if (key == "nativeResourceInputs") {
                    nativeLiteObject(child).values.forEach { input -> listOf("file", "receiptFile").forEach { key -> (nativeLiteObject(input)[key] as? String)?.let(paths::add) } }
                } else if (key in setOf("path", "tls-cert", "tls-key", "ca", "mmdb-path", "asn-path", "geosite-path") && child is String && child.isNotEmpty()) {
                    paths += if (File(child).isAbsolute) root.toPath().relativize(File(child).toPath()).toString().replace('\\', '/') else child
                } else resources(child)
            }
            if (value is List<*>) value.forEach(::resources)
        }
        rows.getValue("profiles").forEach { resources(nativeLiteObject(it["overrides"])) }; resources(map["patchClashConfig"])
        rows.getValue("custom_proxies").forEach { resources(nativeLiteObject(it["definition"])) }
        val files = proof.getJSONArray("files")
        check((0 until files.length()).map { files.getJSONObject(it).getString("path") }.toSet() == paths && files.length() == paths.size) { "shared_source_file_set_changed" }
        verifyNativeLiteFiles(root, files)
        providerBindings = nativeLiteDetachedProviderBindings(root, selectedId, overrides)
        check(nativeLiteJsonHash(nativeLiteCurrentConfig(context).also { nativeLiteCurrentProfileId(it) }.let(::nativeLiteMap).let { nativeLiteRuntimeConfig(it, filters) }) == proof.getString("configSha256")) { "shared_source_config_changed" }
        selectedMap = nativeLiteObject(selected?.get("selected_map")).mapValues { (_, value) ->
            require(value is String) { "shared_source_selected_map_invalid" }
            value
        }
        val definitions=rows.mapValues { (table,values) ->
            values.map { row -> if(table == "profiles") row.filterKeys { it != "selected_map" } else row }
        }
        definitionSha256=nativeLiteJsonHash(mapOf("schema" to proof.getInt("schema"),
            "setupSha256" to proof.getString("setupSha256"),"tables" to definitions,"files" to nativeLiteList(files)))
    }
    return NativeLiteVerifiedSource(config,checkNotNull(selectedMap),checkNotNull(definitionSha256),providerBindings)
}

private fun verifyNativeLiteFiles(root: File, files: org.json.JSONArray) {
    require(files.length() <= 12000) { "migration_target_file_limit" }
    for (i in 0 until files.length()) {
        val row = files.getJSONObject(i)
        val path = row.getString("path")
        require(path.matches(Regex("(?:profiles/[0-9]+\\.yaml|scripts/[0-9]+\\.js|(?:profiles/)?providers/[a-zA-Z0-9_.@/-]+)")) && path.split('/').none { it in setOf("", ".", "..") }) { "migration_target_file_path" }
        val file = File(root, path)
        require(file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() == row.getLong("bytes") && sha256(file) == row.getString("sha256")) { "migration_target_file_changed" }
    }
}

/** These two verifiers own a single-connection read-only handle for the whole read. */
internal fun <T> withNativeLiteReadOnlyDatabase(database: File, changed: String, read: (SQLiteDatabase) -> T): T =
    SQLiteDatabase.openDatabase(database, SQLiteDatabase.OpenParams.Builder()
        .setOpenFlags(SQLiteDatabase.OPEN_READONLY)
        // data_version must keep the same connection, including with system debug idle closure.
        .setIdleConnectionTimeout(Long.MAX_VALUE)
        .build()).use { db ->
        fun dataVersion(): Long = db.rawQuery("PRAGMA data_version", null).use { cursor ->
            check(cursor.columnCount == 1 && cursor.moveToFirst() && cursor.getType(0) == Cursor.FIELD_TYPE_INTEGER) { changed }
            val version = cursor.getLong(0)
            check(!cursor.moveToNext()) { changed }
            version
        }
        val before = dataVersion()
        val result = read(db)
        check(dataVersion() == before) { changed }
        result
    }

private fun verifyNativeLiteTables(root: File, tables: JSONObject, schema: Int, allowed: Set<String>) {
    val database = File(root, "database.sqlite")
    require(database.isFile && database.canonicalFile == database.absoluteFile && !Files.isSymbolicLink(database.toPath())) { "migration_target_database_missing" }
    withNativeLiteReadOnlyDatabase(database, "migration_target_database_changed") { db ->
        check(db.version == 22 && schema == db.version) { "migration_target_database_version" }
        check(tables.keys().asSequence().toSet() == allowed) { "migration_target_table_set" }
        for (table in allowed) {
            val columns = mutableListOf<String>()
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
            val hash = MessageDigest.getInstance("SHA-256")
            fun text(value: String) { hash.update(value.toByteArray(Charsets.UTF_8)) }
            fun string(value: String) = "t${value.toByteArray(Charsets.UTF_8).size}:$value;"
            columns.forEach { text(string(it)) }
            var rows = 0
            db.rawQuery("SELECT * FROM \"$table\" ORDER BY rowid", null).use { cursor ->
                while (cursor.moveToNext()) {
                    rows++; text("r;")
                    for (i in columns.indices) text(when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> "n;"
                        Cursor.FIELD_TYPE_INTEGER -> "i${cursor.getLong(i)};"
                        Cursor.FIELD_TYPE_FLOAT -> "d${java.lang.Double.doubleToRawLongBits(cursor.getDouble(i)).toULong().toString(16).padStart(16, '0')};"
                        Cursor.FIELD_TYPE_BLOB -> "b${Base64.getEncoder().encodeToString(cursor.getBlob(i))};"
                        else -> string(cursor.getString(i))
                    })
                }
            }
            val row = tables.getJSONObject(table)
            check(row.getInt("rows") == rows && row.getString("sha256") == hash.digest().joinToString("") { "%02x".format(it) }) { "migration_target_table_changed:$table" }
        }
    }
}

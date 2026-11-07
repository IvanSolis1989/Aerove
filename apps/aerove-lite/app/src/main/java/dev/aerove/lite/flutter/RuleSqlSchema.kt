package dev.aerove.lite.flutter

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

internal val ruleSqlInfrastructureTables = setOf("rule_sql_identity", "rule_source_publications")
private val ruleUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val ruleSha = Regex("[0-9a-f]{64}")

internal fun verifyRuleSqlSchema(root: File, db: SQLiteDatabase): JSONObject? {
    check(db.version == 22 || db.version == 23) { "shared_source_schema_unknown" }
    db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('view','trigger')",null).use { check(!it.moveToFirst()) { "rule_sql_unexpected_sql_object" } }
    val expected = ruleSqlBusinessColumns.keys + if (db.version == 23) ruleSqlInfrastructureTables else emptySet()
    val names = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    check(names == expected) { "shared_source_table_schema_unknown" }
    ruleSqlBusinessColumns.forEach { (table, columns) ->
        val actual = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            buildMap { while(c.moveToNext()) {
                val name=c.getString(c.getColumnIndexOrThrow("name"));val flags=ruleSqlBusinessColumnFlags.getValue(table)[name]
                check(flags!=null && c.getInt(c.getColumnIndexOrThrow("notnull"))==flags[0] && c.getInt(c.getColumnIndexOrThrow("pk"))==flags[1]) { "rule_sql_business_column_flags" }
                put(name,c.getString(c.getColumnIndexOrThrow("type")).uppercase())
            } }
        }
        check(actual == columns) { "shared_source_column_schema_unknown:$table" }
    }
    val marker = File(root, "rule-sql-publications/connection-uncertain.json")
    val pendingMarker=File(root,"rule-sql-publications/connection-uncertain.json.pending")
    check(!marker.exists() && !Files.isSymbolicLink(marker.toPath()) && !pendingMarker.exists() && !Files.isSymbolicLink(pendingMarker.toPath())) { "rule_sql_connection_uncertain" }
    if (db.version == 22) {
        check(File(root,"rule-sql-publications").listFiles().orEmpty().none {
            ruleUuid.matches(it.name) || it.name.startsWith(".finished-")
        }) { "rule_sql_infrastructure_requires_upgrade" }
        return null
    }
    val schema = mapOf(
        "rule_sql_identity" to mapOf("slot" to "INTEGER", "incarnation" to "TEXT"),
        "rule_source_publications" to mapOf("transaction_id" to "TEXT", "incarnation" to "TEXT", "provider_id" to "INTEGER",
            "plan_sha256" to "TEXT", "decision" to "TEXT", "schema_version" to "INTEGER"),
    )
    schema.forEach { (table, columns) ->
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            val seen = mutableSetOf<String>()
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name")); seen += name
                val key = name == if (table == "rule_sql_identity") "slot" else "transaction_id"
                check(columns[name] == c.getString(c.getColumnIndexOrThrow("type")).uppercase() &&
                    c.getInt(c.getColumnIndexOrThrow("notnull")) == 1 && c.getInt(c.getColumnIndexOrThrow("pk")) == if (key) 1 else 0) { "rule_sql_infrastructure_columns_invalid" }
            }
            check(seen == columns.keys) { "rule_sql_infrastructure_columns_invalid" }
        }
    }
    fun rows(table: String): List<Map<String, Any?>> = db.rawQuery("SELECT * FROM \"$table\" ORDER BY rowid LIMIT 4097", null).use { c ->
        buildList { while (c.moveToNext()) add(c.columnNames.mapIndexed { i, name -> name to when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); Cursor.FIELD_TYPE_STRING -> c.getString(i)
            else -> error("rule_sql_infrastructure_value_invalid")
        } }.toMap()) }
    }
    val identity = rows("rule_sql_identity").single()
    val incarnation = identity["incarnation"] as? String
    check(identity["slot"] == 1L && incarnation != null && ruleUuid.matches(incarnation)) { "rule_sql_identity_invalid" }
    val tokens = rows("rule_source_publications")
    check(tokens.size <= 4096) { "rule_sql_token_budget_exceeded" }
    tokens.forEach { row ->
        check(row["transaction_id"] is String && ruleUuid.matches(row["transaction_id"] as String) && row["incarnation"] == incarnation &&
            row["provider_id"] is Long && row["plan_sha256"] is String && ruleSha.matches(row["plan_sha256"] as String) &&
            row["decision"] is String && (row["decision"] as String) in setOf("PREPARED", "COMMITTED", "ABORTED") && row["schema_version"] == 1L) { "rule_sql_token_invalid" }
    }
    check(tokens.isEmpty()) { "rule_publication_recovery_required" }
    return JSONObject().put("schema", 1).put("incarnation", incarnation).put("tokens", JSONArray())
}

internal val ruleSqlBusinessColumns = mapOf(
    "profiles" to mapOf("id" to "INTEGER", "type" to "TEXT", "label" to "TEXT", "current_group_name" to "TEXT", "url" to "TEXT", "age_secret_key" to "TEXT", "last_update_date" to "INTEGER", "last_update_date_millis" to "INTEGER", "extend_type" to "TEXT", "script_id" to "INTEGER", "match_target" to "TEXT", "auto_update_duration_millis" to "INTEGER", "subscription_info" to "TEXT", "auto_update" to "INTEGER", "selected_map" to "TEXT", "unfold_set" to "TEXT", "order" to "INTEGER", "overrides" to "TEXT"),
    "scripts" to mapOf("id" to "INTEGER", "label" to "TEXT", "last_update_time" to "INTEGER", "last_update_time_millis" to "INTEGER", "url" to "TEXT", "order" to "INTEGER"),
    "rules" to mapOf("id" to "INTEGER", "profile_id" to "INTEGER", "rule_action" to "TEXT", "content" to "TEXT", "rule_target" to "TEXT", "rule_provider" to "TEXT", "sub_rule" to "TEXT", "no_resolve" to "INTEGER", "src" to "INTEGER", "order" to "TEXT"),
    "disabled_rules" to mapOf("profile_id" to "INTEGER", "rule_id" to "INTEGER"),
    "proxy_groups" to mapOf("id" to "INTEGER", "profile_id" to "INTEGER", "name" to "TEXT", "type" to "TEXT", "proxies" to "TEXT", "use" to "TEXT", "definition" to "TEXT", "order" to "TEXT"),
    "icon_records" to mapOf("url" to "TEXT", "last_accessed" to "INTEGER"),
    "icon_sets" to mapOf("id" to "INTEGER", "name" to "TEXT", "url" to "TEXT", "icons" to "TEXT", "last_update_time" to "INTEGER", "last_update_time_millis" to "INTEGER", "order" to "INTEGER"),
    "clash_providers" to mapOf("id" to "INTEGER", "kind" to "TEXT", "label" to "TEXT", "source_definition" to "TEXT", "url" to "TEXT", "behavior" to "TEXT", "format" to "TEXT", "order" to "INTEGER"),
    "custom_proxies" to mapOf("id" to "INTEGER", "definition" to "TEXT", "order" to "TEXT", "empty_options" to "TEXT"),
    "proxy_dialers" to mapOf("profile_id" to "INTEGER", "proxy_id" to "INTEGER", "target" to "TEXT"),
    "ip_qualities" to mapOf("ip" to "TEXT", "quality" to "TEXT", "checked_at" to "INTEGER")
)

internal val ruleSqlBusinessColumnFlags = mapOf(
    "profiles" to mapOf("id" to listOf(1,1),"type" to listOf(1,0),"label" to listOf(1,0),"current_group_name" to listOf(0,0),"url" to listOf(1,0),"age_secret_key" to listOf(0,0),"last_update_date" to listOf(0,0),"last_update_date_millis" to listOf(0,0),"extend_type" to listOf(1,0),"script_id" to listOf(0,0),"match_target" to listOf(0,0),"auto_update_duration_millis" to listOf(1,0),"subscription_info" to listOf(0,0),"auto_update" to listOf(1,0),"selected_map" to listOf(1,0),"unfold_set" to listOf(1,0),"order" to listOf(0,0),"overrides" to listOf(0,0)),
    "scripts" to mapOf("id" to listOf(1,1),"label" to listOf(1,0),"last_update_time" to listOf(1,0),"last_update_time_millis" to listOf(0,0),"url" to listOf(0,0),"order" to listOf(0,0)),
    "rules" to mapOf("id" to listOf(1,1),"profile_id" to listOf(0,0),"rule_action" to listOf(1,0),"content" to listOf(0,0),"rule_target" to listOf(0,0),"rule_provider" to listOf(0,0),"sub_rule" to listOf(0,0),"no_resolve" to listOf(1,0),"src" to listOf(1,0),"order" to listOf(0,0)),
    "disabled_rules" to mapOf("profile_id" to listOf(1,1),"rule_id" to listOf(1,2)),
    "proxy_groups" to mapOf("id" to listOf(1,1),"profile_id" to listOf(1,0),"name" to listOf(1,0),"type" to listOf(1,0),"proxies" to listOf(0,0),"use" to listOf(0,0),"definition" to listOf(1,0),"order" to listOf(0,0)),
    "icon_records" to mapOf("url" to listOf(1,1),"last_accessed" to listOf(1,0)),
    "icon_sets" to mapOf("id" to listOf(1,1),"name" to listOf(1,0),"url" to listOf(1,0),"icons" to listOf(1,0),"last_update_time" to listOf(0,0),"last_update_time_millis" to listOf(0,0),"order" to listOf(0,0)),
    "clash_providers" to mapOf("id" to listOf(1,1),"kind" to listOf(1,0),"label" to listOf(1,0),"source_definition" to listOf(0,0),"url" to listOf(1,0),"behavior" to listOf(0,0),"format" to listOf(0,0),"order" to listOf(0,0)),
    "custom_proxies" to mapOf("id" to listOf(1,1),"definition" to listOf(1,0),"order" to listOf(0,0),"empty_options" to listOf(0,0)),
    "proxy_dialers" to mapOf("profile_id" to listOf(1,1),"proxy_id" to listOf(1,2),"target" to listOf(1,0)),
    "ip_qualities" to mapOf("ip" to listOf(1,1),"quality" to listOf(1,0),"checked_at" to listOf(1,0))
)

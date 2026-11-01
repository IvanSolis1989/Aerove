package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowGeoOrigin
import dev.aerove.lite.data.MeowGeoPolicy
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.resources.GeoResourceKind
import dev.aerove.lite.resources.GeoResourcePreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import org.json.JSONObject

/** Admission of a fresh output from the existing Dart producer; it grants no startup authority. */
internal class MeowGeoProducer(val transactionId: String, val type: String, val body: String,
    val sha256: String, val file: File, val setupParams: JSONObject, val definitionSha256: String) {
    companion object {
        fun read(home: File, request: JSONObject, snapshot: MeowRuntimeSnapshot,
            validateSetup: (JSONObject) -> JSONObject, verify: (JSONObject) -> Triple<JSONObject,Map<String,String>,String>): MeowGeoProducer {
            require(request.keys().asSequence().toSet() == setOf("operation","schemaVersion","transactionId","type","source","setupParams") &&
                request.getString("operation") == "update" && request.get("schemaVersion") == 1) { "Invalid Geo producer request" }
            val id=request.getString("transactionId").also { require(UUID.fromString(it).toString() == it) }
            val type=request.getString("type").also { require(it in setOf("automatic","mmdb","asn","geosite")) { "Unknown Host Geo format" } }
            val source=request.getJSONObject("source")
            require(source.keys().asSequence().toSet() == setOf("path","sha256","bytes")) { "Invalid Geo source receipt" }
            val file=File(source.getString("path"))
            require(file.absoluteFile == File(home,"geo-requests/$id.yaml") && file.toPath() == file.toPath().toRealPath() &&
                Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS)) { "Geo producer file is not owned by this request" }
            val bytes=Files.newInputStream(file.toPath(),NOFOLLOW_LINKS).use { it.readBounded(4*1024*1024) }
            val sha=runtimeSha256(bytes)
            require(source.get("bytes") is Number && (source.get("bytes") as Number).toDouble() == bytes.size.toDouble() && source.getString("sha256") == sha) { "Geo producer bytes changed" }
            val body=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            val params=validateSetup(request.getJSONObject("setupParams"))
            val proof=params.getJSONObject("data-source-proof")
            val (config,raw,definition)=verify(proof)
            val requested=params.getJSONObject("selected-map").let { row -> row.keys().asSequence().associateWith(row::getString) }
            require(raw == requested) { "Geo producer selected map differs from current SQL" }
            val metadata=JSONObject(snapshot.handoff.runtimeSettingsJson)
            require(metadata.getString("flutterDefinitionSha256") == definition) { "Geo-only request changed a profile, script or non-Geo definition" }
            val previousSetup=metadata.getJSONObject("flutterSetup")
            require(previousSetup.getString("test-url") == params.getString("test-url") &&
                previousSetup.getBoolean("skip-cert-verify") == params.getBoolean("skip-cert-verify")) { "Geo-only request changed runtime policy" }
            val record=params.getJSONObject("applied")
            require(record.keys().asSequence().toSet() == setOf("digest","yaml-md5","groups","proxies","rules") &&
                record.getString("digest").isNotBlank() && record.getString("yaml-md5").matches(Regex("[0-9a-f]{32}"))) { "Geo producer record is invalid" }
            listOf("groups","proxies","rules").forEach { key -> require(record.getLong(key) == JSONObject(snapshot.handoff.setupRecordJson).getLong(key)) { "Geo-only request changed configuration counts" } }
            val stored=metadata.getJSONObject("flutterProducer")
            val asset=snapshot.handoff.resources.single { it.relativePath == stored.getString("file") }
            val old=File(snapshot.resourceDirectory,asset.relativePath)
            require(old.toPath() == old.toPath().toRealPath() && Files.isRegularFile(old.toPath(),NOFOLLOW_LINKS))
            val oldBytes=Files.newInputStream(old.toPath(),NOFOLLOW_LINKS).use { it.readBounded(4*1024*1024) }
            require(runtimeSha256(oldBytes) == asset.sha256 && asset.sha256 == stored.getString("sha256") && stored.getLong("bytes") == oldBytes.size.toLong()) { "Stored producer receipt changed" }
            val actual=verifyDifference(oldBytes.toString(Charsets.UTF_8),body)
            val patch=config.getJSONObject("patchClashConfig")
            val urls=patch.getJSONObject("geox-url")
            val global=MeowGeoPolicy.read(mapOf("geodata" to mapOf(
                "auto-update" to patch.getBoolean("geo-auto-update"),
                "auto-update-interval" to patch.getInt("geo-update-interval"),
                "url" to GeoResourceKind.entries.associate { it.wire to urls.getString(it.wire) }
            )),GeoResourcePreferences())
            val origin=checkNotNull(MeowGeoOrigin.read(ConfigYaml.parse(body)))
            require(actual == origin.apply(global)) { "Geo producer fallback differs from independently verified current Config" }
            return MeowGeoProducer(id,type,body,sha,file,params,definition)
        }

        fun verifyDifference(previous: String, next: String): GeoResourcePreferences {
            val old=ConfigYaml.parse(previous);val fresh=ConfigYaml.parse(next)
            val oldOrigin=checkNotNull(MeowGeoOrigin.read(old)) { "Stored source Geo origin is required" }
            val origin=checkNotNull(MeowGeoOrigin.read(fresh)) { "Fresh source Geo origin is required" }
            require(nativeLiteJsonHash(oldOrigin.json()) == nativeLiteJsonHash(origin.json())) { "Geo-only request changed explicit profile/script Geo policy" }
            val actual=MeowGeoPolicy.read(fresh,GeoResourcePreferences())
            fun projection(root: Map<String,Any?>):Map<String,Any?> {
                val out=root.toMutableMap()
                val geo=(root["geodata"] as? Map<*,*>)?.entries?.associate { (key,value) -> (key as String) to value }?.toMutableMap() ?: linkedMapOf()
                if(origin.autoUpdate == null)geo.remove("auto-update")
                if(origin.intervalHours == null)geo.remove("auto-update-interval")
                val urls=(geo["url"] as? Map<*,*>)?.toMutableMap() ?: linkedMapOf()
                GeoResourceKind.entries.filter { it !in origin.urls }.forEach { kind -> urls.remove(kind.wire) }
                if(urls.isEmpty())geo.remove("url") else geo["url"]=urls
                if(geo.isEmpty())out.remove("geodata") else out["geodata"]=geo
                GeoResourceKind.entries.filter { it !in origin.urls }.forEach { kind ->
                    val key="${kind.sourceKey}-download-url"
                    if(out.containsKey(key)) {
                        val policy=MeowGeoPolicy.read(root,GeoResourcePreferences())
                        require(out[key] == policy.urls.getValue(kind)) { "Geo download hint differs from actual policy" }
                        out.remove(key)
                    }
                }
                return out
            }
            require(projection(old) == projection(fresh)) { "Geo-only request changed non-fallback producer content" }
            return actual
        }
    }
}

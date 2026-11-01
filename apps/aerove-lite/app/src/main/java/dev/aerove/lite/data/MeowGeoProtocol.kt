package dev.aerove.lite.data

import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.geoExact(vararg keys: String) {
    require(this.keys().asSequence().toSet() == keys.toSet()) { "Invalid owned Geo protocol fields" }
}
internal fun JSONObject.geoText(key: String): String = get(key) as? String ?: error("Invalid owned Geo string: $key")
internal fun geoDecimal(value: String, positive: Boolean = false): String {
    require(value.toULongOrNull()?.let { it.toString() == value && (!positive || it > 0uL) } == true) { "Invalid owned Geo decimal" }
    return value
}
internal fun geoHash(value: String): String {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid owned Geo hash" }; return value
}

data class MeowGeoStamp(val coreEpoch: String, val routeVersion: String, val selectionIntent: String) {
    init { geoDecimal(coreEpoch,true);geoDecimal(routeVersion);geoDecimal(selectionIntent) }
    fun json() = JSONObject().put("coreEpoch",coreEpoch).put("routeVersion",routeVersion).put("selectionIntent",selectionIntent)
    companion object {
        fun decode(row: JSONObject): MeowGeoStamp {
            row.geoExact("coreEpoch","routeVersion","selectionIntent")
            return MeowGeoStamp(row.geoText("coreEpoch"),row.geoText("routeVersion"),row.geoText("selectionIntent"))
        }
    }
}

data class MeowGeoExpected(val stamp: MeowGeoStamp, val hostBinding: MeowGeoHostBinding) {
    fun json() = stamp.json().put("hostBinding",hostBinding.json())
}

data class MeowGeoToken(val transactionId: String, val operationSerial: String) {
    init { require(UUID.fromString(transactionId).toString() == transactionId);geoDecimal(operationSerial,true) }
    fun json() = JSONObject().put("schemaVersion",1).put("transactionId",transactionId).put("operationSerial",operationSerial)
    companion object {
        fun decode(row: JSONObject): MeowGeoToken {
            row.geoExact("schemaVersion","transactionId","operationSerial");require(row.get("schemaVersion") == 1)
            return MeowGeoToken(row.geoText("transactionId"),row.geoText("operationSerial"))
        }
    }
}

data class MeowGeoAsset(val kind: String, val relativePath: String, val bytes: String, val sha256: String) {
    init {
        require(kind in setOf("geoip","asn","geosite"));geoDecimal(bytes,true);geoHash(sha256)
        require(relativePath.isNotEmpty() && relativePath.length <= 16384 && relativePath.none { it in "\u0000\\:" } &&
            !relativePath.startsWith('/') && relativePath.split('/').none { it in setOf("",".","..") })
    }
    fun json() = JSONObject().put("kind",kind).put("relativePath",relativePath).put("bytes",bytes).put("sha256",sha256)
    companion object {
        fun decode(row: JSONObject): MeowGeoAsset {
            row.geoExact("kind","relativePath","bytes","sha256")
            return MeowGeoAsset(row.geoText("kind"),row.geoText("relativePath"),row.geoText("bytes"),row.geoText("sha256"))
        }
    }
}

class MeowGeoCandidate(val compiledYaml: String, val compiledSha256: String, val engineYaml: String,
    val readRoot: File, val resourceLeaseId: String, assets: List<MeowGeoAsset>) {
    val assets: List<MeowGeoAsset> = assets.toList()
    init {
        require(sha256(compiledYaml) == geoHash(compiledSha256));require(UUID.fromString(resourceLeaseId).toString() == resourceLeaseId && readRoot.name == resourceLeaseId)
        require(this.assets.size in 0..3 && this.assets.map { it.kind }.toSet().size == this.assets.size && this.assets.map { it.relativePath }.toSet().size == this.assets.size)
        if(this.assets.isEmpty()) {
            val root=ConfigYaml.parse(engineYaml);val geo=root["geodata"] as? Map<*,*>
            require(listOf("mmdb-path","asn-path","geosite-path").none { geo?.containsKey(it) == true } &&
                listOf("mmdb","asn-mmdb","geosite").none(root::containsKey)) { "Zero-asset Geo candidate has explicit Geo inputs" }
            // The existing Native graph owner must additionally prove no rules/provider/DNS Geo reads.
        }
    }
    fun json() = JSONObject().put("compiledYaml",compiledYaml).put("compiledSha256",compiledSha256).put("engineYaml",engineYaml)
        .put("readRoot",readRoot.path).put("resourceLeaseId",resourceLeaseId).put("assets",JSONArray(assets.map { it.json() }))
}

data class MeowGeoApplied(val token: MeowGeoToken, val before: MeowGeoStamp, val after: MeowGeoStamp,
    val readRoot: String, val resourceLeaseId: String, val compiledSha256: String, val engineSha256: String,
    val actualConfigSha256: String, val sourceAssets: List<MeowGeoAsset>) {
    fun verify(candidate: MeowGeoCandidate, expected: MeowGeoExpected) {
        require(before == expected.stamp && after.coreEpoch == before.coreEpoch && after.selectionIntent == before.selectionIntent &&
            after.routeVersion.toULong() > before.routeVersion.toULong() && readRoot == candidate.readRoot.path && resourceLeaseId == candidate.resourceLeaseId &&
            compiledSha256 == candidate.compiledSha256 && engineSha256 == sha256(candidate.engineYaml) && sourceAssets.toSet() == candidate.assets.toSet()) { "Owned Geo applied receipt differs from the actual candidate" }
    }
    companion object {
        fun decode(row: JSONObject): MeowGeoApplied {
            row.geoExact("token","before","after","readRoot","resourceLeaseId","compiledSha256","engineSha256","actualConfigSha256","sourceAssets","effectiveStorage","sealedSnapshot")
            val assets=row.getJSONArray("sourceAssets").let { array -> (0 until array.length()).map { MeowGeoAsset.decode(array.getJSONObject(it)) } }
            require(if(assets.isEmpty()) row.geoText("effectiveStorage") == "none" && row.get("sealedSnapshot") == false
                else row.geoText("effectiveStorage") == "owned-sealed-memfd" && row.get("sealedSnapshot") == true)
            return MeowGeoApplied(MeowGeoToken.decode(row.getJSONObject("token")),MeowGeoStamp.decode(row.getJSONObject("before")),MeowGeoStamp.decode(row.getJSONObject("after")),
                row.geoText("readRoot"),row.geoText("resourceLeaseId"),geoHash(row.geoText("compiledSha256")),geoHash(row.geoText("engineSha256")),geoHash(row.geoText("actualConfigSha256")),assets)
        }
    }
}

data class MeowGeoStatus(val token: MeowGeoToken, val state: String, val error: String?, val sourceReadsJoined: Boolean,
    val applied: MeowGeoApplied?, val current: MeowGeoStamp) {
    val terminal: Boolean get() = state in setOf("committed","aborted","failed")
    companion object {
        fun decode(source: String): MeowGeoStatus {
            val row=JSONObject(source);row.geoExact("token","state","error","sourceReadsJoined","applied","current")
            val state=row.geoText("state");require(state in setOf("preparing","prepared","committing","committed","aborting","aborted","failed"))
            val joined=row.get("sourceReadsJoined") as? Boolean ?: error("Invalid Geo join receipt")
            val applied=if(row.isNull("applied")) null else MeowGeoApplied.decode(row.getJSONObject("applied"))
            val token=MeowGeoToken.decode(row.getJSONObject("token"));require((state == "committed") == (applied != null) && (applied == null || applied.token == token))
            require(!joined || state in setOf("committed","aborted","failed"))
            return MeowGeoStatus(token,state,if(row.isNull("error"))null else row.geoText("error"),joined,applied,MeowGeoStamp.decode(row.getJSONObject("current")))
        }
    }
}


data class MeowSelectionWitness(val witnessId: String, val complete: Boolean, val stamp: MeowGeoStamp,
    val acknowledgedSelectionIntent: String, val acknowledgedCoreEpoch: String?, val initialStartupWitnessId: String?, val graphVersion: String,
    val rawRequested: Map<String,String>, val normalizedRawRequested: Map<String,String>,
    val groups: Map<String,StartupSelectionGroup>, val ignored: Map<String,String>) {
    fun matchesRaw(raw: Map<String,String>): Boolean = complete && normalize(raw) == normalizedRawRequested
    private fun normalize(raw: Map<String,String>) = raw.filterNot { (name,value) -> value.isEmpty() &&
        groups[name]?.type in setOf("URLTest","url-test","Fallback","fallback") }
    companion object {
        fun decode(source: String): MeowSelectionWitness {
            val row=JSONObject(source)
            row.geoExact("schemaVersion","witnessId","complete","coreEpoch","routeVersion","selectionIntent","acknowledgedSelectionIntent","acknowledgedCoreEpoch","initialStartupWitnessId","graphVersion","rawRequested","normalizedRawRequested","groups","ignored")
            require(row.get("schemaVersion") == 1)
            fun strings(value:JSONObject)=value.keys().asSequence().associateWith(value::geoText)
            val groups=row.getJSONObject("groups").let { values -> values.keys().asSequence().associateWith { name ->
                val group=values.getJSONObject(name);group.geoExact("type","current","fixed","members")
                val members=group.getJSONArray("members").let { array -> (0 until array.length()).map { array.get(it) as? String ?: error("Invalid selection member") } }
                require(members.isNotEmpty() && group.geoText("current") in members)
                StartupSelectionGroup(group.geoText("type"),group.geoText("current"),if(group.isNull("fixed"))null else group.geoText("fixed"),members)
            } }
            val witness=MeowSelectionWitness(geoDecimal(row.geoText("witnessId")),row.get("complete") as? Boolean ?: error("Invalid selection completeness"),
                MeowGeoStamp(row.geoText("coreEpoch"),row.geoText("routeVersion"),row.geoText("selectionIntent")),geoDecimal(row.geoText("acknowledgedSelectionIntent")),
                if(row.isNull("acknowledgedCoreEpoch"))null else geoDecimal(row.geoText("acknowledgedCoreEpoch"),true),
                if(row.isNull("initialStartupWitnessId"))null else geoDecimal(row.geoText("initialStartupWitnessId"),true),geoDecimal(row.geoText("graphVersion"),true),
                strings(row.getJSONObject("rawRequested")),strings(row.getJSONObject("normalizedRawRequested")),groups,strings(row.getJSONObject("ignored")))
            require(witness.normalize(witness.rawRequested) == witness.normalizedRawRequested)
            require(witness.ignored.all { (key,value) -> key !in groups && witness.rawRequested[key] == value })
            return witness
        }
    }
}

package dev.aerove.lite.data

import dev.aerove.lite.resources.GeoResourceKind
import dev.aerove.lite.resources.GeoResourcePreferences
import org.json.JSONObject

/** Sparse explicit profile/script intent, produced before global defaults or cached URL hints. */
internal class MeowGeoOrigin private constructor(val autoUpdate:Boolean?,val intervalHours:Int?,
    urls:Map<GeoResourceKind,String>) {
    val urls:Map<GeoResourceKind,String> = urls.toMap()
    fun apply(global:GeoResourcePreferences):GeoResourcePreferences = global.copy(
        autoUpdate=autoUpdate ?: global.autoUpdate,intervalHours=intervalHours ?: global.intervalHours,
        urls=global.urls + urls).validate()
    fun json():JSONObject = JSONObject().put("schema",1).put("source",JSONObject().apply {
        autoUpdate?.let {put("auto-update",it)};intervalHours?.let {put("auto-update-interval",it)}
        if(urls.isNotEmpty())put("url",JSONObject().apply {urls.forEach { (kind,url)->put(kind.wire,url) }})
    })
    companion object {
        const val KEY="aerove-geo-origin"
        fun read(root:Map<String,Any?>):MeowGeoOrigin? {
            if(!root.containsKey(KEY))return null
            return decode(root[KEY],MeowGeoPolicy.read(root,GeoResourcePreferences()))
        }
        fun stored(value:JSONObject):MeowGeoOrigin = decode(ConfigYaml.parse(value.toString()),null)
        private fun fail(path:String,reason:String):Nothing=throw ConfigValidationException(listOf(ConfigIssue(path,reason)))
        private fun decode(value:Any?,actual:GeoResourcePreferences?):MeowGeoOrigin {
            val path="$.$KEY"
            val root=value as? Map<*,*> ?: fail(path,"Expected typed Host origin metadata")
            root.keys.forEach { if(it !in setOf("schema","source"))fail("$path.$it","Unknown origin field") }
            if(root.keys != setOf("schema","source") || root["schema"] != 1)fail("$path.schema","Expected origin schema 1")
            val source=root["source"] as? Map<*,*> ?: fail("$path.source","Expected sparse source policy")
            source.keys.forEach { if(it !in setOf("auto-update","auto-update-interval","url"))fail("$path.source.$it","Unknown source policy") }
            val normalized=MeowGeoPolicy.read(mapOf("geodata" to source),GeoResourcePreferences())
            val auto=if(source.containsKey("auto-update"))normalized.autoUpdate else null
            val interval=if(source.containsKey("auto-update-interval"))normalized.intervalHours else null
            val keys=(source["url"] as? Map<*,*>)?.keys.orEmpty()
            val urls=GeoResourceKind.entries.filter { it.wire in keys }.associateWith {normalized.urls.getValue(it)}
            if(actual != null) {
                if(auto != null && auto != actual.autoUpdate)fail("$path.source.auto-update","Origin differs from actual geodata")
                if(interval != null && interval != actual.intervalHours)fail("$path.source.auto-update-interval","Origin differs from actual geodata")
                urls.forEach { (kind,url)->if(url != actual.urls.getValue(kind))fail("$path.source.url.${kind.wire}","Origin differs from actual geodata") }
            }
            return MeowGeoOrigin(auto,interval,urls)
        }
    }
}

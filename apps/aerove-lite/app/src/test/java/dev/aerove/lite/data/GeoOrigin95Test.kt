package dev.aerove.lite.data

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import dev.aerove.lite.resources.GeoResourcePreferences
import dev.aerove.lite.resources.GeoResourcePreferencesJson

class GeoOrigin95Test {
    @Test fun actualTaskProducerIsStrictlyConsumedPersistedAndRebasedWithoutCoveringProfileIntent() {
        val path=System.getProperty("origin95.fixture")
        org.junit.Assume.assumeNotNull("Supply the generated Dart producer-fixtures.json",path)
        val fixture=JSONObject(File(checkNotNull(path)).readText())
        val source=fixture.getString("initialYaml")
        val root=ConfigYaml.parse(source)
        val origin=checkNotNull(MeowGeoOrigin.read(root))
        assertEquals(ConfigYaml.parse(fixture.getJSONObject("expectedOrigin").toString()),ConfigYaml.parse(origin.json().toString()))
        val changedGlobal=GeoResourcePreferencesJson.decode(fixture.getJSONObject("changedGlobal"))
        val expected=MeowGeoPolicy.read(mapOf("geodata" to ConfigYaml.parse(fixture.getJSONObject("expectedAfterGlobal").toString())),GeoResourcePreferences())
        assertEquals(expected,origin.apply(changedGlobal))
        assertEquals(expected,MeowGeoOrigin.stored(origin.json()).apply(changedGlobal))
        val rerun=ConfigYaml.parse(fixture.getString("rerunYaml"))
        assertEquals(expected,MeowGeoPolicy.read(rerun,GeoResourcePreferences()))
        assertEquals(ConfigYaml.parse(origin.json().toString()),ConfigYaml.parse(checkNotNull(MeowGeoOrigin.read(rerun)).json().toString()))
        val receipt=MeowTunReconciliation.reconcile(source,dev.aerove.lite.LiteSettings())
        val (sanitized,_)=MeowSourceConfig.input(source,receipt)
        assertFalse(sanitized.containsKey(MeowGeoOrigin.KEY))
        assertEquals(MeowGeoPolicy.read(root,GeoResourcePreferences()),MeowGeoPolicy.read(sanitized,GeoResourcePreferences()))
    }
    @Test fun unknownOrMismatchedOriginFailsAndAbsencePreservesLegacyContract() {
        val base=mapOf<String,Any?>("geodata" to mapOf("auto-update" to false,"auto-update-interval" to 24))
        assertNull(MeowGeoOrigin.read(base))
        val bad=listOf(
            mapOf("schema" to 2,"source" to emptyMap<String,Any?>()),
            mapOf("schema" to 1,"source" to mapOf("auto-update" to true)),
            mapOf("schema" to 1,"source" to mapOf("auto-update-interval" to 48)),
            mapOf("schema" to 1,"source" to mapOf("unknown" to true)),
            mapOf("schema" to 1,"source" to mapOf("url" to mapOf("geoip" to "https://fixture.invalid/dat"))),
            mapOf("schema" to 1,"source" to emptyMap<String,Any?>(),"unknown" to true)
        )
        for(metadata in bad) {
            val failure=runCatching {MeowGeoOrigin.read(base + (MeowGeoOrigin.KEY to metadata))}.exceptionOrNull()
            assertTrue("Origin strictness must reject an invalid source fact",failure is ConfigValidationException)
            assertTrue(failure!!.message.orEmpty().contains("geo"))
        }
        val empty=checkNotNull(MeowGeoOrigin.read(base + (MeowGeoOrigin.KEY to mapOf("schema" to 1,"source" to emptyMap<String,Any?>()))))
        val global=GeoResourcePreferences(autoUpdate=true,intervalHours=72)
        assertEquals(global,empty.apply(global))
    }
}

package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowLegacyMigrationOwnerTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun realDomainOwnerBlocksLegacyWritesAndOnlyVerifiedReceiptCanTransferAuthority()=runBlocking<Unit> {
        val root=temporary.newFolder()
        val profiles=ProfileRepository(root,ProfileValidator { })
        profiles.importText("Fixture","mode: rule\nrules: ['MATCH,DIRECT']\n")
        val settings=LiteSettingsStore(root);settings.save(LiteSettings())
        val overrides=dev.aerove.lite.overrides.OverrideRepository(root)
        val exporter=MeowLegacyMigrationExport(root,profiles,settings,overrides)
        var verifications=0
        val owner=MeowLegacyMigrationOwner(root,exporter,verifyTarget={file,digest->
            check(sha256(file)==digest)
            check(JSONObject(file.readText()).getString("sourceDigest").matches(Regex("[0-9a-f]{64}")))
            verifications++
        })
        val before=File(root,"profiles/index.json").readBytes()
        val opened=owner.begin("engine-a")
        assertEquals("pending",opened["status"])
        assertTrue(runCatching{owner.begin("engine-b")}.isFailure)
        assertEquals(opened,owner.begin("engine-a"))
        assertTrue(runCatching{settings.save(LiteSettings())}.isFailure)
        assertTrue(runCatching{profiles.snapshot()}.isFailure)
        val token=opened["leaseId"] as String
        owner.validate(token,opened["sha256"] as String)
        val receipt=File(root,"native-lite-migration/target-receipt.json").apply{parentFile.mkdirs()}
        val plan=File(root,"native-lite-migration/target-files.json")
        plan.writeText(JSONObject().put("schema",1).put("sourceDigest",opened["sha256"]).put("files",JSONArray()).toString())
        owner.prepareFiles(token,plan.path,sha256(plan))
        receipt.writeText(JSONObject().put("schema",1).put("sourceDigest",opened["sha256"]).put("files",JSONArray()).toString())
        assertTrue(runCatching{owner.commit(token,receipt.path,"0".repeat(64))}.isFailure)
        assertEquals("pending",NativeLiteDataAuthority.phase(root))
        owner.commit(token,receipt.path,sha256(receipt))
        assertEquals(1,verifications)
        assertEquals("shared",NativeLiteDataAuthority.phase(root))
        assertEquals("shared",owner.begin("engine-b")["status"])
        owner.confirmTransferred(opened["sha256"] as String,sha256(receipt))
        assertArrayEquals(before,File(root,"profiles/index.json").readBytes())
        owner.release(token)
    }
    @Test fun receiptVerifierFailureAndEngineDetachNeverMarkCompleteAndAllowRetry()=runBlocking<Unit> {
        val root=temporary.newFolder()
        val profiles=ProfileRepository(root,ProfileValidator { });profiles.importText("Fixture","mode: direct\n")
        val exporter=MeowLegacyMigrationExport(root,profiles,LiteSettingsStore(root),dev.aerove.lite.overrides.OverrideRepository(root))
        val owner=MeowLegacyMigrationOwner(root,exporter,verifyTarget={_,_->error("fixture_target_rejected")})
        val first=owner.begin("a")
        val receipt=File(root,"native-lite-migration/target-receipt.json").apply{parentFile.mkdirs()}
        val plan=File(root,"native-lite-migration/target-files.json")
        plan.writeText(JSONObject().put("schema",1).put("sourceDigest",first["sha256"]).put("files",JSONArray()).toString())
        owner.prepareFiles(first["leaseId"] as String,plan.path,sha256(plan))
        receipt.writeText(JSONObject().put("schema",1).put("sourceDigest",first["sha256"]).put("files",JSONArray()).toString())
        assertTrue(runCatching{owner.commit(first["leaseId"] as String,receipt.path,sha256(receipt))}.isFailure)
        assertEquals("pending",NativeLiteDataAuthority.phase(root))
        owner.releaseSession("a")
        val retry=owner.begin("b")
        assertEquals(first["sha256"],retry["sha256"])
        assertNotEquals(first["leaseId"],retry["leaseId"])
        owner.releaseSession("b")
    }
}

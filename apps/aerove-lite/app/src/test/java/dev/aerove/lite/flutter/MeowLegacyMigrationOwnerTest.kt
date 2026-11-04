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
    private fun trustedAlias(root: File): File {
        val alias = File(temporary.newFolder(), "files")
        val made = runCatching { java.nio.file.Files.createSymbolicLink(alias.toPath(), root.toPath()) }
        if (System.getProperty("os.name").startsWith("Windows"))
            org.junit.Assume.assumeTrue("Windows host must permit symbolic links", made.isSuccess)
        made.getOrThrow()
        return alias
    }

    @Test fun trustedRootAliasSupportsAbsentAndCancelledLegacyRetryToShared()=runBlocking<Unit> {
        val physical=temporary.newFolder().canonicalFile
        val root=trustedAlias(physical)
        val profiles=ProfileRepository(root,ProfileValidator { })
        val exporter=MeowLegacyMigrationExport(root,profiles,LiteSettingsStore(root),dev.aerove.lite.overrides.OverrideRepository(root))
        var cancelTransfer=true
        var verifications=0
        val owner=MeowLegacyMigrationOwner(root,exporter,verifyTarget={_,_->
            verifications++
            if(cancelTransfer) throw kotlinx.coroutines.CancellationException("fixture_cancelled_before_transfer")
        })
        assertNull(NativeLiteDataAuthority.phase(root))
        privateDomainTransaction(root) { }
        assertEquals(mapOf("status" to "absent"),owner.begin("empty"))
        profiles.importText("Alias fixture","mode: direct\n")
        val original=File(physical,"profiles/index.json").readBytes()
        val first=owner.begin("cancelled-engine")
        assertEquals("pending",first["status"])
        assertTrue(File(first["manifest"] as String).toPath().startsWith(physical.toPath()))
        val token=first["leaseId"] as String
        val plan=File(physical,"native-lite-migration/target-files.json").apply{parentFile.mkdirs()}
        plan.writeText(JSONObject().put("schema",1).put("sourceDigest",first["sha256"]).put("files",JSONArray()).toString())
        owner.prepareFiles(token,plan.path,sha256(plan))
        val receipt=File(physical,"native-lite-migration/target-receipt.json")
        receipt.writeText(JSONObject().put("schema",1).put("sourceDigest",first["sha256"]).put("files",JSONArray()).toString())
        val cancelled=runCatching{owner.commit(token,receipt.path,sha256(receipt))}.exceptionOrNull()
        assertTrue(cancelled is kotlinx.coroutines.CancellationException)
        assertEquals("pending",NativeLiteDataAuthority.phase(root))
        owner.releaseSession("cancelled-engine")
        assertFalse(File(first["manifest"] as String).exists())
        cancelTransfer=false
        val retry=owner.begin("retry-engine")
        assertEquals(first["sha256"],retry["sha256"])
        assertNotEquals(token,retry["leaseId"])
        owner.validate(retry["leaseId"] as String,retry["sha256"] as String)
        owner.commit(retry["leaseId"] as String,receipt.path,sha256(receipt))
        assertEquals(2,verifications)
        assertEquals("shared",NativeLiteDataAuthority.phase(root))
        owner.confirmTransferred(retry["sha256"] as String,sha256(receipt))
        assertEquals("shared",owner.begin("later-engine")["status"])
        assertArrayEquals(original,File(physical,"profiles/index.json").readBytes())
        owner.releaseSession("retry-engine")
    }

    @Test fun trustedRootAliasStillRejectsJournalAndSourceSymlinks()=runBlocking<Unit> {
        val physical=temporary.newFolder().canonicalFile
        val root=trustedAlias(physical)
        val journal=File(physical,"native-lite-migration-native")
        val external=temporary.newFolder()
        java.nio.file.Files.createSymbolicLink(journal.toPath(),external.toPath())
        val authorityFailure=runCatching{NativeLiteDataAuthority.phase(root)}.exceptionOrNull()
        assertEquals("migration_authority_invalid",authorityFailure?.message)
        java.nio.file.Files.delete(journal.toPath())
        val profiles=ProfileRepository(root,ProfileValidator { })
        profiles.importText("Alias fixture","mode: direct\n")
        val sentinel=File(external,"sentinel").apply{writeText("outside remains unchanged")}
        val exporter=MeowLegacyMigrationExport(root,profiles,LiteSettingsStore(root),dev.aerove.lite.overrides.OverrideRepository(root))
        java.nio.file.Files.createSymbolicLink(File(physical,"settings/link").toPath(),sentinel.toPath())
        val failure=runCatching{exporter.export()}.exceptionOrNull()
        assertTrue(failure is MeowLegacyExportException)
        assertEquals("files",(failure as MeowLegacyExportException).legacyField)
        assertEquals("outside remains unchanged",sentinel.readText())
        assertFalse(File(physical,"native-lite-migration-exports").exists())
    }
    @Test fun trustedRootAliasRejectsDanglingAuthorityStateWithoutCreatingOutsideTarget() {
        val physical=temporary.newFolder().canonicalFile
        val root=trustedAlias(physical)
        val directory=File(physical,"native-lite-migration-native")
        assertTrue(directory.mkdir())
        assertNull(NativeLiteDataAuthority.phase(root))
        val outside=File(temporary.newFolder(),"missing-state.json")
        val state=File(directory,"state.json")
        java.nio.file.Files.createSymbolicLink(state.toPath(),outside.toPath())
        assertFalse(state.exists())
        val failure=runCatching{NativeLiteDataAuthority.phase(root)}.exceptionOrNull()
        assertEquals("migration_authority_invalid",failure?.message)
        assertTrue(java.nio.file.Files.isSymbolicLink(state.toPath()))
        assertFalse(outside.exists())
    }
}

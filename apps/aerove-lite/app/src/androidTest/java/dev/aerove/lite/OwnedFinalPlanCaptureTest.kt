package dev.aerove.lite

import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import org.junit.Test
import java.io.File

class OwnedFinalPlanCaptureTest : FlutterProductionUiTest() {
    @Test fun freshDefaultFinalPlanCapture() = scenario {
        val capture = OwnedSyntheticFinalPlanCapture(context, arguments, root)
        capture.use {
            dev.aerove.lite.vpn.MeowTunnelBackend.captureFinalPlansForOwnedQa(capture).use {
                completeProductionConsent(screenshots = false)
                check(capture.finished.await(60, java.util.concurrent.TimeUnit.SECONDS)) { "No final plan reached the actual start boundary" }
                capture.requireComplete()
                receipt.put("inputCaptureOnly", true).put("original312Input", false)
                    .put("nativeAccepted", false).put("finalPlanManifest", "final-plan/capture-manifest.json")
                stage = "fresh-default-final-plan-captured"
            }
        }
    }

}

/** Never a personal-phone producer. Only this test file is selected by the existing platform runner. */
private class OwnedSyntheticFinalPlanCapture(private val context: Context, private val args: Bundle,
    private val output: File) : dev.aerove.lite.vpn.MeowFinalPlanQaSink, AutoCloseable {
    val finished = java.util.concurrent.CountDownLatch(1)
    private val gate = Any()
    private var closed = false
    private var complete = false
    private var failure: Throwable? = null
    private var subsequentStarts = 0
    init {
        check(args.getString("qaFinalPlanSynthetic") == "fresh-default-v1")
        check(args.getString("qaFinalPlanOwnerSha256")?.matches(Regex("[0-9a-f]{64}")) == true)
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && (android.os.Build.PRODUCT.startsWith("sdk") || android.os.Build.MODEL.startsWith("sdk_gphone"))) { "Owned emulator only" }
        check(context.packageName == "dev.aerove.lite")
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.firstInstallTime == info.lastUpdateTime) { "Fresh installation required" }
        check(dev.aerove.lite.data.sha256(File(context.applicationInfo.sourceDir)) == args.getString("apkSha256"))
        val signer = info.signingInfo!!.apkContentsSigners.single().toByteArray()
        check(java.security.MessageDigest.getInstance("SHA-256").digest(signer).joinToString("") { "%02x".format(it) } == args.getString("qaFinalPlanSignerSha256"))
        check(listOf("runtime-handoffs/current.json", "runtime-handoffs/authority").none { File(context.filesDir, it).exists() }) { "Preexisting business state rejected" }
    }
    override fun capture(finalPlan: String, fd: Int, lease: dev.aerove.lite.vpn.MeowTunnelBackend.Lease) = synchronized(gate) {
        if (closed) return@synchronized // A backend may have read the sink immediately before unregister.
        failure?.let { throw it }
        if (complete) {
            subsequentStarts++
            File(output, "final-plan-subsequent-starts.json").writeText(JSONObject().put("filtered", true)
                .put("count", subsequentStarts).put("reason", "only first actual start boundary requested").toString())
            return@synchronized
        }
        try {
            val identity = JSONObject().put("syntheticQA", true).put("original312Input", false)
                .put("selector", "freshDefaultFinalPlanCapture").put("qaToken", args.getString("qaToken"))
                .put("source", args.getString("source")).put("tree", args.getString("tree"))
                .put("meowOverlaySha256", args.getString("meowOverlaySha256")).put("meowFileManifestSha256", args.getString("meowFileManifestSha256"))
                .put("workflow", args.getString("workflow")).put("runAttempt", args.getString("runAttempt"))
                .put("executorRun", args.getString("qaExecutorRun")).put("executorAttempt", args.getString("qaExecutorAttempt"))
                .put("signerSha256", args.getString("qaFinalPlanSignerSha256")).put("uid", android.os.Process.myUid())
                .put("firstInstallTime", context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime)
                .put("apkSha256", args.getString("apkSha256")).put("hostOwnerSha256", args.getString("qaFinalPlanOwnerSha256"))
            val sources = lease.finalPlanQaSources()
            dev.aerove.lite.vpn.MeowFinalPlanQaPacket.write(File(output, "final-plan"), identity, finalPlan, fd,
                sources, { lease.isCurrent() }, lease.protector != null, lease.connectionOwner != null) {
                val after = lease.finalPlanQaSources()
                check(after.revision == sources.revision && after.intentEpoch == sources.intentEpoch &&
                    after.runtimeSettingsJson == sources.runtimeSettingsJson && after.heldHandoffJson == sources.heldHandoffJson &&
                    after.readRoot == sources.readRoot && after.storeRoot == sources.storeRoot && after.appliedFile == sources.appliedFile)
            }
            complete = true
        } catch (error: Throwable) { failure = error; throw error }
        finally { finished.countDown() }
    }
    fun requireComplete() = synchronized(gate) { check(complete && failure == null) { "Final plan capture failed" } }
    override fun close() = synchronized(gate) { closed = true }
}

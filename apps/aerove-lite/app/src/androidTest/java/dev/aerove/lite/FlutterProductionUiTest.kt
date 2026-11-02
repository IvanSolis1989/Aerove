package dev.aerove.lite

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Black-box release driver: no Activity rule, alternate Dart main, or product-state injection. */
class FlutterProductionUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui = instrumentation.uiAutomation
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val actions = JSONArray()
    private val frames = JSONArray()
    private var stage = "initialization"
    private val token = requireNotNull(arguments.getString("qaToken")) {
        "A unique host-owned qaToken is required"
    }.also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private val root = File(requireNotNull(context.getExternalFilesDir(null)), "flutter-production-qa/$token")
    private val receipt = JSONObject().put("schema", 1).put("producer", javaClass.name)
        .put("screenshotMethod", "UiAutomation.takeScreenshot/Bitmap.compress(PNG)")
        .put("actions", actions).put("frames", frames).put("fixture", "fresh-empty-app-data")
        .put("performanceAccepted", false).put("visualApproved", false)

    @Test
    fun consentNavigationTheme12() = scenario {
        stage = "first-launch"
        launch()
        requireLabel("Agree")
        capture("first-consent")
        click("Agree")
        requireLabel("Restate the statement")
        stage = "select-copy-statement"
        copyStatement()
        stage = "actual-restatement"
        val field = awaitNode("restatement text field") { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, STATEMENT)
        })) { "Production Flutter field rejected ACTION_SET_TEXT" }
        awaitNode("committed full statement") { it.isEditable && it.text?.toString() == STATEMENT }
        action("set-text", field, "full production statement")
        click("Confirm")
        requireLabel("Got it")
        capture("glass-introduction")
        click("Got it")
        stage = "bottom-navigation"
        for (label in listOf("Dashboard", "Profiles", "Tools", "Dashboard")) navigate(label)
        for (material in listOf("Plain solid", "Soft frost", "Clear shimmer")) {
            for (backdrop in listOf("Classic monochrome", "Living Aurora")) {
                for (brightness in listOf("Light", "Dark")) {
                    stage = "theme/$material/$backdrop/$brightness"
                    navigate("Tools")
                    click("Theme", scroll = true)
                    requireLabel("Theme mode")
                    choose(brightness)
                    choose(backdrop)
                    choose(material)
                    val settingProof = capture("settings-${frames.length()}", JSONObject()
                        .put("material", material).put("backdrop", backdrop).put("brightness", brightness))
                    check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    requireLabel("Theme")
                    navigate("Dashboard")
                    capture("matrix-${slug(material)}-${slug(backdrop)}-${slug(brightness)}", JSONObject()
                        .put("material", material).put("backdrop", backdrop).put("brightness", brightness)
                        .put("settingsEvidence", settingProof).put("matrix", true))
                }
            }
        }
        check((0 until frames.length()).count { frames.getJSONObject(it).optJSONObject("configuration")?.optBoolean("matrix") == true } == 12)
        captureDockOpticalCandidates()
        stage = "theme12-captured-pending-visual-review"
    }

    /** The host kills the first process before invoking this method in a new instrumentation process. */
    @Test
    fun persistedConsentAfterProcessRestart() = scenario {
        stage = "restart-launch"
        launch()
        requireLabel("Dashboard")
        check(findLabel("Agree") == null && findLabel("Restate the statement") == null) {
            "Consent was not persisted by the production repository"
        }
        navigate("Dashboard")
        capture("persisted-consent")
        stage = "restart-consent-observed"
    }

    @Test
    fun profileImportApplyVpnNetworkAndBackgroundResume() = scenario {
        functionalReceipt("host-consented-empty-profiles")
        launch(); requireLabel("Dashboard")
        checkNoVpn()
        functionalNetworkSettings(allowChanges = true)
        val label = profileLabel()
        navigate("Profiles")
        check(findLabel(label) == null) { "Functional fixture must not reuse an existing profile" }
        openProfileForm()
        fillProfile(label + "-cancelled")
        capture("import-form-before-cancel")
        backFromImportForm()
        navigate("Profiles")
        check(findLabel(label + "-cancelled") == null) { "Cancelled form created a profile" }
        openProfileForm(); fillProfile(label)
        stage = "actual-profile-import"
        click("Submit")
        awaitCondition("completed production download and closed import form", 60_000) {
            findLabel("Import from link") == null && findLabel(label)?.isEditable == false
        }
        requireLabel(label)
        click(label)
        awaitCondition("production selected imported profile") { findLabel(label)?.let(::selected) == true }
        capture("profile-imported-selected")
        checkpoint("imported-selected", false)
        navigate("Dashboard")
        choose("Direct")
        startRealVpn()
        checkpoint("vpn-active", true)
        assertProxyFixture("vpn-active")
        val network = ownedVpn().single().networkHandle
        stage = "real-background"
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        awaitCondition("app left foreground") { ui.rootInActiveWindow?.packageName?.toString() != PACKAGE }
        assertProxyFixture("background-active")
        checkpoint("background-active", true)
        stage = "real-foreground-return"
        launch(); navigate("Dashboard"); requireLabel("Stop")
        check(ownedVpn().single().networkHandle == network) { "Activity resume replaced the actual VPN" }
        assertProxyFixture("resumed-active")
        checkpoint("resumed-active", true)
        capture("vpn-foreground-return")
        stopRealVpn()
        checkpoint("vpn-stopped", false)
        receipt.put("profileLabel", label)
        stage = "functional-stopped"
    }

    @Test
    fun cancelVpnAuthorizationThenRecover() = scenario {
        functionalReceipt("host-consented-selected-profile-vpn-permission-revoked")
        launch(); requireLabel("Dashboard")
        checkNoVpn()
        functionalNetworkSettings(allowChanges = true)
        require(android.net.VpnService.prepare(context) != null) {
            "Host must revoke VPN authorization before the cancellation branch"
        }
        navigate("Profiles"); requireLabel(profileLabel()); navigate("Dashboard")
        choose("Direct")
        stage = "vpn-permission-cancel"
        click("Start")
        permissionButton("Cancel")
        requireLabel("Start")
        checkNoVpn()
        check(android.net.VpnService.prepare(context) != null)
        checkpoint("vpn-permission-cancelled", false)
        capture("permission-cancelled")
        startRealVpn()
        checkpoint("vpn-active", true)
        assertProxyFixture("after-permission-recovery")
        capture("permission-recovered")
        stopRealVpn()
        checkpoint("vpn-stopped", false)
        stage = "permission-cancel-recovered-stopped"
    }

    @Test
    fun prepareImportedRunningSessionForProcessRestart() = scenario {
        functionalReceipt("host-consented-selected-profile-owned-process-restart")
        require(requiredArg("hostRestartBoundary") == "true") { "Host must own an asynchronous termination boundary" }
        launch(); requireLabel("Dashboard"); checkNoVpn()
        functionalNetworkSettings(allowChanges = true)
        navigate("Profiles"); requireLabel(profileLabel())
        check(selected(requireLabel(profileLabel())))
        navigate("Dashboard"); choose("Direct")
        startRealVpn(); assertProxyFixture("before-owned-process-restart")
        checkpoint("vpn-active", true)
        checkpoint("functional-running-for-host-restart", true)
        capture("owned-running-session-before-host-restart")
        receipt.put("profileLabel", profileLabel())
        stage = "functional-running-for-host-restart"
        receipt.put("stage", stage).put("status", "awaiting-host-process-termination")
            .put("scenarioPassed", false).put("expectedTermination", true)
            .put("preparedElapsedRealtimeMs", SystemClock.elapsedRealtime())
        val prepared = File(root, "receipt.partial")
        prepared.writeText(receipt.toString(2) + "\n")
        check(prepared.renameTo(File(root, "receipt.json")))
        awaitCondition("host-owned process termination; returning normally is forbidden", 45_000) { false }
    }

    /** The host must really end the prior process; this method cannot kill its own runner. */
    @Test
    fun persistedImportedProfileAfterProcessRestart() = scenario {
        functionalReceipt("host-ended-prior-functional-process")
        val previousToken = requiredArg("previousQaToken")
        require(previousToken.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && previousToken != token)
        val previousFile = File(requireNotNull(context.getExternalFilesDir(null)),
            "flutter-production-qa/$previousToken/receipt.json")
        val previous = JSONObject(previousFile.readText())
        if (previous.optBoolean("expectedTermination")) {
            check(previous.getString("stage") == "functional-running-for-host-restart" &&
                previous.getString("status") == "awaiting-host-process-termination" &&
                !previous.getBoolean("scenarioPassed"))
            check(digest(previousFile.readBytes()) == requiredArg("previousBoundarySha256")) {
                "Host must bind the exact prepared receipt it used before killing the process"
            }
        } else {
            check(previous.getBoolean("scenarioPassed") && previous.getString("stage") == "functional-stopped")
        }
        for (field in listOf("apkSha256", "source", "tree")) {
            check(previous.getString(field) == requiredArg(field)) { "Restart evidence identity mismatch: $field" }
        }
        check(previous.getInt("pid") != android.os.Process.myPid()) { "Host reused the previous process" }
        check(previous.getString("profileLabel") == profileLabel())
        launch(); requireLabel("Dashboard")
        check(findLabel("Agree") == null && findLabel("Restate the statement") == null)
        navigate("Profiles"); requireLabel(profileLabel())
        check(selected(requireLabel(profileLabel()))) { "Imported profile selection did not persist" }
        navigate("Dashboard")
        functionalNetworkSettings(allowChanges = false)
        val expected = requiredArg("restartExpectedState")
        require(expected in setOf("running", "stopped")) { "Host must bind the real lifecycle policy" }
        if (expected == "running") {
            requireLabel("Stop")
            awaitCondition("owned VPN after process recovery") { ownedVpn().size == 1 }
        } else {
            requireLabel("Start"); checkNoVpn()
        }
        checkpoint("restart-observed", expected == "running")
        capture("persisted-import-after-process-restart")
        if (expected == "stopped") startRealVpn()
        checkpoint("vpn-active", true)
        assertProxyFixture("restarted-real-network")
        stopRealVpn(); checkpoint("vpn-stopped", false)
        stage = "profile-process-recovery-stopped"
    }

    private fun functionalReceipt(fixture: String) {
        receipt.put("fixture", fixture).put("nativeMeowAccepted", false)
            .put("fullAcceptance", false).put("tunSeparateUidAccepted", false)
            .put("networkRequests", org.json.JSONArray()).put("nativeAttestations", org.json.JSONArray())
    }

    private fun requiredArg(name: String): String =
        requireNotNull(arguments.getString(name)?.takeIf { it.isNotBlank() }) { "Host-owned $name is required" }

    private fun profileLabel(): String = requiredArg("profileLabel").also {
        require(it.matches(Regex("[a-zA-Z0-9_-]{1,50}")))
    }

    private fun controlledUrl(name: String): android.net.Uri = android.net.Uri.parse(requiredArg(name)).also {
        require(it.scheme == "http" && it.host == "10.0.2.2" && it.port in 1..65535 &&
            it.userInfo == null && it.fragment == null && it.pathSegments.none { part -> part == ".." }) {
            "$name must name the isolated existing emulator fixture"
        }
    }

    private fun mixedProxyPort(): Int = requiredArg("mixedProxyPort").toInt().also {
        require(it in 1024..49151) { "Match the production Port form range" }
    }

    private fun rowControl(label: String, editable: Boolean): AccessibilityNodeInfo {
        val title = reveal(label)
        val area = bounds(title)
        val controls = nodes().filter { node ->
            val box = bounds(node)
            node.isVisibleToUser && node.packageName?.toString() == PACKAGE &&
                (if (editable) node.isEditable && !node.isPassword else node.isCheckable) &&
                (matches(node, label) || box.right > area.right) &&
                kotlin.math.abs(box.centerY() - area.centerY()) <= maxOf(box.height(), area.height()) / 2
        }
        check(controls.size == 1) { "No unambiguous accessibility control in production row $label" }
        return controls.single()
    }

    private fun functionalNetworkSettings(allowChanges: Boolean) {
        stage = "actual-functional-network-settings"
        if (allowChanges) checkNoVpn()
        val port = mixedProxyPort().toString()
        navigate("Tools"); click("General", scroll = true); click("Port", scroll = true)
        var mixed = rowControl("Mixed port", editable = true)
        if (allowChanges) {
            check(mixed.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, port)
            })) { "Production mixed port field rejected ACTION_SET_TEXT" }
            awaitCondition("mixed port field committed") { rowControl("Mixed port", true).text?.toString() == port }
            action("set-mixed-port", mixed, port)
            click("Save")
            awaitCondition("production port form saved") { findLabel("Mixed port") == null }
            click("Port", scroll = true)
            mixed = rowControl("Mixed port", editable = true)
        }
        check(mixed.text?.toString() == port) { "Saved production mixed port does not equal host allocated port" }
        val proofs = JSONArray().put(capture("network-mixed-port-readback"))
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        awaitCondition("port readback form closed") { findLabel("Mixed port") == null }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        navigate("Tools"); click("Advanced configuration", scroll = true); click("Network", scroll = true)
        val privateLabel = "Bypass private addresses"
        val bypass = rowControl(privateLabel, editable = false)
        if (allowChanges && bypass.isChecked) {
            performClick(bypass); action("disable-private-route-bypass", bypass, privateLabel)
        }
        awaitCondition("private address bypass disabled in production UI") { !rowControl(privateLabel, false).isChecked }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        awaitCondition("network settings route closed") { findLabel(privateLabel) == null }
        click("Network", scroll = true)
        check(!rowControl(privateLabel, false).isChecked) { "Private bypass setting did not survive route reopening" }
        proofs.put(capture("network-private-bypass-readback"))
        receipt.put("networkSettingsUiEvidence", proofs).put("uiReadbackMixedPort", mixedProxyPort())
            .put("uiReadbackBypassPrivateRoute", false)
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        awaitCondition("network readback route closed") { findLabel(privateLabel) == null }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        navigate("Dashboard")
    }

    private fun openProfileForm() {
        stage = "profile-import-form"
        click("Add profile", scroll = true); click("Link", scroll = true)
        requireLabel("Import from link")
    }

    private fun fillProfile(label: String) {
        controlledUrl("profileUrl")
        fun set(hint: String, value: String) {
            var candidates = emptyList<AccessibilityNodeInfo>()
            awaitCondition("unambiguous production editable field $hint") {
                candidates = nodes().filter { it.isVisibleToUser && it.isEditable && !it.isPassword &&
                    (it.hintText?.toString() == hint || matches(it, hint)) }
                candidates.size == 1
            }
            val field = candidates.single()
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }))
            awaitNode("committed field $hint") { it.isEditable && it.text?.toString() == value }
            action("set-text", field, hint)
        }
        set("Optional", label)
        set("Required", requiredArg("profileUrl"))
    }

    private fun backFromImportForm() {
        var attempts = 0
        while (findLabel("Import from link") != null && attempts++ < 4) {
            check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitCondition("keyboard or route responds", 600, required = false) { findLabel("Import from link") == null }
        }
        check(findLabel("Import from link") == null) { "Production form did not cancel" }
        if (findLabel("Link") != null) {
            check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitCondition("add-profile route closed") { findLabel("Link") == null }
        }
    }

    private fun ownedVpn(): List<android.net.Network> {
        val manager = requireNotNull(context.getSystemService(android.net.ConnectivityManager::class.java))
        return manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.let {
                it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == context.applicationInfo.uid
            } == true
        }
    }

    private fun checkNoVpn() {
        val manager = requireNotNull(context.getSystemService(android.net.ConnectivityManager::class.java))
        check(manager.allNetworks.none { network -> manager.getNetworkCapabilities(network)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }) {
            "Refusing to replace an existing VPN; host must isolate its fixture"
        }
    }

    private fun permissionButton(label: String) {
        val button = awaitNode("real Android VPN permission $label") {
            it.packageName?.toString() == "com.android.vpndialogs" && matches(it, label)
        }
        performClick(button); action("vpn-permission", button, label)
    }

    private fun startRealVpn() {
        stage = "actual-start-vpn"
        val permission = android.net.VpnService.prepare(context) != null
        click("Start")
        if (permission) permissionButton("OK")
        requireLabel("Stop")
        awaitCondition("real app-owned Android VPN", 60_000) { ownedVpn().size == 1 }
        capture("actual-vpn-started-${frames.length()}")
    }

    private fun stopRealVpn() {
        stage = "actual-stop-vpn"
        click("Stop"); requireLabel("Start")
        awaitCondition("real owned VPN released", 30_000) { ownedVpn().isEmpty() }
        capture("actual-vpn-stopped-${frames.length()}")
    }

    private fun assertProxyFixture(label: String) {
        val fixture = controlledUrl("fixtureUrl")
        require(fixture.path.isNullOrEmpty() || fixture.path == "/")
        val port = mixedProxyPort()
        check(receipt.getInt("uiReadbackMixedPort") == port) { "No real saved UI port readback" }
        val requestToken = java.util.UUID.randomUUID().toString()
        val target = "${fixture.toString().trimEnd('/')}/$requestToken"
        val body = java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write(("GET $target HTTP/1.0\r\nHost: ${fixture.authority}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val bytes = socket.getInputStream().readNBytes(65_537)
            check(bytes.size <= 65_536) { "Controlled proxy reply exceeded bounded fixture size" }
            bytes.toString(Charsets.UTF_8)
        }
        check(body.lineSequence().first().split(' ').getOrNull(1) == "200") { "Actual proxy request failed" }
        check(body.substringAfter("\r\n\r\n").contains("AEROVE_LITE_FIXTURE:$requestToken")) {
            "The actual listener returned no unique controlled fixture echo"
        }
        receipt.getJSONArray("networkRequests").put(JSONObject().put("stage", label)
            .put("requestToken", requestToken).put("responseSha256", digest(body.toByteArray())))
    }

    private fun checkpoint(name: String, expectedVpn: Boolean, runtimeDevice: JSONObject? = null) {
        require(name.matches(Regex("[a-z-]{1,60}")))
        val folder = File(root, "checkpoints").also { check(it.isDirectory || it.mkdirs()) }
        val value = JSONObject().put("schema", 1).put("qaToken", token)
            .put("stage", name).put("pid", android.os.Process.myPid())
            .put("nonce", java.util.UUID.randomUUID().toString()).put("expectedVpn", expectedVpn)
            .put("networkHandles", JSONArray(ownedVpn().map { it.networkHandle.toString() }))
            .put("apkSha256", requiredArg("apkSha256")).put("source", requiredArg("source"))
            .put("tree", requiredArg("tree")).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("expectedMixedPort", mixedProxyPort()).put("expectedBypassPrivateRoute", false)
            .put("fixtureRouteTarget", "10.0.2.2")
            .put("networkSettingsUiEvidence", receipt.getJSONArray("networkSettingsUiEvidence"))
        if (runtimeDevice != null) value.put("checkpointKind", "runtime-device").put("runtimeDevice", runtimeDevice)
        arguments.getString("previousQaToken")?.let { value.put("previousQaToken", it) }
        arguments.getString("restartExpectedState")?.let { value.put("restartExpectedState", it) }
        arguments.getString("hostRestartBoundary")?.let { value.put("hostRestartBoundary", it) }
        val request = File(folder, "$name.json")
        check(!request.exists()) { "Checkpoint must not overwrite an earlier event" }
        val temporary = File(folder, "$name.partial")
        temporary.writeText(value.toString(2) + "\n")
        check(temporary.renameTo(request)) { "Atomic checkpoint publication failed" }
        receipt.put("nativeEvidencePending", true)
        val ackFile = File(root, "native-acks/$name.json")
        awaitCondition("independent native platform attestation $name", if (runtimeDevice != null) 60_000 else 45_000) { ackFile.isFile }
        val ack = JSONObject(ackFile.readText())
        check(ack.getInt("schema") == 1)
        for (field in listOf("qaToken", "stage", "nonce", "source", "tree", "apkSha256")) {
            check(ack.getString(field) == value.getString(field)) { "Native ACK identity mismatch: $field" }
        }
        check(ack.getInt("pid") == android.os.Process.myPid()) { "Native observer ran in a different target process" }
        check(ack.getString("requestSha256") == digest(request.readBytes())) { "Native ACK is not bound to this request" }
        check(ack.getString("status") == "attested") { "Real native attestation failed: ${ack.optString("error")}" }
        val relative = ack.getString("evidencePath")
        check(relative.startsWith("native-state/") && !File(relative).isAbsolute)
        val evidence = File(root, relative).canonicalFile
        check(evidence.toPath().startsWith(root.canonicalFile.toPath()) && evidence.isFile)
        check(digest(evidence.readBytes()) == ack.getString("evidenceSha256")) { "Native evidence readback hash mismatch" }
        receipt.getJSONArray("nativeAttestations").put(JSONObject().put("stage", name)
            .put("request", "checkpoints/$name.json").put("requestSha256", digest(request.readBytes()))
            .put("ack", "native-acks/$name.json").put("ackSha256", digest(ackFile.readBytes()))
            .put("evidencePath", relative).put("evidenceSha256", digest(evidence.readBytes())))
        receipt.put("nativeEvidencePending", false)
    }


    @Test
    fun profileUpdateCancelBatchAndRetry() = scenario {
        functionalReceipt("host-consented-isolated-profile-cancel-fixture")
        require(requiredArg("profileCancelPrerequisiteGreen") == "true") {
            "Root must first validate the exact profile-cancel revision2 production source"
        }
        for (key in listOf("profileCancelImplementationManifestSha256", "profileCancelGreenEvidenceSha256")) {
            require(requiredArg(key).matches(Regex("[0-9a-f]{64}"))) { "Host must bind real prerequisite evidence: $key" }
        }
        launch(); requireLabel("Dashboard"); checkNoVpn()
        val profiles = JSONArray()
        for (key in listOf("A", "B")) {
            val label = requiredArg("cancelProfile${key}Label")
            require(label.matches(Regex("[a-zA-Z0-9_-]{1,50}")))
            val url = controlledUrl("cancelProfile${key}Url").toString()
            profiles.put(JSONObject().put("key", key).put("label", label).put("url", url))
        }
        check(profiles.getJSONObject(0).getString("label") != profiles.getJSONObject(1).getString("label"))
        check(profiles.getJSONObject(0).getString("url") != profiles.getJSONObject(1).getString("url"))
        navigate("Profiles")
        for (i in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(i)
            val label = profile.getString("label")
            check(findLabel(label) == null) { "Cancellation fixture profile must be newly imported through UI" }
            openProfileForm()
            cancelSetImportField("Optional", label)
            cancelSetImportField("Required", profile.getString("url"))
            click("Submit")
            awaitCondition("real cancellation fixture import completed", 60_000) {
                findLabel("Import from link") == null && findLabel(label)?.isEditable == false
            }
            requireLabel(label)
        }
        val labelA = profiles.getJSONObject(0).getString("label")
        capture("profile-cancel-imported-baseline")
        profileCancelCheckpoint("profile-cancel-baseline", profiles)

        stage = "single-profile-update-pending"
        cancelLongPressProfile(labelA)
        val singleStarted = SystemClock.elapsedRealtime()
        val singleDeadline = singleStarted + 18_000
        click("Sync")
        profileCancelCheckpoint("profile-single-pending", profiles, singleStarted, singleDeadline)
        val toolbarCancel = requireLabel("Cancel").let(::bounds)
        cancelLongPressProfile(labelA)
        val rowCancel = awaitNode("updating profile context-menu Cancel, distinct from toolbar") {
            it.packageName?.toString() == PACKAGE && matches(it, "Cancel") && bounds(it) != toolbarCancel
        }
        check(SystemClock.elapsedRealtime() < singleDeadline) { "Single Cancel missed the pre-timeout window" }
        performClick(rowCancel); action("profile-context-cancel", rowCancel, labelA)
        cancelAwaitIdle(singleDeadline)
        profileCancelCheckpoint("profile-single-cancelled", profiles, singleStarted, singleDeadline)
        check(SystemClock.elapsedRealtime() < singleDeadline) { "Actual abort/join was not proven before 20s timeout" }
        capture("profile-single-cancelled")

        stage = "single-profile-update-retry"
        cancelLongPressProfile(labelA); click("Sync")
        awaitCondition("single real update retry completed", 60_000) {
            findLabel("Update") != null && findLabel("Cancel") == null
        }
        profileCancelCheckpoint("profile-single-retried", profiles)
        capture("profile-single-retried")

        stage = "batch-profile-update-pending"
        val batchStarted = SystemClock.elapsedRealtime()
        val batchDeadline = batchStarted + 18_000
        click("Update")
        profileCancelCheckpoint("profile-batch-pending", profiles, batchStarted, batchDeadline)
        check(SystemClock.elapsedRealtime() < batchDeadline) { "Batch Cancel missed the pre-timeout window" }
        click("Cancel")
        cancelAwaitIdle(batchDeadline)
        profileCancelCheckpoint("profile-batch-cancelled", profiles, batchStarted, batchDeadline)
        check(SystemClock.elapsedRealtime() < batchDeadline) { "Batch abort/join was not proven before 20s timeout" }
        capture("profile-batch-cancelled-retained-completed-item")

        stage = "batch-profile-update-retry"
        click("Update")
        awaitCondition("both real batch retries completed", 60_000) {
            findLabel("Update") != null && findLabel("Cancel") == null
        }
        profileCancelCheckpoint("profile-batch-retried", profiles)
        capture("profile-batch-retried")
        receipt.put("profileUpdateCancelAccepted", false)
            .put("profileCancelFixtures", profiles)
        stage = "profile-update-cancel-candidate-stopped"
    }

    private fun cancelSetImportField(hint: String, value: String) {
        val field = awaitNode("unique real import $hint field") { node ->
            node.isEditable && !node.isPassword &&
                (node.hintText?.toString() == hint || matches(node, hint))
        }
        check(nodes().count { it.isVisibleToUser && it.isEditable && !it.isPassword &&
            (it.hintText?.toString() == hint || matches(it, hint)) } == 1)
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        awaitNode("committed real import $hint") { it.isEditable && it.text?.toString() == value }
        action("set-text", field, hint)
    }

    private fun cancelLongPressProfile(label: String) {
        var current: AccessibilityNodeInfo? = reveal(label)
        repeat(6) {
            val target = current
            if (target != null && target.isEnabled && target.isLongClickable &&
                target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                action("profile-long-click", target, label)
                return
            }
            current = target?.parent
        }
        error("Production profile exposes no actionable long-click; no coordinate fallback")
    }

    private fun cancelAwaitIdle(deadline: Long) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        check(remaining > 0) { "Cancellation exceeded the pre-timeout budget" }
        awaitCondition("real updating keys released after cleanup", remaining) {
            findLabel("Update") != null && findLabel("Cancel") == null
        }
    }

    private fun profileCancelCheckpoint(name: String, profiles: JSONArray,
        started: Long? = null, deadline: Long? = null) {
        require(name.matches(Regex("[a-z-]{1,60}")))
        stage = name
        val hierarchy = File(root, "$name-hierarchy.json")
        hierarchy.writeText(tree().toString(2) + "\n")
        val payload = JSONObject().put("profiles", profiles)
            .put("implementationManifestSha256", requiredArg("profileCancelImplementationManifestSha256"))
            .put("greenEvidenceSha256", requiredArg("profileCancelGreenEvidenceSha256"))
            .put("uiHierarchy", hierarchy.name).put("uiHierarchySha256", digest(hierarchy.readBytes()))
            .put("toolbarUpdateVisible", findLabel("Update") != null)
            .put("toolbarCancelVisible", findLabel("Cancel") != null)
        if (started != null) payload.put("updateStartedElapsedRealtimeMs", started)
        if (deadline != null) payload.put("cancelDeadlineElapsedRealtimeMs", deadline)
        val value = JSONObject().put("schema", 1).put("qaToken", token).put("stage", name)
            .put("checkpointKind", "profile-update-cancel").put("profileCancel", payload)
            .put("pid", android.os.Process.myPid()).put("nonce", java.util.UUID.randomUUID().toString())
            .put("expectedVpn", false).put("apkSha256", requiredArg("apkSha256"))
            .put("source", requiredArg("source")).put("tree", requiredArg("tree"))
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        val folder = File(root, "checkpoints").also { check(it.isDirectory || it.mkdirs()) }
        val request = File(folder, "$name.json")
        check(!request.exists())
        val temporary = File(folder, "$name.partial")
        temporary.writeText(value.toString(2) + "\n"); check(temporary.renameTo(request))
        val ackFile = File(root, "native-acks/$name.json")
        val wait = if (deadline == null) 45_000L else minOf(6_000L, deadline - SystemClock.elapsedRealtime())
        check(wait > 0) { "No pre-timeout attestation budget remains" }
        receipt.put("nativeEvidencePending", true)
        awaitCondition("real profile/file/SQL/server cancellation attestation $name", wait) { ackFile.isFile }
        val ack = JSONObject(ackFile.readText())
        check(ack.getInt("schema") == 1)
        for (field in listOf("qaToken", "stage", "nonce", "source", "tree", "apkSha256")) {
            check(ack.getString(field) == value.getString(field)) { "Profile native ACK identity mismatch: $field" }
        }
        check(ack.getInt("pid") == android.os.Process.myPid())
        check(ack.getString("requestSha256") == digest(request.readBytes()))
        check(ack.getString("status") == "attested") { "Actual profile cancellation proof failed: ${ack.optString("error")}" }
        val relative = ack.getString("evidencePath")
        check(relative.startsWith("native-state/") && !File(relative).isAbsolute)
        val evidence = File(root, relative).canonicalFile
        check(evidence.toPath().startsWith(root.canonicalFile.toPath()) && evidence.isFile)
        check(digest(evidence.readBytes()) == ack.getString("evidenceSha256"))
        receipt.getJSONArray("nativeAttestations").put(JSONObject().put("stage", name)
            .put("request", "checkpoints/$name.json").put("requestSha256", digest(request.readBytes()))
            .put("ack", "native-acks/$name.json").put("ackSha256", digest(ackFile.readBytes()))
            .put("evidencePath", relative).put("evidenceSha256", digest(evidence.readBytes())))
        receipt.put("nativeEvidencePending", false)
    }


    @Test
    fun listenerRebindFailureRecoveryAndNotificationLifecycle() = scenario {
        functionalReceipt("host-owned-runtime-device-fixture")
        launch(); requireLabel("Dashboard"); checkNoVpn()
        deviceNetworkToggle("VPN", true)
        functionalNetworkSettings(allowChanges = true)
        navigate("Profiles"); click(profileLabel()); navigate("Dashboard")
        startRealVpn()
        checkpoint("listener-baseline", true, JSONObject().put("port", mixedProxyPort()))
        val rebound = requiredArg("rebindMixedPort").toInt()
        val occupied = requiredArg("occupiedMixedPort").toInt()
        require(setOf(mixedProxyPort(), rebound, occupied).size == 3)
        require(listOf(rebound, occupied).all { it in 1024..49151 })
        deviceEditMixedPort(rebound)
        checkpoint("listener-rebound", true, JSONObject().put("port", rebound).put("releasedPort", mixedProxyPort()))
        java.net.ServerSocket().use { blocker ->
            blocker.reuseAddress = false
            blocker.bind(java.net.InetSocketAddress("127.0.0.1", occupied))
            deviceEditMixedPort(occupied)
            awaitCondition("failed bind releases actual VPN and visible running state", 45_000) {
                ownedVpn().isEmpty() && findLabel("Start") != null
            }
            checkpoint("listener-bind-failed", false, JSONObject().put("port", occupied).put("releasedPort", rebound))
        }
        deviceEditMixedPort(rebound)
        navigate("Dashboard"); startRealVpn()
        checkpoint("listener-retry", true, JSONObject().put("port", rebound))
        stopRealVpn()
        checkpoint("listener-stopped", false, JSONObject().put("releasedPort", rebound))
        deviceNetworkToggle("VPN", false)
        deviceGeneralToggle("Stop button in notification", true)
        navigate("Dashboard"); click("Start"); requireLabel("Stop")
        checkpoint("proxy-notification", false, JSONObject().put("port", rebound).put("stopAction", true))
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
        launch(); requireLabel("Dashboard"); requireLabel("Stop")
        deviceGeneralToggle("Stop button in notification", false)
        checkpoint("proxy-stop-hidden", false, JSONObject().put("port", rebound).put("stopAction", false))
        deviceGeneralToggle("Stop button in notification", true)
        checkpoint("proxy-stop-restored", false, JSONObject().put("port", rebound).put("stopAction", true))
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        val notificationStop = awaitNode("real SystemUI notification Stop action") {
            it.packageName?.toString() == "com.android.systemui" && matches(it, "Stop")
        }
        performClick(notificationStop); action("notification-stop", notificationStop, "Stop")
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        launch(); requireLabel("Dashboard"); requireLabel("Start")
        checkpoint("proxy-notification-stopped", false, JSONObject().put("releasedPort", rebound))
        // Later real preference publications must not resurrect a stopped notification.
        deviceGeneralToggle("Stop button in notification", false)
        deviceGeneralToggle("Stop button in notification", true)
        checkpoint("proxy-notification-still-stopped", false, JSONObject().put("releasedPort", rebound))
        deviceNetworkToggle("VPN", true)
        deviceEditMixedPort(mixedProxyPort())
        capture("runtime-device-complete")
        stage = "runtime-device-stopped"
    }

    @Test
    fun independentApplicationTcpUdpNativeAttribution() = scenario {
        functionalReceipt("host-owned-separate-application-sockets")
        launch(); requireLabel("Dashboard"); checkNoVpn()
        functionalNetworkSettings(allowChanges = true)
        deviceGeneralToggle("Find process", true)
        navigate("Profiles"); click(profileLabel()); navigate("Dashboard")
        startRealVpn()
        checkpoint("owner-tcp-udp", true, JSONObject().put("port", mixedProxyPort())
            .put("fixturePackage", "dev.aerove.fixture.socketowner")
            .put("tcpPort", requiredArg("ownerTcpPort").toInt()).put("udpPort", requiredArg("ownerUdpPort").toInt()))
        stopRealVpn()
        checkpoint("owner-stopped", false, JSONObject().put("releasedPort", mixedProxyPort()))
        capture("ordinary-app-owner-complete")
        stage = "ordinary-app-owner-stopped"
    }

    private fun deviceEditMixedPort(port: Int) {
        navigate("Tools"); click("General", scroll = true); click("Port", scroll = true)
        val field = rowControl("Mixed port", editable = true)
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, port.toString())
        }))
        awaitCondition("runtime mixed-port edit accepted") { rowControl("Mixed port", true).text?.toString() == port.toString() }
        action("runtime-edit-mixed-port", field, port.toString()); click("Save")
        awaitCondition("runtime port form saved") { findLabel("Mixed port") == null }
        click("Port", scroll = true)
        check(rowControl("Mixed port", true).text?.toString() == port.toString())
        capture("runtime-port-readback-${frames.length()}")
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        awaitCondition("runtime port readback closed") { findLabel("Mixed port") == null }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        navigate("Dashboard")
    }

    private fun deviceGeneralToggle(label: String, enabled: Boolean) {
        navigate("Tools"); click("General", scroll = true)
        val control = rowControl(label, editable = false)
        if (control.isChecked != enabled) { performClick(control); action("runtime-toggle", control, "$label=$enabled") }
        awaitCondition("saved production $label toggle") { rowControl(label, false).isChecked == enabled }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        navigate("Dashboard")
    }

    private fun deviceNetworkToggle(label: String, enabled: Boolean) {
        checkNoVpn()
        navigate("Tools"); click("Advanced configuration", scroll = true); click("Network", scroll = true)
        val control = rowControl(label, editable = false)
        if (control.isChecked != enabled) { performClick(control); action("runtime-network-toggle", control, "$label=$enabled") }
        awaitCondition("saved production network $label toggle") { rowControl(label, false).isChecked == enabled }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        awaitCondition("network route closed") { findLabel(label) == null }
        check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        navigate("Dashboard")
    }


    private fun scenario(block: () -> Unit) {
        check(context.packageName == PACKAGE)
        check(root.mkdirs()) { "Evidence directory must be new: $root" }
        receipt.put("qaToken", token).put("apkSha256", arguments.getString("apkSha256"))
            .put("source", arguments.getString("source")).put("tree", arguments.getString("tree"))
            .put("workflow", arguments.getString("workflow")).put("runAttempt", arguments.getString("runAttempt"))
            .put("pid", android.os.Process.myPid()).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("locales", context.resources.configuration.locales.toLanguageTags())
        val previous = ui.serviceInfo
        try {
            check(android.os.Build.VERSION.SDK_INT >= 33)
            check(context.resources.configuration.locales[0].language == "en") {
                "Host must fix the real per-app locale to en-US before instrumentation"
            }
            ui.serviceInfo = ui.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            block()
            receipt.put("status", "captured").put("scenarioPassed", true)
        } catch (failure: Throwable) {
            receipt.put("status", "failed").put("scenarioPassed", false)
                .put("error", failure.stackTraceToString())
            runCatching { capture("failure") }.onFailure { receipt.put("captureFailure", it.stackTraceToString()) }
            throw failure
        } finally {
            receipt.put("stage", stage).put("finishedElapsedRealtimeMs", SystemClock.elapsedRealtime())
            File(root, "receipt.json").writeText(receipt.toString(2) + "\n")
            ui.serviceInfo = previous
        }
    }

    private fun launch() {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE))
        val component = requireNotNull(intent.component)
        check(component.className == LAUNCHER) { "Refusing non-Flutter launcher: $component" }
        receipt.put("launcher", component.flattenToString())
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitNode("production Flutter window") { it.packageName?.toString() == PACKAGE && it.isVisibleToUser }
    }

    private fun copyStatement() {
        val statement = requireLabel(STATEMENT)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, STATEMENT.length)
        }
        val selected = statement.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        var copied = selected && statement.performAction(AccessibilityNodeInfo.ACTION_COPY)
        action("select-statement", statement, "selection=$selected directCopy=$copied")
        if (!copied) {
            check(statement.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                "Selectable production statement exposes neither copy actions nor a long-click selection menu"
            }
            awaitNode("real selection menu", { matches(it, "Select all") || matches(it, "Copy") })
            findLabel("Select all", appOnly = false)?.let { performClick(it) }
            click("Copy", appOnly = false)
            copied = true
        }
        check(copied)
        awaitCondition("actual clipboard statement") {
            var text: String? = null
            instrumentation.runOnMainSync {
                text = context.getSystemService(ClipboardManager::class.java).primaryClip
                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }
            text == STATEMENT
        }
        receipt.put("statementCopied", true).put("statementSha256", digest(STATEMENT.toByteArray()))
        capture("selected-statement-copied")
    }

    /** Raw same-window candidates only; registration, signal and optical acceptance remain external. */
    private fun captureDockOpticalCandidates() {
        stage = "optical-candidate-settings"
        val settingsPackage = "com.android.settings"
        val (title, switch) = opticalAnimationControl()
        val wasChecked = switch.isChecked
        receipt.put("opticalRemoveAnimationsInitiallyChecked", wasChecked)
        var collectionFailure: Throwable? = null
        receipt.put("opticalSystemSettingsEvidence", JSONArray())
        try {
            if (!wasChecked) { performClick(title); action("settings-toggle", title, "Remove animations on") }
            awaitCondition("Remove animations checked readback") { opticalAnimationSwitch(title).isChecked }
            val systemProof = capture("optical-remove-animations", JSONObject().put("phase", "enabled-for-optical")
                .put("removeAnimations", true).put("initialActualChecked", wasChecked), settingsPackage)
            // Read only: the actual switch above is the authority; no shell/settings mutation.
            receipt.put("opticalAnimatorDurationScaleDuringCollection", android.provider.Settings.Global.getString(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE))
            launch(); navigate("Tools"); click("Theme", scroll = true); requireLabel("Theme mode")
            choose("Light"); choose("Living Aurora"); choose("Clear shimmer")
            val themeProof = capture("optical-theme-settings", JSONObject().put("material", "Clear shimmer")
                .put("backdrop", "Living Aurora").put("brightness", "Light"))
            check(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            requireLabel("Theme"); navigate("Tools")
            stage = "optical-dock-candidates"
            val density = context.resources.displayMetrics.density
            opticalDrag(-80 * density)
            opticalDrag(android.view.ViewConfiguration.get(context).scaledTouchSlop + 13 * density)
            val expanded = opticalDockBounds()
            val anchor = nodes().filter { it.isVisibleToUser && it.packageName?.toString() == PACKAGE &&
                !it.text.isNullOrBlank() && it.text!!.length >= 6 && bounds(it).centerY() in
                    (context.resources.displayMetrics.heightPixels / 4)..(context.resources.displayMetrics.heightPixels / 2) }
                .maxByOrNull { it.text!!.length } ?: error("Tools has no visible stable text anchor")
            val anchorText = anchor.text.toString()
            check(nodes().count { it.isVisibleToUser && it.packageName?.toString() == PACKAGE && matches(it, anchorText) } == 1)
            val targetY = bounds(anchor).top
            fun configuration(role: String) = JSONObject().put("opticalCandidate", true).put("role", role)
                .put("matrix", false).put("opticalAccepted", false).put("geometryAccepted", false)
                .put("strictSameMaterialNoLens", false).put("systemSettingsEvidence", systemProof)
                .put("settingsEvidence", themeProof).put("anchor", anchorText).put("targetAnchorTopPx", targetY)
                .put("observedAnchorBoundsPx", rect(bounds(requireLabel(anchorText))))
                .put("observedDockBoundsPx", rect(opticalDockBounds()))
            capture("optical-P1-expanded-clear", configuration("P1-expanded-clear"))
            opticalDrag(-40 * density)
            val compact = opticalDockBounds()
            check(compact.width() < expanded.width() && compact.height() < expanded.height()) {
                "Actual Tools scroll did not expose dock side strips; no source image can be claimed"
            }
            val sourceRestored = opticalRestoreAnchor(anchorText, targetY, compact)
            check(opticalDockBounds() == compact) { "Short reverse gestures expanded the dock; source ROI is covered" }
            capture("optical-S1-exposed-text", configuration("S1-exposed-page-content").put("offsetRestored", sourceRestored))
            capture("optical-S2-exposed-text", configuration("S2-repeat-exposed-page-content").put("offsetRestored", sourceRestored))
            opticalDrag(40 * density)
            check(opticalDockBounds() == expanded) { "Actual reverse scroll did not restore expanded dock geometry" }
            val paneRestored = opticalRestoreAnchor(anchorText, targetY, expanded)
            check(opticalDockBounds() == expanded) { "Short restoring gestures collapsed P2; no expanded pane can be claimed" }
            capture("optical-P2-expanded-clear", configuration("P2-expanded-clear").put("offsetRestored", paneRestored))
            receipt.put("opticalCandidateCollection", JSONObject().put("frames", 4)
                .put("sourceOffsetRestored", sourceRestored).put("paneOffsetRestored", paneRestored)
                .put("expandedDockBoundsPx", rect(expanded)).put("compactDockBoundsPx", rect(compact))
                .put("registrationPending", true).put("signalPending", true).put("opticalAccepted", false)
                .put("removeAnimationsCheckedDuringCollection", true))
        } catch (failure: Throwable) {
            collectionFailure = failure
            receipt.put("opticalCollectionError", failure.stackTraceToString())
            // Preserve the actual failing window before cleanup returns to a different activity.
            runCatching { capture("optical-collection-failure", JSONObject().put("phase", "collection-failed"),
                requireNotNull(ui.rootInActiveWindow?.packageName?.toString())) }
                .onFailure { receipt.put("opticalCollectionFailureCaptureError", it.stackTraceToString()) }
            throw failure
        } finally {
            // Cancellation remains a failure, but its existing interrupt must not skip restoration.
            val interrupted = Thread.interrupted()
            var cleanupFailure: Throwable? = null
            var restoredChecked: Boolean? = null
            try {
                stage = "optical-restore-system-animations"
                val (restoreTitle, restoreSwitch) = opticalAnimationControl()
                if (restoreSwitch.isChecked != wasChecked) {
                    performClick(restoreTitle)
                    action("settings-restore", restoreTitle, "Remove animations=$wasChecked (actual initial state)")
                }
                awaitCondition("Remove animations original state readback") {
                    opticalAnimationSwitch(restoreTitle).isChecked == wasChecked
                }
                restoredChecked = opticalAnimationSwitch(restoreTitle).isChecked
                check(restoredChecked == wasChecked) { "Remove animations changed after original-state readback" }
                val proof = capture("optical-remove-animations-restored", JSONObject()
                    .put("phase", "restored").put("removeAnimations", restoredChecked)
                    .put("initialActualChecked", wasChecked), settingsPackage)
                receipt.put("opticalAnimationRestoration", JSONObject().put("initialActualChecked", wasChecked)
                    .put("actualChecked", restoredChecked).put("restored", true).put("settingsEvidence", proof)
                    .put("animatorDurationScaleReadback", android.provider.Settings.Global.getString(
                        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)))
            } catch (failure: Throwable) {
                cleanupFailure = failure
                receipt.put("opticalAnimationRestoreError", failure.stackTraceToString())
                runCatching { capture("optical-animation-restore-failure", JSONObject().put("phase", "restore-failed"),
                    requireNotNull(ui.rootInActiveWindow?.packageName?.toString())) }
                    .onFailure { receipt.put("opticalAnimationRestoreCaptureError", it.stackTraceToString()) }
            } finally {
                try {
                    stage = "optical-return-to-app-after-animation-restore"
                    launch(); requireLabel("Dashboard")
                    val policy = when (restoredChecked) {
                        true -> "remove-animations-enabled-as-original"
                        false -> "remove-animations-disabled-as-original"
                        null -> "unknown-restoration-failed"
                    }
                    capture("optical-app-after-animation-restore", JSONObject().put("animationPolicy", policy)
                        .put("removeAnimationsActualChecked", restoredChecked ?: JSONObject.NULL))
                    receipt.put("opticalReturnedToApp", true).put("opticalFinalAnimationPolicy", policy)
                } catch (failure: Throwable) {
                    receipt.put("opticalAppReturnError", failure.stackTraceToString())
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
            cleanupFailure?.let { failure ->
                if (collectionFailure != null) collectionFailure!!.addSuppressed(failure) else throw failure
            }
        }
    }

    private fun opticalAnimationControl(): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        awaitNode("Android accessibility Settings") { it.packageName?.toString() == "com.android.settings" }
        // Reopen the same real route for both collection and restoration, without writing Settings directly.
        val colorMotion = opticalSettingsLabel("Color and motion")
        val before = tree().toString()
        performClick(colorMotion); action("settings-open", colorMotion, "Color and motion")
        awaitCondition("Color and motion opened") { tree().toString() != before }
        val title = opticalSettingsLabel("Remove animations")
        return title to opticalAnimationSwitch(title)
    }

    private fun opticalAnimationSwitch(title: AccessibilityNodeInfo): AccessibilityNodeInfo =
        nodes().filter { it.isVisibleToUser && it.isCheckable &&
            it.packageName?.toString() == "com.android.settings" && bounds(it).contains(bounds(title).centerX(), bounds(title).centerY()) }
            .singleOrNull() ?: nodes().filter { it.isVisibleToUser && it.isCheckable &&
                it.packageName?.toString() == "com.android.settings" &&
                kotlin.math.abs(bounds(it).centerY() - bounds(title).centerY()) < bounds(title).height() }
                .singleOrNull() ?: error("Remove animations has no unambiguous real switch")

    private fun opticalSettingsLabel(label: String): AccessibilityNodeInfo {
        repeat(12) {
            nodes().firstOrNull { it.isVisibleToUser && it.packageName?.toString() == "com.android.settings" && matches(it, label) }
                ?.let { return it }
            val scroll = nodes().filter { it.isVisibleToUser && it.isScrollable && it.packageName?.toString() == "com.android.settings" }
                .maxByOrNull { bounds(it).width() * bounds(it).height() } ?: error("Settings route does not expose $label")
            val before = tree().toString()
            check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "Settings route cannot reveal $label" }
            action("settings-scroll", scroll, "reveal=$label")
            awaitCondition("Settings scroll settles", 1800, required = false) {
                tree().toString() != before
            }
        }
        error("Settings route does not expose $label within bounded scroll")
    }

    private fun opticalDockBounds(): Rect {
        val result = Rect()
        for (label in listOf("Dashboard", "Profiles", "Tools")) result.union(bounds(requireLabel(label, dock = true)))
        return result
    }

    private fun opticalRestoreAnchor(label: String, targetY: Int, dock: Rect): Boolean {
        val density = context.resources.displayMetrics.density
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        repeat(32) {
            val before = bounds(requireLabel(label)).top
            val remaining = targetY - before
            if (remaining == 0) return true
            // Each new touch ends at rest. Observe actual content travel, never infer it from finger travel.
            val step = minOf(kotlin.math.abs(remaining).toFloat(), 6 * density)
            opticalDrag((slop + step) * if (remaining > 0) 1 else -1)
            val moved = bounds(requireLabel(label)).top - before
            actions.put(JSONObject().put("stage", stage).put("kind", "optical-offset-readback")
                .put("anchor", label).put("beforeTopPx", before).put("afterTopPx", before + moved)
                .put("targetTopPx", targetY).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()))
            if (moved == 0 || kotlin.math.abs(moved) >= 12 * density || opticalDockBounds() != dock) return false
        }
        return bounds(requireLabel(label)).top == targetY
    }

    private fun opticalDrag(deltaY: Float) {
        val scroll = nodes().filter { it.isVisibleToUser && it.isScrollable && it.packageName?.toString() == PACKAGE }
            .maxByOrNull { bounds(it).width() * bounds(it).height() } ?: error("No real Tools scroll surface")
        val box = bounds(scroll)
        val x = box.exactCenterX()
        val y = box.exactCenterY()
        check(y + deltaY > box.top && y + deltaY < box.bottom) { "Gesture leaves observed scroll surface" }
        action("optical-touch-drag", scroll, "x=$x startY=$y deltaY=$deltaY durationMs=800")
        val down = SystemClock.uptimeMillis()
        fun inject(kind: Int, at: Float) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), kind, x, at, 0)
            try {
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                check(ui.injectInputEvent(event, true)) { "Real touch injection failed" }
            } finally { event.recycle() }
        }
        inject(android.view.MotionEvent.ACTION_DOWN, y)
        for (step in 1..12) {
            SystemClock.sleep(50)
            inject(android.view.MotionEvent.ACTION_MOVE, y + deltaY * step / 12)
        }
        SystemClock.sleep(200) // Stationary release prevents an artificial fling after the measured drag.
        inject(android.view.MotionEvent.ACTION_UP, y + deltaY)
        var previous = ""
        var stable = 0
        awaitCondition("post-gesture real geometry settled") {
            val current = tree().toString()
            stable = if (current == previous) stable + 1 else 0
            previous = current
            stable >= 3
        }
    }

    private fun choose(label: String) {
        click(label, scroll = true)
        awaitNode("selected theme choice $label") { node ->
            matches(node, label) && selected(node)
        }
        action("verified-selected-choice", requireLabel(label), label)
    }

    private fun navigate(label: String) {
        click(label, dock = true)
        awaitNode("selected bottom destination $label") { matches(it, label) && it.isSelected }
        action("verified-selected-destination", requireLabel(label, dock = true), label)
    }

    private fun selected(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isSelected == true || current?.isChecked == true) return true
            current = current?.parent
        }
        return false
    }

    private fun click(label: String, scroll: Boolean = false, dock: Boolean = false, appOnly: Boolean = true) {
        val node = if (scroll) reveal(label) else requireLabel(label, dock, appOnly)
        performClick(node)
        action("click", node, label)
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val target = current
            if (target != null && target.isEnabled && target.isClickable &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = target?.parent
        }
        error("No enabled ACTION_CLICK for ${node.text ?: node.contentDescription}; no coordinate fallback")
    }

    private fun reveal(label: String): AccessibilityNodeInfo {
        findLabel(label)?.let { return it }
        // Search to the top, then forward; both directions and the total time are bounded.
        val end = SystemClock.elapsedRealtime() + 25_000
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(28) {
                check(SystemClock.elapsedRealtime() < end) { "Timed out revealing $label" }
                checkInterrupted()
                findLabel(label)?.let { return it }
                val scroller = nodes().filter { n -> n.isVisibleToUser && n.isScrollable && n.packageName?.toString() == PACKAGE }
                    .maxByOrNull { bounds(it).height() * bounds(it).width() }
                    ?: error("No production scroll node while revealing $label")
                val before = tree().toString()
                if (!scroller.performAction(direction)) return@repeat
                action("scroll", scroller, "direction=$direction target=$label")
                awaitCondition("scroll content change", 1800, required = false) { tree().toString() != before }
            }
        }
        return requireLabel(label)
    }

    private fun requireLabel(label: String, dock: Boolean = false, appOnly: Boolean = true): AccessibilityNodeInfo =
        awaitNode("visible label $label") { matches(it, label) && (!appOnly || it.packageName?.toString() == PACKAGE) &&
            (!dock || bounds(it).centerY() > context.resources.displayMetrics.heightPixels / 2) }

    private fun findLabel(label: String, dock: Boolean = false, appOnly: Boolean = true) = nodes().firstOrNull {
        it.isVisibleToUser && matches(it, label) && (!appOnly || it.packageName?.toString() == PACKAGE) &&
            (!dock || bounds(it).centerY() > context.resources.displayMetrics.heightPixels / 2)
    }

    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString() == label || node.contentDescription?.toString() == label

    private fun awaitNode(reason: String, condition: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        awaitCondition(reason) { result = nodes().firstOrNull { it.isVisibleToUser && condition(it) }; result != null }
        return requireNotNull(result)
    }

    private fun awaitCondition(reason: String, timeoutMs: Long = 12_000, required: Boolean = true, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            checkInterrupted()
            if (condition()) return true
            SystemClock.sleep(100) // Poll a real postcondition; elapsed time alone never passes a stage.
        } while (SystemClock.elapsedRealtime() < deadline)
        check(!required) { "Stage $stage: timed out waiting for $reason" }
        return false
    }

    private fun checkInterrupted() { check(!Thread.currentThread().isInterrupted) { "Cancelled at $stage" } }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            check(depth < 80 && result.size < 4000) { "Accessibility hierarchy exceeds bounded traversal" }
            result.add(node)
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(ui.rootInActiveWindow, 0)
        return result
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun rect(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun tree() = JSONArray().also { rows -> nodes().forEach { rows.put(nodeJson(it)) } }
    private fun nodeJson(node: AccessibilityNodeInfo) = JSONObject()
        .put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
        .put("class", node.className?.toString()).put("package", node.packageName?.toString())
        .put("boundsPx", rect(bounds(node))).put("visible", node.isVisibleToUser)
        .put("selected", node.isSelected).put("checked", node.isChecked).put("enabled", node.isEnabled)
        .put("editable", node.isEditable).put("actions", JSONArray(node.actionList.map { it.id }))

    private fun action(kind: String, node: AccessibilityNodeInfo, detail: String) {
        actions.put(JSONObject().put("stage", stage).put("kind", kind).put("detail", detail)
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("node", nodeJson(node)))
    }

    private fun capture(label: String, configuration: JSONObject? = null, expectedPackage: String = PACKAGE): String {
        checkInterrupted()
        if (label != "failure") {
            var previous = ""
            var stable = 0
            awaitCondition("stable real accessibility geometry") {
                val current = tree().toString()
                stable = if (current == previous) stable + 1 else 0
                previous = current
                stable >= 3
            }
        }
        val hierarchy = tree()
        if (label != "failure") {
            check(ui.rootInActiveWindow?.packageName?.toString() == expectedPackage) {
                "Production app lost the active window at $stage"
            }
        }
        File(root, "$label-hierarchy.json").writeText(hierarchy.toString(2) + "\n")
        val bitmap = requireNotNull(ui.takeScreenshot()) { "SurfaceFlinger screenshot failed" }
        val image = File(root, "$label.png")
        try {
            check(bitmap.width > 0 && bitmap.height > 0)
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val metrics = context.resources.displayMetrics
        val windowFile = File(root, "$label-window-insets.txt")
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys -t 3 window windows")).use {
            val bytes = it.readNBytes(1024 * 1024 + 1)
            check(bytes.size <= 1024 * 1024) { "Window/inset diagnostics exceeded evidence budget" }
            windowFile.writeBytes(bytes)
        }
        val row = JSONObject().put("label", label).put("file", image.name)
            .put("sha256", digest(image.readBytes())).put("bytes", image.length())
            .put("hierarchy", "$label-hierarchy.json").put("hierarchySha256", digest(File(root, "$label-hierarchy.json").readBytes()))
            .put("windowInsetsDump", windowFile.name).put("windowInsetsDumpSha256", digest(windowFile.readBytes()))
            .put("density", metrics.density).put("densityDpi", metrics.densityDpi)
            .put("fontScale", context.resources.configuration.fontScale)
            .put("displayMetricsPx", JSONArray(listOf(metrics.widthPixels, metrics.heightPixels)))
            .put("windows", JSONArray(ui.windows.map { window -> JSONObject().put("type", window.type)
                .put("focused", window.isFocused).put("active", window.isActive)
                .put("boundsPx", rect(Rect().also { window.getBoundsInScreen(it) })) }))
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime()).put("configuration", configuration)
        if (expectedPackage == PACKAGE) frames.put(row) else receipt.getJSONArray("opticalSystemSettingsEvidence").put(row)
        return image.name
    }

    private fun slug(text: String) = text.lowercase().replace(' ', '-')
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PACKAGE = "dev.aerove.lite"
        private const val LAUNCHER = "dev.aerove.lite.FlutterMainActivity"
        private const val STATEMENT = "The Software itself does not provide any proxy server, node, subscription, or network access service, and has no partnership, agency, or guarantee relationship with any provider of such services."
    }
}

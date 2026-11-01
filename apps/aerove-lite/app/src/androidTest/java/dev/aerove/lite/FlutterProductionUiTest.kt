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

    private fun capture(label: String, configuration: JSONObject? = null): String {
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
            check(ui.rootInActiveWindow?.packageName?.toString() == PACKAGE) {
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
        frames.put(row)
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

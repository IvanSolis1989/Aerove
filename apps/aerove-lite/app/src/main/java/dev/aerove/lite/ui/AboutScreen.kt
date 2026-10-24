package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aerove.lite.BuildConfig
import dev.aerove.lite.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private data class LicenseEntry(val file: String, val label: Int, val notice: String? = null, val clientHelper: Boolean = false)
private val Licenses = listOf(
    LicenseEntry("AEROVE-GPL-3.0.txt", R.string.lite_license_aerove),
    LicenseEntry("clash-rs-Apache-2.0.txt", R.string.lite_license_core),
    LicenseEntry("clash-rs-NOTICE.md", R.string.lite_license_core_notice),
    LicenseEntry("Backdrop-Apache-2.0.txt", R.string.lite_license_backdrop),
    LicenseEntry("Backdrop-Apache-2.0.txt", R.string.group_icon_license_androidsvg,
        "AndroidSVG 1.4\nCopyright 2013, 2014, 2018 Paul LeBeau, Cave Rock Software Ltd.\nhttps://github.com/BigBadaboom/androidsvg"),
    LicenseEntry("Backdrop-Apache-2.0.txt", R.string.lite_license_mcu,
        "Material Color Utilities — Material Components Android 1.13.0\nCopyright (C) 2021–2023 The Android Open Source Project\nhttps://github.com/material-components/material-components-android/tree/84c0e6d6d493fcf176d2099683245b78afbeb78d/lib/java/com/google/android/material/color/utilities"),
    LicenseEntry("client-helper/dependencies.json", R.string.about_license_client_helper, clientHelper = true),
)

@Composable
internal fun AboutScreen(padding: PaddingValues, onDeveloperUnlocked: () -> Unit) {
    val features = LocalLiteFeatures.current
    val uriHandler = LocalUriHandler.current
    val telegramConfirmation = rememberAboutTelegramConfirmation()
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val entry = Licenses.firstOrNull { it.label == selected }
    BackHandler(entry != null) { selected = null }
    AboutTelegramContent(telegramConfirmation, uriHandler::openUri) {
    if (entry != null) {
        LicenseBody(entry, padding) { selected = null }
    } else LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MaintenanceDeveloperUnlockTarget(onDeveloperUnlocked, Modifier.align(Alignment.CenterHorizontally)) {
                        LitePane(Modifier.size(96.dp), corner = LiteCorner.Sheet) {
                            Image(painterResource(R.drawable.aerove_lite_icon), stringResource(R.string.app_name),
                                Modifier.align(Alignment.Center).size(68.dp))
                        }
                    }
                    LiteText(stringResource(R.string.app_name), style = LiteType.Title)
                    LiteText(stringResource(R.string.lite_version, BuildConfig.VERSION_NAME), style = LiteType.Label)
                    LiteText(stringResource(R.string.lite_about_detail), color = LocalLiteVisuals.current.secondaryInk)
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.update_check), onClick = { features().updates.check() }) { LiteIcon(FullGlyphs.chevronForward) }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(R.string.access_source), subtitle = "docs/development/upstreams.md",
                    onClick = { uriHandler.openUri("https://github.com/IvanSolis1989/Aerove/blob/main/docs/development/upstreams.md#开源来源与鸣谢") }) { LiteIcon(FullGlyphs.chevronForward) }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column {
                AboutTelegramLink(stringResource(R.string.telegram_channel), label = "t.me/IvanSolis89",
                    onClick = { telegramConfirmation.request("https://t.me/IvanSolis89") })
                AboutTelegramLink(stringResource(R.string.telegram_group), label = "t.me/Olympus_Habitue",
                    onClick = { telegramConfirmation.request("https://t.me/Olympus_Habitue") })
                }
            }
        }
        item { SectionLabel(stringResource(R.string.lite_open_source)) }
        items(Licenses, key = { it.label }) { license ->
            LitePane(Modifier.fillMaxWidth()) {
                SettingsRow(stringResource(license.label), onClick = { selected = license.label }) { LiteIcon(FullGlyphs.chevronForward) }
            }
        }
    }
    }
}

@Composable
private fun LicenseBody(entry: LicenseEntry, padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val document by produceState<Result<List<String>>?>(null, entry.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (entry.clientHelper) readClientHelperLicenses { path -> context.assets.open(path).bufferedReader().use { it.readText() } }
                else context.assets.open("licenses/${entry.file}").bufferedReader().use { it.readLines() }
            }
        }
    }
    SelectionContainer {
        LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { LiteButton(stringResource(R.string.lite_back), onBack, Modifier.fillMaxWidth()) }
            item { LiteText(stringResource(entry.label), Modifier.padding(vertical = 12.dp), style = LiteType.Headline) }
            entry.notice?.let { notice -> item { LiteText(notice, style = LiteType.Caption) } }
            val result = document
            if (result == null) item { LiteText(stringResource(R.string.lite_busy)) }
            else if (result.isFailure) item { LiteText("licenses/${entry.file}\n${result.exceptionOrNull()?.message.orEmpty()}") }
            else itemsIndexed(result.getOrThrow()) { _, line ->
                LiteText(if (line.isEmpty()) " " else line, style = LiteType.Caption.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

internal fun readClientHelperLicenses(readAsset: (String) -> String): List<String> {
    val base = "licenses/client-helper/"
    fun read(path: String): String = readAsset(base + path).also { require(it.length <= 256 * 1024) { "License asset is too large" } }
    val dependencies = JSONArray(read("dependencies.json"))
    require(dependencies.length() <= 128) { "Too many helper dependencies" }
    val lines = mutableListOf<String>()
    fun append(path: String) {
        require(path.split('/').all { it.matches(Regex("[a-zA-Z0-9_.-]+")) && it != "." && it != ".." }) { "Invalid license asset path" }
        lines += path
        lines += read(path).lines()
        lines += ""
    }
    listOf("LICENSE.AEROVE", "LICENSE.Go", "LICENSE.regexp2", "ATTRIB.regexp2").forEach(::append)
    repeat(dependencies.length()) { index ->
        val dependency = dependencies.getJSONObject(index)
        lines += "${dependency.getString("Path")} ${dependency.getString("Version")}"
        val licenses = dependency.getJSONArray("licenses")
        require(licenses.length() in 1..16) { "Missing helper dependency license" }
        repeat(licenses.length()) { license ->
            val source = licenses.getString(license)
            require(source.startsWith("licenses/")) { "Invalid helper license source" }
            append(source.removePrefix("licenses/"))
        }
    }
    return lines
}

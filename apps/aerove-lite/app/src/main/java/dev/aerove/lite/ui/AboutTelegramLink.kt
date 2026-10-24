package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R

internal class AboutTelegramConfirmation {
    var pendingUrl: String? by mutableStateOf(null)
        private set

    var failedUrl: String? by mutableStateOf(null)
        private set

    fun request(url: String) {
        require(url == "https://t.me/IvanSolis89" || url == "https://t.me/Olympus_Habitue")
        failedUrl = null
        pendingUrl = url
    }

    fun dismiss() { pendingUrl = null; failedUrl = null }

    fun confirm(openUrl: (String) -> Unit) {
        val url = pendingUrl ?: return
        dismiss()
        try {
            openUrl(url)
        } catch (_: IllegalArgumentException) {
            failedUrl = url
        } catch (_: SecurityException) {
            failedUrl = url
        }
    }

    companion object {
        val StateSaver = Saver<AboutTelegramConfirmation, String>(
            save = { it.pendingUrl ?: it.failedUrl?.let { url -> "failure:$url" }.orEmpty() },
            restore = { saved -> AboutTelegramConfirmation().also { state ->
                if (saved.startsWith("failure:")) {
                    state.request(saved.removePrefix("failure:"))
                    state.failedUrl = state.pendingUrl
                    state.pendingUrl = null
                } else if (saved.isNotEmpty()) state.request(saved)
            } },
        )
    }
}

@Composable
internal fun rememberAboutTelegramConfirmation(): AboutTelegramConfirmation = rememberSaveable(
    saver = AboutTelegramConfirmation.StateSaver,
) { AboutTelegramConfirmation() }

@Composable
internal fun AboutTelegramContent(confirmation: AboutTelegramConfirmation, openUrl: (String) -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        confirmation.pendingUrl?.let { url ->
            LiteModal(stringResource(R.string.about_external_link), confirmation::dismiss) {
                LiteText(url)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteButton(stringResource(R.string.lite_cancel), confirmation::dismiss, Modifier.weight(1f))
                    LiteButton(stringResource(R.string.about_link_go), { confirmation.confirm(openUrl) }, Modifier.weight(1f))
                }
            }
        }
        confirmation.failedUrl?.let { url ->
            LiteModal(stringResource(R.string.about_external_link), confirmation::dismiss) {
                LiteText(stringResource(R.string.about_link_open_failed))
                LiteText(url)
                LiteButton(stringResource(R.string.lite_cancel), confirmation::dismiss, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun AboutTelegramLink(title: String, label: String, onClick: () -> Unit) {
    val visuals = LocalLiteVisuals.current
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LitePane(Modifier.size(40.dp), corner = LiteCorner.Control) {
            LiteIcon(AboutSendGlyph, Modifier.align(Alignment.Center), color = visuals.secondaryInk, size = 20.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiteText(title)
            LiteText(label, style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
        }
        LiteIcon(AboutOpenExternalGlyph)
    }
}

// Same geometry and roles as Standard's AppGlyphs.send/openExternal.
private val AboutSendGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(3.2, 10.8, 1), vertex(20.6, 3.4, 1), vertex(13.2, 20.8, 1), vertex(10.6, 13.4, 0.6)), closed = true),
    glyphPolyline(listOf(vertex(10.6, 13.4), vertex(15.2, 8.8)), role = LiteGlyphRole.Detail),
))
private val AboutOpenExternalGlyph = LiteGlyph(listOf(
    glyphBox(3, 3, 21, 21, 3),
    glyphPolyline(listOf(vertex(7.6, 16.4), vertex(16.4, 7.6)), role = LiteGlyphRole.Detail),
    glyphPolyline(listOf(vertex(11, 7.6), vertex(16.4, 7.6, 0.4), vertex(16.4, 13)), role = LiteGlyphRole.Detail),
))

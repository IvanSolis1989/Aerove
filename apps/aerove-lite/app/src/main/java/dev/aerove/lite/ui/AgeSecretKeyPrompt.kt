package dev.aerove.lite.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.profilesource.AgeSecret
import dev.aerove.lite.profilesource.MAX_IDENTITY_BYTES

/** submit transfers ownership of the secret; caller closes it after private persistence/retry. */
@Composable
internal fun AgeSecretKeyPrompt(busy: Boolean, errorCode: String?, dismiss: () -> Unit, submit: (AgeSecret) -> Unit) {
    var secret by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { secret = "" } }
    ProfileMaintenanceSheet(stringResource(R.string.age_secret_title), { secret = ""; dismiss() }) {
        AgeIdentityField(secret, { secret = it }, !busy)
        if (errorCode != null) LiteText(stringResource(R.string.age_decrypt_failed), style = LiteType.Caption)
        LiteButton(stringResource(R.string.age_continue), {
            val bytes = secret.trim().toByteArray(Charsets.UTF_8)
            try { submit(AgeSecret(bytes)) } finally { bytes.fill(0); secret = "" }
        }, enabled = !busy && secret.isNotBlank() && secret.toByteArray().size <= MAX_IDENTITY_BYTES)
        LiteButton(stringResource(R.string.age_cancel), { secret = ""; dismiss() })
    }
}

@Composable
internal fun AgeIdentityField(value: String, change: (String) -> Unit, enabled: Boolean = true) {
    LiteText(stringResource(R.string.age_secret_label), style = LiteType.Label)
    val visuals = LocalLiteVisuals.current
    LitePane(Modifier.fillMaxWidth(), corner = LiteCorner.Control) {
        BasicTextField(value, { if (it.toByteArray().size <= MAX_IDENTITY_BYTES) change(it) },
            Modifier.fillMaxWidth().padding(14.dp).testTag("age-identity"), enabled = enabled, singleLine = false,
            textStyle = LiteType.Body.copy(color = visuals.ink, fontFamily = LocalLiteFontFamily.current),
            cursorBrush = SolidColor(visuals.primary), visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password))
    }
}

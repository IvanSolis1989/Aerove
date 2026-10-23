package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun encodeProfileShareQr(url: String): BitMatrix? {
    if (url.isBlank()) return null
    val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4)
    val writer = QRCodeWriter()
    return try { writer.encode(url, BarcodeFormat.QR_CODE, 0, 0, hints + (EncodeHintType.QR_COMPACT to true)) }
    catch (_: WriterException) {
        // ZXing's compact encoder cannot handle some supplementary Unicode characters.
        try { writer.encode(url, BarcodeFormat.QR_CODE, 0, 0, hints) } catch (_: WriterException) { null }
    }
}

/** The caller resolves the current profile by ID while the overlay is composed. */
@Composable
internal fun ProfileShareQr(profile: ProfileSummary?, onDismiss: () -> Unit) {
    val url = profile?.url
    if (profile == null || url.isNullOrBlank()) {
        LaunchedEffect(profile?.id, url) { onDismiss() }
        return
    }
    var code by remember(url) { mutableStateOf<BitMatrix?>(null) }
    var finished by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        val result = withContext(Dispatchers.Default) { encodeProfileShareQr(url) }
        ensureActive()
        code = result; finished = true
    }
    LiteModal(profile.name, onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val matrix = code
            if (matrix != null) {
                Box(Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f)
                    .testTag("profile-share-qr-image").semantics { role = Role.Image; contentDescription = url }
                    .clip(rememberLiteShape(LiteCorner.Control)).background(Color.White).drawWithCache {
                        val module = size.minDimension / matrix.width
                        val path = Path()
                        for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) {
                            path.addRect(Rect(x * module, y * module, (x + 1) * module, (y + 1) * module))
                        }
                        onDrawBehind { drawPath(path, Color.Black) }
                    })
                LiteText(stringResource(R.string.profile_qr_share_tip), style = LiteType.Body.copy(textAlign = TextAlign.Center))
            } else if (finished) {
                LiteText(stringResource(R.string.profile_qr_too_long), Modifier.testTag("profile-share-qr-too-long"), style = LiteType.Body.copy(textAlign = TextAlign.Center))
            } else {
                val loading = stringResource(R.string.profile_qr_loading)
                Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.semantics { contentDescription = loading }, color = LocalLiteVisuals.current.primary)
                }
            }
            LiteButton(stringResource(R.string.lite_close), onDismiss, Modifier.align(Alignment.End).testTag("profile-share-qr-close"))
        }
    }
}

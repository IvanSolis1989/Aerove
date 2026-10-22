package dev.aerove.lite.scan

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AlbumQrDecoderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun readsQrFromRealPngAndClosesItsResources() {
        val link = "ss://offline-test@localhost:443#Album"
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 512, 512)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(512 * 512) { if (matrix[it % 512, it / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 512, 0, 0, 512, 512)
        val file = File.createTempFile("scan-", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertEquals(listOf(link), AlbumQrDecoder(context.contentResolver).decode(Uri.fromFile(file)))
        } finally {
            bitmap.recycle()
            assertTrue(file.delete())
        }
    }

    @Test fun rejectsInvalidAndOversizedFiles() {
        val file = File.createTempFile("scan-", ".bin", context.cacheDir)
        try {
            file.writeText("invalid image")
            assertThrows(IOException::class.java) { AlbumQrDecoder(context.contentResolver).decode(Uri.fromFile(file)) }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(AlbumQrDecoder.MAX_BYTES + 1L) }
            assertThrows(IOException::class.java) { AlbumQrDecoder(context.contentResolver).decode(Uri.fromFile(file)) }
        } finally {
            assertTrue(file.delete())
        }
    }
}

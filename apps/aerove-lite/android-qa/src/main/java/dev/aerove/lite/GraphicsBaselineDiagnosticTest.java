package dev.aerove.lite;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Self-targeted observer: no App install, launch, input, preferences, or renderer changes. */
@SuppressWarnings("deprecation")
public class GraphicsBaselineDiagnosticTest extends InstrumentationTestCase {
    public void testCaptureCurrentCompositor() throws Exception {
        Instrumentation owner = getInstrumentation();
        Bundle args = ((InstrumentationTestRunner) owner).getArguments();
        String scene = args.getString("scene");
        String session = args.getString("session");
        assertTrue(scene != null && scene.matches("home|settings|app"));
        assertTrue(session != null && session.matches("[a-f0-9]{32}"));
        assertEquals("dev.aerove.lite.test", owner.getTargetContext().getPackageName());
        File directory = new File(owner.getTargetContext().getExternalFilesDir(null), "graphics-" + session + "/" + scene);
        assertTrue(!directory.exists() && directory.mkdirs());
        JSONObject receipt = new JSONObject().put("schema", 1).put("scene", scene)
            .put("session", session).put("diagnosticOnly", true).put("accepted", false)
            .put("visualApproved", false).put("atomicSameFrame", false)
            .put("captureStartedElapsedMs", SystemClock.elapsedRealtime());
        Bitmap bitmap = null;
        Throwable failure = null;
        try {
            bitmap = owner.getUiAutomation().takeScreenshot();
            assertNotNull("UiAutomation returned no compositor image", bitmap);
            File image = new File(directory, "uiautomation.png");
            try (FileOutputStream stream = new FileOutputStream(image)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            long black = 0, transparent = 0;
            int[] row = new int[bitmap.getWidth()];
            for (int y = 0; y < bitmap.getHeight(); y++) {
                bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
                for (int pixel : row) {
                    if ((pixel & 0xffffff) == 0) black++;
                    if ((pixel >>> 24) == 0) transparent++;
                }
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(image.toPath());
            StringBuilder digest = new StringBuilder();
            for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) digest.append(String.format("%02x", part & 255));
            receipt.put("captureCompleted", true).put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                .put("pixels", (long) bitmap.getWidth() * bitmap.getHeight()).put("rgbBlack", black)
                .put("transparent", transparent).put("sha256", digest.toString()).put("observationOnly", true);
        } catch (Throwable error) {
            failure = error;
            receipt.put("captureCompleted", false).put("errorType", error.getClass().getName()).put("error", String.valueOf(error.getMessage()));
        } finally {
            if (bitmap != null) bitmap.recycle();
            receipt.put("captureFinishedElapsedMs", SystemClock.elapsedRealtime());
            try (FileOutputStream stream = new FileOutputStream(new File(directory, "receipt.json"))) {
                stream.write(receipt.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        }
        if (failure != null) throw new AssertionError("Compositor capture failed; receipt preserved", failure);
    }
}

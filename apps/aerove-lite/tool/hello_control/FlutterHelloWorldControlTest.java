package dev.aerove.diagnostic;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.*;
import android.os.Process;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Fixed official control. Public Android readback only; never production acceptance. */
@SuppressWarnings("deprecation")
public class FlutterHelloWorldControlTest extends InstrumentationTestCase {
    private static final String APP = "dev.aerove.diagnostic.flutter_onscreen_control";
    private static final String RUNNER = APP + ".test";
    private Bundle args;
    private final AtomicInteger mainWorkOutstanding = new AtomicInteger();
    private final AtomicBoolean mainWorkTeardownRequired = new AtomicBoolean();
    private final AtomicInteger nativePending = new AtomicInteger();
    private final AtomicBoolean retained = new AtomicBoolean();
    private final AtomicBoolean workerPending = new AtomicBoolean();
    private interface Observation { void collect() throws Exception; }
    private void observe(String name, JSONArray failures, Observation task) throws Exception {
        try { task.collect(); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw cancelled; }
        catch (Exception | AssertionError failure) {
            failures.put(new JSONObject().put("name", name).put("failureClass", failure.getClass().getName())
                    .put("causeClass", failure.getCause() == null ? JSONObject.NULL : failure.getCause().getClass().getName())
                    .put("deviceNanos", now()));
        }
    }
    public void testFirstScreen() throws Exception {
        check(getInstrumentation() instanceof InstrumentationTestRunner, "Require system runner");
        args = ((InstrumentationTestRunner) getInstrumentation()).getArguments();
        check(APP.equals(getInstrumentation().getTargetContext().getPackageName())
                && RUNNER.equals(getInstrumentation().getContext().getPackageName())
                && Build.VERSION.SDK_INT == 33 && Process.myUid() == getInstrumentation().getTargetContext().getApplicationInfo().uid, "Require fixed owned API33 control target");
        String token = required(args.getString("qaToken"), "Missing qaToken");
        check(token.matches("[a-zA-Z0-9_-]{1,80}"), "Invalid token");
        File parent = new File(required(getInstrumentation().getTargetContext().getExternalFilesDir(null), "No output root"), "hello-control");
        check(parent.isDirectory() || parent.mkdirs(), "Cannot create control output");
        File root = new File(parent, token);
        check(root.mkdir(), "Never reuse observation output");
        JSONObject receipt = new JSONObject().put("schema", 1).put("diagnosticOnly", true).put("accepted", false)
                .put("visualApproved", false).put("performanceAccepted", false).put("releaseAccepted", false)
                .put("qaToken", token).put("pid", Process.myPid()).put("startedNanos", now())
                .put("captureCompleted", false).put("atomicSameFrame", false).put("status", "running");
        JSONArray failures = new JSONArray(); receipt.put("failures", failures);
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "hello-control-worker"); t.setDaemon(true); return t; });
        HandlerThread callbacks = new HandlerThread("hello-control-copy");
        List<Bitmap> owned = new ArrayList<>();
        saveReceipt(root, receipt.put("resourceCleanup", "pending-runner-process-teardown"));
        FutureTask<Void> caseTask = new FutureTask<>(() -> {
            collect(root, receipt, failures, worker, callbacks, owned); return null;
        });
        Thread coordinator = new Thread(caseTask, "hello-control-coordinator"); coordinator.setDaemon(true); coordinator.start();
        try { caseTask.get(120, TimeUnit.SECONDS); }
        catch (TimeoutException failure) { retained.set(true); caseTask.cancel(true); throw failure; }
        catch (InterruptedException failure) { retained.set(true); caseTask.cancel(true); throw failure; }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
        // The coordinator owns receipt and resource writes, including after a deadline.
    }
    private void collect(File root, JSONObject receipt, JSONArray failures, ExecutorService worker,
            HandlerThread callbacks, List<Bitmap> owned) throws Exception {
        try {
            receipt.put("appIdentity", installed(APP, args.getString("appSha256")))
                    .put("runnerIdentity", installed(RUNNER, args.getString("runnerSha256")));
            Intent launch = required(getInstrumentation().getTargetContext().getPackageManager().getLaunchIntentForPackage(APP), "Missing control launcher");
            check(launch.getComponent() != null && (APP + ".MainActivity").equals(launch.getComponent().getClassName()), "Unexpected control launcher");
            workerPending.set(true);
            Future<Activity> launched = worker.submit(() -> {
                try { return getInstrumentation().startActivitySync(launch); }
                finally { workerPending.set(false); }
            });
            Activity activity;
            try { activity = launched.get(25, TimeUnit.SECONDS); }
            catch (TimeoutException | InterruptedException failure) { retained.set(true); throw failure; }
            check(APP.equals(activity.getPackageName()), "Wrong returned Activity");
            long end = now() + TimeUnit.SECONDS.toNanos(10);
            while (now() < end) Thread.sleep(Math.max(1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(end - now()))));
            AtomicReference<SurfaceView> surface = new AtomicReference<>();
            observe("native-views", failures, () -> {
                JSONObject result = onMain(() -> {
                    JSONArray views = new JSONArray(); List<SurfaceView> surfaces = new ArrayList<>();
                    nativeTree(activity.getWindow().getDecorView(), views, surfaces, 0);
                    check(surfaces.size() == 1, "Require one visible attached SurfaceView");
                    surface.set(surfaces.get(0));
                    return new JSONObject().put("nodes", views).put("deviceNanos", now())
                            .put("focus", activity.hasWindowFocus()).put("windowFlags", activity.getWindow().getAttributes().flags);
                }); receipt.put("nativeViews", result);
            });
            callbacks.start(); Handler handler = new Handler(callbacks.getLooper());
            observe("surface", failures, () -> copy(activity, required(surface.get(), "Missing actual SurfaceView"), false, root, receipt, handler, owned));
            observe("window", failures, () -> copy(activity, surface.get(), true, root, receipt, handler, owned));
            observe("display", failures, () -> {
                check(workerPending.compareAndSet(false, true), "Prior worker remains pending");
                Future<Bitmap> task;
                try { task = worker.submit(() -> { try { return getInstrumentation().getUiAutomation().takeScreenshot(); } finally { workerPending.set(false); } }); }
                catch (RuntimeException failure) { workerPending.set(false); throw failure; }
                Bitmap bitmap;
                try { bitmap = task.get(15, TimeUnit.SECONDS); }
                catch (TimeoutException | InterruptedException failure) { retained.set(true); throw failure; }
                bitmap = required(bitmap, "No display screenshot"); owned.add(bitmap);
                receipt.put("display", savePng(root, "display.png", bitmap).put("deviceNanos", now()));
            });
            observe("hello-semantics", failures, () -> {
                check(workerPending.compareAndSet(false, true), "Prior worker remains pending");
                Future<JSONObject> task;
                try { task = worker.submit(() -> {
                    try {
                        AccessibilityNodeInfo node = getInstrumentation().getUiAutomation().getRootInActiveWindow();
                        check(node != null, "No accessibility root in single query");
                        JSONObject result = new JSONObject().put("helloFound", false).put("deviceNanos", now());
                        JSONArray nodes = new JSONArray(); result.put("nodes", nodes);
                        try { semanticTree(node, nodes, result, 0); } finally { node.recycle(); }
                        return result.put("traversalComplete", true);
                    } finally { workerPending.set(false); }
                }); } catch (RuntimeException failure) { workerPending.set(false); throw failure; }
                JSONObject result;
                try { result = task.get(10, TimeUnit.SECONDS); }
                catch (TimeoutException | InterruptedException failure) { retained.set(true); throw failure; }
                receipt.put("semantics", result);
                check(result.getBoolean("helloFound"), "Official Hello text not observed");
            });
            observe("app-after", failures, () -> receipt.put("appIdentityAfter", installed(APP, args.getString("appSha256"))));
            receipt.put("captureCompleted", failures.length() == 0).put("status", failures.length() == 0 ? "captured" : "failed");
            check(failures.length() == 0, "Control observation failures retained");
        } catch (Throwable failure) {
            receipt.put("status", "failed").put("captureCompleted", false).put("failureClass", failure.getClass().getName()); throw failure;
        } finally {
            boolean pending = workerPending.get() || nativePending.get() != 0 || mainWorkOutstanding.get() != 0
                    || mainWorkTeardownRequired.get() || retained.get();
            receipt.put("resourceCleanup", pending ? "pending-runner-process-teardown" : "complete")
                    .put("workerPending", workerPending.get()).put("nativePending", nativePending.get())
                    .put("mainWorkOutstanding", mainWorkOutstanding.get()).put("retainedForRunnerTeardown", retained.get());
            try {
                if (!pending) for (Bitmap bitmap : owned) bitmap.recycle();
                if (nativePending.get() == 0 && !mainWorkTeardownRequired.get() && mainWorkOutstanding.get() == 0 && callbacks.isAlive()) {
                    callbacks.quitSafely(); callbacks.join(1000);
                    if (callbacks.isAlive()) receipt.put("resourceCleanup", "callback-thread-still-alive");
                }
            } catch (InterruptedException failure) {
                receipt.put("resourceCleanup", "interrupted-pending-runner-process-teardown");
                Thread.currentThread().interrupt();
                throw failure;
            } finally {
                try {
                    worker.shutdown();
                    if (!workerPending.get() && !worker.awaitTermination(1, TimeUnit.SECONDS)) {
                        receipt.put("resourceCleanup", "worker-thread-still-alive");
                    }
                } catch (InterruptedException failure) {
                    receipt.put("resourceCleanup", "interrupted-pending-runner-process-teardown");
                    Thread.currentThread().interrupt();
                    throw failure;
                } finally {
                    receipt.put("finishedNanos", now());
                    if (!"complete".equals(receipt.getString("resourceCleanup"))) receipt.put("status", "failed").put("captureCompleted", false);
                    saveReceipt(root, receipt);
                }
            }
            check("complete".equals(receipt.getString("resourceCleanup")), "Control teardown incomplete");
        }
    }
    private JSONObject installed(String packageName, String expected) throws Exception {
        check(expected != null && expected.matches("[0-9a-f]{64}"), "Missing expected package digest");
        PackageManager pm = getInstrumentation().getTargetContext().getPackageManager();
        String file = APP.equals(packageName) ? pm.getApplicationInfo(APP, 0).sourceDir : getInstrumentation().getContext().getApplicationInfo().sourceDir;
        PackageInfo info = pm.getPackageArchiveInfo(file, PackageManager.GET_SIGNING_CERTIFICATES);
        check(info != null && packageName.equals(info.packageName) && fileDigest(new File(file)).equals(expected), "Installed fixed control identity differs");
        android.content.pm.Signature[] signers = info.signingInfo.getApkContentsSigners();
        check(signers.length == 1 && digest(signers[0].toByteArray()).equals(args.getString("signerSha256")), "Control signer differs");
        return new JSONObject().put("package", packageName).put("sha256", expected).put("signerSha256", args.getString("signerSha256"));
    }
    private void copy(Activity activity, SurfaceView view, boolean window, File root, JSONObject receipt,
            Handler handler, List<Bitmap> owned) throws Exception {
        String name = window ? "window" : "surface";
        JSONObject row = new JSONObject().put("submittedNanos", now()).put("result", -1).put("callbackObserved", false);
        receipt.put(name, row);
        Point size = onMain(() -> { Point p = new Point(); activity.getDisplay().getRealSize(p); return p; });
        Bitmap bitmap = Bitmap.createBitmap(window ? size.x : view.getWidth(), window ? size.y : view.getHeight(), Bitmap.Config.ARGB_8888);
        owned.add(bitmap); bitmap.eraseColor(0xfff10df1);
        AtomicInteger status = new AtomicInteger(-1); AtomicLong callbackAt = new AtomicLong(); CountDownLatch done = new CountDownLatch(1); AtomicBoolean pending = new AtomicBoolean();
        onMain(() -> {
            nativePending.incrementAndGet(); pending.set(true);
            PixelCopy.OnPixelCopyFinishedListener callback = result -> { status.set(result); callbackAt.set(now()); if (pending.compareAndSet(true, false)) nativePending.decrementAndGet(); done.countDown(); };
            try { if (window) PixelCopy.request(activity.getWindow(), bitmap, callback, handler); else PixelCopy.request(view, bitmap, callback, handler); }
            catch (RuntimeException | Error failure) { if (pending.compareAndSet(true, false)) nativePending.decrementAndGet(); throw failure; }
            return null;
        });
        boolean finished = done.await(3, TimeUnit.SECONDS);
        row.put("result", status.get()).put("callbackObserved", finished).put("callbackNanos", callbackAt.get()).put("readbackNanos", now());
        check(finished && status.get() == PixelCopy.SUCCESS, "PixelCopy did not succeed");
        row.put("image", savePng(root, name + ".png", bitmap));
    }
    private static void nativeTree(View view, JSONArray rows, List<SurfaceView> surfaces, int depth) throws Exception {
        check(depth <= 40 && rows.length() < 128, "Native View bound exceeded");
        int[] xy = new int[2]; view.getLocationOnScreen(xy);
        rows.put(new JSONObject().put("class", view.getClass().getName()).put("depth", depth).put("identity", System.identityHashCode(view))
                .put("x", xy[0]).put("y", xy[1]).put("width", view.getWidth()).put("height", view.getHeight())
                .put("alpha", view.getAlpha()).put("shown", view.isShown()).put("attached", view.isAttachedToWindow()));
        if (view instanceof SurfaceView && view.isShown() && view.isAttachedToWindow()) surfaces.add((SurfaceView) view);
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) nativeTree(group.getChildAt(i), rows, surfaces, depth + 1); }
    }
    private static String text(CharSequence value) { String s = value == null ? "" : value.toString(); return s.substring(0, Math.min(s.length(), 96)); }
    private static void semanticTree(AccessibilityNodeInfo node, JSONArray rows, JSONObject result, int depth) throws Exception {
        check(depth <= 32 && rows.length() < 192, "Semantic bound exceeded");
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        String value = text(node.getText()), description = text(node.getContentDescription());
        if (APP.contentEquals(node.getPackageName() == null ? "" : node.getPackageName()) && node.isVisibleToUser()
                && !bounds.isEmpty() && ("Hello, world!".equals(value) || "Hello, world!".equals(description))) result.put("helloFound", true).put("helloBounds", rect(bounds));
        rows.put(new JSONObject().put("text", value).put("description", description).put("package", text(node.getPackageName()))
                .put("bounds", rect(bounds)).put("visible", node.isVisibleToUser()));
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) try { semanticTree(child, rows, result, depth + 1); } finally { child.recycle(); } }
    }

    private <T> T onMain(Callable<T> block) throws Exception {
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicInteger state = new AtomicInteger(); // queued=0, running=1, finished=2, cancelled-before-start=3
        mainWorkOutstanding.incrementAndGet();
        FutureTask<T> task = new FutureTask<>(() -> {
            try { return block.call(); }
            finally { state.set(2); mainWorkOutstanding.decrementAndGet(); }
        });
        Runnable dispatch = () -> { if (state.compareAndSet(0, 1)) task.run(); };
        Runnable stopWaiting = () -> {
            if (state.compareAndSet(0, 3)) {
                handler.removeCallbacks(dispatch);
                task.cancel(false);
                mainWorkOutstanding.decrementAndGet();
            } else {
                mainWorkTeardownRequired.set(true);
            }
        };
        if (!handler.post(dispatch)) {
            stopWaiting.run();
            throw new IllegalStateException("Main Looper rejected snapshot");
        }
        try {
            return task.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException failure) {
            stopWaiting.run();
            throw failure;
        }
    }

    private static JSONObject savePng(File root, String name, Bitmap bitmap) throws Exception {
        File file = new File(root, name);
        check(file.createNewFile(), "Capture output must be new");
        try (FileOutputStream output = new FileOutputStream(file)) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "PNG encoding failed");
        }
        check(file.length() > 0, "PNG is empty");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        check(options.outWidth == bitmap.getWidth() && options.outHeight == bitmap.getHeight()
                && "image/png".equals(options.outMimeType), "Encoded PNG dimensions or format mismatch");
        return new JSONObject().put("file", name).put("bytes", file.length()).put("sha256", fileDigest(file))
                .put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                .put("config", bitmap.getConfig() == null ? null : bitmap.getConfig().name())
                .put("encoding", "lossless PNG; no crop, resize or nonblack assertion");
    }

    private static void saveReceipt(File root, JSONObject receipt) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(root, "receipt.json"))) {
            output.write((receipt.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JSONArray rect(Rect value) {
        return new JSONArray().put(value.left).put(value.top).put(value.right).put(value.bottom);
    }

    private static long now() { return SystemClock.elapsedRealtimeNanos(); }

    private static String digest(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String fileDigest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[65_536];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            output[index * 2] = alphabet[(bytes[index] & 255) >>> 4];
            output[index * 2 + 1] = alphabet[bytes[index] & 15];
        }
        return new String(output);
    }

    private static <T> T required(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

package dev.aerove.lite;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.view.Display;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One Surface readback, then one compositor capture; platform-provided JUnit3 only. */
@SuppressWarnings("deprecation")
public class SurfacePixelDiagnosticTest extends InstrumentationTestCase {
    private static final String TARGET = "dev.aerove.lite";
    private static final String HARNESS = "dev.aerove.lite.test";
    private static final String LAUNCHER = "dev.aerove.lite.FlutterMainActivity";
    private static final String ORIGINAL_APK = "e0e4ddb3cf4868377a903c7c031ee6dcc5715a1e303d303a012bcb8a366f26f1";
    private static final String ORIGINAL_SIGNER = "09f9e021ddac36ead015d27e073b673e79fdf4e02605c402a505781963b1a4d8";
    private final AtomicInteger mainWorkOutstanding = new AtomicInteger();
    private final AtomicBoolean mainWorkTeardownRequired = new AtomicBoolean();
    private final AtomicBoolean coordinatorTeardownRequired = new AtomicBoolean();
    private final AtomicBoolean coordinatorDeadlineExceeded = new AtomicBoolean();
    private Instrumentation instrumentation;
    private Bundle arguments;
    private Context context;

    public void testCaptureLateSurfaceAndCompositor() throws Exception {
        // The system runner injects Instrumentation after constructing this JUnit3 case.
        instrumentation = required(getInstrumentation(), "Instrumentation was not injected");
        check(instrumentation instanceof InstrumentationTestRunner, "Require the system test runner");
        arguments = required(((InstrumentationTestRunner) instrumentation).getArguments(), "Missing arguments");
        context = instrumentation.getTargetContext();
        FutureTask<Void> task = new FutureTask<>(() -> {
            captureLateSurfaceAndCompositor();
            return null;
        });
        Thread coordinator = new Thread(task, "surface-pixel-diagnostic-case");
        coordinator.setDaemon(true);
        coordinator.start();
        try {
            task.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            coordinatorDeadlineExceeded.set(true);
            coordinatorTeardownRequired.set(true);
            task.cancel(true); // Interrupt only this case thread, never the App main/native worker.
            throw failure;
        } catch (InterruptedException failure) {
            coordinatorTeardownRequired.set(true);
            task.cancel(true);
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private void captureLateSurfaceAndCompositor() throws Exception {
        String token = argument("qaToken");
        require(token.matches("[a-zA-Z0-9_-]{1,80}"), "Invalid qaToken");
        check(TARGET.equals(context.getPackageName()) && HARNESS.equals(instrumentation.getContext().getPackageName()),
                "Unexpected target or harness package");
        check(Build.VERSION.SDK_INT == 33 && Process.myUid() == context.getApplicationInfo().uid,
                "Require the owned API33 target App process");
        File parent = new File(required(context.getExternalFilesDir(null), "Missing external files directory"),
                "surface-pixel-diagnostic");
        check(parent.isDirectory() || parent.mkdirs(), "Cannot create diagnostic parent");
        File root = new File(parent, token);
        check(root.mkdir(), "qaToken directory must be new");
        JSONObject receipt = new JSONObject().put("schema", 1).put("producer", getClass().getName())
                .put("diagnosticOnly", true).put("accepted", false).put("visualApproved", false)
                .put("captureCompleted", false).put("status", "running")
                .put("qaTokenSha256", digest(token.getBytes(StandardCharsets.UTF_8)))
                .put("pid", Process.myPid()).put("uid", Process.myUid()).put("sdkInt", Build.VERSION.SDK_INT)
                .put("startedElapsedRealtimeNanos", now())
                .put("observation", "instrumented fresh install; Launcher initialization may write normal App data")
                .put("atomicSameFrame", false).put("activityFinishRequestedByTest", false)
                .put("activityTeardownOwner", "system InstrumentationTestRunner finishes instrumentation; host owns installation and AVD cleanup");
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "surface-pixel-diagnostic-worker");
            thread.setDaemon(true);
            return thread;
        });
        HandlerThread callbacks = new HandlerThread("surface-pixel-diagnostic-callback");
        Future<?> activeTask = null;
        boolean copyPending = false;
        AtomicInteger copyStatus = new AtomicInteger(-1);
        AtomicLong copyStart = new AtomicLong();
        AtomicLong copyReturn = new AtomicLong();
        AtomicLong copyCallback = new AtomicLong();
        AtomicLong compositorStart = new AtomicLong();
        AtomicLong compositorEnd = new AtomicLong();
        AtomicLong surfaceEpoch = new AtomicLong();
        SurfaceHolder.Callback surfaceObserver = new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) { surfaceEpoch.incrementAndGet(); }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surfaceEpoch.incrementAndGet();
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceEpoch.incrementAndGet(); }
        };
        AtomicReference<SurfaceHolder> observedHolder = new AtomicReference<>();
        Bitmap surfaceBitmap = null;
        Bitmap compositorBitmap = null;
        String stage = "identity";
        saveReceipt(root, receipt);
        try {
            receipt.put("originalApp", originalIdentity()).put("harness", harnessIdentity())
                    .put("execution", new JSONObject()
                            .put("source", sourceArgument("executorSourceSha"))
                            .put("run", numberArgument("executionRun"))
                            .put("attempt", numberArgument("executionAttempt")));
            stage = "launcher";
            saveReceipt(root, receipt);
            Intent launch = required(context.getPackageManager().getLaunchIntentForPackage(TARGET),
                    "No production Launcher Intent");
            check(Intent.ACTION_MAIN.equals(launch.getAction()) && launch.hasCategory(Intent.CATEGORY_LAUNCHER),
                    "Intent is not MAIN/LAUNCHER");
            ActivityInfo resolved = required(launch.resolveActivityInfo(context.getPackageManager(), 0),
                    "Cannot resolve Launcher");
            check(TARGET.equals(resolved.packageName) && LAUNCHER.equals(resolved.name)
                    && TARGET.equals(resolved.processName), "Unexpected Launcher component or process");
            receipt.put("launcherSubmittedNanos", now());
            Future<Activity> launchTask = worker.submit((Callable<Activity>) () -> instrumentation.startActivitySync(launch));
            activeTask = launchTask;
            Activity activity = launchTask.get(25, TimeUnit.SECONDS);
            activeTask = null;
            check(TARGET.equals(activity.getPackageName()) && LAUNCHER.equals(activity.getComponentName().getClassName()),
                    "Unexpected returned Launcher Activity");
            receipt.put("launcherReturnedNanos", now()).put("lateFrameDelayMs", 15_000);
            stage = "late-frame-wait";
            saveReceipt(root, receipt);
            long lateAt = SystemClock.elapsedRealtime() + 15_000;
            while (SystemClock.elapsedRealtime() < lateAt) {
                Thread.sleep(Math.max(1L, Math.min(100L, lateAt - SystemClock.elapsedRealtime())));
            }
            stage = "surface-snapshot";
            Snapshot before = onMain(() -> {
                Snapshot initial = snapshot(activity, surfaceEpoch.get());
                initial.view.getHolder().addCallback(surfaceObserver);
                observedHolder.set(initial.view.getHolder());
                return snapshot(activity, surfaceEpoch.get());
            });
            receipt.put("beforeSurfaceCopy", before.json);
            Bitmap destination = Bitmap.createBitmap(before.width, before.height, Bitmap.Config.ARGB_8888);
            surfaceBitmap = destination;
            destination.eraseColor(0xfff10df1);
            CountDownLatch finished = new CountDownLatch(1);
            callbacks.start();
            Handler callbackHandler = new Handler(callbacks.getLooper());
            stage = "surface-copy";
            receipt.put("surfaceCopy", new JSONObject().put("method", "PixelCopy.request(SurfaceView,Bitmap,listener,Handler)")
                    .put("submittedNanos", now()).put("destinationSentinelArgb", "fff10df1"));
            saveReceipt(root, receipt);
            copyPending = true;
            Future<Void> copyTask = worker.submit(() -> {
                copyStart.set(now());
                try {
                    PixelCopy.request(before.view, destination, result -> {
                        copyStatus.set(result);
                        copyCallback.set(now());
                        finished.countDown();
                    }, callbackHandler);
                    return null;
                } finally {
                    copyReturn.set(now());
                }
            });
            activeTask = copyTask;
            copyTask.get(20, TimeUnit.SECONDS);
            activeTask = null;
            copyPending = false;
            check(finished.await(3, TimeUnit.SECONDS), "PixelCopy callback deadline exceeded");
            check(copyStatus.get() == PixelCopy.SUCCESS, "PixelCopy returned a failure status");
            Snapshot afterCopy = onMain(() -> snapshot(activity, surfaceEpoch.get()));
            checkSame(before, afterCopy);
            receipt.put("afterSurfaceCopy", afterCopy.json);
            receipt.getJSONObject("surfaceCopy").put("image", savePng(root, "surface.png", destination));
            stage = "compositor-copy";
            Snapshot beforeCompositor = onMain(() -> snapshot(activity, surfaceEpoch.get()));
            checkSame(before, beforeCompositor);
            receipt.put("beforeCompositorCopy", beforeCompositor.json);
            receipt.put("compositorCopy", new JSONObject().put("method", "UiAutomation.takeScreenshot")
                    .put("uiAutomationFirstAccessAfterSurfacePng", true).put("submittedNanos", now()));
            saveReceipt(root, receipt);
            // First and only UiAutomation access follows the successfully saved Surface PNG.
            Future<Bitmap> screenshotTask = worker.submit((Callable<Bitmap>) () -> {
                compositorStart.set(now());
                try {
                    return instrumentation.getUiAutomation().takeScreenshot();
                } finally {
                    compositorEnd.set(now());
                }
            });
            activeTask = screenshotTask;
            Bitmap screenshot = required(screenshotTask.get(15, TimeUnit.SECONDS), "UiAutomation returned no screenshot");
            activeTask = null;
            compositorBitmap = screenshot;
            Snapshot afterCompositor = onMain(() -> snapshot(activity, surfaceEpoch.get()));
            checkSame(before, afterCompositor);
            check(screenshot.getWidth() == afterCompositor.displayWidth
                    && screenshot.getHeight() == afterCompositor.displayHeight,
                    "Compositor dimensions do not match the actual display");
            receipt.put("afterCompositorCopy", afterCompositor.json);
            receipt.getJSONObject("compositorCopy").put("image", savePng(root, "compositor.png", screenshot));
            stage = "complete";
            receipt.put("captureCompleted", true).put("status", "captured");
        } catch (Throwable failure) {
            receipt.put("status", "failed").put("failureClass", failure.getClass().getName())
                    .put("failureCauseClass", failure.getCause() == null ? null : failure.getCause().getClass().getName());
            // Do not export arbitrary exception messages, tokens, or App data into this receipt.
            throw failure;
        } finally {
            boolean pending = activeTask != null && !activeTask.isDone();
            boolean mainPending = mainWorkOutstanding.get() != 0 || mainWorkTeardownRequired.get();
            boolean nativeCopyPending = copyPending && pending;
            JSONObject surfaceCopy = receipt.optJSONObject("surfaceCopy");
            if (surfaceCopy != null) surfaceCopy.put("startNanos", copyStart.get())
                    .put("requestReturnedNanos", copyReturn.get()).put("callbackNanos", copyCallback.get())
                    .put("result", copyStatus.get());
            JSONObject compositorCopy = receipt.optJSONObject("compositorCopy");
            if (compositorCopy != null) compositorCopy.put("startNanos", compositorStart.get()).put("endNanos", compositorEnd.get());
            receipt.put("stage", stage).put("finishedElapsedRealtimeNanos", now())
                    .put("workerStillPending", pending).put("pixelCopyStillPending", nativeCopyPending)
                    .put("resourceCleanup", pending || mainPending || coordinatorTeardownRequired.get()
                            ? "pending-runner-process-teardown" : "complete")
                    .put("uiAutomationTeardownOwner", "system Instrumentation/InstrumentationTestRunner");
            try {
                if (!nativeCopyPending) {
                    if (surfaceBitmap != null) surfaceBitmap.recycle();
                    if (callbacks.isAlive()) {
                        callbacks.quitSafely();
                        callbacks.join(1_000);
                        if (callbacks.isAlive()) receipt.put("resourceCleanup", "callback-thread-still-alive");
                    }
                }
                if (compositorBitmap != null) compositorBitmap.recycle();
                SurfaceHolder holder = observedHolder.get();
                if (holder != null) onMain(() -> { holder.removeCallback(surfaceObserver); return null; });
                // shutdown never interrupts/cancels the App main thread or a native PixelCopy operation.
                worker.shutdown();
                if (!pending && !worker.awaitTermination(1, TimeUnit.SECONDS)) {
                    receipt.put("resourceCleanup", "worker-thread-still-alive");
                }
            } catch (InterruptedException failure) {
                receipt.put("resourceCleanup", "interrupted-pending-runner-process-teardown");
                worker.shutdown();
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                receipt.put("resourceCleanup", "cleanup-failed").put("cleanupFailureClass", failure.getClass().getName());
                worker.shutdown();
            } finally {
                int mainOutstanding = mainWorkOutstanding.get();
                boolean mainTeardown = mainWorkTeardownRequired.get();
                boolean coordinatorTeardown = coordinatorTeardownRequired.get();
                receipt.put("mainWorkOutstanding", mainOutstanding).put("mainWorkTeardownRequired", mainTeardown)
                        .put("mainWorkStillPending", mainOutstanding != 0 || mainTeardown)
                        .put("coordinatorTeardownRequired", coordinatorTeardown)
                        .put("coordinatorDeadlineExceeded", coordinatorDeadlineExceeded.get());
                if (mainOutstanding != 0 || mainTeardown || coordinatorTeardown) {
                    receipt.put("resourceCleanup", "pending-runner-process-teardown");
                }
                boolean cleanupFailed = !"complete".equals(receipt.getString("resourceCleanup"));
                boolean captured = "captured".equals(receipt.getString("status"));
                if (captured && cleanupFailed) receipt.put("status", "failed").put("failureClass", IllegalStateException.class.getName());
                saveReceipt(root, receipt);
                if (captured && cleanupFailed) throw new IllegalStateException("Diagnostic resource cleanup incomplete");
            }
        }
    }

    private JSONObject originalIdentity() throws Exception {
        if ("34".equals(argument("originalAppVersionCode"))) {
            String original257 = "c58aed2e2cddc15b5e8a1953bac88bef3b60feddbbd1621e822a230bc28e9a64";
            check(original257.equals(argument("originalAppApkSha256")), "Unexpected original257 APK argument");
            check(ORIGINAL_SIGNER.equals(argument("originalAppSignerSha256")), "Unexpected original257 signer argument");
            JSONObject identity = installedIdentity(TARGET, original257, ORIGINAL_SIGNER, 34L);
            check("0.2.0-beta.1".equals(identity.getString("versionName")), "Unexpected original257 versionName");
            return identity.put("source", "baea420b1ec6e42ecdd3f9e2610c3dc2346b326c")
                    .put("tree", "6b15d9ae6b20d85f324bff38855523f5a26d0375")
                    .put("buildRun", "41958443583").put("buildAttempt", "1")
                    .put("provenance", "fixed original identity; host must retain actual archive/API binding");
        }
        check(ORIGINAL_APK.equals(argument("originalAppApkSha256")), "Unexpected original APK argument");
        check(ORIGINAL_SIGNER.equals(argument("originalAppSignerSha256")), "Unexpected original signer argument");
        check("28".equals(argument("originalAppVersionCode")), "Unexpected original versionCode argument");
        return installedIdentity(TARGET, ORIGINAL_APK, ORIGINAL_SIGNER, 28L)
                .put("source", "2a38627ca1c224f6ebf71c3e42052912699decdb")
                .put("buildRun", "41779045595").put("buildAttempt", "1")
                .put("provenance", "fixed original identity; host must retain actual archive/API binding");
    }

    private JSONObject harnessIdentity() throws Exception {
        String signer = hashArgument("harnessSignerSha256");
        check(ORIGINAL_SIGNER.equals(signer), "Harness must retain the independent Lite signer");
        return installedIdentity(HARNESS, hashArgument("harnessApkSha256"), signer, null)
                .put("source", sourceArgument("harnessSourceSha"))
                .put("buildRun", numberArgument("harnessBuildRun")).put("buildAttempt", numberArgument("harnessBuildAttempt"));
    }

    private JSONObject installedIdentity(String name, String expectedApk, String expectedSigner, Long code) throws Exception {
        PackageManager manager = context.getPackageManager();
        ApplicationInfo harnessApp = instrumentation.getContext().getApplicationInfo();
        PackageInfo info = TARGET.equals(name) ? manager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
                : required(manager.getPackageArchiveInfo(harnessApp.sourceDir, PackageManager.GET_SIGNING_CERTIFICATES),
                        "Cannot parse the runtime harness APK");
        ApplicationInfo app = TARGET.equals(name) ? required(info.applicationInfo, "Missing application info") : harnessApp;
        check(name.equals(info.packageName) && name.equals(app.packageName), "Runtime package identity mismatch");
        check(app.splitSourceDirs == null || app.splitSourceDirs.length == 0, "Split APK identity is outside this diagnostic");
        Signature[] signers = required(required(info.signingInfo, "Missing signing info").getApkContentsSigners(), "Missing current signers");
        check(signers.length == 1, "Exactly one current signer is required");
        String signer = digest(signers[0].toByteArray());
        String apk = fileDigest(new File(app.sourceDir));
        check(expectedApk.equals(apk) && expectedSigner.equals(signer), "Installed APK or signer mismatch");
        if (code != null) check(info.getLongVersionCode() == code.longValue(), "Original versionCode mismatch");
        return new JSONObject().put("package", name).put("apkSha256", apk).put("signerSha256", signer)
                .put("versionCode", info.getLongVersionCode()).put("versionName", info.versionName);
    }

    private static final class Snapshot {
        final SurfaceView view;
        final Surface surface;
        final long lifecycleEpoch;
        final int width, height, displayWidth, displayHeight, rotation;
        final JSONObject json;
        Snapshot(SurfaceView view, Surface surface, long lifecycleEpoch, int width, int height,
                int displayWidth, int displayHeight, int rotation, JSONObject json) {
            this.view = view; this.surface = surface; this.lifecycleEpoch = lifecycleEpoch;
            this.width = width; this.height = height; this.displayWidth = displayWidth;
            this.displayHeight = displayHeight; this.rotation = rotation; this.json = json;
        }
    }

    private Snapshot snapshot(Activity activity, long lifecycleEpoch) throws Exception {
        check(Looper.myLooper() == Looper.getMainLooper(), "Snapshot must run on the main thread");
        check(!activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus(),
                "Activity is not the live focused window");
        View decor = activity.getWindow().getDecorView();
        check(decor.getWindowToken() != null, "Window has no token");
        List<SurfaceView> candidates = new ArrayList<>();
        visit(decor, candidates);
        check(candidates.size() == 1, "Require exactly one visible attached SurfaceView");
        SurfaceView view = candidates.get(0);
        Rect frame = new Rect(view.getHolder().getSurfaceFrame());
        Surface surface = view.getHolder().getSurface();
        Rect visible = new Rect();
        check(surface.isValid() && frame.width() > 0 && frame.height() > 0 && view.getWidth() > 0 && view.getHeight() > 0
                && view.getGlobalVisibleRect(visible) && !visible.isEmpty(), "Surface is not valid and visible");
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Display display = required(activity.getDisplay(), "Activity has no display");
        Point displaySize = new Point();
        display.getRealSize(displaySize);
        JSONObject json = new JSONObject().put("elapsedRealtimeNanos", now()).put("pid", Process.myPid())
                .put("activity", activity.getComponentName().flattenToString())
                .put("activityIdentity", System.identityHashCode(activity))
                .put("windowIdentity", System.identityHashCode(activity.getWindow()))
                .put("windowTokenIdentity", System.identityHashCode(decor.getWindowToken()))
                .put("windowFlags", activity.getWindow().getAttributes().flags).put("hasWindowFocus", activity.hasWindowFocus())
                .put("viewClass", view.getClass().getName()).put("viewIdentity", System.identityHashCode(view))
                .put("surfaceIdentity", System.identityHashCode(surface)).put("surfaceLifecycleEpoch", lifecycleEpoch)
                .put("generationMethod", "public SurfaceHolder.Callback observed epoch since registration; not native generation")
                .put("surfaceFrame", rect(frame)).put("bufferWidth", frame.width()).put("bufferHeight", frame.height())
                .put("viewWidth", view.getWidth()).put("viewHeight", view.getHeight())
                .put("screenX", location[0]).put("screenY", location[1]).put("globalVisibleRect", rect(visible))
                .put("displayWidth", displaySize.x).put("displayHeight", displaySize.y).put("rotation", display.getRotation())
                .put("locales", context.getResources().getConfiguration().getLocales().toLanguageTags());
        return new Snapshot(view, surface, lifecycleEpoch, frame.width(), frame.height(),
                displaySize.x, displaySize.y, display.getRotation(), json);
    }

    private static void visit(View view, List<SurfaceView> candidates) {
        if (view instanceof SurfaceView && view.isAttachedToWindow() && view.isShown()) candidates.add((SurfaceView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) visit(group.getChildAt(index), candidates);
        }
    }

    private static void checkSame(Snapshot before, Snapshot after) throws Exception {
        check(before.view == after.view && before.surface == after.surface && before.lifecycleEpoch == after.lifecycleEpoch
                && before.width == after.width && before.height == after.height && before.rotation == after.rotation
                && before.displayWidth == after.displayWidth && before.displayHeight == after.displayHeight,
                "Activity Surface or display changed during capture");
        for (String key : new String[]{"activityIdentity", "windowIdentity", "windowTokenIdentity", "screenX", "screenY",
                "viewWidth", "viewHeight", "globalVisibleRect"}) {
            check(before.json.get(key).toString().equals(after.json.get(key).toString()), "Window or View geometry changed");
        }
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

    private String argument(String name) { return required(arguments.getString(name), "Missing argument: " + name); }
    private String hashArgument(String name) {
        String value = argument(name);
        require(value.matches("[0-9a-f]{64}"), "Invalid SHA256 argument: " + name);
        return value;
    }
    private String sourceArgument(String name) {
        String value = argument(name);
        require(value.matches("[0-9a-f]{40}"), "Invalid source SHA argument: " + name);
        return value;
    }
    private String numberArgument(String name) {
        String value = argument(name);
        require(value.matches("[1-9][0-9]*"), "Invalid positive identity argument: " + name);
        return value;
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
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

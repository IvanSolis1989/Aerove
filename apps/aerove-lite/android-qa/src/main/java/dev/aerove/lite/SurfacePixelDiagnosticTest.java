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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private boolean original265Probe;
    private View firstFrameView;
    private Method firstFrameRead;

    /** Fixed release38 observation; no App mutation, scene navigation or renderer override. */
    public void testOriginal290MappedRendererAndSurface() throws Exception {
        original290Probe = true;
        testCaptureLateSurfaceAndCompositor();
    }

    private static final String ORIGINAL290_APK = "e8a0a712317cf59f8aad5034ad2c560f0f01f520f668cb241146731a9b831108";
    private static final String ORIGINAL290_MAP_SHA = "7365901cbb1d15ea75521b172ab43be7f99993929994c98bd9468bc2b65f1a8a";
    private static final String ORIGINAL290_MAP_ID = "2ec72d72bdeb76128550b7e7eb062450ca6ff4f15d6a5a1b78a8b62670e20cfa";
    private boolean original290Probe;
    private final AtomicBoolean original290WorkerPending = new AtomicBoolean();
    private final AtomicInteger original290NativePending = new AtomicInteger();
    private final AtomicBoolean original290RetainedResources = new AtomicBoolean();

    private interface Observation290 { void collect() throws Exception; }

    /** Record independent failures, then fail the test after safe independent evidence is saved. */
    private void observe290(String label, JSONObject receipt, JSONArray failures, Observation290 observation) throws Exception {
        try {
            observation.collect();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        } catch (Exception | AssertionError failure) {
            failures.put(new JSONObject().put("observation", label).put("failureClass", failure.getClass().getName())
                    .put("failureCauseClass", failure.getCause() == null ? JSONObject.NULL : failure.getCause().getClass().getName())
                    .put("deviceNanos", now()));
        }
    }

    private void captureOriginal290(Activity activity, File root, JSONObject receipt, ExecutorService worker,
            HandlerThread callbacks, AtomicLong epoch, SurfaceHolder.Callback observer,
            AtomicReference<SurfaceHolder> observedHolder) throws Exception {
        JSONArray failures = new JSONArray();
        receipt.put("observationFailures", failures).put("checkpoint", "fresh-startup-10s")
                .put("fixture", "host-verified-fresh-empty-app-data").put("semanticNavigationPerformed", false)
                .put("performanceAccepted", false).put("releaseAccepted", false)
                .put("mappingSha256", ORIGINAL290_MAP_SHA).put("mappingId", ORIGINAL290_MAP_ID)
                .put("originalMapping", new JSONObject().put("sha256", ORIGINAL290_MAP_SHA).put("pgMapId", ORIGINAL290_MAP_ID))
                .put("mappingBinding", "host verifies original mapping bytes; runtime verifies exact original APK and mapped field types")
                .put("checkpointDelayMs", 10_000).put("atomicSameFrame", false);
        long checkpointAt = now() + TimeUnit.SECONDS.toNanos(10);
        // Register the holder epoch before the wait whenever a usable Surface exists.
        observe290("initial-surface", receipt, failures, () -> onMain(() -> {
            Snapshot initial = snapshot(activity, epoch.get());
            initial.view.getHolder().addCallback(observer);
            observedHolder.set(initial.view.getHolder());
            return null;
        }));
        while (now() < checkpointAt) {
            Thread.sleep(Math.max(1L, Math.min(100L, TimeUnit.NANOSECONDS.toMillis(checkpointAt - now()))));
        }
        receipt.put("checkpointDeviceNanos", now());
        AtomicReference<Snapshot> before = new AtomicReference<>();
        observe290("surface-before", receipt, failures, () -> {
            Snapshot value = onMain(() -> snapshot(activity, epoch.get()));
            before.set(value);
            receipt.put("beforeSurfaceCopy", value.json);
        });
        JSONObject mappedBefore = new JSONObject();
        receipt.put("mappedRendererBefore", mappedBefore).put("mappedRendererObservation", mappedBefore);
        observe290("mapped-renderer-before", receipt, failures,
                () -> onMain(() -> mapped290(activity, mappedBefore)));
        JSONObject marker = new JSONObject().put("schema", 1).put("checkpoint", "fresh-startup-10s")
                .put("qaToken", argument("qaToken")).put("pid", Process.myPid()).put("elapsedRealtimeNanos", now())
                .put("surfaceEpoch", epoch.get()).put("originalAppApkSha256", ORIGINAL290_APK)
                .put("mappingSha256", ORIGINAL290_MAP_SHA).put("atomicSameFrame", false);
        File markerFile = new File(root, "checkpoint.json");
        File markerPending = new File(root, "checkpoint.json.tmp");
        check(!markerFile.exists() && markerPending.createNewFile(), "Checkpoint marker must be new");
        try (FileOutputStream out = new FileOutputStream(markerPending)) {
            out.write((marker.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        check(markerPending.renameTo(markerFile), "Atomic checkpoint marker rename failed");
        receipt.put("checkpointMarker", new JSONObject().put("file", markerFile.getName())
                .put("sha256", fileDigest(markerFile)).put("deviceNanos", marker.getLong("elapsedRealtimeNanos")));
        saveReceipt(root, receipt);
        callbacks.start();
        Handler handler = new Handler(callbacks.getLooper());
        List<Bitmap> owned = new ArrayList<>();
        try {
            observe290("surface-copy", receipt, failures, () -> {
                Snapshot value = required(before.get(), "No valid Surface snapshot for PixelCopy");
                copy290(activity, value, false, root, receipt, handler, owned);
            });
            observe290("window-copy", receipt, failures,
                    () -> copy290(activity, before.get(), true, root, receipt, handler, owned));
            observe290("compositor-copy", receipt, failures, () -> {
                JSONObject copy = new JSONObject().put("method", "UiAutomation.takeScreenshot").put("submittedNanos", now());
                receipt.put("compositorCopy", copy);
                original290WorkerPending.set(true);
                Future<Bitmap> task;
                try {
                    task = worker.submit(() -> {
                        try { return instrumentation.getUiAutomation().takeScreenshot(); }
                        finally { original290WorkerPending.set(false); }
                    });
                } catch (RuntimeException rejected) {
                    original290WorkerPending.set(false);
                    throw rejected;
                }
                Bitmap image;
                try { image = task.get(15, TimeUnit.SECONDS); }
                catch (TimeoutException | InterruptedException unfinished) {
                    original290RetainedResources.set(true);
                    throw unfinished;
                }
                copy.put("returnedNanos", now()).put("returnedNull", image == null);
                image = required(image, "UiAutomation returned no screenshot");
                owned.add(image);
                final Bitmap captured = image;
                Point size = onMain(() -> {
                    Point point = new Point();
                    required(activity.getDisplay(), "Activity has no display").getRealSize(point);
                    return point;
                });
                check(captured.getWidth() == size.x && captured.getHeight() == size.y, "Compositor display dimensions changed");
                copy.put("image", savePng(root, "compositor.png", captured));
            });
            observe290("surface-after", receipt, failures, () -> {
                Snapshot after = onMain(() -> snapshot(activity, epoch.get()));
                receipt.put("afterCompositorCopy", after.json);
                checkSame(required(before.get(), "No before Surface snapshot"), after);
            });
            JSONObject mappedAfter = new JSONObject();
            receipt.put("mappedRendererAfter", mappedAfter);
            observe290("mapped-renderer-after", receipt, failures,
                    () -> onMain(() -> mapped290(activity, mappedAfter)));
            observe290("original-apk-after", receipt, failures, () -> receipt.put("originalAppAfter", originalIdentity()));
            receipt.put("collectionAttemptsFinished", true).put("captureCompleted", failures.length() == 0)
                    .put("status", failures.length() == 0 ? "captured" : "failed");
            saveReceipt(root, receipt);
            check(failures.length() == 0, "Original290 independent observations failed; inspect observationFailures");
        } finally {
            // Native work cannot be cancelled safely. Keep its bitmaps/handler until runner-process teardown.
            if (original290NativePending.get() == 0 && !mainWorkTeardownRequired.get() && mainWorkOutstanding.get() == 0) {
                for (Bitmap bitmap : owned) bitmap.recycle();
            } else {
                original290RetainedResources.set(true);
            }
            receipt.put("original290WorkerPending", original290WorkerPending.get())
                    .put("original290NativePending", original290NativePending.get());
        }
    }

    private void copy290(Activity activity, Snapshot snapshot, boolean window, File root, JSONObject receipt,
            Handler handler, List<Bitmap> owned) throws Exception {
        String key = window ? "windowCopy" : "surfaceCopy";
        Point size = onMain(() -> {
            Point point = new Point();
            required(activity.getDisplay(), "Activity has no display").getRealSize(point);
            return point;
        });
        int width = window ? size.x : required(snapshot, "Missing Surface snapshot").width;
        int height = window ? size.y : snapshot.height;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        owned.add(bitmap);
        bitmap.eraseColor(0xfff10df1);
        JSONObject copy = new JSONObject().put("method", window ? "PixelCopy.request(Window)" : "PixelCopy.request(SurfaceView)")
                .put("submittedNanos", now()).put("destinationSentinelArgb", "fff10df1")
                .put("callbackObserved", false).put("result", -1);
        receipt.put(key, copy);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(-1);
        AtomicLong callbackAt = new AtomicLong();
        AtomicBoolean pending = new AtomicBoolean();
        onMain(() -> {
            original290NativePending.incrementAndGet();
            pending.set(true);
            PixelCopy.OnPixelCopyFinishedListener listener = code -> {
                status.set(code); callbackAt.set(now());
                if (pending.compareAndSet(true, false)) original290NativePending.decrementAndGet();
                finished.countDown();
            };
            try {
                if (window) PixelCopy.request(activity.getWindow(), bitmap, listener, handler);
                else PixelCopy.request(snapshot.view, bitmap, listener, handler);
            } catch (RuntimeException | Error failure) {
                if (pending.compareAndSet(true, false)) original290NativePending.decrementAndGet();
                throw failure;
            }
            return null;
        });
        boolean completed = finished.await(3, TimeUnit.SECONDS);
        copy.put("callbackObserved", completed).put("callbackNanos", callbackAt.get())
                .put("result", status.get()).put("readbackNanos", now());
        // Never save the untouched sentinel bitmap as source evidence.
        check(completed && status.get() == PixelCopy.SUCCESS, "PixelCopy did not return SUCCESS");
        copy.put("image", savePng(root, window ? "window.png" : "surface.png", bitmap));
    }

    private static Object mappedField290(Object owner, String className, String name, String type) throws Exception {
        Class<?> declared = Class.forName(className, false, owner.getClass().getClassLoader());
        check(declared.isInstance(owner), "Mapped owner class differs");
        java.lang.reflect.Field field = declared.getDeclaredField(name);
        check(field.getType().getName().equals(type) && !Modifier.isStatic(field.getModifiers()), "Mapped field signature differs");
        field.setAccessible(true);
        return required(field.get(owner), "Mapped field is null");
    }

    private JSONObject mapped290(Activity activity, JSONObject result) throws Exception {
        check(Looper.myLooper() == Looper.getMainLooper(), "Mapped fields must be read on main");
        result.put("deviceNanos", now()).put("mappingId", ORIGINAL290_MAP_ID)
                .put("privateReadOnlyAccessUsed", true).put("listenerCallbackObserved", false);
        Object delegate = mappedField290(activity, "h22", "d", "l22");
        result.put("delegateUiDisplayed", mappedField290(delegate, "l22", "h", "boolean"))
                .put("delegateFirstFrameRendered", mappedField290(delegate, "l22", "i", "boolean"))
                .put("delegateAttached", mappedField290(delegate, "l22", "j", "boolean"));
        Object engine = mappedField290(delegate, "l22", "b", "r22");
        List<SurfaceView> surfaces = new ArrayList<>();
        visit(activity.getWindow().getDecorView(), surfaces);
        check(surfaces.size() == 1, "Require exactly one visible attached SurfaceView");
        SurfaceView view = surfaces.get(0);
        Object renderer = mappedField290(view, "h42", "g", "io.flutter.embedding.engine.renderer.e");
        View ancestor = view;
        Class<?> flutterView = Class.forName("p42", false, activity.getClassLoader());
        for (int depth = 0; depth < 80 && !flutterView.isInstance(ancestor); depth++) {
            check(ancestor.getParent() instanceof View, "Mapped FlutterView ancestor missing");
            ancestor = (View) ancestor.getParent();
        }
        check(flutterView.isInstance(ancestor) && ancestor.getWindowToken() == activity.getWindow().getDecorView().getWindowToken(),
                "Mapped FlutterView belongs to another window");
        check(mappedField290(ancestor, "p42", "t", "r22") == engine, "Mapped FlutterView engine differs");
        result.put("surfaceAvailable", mappedField290(view, "h42", "a", "boolean"))
                .put("surfacePaused", mappedField290(view, "h42", "d", "boolean"))
                .put("rendererDisplayingFlutterUi", mappedField290(renderer, "io.flutter.embedding.engine.renderer.e", "d", "boolean"))
                .put("flutterViewUiDisplayed", mappedField290(ancestor, "p42", "s", "boolean"));
        Object rendererSurface = mappedField290(renderer, "io.flutter.embedding.engine.renderer.e", "c", "android.view.Surface");
        result.put("rendererSurfaceSameJavaWrapperAsHolder", rendererSurface == view.getHolder().getSurface());
        return result
                .put("viewAlpha", view.getAlpha()).put("viewVisibility", view.getVisibility())
                .put("surfaceValid", view.getHolder().getSurface().isValid()).put("viewIdentity", System.identityHashCode(view))
                .put("rendererIdentity", System.identityHashCode(renderer)).put("surfaceIdentity", System.identityHashCode(rendererSurface))
                .put("readCompleted", true);
    }


    public void testOriginal265SurfaceWindowAndStartupMetadata() throws Exception {
        original265Probe = true;
        testCaptureLateSurfaceAndCompositor();
    }

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
        Bitmap windowBitmap = null;
        AtomicBoolean windowCopyPending = new AtomicBoolean();
        String stage = "identity";
        saveReceipt(root, receipt);
        try {
            receipt.put("originalApp", originalIdentity()).put("harness", harnessIdentity())
                    .put("execution", new JSONObject()
                            .put("source", sourceArgument("executorSourceSha"))
                            .put("run", numberArgument("executionRun"))
                            .put("attempt", numberArgument("executionAttempt")));
            if (original265Probe) receipt.put("startupMetadataBeforeLaunch", startupMetadata());
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
            String appRenderer = arguments.getString("appRenderer", "default");
            check("default".equals(appRenderer) || ("skia".equals(appRenderer)
                    && "34".equals(argument("originalAppVersionCode"))),
                    "GPU Skia is restricted to the fixed original257 Surface diagnosis");
            if ("skia".equals(appRenderer)) {
                launch.putExtra("enable-impeller", false);
                receipt.put("appRenderer", new JSONObject().put("requested", "skia")
                        .put("launcherEnableImpellerExtra", false)
                        .put("backendConfirmation", "backend-unconfirmed")
                        .put("observation", "Launcher request only; inspect original App engine logcat"));
            }
            receipt.put("launcherSubmittedNanos", now());
            Future<Activity> launchTask = worker.submit((Callable<Activity>) () -> instrumentation.startActivitySync(launch));
            activeTask = launchTask;
            Activity activity = launchTask.get(25, TimeUnit.SECONDS);
            activeTask = null;
            check(TARGET.equals(activity.getPackageName()) && LAUNCHER.equals(activity.getComponentName().getClassName()),
                    "Unexpected returned Launcher Activity");
            if (original290Probe) {
                stage = "original290-checkpoint";
                captureOriginal290(activity, root, receipt, worker, callbacks, surfaceEpoch, surfaceObserver, observedHolder);
                return;
            }
            receipt.put("launcherReturnedNanos", now()).put("lateFrameDelayMs", original265Probe ? 0 : 15_000);
            stage = "late-frame-wait";
            saveReceipt(root, receipt);
            if (original265Probe) {
                awaitFocusedWindowDraw(activity, receipt);
                receipt.put("startupMetadataAfterLaunch", startupMetadata());
            } else {
                long lateAt = SystemClock.elapsedRealtime() + 15_000;
                while (SystemClock.elapsedRealtime() < lateAt) {
                    Thread.sleep(Math.max(1L, Math.min(100L, lateAt - SystemClock.elapsedRealtime())));
                }
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
            if (original265Probe) {
                stage = "window-copy";
                Bitmap target = Bitmap.createBitmap(before.displayWidth, before.displayHeight, Bitmap.Config.ARGB_8888);
                windowBitmap = target;
                target.eraseColor(0xfff10df1);
                AtomicInteger status = new AtomicInteger(-1);
                AtomicLong callbackTime = new AtomicLong();
                CountDownLatch windowFinished = new CountDownLatch(1);
                JSONObject copy = new JSONObject().put("method", "PixelCopy.request(Window,Bitmap,listener,Handler)")
                        .put("submittedNanos", now()).put("destinationSentinelArgb", "fff10df1");
                receipt.put("windowCopy", copy);
                windowCopyPending.set(true);
                onMain(() -> { PixelCopy.request(activity.getWindow(), target, code -> {
                    status.set(code); callbackTime.set(now()); windowCopyPending.set(false); windowFinished.countDown();
                }, callbackHandler); return null; });
                boolean completed = windowFinished.await(3, TimeUnit.SECONDS);
                copy.put("callbackObserved", completed).put("callbackNanos", callbackTime.get())
                        .put("callbackReadbackNanos", now()).put("result", status.get());
                check(completed && status.get() == PixelCopy.SUCCESS, "Window PixelCopy did not succeed");
                Snapshot afterWindow = onMain(() -> snapshot(activity, surfaceEpoch.get()));
                checkSame(before, afterWindow);
                receipt.put("afterWindowCopy", afterWindow.json);
                copy.put("image", savePng(root, "window.png", target));
            }
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
            if (original265Probe) receipt.put("startupMetadataAfterCapture", startupMetadata())
                    .put("semanticSceneAfterCaptures", semanticScene());
            stage = "complete";
            receipt.put("captureCompleted", true).put("status", "captured");
        } catch (Throwable failure) {
            receipt.put("status", "failed").put("failureClass", failure.getClass().getName())
                    .put("failureCauseClass", failure.getCause() == null ? null : failure.getCause().getClass().getName());
            // Do not export arbitrary exception messages, tokens, or App data into this receipt.
            throw failure;
        } finally {
            boolean pending = activeTask != null && !activeTask.isDone()
                    || original290WorkerPending.get() || original290RetainedResources.get();
            boolean mainPending = mainWorkOutstanding.get() != 0 || mainWorkTeardownRequired.get();
            boolean nativeCopyPending = copyPending && pending || windowCopyPending.get() || original290NativePending.get() != 0;
            JSONObject surfaceCopy = receipt.optJSONObject("surfaceCopy");
            if (!original290Probe && surfaceCopy != null) surfaceCopy.put("startNanos", copyStart.get())
                    .put("requestReturnedNanos", copyReturn.get()).put("callbackNanos", copyCallback.get())
                    .put("result", copyStatus.get());
            JSONObject compositorCopy = receipt.optJSONObject("compositorCopy");
            if (!original290Probe && compositorCopy != null) compositorCopy.put("startNanos", compositorStart.get()).put("endNanos", compositorEnd.get());
            receipt.put("stage", stage).put("finishedElapsedRealtimeNanos", now())
                    .put("workerStillPending", pending).put("pixelCopyStillPending", nativeCopyPending)
                    .put("resourceCleanup", pending || nativeCopyPending || mainPending || coordinatorTeardownRequired.get()
                            ? "pending-runner-process-teardown" : "complete")
                    .put("uiAutomationTeardownOwner", "system Instrumentation/InstrumentationTestRunner");
            try {
                if (!nativeCopyPending) {
                    if (surfaceBitmap != null) surfaceBitmap.recycle();
                    if (windowBitmap != null) windowBitmap.recycle();
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

    private void awaitFocusedWindowDraw(Activity activity, JSONObject receipt) throws Exception {
        long deadline = now() + TimeUnit.SECONDS.toNanos(10);
        View decor = activity.getWindow().getDecorView();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicLong focusNanos = new AtomicLong();
        AtomicLong drawNanos = new AtomicLong();
        AtomicReference<Object> focusedToken = new AtomicReference<>();
        AtomicInteger unfocusedDraws = new AtomicInteger();
        AtomicReference<android.view.ViewTreeObserver> registeredTree = new AtomicReference<>();
        android.view.ViewTreeObserver.OnDrawListener draw = () -> {
            if (!activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus()
                    && decor.isAttachedToWindow() && decor.getWindowToken() != null) {
                drawNanos.compareAndSet(0, now());
                focusedToken.compareAndSet(null, decor.getWindowToken());
                ready.countDown();
            } else {
                unfocusedDraws.incrementAndGet();
            }
        };
        android.view.ViewTreeObserver.OnWindowFocusChangeListener focus = hasFocus -> {
            if (hasFocus && !activity.isFinishing() && !activity.isDestroyed() && ready.getCount() != 0) {
                focusNanos.compareAndSet(0, now());
                decor.postInvalidateOnAnimation();
            }
        };
        JSONObject observation = new JSONObject().put("budgetSeconds", 10).put("startedNanos", now())
                .put("activityIdentity", System.identityHashCode(activity)).put("decorIdentity", System.identityHashCode(decor))
                .put("testRequestsWindowDraw", true).put("isFlutterFrameProof", false);
        receipt.put("focusedWindowObservation", observation).put("windowDrawIsFlutterFrameProof", false);
        try {
            boolean initiallyFocused = onMain(() -> {
                android.view.ViewTreeObserver tree = decor.getViewTreeObserver();
                registeredTree.set(tree);
                tree.addOnWindowFocusChangeListener(focus);
                tree.addOnDrawListener(draw);
                boolean focused = activity.hasWindowFocus();
                if (focused) focus.onWindowFocusChanged(true);
                return focused;
            });
            observation.put("initialWindowFocus", initiallyFocused);
            boolean observed = ready.await(Math.max(0L, deadline - now()), TimeUnit.NANOSECONDS);
            JSONObject finalState = onMain(() -> new JSONObject()
                    .put("finishing", activity.isFinishing()).put("destroyed", activity.isDestroyed())
                    .put("windowFocused", activity.hasWindowFocus()).put("decorAttached", decor.isAttachedToWindow())
                    .put("hasWindowToken", decor.getWindowToken() != null)
                    .put("tokenMatchesFocusedDraw", decor.getWindowToken() == focusedToken.get()));
            observation.put("finishedNanos", now()).put("firstFocusNanos", focusNanos.get())
                    .put("focusedDrawNanos", drawNanos.get()).put("unfocusedDraws", unfocusedDraws.get())
                    .put("focusedDrawObserved", observed).put("finalState", finalState);
            receipt.put("windowDrawObserved", observed);
            check(observed, "Live focused target window did not draw within observation budget");
            check(finalState.getBoolean("tokenMatchesFocusedDraw"), "Window token changed after focused draw");
            observeFirstFlutterFrame(activity, receipt, deadline);
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                onMain(() -> {
                    android.view.ViewTreeObserver tree = registeredTree.get();
                    if (tree != null && tree.isAlive()) {
                        tree.removeOnDrawListener(draw);
                        tree.removeOnWindowFocusChangeListener(focus);
                    }
                    android.view.ViewTreeObserver current = decor.getViewTreeObserver();
                    if (current != tree && current.isAlive()) {
                        current.removeOnDrawListener(draw);
                        current.removeOnWindowFocusChangeListener(focus);
                    }
                    return null;
                });
                observation.put("listenersRemoved", true);
            } catch (Exception failure) {
                mainWorkTeardownRequired.set(true);
                observation.put("listenersRemoved", false);
                throw failure;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void observeFirstFlutterFrame(Activity activity, JSONObject receipt, long deadline) throws Exception {
        JSONObject observation = new JSONObject().put("status", "public-api-unavailable")
                .put("sharesFocusedWindowDeadline", true).put("privateAccessUsed", false)
                .put("listenerCallbackObserved", false).put("nativeFrameObserved", false);
        receipt.put("flutterFirstFrameObservation", observation);
        AtomicReference<Method> add = new AtomicReference<>();
        AtomicReference<Method> remove = new AtomicReference<>();
        AtomicReference<Class<?>> listenerType = new AtomicReference<>();
        onMain(() -> {
            try {
                ClassLoader loader = activity.getClassLoader();
                Class<?> type = Class.forName("io.flutter.embedding.android.FlutterView", false, loader);
                Class<?> listener = Class.forName("io.flutter.embedding.engine.renderer.FlutterUiDisplayListener", false, loader);
                if (!Modifier.isPublic(type.getModifiers()) || !Modifier.isPublic(listener.getModifiers())
                        || !listener.isInterface()) {
                    observation.put("unavailableReason", "non-public-types");
                    return null;
                }
                List<View> views = new ArrayList<>();
                findFlutterViews(activity.getWindow().getDecorView(), type, views, new int[1], 0);
                if (views.size() != 1) {
                    observation.put("matchingFlutterViewCount", views.size());
                    return null;
                }
                Method read = type.getMethod("hasRenderedFirstFrame");
                Method register = type.getMethod("addOnFirstFrameRenderedListener", listener);
                Method unregister = type.getMethod("removeOnFirstFrameRenderedListener", listener);
                if (read.getReturnType() != boolean.class || register.getReturnType() != void.class
                        || unregister.getReturnType() != void.class
                        || listener.getMethod("onFlutterUiDisplayed").getReturnType() != void.class
                        || listener.getMethod("onFlutterUiNoLongerDisplayed").getReturnType() != void.class) {
                    observation.put("unavailableReason", "public-signatures-differ");
                    return null;
                }
                observation.put("lookupHasRenderedFirstFrame", (Boolean) read.invoke(views.get(0)));
                firstFrameView = views.get(0);
                firstFrameRead = read;
                add.set(register); remove.set(unregister); listenerType.set(listener);
                observation.put("status", "public-api-available").put("viewIdentity", System.identityHashCode(firstFrameView));
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | SecurityException unavailable) {
                observation.put("unavailableClass", unavailable.getClass().getName());
            }
            return null;
        });
        if (firstFrameRead == null) return;
        CountDownLatch displayed = new CountDownLatch(1);
        AtomicLong callbackNanos = new AtomicLong();
        Object listener = Proxy.newProxyInstance(listenerType.get().getClassLoader(), new Class<?>[]{listenerType.get()},
                (proxy, method, values) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == values[0];
                        return "Original265FirstFrameObserver";
                    }
                    if ("onFlutterUiDisplayed".equals(method.getName())) {
                        callbackNanos.compareAndSet(0L, now());
                        displayed.countDown();
                    }
                    return null;
                });
        boolean registrationAttempted = false;
        try {
            registrationAttempted = true;
            boolean initial = onMain(() -> {
                add.get().invoke(firstFrameView, listener);
                return (Boolean) firstFrameRead.invoke(firstFrameView);
            });
            observation.put("initialHasRenderedFirstFrame", initial);
            if (!initial) displayed.await(Math.max(0L, deadline - now()), TimeUnit.NANOSECONDS);
            boolean after = onMain(() -> (Boolean) firstFrameRead.invoke(firstFrameView));
            observation.put("finalHasRenderedFirstFrame", after).put("callbackNanos", callbackNanos.get())
                    .put("listenerCallbackObserved", callbackNanos.get() != 0)
                    .put("nativeFrameObserved", after).put("finishedNanos", now())
                    .put("status", after ? "native-first-frame-observed" : "native-first-frame-not-observed-within-budget");
        } finally {
            boolean interrupted = Thread.interrupted();
            try {
                if (registrationAttempted) onMain(() -> { remove.get().invoke(firstFrameView, listener); return null; });
                observation.put("listenerRemoved", true);
            } catch (Exception failure) {
                mainWorkTeardownRequired.set(true);
                observation.put("listenerRemoved", false);
                throw failure;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void findFlutterViews(View view, Class<?> type, List<View> found, int[] count, int depth) {
        check(depth < 80 && ++count[0] <= 4000, "Flutter view observation exceeds traversal bound");
        if (type.isInstance(view)) found.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                findFlutterViews(group.getChildAt(index), type, found, count, depth + 1);
            }
        }
    }

    private JSONObject originalIdentity() throws Exception {
        if (original290Probe) {
            check(ORIGINAL290_APK.equals(argument("originalAppApkSha256"))
                    && "38".equals(argument("originalAppVersionCode"))
                    && ORIGINAL_SIGNER.equals(argument("originalAppSignerSha256")), "Unexpected original290 identity argument");
            JSONObject identity = installedIdentity(TARGET, ORIGINAL290_APK, ORIGINAL_SIGNER, 38L);
            check("0.2.0-beta.6".equals(identity.getString("versionName")), "Unexpected original290 versionName");
            return identity.put("source", "dbf758c8db04516780513453fa65609ee2e8e80f")
                    .put("tree", "8af4cc19bbeceb46810f68166aced2b42c792ac2")
                    .put("buildRun", "42236353882").put("buildAttempt", "1");
        }
        if (original265Probe) {
            String apk = "07b9482e3ba7227c4746b11ea55b6eac7c64fe51a1355276686b791fbb14a1cb";
            check(apk.equals(argument("originalAppApkSha256")) && "36".equals(argument("originalAppVersionCode"))
                    && ORIGINAL_SIGNER.equals(argument("originalAppSignerSha256")), "Unexpected original265 identity argument");
            JSONObject identity = installedIdentity(TARGET, apk, ORIGINAL_SIGNER, 36L);
            check("0.2.0-beta.4".equals(identity.getString("versionName")), "Unexpected original265 versionName");
            return identity.put("source", "4190ff45f8eedc6734fa666e2658b71abf147479")
                    .put("tree", "8ca6fb6bc19867e9302923ba9bea8d289972a8b6")
                    .put("buildRun", "42066278567").put("buildAttempt", "1");
        }
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

    private JSONObject startupMetadata() throws Exception {
        File root = new File(context.getApplicationInfo().dataDir).getAbsoluteFile();
        File canonical = root.getCanonicalFile();
        return new JSONObject().put("trustedDataRootAlias", !root.equals(canonical))
                .put("originalInput", inputMetadata(root)).put("canonicalRootInput", inputMetadata(canonical));
    }

    private JSONObject semanticScene() throws Exception {
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        boolean[] labels = new boolean[2];
        int[] count = new int[1];
        scanLabels(root, labels, count, 0);
        return new JSONObject().put("observedAfterPixelCopies", true).put("nodeCount", count[0])
                .put("activePackageIsTarget", root != null && TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()))
                .put("visibleAgree", labels[0]).put("visibleTools", labels[1]);
    }

    private static void scanLabels(android.view.accessibility.AccessibilityNodeInfo node,
                                   boolean[] labels, int[] count, int depth) {
        if (node == null) return;
        check(depth < 80 && ++count[0] <= 4000, "Accessibility observation exceeds traversal bound");
        if (node.isVisibleToUser() && TARGET.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
            String text = node.getText() == null ? "" : node.getText().toString();
            String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
            labels[0] |= "Agree".equals(text) || "Agree".equals(description);
            labels[1] |= "Tools".equals(text) || "Tools".equals(description);
        }
        for (int index = 0; index < node.getChildCount(); index++) scanLabels(node.getChild(index), labels, count, depth + 1);
    }

    private static JSONObject inputMetadata(File root) throws Exception {
        File parent = new File(root, "shared_prefs");
        File file = new File(parent, "FlutterSharedPreferences.xml");
        return new JSONObject().put("isFile", file.isFile())
                .put("canonicalEqualsAbsolute", file.getCanonicalFile().equals(file.getAbsoluteFile()))
                .put("leafSymlink", Files.isSymbolicLink(file.toPath()))
                .put("parentIsDirectory", parent.isDirectory()).put("parentSymlink", Files.isSymbolicLink(parent.toPath()));
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
        if (original265Probe && firstFrameRead != null) {
            check(firstFrameView.isAttachedToWindow() && firstFrameView.getWindowToken() == decor.getWindowToken(),
                    "Observed FlutterView no longer belongs to the captured window");
            View owner = view;
            for (int depth = 0; depth < 80 && owner != firstFrameView; depth++) {
                if (!(owner.getParent() instanceof View)) break;
                owner = (View) owner.getParent();
            }
            check(owner == firstFrameView, "Captured Surface no longer belongs to the observed FlutterView");
            json.put("flutterFirstFramePublicApiAvailable", true)
                    .put("flutterViewIdentity", System.identityHashCode(firstFrameView))
                    .put("flutterHasRenderedFirstFrame", (Boolean) firstFrameRead.invoke(firstFrameView));
        } else if (original265Probe) {
            json.put("flutterFirstFramePublicApiAvailable", false);
        }
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

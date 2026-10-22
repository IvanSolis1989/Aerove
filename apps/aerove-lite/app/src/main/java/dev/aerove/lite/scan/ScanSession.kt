package dev.aerove.lite.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import java.util.concurrent.Executors

internal enum class ScanFailure { Permission, Camera, Image, NoCode }

internal class ScanPreview(context: Context) : FrameLayout(context) {
    val preview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val held = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY }
    private var bitmap: Bitmap? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(held, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun freeze() {
        if (bitmap != null) return
        bitmap = runCatching { preview.bitmap }.getOrNull()
        held.setImageBitmap(bitmap)
        held.visibility = View.VISIBLE
    }

    fun reveal() {
        held.visibility = View.GONE
        held.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
    }
}

internal class ScanSession(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onClose: () -> Unit,
) : LifecycleOwner {
    private val appContext = context.applicationContext
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = ScanResultGate()
    private val previewUseCase = Preview.Builder().build()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
        ).build())
        .build()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var loading = false
    private var disposed = false
    private var leaving = false
    private var wanted = false
    private var permitted = false
    private var picking = false
    private var pendingAlbum: Result<List<String>>? = null
    private var albumGeneration = 0L
    private var view: ScanPreview? = null
    private val streamObserver = Observer<PreviewView.StreamState> { state ->
        if (running && state == PreviewView.StreamState.STREAMING) view?.reveal()
    }
    @Volatile private var running = false
    @Volatile private var frameGeneration = 0L
    var failure: ScanFailure? by mutableStateOf(null)
        private set
    var invalid: Boolean by mutableStateOf(false)
        private set
    var torch: Boolean by mutableStateOf(false)
        private set
    var hasTorch: Boolean by mutableStateOf(false)
        private set
    var decodingImage: Boolean by mutableStateOf(false)
        private set
    var cameraRunning: Boolean by mutableStateOf(false)
        private set
    private val clearInvalid = Runnable { invalid = false }

    init {
        registry.currentState = Lifecycle.State.CREATED
        val decoder = QrDecoder()
        analysis.setAnalyzer(executor) { image ->
            val generation = frameGeneration
            try {
                if (running && image.width.toLong() * image.height <= AlbumQrDecoder.MAX_PIXELS) {
                    val values = decoder.luminance(copyLuma(image), image.width, image.height)
                    if (values.isNotEmpty()) main.post {
                        if (running && generation == frameGeneration && !disposed) accept(values)
                    }
                }
            } catch (_: Exception) {
                // Camera shutdown can invalidate a frame already on the executor.
            } finally {
                image.close()
            }
        }
    }

    fun attach(preview: ScanPreview) {
        view?.preview?.previewStreamState?.removeObserver(streamObserver)
        view = preview
        previewUseCase.surfaceProvider = preview.preview.surfaceProvider
        preview.preview.previewStreamState.observeForever(streamObserver)
        reconcile()
    }

    fun update(active: Boolean, permitted: Boolean) {
        if (disposed || leaving) return
        wanted = active
        this.permitted = permitted
        if (!permitted) failure = ScanFailure.Permission
        else if (failure == ScanFailure.Permission) failure = null
        reconcile()
        if (active) deliverAlbum()
    }

    fun retry() {
        if (disposed || leaving) return
        pause()
        camera?.cameraInfo?.cameraState?.removeObservers(this)
        provider?.unbind(previewUseCase, analysis)
        camera = null
        failure = null
        reconcile()
    }

    fun beginAlbum(): Boolean {
        if (disposed || leaving || picking) return false
        picking = true
        albumGeneration++
        pause()
        return true
    }

    fun album(uri: Uri?) {
        if (disposed || leaving || !picking) return
        if (uri == null) {
            picking = false
            reconcile()
            return
        }
        decodingImage = true
        val generation = albumGeneration
        executor.execute {
            val result = runCatching { AlbumQrDecoder(appContext.contentResolver).decode(uri) }
            main.post {
                if (!disposed && !leaving && generation == albumGeneration) {
                    pendingAlbum = result
                    deliverAlbum()
                }
            }
        }
    }

    private fun deliverAlbum() {
        val pending = pendingAlbum ?: return
        if (!wanted || leaving || disposed) return
        pendingAlbum = null
        decodingImage = false
        picking = false
        pending.fold(
            onSuccess = { values ->
                if (values.isEmpty()) failure = ScanFailure.NoCode else accept(values)
            },
            onFailure = { failure = ScanFailure.Image },
        )
        reconcile()
    }

    fun dismissImageFailure() {
        if (failure == ScanFailure.Image || failure == ScanFailure.NoCode) {
            failure = if (permitted) null else ScanFailure.Permission
        }
        reconcile()
    }

    fun toggleTorch() {
        val current = camera ?: return
        if (!running || !hasTorch) return
        val next = !torch
        val result = current.cameraControl.enableTorch(next)
        result.addListener({
            if (!disposed && running && camera === current) {
                runCatching { result.get() }.onSuccess { torch = next }.onFailure { torch = false }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun close() {
        if (!disposed && !leaving && gate.close()) leave(null)
    }

    private fun accept(values: List<String?>) {
        if (leaving || disposed) return
        when (val match = gate.accept(values)) {
            ScanMatch.Ignored -> Unit
            ScanMatch.Invalid -> {
                invalid = true
                main.removeCallbacks(clearInvalid)
                main.postDelayed(clearInvalid, 2000)
            }
            is ScanMatch.Found -> leave(match.url)
        }
    }

    private fun leave(url: String?) {
        leaving = true
        albumGeneration++
        pause()
        if (url == null) onClose() else onResult(url)
    }

    private fun reconcile() {
        if (disposed) return
        val active = wanted && permitted && !leaving && !picking && failure == null && view != null
        if (!active) {
            pause()
            return
        }
        if (camera == null) {
            bind()
            return
        }
        running = true
        cameraRunning = true
        registry.currentState = Lifecycle.State.RESUMED
    }

    private fun bind() {
        if (loading) return
        loading = true
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            loading = false
            if (disposed || leaving) return@addListener
            try {
                provider = future.get()
                if (!wanted || !permitted || picking || failure != null || view == null) return@addListener
                camera = provider!!.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
                hasTorch = camera!!.cameraInfo.hasFlashUnit()
                camera!!.cameraInfo.cameraState.observe(this) { state ->
                    if (state.error != null && !disposed && !leaving) {
                        failure = ScanFailure.Camera
                        pause()
                    }
                }
                reconcile()
            } catch (_: Exception) {
                failure = ScanFailure.Camera
                pause()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun pause() {
        if (running) {
            running = false
            frameGeneration++
            view?.freeze()
            camera?.cameraControl?.enableTorch(false)
            registry.currentState = Lifecycle.State.CREATED
        }
        torch = false
        cameraRunning = false
    }

    fun dispose() {
        if (disposed) return
        pause()
        disposed = true
        albumGeneration++
        registry.currentState = Lifecycle.State.DESTROYED
        analysis.clearAnalyzer()
        provider?.unbind(previewUseCase, analysis)
        view?.preview?.previewStreamState?.removeObserver(streamObserver)
        view?.reveal()
        view = null
        main.removeCallbacks(clearInvalid)
        executor.shutdown()
    }

    private fun copyLuma(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val origin = buffer.position()
        return ByteArray(image.width * image.height).also { bytes ->
            for (y in 0 until image.height) for (x in 0 until image.width) {
                bytes[y * image.width + x] = buffer.get(origin + y * plane.rowStride + x * plane.pixelStride)
            }
        }
    }
}

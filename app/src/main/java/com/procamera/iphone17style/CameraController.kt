package com.procamera.iphone17style

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

class CameraController(private val context: Context, private val owner: LifecycleOwner,
    private val previewView: PreviewView) {
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var front = false
    private val executor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var analysis: ImageAnalysis? = null
    @Volatile private var generation = 0
    var previewParams: () -> ProcessingParams = { ProcessingParams() }
    var shouldAnalyze: () -> Boolean = { true }
    var onFrame: ((Bitmap) -> Unit)? = null
    var onPreviewFailure: ((String) -> Unit)? = null
    var hasAlternateCamera = false
        private set

    suspend fun start(switch: Boolean = false) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Разрешите доступ к камере или выберите фото из галереи"
        }
        val p = provider ?: ProcessCameraProvider.getInstance(context).awaitValue().also { provider = it }
        if (switch) front = !front
        val rearExists = p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        val frontExists = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        hasAlternateCamera = rearExists && frontExists
        if (!rearExists) front = true
        if (!frontExists) front = false
        check(rearExists || frontExists) { "Камера на этом устройстве не найдена" }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        stop()
        camera = null
        capture = null
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val newCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(previewView.display?.rotation ?: 0).build()
        val session = generation
        val mirrored = front
        val processor = LivePreviewProcessor()
        var lastFrame = 0L
        var failures = 0
        @Suppress("DEPRECATION")
        val newAnalysis = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
            .setTargetRotation(previewView.display?.rotation ?: 0)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        newAnalysis.setAnalyzer(analysisExecutor) { frame ->
            try {
                val now = System.nanoTime()
                if (session == generation && shouldAnalyze() && now - lastFrame >= 100_000_000L) {
                    lastFrame = now
                    val bitmap = processor.process(frame, previewParams(), mirrored)
                    executor.execute {
                        if (session == generation && shouldAnalyze()) onFrame?.invoke(bitmap)
                        else bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                failures++
                if (failures == 1 || failures % 30 == 0) executor.execute {
                    if (session == generation) onPreviewFailure?.invoke(
                        "Live-эффект недоступен; показано обычное превью. Полная обработка — после съёмки")
                }
            } finally { frame.close() }
        }
        analysis = newAnalysis
        previewProcessor = processor
        try {
            camera = p.bindToLifecycle(owner, selector, preview, newCapture, newAnalysis)
        } catch (error: IllegalArgumentException) {
            // Some cameras cannot provide three streams at once. Keep still capture usable.
            generation++
            newAnalysis.clearAnalyzer()
            analysis = null
            analysisExecutor.execute { processor.close() }
            previewProcessor = null
            p.unbindAll()
            camera = p.bindToLifecycle(owner, selector, preview, newCapture)
            onPreviewFailure?.invoke("Эта камера не поддерживает live-обработку вместе со съёмкой")
        }
        capture = newCapture
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focus(event.x, event.y)
                view.performClick()
            }
            true
        }
    }

    fun supportsBracket(): Boolean {
        val state = camera?.cameraInfo?.exposureState ?: return false
        val step = state.exposureCompensationStep.toFloat()
        return state.isExposureCompensationSupported && step > 0 &&
            state.exposureCompensationRange.lower * step <= -0.5f &&
            state.exposureCompensationRange.upper * step >= 0.5f
    }

    suspend fun takePhoto(bracket: Boolean): CaptureResult {
        val c = camera ?: error("Камера ещё не готова")
        val ic = capture ?: error("Камера ещё не готова")
        ic.targetRotation = previewView.display?.rotation ?: 0
        val useBracket = bracket && supportsBracket()
        val frames = mutableListOf<ExposureFrame>()
        val state = c.cameraInfo.exposureState
        val step = state.exposureCompensationStep.toFloat()
        val originalIndex = state.exposureCompensationIndex
        try {
            // Neutral is always first so partial HDR series can safely fall back to it.
            val indices = if (useBracket) listOf(0,
                (-1f / step).roundToInt().coerceIn(state.exposureCompensationRange.lower, -1),
                (1f / step).roundToInt().coerceIn(1, state.exposureCompensationRange.upper)) else listOf(originalIndex)
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                if (useBracket) {
                    c.cameraControl.setExposureCompensationIndex(index).awaitValue()
                    delay(250) // CameraX signals exposure application; allow settling before JPEG capture.
                }
                val file = File.createTempFile("capture_", ".jpg", context.cacheDir)
                try {
                    saveJpeg(ic, file)
                    val bitmap = PhotoFiles.decode(context, Uri.fromFile(file))
                    frames += ExposureFrame(bitmap, if (useBracket) index * step else 0f)
                } finally { file.delete() }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (frames.isEmpty()) throw error
            val base = frames.first().bitmap
            return CaptureResult(base, base, "HDR не завершён: использован первый кадр")
        } finally {
            if (useBracket) withContext(NonCancellable) {
                withTimeoutOrNull(2000) {
                    runCatching { c.cameraControl.setExposureCompensationIndex(originalIndex).awaitValue() }
                }
            }
        }
        return if (useBracket) ExposureFusion.merge(frames) else {
            val bitmap = frames.first().bitmap
            CaptureResult(bitmap, bitmap, if (bracket) "Эта камера не поддерживает HDR-серию" else null)
        }
    }

    private suspend fun saveJpeg(ic: ImageCapture, file: File) = suspendCancellableCoroutine<Unit> { continuation ->
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    if (continuation.isActive) continuation.resume(Unit) else file.delete()
                }
                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
            })
    }

    private var previewProcessor: LivePreviewProcessor? = null

    fun focus(x: Float, y: Float) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, TimeUnit.SECONDS).build())
    }

    fun stop() {
        generation++
        analysis?.clearAnalyzer()
        analysis = null
        // Closing is queued behind any running inference; no detector-close race.
        previewProcessor?.let { processor -> analysisExecutor.execute { processor.close() } }
        previewProcessor = null
        provider?.unbindAll(); camera = null; capture = null
    }

    fun close() { stop(); analysisExecutor.shutdown() }

    private suspend fun <T> ListenableFuture<T>.awaitValue(): T = suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) {
                try { continuation.resume(get()) }
                catch (error: Exception) { continuation.resumeWithException(error.cause ?: error) }
            }
        }, executor)
        continuation.invokeOnCancellation { cancel(false) }
    }
}

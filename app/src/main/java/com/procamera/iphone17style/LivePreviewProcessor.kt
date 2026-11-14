package com.procamera.iphone17style

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/** Worker-thread preview only. Full-quality stills use ApplePhotonicEngine. */
class LivePreviewProcessor : Closeable {
    private var segmenter: Segmenter? = null

    fun process(frame: ImageProxy, params: ProcessingParams, mirror: Boolean): Bitmap {
        val crop = frame.cropRect
        val factor = max(1, (max(crop.width(), crop.height()) + 479) / 480)
        val w = max(1, crop.width() / factor)
        val h = max(1, crop.height() / factor)
        val planes = frame.planes
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = crop.left + x * factor
            val sy = crop.top + y * factor
            // Respect both row stride and chroma pixel stride (planar and interleaved devices).
            val yy = planes[0].buffer.get(sy * planes[0].rowStride + sx * planes[0].pixelStride).toInt() and 255
            val u = planes[1].buffer.get((sy / 2) * planes[1].rowStride + (sx / 2) * planes[1].pixelStride).toInt() and 255
            val v = planes[2].buffer.get((sy / 2) * planes[2].rowStride + (sx / 2) * planes[2].pixelStride).toInt() and 255
            pixels[y * w + x] = PixelMath.fromYuv(yy, u, v)
        }
        val lum = FloatArray(pixels.size) { PixelMath.luma(pixels[it]) }
        val local = PixelMath.boxBlur(lum, w, h, max(w, h) / 32)
        val fine = PixelMath.boxBlur(lum, w, h, 1)
        for (i in pixels.indices) pixels[i] = PixelMath.enhance(pixels[i], local[i], fine[i],
            params.smartHdr, params.deepFusion, params.photonicColor)
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            postRotate(frame.imageInfo.rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        var output = Bitmap.createBitmap(raw, 0, 0, w, h, matrix, true)
        if (output !== raw) raw.recycle()
        if (params.portraitBokeh > 0.01f) output = applyPortrait(output, params.portraitBokeh)
        return output
    }

    private fun applyPortrait(bitmap: Bitmap, amount: Float): Bitmap {
        val detector = segmenter ?: Segmentation.getClient(SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).enableRawSizeMask().build())
            .also { segmenter = it }
        // Only this dedicated analyzer thread waits; the UI/capture threads remain free.
        val mask = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
        val data = FloatArray(mask.width * mask.height)
        val buffer = mask.buffer
        for (i in data.indices) data[i] = buffer.float
        if (data.count { it > 0.8f } < data.size / 100) return bitmap
        val w = bitmap.width; val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val channels = Array(3) { channel -> FloatArray(src.size) { i ->
            (src[i] ushr (16 - channel * 8) and 255) / 255f
        } }
        val radius = max(2, (2 + amount * 8).toInt())
        val blurred = channels.map { PixelMath.boxBlur(it, w, h, radius) }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val confidence = data[min(mask.height - 1, y * mask.height / h) * mask.width +
                min(mask.width - 1, x * mask.width / w)]
            val a = PixelMath.smoothStep(0.15f, 0.85f, confidence)
            src[i] = PixelMath.rgb(channels[0][i] * a + blurred[0][i] * (1f - a),
                channels[1][i] * a + blurred[1][i] * (1f - a),
                channels[2][i] * a + blurred[2][i] * (1f - a))
        }
        val output = Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        return output
    }

    override fun close() { segmenter?.close(); segmenter = null }
}

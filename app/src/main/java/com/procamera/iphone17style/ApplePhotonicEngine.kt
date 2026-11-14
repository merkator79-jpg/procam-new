package com.procamera.iphone17style

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class ProcessingParams(
    val smartHdr: Float = 0.55f,
    val deepFusion: Float = 0.35f,
    val photonicColor: Float = 0.4f,
    val portraitBokeh: Float = 0f
)

data class ProcessedPhoto(val bitmap: Bitmap, val notice: String? = null)

/** Independent photo effects. Not Apple's proprietary Photonic Engine or Deep Fusion. */
object ApplePhotonicEngine {
    suspend fun processImage(source: Bitmap, params: ProcessingParams): ProcessedPhoto =
        withContext(Dispatchers.Default) {
            require(listOf(params.smartHdr, params.deepFusion, params.photonicColor,
                params.portraitBokeh).all { it.isFinite() && it in 0f..1f })
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            val lum = FloatArray(pixels.size) { PixelMath.luma(pixels[it]) }
            currentCoroutineContext().ensureActive()
            val local = PixelMath.boxBlur(lum, w, h, max(w, h) / 32)
            val fine = PixelMath.boxBlur(lum, w, h, 1)
            for (y in 0 until h) {
                currentCoroutineContext().ensureActive()
                for (x in 0 until w) {
                    val i = y * w + x
                    pixels[i] = PixelMath.enhance(pixels[i], local[i], fine[i],
                        params.smartHdr, params.deepFusion, params.photonicColor)
                }
            }
            val enhanced = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            if (params.portraitBokeh < 0.01f) return@withContext ProcessedPhoto(enhanced)
            portrait(source, enhanced, pixels, params.portraitBokeh)
        }

    private suspend fun portrait(source: Bitmap, enhanced: Bitmap, sharp: IntArray,
        amount: Float): ProcessedPhoto {
        // Bundled model; no model download, account or cloud photo upload.
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        val segmenter = Segmentation.getClient(options)
        try {
            val mask = segmenter.process(InputImage.fromBitmap(source, 0)).await()
            currentCoroutineContext().ensureActive()
            val mw = mask.width
            val mh = mask.height
            val confidence = FloatArray(mw * mh)
            val buffer = mask.buffer
            for (i in confidence.indices) confidence[i] = buffer.float
            val personPixels = confidence.count { it > 0.8f }
            if (personPixels < confidence.size / 100) {
                return ProcessedPhoto(enhanced, "Человек не найден: размытие не применено")
            }
            val w = enhanced.width
            val h = enhanced.height
            // Weighted background blur reduces subject-colour halos around hair/shoulders.
            val smallW = min(640, w)
            val smallH = max(1, (h.toFloat() * smallW / w).toInt())
            val small = Bitmap.createScaledBitmap(enhanced, smallW, smallH, true)
            val p = IntArray(smallW * smallH)
            small.getPixels(p, 0, smallW, 0, 0, smallW, smallH)
            if (small !== enhanced) small.recycle()
            val weights = FloatArray(p.size)
            val rr = FloatArray(p.size)
            val gg = FloatArray(p.size)
            val bb = FloatArray(p.size)
            for (y in 0 until smallH) for (x in 0 until smallW) {
                val i = y * smallW + x
                val a = sampleMask(confidence, mw, mh, x, y, smallW, smallH)
                val weight = (1f - PixelMath.smoothStep(0.15f, 0.8f, a)).coerceAtLeast(0.001f)
                weights[i] = weight
                rr[i] = (p[i] ushr 16 and 255) / 255f * weight
                gg[i] = (p[i] ushr 8 and 255) / 255f * weight
                bb[i] = (p[i] and 255) / 255f * weight
            }
            val radius = max(2, (5 + amount * 16).toInt())
            val blurredWeight = PixelMath.boxBlur(weights, smallW, smallH, radius)
            val br = PixelMath.boxBlur(rr, smallW, smallH, radius)
            val bg = PixelMath.boxBlur(gg, smallW, smallH, radius)
            val bl = PixelMath.boxBlur(bb, smallW, smallH, radius)
            for (i in p.indices) p[i] = PixelMath.rgb(br[i] / blurredWeight[i],
                bg[i] / blurredWeight[i], bl[i] / blurredWeight[i])
            val tinyBlur = Bitmap.createBitmap(p, smallW, smallH, Bitmap.Config.ARGB_8888)
            val fullBlur = Bitmap.createScaledBitmap(tinyBlur, w, h, true)
            val background = IntArray(sharp.size)
            fullBlur.getPixels(background, 0, w, 0, 0, w, h)
            if (fullBlur !== tinyBlur) fullBlur.recycle()
            tinyBlur.recycle()
            for (y in 0 until h) {
                currentCoroutineContext().ensureActive()
                for (x in 0 until w) {
                    val i = y * w + x
                    val a = PixelMath.smoothStep(0.15f, 0.85f,
                        sampleMask(confidence, mw, mh, x, y, w, h))
                    val back = background[i]
                    val front = sharp[i]
                    fun mix(shift: Int): Float =
                        (((front ushr shift and 255) * a + (back ushr shift and 255) * (1f - a)) / 255f)
                    sharp[i] = PixelMath.rgb(mix(16), mix(8), mix(0))
                }
            }
            return ProcessedPhoto(Bitmap.createBitmap(sharp, w, h, Bitmap.Config.ARGB_8888))
        } finally {
            // ML task itself is not cancelled by await; close is handled by the SDK.
            segmenter.close()
        }
    }

    private fun sampleMask(mask: FloatArray, mw: Int, mh: Int,
        x: Int, y: Int, w: Int, h: Int): Float {
        val fx = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, (mw - 1).toFloat())
        val fy = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, (mh - 1).toFloat())
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = min(x0 + 1, mw - 1); val y1 = min(y0 + 1, mh - 1)
        val dx = fx - x0; val dy = fy - y0
        return (mask[y0 * mw + x0] * (1 - dx) + mask[y0 * mw + x1] * dx) * (1 - dy) +
            (mask[y1 * mw + x0] * (1 - dx) + mask[y1 * mw + x1] * dx) * dy
    }
}

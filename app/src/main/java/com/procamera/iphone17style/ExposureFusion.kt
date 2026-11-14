package com.procamera.iphone17style

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

data class ExposureFrame(val bitmap: Bitmap, val ev: Float)
data class CaptureResult(val original: Bitmap, val input: Bitmap, val notice: String? = null)

object ExposureFusion {
    suspend fun merge(frames: List<ExposureFrame>): CaptureResult = withContext(Dispatchers.Default) {
        val neutral = frames.minBy { abs(it.ev) }.bitmap
        if (frames.size != 3) return@withContext CaptureResult(neutral, neutral,
            "HDR-серия недоступна: использован один кадр")
        val w = neutral.width; val h = neutral.height
        if (frames.any { it.bitmap.width != w || it.bitmap.height != h }) {
            return@withContext CaptureResult(neutral, neutral, "HDR: размеры кадров различаются")
        }
        val arrays = frames.map { frame -> IntArray(w * h).also {
            frame.bitmap.getPixels(it, 0, w, 0, 0, w, h)
        } }
        val neutralIndex = frames.indices.minBy { abs(frames[it].ev) }
        val reference = arrays[neutralIndex]
        // Conservative gate: do not blend shifted/moving scenes. No optical flow claims.
        var moving = 0; var samples = 0
        for (y in 8 until h - 8 step 12) for (x in 8 until w - 8 step 12) {
            val i = y * w + x
            val base = PixelMath.linear(PixelMath.luma(reference[i]))
            if (base in 0.03f..0.65f) {
                samples++
                if (arrays.indices.any { j ->
                    val c = arrays[j][i]
                    val l = PixelMath.luma(c)
                    l in 0.08f..0.92f && abs(PixelMath.linear(l) / 2f.pow(frames[j].ev) - base) > 0.13f
                }) moving++
            }
        }
        if (samples == 0 || moving.toFloat() / samples > 0.15f) {
            return@withContext CaptureResult(neutral, neutral,
                "HDR: обнаружено движение или сложная сцена — сохранён средний кадр")
        }
        val out = IntArray(w * h)
        val gains = frames.map { 2f.pow(it.ev) }
        for (y in 0 until h) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until w) {
                val i = y * w + x
                val baseL = PixelMath.linear(PixelMath.luma(reference[i]))
                var r = 0f; var g = 0f; var b = 0f; var total = 0f
                for (j in arrays.indices) {
                    val c = arrays[j][i]
                    val l = PixelMath.luma(c)
                    val corrected = PixelMath.linear(l) / gains[j]
                    // Per-pixel deghosting: trust reference where other frames disagree.
                    if (j != neutralIndex && l in 0.08f..0.92f && baseL in 0.03f..0.65f &&
                        abs(corrected - baseL) > 0.13f) continue
                    val weight = PixelMath.exposureWeight(l)
                    r += PixelMath.linear((c ushr 16 and 255) / 255f) / gains[j] * weight
                    g += PixelMath.linear((c ushr 8 and 255) / 255f) / gains[j] * weight
                    b += PixelMath.linear((c and 255) / 255f) / gains[j] * weight
                    total += weight
                }
                r /= total; g /= total; b /= total
                val peak = max(r, max(g, b))
                val shoulder = if (peak > 0.7f) (0.7f + 0.3f * (1f -
                    kotlin.math.exp(-(peak - 0.7f) / 0.3f))) / peak else 1f
                out[i] = PixelMath.rgb(PixelMath.encoded(r * shoulder),
                    PixelMath.encoded(g * shoulder), PixelMath.encoded(b * shoulder))
            }
        }
        CaptureResult(neutral, Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888),
            "HDR · 3 экспозиции. Лучший результат — неподвижная сцена")
    }
}

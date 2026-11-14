package com.procamera.iphone17style

import android.graphics.Bitmap
import android.graphics.Color
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class ProcessingParams(
    val smartHdr: Float = 0.75f,       // 0..1 Local tone mapping & shadow lift
    val deepFusion: Float = 0.65f,     // 0..1 Micro-contrast & texture sharpness
    val photonicColor: Float = 0.70f,  // 0..1 Apple TrueTone warmth + Skin protection
    val portraitBokeh: Float = 0.0f    // 0..1 AI Subject segmentation background blur
)

object ApplePhotonicEngine {

    /**
     * Full iPhone 17 Pro Computational Photography Pipeline
     */
    suspend fun processImage(
        context: Context,
        source: Bitmap,
        params: ProcessingParams
    ): Bitmap = withContext(Dispatchers.Default) {
        // Scale down slightly if huge to keep fast mobile memory performance while maintaining high res
        val workingBitmap = scaleIfNeeded(source, 2560)
        val width = workingBitmap.width
        val height = workingBitmap.height
        val totalPixels = width * height

        val pixels = IntArray(totalPixels)
        workingBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Compute Luminance Map for Smart HDR & Deep Fusion
        val lum = FloatArray(totalPixels)
        for (i in 0 until totalPixels) {
            val c = pixels[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            lum[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        // Fast integral image for local luminance (Smart HDR 5 Local Tone Mapping)
        val localLum = computeBoxBlurFloat(lum, width, height, max(width, height) / 24)
        // Fine blur for Deep Fusion Unsharp Masking (micro-texture)
        val fineLum = computeBoxBlurFloat(lum, width, height, 2)

        val outPixels = IntArray(totalPixels)

        for (i in 0 until totalPixels) {
            val c = pixels[i]
            var r = (c shr 16 and 0xFF) / 255f
            var g = (c shr 8 and 0xFF) / 255f
            var b = (c and 0xFF) / 255f

            val y = lum[i]
            val yLocal = localLum[i]

            // --- STAGE 1: SMART HDR 5 (Shadow lift + Highlight roll-off + Local contrast) ---
            var targetY = y
            if (params.smartHdr > 0.01f) {
                // Lift deep shadows softly
                val shadowLift = (1f - yLocal).pow(2.2f) * 0.38f * params.smartHdr
                // Compress highlights (filmic roll-off so sky never blows out)
                val highlightCompress = yLocal.pow(2.5f) * 0.18f * params.smartHdr
                // Local contrast preservation
                val localDetail = (y - yLocal) * (1.0f + 0.45f * params.smartHdr)
                targetY = (yLocal + shadowLift - highlightCompress + localDetail).coerceIn(0f, 1f)
                
                // Filmic S-curve punch
                val sCurve = targetY * targetY * (3f - 2f * targetY)
                targetY = targetY * (1f - 0.25f * params.smartHdr) + sCurve * (0.25f * params.smartHdr)
            }

            // --- STAGE 2: DEEP FUSION (Pixel-level micro-texture & noise suppression) ---
            if (params.deepFusion > 0.01f) {
                val microDetail = y - fineLum[i]
                // Threshold small noise, boost real textures
                val absDetail = kotlin.math.abs(microDetail)
                if (absDetail > 0.008f) {
                    targetY = (targetY + microDetail * (1.15f * params.deepFusion)).coerceIn(0f, 1f)
                }
            }

            // Re-apply luminance ratio to RGB channels
            val ratio = if (y > 0.001f) targetY / y else 1f
            r *= ratio
            g *= ratio
            b *= ratio

            // --- STAGE 3: PHOTONIC ENGINE COLOR & SKIN TONE PROTECTION ---
            if (params.photonicColor > 0.01f) {
                val p = params.photonicColor
                // TrueTone subtle golden warmth
                r *= (1f + 0.045f * p)
                g *= (1f + 0.015f * p)
                b *= (1f - 0.035f * p)

                // Detect skin tone proximity (warm orange-red hue, moderate saturation)
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val sat = if (maxC > 0f) (maxC - minC) / maxC else 0f
                val isSkin = (r > g && g > b && (r - b) > 0.05f && (r - b) < 0.45f && sat < 0.65f)

                // Vibrance boost (boosts sky/foliage strongly, protects skin tones)
                val gray = 0.299f * r + 0.587f * g + 0.114f * b
                val vibranceStrength = if (isSkin) 0.12f * p else (0.36f * (1f - sat * 0.5f)) * p
                r = gray + (r - gray) * (1f + vibranceStrength)
                g = gray + (g - gray) * (1f + vibranceStrength)
                b = gray + (b - gray) * (1f + vibranceStrength)
            }

            val finalR = (r.coerceIn(0f, 1f) * 255f).toInt()
            val finalG = (g.coerceIn(0f, 1f) * 255f).toInt()
            val finalB = (b.coerceIn(0f, 1f) * 255f).toInt()

            outPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        val enhancedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        enhancedBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)

        // --- STAGE 4: NEXT-GEN PORTRAIT BOKEH (ML Kit Subject Segmentation) ---
        if (params.portraitBokeh > 0.05f) {
            return@withContext applyPortraitBokeh(context, enhancedBitmap, params.portraitBokeh)
        }

        return@withContext enhancedBitmap
    }

    private suspend fun applyPortraitBokeh(
        context: Context,
        bitmap: Bitmap,
        strength: Float
    ): Bitmap {
        return try {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
            val segmenter = SubjectSegmentation.getClient(options)
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()
            val mask = result.foregroundConfidenceMask ?: return bitmap

            val width = bitmap.width
            val height = bitmap.height
            val total = width * height

            // Create creamy optical blur background
            val blurRadius = (24f * strength).coerceIn(1f, 25f)
            val blurredBitmap = blurBitmap(context, bitmap, blurRadius)

            val fgPixels = IntArray(total)
            val bgPixels = IntArray(total)
            val outPixels = IntArray(total)

            bitmap.getPixels(fgPixels, 0, width, 0, 0, width, height)
            blurredBitmap.getPixels(bgPixels, 0, width, 0, 0, width, height)

            mask.rewind()
            for (i in 0 until total) {
                val conf = mask.float.coerceIn(0f, 1f)
                // Smooth edge transition
                val alpha = smoothStep(0.25f, 0.75f, conf)
                val fg = fgPixels[i]
                val bg = bgPixels[i]

                val r = ((fg shr 16 and 0xFF) * alpha + (bg shr 16 and 0xFF) * (1f - alpha)).toInt()
                val g = ((fg shr 8 and 0xFF) * alpha + (bg shr 8 and 0xFF) * (1f - alpha)).toInt()
                val b = ((fg and 0xFF) * alpha + (bg and 0xFF) * (1f - alpha)).toInt()

                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            val finalPortrait = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            finalPortrait.setPixels(outPixels, 0, width, 0, 0, width, height)
            finalPortrait
        } catch (e: Exception) {
            bitmap
        }
    }

    @Suppress("DEPRECATION")
    private fun blurBitmap(context: Context, source: Bitmap, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val rs = RenderScript.create(context)
        val input = Allocation.createFromBitmap(rs, source)
        val outAlloc = Allocation.createFromBitmap(rs, output)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius)
        script.setInput(input)
        script.forEach(outAlloc)
        outAlloc.copyTo(output)
        rs.destroy()
        return output
    }

    private fun computeBoxBlurFloat(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = max(1, radius)
        val temp = FloatArray(w * h)
        val dst = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            var sum = 0f
            var count = 0
            for (x in 0..min(r, w - 1)) {
                sum += src[rowOffset + x]
                count++
            }
            for (x in 0 until w) {
                temp[rowOffset + x] = sum / count
                val next = x + r + 1
                if (next < w) {
                    sum += src[rowOffset + next]
                    count++
                }
                val prev = x - r
                if (prev >= 0) {
                    sum -= src[rowOffset + prev]
                    count--
                }
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (y in 0..min(r, h - 1)) {
                sum += temp[y * w + x]
                count++
            }
            for (y in 0 until h) {
                dst[y * w + x] = sum / count
                val next = y + r + 1
                if (next < h) {
                    sum += temp[next * w + x]
                    count++
                }
                val prev = y - r
                if (prev >= 0) {
                    sum -= temp[prev * w + x]
                    count--
                }
            }
        }
        return dst
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun scaleIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = w.toFloat() / h.toFloat()
        val newW: Int
        val newH: Int
        if (w > h) {
            newW = maxDim
            newH = (maxDim / ratio).toInt()
        } else {
            newH = maxDim
            newW = (maxDim * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }
}

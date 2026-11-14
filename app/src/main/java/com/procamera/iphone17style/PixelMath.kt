package com.procamera.iphone17style

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Pure Kotlin numerical core; independent of Android for JVM regression tests. */
object PixelMath {
    /** Limited-range BT.601 YUV preview conversion, clamped to opaque sRGB bytes. */
    fun fromYuv(y: Int, u: Int, v: Int): Int {
        val c = max(0, y - 16)
        val d = u - 128; val e = v - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (255 shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun luma(c: Int): Float = (0.2126f * (c ushr 16 and 255) +
        0.7152f * (c ushr 8 and 255) + 0.0722f * (c and 255)) / 255f

    fun rgb(r: Float, g: Float, b: Float): Int {
        fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (255 shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    fun smoothStep(low: Float, high: Float, value: Float): Float {
        require(high > low)
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // Sliding windows: O(w*h), bounded memory, correct denominators at edges.
    fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        require(w > 0 && h > 0 && src.size == w * h)
        require(radius >= 0)
        if (radius == 0) return src.copyOf()
        val r = radius.coerceAtMost(max(w, h))
        val tmp = FloatArray(src.size)
        val dst = FloatArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0.0
            for (x in 0..min(r, w - 1)) sum += src[row + x]
            for (x in 0 until w) {
                tmp[row + x] = (sum / (min(w - 1, x + r) - max(0, x - r) + 1)).toFloat()
                if (x - r >= 0) sum -= src[row + x - r]
                if (x + r + 1 < w) sum += src[row + x + r + 1]
            }
        }
        for (x in 0 until w) {
            var sum = 0.0
            for (y in 0..min(r, h - 1)) sum += tmp[y * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (sum / (min(h - 1, y + r) - max(0, y - r) + 1)).toFloat()
                if (y - r >= 0) sum -= tmp[(y - r) * w + x]
                if (y + r + 1 < h) sum += tmp[(y + r + 1) * w + x]
            }
        }
        return dst
    }

    fun skinConfidence(r: Float, g: Float, b: Float): Float {
        val cb = -0.168736f * r - 0.331264f * g + 0.5f * b + 0.5f
        val cr = 0.5f * r - 0.418688f * g - 0.081312f * b + 0.5f
        val chroma = smoothStep(0.28f, 0.33f, cb) * (1f - smoothStep(0.48f, 0.54f, cb)) *
            smoothStep(0.51f, 0.55f, cr) * (1f - smoothStep(0.70f, 0.77f, cr))
        return chroma * smoothStep(0.03f, 0.15f, max(r, max(g, b)))
    }

    fun enhance(c: Int, local: Float, fine: Float, hdr: Float, detail: Float, color: Float): Int {
        val r = (c ushr 16 and 255) / 255f
        val g = (c ushr 8 and 255) / 255f
        val b = (c and 255) / 255f
        val y = luma(c)
        val skin = skinConfidence(r, g, b)
        val lift = 0.19f * hdr * (1f - local).pow(2f) * smoothStep(0f, 0.06f, y)
        val rolloff = 0.10f * hdr * local.pow(3f)
        val micro = y - fine
        val texture = if (abs(micro) > 0.01f) micro * detail * (1f - 0.65f * skin) else 0f
        val target = (y + lift - rolloff + texture).coerceIn(0f, 1f)
        val ratio = if (y > 0.0001f) target / y else 1f
        val vibrance = 1f + 0.14f * color * (1f - 0.85f * skin)
        val warmth = color * (1f - 0.8f * skin)
        return rgb(
            target + (r * ratio - target) * vibrance + 0.012f * warmth,
            target + (g * ratio - target) * vibrance + 0.003f * warmth,
            target + (b * ratio - target) * vibrance - 0.009f * warmth
        )
    }

    fun exposureWeight(y: Float): Float = max(0.001f, 1f - abs(2f * y - 1f)).pow(2f)
    fun linear(value: Float): Float = value.coerceIn(0f, 1f).pow(2.2f)
    fun encoded(value: Float): Float = max(0f, value).pow(1f / 2.2f)
}

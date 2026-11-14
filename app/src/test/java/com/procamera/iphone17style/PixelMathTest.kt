package com.procamera.iphone17style

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PixelMathTest {
    @Test fun yuvPreviewMapsNeutralBlackWhiteAndClipsChroma() {
        assertEquals(0xFF000000.toInt(), PixelMath.fromYuv(16, 128, 128))
        assertEquals(0xFFFFFFFF.toInt(), PixelMath.fromYuv(235, 128, 128))
        for (y in listOf(0, 16, 128, 235, 255)) for (u in listOf(0, 128, 255))
            for (v in listOf(0, 128, 255)) assertEquals(255, PixelMath.fromYuv(y, u, v) ushr 24)
    }

    @Test fun blurPreservesConstantIncludingEdgesAndLargeRadius() {
        for (radius in listOf(0, 1, 2, 100)) {
            val out = PixelMath.boxBlur(FloatArray(35) { 0.37f }, 7, 5, radius)
            out.forEach { assertEquals(0.37f, it, 0.00001f) }
        }
    }

    @Test fun blurMatchesNaiveReferenceOnNonSquareInput() {
        val w = 7; val h = 4
        val input = FloatArray(w * h) { ((it * 17) % 31).toFloat() }
        for (radius in listOf(0, 1, 3, 9)) {
            val fast = PixelMath.boxBlur(input, w, h, radius)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f; var count = 0
                for (yy in maxOf(0, y - radius)..minOf(h - 1, y + radius))
                    for (xx in maxOf(0, x - radius)..minOf(w - 1, x + radius)) {
                        sum += input[yy * w + xx]; count++
                    }
                assertEquals(sum / count, fast[y * w + x], 0.0001f)
            }
        }
    }

    @Test fun zeroSettingsAreIdentityForEveryGrayscaleByte() {
        for (i in 0..255) {
            val c = (255 shl 24) or (i shl 16) or (i shl 8) or i
            val y = PixelMath.luma(c)
            assertEquals(c, PixelMath.enhance(c, y, y, 0f, 0f, 0f))
        }
    }

    @Test fun zeroSettingsPreserveArbitraryRgb() {
        val colors = listOf(0xFF1244AA.toInt(), 0xFFFFAA83.toInt(), 0xFF03FA14.toInt())
        colors.forEach { assertEquals(it, PixelMath.enhance(it, 0.5f, 0.5f, 0f, 0f, 0f)) }
    }

    @Test fun skinConfidenceDistinguishesWarmSkinFromBlue() {
        assertTrue(PixelMath.skinConfidence(0.75f, 0.5f, 0.38f) > 0.6f)
        assertTrue(PixelMath.skinConfidence(0.1f, 0.2f, 0.9f) < 0.1f)
    }

    @Test fun exposureMathNeverHasZeroWeightAndGammaRoundTrips() {
        for (i in 0..255) {
            val v = i / 255f
            assertTrue(PixelMath.exposureWeight(v) > 0f)
            assertTrue(abs(PixelMath.encoded(PixelMath.linear(v)) - v) < 0.00001f)
        }
    }

    @Test fun blackAndWhiteDoNotOverflowAndOutputIsOpaque() {
        for (c in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt())) {
            val out = PixelMath.enhance(c, PixelMath.luma(c), PixelMath.luma(c), 1f, 1f, 1f)
            assertEquals(255, out ushr 24)
        }
        assertEquals(0xFF00FF80.toInt(), PixelMath.rgb(-2f, 4f, 0.5f))
    }
}

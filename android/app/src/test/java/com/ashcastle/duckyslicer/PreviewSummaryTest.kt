package com.ashcastle.duckyslicer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSummaryTest {
    @Test
    fun sliceResultKeepsTimeMassAndLengthWithoutReadingGcode() {
        val summary = outcome(seconds = 5_430f, millimeters = 4_250f, grams = 12.6f).previewSummary()

        assertEquals(1, summary.duration.hours)
        assertEquals(31, summary.duration.minutes)
        assertFalse(summary.duration.underOneMinute)
        assertEquals(4.25f, summary.filamentMeters, 0.001f)
        assertEquals(12.6f, summary.filamentGrams, 0.001f)
    }

    @Test
    fun subMinuteEstimateUsesCompactFallback() {
        val summary = outcome(seconds = 42f, millimeters = 80f, grams = 0.2f).previewSummary()

        assertTrue(summary.duration.underOneMinute)
        assertEquals(0, summary.duration.hours)
        assertEquals(1, summary.duration.minutes)
    }

    @Test
    fun invalidNativeStatisticsAreRejectedBeforeDisplay() {
        assertThrows(IllegalArgumentException::class.java) {
            outcome(seconds = Float.NaN, millimeters = 1f, grams = 1f).previewSummary()
        }
        assertThrows(IllegalArgumentException::class.java) {
            outcome(seconds = 1f, millimeters = -1f, grams = 1f).previewSummary()
        }
        assertThrows(IllegalArgumentException::class.java) {
            outcome(seconds = 1f, millimeters = 1f, grams = Float.POSITIVE_INFINITY).previewSummary()
        }
    }

    private fun outcome(seconds: Float, millimeters: Float, grams: Float) = SliceOutcome(
        output = File("preview-summary.gcode"),
        layers = 10,
        estimatedSeconds = seconds,
        filamentMm = millimeters,
        filamentGrams = grams,
    )
}

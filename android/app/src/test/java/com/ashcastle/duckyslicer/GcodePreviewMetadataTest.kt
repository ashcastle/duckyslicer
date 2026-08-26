package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GcodePreviewMetadataTest {
    @Test
    fun parsesOrcaDurationAndSumsPerToolFilament() {
        val metadata = parseGcodePreviewMetadata(
            """
            ; filament used [mm] = 1000.0, 250.0
            ; filament used [g] = 3.0, 0.75
            ; total filament used [g] = 3.75
            ; estimated printing time (normal mode) = 1h 14m 30s
            ; filament_colour = #123456;#ABCDEF
            """.trimIndent(),
        )
        val summary = metadata.summary

        val duration = requireNotNull(summary.duration)
        assertEquals(1, duration.hours)
        assertEquals(15, duration.minutes)
        assertEquals(1.25f, requireNotNull(summary.filamentMeters), 0.001f)
        assertEquals(3.75f, requireNotNull(summary.filamentGrams), 0.001f)
        assertEquals(listOf(0x123456, 0xABCDEF), metadata.filamentColors)
    }

    @Test
    fun fallsBackToValidExtruderColorsWhenFilamentColorsAreMalformed() {
        val metadata = parseGcodePreviewMetadata(
            "; extruder_colour = #010203;#A0B0C0\n; filament_colour = nope",
        )

        assertEquals(listOf(0x010203, 0xA0B0C0), metadata.filamentColors)
    }

    @Test
    fun parsesCommonTimeAndCuraLengthWithoutInventingMass() {
        val summary = parseGcodePreviewSummary(";TIME:42\n;Filament used: 2.5m")

        val duration = requireNotNull(summary.duration)
        assertEquals(true, duration.underOneMinute)
        assertEquals(2.5f, requireNotNull(summary.filamentMeters), 0.001f)
        assertNull(summary.filamentGrams)
    }

    @Test
    fun malformedOrNegativeStatisticsRemainUnavailable() {
        val summary = parseGcodePreviewSummary(
            ";TIME:-1\n; filament used [mm] = nope\n; total filament used [g] = NaN",
        )

        assertNull(summary.duration)
        assertNull(summary.filamentMeters)
        assertNull(summary.filamentGrams)
        assertEquals(
            emptyList<Int>(),
            parseGcodePreviewMetadata("; filament_colour = nope").filamentColors,
        )
    }
}

package com.ashcastle.duckyslicer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeightRangeModifiersTest {
    @Test
    fun sidecarAndJsonRoundTripEverySupportedOverride() {
        val modifiers = HeightRangeModifiers(
            listOf(
                HeightRangeModifier(
                    1f,
                    5f,
                    ObjectProcessOverrides(
                        layerHeightMm = 0.12f,
                        wallLoops = 4,
                        topShellLayers = 6,
                        bottomShellLayers = 5,
                        sparseInfillDensityPercent = 42f,
                        outerWallSpeedMmS = 35f,
                        innerWallSpeedMmS = 60f,
                        sparseInfillSpeedMmS = 80f,
                        supportEnabled = true,
                    ),
                ),
                HeightRangeModifier(
                    7f,
                    12f,
                    ObjectProcessOverrides(sparseInfillDensityPercent = 0f),
                ),
            ),
        )
        val sidecar = File.createTempFile("height-ranges", ".bin")
        try {
            modifiers.writeSidecar(sidecar)

            assertEquals(HeightRangeModifiers.sidecarBytes(2), sidecar.length())
            assertEquals(modifiers, HeightRangeModifiers.readSidecar(sidecar))
            assertEquals(modifiers, modifiers.toProjectJson().toHeightRangeModifiers())
        } finally {
            sidecar.delete()
        }
    }

    @Test
    fun rejectsOverlapEmptySettingsAndOutOfObjectRange() {
        val setting = ObjectProcessOverrides(wallLoops = 3)
        assertThrows(IllegalArgumentException::class.java) {
            HeightRangeModifiers(
                listOf(
                    HeightRangeModifier(1f, 6f, setting),
                    HeightRangeModifier(5f, 8f, setting),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeightRangeModifier(1f, 2f, ObjectProcessOverrides())
        }
        assertThrows(IllegalArgumentException::class.java) {
            HeightRangeModifiers(listOf(HeightRangeModifier(1f, 8f, setting)))
                .constrainedToHeight(7f)
        }
    }
}

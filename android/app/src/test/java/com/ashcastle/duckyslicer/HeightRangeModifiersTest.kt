package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeightRangeModifiersTest {
    @Test
    fun slicingResolvesInheritedLayerHeightWithoutChangingStoredOverrides() {
        val value = HeightRangeModifiers(listOf(
            HeightRangeModifier(1f, 3f, ObjectProcessOverrides(), 1),
            HeightRangeModifier(3f, 5f, ObjectProcessOverrides(layerHeightMm = 0.12f)),
        ))
        val resolved = value.resolvedForSlicing(0.235f)
        assertEquals(0.235f, resolved.ranges[0].overrides.layerHeightMm)
        assertEquals(0.12f, resolved.ranges[1].overrides.layerHeightMm)
        assertEquals(null, value.ranges[0].overrides.layerHeightMm)
        assertEquals(1, resolved.ranges[0].filamentSlot)
    }

    @Test
    fun colorOnlyAndMixedRangesRoundTripWithoutInventingProcessOverrides() {
        val value = HeightRangeModifiers(listOf(
            HeightRangeModifier(1f, 3f, ObjectProcessOverrides(), filamentSlot = 1),
            HeightRangeModifier(3f, 5f, ObjectProcessOverrides(wallLoops = 4)),
        ))
        val file = Files.createTempFile("height-color", ".bin").toFile()
        try {
            value.writeSidecar(file)
            assertEquals(HeightRangeModifiers.sidecarBytes(2, true), file.length())
            assertEquals(value, HeightRangeModifiers.readSidecar(file))
            assertEquals(value, value.toProjectJson().toHeightRangeModifiers())
            assertThrows(IllegalArgumentException::class.java) {
                HeightRangeModifier(1f, 2f, ObjectProcessOverrides(), MAX_FILAMENT_SLOTS)
            }
        } finally { file.delete() }
    }

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
        val sidecar = Files.createTempFile("height-ranges-", ".bin").toFile()
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

package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FeatureFilamentRoutingTest {
    @Test
    fun defaultVolumeRemainsUnspecifiedWhenFeatureRoutingNeedsIt() {
        assertEquals(
            0,
            FeatureFilamentSettings(
                infillOverrideEnabled = true,
                sparseInfillFilament = 2,
            ).nativeVolumeSlot(0),
        )
        assertEquals(
            0,
            FeatureFilamentSettings(solidInfillFilament = 2).nativeVolumeSlot(0),
        )
        assertEquals(
            0,
            FeatureFilamentSettings(wallFilament = 2).nativeVolumeSlot(0),
        )
    }

    @Test
    fun ordinaryAndExplicitObjectAssignmentsRemainOneBased() {
        assertEquals(1, FeatureFilamentSettings().nativeVolumeSlot(0))
        assertEquals(2, FeatureFilamentSettings(sparseInfillFilament = 2).nativeVolumeSlot(1))
        assertEquals(3, FeatureFilamentSettings(solidInfillFilament = 2).nativeVolumeSlot(2))
    }

    @Test
    fun invalidProjectSlotIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FeatureFilamentSettings().nativeVolumeSlot(-1)
        }
    }

    @Test
    fun removingTheLastSlotBoundsEveryMaterialRoute() {
        val options = SliceOptions()
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                supportFilament = 2,
                supportInterfaceFilament = 2,
                featureFilaments = FeatureFilamentSettings(
                    infillOverrideEnabled = true,
                    sparseInfillFilament = 2,
                    wallFilament = 2,
                    solidInfillFilament = 2,
                    wipeTowerFilament = 2,
                ),
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 65f, 175f, 0f),
                ),
            )
            .removeLastFilamentSlot()

        assertEquals(listOf(FilamentProfile.PLA), options.resolvedFilamentSlots())
        assertEquals(1, options.supportFilament)
        assertEquals(1, options.supportInterfaceFilament)
        assertEquals(1, options.featureFilaments.sparseInfillFilament)
        assertEquals(1, options.featureFilaments.wallFilament)
        assertEquals(1, options.featureFilaments.solidInfillFilament)
        assertEquals(1, options.featureFilaments.wipeTowerFilament)
        assertEquals(listOf(0f), options.multiMaterial.purgeVolumes)
    }

    @Test
    fun primarySlotCannotBeRemoved() {
        assertThrows(IllegalArgumentException::class.java) {
            SliceOptions().removeLastFilamentSlot()
        }
    }

    @Test
    fun addingAndRemovingSlotsPreservesDirectedPurgePairs() {
        val twoTools = SliceOptions().copy(
            filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
            multiMaterial = MultiMaterialSettings(
                purgeVolumes = listOf(0f, 65f, 175f, 0f),
            ),
        )

        val threeTools = twoTools.addFilamentSlot(FilamentProfile.ABS)
        assertEquals(
            listOf(
                0f, 65f, DEFAULT_PURGE_VOLUME,
                175f, 0f, DEFAULT_PURGE_VOLUME,
                DEFAULT_PURGE_VOLUME, DEFAULT_PURGE_VOLUME, 0f,
            ),
            threeTools.multiMaterial.purgeVolumes,
        )

        val adjusted = threeTools.copy(
            multiMaterial = threeTools.multiMaterial.withPurgeVolume(3, 2, 0, 88f),
        )
        assertEquals(88f, adjusted.multiMaterial.purgeVolumes[6])
        assertEquals(
            listOf(0f, 65f, 175f, 0f),
            adjusted.removeLastFilamentSlot().multiMaterial.purgeVolumes,
        )
    }

    @Test
    fun switchingToFewerToolsBoundsRoutesEvenWhenTheNozzleMatches() {
        val multiTool = SliceOptions().copy(
            filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
            supportFilament = 2,
            supportInterfaceFilament = 2,
            featureFilaments = FeatureFilamentSettings(
                sparseInfillFilament = 2,
                wallFilament = 2,
                solidInfillFilament = 2,
                wipeTowerFilament = 2,
            ),
        )

        val singleTool = multiTool.selectPrinter(
            PrinterProfile.U1_04.copy(id = "single-tool", extruderCount = 1),
        )

        assertEquals(1, singleTool.resolvedFilamentSlots().size)
        assertEquals(1, singleTool.supportFilament)
        assertEquals(1, singleTool.supportInterfaceFilament)
        assertEquals(1, singleTool.featureFilaments.sparseInfillFilament)
        assertEquals(1, singleTool.featureFilaments.wallFilament)
        assertEquals(1, singleTool.featureFilaments.solidInfillFilament)
        assertEquals(1, singleTool.featureFilaments.wipeTowerFilament)
    }
}

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
            )
            .removeLastFilamentSlot()

        assertEquals(listOf(FilamentProfile.PLA), options.resolvedFilamentSlots())
        assertEquals(1, options.supportFilament)
        assertEquals(1, options.supportInterfaceFilament)
        assertEquals(1, options.featureFilaments.sparseInfillFilament)
        assertEquals(1, options.featureFilaments.wallFilament)
        assertEquals(1, options.featureFilaments.solidInfillFilament)
        assertEquals(1, options.featureFilaments.wipeTowerFilament)
    }

    @Test
    fun primarySlotCannotBeRemoved() {
        assertThrows(IllegalArgumentException::class.java) {
            SliceOptions().removeLastFilamentSlot()
        }
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

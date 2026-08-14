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
}

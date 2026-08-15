package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimeTowerSettingsTest {
    @Test
    fun validatesTowerInterfaceFilamentBounds() {
        assertTrue(ProfileValidation.filament(FilamentProfile.GENERIC_PLA))

        listOf(
            FilamentProfile.GENERIC_PLA.copy(towerInterfacePreExtrusionDistance = 1_000.1f),
            FilamentProfile.GENERIC_PLA.copy(towerInterfacePreExtrusionLength = -0.1f),
            FilamentProfile.GENERIC_PLA.copy(towerIroningArea = 10_000.1f),
            FilamentProfile.GENERIC_PLA.copy(towerInterfacePurgeLength = 1_000.1f),
            FilamentProfile.GENERIC_PLA.copy(towerInterfacePrintTemperature = 501),
        ).forEach { profile ->
            assertFalse(ProfileValidation.filament(profile))
        }
    }

    @Test
    fun validatesPrimeTowerInfillGapBounds() {
        assertTrue(ProfileValidation.slicing(QualityProfile.STANDARD))
        assertFalse(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(
                    multiMaterial = MultiMaterialSettings(primeTowerInfillGap = 99.9f),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(
                    multiMaterial = MultiMaterialSettings(primeTowerInfillGap = 1_000.1f),
                ),
            ),
        )
    }
}

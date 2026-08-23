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

    @Test
    fun validatesPrimeTowerPositionBoundsAndFiniteValues() {
        val base = QualityProfile.STANDARD.multiMaterial
        assertTrue(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(
                    multiMaterial = base.copy(
                        primeTowerPositionX = -1_000f,
                        primeTowerPositionY = 1_000f,
                    ),
                ),
            ),
        )
        listOf(-1_000.1f, 1_000.1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertFalse(
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(
                        multiMaterial = base.copy(primeTowerPositionX = invalid),
                    ),
                ),
            )
            assertFalse(
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(
                        multiMaterial = base.copy(primeTowerPositionY = invalid),
                    ),
                ),
            )
        }
    }

    @Test
    fun validatesPrimeTowerBrimChamferWidthBoundsAndFiniteValues() {
        val base = QualityProfile.STANDARD.multiMaterial
        listOf(0f, 100f).forEach { valid ->
            assertTrue(
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(
                        multiMaterial = base.copy(primeTowerBrimChamferMaxWidth = valid),
                    ),
                ),
            )
        }
        listOf(-0.1f, 100.1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertFalse(
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(
                        multiMaterial = base.copy(primeTowerBrimChamferMaxWidth = invalid),
                    ),
                ),
            )
        }
    }

    @Test
    fun validatesFlushMultiplierBoundsAndFiniteValues() {
        val base = QualityProfile.STANDARD.multiMaterial
        listOf(0f, 0.3f, 10f).forEach { valid ->
            assertTrue(
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(
                        multiMaterial = base.copy(flushMultiplier = valid),
                    ),
                ),
            )
        }
        listOf(-0.01f, 10.01f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertFalse(
                ProfileValidation.slicing(
                    QualityProfile.STANDARD.copy(
                        multiMaterial = base.copy(flushMultiplier = invalid),
                    ),
                ),
            )
        }
    }
}

package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PelletProfilesTest {
    @Test
    fun orcaPelletFlowConversionRoundTrips() {
        val diameter = filamentDiameterFromPelletFlowCoefficient(1f)

        assertEquals(1.1283792f, diameter, 0.000001f)
        assertEquals(1f, pelletFlowCoefficientFromDiameter(diameter), 0.000001f)
        assertEquals(0.4157517f, pelletFlowCoefficientFromDiameter(1.75f), 0.000001f)
    }

    @Test
    fun selectingPelletMaterialUpdatesEffectiveFilamentDiameter() {
        val coefficient = 1f
        val profile = FilamentProfile.GENERIC_PLA.copy(
            id = "pellet-pla",
            pelletFlowCoefficient = coefficient,
            diameter = filamentDiameterFromPelletFlowCoefficient(coefficient),
        )

        val selected = SliceOptions().selectFilament(profile)

        assertEquals(profile.diameter, selected.filamentDiameter, 0.000001f)
        assertEquals(profile, selected.filamentProfile)
    }

    @Test
    fun legacyProfilesDeriveSafePelletDefaults() {
        val legacyFilament = FilamentProfile.GENERIC_PLA.copy(diameter = 2.85f)
            .toProfileJson()
            .apply { remove("pelletFlowCoefficient") }
        val legacyPrinter = PrinterProfile.CUSTOM_CARTESIAN.toProfileJson()
            .apply { remove("pelletModded") }

        val filament = requireNotNull(legacyFilament.toFilamentProfileOrNull())
        val printer = requireNotNull(legacyPrinter.toPrinterProfileOrNull())

        assertEquals(
            pelletFlowCoefficientFromDiameter(2.85f),
            filament.pelletFlowCoefficient,
            0.000001f,
        )
        assertFalse(printer.pelletModded)
    }

    @Test
    fun validationRejectsImpossiblePelletFlowCoefficient() {
        assertTrue(ProfileValidation.filament(FilamentProfile.GENERIC_PLA))
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(pelletFlowCoefficient = 0f),
            ),
        )
    }
}

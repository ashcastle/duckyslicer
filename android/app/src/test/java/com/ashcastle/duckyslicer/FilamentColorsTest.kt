package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FilamentColorsTest {
    @Test
    fun colorTextUsesAStableSixDigitRepresentation() {
        assertEquals("#000000", filamentColorText(0))
        assertEquals("#01A2FF", filamentColorText(0x01A2FF))
        assertEquals("#FFFFFF", filamentColorText(0xFFFFFF))
        assertThrows(IllegalArgumentException::class.java) { filamentColorText(-1) }
        assertThrows(IllegalArgumentException::class.java) { filamentColorText(0x1000000) }
    }

    @Test
    fun parserAcceptsUserFacingColorCodesWithoutLosingLeadingZeros() {
        assertEquals(0x01A2FF, parseFilamentColor("#01a2fF"))
        assertEquals(0xF6C945, parseFilamentColor("  F6C945  "))
        assertEquals(0, parseFilamentColor("#000000"))
    }

    @Test
    fun parserRejectsPartialAndMalformedValues() {
        assertNull(parseFilamentColor("#FFF"))
        assertNull(parseFilamentColor("#1234567"))
        assertNull(parseFilamentColor("#12GG56"))
        assertNull(parseFilamentColor(""))
    }

    @Test
    fun selectedMarkerContrastsWithBothLightAndDarkSpoolColors() {
        assertEquals(0xFFFFFF, filamentColorContrast(0x000000))
        assertEquals(0xFFFFFF, filamentColorContrast(0x26547C))
        assertEquals(0x000000, filamentColorContrast(0xFFFFFF))
        assertEquals(0x000000, filamentColorContrast(0xF6C945))
    }

    @Test
    fun profileColorIsPreferredOnlyWhenItIsValid() {
        assertEquals(
            0x124943,
            suggestedFilamentColor(1, FilamentProfile.PETG.copy(defaultColor = 0x124943)),
        )
        assertEquals(
            defaultFilamentColor(1),
            suggestedFilamentColor(1, FilamentProfile.PETG.copy(defaultColor = NO_FILAMENT_COLOR)),
        )
    }
}

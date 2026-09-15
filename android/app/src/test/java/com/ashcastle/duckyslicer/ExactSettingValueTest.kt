package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExactSettingValueTest {
    @Test fun labelsPreservePrecisionAndLocalizedUnits() {
        assertEquals("0.235 mm", exactSettingDisplay("0.23 mm", 0.235f, 1f, java.util.Locale.US))
        assertEquals("0,235 mm", exactSettingDisplay("0,23 mm", 0.235f, 1f, java.util.Locale.GERMANY))
        assertEquals("12.75 mm³/s", exactSettingDisplay("13 mm³/s", 12.75f, 1f, java.util.Locale.US))
        assertEquals("15.5%", exactSettingDisplay("16%", 0.155f, 100f, java.util.Locale.US))
        assertEquals("Automatic", exactSettingDisplay("Automatic", 0f, 1f, java.util.Locale.US))
        assertEquals("15", exactSettingInput(0.15f, 100f))
    }

    @Test fun acceptsExactDecimalsAndCommaDecimalSeparator() {
        assertEquals(0.235f, parseExactSettingValue("0.235", 1f, 0.05f..1f)!!, 0.00001f)
        assertEquals(0.235f, parseExactSettingValue("0,235", 1f, 0.05f..1f)!!, 0.00001f)
    }

    @Test fun percentagesUseDisplayedUnits() {
        assertEquals(0.155f, parseExactSettingValue("15.5", 100f, 0f..1f)!!, 0.00001f)
        assertEquals(0f, parseExactSettingValue("0", 100f, 0f..1f)!!, 0f)
    }

    @Test fun rejectsInvalidAndOutOfRangeInputWithoutClamping() {
        listOf("", "NaN", "Infinity", "-1", "101", "1.2.3").forEach {
            assertNull(parseExactSettingValue(it, 1f, 0f..100f))
        }
        assertNull(parseExactSettingValue("1", 0f, 0f..100f))
    }
}

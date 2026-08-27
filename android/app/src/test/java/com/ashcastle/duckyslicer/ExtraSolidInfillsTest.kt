package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraSolidInfillsTest {
    @Test
    fun validatesTheOrcaLayerPatternContract() {
        listOf("", "5", "5#2", "5#", "1,7,9", " \t5 # 2\n").forEach { pattern ->
            assertTrue(pattern, extraSolidInfillsIsValid(pattern))
            assertTrue(
                pattern,
                ProfileValidation.slicing(QualityProfile.STANDARD.copy(extraSolidInfills = pattern)),
            )
        }

        listOf("0", "5#0", "5#6", "1,,2", "1#2#3", "word", "1000001").forEach { pattern ->
            assertFalse(pattern, extraSolidInfillsIsValid(pattern))
            assertFalse(
                pattern,
                ProfileValidation.slicing(QualityProfile.STANDARD.copy(extraSolidInfills = pattern)),
            )
        }
    }
}

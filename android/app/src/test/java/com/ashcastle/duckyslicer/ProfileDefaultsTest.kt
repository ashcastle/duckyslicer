package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDefaultsTest {
    @Test fun newOptionsUseGenericPlaWithoutOverridingExplicitSelections() {
        assertEquals(FilamentProfile.GENERIC_PLA, SliceOptions().filamentProfile)
        assertEquals(listOf(FilamentProfile.GENERIC_PLA), SliceOptions().resolvedFilamentSlots())
        assertEquals(FilamentProfile.PETG, SliceOptions(filamentProfile = FilamentProfile.PETG).filamentProfile)
    }
}

package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileLibraryViewModelTest {
    @Test
    fun savedProfileAppliesOnlyToTheSessionRevisionThatStartedTheSave() {
        val source = SliceOptions().copy(fillDensity = 0.37f)
        val saved = source.quality.copy(
            id = "user-retained-quality",
            name = "Retained quality",
            fillDensity = 0.37f,
        )
        val completion = ProfileSaveCompletion.Slicing(
            id = 4,
            sessionRevision = 12,
            sourceOptions = source,
            saved = saved,
        )

        assertNull(completion.optionsForSession(13))
        val applied = requireNotNull(completion.optionsForSession(12))
        assertEquals(saved, applied.quality)
        assertEquals(0.37f, applied.fillDensity)
    }

    @Test
    fun eachSavedProfileKindBuildsItsExpectedSelection() {
        val source = SliceOptions()
        val printer = source.printerProfile.copy(
            id = "user-retained-printer",
            name = "Retained printer",
        )
        val filament = source.filamentProfile.copy(
            id = "user-retained-filament",
            name = "Retained filament",
        )

        val printerOptions = requireNotNull(
            ProfileSaveCompletion.Printer(1, 7, source, printer).optionsForSession(7),
        )
        val filamentOptions = requireNotNull(
            ProfileSaveCompletion.Filament(2, 7, source, 0, filament).optionsForSession(7),
        )

        assertEquals(printer, printerOptions.printerProfile)
        assertEquals(filament, filamentOptions.filamentProfile)
    }
}

package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    @Test
    fun profileTransferStateRejectsStaleTransitionsAndSettlesCancellation() {
        val idle = ProfileLibraryState(busy = false, catalogLoaded = true)
        val started = requireNotNull(
            idle.withStartedProfileTransfer(7, ProfileTransferDirection.IMPORT),
        )
        assertTrue(started.busy)
        assertEquals(ProfileTransferDirection.IMPORT, started.activeTransferDirection)
        assertNull(started.withStartedProfileTransfer(8, ProfileTransferDirection.EXPORT))
        assertNull(
            started.withProfileTransferCancellationRequested(
                8,
                ProfileTransferDirection.IMPORT,
            ),
        )

        val canceling = requireNotNull(
            started.withProfileTransferCancellationRequested(
                7,
                ProfileTransferDirection.IMPORT,
            ),
        )
        assertTrue(canceling.transferCancellationRequested)
        assertNull(
            canceling.withProfileTransferCancellationRequested(
                7,
                ProfileTransferDirection.IMPORT,
            ),
        )
        val settled = requireNotNull(
            canceling.withCompletedProfileTransfer(
                operationId = 7,
                direction = ProfileTransferDirection.IMPORT,
                requestedOutcome = ProfileTransferOutcome.SUCCEEDED,
                importResult = ProfileBundleImportResult(1, 0, 0, 0),
                refreshedCatalog = ProfileCatalog(),
                profileStorageUnavailable = false,
            ),
        )
        assertFalse(settled.busy)
        assertEquals(ProfileTransferOutcome.CANCELED, settled.transferCompletion?.outcome)
        assertNull(settled.transferCompletion?.importResult)
        assertNotNull(settled.catalog)
    }
}

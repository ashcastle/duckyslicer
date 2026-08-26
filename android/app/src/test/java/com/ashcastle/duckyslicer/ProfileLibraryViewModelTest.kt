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
    fun renamedProfilesOnlyReplaceMatchingCurrentSelections() {
        val source = SliceOptions().copy(
            filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PLA),
        )
        val unrelatedPrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            id = "user-unrelated-printer",
            name = "Renamed printer",
            builtIn = false,
        )
        val printerOptions = requireNotNull(
            ProfileSaveCompletion.Printer(
                1,
                7,
                source,
                unrelatedPrinter,
                selectSaved = false,
            ).optionsForSession(7),
        )
        assertEquals(source, printerOptions)

        val renamedFilament = FilamentProfile.PLA.copy(name = "Renamed PLA", builtIn = false)
        val filamentOptions = requireNotNull(
            ProfileSaveCompletion.Filament(
                2,
                7,
                source,
                0,
                renamedFilament,
                selectSaved = false,
            ).optionsForSession(7),
        )
        assertTrue(filamentOptions.resolvedFilamentSlots().all { it.name == "Renamed PLA" })

        val selectedSlicing = source.quality.copy(name = "Renamed slicing", builtIn = false)
        val slicingOptions = requireNotNull(
            ProfileSaveCompletion.Slicing(
                3,
                7,
                source,
                selectedSlicing,
                selectSaved = false,
            ).optionsForSession(7),
        )
        assertEquals("Renamed slicing", slicingOptions.quality.name)
    }

    @Test
    fun profileTransferStateRejectsStaleTransitionsAndSettlesCancellation() {
        val idle = ProfileLibraryState(busy = false, catalogLoaded = true)
        assertNull(
            idle.copy(
                deletionCompletion = ProfileDeleteCompletion(
                    id = 5,
                    kind = ProfileKind.PRINTER,
                    profileId = "user-pending",
                ),
            ).withStartedProfileTransfer(6, ProfileTransferDirection.IMPORT),
        )
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

    @Test
    fun deletingTheSelectedUserProfilePrefersABuiltInFallback() {
        val deleted = QualityProfile.STANDARD.copy(
            id = "user-delete-me",
            name = "Delete me",
            builtIn = false,
        )
        val otherUser = deleted.copy(id = "user-other", name = "Other")
        val builtIn = QualityProfile.FINE

        assertEquals(
            builtIn,
            profileDeletionFallback(
                entries = listOf(deleted, otherUser, builtIn),
                deleted = deleted,
                builtIn = QualityProfile::builtIn,
            ),
        )
        assertEquals(
            otherUser,
            profileDeletionFallback(
                entries = listOf(deleted, otherUser),
                deleted = deleted,
                builtIn = QualityProfile::builtIn,
            ),
        )
        assertNull(
            profileDeletionFallback(
                entries = listOf(deleted),
                deleted = deleted,
                builtIn = QualityProfile::builtIn,
            ),
        )
    }
}

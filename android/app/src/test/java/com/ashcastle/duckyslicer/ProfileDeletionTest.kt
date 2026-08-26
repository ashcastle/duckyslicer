package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDeletionTest {
    @Test
    fun eachUserProfileKindCanBeDeletedWithoutAffectingTheOthers() = withStore { file, store ->
        val printer = store.savePrinter("My printer", SliceOptions())
        val filament = store.saveFilament("My filament", SliceOptions())
        val slicing = store.saveSlicing("My slicing", SliceOptions())

        assertTrue(store.deletePrinter(printer.id))
        var catalog = store.load()
        assertFalse(catalog.printers.any { it.id == printer.id })
        assertTrue(catalog.filaments.any { it.id == filament.id })
        assertTrue(catalog.slicing.any { it.id == slicing.id })

        assertTrue(store.deleteFilament(filament.id))
        assertTrue(store.deleteSlicing(slicing.id))
        catalog = ProfileStore(file).load()
        assertFalse(catalog.filaments.any { it.id == filament.id })
        assertFalse(catalog.slicing.any { it.id == slicing.id })
    }

    @Test
    fun missingAndBuiltInProfileIdsAreNeverDeleted() = withStore { _, store ->
        val saved = store.savePrinter("Keep me", SliceOptions())

        assertFalse(store.deletePrinter(PrinterProfile.CUSTOM_CARTESIAN.id))
        assertFalse(store.deleteFilament(FilamentProfile.GENERIC_PLA.id))
        assertFalse(store.deleteSlicing(QualityProfile.STANDARD.id))
        assertFalse(store.deletePrinter("user-missing"))
        assertEquals(saved, store.load().printers.single { it.id == saved.id })
    }

    @Test
    fun unreadableProfileStorageIsNeverOverwrittenByDeletion() = withStore { file, store ->
        file.parentFile?.mkdirs()
        file.writeText("{broken-primary")
        File(file.parentFile, "${file.name}.bak").writeText("{broken-backup")

        store.load()
        assertTrue(store.storageUnavailable)
        assertThrows(IllegalStateException::class.java) {
            store.deletePrinter("user-profile")
        }
        assertEquals("{broken-primary", file.readText())
    }

    private fun withStore(block: (File, ProfileStore) -> Unit) {
        val directory = Files.createTempDirectory("duckyslicer-profile-delete-").toFile()
        try {
            val file = directory.resolve("user_profiles.json")
            block(file, ProfileStore(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}

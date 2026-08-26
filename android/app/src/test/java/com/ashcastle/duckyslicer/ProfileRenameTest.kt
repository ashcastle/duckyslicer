package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRenameTest {
    @Test
    fun everyUserProfileKindRenamesWithoutChangingIdentityOrSettings() = withStore { file, store ->
        val printer = store.savePrinter(
            "Printer before",
            SliceOptions().copy(maxPrintHeight = 345f),
        )
        val filament = store.saveFilament(
            "Filament before",
            SliceOptions().copy(nozzleTemp = 231, flowRatio = 0.97f),
        )
        val slicing = store.saveSlicing(
            "Slicing before",
            SliceOptions().copy(layerHeight = 0.18f, perimeters = 5),
        )

        assertEquals(printer.copy(name = "Printer after"), store.renamePrinter(printer.id, " Printer after "))
        assertEquals(filament.copy(name = "Filament after"), store.renameFilament(filament.id, "Filament after"))
        assertEquals(slicing.copy(name = "Slicing after"), store.renameSlicing(slicing.id, "Slicing after"))

        val restored = ProfileStore(file).load()
        assertEquals(printer.copy(name = "Printer after"), restored.printers.single { it.id == printer.id })
        assertEquals(filament.copy(name = "Filament after"), restored.filaments.single { it.id == filament.id })
        assertEquals(slicing.copy(name = "Slicing after"), restored.slicing.single { it.id == slicing.id })
    }

    @Test
    fun duplicateUserNamesAreRejectedCaseInsensitively() = withStore { _, store ->
        val printer = store.savePrinter("Workshop", SliceOptions())
        val filament = store.saveFilament("Matte PLA", SliceOptions())
        val slicing = store.saveSlicing("Detailed", SliceOptions())

        assertThrows(IllegalArgumentException::class.java) {
            store.savePrinter(" workshop ", SliceOptions())
        }
        val otherFilament = store.saveFilament("Gloss PLA", SliceOptions())
        assertThrows(IllegalArgumentException::class.java) {
            store.renameFilament(otherFilament.id, "MATTE PLA")
        }
        val otherSlicing = store.saveSlicing("Draft", SliceOptions())
        assertThrows(IllegalArgumentException::class.java) {
            store.renameSlicing(otherSlicing.id, " detailed ")
        }

        assertEquals("Workshop", store.load().printers.single { it.id == printer.id }.name)
        assertEquals("Matte PLA", store.load().filaments.single { it.id == filament.id }.name)
        assertEquals("Detailed", store.load().slicing.single { it.id == slicing.id }.name)
    }

    @Test
    fun missingBuiltInAndUnreadableProfilesCannotBeRenamed() = withStore { file, store ->
        val saved = store.savePrinter("Keep me", SliceOptions())
        assertThrows(IllegalStateException::class.java) {
            store.renamePrinter("user-missing", "Missing")
        }
        assertThrows(IllegalStateException::class.java) {
            store.renamePrinter(PrinterProfile.CUSTOM_CARTESIAN.id, "Built-in")
        }
        assertEquals(saved, store.load().printers.single { it.id == saved.id })

        file.writeText("{broken-primary")
        File(file.parentFile, "${file.name}.bak").writeText("{broken-backup")
        store.load()
        assertTrue(store.storageUnavailable)
        assertThrows(IllegalStateException::class.java) {
            store.renamePrinter(saved.id, "Must not overwrite")
        }
        assertEquals("{broken-primary", file.readText())
    }

    private fun withStore(block: (File, ProfileStore) -> Unit) {
        val directory = Files.createTempDirectory("duckyslicer-profile-rename-").toFile()
        try {
            val file = directory.resolve("user_profiles.json")
            block(file, ProfileStore(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}

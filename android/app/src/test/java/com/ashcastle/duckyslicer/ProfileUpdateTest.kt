package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateTest {
    @Test
    fun userProfilesUpdateInPlaceAndSurviveReload() = withStore { file, store ->
        val printer = store.savePrinter("My printer", SliceOptions())
        val filament = store.saveFilament("My filament", SliceOptions())
        val slicing = store.saveSlicing("My slicing", SliceOptions())

        val updatedPrinter = store.updatePrinter(
            printer.id,
            printer.name,
            SliceOptions().selectPrinter(printer).copy(maxPrintHeight = 345f),
        )
        val updatedFilament = store.updateFilament(
            filament.id,
            filament.name,
            SliceOptions().selectFilament(filament)
                .copy(nozzleTemp = 231, flowRatio = 0.97f)
                .updateFilamentColor(0, 0x124943),
        )
        val updatedSlicing = store.updateSlicing(
            slicing.id,
            slicing.name,
            SliceOptions().selectQuality(slicing).copy(layerHeight = 0.18f, perimeters = 5),
        )

        assertEquals(printer.id, updatedPrinter.id)
        assertEquals(filament.id, updatedFilament.id)
        assertEquals(slicing.id, updatedSlicing.id)

        val restored = ProfileStore(file).load()
        assertEquals(1, restored.printers.count { it.id == printer.id })
        assertEquals(345f, restored.printers.single { it.id == printer.id }.maxPrintHeight)
        assertEquals(1, restored.filaments.count { it.id == filament.id })
        assertEquals(231, restored.filaments.single { it.id == filament.id }.nozzleTemp)
        assertEquals(0.97f, restored.filaments.single { it.id == filament.id }.flowRatio)
        assertEquals(0x124943, restored.filaments.single { it.id == filament.id }.defaultColor)
        assertEquals(1, restored.slicing.count { it.id == slicing.id })
        assertEquals(0.18f, restored.slicing.single { it.id == slicing.id }.layerHeightMm)
        assertEquals(5, restored.slicing.single { it.id == slicing.id }.perimeters)
    }

    @Test
    fun missingAndBuiltInProfileIdsCannotBeUpdated() = withStore { _, store ->
        val saved = store.savePrinter("Keep me", SliceOptions())

        assertThrows(IllegalStateException::class.java) {
            store.updatePrinter("user-missing", "Missing", SliceOptions())
        }
        assertThrows(IllegalStateException::class.java) {
            store.updatePrinter(
                PrinterProfile.CUSTOM_CARTESIAN.id,
                "Built-in",
                SliceOptions(),
            )
        }

        assertEquals(saved, store.load().printers.single { it.id == saved.id })
    }

    @Test
    fun unreadableProfileStorageIsNeverOverwrittenByUpdate() = withStore { file, store ->
        file.parentFile?.mkdirs()
        file.writeText("{broken-primary")
        File(file.parentFile, "${file.name}.bak").writeText("{broken-backup")

        store.load()
        assertTrue(store.storageUnavailable)
        assertThrows(IllegalStateException::class.java) {
            store.updateSlicing("user-profile", "Must not overwrite", SliceOptions())
        }
        assertEquals("{broken-primary", file.readText())
    }

    private fun withStore(block: (File, ProfileStore) -> Unit) {
        val directory = Files.createTempDirectory("duckyslicer-profile-update-").toFile()
        try {
            val file = directory.resolve("user_profiles.json")
            block(file, ProfileStore(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}

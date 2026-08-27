package com.ashcastle.duckyslicer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUpdateTest {
    @Test
    fun filamentSafetyBoundsMatchTheEngineContract() {
        val boundary = FilamentProfile.GENERIC_PLA.copy(
            nozzleTemp = MAX_FILAMENT_NOZZLE_TEMPERATURE,
            firstLayerNozzleTemp = MAX_FILAMENT_NOZZLE_TEMPERATURE,
            flowRatio = MIN_FILAMENT_FLOW_RATIO,
        )

        assertTrue(ProfileValidation.filament(boundary))
        assertFalse(ProfileValidation.filament(boundary.copy(nozzleTemp = 501)))
        assertFalse(ProfileValidation.filament(boundary.copy(flowRatio = 0f)))
        assertFalse(ProfileValidation.filament(boundary.copy(notes = "한".repeat(5_462))))
        assertFalse(ProfileValidation.filament(boundary.copy(notes = "Unsafe\u0000note")))
    }

    @Test
    fun userProfilesUpdateInPlaceAndSurviveReload() = withStore { file, store ->
        val printer = store.savePrinter("My printer", SliceOptions())
        val filament = store.saveFilament(
            "My filament",
            SliceOptions().selectFilament(
                FilamentProfile.GENERIC_PLA.copy(
                    notes = "Dry before printing.\nUse bed adhesive.",
                    compatiblePrinters = listOf(PrinterProfile.U1_04.name),
                    compatiblePrints = listOf(QualityProfile.STANDARD.name),
                ),
            ),
        )
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
                .copy(
                    nozzleTemp = 420,
                    firstLayerNozzleTemp = 420,
                    flowRatio = 0.48f,
                )
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
        assertEquals(420, restored.filaments.single { it.id == filament.id }.nozzleTemp)
        assertEquals(420, restored.filaments.single { it.id == filament.id }.firstLayerNozzleTemp)
        assertEquals(0.48f, restored.filaments.single { it.id == filament.id }.flowRatio)
        assertEquals(0x124943, restored.filaments.single { it.id == filament.id }.defaultColor)
        assertEquals(
            "Dry before printing.\nUse bed adhesive.",
            restored.filaments.single { it.id == filament.id }.notes,
        )
        assertEquals(
            listOf(PrinterProfile.U1_04.name),
            restored.filaments.single { it.id == filament.id }.compatiblePrinters,
        )
        assertEquals(
            listOf(QualityProfile.STANDARD.name),
            restored.filaments.single { it.id == filament.id }.compatiblePrints,
        )
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

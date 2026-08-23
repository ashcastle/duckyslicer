package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NozzleHardnessSettingsTest {
    private val printer = PrinterProfile.U1_04.copy(
        nozzleMaterial = NozzleMaterial.BRASS,
        nozzleHrc = 0,
    )
    private val abrasiveFilament = FilamentProfile.GENERIC_PLA.copy(
        id = "abrasive-test",
        name = "Abrasive test",
        requiredNozzleHrc = 40,
    )

    @Test
    fun materialFallbackAndValidationMatchTheNativeContract() {
        assertEquals(2, printer.effectiveNozzleHrc())
        assertEquals(62, printer.copy(nozzleHrc = 62).effectiveNozzleHrc())
        assertTrue(ProfileValidation.printer(printer))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleHrc = -1)))
        assertFalse(ProfileValidation.printer(printer.copy(nozzleHrc = 501)))
        assertTrue(ProfileValidation.filament(abrasiveFilament))
        assertFalse(ProfileValidation.filament(abrasiveFilament.copy(requiredNozzleHrc = -1)))
        assertFalse(ProfileValidation.filament(abrasiveFilament.copy(requiredNozzleHrc = 501)))
    }

    @Test
    fun settingsRoundTripThroughProfilesProjectsAndNativeConfig() {
        val options = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(abrasiveFilament)
            .copy(
                filamentSlots = listOf(
                    abrasiveFilament,
                    abrasiveFilament.copy(
                        id = "non-abrasive-test",
                        name = "Non-abrasive test",
                        requiredNozzleHrc = 0,
                    ),
                ),
            )

        val restoredPrinter = requireNotNull(printer.toProfileJson().toPrinterProfileOrNull())
        assertEquals(NozzleMaterial.BRASS, restoredPrinter.nozzleMaterial)
        assertEquals(0, restoredPrinter.nozzleHrc)
        val restoredFilament = requireNotNull(
            abrasiveFilament.toProfileJson().toFilamentProfileOrNull(),
        )
        assertEquals(40, restoredFilament.requiredNozzleHrc)

        val projectJson = options.toProjectJson()
        assertEquals(97, projectJson.getInt("formatVersion"))
        val restored = requireNotNull(projectJson.toProjectSliceOptionsOrNull())
        assertEquals(NozzleMaterial.BRASS, restored.printerProfile.nozzleMaterial)
        assertEquals(listOf(40, 0), restored.filamentSlots.map(FilamentProfile::requiredNozzleHrc))

        val native = restored.toNativeConfig()
        assertEquals(NozzleMaterial.BRASS.nativeValue, native.nozzleMaterial)
        assertEquals(0, native.nozzleHrc)
        assertEquals(listOf(40, 0), native.filamentRequiredNozzleHrc.toList())
    }

    @Test
    fun userProfilePersistenceAndLegacyDefaultsRemainStable() {
        val directory = Files.createTempDirectory("duckyslicer-nozzle-hardness-").toFile()
        val file = directory.resolve("profiles.json")
        try {
            val options = SliceOptions()
                .selectPrinter(printer)
                .selectFilament(abrasiveFilament)
            val store = ProfileStore(file)
            val savedPrinter = store.savePrinter("Brass printer", options)
            val savedFilament = store.saveFilament("Abrasive filament", options)
            val restored = store.load()
            assertEquals(
                NozzleMaterial.BRASS,
                restored.printers.single { it.id == savedPrinter.id }.nozzleMaterial,
            )
            assertEquals(
                40,
                restored.filaments.single { it.id == savedFilament.id }.requiredNozzleHrc,
            )

            val legacyPrinter = JSONObject(printer.toProfileJson().toString()).apply {
                remove("nozzleMaterial")
                remove("nozzleHrc")
            }
            val legacyFilament = JSONObject(abrasiveFilament.toProfileJson().toString()).apply {
                remove("requiredNozzleHrc")
            }
            assertEquals(
                NozzleMaterial.UNDEFINED,
                requireNotNull(legacyPrinter.toPrinterProfileOrNull()).nozzleMaterial,
            )
            assertEquals(
                0,
                requireNotNull(legacyFilament.toFilamentProfileOrNull()).requiredNozzleHrc,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun warningCodesAreStrictAndBounded() {
        assertEquals(
            setOf(SliceWarningCode.NOZZLE_HARDNESS),
            parseSliceWarningCodes(listOf("nozzle_hardness")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            parseSliceWarningCodes(listOf("unknown"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSliceWarningCodes(listOf("nozzle_hardness", "nozzle_hardness"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSliceWarningCodes(List(MAX_SLICE_WARNING_CODES + 1) { "nozzle_hardness" })
        }
    }
}

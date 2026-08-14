package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreMigrationTest {
    @Test
    fun schemaThreeProfilesRemainReadableWithoutNewMetadataFields() {
        val file = Files.createTempFile("duckyslicer-profiles-v3-", ".json").toFile()
        try {
            val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(id = "v3-printer", name = "V3 Printer")
            val filament = FilamentProfile.GENERIC_PLA.copy(id = "v3-filament", name = "V3 Filament")
            val slicing = QualityProfile.STANDARD.copy(id = "v3-slicing", name = "V3 Slicing")
            val legacySlicing = slicing.toProfileJson().withoutProfileMetadata().apply {
                remove("makeOverhangPrintable")
                remove("makeOverhangPrintableAngle")
                remove("makeOverhangPrintableHoleSize")
            }
            file.writeText(
                JSONObject()
                    .put("schemaVersion", 3)
                    .put("printers", JSONArray().put(printer.toProfileJson().withoutProfileMetadata()))
                    .put("filaments", JSONArray().put(filament.toProfileJson().withoutProfileMetadata()))
                    .put("slicing", JSONArray().put(legacySlicing))
                    .toString(),
            )

            val restored = ProfileStore(file).load()
            val restoredPrinter = restored.printers.single { it.id == "v3-printer" }
            val restoredFilament = restored.filaments.single { it.id == "v3-filament" }
            val restoredSlicing = restored.slicing.single { it.id == "v3-slicing" }

            assertFalse(restoredPrinter.builtIn)
            assertNull(restoredPrinter.brand)
            assertTrue(restoredFilament.compatiblePrinters.isEmpty())
            assertTrue(restoredSlicing.compatiblePrinters.isEmpty())
            assertEquals("V3 Filament", restoredFilament.name)
            assertEquals("V3 Slicing", restoredSlicing.name)
            assertEquals(PrintableOverhangSettings(), restoredSlicing.printableOverhangs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun corruptPrimaryRecoversLastKnownGoodProfiles() {
        val directory = Files.createTempDirectory("duckyslicer-profile-recovery-").toFile()
        val file = directory.resolve("user_profiles.json")
        try {
            val profile = PrinterProfile.CUSTOM_CARTESIAN.copy(id = "saved", name = "Saved Printer")
            file.writeText(
                JSONObject()
                    .put("schemaVersion", 14)
                    .put("printers", JSONArray().put(profile.toProfileJson()))
                    .toString(),
            )
            assertTrue(ProfileStore(file).load().printers.any { it.id == "saved" })
            assertTrue(directory.resolve("user_profiles.json.bak").isFile)
            file.writeText("{broken")

            val recoveredStore = ProfileStore(file)
            val recovered = recoveredStore.load()

            assertTrue(recovered.printers.any { it.id == "saved" })
            assertFalse(recoveredStore.storageUnavailable)
            assertTrue(JSONObject(file.readText()).getJSONArray("printers").length() == 1)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nonRectangularBedPersistsAndMalformedPolygonFailsClosed() {
        val directory = Files.createTempDirectory("duckyslicer-profile-bed-").toFile()
        val file = directory.resolve("user_profiles.json")
        val polygon = listOf(110f, 0f, 220f, 110f, 110f, 220f, 0f, 110f)
        try {
            val options = SliceOptions().selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    bedOriginX = -110f,
                    bedOriginY = -110f,
                    bedPolygon = polygon,
                ),
            )
            val saved = ProfileStore(file).savePrinter("Delta bed", options)
            assertEquals(polygon, saved.bedPolygon)
            val restored = ProfileStore(file).load().printers.single { it.id == saved.id }
            assertEquals(polygon, restored.bedPolygon)
            assertEquals(-110f, restored.bedOriginX)
            assertEquals(-110f, restored.bedOriginY)

            val root = JSONObject(file.readText())
            root.getJSONArray("printers").getJSONObject(0)
                .put("bedPolygon", JSONArray(listOf(0, 0, 220, 220, 0, 220, 220, 0)))
            file.writeText(root.toString())
            directory.resolve("user_profiles.json.bak").delete()

            val invalid = ProfileStore(file)
            invalid.load()
            assertTrue(invalid.storageUnavailable)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun perFeatureJerkPersistsInUserSlicingProfiles() {
        val directory = Files.createTempDirectory("duckyslicer-profile-jerk-").toFile()
        val file = directory.resolve("user_profiles.json")
        try {
            val options = SliceOptions().copy(
                jerk = JerkSettings(
                    defaultJerk = 8.5f,
                    outerWallJerk = 7.5f,
                    innerWallJerk = 8f,
                    topSurfaceJerk = 6.5f,
                    infillJerk = 9.5f,
                    firstLayerJerk = 5.5f,
                    travelJerk = 12.5f,
                ),
                scarfSeam = ScarfSeamSettings(
                    type = "external",
                    conditional = true,
                    angleThreshold = 145,
                    overhangThreshold = 35f,
                    speed = 62f,
                    speedPercent = true,
                    flowRatio = 0.93f,
                    startHeight = 12f,
                    startHeightPercent = true,
                    entireLoop = false,
                    length = 18f,
                    steps = 11,
                    innerWalls = true,
                ),
            )

            val saved = ProfileStore(file).saveSlicing("Jerk tuned", options)
            val restored = ProfileStore(file).load().slicing.single { it.id == saved.id }

            assertEquals(8.5f, restored.defaultJerk)
            assertEquals(7.5f, restored.outerWallJerk)
            assertEquals(8f, restored.innerWallJerk)
            assertEquals(6.5f, restored.topSurfaceJerk)
            assertEquals(9.5f, restored.infillJerk)
            assertEquals(5.5f, restored.firstLayerJerk)
            assertEquals(12.5f, restored.travelJerk)
            assertEquals(options.scarfSeam, restored.scarfSeam)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun filamentRetractionInheritanceSurvivesUserProfileSave() {
        val directory = Files.createTempDirectory("duckyslicer-profile-retraction-").toFile()
        val file = directory.resolve("user_profiles.json")
        try {
            val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
                retractLength = 1.35f,
                zHopType = "spiral",
            )
            val options = SliceOptions()
                .selectPrinter(printer)
                .selectFilament(FilamentProfile.GENERIC_PLA)

            val saved = ProfileStore(file).saveFilament("Inherited PLA", options)
            val restored = ProfileStore(file).load().filaments.single { it.id == saved.id }

            assertNull(saved.retractLength)
            assertNull(saved.zHopType)
            assertNull(restored.retractLength)
            assertNull(restored.zHopType)
            assertEquals(1.35f, restored.resolveRetraction(printer).length)
            assertEquals("spiral", restored.resolveRetraction(printer).zHopType)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unreadableOrFutureProfilesAreNotOverwrittenBySave() {
        val semanticallyInvalid = JSONObject()
            .put("schemaVersion", 14)
            .put("printers", JSONArray().put(JSONObject().put("id", "incomplete")))
            .toString()
        for (contents in listOf(
            "{broken",
            """{"schemaVersion":999,"printers":[]}""",
            semanticallyInvalid,
        )) {
            val directory = Files.createTempDirectory("duckyslicer-profile-block-").toFile()
            val file = directory.resolve("user_profiles.json").apply { writeText(contents) }
            try {
                val original = file.readBytes()
                val store = ProfileStore(file)

                store.load()

                assertTrue(store.storageUnavailable)
                assertThrows(IllegalStateException::class.java) {
                    store.savePrinter("Must not overwrite", SliceOptions())
                }
                assertTrue(original.contentEquals(file.readBytes()))
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun JSONObject.withoutProfileMetadata(): JSONObject = apply {
        remove("builtIn")
        remove("brand")
        remove("compatiblePrinters")
    }
}

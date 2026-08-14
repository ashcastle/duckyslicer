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
            val legacyPrinter = printer.toProfileJson().withoutProfileMetadata().apply {
                remove("auxiliaryFan")
            }
            val legacyFilament = filament.toProfileJson().withoutProfileMetadata().apply {
                remove("diameter")
                remove("density")
                remove("costPerKilogram")
                remove("shrinkageXyPercent")
                remove("shrinkageZPercent")
                remove("soluble")
                remove("supportMaterial")
                remove("minimalPurgeOnWipeTower")
                remove("additionalCoolingFanSpeed")
                remove("fanCoolingLayerTime")
                remove("slowDownForLayerCooling")
                remove("keepFanAlwaysOn")
                remove("dontSlowDownOuterWall")
                remove("enableOverhangBridgeFan")
                remove("overhangFanThreshold")
                remove("internalBridgeFanSpeed")
                remove("supportInterfaceFanSpeed")
                remove("texturedPlateTemp")
                remove("firstLayerTexturedPlateTemp")
                remove("engineeringPlateTemp")
                remove("firstLayerEngineeringPlateTemp")
                remove("coolPlateTemp")
                remove("firstLayerCoolPlateTemp")
                remove("texturedCoolPlateTemp")
                remove("firstLayerTexturedCoolPlateTemp")
                remove("superTackPlateTemp")
                remove("firstLayerSuperTackPlateTemp")
                remove("graphicEffectPlateTemp")
                remove("firstLayerGraphicEffectPlateTemp")
            }
            val legacySlicing = slicing.toProfileJson().withoutProfileMetadata().apply {
                remove("makeOverhangPrintable")
                remove("makeOverhangPrintableAngle")
                remove("makeOverhangPrintableHoleSize")
                remove("holeToPolyhole")
                remove("holeToPolyholeThreshold")
                remove("holeToPolyholeThresholdPercent")
                remove("holeToPolyholeTwisted")
                remove("slowDownLayers")
                remove("maxVolumetricExtrusionRateSlope")
                remove("maxVolumetricExtrusionRateSlopeSegmentLength")
                remove("extrusionRateSmoothingExternalOnly")
                remove("travelSpeedZ")
                remove("purgeVolumes")
                remove("skeletonInfillDensity")
                remove("skinInfillDensity")
                remove("skinInfillDepth")
                remove("infillLockDepth")
                remove("skinInfillLineWidth")
                remove("skinInfillLineWidthPercent")
                remove("skeletonInfillLineWidth")
                remove("skeletonInfillLineWidthPercent")
                remove("skirtStartAngle")
                remove("gcodeComments")
                remove("topSurfaceDensity")
                remove("bottomSurfaceDensity")
                remove("infillShiftStep")
                remove("symmetricInfillYAxis")
            }
            file.writeText(
                JSONObject()
                    .put("schemaVersion", 3)
                    .put("printers", JSONArray().put(legacyPrinter))
                    .put("filaments", JSONArray().put(legacyFilament))
                    .put("slicing", JSONArray().put(legacySlicing))
                    .toString(),
            )

            val restored = ProfileStore(file).load()
            val restoredPrinter = restored.printers.single { it.id == "v3-printer" }
            val restoredFilament = restored.filaments.single { it.id == "v3-filament" }
            val restoredSlicing = restored.slicing.single { it.id == "v3-slicing" }

            assertFalse(restoredPrinter.builtIn)
            assertNull(restoredPrinter.brand)
            assertFalse(restoredPrinter.singleExtruderMultiMaterial)
            assertFalse(restoredPrinter.auxiliaryFan)
            assertTrue(restoredFilament.compatiblePrinters.isEmpty())
            assertTrue(restoredSlicing.compatiblePrinters.isEmpty())
            assertEquals("V3 Filament", restoredFilament.name)
            assertEquals(1.75f, restoredFilament.diameter)
            assertEquals(1.24f, restoredFilament.density)
            assertEquals(0f, restoredFilament.costPerKilogram)
            assertEquals(100f, restoredFilament.shrinkageXyPercent)
            assertEquals(100f, restoredFilament.shrinkageZPercent)
            assertFalse(restoredFilament.soluble)
            assertFalse(restoredFilament.supportMaterial)
            assertEquals(15f, restoredFilament.minimalPurgeOnWipeTower)
            assertEquals(0, restoredFilament.additionalCoolingFanSpeed)
            assertEquals(60f, restoredFilament.fanCoolingLayerTime)
            assertTrue(restoredFilament.slowDownForLayerCooling)
            assertFalse(restoredFilament.keepFanAlwaysOn)
            assertFalse(restoredFilament.dontSlowDownOuterWall)
            assertTrue(restoredFilament.enableOverhangBridgeFan)
            assertEquals("95%", restoredFilament.overhangFanThreshold)
            assertEquals(-1, restoredFilament.internalBridgeFanSpeed)
            assertEquals(-1, restoredFilament.supportInterfaceFanSpeed)
            BUILD_PLATE_TYPES.forEach { plate ->
                assertEquals(restoredFilament.bedTemp, restoredFilament.bedTemperature(plate))
                assertEquals(
                    restoredFilament.firstLayerBedTemp,
                    restoredFilament.firstLayerBedTemperature(plate),
                )
            }
            assertEquals("V3 Slicing", restoredSlicing.name)
            assertEquals(PrintableOverhangSettings(), restoredSlicing.printableOverhangs)
            assertEquals(PolyholeSettings(), restoredSlicing.precision.polyholes)
            assertEquals(0, restoredSlicing.gcodeSettings.slowDownLayers)
            assertEquals(ExtrusionRateSmoothingSettings(), restoredSlicing.extrusionRateSmoothing)
            assertEquals(0f, restoredSlicing.travelSpeedZ)
            assertEquals(emptyList<Float>(), restoredSlicing.multiMaterial.purgeVolumes)
            assertEquals(BrimEarSettings(), restoredSlicing.precision.brimEars)
            assertEquals(25f, restoredSlicing.skeletonInfillDensity)
            assertEquals(25f, restoredSlicing.skinInfillDensity)
            assertEquals(2f, restoredSlicing.skinInfillDepth)
            assertEquals(1f, restoredSlicing.infillLockDepth)
            assertEquals(100f, restoredSlicing.skinInfillLineWidth)
            assertTrue(restoredSlicing.skinInfillLineWidthPercent)
            assertEquals(100f, restoredSlicing.skeletonInfillLineWidth)
            assertTrue(restoredSlicing.skeletonInfillLineWidthPercent)
            assertEquals(-135f, restoredSlicing.skirtStartAngle)
            assertEquals(false, restoredSlicing.gcodeSettings.verboseComments)
            assertEquals(SurfaceDensitySettings(), restoredSlicing.surfaceDensity)
            assertEquals(0.4f, restoredSlicing.infillShiftStep)
            assertEquals(false, restoredSlicing.symmetricInfillYAxis)
            assertEquals("", restoredSlicing.sparseInfillRotationTemplate)
            assertEquals("", restoredSlicing.solidInfillRotationTemplate)
            assertEquals(0f, restoredSlicing.multiMaterial.segmentedRegionMaxWidth)
            assertEquals(0f, restoredSlicing.multiMaterial.segmentedRegionInterlockingDepth)
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
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                    auxiliaryFan = true,
                ),
            )
            val saved = ProfileStore(file).savePrinter("Delta bed", options)
            assertEquals(polygon, saved.bedPolygon)
            val restored = ProfileStore(file).load().printers.single { it.id == saved.id }
            assertEquals(polygon, restored.bedPolygon)
            assertEquals(-110f, restored.bedOriginX)
            assertEquals(-110f, restored.bedOriginY)
            assertTrue(restored.singleExtruderMultiMaterial)
            assertEquals(2, restored.extruderCount)
            assertTrue(restored.auxiliaryFan)

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
    fun lockedZagSettingsPersistInUserSlicingProfiles() {
        val directory = Files.createTempDirectory("duckyslicer-profile-locked-zag-").toFile()
        val file = directory.resolve("user_profiles.json")
        try {
            val options = SliceOptions().copy(
                fillPattern = "lockedzag",
                gcodeSettings = GcodeSettings(verboseComments = true),
                quality = QualityProfile.STANDARD.copy(
                    surfaceDensity = SurfaceDensitySettings(topPercent = 42f, bottomPercent = 68f),
                    skeletonInfillDensity = 31f,
                    skinInfillDensity = 47f,
                    skinInfillDepth = 3.5f,
                    infillLockDepth = 1.25f,
                    infillShiftStep = 1.7f,
                    symmetricInfillYAxis = true,
                    sparseInfillRotationTemplate = "0,60,120",
                    solidInfillRotationTemplate = "0,90",
                    skinInfillLineWidth = 135f,
                    skinInfillLineWidthPercent = true,
                    skeletonInfillLineWidth = 0.62f,
                    skeletonInfillLineWidthPercent = false,
                    skirtStartAngle = -25f,
                ),
            )

            val saved = ProfileStore(file).saveSlicing("Locked Zag tuned", options)
            val restored = ProfileStore(file).load().slicing.single { it.id == saved.id }

            assertEquals("lockedzag", restored.fillPattern)
            assertEquals(31f, restored.skeletonInfillDensity)
            assertEquals(47f, restored.skinInfillDensity)
            assertEquals(3.5f, restored.skinInfillDepth)
            assertEquals(1.25f, restored.infillLockDepth)
            assertEquals(135f, restored.skinInfillLineWidth)
            assertEquals(true, restored.skinInfillLineWidthPercent)
            assertEquals(0.62f, restored.skeletonInfillLineWidth)
            assertEquals(false, restored.skeletonInfillLineWidthPercent)
            assertEquals(-25f, restored.skirtStartAngle)
            assertEquals(true, restored.gcodeSettings.verboseComments)
            assertEquals(42f, restored.surfaceDensity.topPercent)
            assertEquals(68f, restored.surfaceDensity.bottomPercent)
            assertEquals(1.7f, restored.infillShiftStep)
            assertEquals(true, restored.symmetricInfillYAxis)
            assertEquals("0,60,120", restored.sparseInfillRotationTemplate)
            assertEquals("0,90", restored.solidInfillRotationTemplate)
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
                .selectFilament(
                    FilamentProfile.GENERIC_PLA.copy(
                        diameter = 2.85f,
                        density = 1.07f,
                        costPerKilogram = 42.5f,
                        shrinkageXyPercent = 99.2f,
                        shrinkageZPercent = 99.18f,
                        soluble = true,
                        supportMaterial = true,
                        minimalPurgeOnWipeTower = 35f,
                        additionalCoolingFanSpeed = 70,
                        fanCoolingLayerTime = 42f,
                        slowDownForLayerCooling = false,
                        keepFanAlwaysOn = true,
                        dontSlowDownOuterWall = true,
                        enableOverhangBridgeFan = true,
                        overhangFanThreshold = "25%",
                        internalBridgeFanSpeed = 45,
                        supportInterfaceFanSpeed = 85,
                    ),
                )

            val saved = ProfileStore(file).saveFilament("Inherited PLA", options)
            val restored = ProfileStore(file).load().filaments.single { it.id == saved.id }

            assertNull(saved.retractLength)
            assertNull(saved.zHopType)
            assertNull(restored.retractLength)
            assertNull(restored.zHopType)
            assertEquals(1.35f, restored.resolveRetraction(printer).length)
            assertEquals("spiral", restored.resolveRetraction(printer).zHopType)
            assertEquals(2.85f, saved.diameter)
            assertEquals(2.85f, restored.diameter)
            assertEquals(1.07f, saved.density)
            assertEquals(1.07f, restored.density)
            assertEquals(42.5f, saved.costPerKilogram)
            assertEquals(42.5f, restored.costPerKilogram)
            assertEquals(99.2f, saved.shrinkageXyPercent)
            assertEquals(99.2f, restored.shrinkageXyPercent)
            assertEquals(99.18f, saved.shrinkageZPercent)
            assertEquals(99.18f, restored.shrinkageZPercent)
            assertTrue(saved.soluble)
            assertTrue(restored.soluble)
            assertTrue(saved.supportMaterial)
            assertTrue(restored.supportMaterial)
            assertEquals(35f, saved.minimalPurgeOnWipeTower)
            assertEquals(35f, restored.minimalPurgeOnWipeTower)
            assertEquals(70, saved.additionalCoolingFanSpeed)
            assertEquals(70, restored.additionalCoolingFanSpeed)
            assertEquals(42f, saved.fanCoolingLayerTime)
            assertEquals(42f, restored.fanCoolingLayerTime)
            assertFalse(saved.slowDownForLayerCooling)
            assertFalse(restored.slowDownForLayerCooling)
            assertTrue(saved.keepFanAlwaysOn)
            assertTrue(restored.keepFanAlwaysOn)
            assertTrue(saved.dontSlowDownOuterWall)
            assertTrue(restored.dontSlowDownOuterWall)
            assertEquals("25%", restored.overhangFanThreshold)
            assertEquals(45, restored.internalBridgeFanSpeed)
            assertEquals(85, restored.supportInterfaceFanSpeed)
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

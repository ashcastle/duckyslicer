package com.ashcastle.duckyslicer

import com.u1.slicer.data.DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
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
                remove("fanSpeedupTime")
                remove("fanSpeedupOverhangs")
                remove("fanKickstart")
                remove("minLayerHeight")
                remove("maxLayerHeight")
                remove("extruderOffsetsX")
                remove("extruderOffsetsY")
                remove("beforeLayerChangeGcode")
                remove("layerChangeGcode")
                remove("changeFilamentGcode")
                remove("printingByObjectGcode")
                remove("useRelativeEDistances")
                remove("emitMachineLimitsToGcode")
                remove("manualFilamentChange")
                remove("disableM73")
                remove("coolingTubeRetraction")
                remove("coolingTubeLength")
                remove("parkingPosRetraction")
                remove("extraLoadingMove")
                remove("enableFilamentRamming")
                remove("purgeInPrimeTower")
                remove("highCurrentOnFilamentSwap")
                remove("toolChangeRetractLengths")
                remove("toolChangeRetractRestartExtras")
                remove("defaultPrintProfile")
                remove("defaultFilamentProfiles")
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
                remove("towerInterfacePreExtrusionDistance")
                remove("towerInterfacePreExtrusionLength")
                remove("towerIroningArea")
                remove("towerInterfacePurgeLength")
                remove("towerInterfacePrintTemperature")
                remove("additionalCoolingFanSpeed")
                remove("fanCoolingLayerTime")
                remove("slowDownForLayerCooling")
                remove("keepFanAlwaysOn")
                remove("dontSlowDownOuterWall")
                remove("enableOverhangBridgeFan")
                remove("overhangFanThreshold")
                remove("internalBridgeFanSpeed")
                remove("supportInterfaceFanSpeed")
                remove("ironingFanSpeed")
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
                remove("flushMultiplierOverrideEnabled")
                remove("flushMultiplier")
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
                remove("smallAreaFlowCompensation")
                remove("smallAreaFlowCompensationModel")
                remove("primeTowerFramework")
                remove("primeTowerSkipPoints")
                remove("primeTowerFlatIroning")
                remove("primeTowerInterfaceFeatures")
                remove("primeTowerInterfaceCooldown")
                remove("primeTowerInfillGap")
                remove("primeTowerPositionX")
                remove("primeTowerPositionY")
                remove("primeTowerBrimChamfer")
                remove("primeTowerBrimChamferMaxWidth")
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
            assertEquals(0f, restoredPrinter.fanSpeedupTime)
            assertTrue(restoredPrinter.fanSpeedupOverhangs)
            assertEquals(0f, restoredPrinter.fanKickstart)
            assertEquals(0.04f, restoredPrinter.minLayerHeight)
            assertEquals(0.28f, restoredPrinter.maxLayerHeight)
            assertEquals(listOf(0f), restoredPrinter.extruderOffsetsX)
            assertEquals(listOf(0f), restoredPrinter.extruderOffsetsY)
            assertEquals("", restoredPrinter.beforeLayerChangeGcode)
            assertEquals("", restoredPrinter.layerChangeGcode)
            assertEquals("", restoredPrinter.changeFilamentGcode)
            assertEquals("", restoredPrinter.printingByObjectGcode)
            assertTrue(restoredPrinter.useRelativeEDistances)
            assertTrue(restoredPrinter.emitMachineLimitsToGcode)
            assertFalse(restoredPrinter.manualFilamentChange)
            assertFalse(restoredPrinter.disableM73)
            assertEquals("", restoredPrinter.defaultPrintProfile)
            assertEquals(emptyList<String>(), restoredPrinter.defaultFilamentProfiles)
            assertEquals(91.5f, restoredPrinter.coolingTubeRetraction)
            assertEquals(5f, restoredPrinter.coolingTubeLength)
            assertEquals(92f, restoredPrinter.parkingPosRetraction)
            assertEquals(-2f, restoredPrinter.extraLoadingMove)
            assertTrue(restoredPrinter.enableFilamentRamming)
            assertTrue(restoredPrinter.purgeInPrimeTower)
            assertFalse(restoredPrinter.highCurrentOnFilamentSwap)
            assertEquals(listOf(0.8f), restoredPrinter.toolChangeRetractLengths)
            assertEquals(listOf(0f), restoredPrinter.toolChangeRetractRestartExtras)
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
            assertEquals(10f, restoredFilament.towerInterfacePreExtrusionDistance)
            assertEquals(0f, restoredFilament.towerInterfacePreExtrusionLength)
            assertEquals(4f, restoredFilament.towerIroningArea)
            assertEquals(20f, restoredFilament.towerInterfacePurgeLength)
            assertEquals(-1, restoredFilament.towerInterfacePrintTemperature)
            assertEquals(0, restoredFilament.additionalCoolingFanSpeed)
            assertEquals(60f, restoredFilament.fanCoolingLayerTime)
            assertTrue(restoredFilament.slowDownForLayerCooling)
            assertFalse(restoredFilament.keepFanAlwaysOn)
            assertFalse(restoredFilament.dontSlowDownOuterWall)
            assertTrue(restoredFilament.enableOverhangBridgeFan)
            assertEquals("95%", restoredFilament.overhangFanThreshold)
            assertEquals(-1, restoredFilament.internalBridgeFanSpeed)
            assertEquals(-1, restoredFilament.supportInterfaceFanSpeed)
            assertEquals(-1, restoredFilament.ironingFanSpeed)
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
            assertFalse(restoredSlicing.multiMaterial.flushMultiplierOverrideEnabled)
            assertEquals(0.3f, restoredSlicing.multiMaterial.flushMultiplier)
            assertFalse(restoredSlicing.multiMaterial.primeTowerFramework)
            assertTrue(restoredSlicing.multiMaterial.primeTowerSkipPoints)
            assertFalse(restoredSlicing.multiMaterial.primeTowerFlatIroning)
            assertFalse(restoredSlicing.multiMaterial.primeTowerInterfaceFeatures)
            assertFalse(restoredSlicing.multiMaterial.primeTowerInterfaceCooldown)
            assertEquals(150f, restoredSlicing.multiMaterial.primeTowerInfillGap)
            assertEquals(170f, restoredSlicing.multiMaterial.primeTowerPositionX)
            assertEquals(140f, restoredSlicing.multiMaterial.primeTowerPositionY)
            assertTrue(restoredSlicing.multiMaterial.primeTowerBrimChamfer)
            assertEquals(4f, restoredSlicing.multiMaterial.primeTowerBrimChamferMaxWidth)
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
            assertEquals(DEFAULT_GCODE_FILENAME_FORMAT, restoredSlicing.gcodeSettings.filenameFormat)
            assertEquals(SurfaceDensitySettings(), restoredSlicing.surfaceDensity)
            assertEquals(0.4f, restoredSlicing.infillShiftStep)
            assertEquals(false, restoredSlicing.symmetricInfillYAxis)
            assertEquals("", restoredSlicing.sparseInfillRotationTemplate)
            assertEquals("", restoredSlicing.solidInfillRotationTemplate)
            assertFalse(restoredSlicing.smallAreaFlowCompensation)
            assertEquals(
                DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL,
                restoredSlicing.smallAreaFlowCompensationModel,
            )
            assertEquals(0f, restoredSlicing.multiMaterial.segmentedRegionMaxWidth)
            assertEquals(0f, restoredSlicing.multiMaterial.segmentedRegionInterlockingDepth)
            assertEquals(0, restoredSlicing.supportCoverage.enforcedLayers)
            assertEquals(LateralInfillSettings(), restoredSlicing.lateralInfill)
            assertEquals(1, restoredSlicing.fillMultiline)
            assertEquals("combined", restoredSlicing.skirtType)
            assertEquals(false, restoredSlicing.singleLoopDraftShield)
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
                    bedExcludeArea = listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
                    singleExtruderMultiMaterial = true,
                    extruderCount = 2,
                    auxiliaryFan = true,
                    retractLiftAbove = 0.35f,
                    retractLiftBelow = 180f,
                    retractLiftEnforce = "top_bottom",
                    travelSlope = 7f,
                    zHopWhenPrime = false,
                    longRetractionWhenCutLevel = 2,
                    longRetractionWhenCut = true,
                    retractionDistanceWhenCut = 16.5f,
                ),
            )
            val saved = ProfileStore(file).savePrinter("Delta bed", options)
            assertEquals(polygon, saved.bedPolygon)
            assertEquals(
                listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
                saved.bedExcludeArea,
            )
            val restored = ProfileStore(file).load().printers.single { it.id == saved.id }
            assertEquals(polygon, restored.bedPolygon)
            assertEquals(
                listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
                restored.bedExcludeArea,
            )
            assertEquals(-110f, restored.bedOriginX)
            assertEquals(-110f, restored.bedOriginY)
            assertTrue(restored.singleExtruderMultiMaterial)
            assertEquals(2, restored.extruderCount)
            assertTrue(restored.auxiliaryFan)
            assertEquals(0.35f, restored.retractLiftAbove)
            assertEquals(180f, restored.retractLiftBelow)
            assertEquals("top_bottom", restored.retractLiftEnforce)
            assertEquals(7f, restored.travelSlope)
            assertFalse(restored.zHopWhenPrime)
            assertEquals(2, restored.longRetractionWhenCutLevel)
            assertTrue(restored.longRetractionWhenCut)
            assertEquals(16.5f, restored.retractionDistanceWhenCut)

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
                supportCoverage = SupportCoverageSettings(enforcedLayers = 7),
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
                multiMaterial = MultiMaterialSettings(
                    flushMultiplierOverrideEnabled = true,
                    flushMultiplier = 1.25f,
                    primeTowerPositionX = 123.5f,
                    primeTowerPositionY = 87.5f,
                    primeTowerBrimChamfer = false,
                    primeTowerBrimChamferMaxWidth = 7.5f,
                    primeTowerFramework = true,
                    primeTowerSkipPoints = false,
                    primeTowerFlatIroning = true,
                    primeTowerInterfaceFeatures = true,
                    primeTowerInterfaceCooldown = true,
                    primeTowerInfillGap = 175f,
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
            assertEquals(7, restored.supportCoverage.enforcedLayers)
            assertTrue(restored.multiMaterial.flushMultiplierOverrideEnabled)
            assertEquals(1.25f, restored.multiMaterial.flushMultiplier)
            assertEquals(123.5f, restored.multiMaterial.primeTowerPositionX)
            assertEquals(87.5f, restored.multiMaterial.primeTowerPositionY)
            assertFalse(restored.multiMaterial.primeTowerBrimChamfer)
            assertEquals(7.5f, restored.multiMaterial.primeTowerBrimChamferMaxWidth)
            assertTrue(restored.multiMaterial.primeTowerFramework)
            assertFalse(restored.multiMaterial.primeTowerSkipPoints)
            assertTrue(restored.multiMaterial.primeTowerFlatIroning)
            assertTrue(restored.multiMaterial.primeTowerInterfaceFeatures)
            assertTrue(restored.multiMaterial.primeTowerInterfaceCooldown)
            assertEquals(175f, restored.multiMaterial.primeTowerInfillGap)
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
                gcodeSettings = GcodeSettings(
                    verboseComments = true,
                    filenameFormat = "{input_filename_base}_{print_time}.gcode",
                ),
                quality = QualityProfile.STANDARD.copy(
                    surfaceDensity = SurfaceDensitySettings(topPercent = 42f, bottomPercent = 68f),
                    lateralInfill = LateralInfillSettings(-32f, 57f, 68f),
                    skeletonInfillDensity = 31f,
                    skinInfillDensity = 47f,
                    skinInfillDepth = 3.5f,
                    infillLockDepth = 1.25f,
                    infillShiftStep = 1.7f,
                    symmetricInfillYAxis = true,
                    sparseInfillRotationTemplate = "0,60,120",
                    solidInfillRotationTemplate = "0,90",
                    extraSolidInfills = "5#2",
                    smallAreaFlowCompensation = true,
                    smallAreaFlowCompensationModel = "0,0\n0.5,0.6\n10,1",
                    skinInfillLineWidth = 135f,
                    skinInfillLineWidthPercent = true,
                    skeletonInfillLineWidth = 0.62f,
                    skeletonInfillLineWidthPercent = false,
                    skirtStartAngle = -25f,
                    skirtType = "perobject",
                    singleLoopDraftShield = true,
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
            assertEquals("perobject", restored.skirtType)
            assertEquals(true, restored.singleLoopDraftShield)
            assertEquals(true, restored.gcodeSettings.verboseComments)
            assertEquals(
                "{input_filename_base}_{print_time}.gcode",
                restored.gcodeSettings.filenameFormat,
            )
            assertEquals(42f, restored.surfaceDensity.topPercent)
            assertEquals(68f, restored.surfaceDensity.bottomPercent)
            assertEquals(LateralInfillSettings(-32f, 57f, 68f), restored.lateralInfill)
            assertEquals(1.7f, restored.infillShiftStep)
            assertEquals(true, restored.symmetricInfillYAxis)
            assertEquals("0,60,120", restored.sparseInfillRotationTemplate)
            assertEquals("0,90", restored.solidInfillRotationTemplate)
            assertEquals("5#2", restored.extraSolidInfills)
            assertTrue(restored.smallAreaFlowCompensation)
            assertEquals("0,0\n0.5,0.6\n10,1", restored.smallAreaFlowCompensationModel)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fillMultilinePersistsInUserSlicingProfiles() {
        val directory = Files.createTempDirectory("duckyslicer-profile-fill-multiline-").toFile()
        val file = directory.resolve("user_profiles.json")
        try {
            val options = SliceOptions().copy(
                fillPattern = "crosshatch",
                quality = QualityProfile.STANDARD.copy(fillMultiline = 4),
            )

            val saved = ProfileStore(file).saveSlicing("Four-line infill", options)
            val restored = ProfileStore(file).load().slicing.single { it.id == saved.id }

            assertEquals("crosshatch", restored.fillPattern)
            assertEquals(4, restored.fillMultiline)
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
                retractLiftAbove = 0.4f,
                retractLiftBelow = 180f,
                retractLiftEnforce = "top_bottom",
                longRetractionWhenCutLevel = 2,
                longRetractionWhenCut = true,
                retractionDistanceWhenCut = 17f,
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
                        towerInterfacePreExtrusionDistance = 21f,
                        towerInterfacePreExtrusionLength = 22f,
                        towerIroningArea = 23f,
                        towerInterfacePurgeLength = 24f,
                        towerInterfacePrintTemperature = 241,
                        additionalCoolingFanSpeed = 70,
                        fanCoolingLayerTime = 42f,
                        slowDownForLayerCooling = false,
                        keepFanAlwaysOn = true,
                        dontSlowDownOuterWall = true,
                        enableOverhangBridgeFan = true,
                        overhangFanThreshold = "25%",
                        internalBridgeFanSpeed = 45,
                        supportInterfaceFanSpeed = 85,
                        ironingFanSpeed = 37,
                    ),
                )

            val saved = ProfileStore(file).saveFilament("Inherited PLA", options)
            val restored = ProfileStore(file).load().filaments.single { it.id == saved.id }

            assertNull(saved.retractLength)
            assertNull(saved.zHopType)
            assertNull(saved.retractLiftAbove)
            assertNull(saved.retractLiftBelow)
            assertNull(saved.retractLiftEnforce)
            assertNull(saved.longRetractionWhenCut)
            assertNull(saved.retractionDistanceWhenCut)
            assertNull(restored.retractLength)
            assertNull(restored.zHopType)
            assertNull(restored.retractLiftAbove)
            assertNull(restored.retractLiftBelow)
            assertNull(restored.retractLiftEnforce)
            assertNull(restored.longRetractionWhenCut)
            assertNull(restored.retractionDistanceWhenCut)
            assertEquals(1.35f, restored.resolveRetraction(printer).length)
            assertEquals("spiral", restored.resolveRetraction(printer).zHopType)
            assertEquals(0.4f, restored.resolveRetraction(printer).liftAbove)
            assertEquals(180f, restored.resolveRetraction(printer).liftBelow)
            assertEquals("top_bottom", restored.resolveRetraction(printer).liftEnforce)
            assertTrue(restored.resolveRetraction(printer).longRetractionWhenCut)
            assertEquals(17f, restored.resolveRetraction(printer).retractionDistanceWhenCut)
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
            assertEquals(21f, saved.towerInterfacePreExtrusionDistance)
            assertEquals(21f, restored.towerInterfacePreExtrusionDistance)
            assertEquals(22f, saved.towerInterfacePreExtrusionLength)
            assertEquals(22f, restored.towerInterfacePreExtrusionLength)
            assertEquals(23f, saved.towerIroningArea)
            assertEquals(23f, restored.towerIroningArea)
            assertEquals(24f, saved.towerInterfacePurgeLength)
            assertEquals(24f, restored.towerInterfacePurgeLength)
            assertEquals(241, saved.towerInterfacePrintTemperature)
            assertEquals(241, restored.towerInterfacePrintTemperature)
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
            assertEquals(37, restored.ironingFanSpeed)
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

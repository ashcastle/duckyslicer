package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceOptionsPersistenceTest {
    @Test
    fun filamentSlotsRoundTripAndReachTheNativeExtruderConfiguration() {
        val primary = FilamentProfile.GENERIC_PLA.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            filamentStartGcode = "M117 PRIMARY_START",
            filamentEndGcode = "M117 PRIMARY_END",
            diameter = 2.85f,
            density = 1.07f,
            costPerKilogram = 42.5f,
            shrinkageXyPercent = 99.2f,
            shrinkageZPercent = 99.18f,
            minimalPurgeOnWipeTower = 9f,
            towerInterfacePreExtrusionDistance = 11f,
            towerInterfacePreExtrusionLength = 12f,
            towerIroningArea = 13f,
            towerInterfacePurgeLength = 14f,
            towerInterfacePrintTemperature = 231,
            additionalCoolingFanSpeed = 40,
            loadingSpeed = 21f,
            loadingSpeedStart = 4f,
            unloadingSpeed = 81f,
            unloadingSpeedStart = 91f,
            toolchangeDelay = 0.7f,
            coolingMoves = 3,
            stampingLoadingSpeed = 29f,
            stampingDistance = 45f,
            coolingInitialSpeed = 2.5f,
            coolingFinalSpeed = 4.5f,
            rammingParameters = "125 95 7 8 9| 0.1 7 0.5 8",
            multitoolRamming = true,
            multitoolRammingVolume = 6f,
            multitoolRammingFlow = 16f,
            softeningTemperature = 62,
            nozzleTemperatureRangeLow = 195,
            nozzleTemperatureRangeHigh = 245,
            chamberTemperatureControl = true,
            chamberTemperature = 55,
            airFiltration = true,
            duringPrintExhaustFanSpeed = 70,
            completePrintExhaustFanSpeed = 40,
            fanCoolingLayerTime = 42f,
            slowDownForLayerCooling = false,
            keepFanAlwaysOn = true,
            dontSlowDownOuterWall = true,
            enableOverhangBridgeFan = true,
            overhangFanThreshold = "25%",
            internalBridgeFanSpeed = 45,
            supportInterfaceFanSpeed = 85,
            ironingFanSpeed = 37,
            bedTemp = 71,
            firstLayerBedTemp = 72,
            texturedPlateTemp = 53,
            firstLayerTexturedPlateTemp = 54,
        )
        val secondary = FilamentProfile.PETG.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_04.name),
            filamentStartGcode = "M117 SECONDARY_START",
            filamentEndGcode = "M117 SECONDARY_END",
            diameter = 2.85f,
            density = 1.32f,
            costPerKilogram = 75f,
            shrinkageXyPercent = 98.4f,
            shrinkageZPercent = 98.8f,
            soluble = true,
            supportMaterial = true,
            minimalPurgeOnWipeTower = 35f,
            towerInterfacePreExtrusionDistance = 21f,
            towerInterfacePreExtrusionLength = 22f,
            towerIroningArea = 23f,
            towerInterfacePurgeLength = 24f,
            towerInterfacePrintTemperature = 241,
            additionalCoolingFanSpeed = 70,
            loadingSpeed = 31f,
            loadingSpeedStart = 5f,
            unloadingSpeed = 82f,
            unloadingSpeedStart = 92f,
            toolchangeDelay = 1.2f,
            coolingMoves = 5,
            stampingLoadingSpeed = 0f,
            stampingDistance = 0f,
            coolingInitialSpeed = 3.5f,
            coolingFinalSpeed = 5.5f,
            rammingParameters = "130 90 8 9 10| 0.2 8 0.6 9",
            multitoolRamming = false,
            multitoolRammingVolume = 7f,
            multitoolRammingFlow = 17f,
            softeningTemperature = 80,
            nozzleTemperatureRangeLow = 220,
            nozzleTemperatureRangeHigh = 280,
            chamberTemperatureControl = false,
            chamberTemperature = 60,
            airFiltration = false,
            duringPrintExhaustFanSpeed = 100,
            completePrintExhaustFanSpeed = 0,
            fanCoolingLayerTime = 91f,
            slowDownForLayerCooling = true,
            keepFanAlwaysOn = false,
            dontSlowDownOuterWall = false,
            enableOverhangBridgeFan = false,
            overhangFanThreshold = "75%",
            internalBridgeFanSpeed = -1,
            supportInterfaceFanSpeed = 65,
            ironingFanSpeed = 72,
            bedTemp = 81,
            firstLayerBedTemp = 82,
            texturedPlateTemp = 63,
            firstLayerTexturedPlateTemp = 64,
            retractLiftAbove = 1.25f,
            retractLiftBelow = 140f,
            retractLiftEnforce = "top",
            longRetractionWhenCut = false,
            retractionDistanceWhenCut = 12.5f,
        )
        val options = SliceOptions()
            .selectPrinter(
                PrinterProfile.U1_04.copy(
                    auxiliaryFan = true,
                    fanSpeedupTime = 0.7f,
                    fanSpeedupOverhangs = false,
                    fanKickstart = 0.25f,
                    extruderOffsetsX = listOf(0f, 12.5f),
                    extruderOffsetsY = listOf(0f, -3.25f),
                    beforeLayerChangeGcode = "; PERSISTED_BEFORE_LAYER",
                    layerChangeGcode = "; PERSISTED_AFTER_LAYER",
                    machinePauseGcode = "M25 ; PERSISTED_PAUSE",
                    timeLapseGcode = "; PERSISTED_TIMELAPSE",
                    changeFilamentGcode = "T[next_extruder] ; PERSISTED_TOOL_CHANGE",
                    printingByObjectGcode = "; PERSISTED_BETWEEN_OBJECTS",
                    useRelativeEDistances = false,
                    emitMachineLimitsToGcode = false,
                    manualFilamentChange = true,
                    disableM73 = true,
                    coolingTubeRetraction = 73.5f,
                    coolingTubeLength = 11f,
                    parkingPosRetraction = 80f,
                    extraLoadingMove = -3.5f,
                    enableFilamentRamming = false,
                    rammingLineWidthRatio = 3.25f,
                    changePressureWhenWiping = false,
                    rammingPressureAdvance = 0.17f,
                    purgeInPrimeTower = false,
                    highCurrentOnFilamentSwap = true,
                    supportsChamberTemperatureControl = true,
                    supportsAirFiltration = true,
                    retractLiftAbove = 0.35f,
                    retractLiftBelow = 180f,
                    retractLiftEnforce = "top_bottom",
                    travelSlope = 7f,
                    zHopWhenPrime = false,
                    useFirmwareRetraction = false,
                    longRetractionWhenCutLevel = 2,
                    longRetractionWhenCut = true,
                    retractionDistanceWhenCut = 17f,
                    toolChangeRetractLengths = listOf(1.2f, 2.3f),
                    toolChangeRetractRestartExtras = listOf(-0.1f, 0.2f),
                ),
            )
            .selectFilament(primary)
            .selectBuildPlate(BuildPlateType.TEXTURED_PEI)
            .copy(
                filamentSlots = listOf(primary, secondary),
                filamentColors = listOf(0x123456, 0xABCDEF),
                supportFilament = 1,
                supportInterfaceFilament = 2,
                featureFilaments = FeatureFilamentSettings(
                    infillOverrideEnabled = true,
                    baseFirstLayers = 3,
                    baseLastLayers = 4,
                    sparseInfillFilament = 2,
                    wallFilament = 1,
                    solidInfillFilament = 2,
                    wipeTowerFilament = 1,
                ),
                wipeTowerEnabled = true,
                wipeTowerWidth = 42f,
                multiMaterial = MultiMaterialSettings(
                    primeVolume = 61.5f,
                    purgeVolumes = listOf(0f, 65f, 175f, 0f),
                    flushMultiplierOverrideEnabled = true,
                    flushMultiplier = 1.25f,
                    primeTowerPositionX = 123.5f,
                    primeTowerPositionY = 87.5f,
                    primeTowerBrimWidth = 4.5f,
                    primeTowerBrimChamfer = false,
                    primeTowerBrimChamferMaxWidth = 7.5f,
                    primeTowerFramework = true,
                    primeTowerSkipPoints = false,
                    primeTowerFlatIroning = true,
                    primeTowerInterfaceFeatures = true,
                    primeTowerInterfaceCooldown = true,
                    primeTowerInfillGap = 175f,
                    wipeTowerNoSparseLayers = true,
                    wipeTowerRotationAngle = 73f,
                    wipeTowerBridging = 12.5f,
                    wipeTowerExtraSpacing = 145f,
                    wipeTowerExtraFlow = 118f,
                    wipeTowerMaxPurgeSpeed = 137f,
                    wipeTowerWallType = "rib",
                    wipeTowerConeAngle = 42f,
                    wipeTowerExtraRibLength = 9.5f,
                    wipeTowerRibWidth = 11f,
                    wipeTowerFilletWall = false,
                    singleExtruderMultiMaterialPriming = true,
                    flushIntoInfill = true,
                    flushIntoSupport = false,
                    flushIntoObjects = true,
                    oozePrevention = true,
                    standbyTemperatureDelta = -35,
                    preheatTime = 94.5f,
                    preheatDeltaTemperature = -18,
                    preheatSteps = 7,
                    interfaceShells = true,
                    segmentedRegionMaxWidth = 2.4f,
                    segmentedRegionInterlockingDepth = 0.8f,
                    interlockingBeam = true,
                    interlockingBeamWidth = 1.25f,
                    interlockingOrientation = 67.5f,
                    interlockingBeamLayerCount = 3,
                    interlockingDepth = 4,
                    interlockingBoundaryAvoidance = 1,
                ),
                gcodeSettings = GcodeSettings(
                    arcFitting = true,
                    addLineNumbers = true,
                    labelObjects = false,
                    excludeObjects = true,
                    verboseComments = true,
                    initialLayerTravelSpeed = 35f,
                    initialLayerTravelSpeedPercent = true,
                    slowDownLayers = 4,
                    accelToDecelEnabled = false,
                    accelToDecelFactor = 27f,
                ),
                quality = QualityProfile.STANDARD.copy(
                    surfaceDensity = SurfaceDensitySettings(topPercent = 44f, bottomPercent = 71f),
                    infillShiftStep = 1.7f,
                    symmetricInfillYAxis = true,
                    extrusionRateSmoothing = ExtrusionRateSmoothingSettings(
                        maximumSlope = 20f,
                        segmentLength = 5f,
                        externalOnly = true,
                    ),
                ),
            )

        val restored = requireNotNull(options.toProjectJson().toProjectSliceOptionsOrNull())
        val native = restored.toNativeConfig()

        assertEquals(listOf(primary.id, secondary.id), restored.resolvedFilamentSlots().map { it.id })
        assertEquals(listOf(0x123456, 0xABCDEF), restored.resolvedFilamentColors())
        assertEquals(listOf(0x123456, 0xABCDEF), native.filamentColors.toList())
        assertNull(restored.filamentProfile.retractLength)
        assertNull(restored.filamentProfile.zHopType)
        assertEquals(2, native.extruderCount)
        assertEquals(0.08f, restored.printerProfile.minLayerHeight)
        assertEquals(0.32f, restored.printerProfile.maxLayerHeight)
        assertArrayEquals(floatArrayOf(0.08f, 0.08f), native.minimumLayerHeights, 0.001f)
        assertArrayEquals(floatArrayOf(0.32f, 0.32f), native.maximumLayerHeights, 0.001f)
        assertArrayEquals(floatArrayOf(0f, 12.5f), native.extruderOffsetsX, 0.001f)
        assertArrayEquals(floatArrayOf(0f, -3.25f), native.extruderOffsetsY, 0.001f)
        assertEquals("; PERSISTED_BEFORE_LAYER", native.beforeLayerChangeGcode)
        assertEquals("; PERSISTED_AFTER_LAYER", native.layerChangeGcode)
        assertEquals("M25 ; PERSISTED_PAUSE", native.machinePauseGcode)
        assertEquals("; PERSISTED_TIMELAPSE", native.timeLapseGcode)
        assertEquals("T[next_extruder] ; PERSISTED_TOOL_CHANGE", native.changeFilamentGcode)
        assertEquals("; PERSISTED_BETWEEN_OBJECTS", native.printingByObjectGcode)
        assertFalse(native.useRelativeEDistances)
        assertFalse(native.emitMachineLimitsToGcode)
        assertTrue(native.manualFilamentChange)
        assertTrue(native.disableM73)
        assertEquals(73.5f, native.coolingTubeRetraction)
        assertEquals(11f, native.coolingTubeLength)
        assertEquals(80f, native.parkingPosRetraction)
        assertEquals(-3.5f, native.extraLoadingMove)
        assertFalse(native.enableFilamentRamming)
        assertEquals(3.25f, native.rammingLineWidthRatio)
        assertFalse(native.changePressureWhenWiping)
        assertEquals(0.17f, native.rammingPressureAdvance)
        assertFalse(native.purgeInPrimeTower)
        assertTrue(native.highCurrentOnFilamentSwap)
        assertTrue(native.supportsChamberTemperatureControl)
        assertTrue(native.supportsAirFiltration)
        assertArrayEquals(floatArrayOf(1.2f, 2.3f), native.toolChangeRetractLengths, 0.001f)
        assertArrayEquals(floatArrayOf(-0.1f, 0.2f), native.toolChangeRetractRestartExtras, 0.001f)
        assertArrayEquals(floatArrayOf(0.35f, 1.25f), native.extruderRetractLiftAbove, 0.001f)
        assertArrayEquals(floatArrayOf(180f, 140f), native.extruderRetractLiftBelow, 0.001f)
        assertEquals(listOf("top_bottom", "top"), native.extruderRetractLiftEnforce.toList())
        assertArrayEquals(floatArrayOf(7f, 7f), native.extruderTravelSlope, 0.001f)
        assertEquals(listOf(0, 0), native.extruderZHopWhenPrime.toList())
        assertFalse(native.useFirmwareRetraction)
        assertEquals(2, native.longRetractionWhenCutLevel)
        assertEquals(listOf(1, 0), native.extruderLongRetractionWhenCut.toList())
        assertArrayEquals(
            floatArrayOf(17f, 12.5f),
            native.extruderRetractionDistanceWhenCut,
            0.001f,
        )
        assertEquals(2.85f, restored.filamentDiameter)
        assertEquals(2.85f, restored.filamentProfile.diameter)
        assertEquals(2.85f, native.filamentDiameter)
        assertArrayEquals(floatArrayOf(1.07f, 1.32f), native.filamentDensities, 0.001f)
        assertArrayEquals(floatArrayOf(42.5f, 75f), native.filamentCosts, 0.001f)
        assertArrayEquals(floatArrayOf(99.2f, 98.4f), native.filamentShrinkages, 0.001f)
        assertArrayEquals(
            floatArrayOf(99.18f, 98.8f),
            native.filamentShrinkageCompensationZ,
            0.001f,
        )
        assertEquals(listOf(0, 1), native.filamentSoluble.toList())
        assertEquals(listOf(0, 1), native.filamentIsSupport.toList())
        assertArrayEquals(floatArrayOf(9f, 35f), native.filamentMinimalPurgeOnWipeTower, 0.001f)
        assertArrayEquals(
            floatArrayOf(11f, 21f),
            native.filamentTowerInterfacePreExtrusionDistances,
            0.001f,
        )
        assertArrayEquals(
            floatArrayOf(12f, 22f),
            native.filamentTowerInterfacePreExtrusionLengths,
            0.001f,
        )
        assertArrayEquals(floatArrayOf(13f, 23f), native.filamentTowerIroningAreas, 0.001f)
        assertArrayEquals(
            floatArrayOf(14f, 24f),
            native.filamentTowerInterfacePurgeLengths,
            0.001f,
        )
        assertEquals(listOf(231, 241), native.filamentTowerInterfacePrintTemperatures.toList())
        assertEquals(listOf(40, 70), native.filamentAdditionalCoolingFanSpeeds.toList())
        assertArrayEquals(floatArrayOf(21f, 31f), native.filamentLoadingSpeeds, 0.001f)
        assertArrayEquals(floatArrayOf(4f, 5f), native.filamentLoadingSpeedStarts, 0.001f)
        assertArrayEquals(floatArrayOf(81f, 82f), native.filamentUnloadingSpeeds, 0.001f)
        assertArrayEquals(floatArrayOf(91f, 92f), native.filamentUnloadingSpeedStarts, 0.001f)
        assertArrayEquals(floatArrayOf(0.7f, 1.2f), native.filamentToolchangeDelays, 0.001f)
        assertEquals(listOf(3, 5), native.filamentCoolingMoves.toList())
        assertArrayEquals(floatArrayOf(29f, 0f), native.filamentStampingLoadingSpeeds, 0.001f)
        assertArrayEquals(floatArrayOf(45f, 0f), native.filamentStampingDistances, 0.001f)
        assertArrayEquals(floatArrayOf(2.5f, 3.5f), native.filamentCoolingInitialSpeeds, 0.001f)
        assertArrayEquals(floatArrayOf(4.5f, 5.5f), native.filamentCoolingFinalSpeeds, 0.001f)
        assertEquals(
            listOf("125 95 7 8 9| 0.1 7 0.5 8", "130 90 8 9 10| 0.2 8 0.6 9"),
            native.filamentRammingParameters.toList(),
        )
        assertEquals(listOf(1, 0), native.filamentMultitoolRamming.toList())
        assertArrayEquals(floatArrayOf(6f, 7f), native.filamentMultitoolRammingVolumes, 0.001f)
        assertArrayEquals(floatArrayOf(16f, 17f), native.filamentMultitoolRammingFlows, 0.001f)
        assertEquals(listOf(62, 80), native.filamentSofteningTemperatures.toList())
        assertEquals(listOf(195, 220), native.filamentNozzleTemperatureRangeLows.toList())
        assertEquals(listOf(245, 280), native.filamentNozzleTemperatureRangeHighs.toList())
        assertEquals(listOf(1, 0), native.filamentChamberTemperatureControl.toList())
        assertEquals(listOf(55, 60), native.filamentChamberTemperatures.toList())
        assertEquals(listOf(1, 0), native.filamentAirFiltration.toList())
        assertEquals(listOf(70, 100), native.filamentDuringPrintExhaustFanSpeeds.toList())
        assertEquals(listOf(40, 0), native.filamentCompletePrintExhaustFanSpeeds.toList())
        assertArrayEquals(floatArrayOf(42f, 91f), native.filamentFanCoolingLayerTimes, 0.001f)
        assertEquals(listOf(0, 1), native.filamentSlowDownForLayerCooling.toList())
        assertEquals(listOf(1, 0), native.filamentKeepFanAlwaysOn.toList())
        assertEquals(listOf(1, 0), native.filamentDontSlowDownOuterWall.toList())
        assertEquals(listOf(1, 0), native.filamentEnableOverhangBridgeFan.toList())
        assertEquals(listOf(2, 4), native.filamentOverhangFanThresholds.toList())
        assertEquals(listOf(45, -1), native.filamentInternalBridgeFanSpeeds.toList())
        assertEquals(listOf(85, 65), native.filamentSupportInterfaceFanSpeeds.toList())
        assertEquals(listOf(37, 72), native.filamentIroningFanSpeeds.toList())
        assertEquals(BuildPlateType.TEXTURED_PEI.nativeValue, native.bedType)
        assertEquals(listOf(53, 63), native.filamentBedTemps.toList())
        assertEquals(listOf(54, 64), native.filamentBedTempInitialLayers.toList())
        assertEquals(true, native.auxiliaryFan)
        assertEquals(0.7f, native.fanSpeedupTime)
        assertEquals(false, native.fanSpeedupOverhangs)
        assertEquals(0.25f, native.fanKickstart)
        assertEquals(listOf("PLA", "PETG"), native.filamentTypes.toList())
        assertEquals(listOf(primary.nozzleTemp, secondary.nozzleTemp), native.extruderTemps.toList())
        assertEquals(listOf(primary.flowRatio, secondary.flowRatio), native.filamentFlowRatios.toList())
        assertEquals(
            listOf("M117 PRIMARY_START", "M117 SECONDARY_START"),
            native.filamentStartGcodes.toList(),
        )
        assertEquals(
            listOf("M117 PRIMARY_END", "M117 SECONDARY_END"),
            native.filamentEndGcodes.toList(),
        )
        assertEquals(1, native.supportFilament)
        assertEquals(2, native.supportInterfaceFilament)
        assertEquals(true, native.infillFilamentOverrideEnabled)
        assertEquals(3, native.infillFilamentBaseFirstLayers)
        assertEquals(4, native.infillFilamentBaseLastLayers)
        assertEquals(2, native.sparseInfillFilament)
        assertEquals(1, native.wallFilament)
        assertEquals(2, native.solidInfillFilament)
        assertEquals(1, native.wipeTowerFilament)
        assertEquals(true, native.wipeTowerEnabled)
        assertEquals(42f, native.wipeTowerWidth)
        assertEquals(61.5f, native.primeVolume)
        assertEquals(listOf(0f, 65f, 175f, 0f), native.purgeVolumes.toList())
        assertTrue(native.flushMultiplierOverrideEnabled)
        assertEquals(1.25f, native.flushMultiplier)
        assertEquals(123.5f, native.wipeTowerX)
        assertEquals(87.5f, native.wipeTowerY)
        assertEquals(false, native.singleExtruderMultiMaterial)
        assertEquals(false, native.purgeInPrimeTower)
        assertEquals(4.5f, native.primeTowerBrimWidth)
        assertFalse(native.primeTowerBrimChamfer)
        assertEquals(7.5f, native.primeTowerBrimChamferMaxWidth)
        assertTrue(native.primeTowerFramework)
        assertFalse(native.primeTowerSkipPoints)
        assertTrue(native.primeTowerFlatIroning)
        assertTrue(native.primeTowerInterfaceFeatures)
        assertTrue(native.primeTowerInterfaceCooldown)
        assertEquals(175f, native.primeTowerInfillGap)
        assertEquals(true, native.wipeTowerNoSparseLayers)
        assertEquals(73f, native.wipeTowerRotationAngle)
        assertEquals(12.5f, native.wipeTowerBridging)
        assertEquals(145f, native.wipeTowerExtraSpacing)
        assertEquals(118f, native.wipeTowerExtraFlow)
        assertEquals(137f, native.wipeTowerMaxPurgeSpeed)
        assertEquals("rib", native.wipeTowerWallType)
        assertEquals(42f, native.wipeTowerConeAngle)
        assertEquals(9.5f, native.wipeTowerExtraRibLength)
        assertEquals(11f, native.wipeTowerRibWidth)
        assertEquals(false, native.wipeTowerFilletWall)
        assertEquals(true, native.singleExtruderMultiMaterialPriming)
        assertEquals(true, native.oozePrevention)
        assertEquals(-35, native.standbyTemperatureDelta)
        assertEquals(94.5f, native.preheatTime)
        assertEquals(-18, native.preheatDeltaTemperature)
        assertEquals(7, native.preheatSteps)
        assertEquals(true, native.interfaceShells)
        assertEquals(2.4f, native.segmentedRegionMaxWidth)
        assertEquals(0.8f, native.segmentedRegionInterlockingDepth)
        assertEquals(true, native.interlockingBeam)
        assertEquals(1.25f, native.interlockingBeamWidth)
        assertEquals(67.5f, native.interlockingOrientation)
        assertEquals(3, native.interlockingBeamLayerCount)
        assertEquals(4, native.interlockingDepth)
        assertEquals(1, native.interlockingBoundaryAvoidance)
        assertEquals(20f, native.maxVolumetricExtrusionRateSlope)
        assertEquals(5f, native.maxVolumetricExtrusionRateSlopeSegmentLength)
        assertEquals(true, native.extrusionRateSmoothingExternalOnly)
        assertEquals(false, native.enableArcFitting)
        assertEquals(true, native.gcodeAddLineNumber)
        assertEquals(false, native.gcodeLabelObjects)
        assertEquals(true, native.excludeObject)
        assertEquals(35f, native.initialLayerTravelSpeed)
        assertEquals(true, native.initialLayerTravelSpeedPercent)
        assertEquals(4, native.slowDownLayers)
        assertEquals(false, native.accelToDecelEnabled)
        assertEquals(27f, native.accelToDecelFactor)
    }

    @Test
    fun firmwareRetractionForcesLongCutRetractionOffForEveryTool() {
        val primary = FilamentProfile.PLA.copy(
            longRetractionWhenCut = true,
            retractionDistanceWhenCut = 16f,
        )
        val secondary = FilamentProfile.PETG.copy(
            longRetractionWhenCut = true,
            retractionDistanceWhenCut = 12f,
        )
        val native = SliceOptions()
            .selectPrinter(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    extruderCount = 2,
                    useFirmwareRetraction = true,
                    longRetractionWhenCutLevel = 2,
                    longRetractionWhenCut = true,
                    retractionDistanceWhenCut = 17f,
                ),
            )
            .selectFilament(primary)
            .copy(filamentSlots = listOf(primary, secondary))
            .toNativeConfig()

        assertTrue(native.useFirmwareRetraction)
        assertEquals(2, native.longRetractionWhenCutLevel)
        assertEquals(listOf(0, 0), native.extruderLongRetractionWhenCut.toList())
        assertArrayEquals(
            floatArrayOf(16f, 12f),
            native.extruderRetractionDistanceWhenCut,
            0.001f,
        )
    }

    @Test
    fun selectedBuildPlateAndTemperaturesRoundTripWhileLegacyProjectsKeepHighTempSemantics() {
        val filament = FilamentProfile.GENERIC_PLA.copy(
            bedTemp = 71,
            firstLayerBedTemp = 72,
            coolPlateTemp = 31,
            firstLayerCoolPlateTemp = 32,
        )
        val options = SliceOptions().selectFilament(filament).selectBuildPlate(BuildPlateType.COOL)
        val stored = options.toProjectJson()
        val restored = requireNotNull(stored.toProjectSliceOptionsOrNull())

        assertEquals("cool", stored.getString("buildPlateType"))
        assertEquals(BuildPlateType.COOL, restored.buildPlate.type)
        assertEquals(31, restored.bedTemp)
        assertEquals(32, restored.firstLayerBedTemp)
        assertEquals(BuildPlateType.COOL.nativeValue, restored.toNativeConfig().bedType)
        assertEquals(listOf(31), restored.toNativeConfig().filamentBedTemps.toList())
        assertEquals(listOf(32), restored.toNativeConfig().filamentBedTempInitialLayers.toList())

        val legacy = JSONObject(stored.toString()).apply {
            put("formatVersion", 60)
            remove("buildPlateType")
        }
        val migrated = requireNotNull(legacy.toProjectSliceOptionsOrNull())
        assertEquals(BuildPlateType.HIGH_TEMP, migrated.buildPlate.type)
        assertEquals(71, migrated.bedTemp)
        assertEquals(72, migrated.firstLayerBedTemp)
    }

    @Test
    fun mixedFilamentDiametersAreRejectedBeforeNativeSlicing() {
        val primary = FilamentProfile.GENERIC_PLA.copy(diameter = 1.75f)
        val secondary = FilamentProfile.PETG.copy(diameter = 2.85f)
        val options = SliceOptions().selectFilament(primary)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            options.copy(filamentSlots = listOf(primary, secondary)).toNativeConfig()
        }
    }

    @Test
    fun changingPrimaryDiameterReconcilesExistingSecondarySlots() {
        val first = FilamentProfile.GENERIC_PLA.copy(diameter = 1.75f)
        val second = FilamentProfile.PETG.copy(diameter = 1.75f)
        val replacement = FilamentProfile.GENERIC_ABS.copy(diameter = 2.85f)
        val options = SliceOptions().selectFilament(first).copy(filamentSlots = listOf(first, second))

        val updated = options.selectFilament(replacement)

        assertEquals(
            listOf(replacement.id, replacement.id),
            updated.resolvedFilamentSlots().map { it.id },
        )
        assertEquals(listOf(2.85f, 2.85f), updated.resolvedFilamentSlots().map { it.diameter })
        assertEquals(2.85f, updated.filamentDiameter)
    }

    @Test
    fun filamentColorsFollowSlotLifecycleAndLegacyProjectsReceiveStableDefaults() {
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(extruderCount = 3)
        val base = SliceOptions().selectPrinter(printer)
        val expanded = base
            .addFilamentSlot(FilamentProfile.PETG)
            .updateFilamentColor(0, 0x102030)
            .updateFilamentColor(1, 0xA0B0C0)

        assertEquals(listOf(0x102030, 0xA0B0C0), expanded.resolvedFilamentColors())
        assertEquals(
            listOf(0x102030, 0xA0B0C0, defaultFilamentColor(2)),
            expanded.addFilamentSlot(FilamentProfile.ABS).resolvedFilamentColors(),
        )
        assertEquals(
            listOf(0x102030),
            expanded.removeLastFilamentSlot().resolvedFilamentColors(),
        )

        val legacy = expanded.toProjectJson().apply {
            put("formatVersion", 98)
            remove("filamentColors")
        }
        assertEquals(
            defaultFilamentColors(2),
            requireNotNull(legacy.toProjectSliceOptionsOrNull()).resolvedFilamentColors(),
        )
    }

    @Test
    fun invalidPersistedOrRuntimeFilamentColorsAreRejected() {
        val stored = restoredSettingsFixture().toProjectJson().apply {
            put("filamentColors", org.json.JSONArray().put(MAX_FILAMENT_RGB + 1))
        }
        assertNull(stored.toProjectSliceOptionsOrNull())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SliceOptions().copy(filamentColors = listOf(-1)).toNativeConfig()
        }
    }

    @Test
    fun semmPrinterClassificationRoundTripsAndEnablesDirectedPrimeTowerPurging() {
        val printer = PrinterProfile.CUSTOM_CARTESIAN.copy(
            id = "test-semm-printer",
            name = "Test SEMM printer",
            singleExtruderMultiMaterial = true,
            extruderCount = 2,
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .selectFilament(FilamentProfile.PLA)
            .copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                multiMaterial = MultiMaterialSettings(
                    purgeVolumes = listOf(0f, 65f, 175f, 0f),
                ),
            )

        val restored = requireNotNull(options.toProjectJson().toProjectSliceOptionsOrNull())
        val native = restored.toNativeConfig()

        assertEquals(true, restored.printerProfile.singleExtruderMultiMaterial)
        assertEquals(2, restored.printerProfile.extruderCount)
        assertEquals(true, native.singleExtruderMultiMaterial)
        assertEquals(true, native.purgeInPrimeTower)
        assertEquals(listOf(0f, 65f, 175f, 0f), native.purgeVolumes.toList())
    }

    @Test
    fun effectivePrinterFilamentAndSlicingOverridesRoundTripCanonically() {
        val options = restoredSettingsFixture()
        val stored = options.toProjectJson()

        val restored = stored.toProjectSliceOptionsOrNull()

        requireNotNull(restored)
        assertEquals(stored.toString(), restored.toProjectJson().toString())
        assertEquals(options.printerProfile.id, restored.printerProfile.id)
        assertEquals(options.printerProfile.brand, restored.printerProfile.brand)
        assertEquals(options.printerProfile.builtIn, restored.printerProfile.builtIn)
        assertEquals(options.printerProfile.scanFirstLayer, restored.printerProfile.scanFirstLayer)
        assertEquals(options.printerProfile.bedMeshMinX, restored.printerProfile.bedMeshMinX)
        assertEquals(options.printerProfile.bedMeshMinY, restored.printerProfile.bedMeshMinY)
        assertEquals(options.printerProfile.bedMeshMaxX, restored.printerProfile.bedMeshMaxX)
        assertEquals(options.printerProfile.bedMeshMaxY, restored.printerProfile.bedMeshMaxY)
        assertEquals(
            options.printerProfile.bedMeshProbeDistanceX,
            restored.printerProfile.bedMeshProbeDistanceX,
        )
        assertEquals(
            options.printerProfile.bedMeshProbeDistanceY,
            restored.printerProfile.bedMeshProbeDistanceY,
        )
        assertEquals(
            options.printerProfile.adaptiveBedMeshMargin,
            restored.printerProfile.adaptiveBedMeshMargin,
        )
        assertEquals(
            options.printerProfile.maxJunctionDeviation,
            restored.printerProfile.maxJunctionDeviation,
        )
        assertEquals(options.printerProfile.nozzleHeight, restored.printerProfile.nozzleHeight)
        assertEquals(options.printerProfile.nozzleHeight, restored.toNativeConfig().nozzleHeight)
        assertEquals(options.printerProfile.nozzleVolume, restored.printerProfile.nozzleVolume)
        assertEquals(options.printerProfile.nozzleVolume, restored.toNativeConfig().nozzleVolume)
        assertEquals(options.printerProfile.gcodeThumbnails, restored.printerProfile.gcodeThumbnails)
        assertEquals(options.printerProfile.gcodeThumbnails, restored.toNativeConfig().gcodeThumbnails)
        assertEquals(options.printerProfile.defaultPrintProfile, restored.printerProfile.defaultPrintProfile)
        assertEquals(
            options.printerProfile.defaultFilamentProfiles,
            restored.printerProfile.defaultFilamentProfiles,
        )
        assertEquals(restored.printerProfile.name, restored.toNativeConfig().printerModel)
        assertEquals(options.bedOriginX, restored.bedOriginX)
        assertEquals(options.bedOriginY, restored.bedOriginY)
        assertEquals(options.bedPolygon, restored.bedPolygon)
        assertEquals(options.bedExcludeArea, restored.bedExcludeArea)
        assertEquals(73f, restored.extruderClearanceRadius)
        assertEquals(29f, restored.extruderClearanceHeightToRod)
        assertEquals(117f, restored.extruderClearanceHeightToLid)
        assertEquals(options.filamentProfile.compatiblePrinters, restored.filamentProfile.compatiblePrinters)
        assertEquals(2.85f, restored.filamentProfile.diameter)
        assertEquals(2.85f, restored.filamentDiameter)
        assertEquals(2.85f, restored.toNativeConfig().filamentDiameter)
        assertEquals(options.quality.compatiblePrinters, restored.quality.compatiblePrinters)
        assertEquals(248, restored.nozzleTemp)
        assertEquals(0.64f, restored.outerWallLineWidth)
        assertEquals(0.68f, restored.innerWallLineWidth)
        assertEquals(0.55f, restored.topSurfaceLineWidth)
        assertEquals(0.72f, restored.sparseInfillLineWidth)
        assertEquals(0.61f, restored.internalSolidInfillLineWidth)
        assertEquals(0.58f, restored.supportLineWidth)
        assertEquals(175f, restored.innerWallSpeed)
        assertEquals(17f, restored.travelSpeedZ)
        assertEquals(17f, restored.toNativeConfig().travelSpeedZ)
        assertEquals(
            "{input_filename_base}_{layer_height}mm_{print_time}.gcode",
            restored.gcodeSettings.filenameFormat,
        )
        assertEquals(restored.gcodeSettings.filenameFormat, restored.toNativeConfig().filenameFormat)
        assertEquals(210f, restored.sparseInfillSpeed)
        assertEquals(165f, restored.internalSolidInfillSpeed)
        assertEquals(95f, restored.topSurfaceSpeed)
        assertEquals(85f, restored.supportSpeed)
        assertEquals(43f, restored.bridgeSpeed)
        assertEquals(133f, restored.gapInfillSpeed)
        assertEquals(61f, restored.firstLayerInfillSpeed)
        assertEquals(52f, restored.supportInterfaceSpeed)
        assertEquals(163f, restored.internalBridgeSpeed)
        assertEquals(true, restored.internalBridgeSpeedPercent)
        assertEquals(0.94f, restored.printFlowRatio)
        assertEquals(0.94f, restored.toNativeConfig().printFlowRatio)
        assertEquals(false, restored.overhangSpeedEnabled)
        assertEquals(81f, restored.overhangSpeed1)
        assertEquals(true, restored.overhangSpeed1Percent)
        assertEquals("crosshatch", restored.fillPattern)
        assertEquals("monotonic", restored.topSurfacePattern)
        assertEquals("concentric", restored.bottomSurfacePattern)
        assertEquals("rectilinear", restored.internalSolidInfillPattern)
        assertEquals("nearest", restored.seamPosition)
        assertEquals(
            IroningSettings(
                type = "top",
                pattern = "concentric",
                flow = 13f,
                spacing = 0.17f,
                inset = 0.37f,
                speed = 27f,
                angle = 123f,
            ),
            restored.ironing,
        )
        assertEquals(0.37f, restored.toNativeConfig().ironingInset)
        assertEquals(123f, restored.toNativeConfig().ironingAngle)
        assertEquals("tree(auto)", restored.supportType)
        assertEquals("by object", restored.printSequence)
        assertEquals("as_obj_list", restored.printOrder)
        assertEquals("rectilinear-grid", restored.supportBasePattern)
        assertEquals("rectilinear_interlaced", restored.supportInterfacePattern)
        assertEquals("tree_strong", restored.supportStyle)
        assertEquals(
            FuzzySkinSettings(
                type = "allwalls",
                firstLayer = true,
                pointDistance = 0.65f,
                thickness = 0.28f,
                mode = "combined",
                noiseType = "billow",
                scale = 3.5f,
                octaves = 6,
                persistence = 0.7f,
            ),
            restored.fuzzySkin,
        )
        assertEquals(
            SupportCoverageSettings(
                onBuildPlateOnly = true,
                criticalRegionsOnly = true,
                removeSmallOverhangs = false,
                enforcedLayers = 7,
            ),
            restored.supportCoverage,
        )
        assertEquals(true, restored.toNativeConfig().supportCriticalRegionsOnly)
        assertEquals(false, restored.toNativeConfig().supportRemoveSmallOverhangs)
        assertEquals(7, restored.supportCoverage.enforcedLayers)
        assertEquals(7, restored.toNativeConfig().enforceSupportLayers)
        assertEquals(
            SupportAdvancedSettings(
                patternAngle = 73f,
                thresholdOverlap = 0.33f,
                thresholdOverlapPercent = false,
                objectFirstLayerGap = 0.42f,
                avoidInterfaceFilamentForBase = false,
                ironingEnabled = true,
                ironingPattern = "concentric",
                ironingFlow = 17f,
                ironingSpacing = 0.18f,
            ),
            restored.supportAdvanced,
        )
        assertEquals(73f, restored.toNativeConfig().supportPatternAngle)
        assertEquals(true, restored.toNativeConfig().supportIroning)
        assertEquals(3.2f, restored.supportBasePatternSpacing)
        assertEquals(-0.4f, restored.supportExpansion)
        assertEquals(true, restored.supportInterfaceLoopPattern)
        assertEquals(false, restored.independentSupportLayerHeight)
        assertEquals(47f, restored.treeSupportBranchAngle)
        assertEquals(6.2f, restored.treeSupportBranchDistance)
        assertEquals(2.4f, restored.treeSupportBranchDiameter)
        assertEquals(2, restored.treeSupportWallCount)
        assertEquals(1.3f, restored.treeSupportTipDiameter)
        assertEquals(31f, restored.treeSupportPreferredBranchAngle)
        assertEquals(37f, restored.treeSupportBranchDensity)
        assertEquals(45f, restored.treeSupportOrganicBranchAngle)
        assertEquals(2.2f, restored.treeSupportOrganicBranchDistance)
        assertEquals(3.1f, restored.treeSupportOrganicBranchDiameter)
        assertEquals(10f, restored.treeSupportBranchDiameterAngle)
        assertEquals(false, restored.treeSupportAdaptiveLayerHeight)
        assertEquals(false, restored.treeSupportAutoBrim)
        assertEquals(4.6f, restored.treeSupportBrimWidth)
        assertEquals(1, restored.supportFilament)
        assertEquals(1, restored.supportInterfaceFilament)
        assertEquals(options.featureFilaments, restored.featureFilaments)
        assertEquals(true, restored.toNativeConfig().infillFilamentOverrideEnabled)
        assertEquals(true, restored.wipeTowerEnabled)
        assertEquals(48f, restored.wipeTowerWidth)
        assertEquals(options.multiMaterial, restored.multiMaterial)
        assertEquals(true, restored.toNativeConfig().flushIntoInfill)
        assertEquals(false, restored.toNativeConfig().flushIntoSupport)
        assertEquals(true, restored.toNativeConfig().flushIntoObjects)
        assertEquals(options.gcodeSettings, restored.gcodeSettings)
        assertEquals(true, restored.infillFirst)
        assertEquals(19f, restored.infillWallOverlap)
        assertEquals(31f, restored.topBottomInfillWallOverlap)
        assertEquals(true, restored.infillCombination)
        assertEquals(0.36f, restored.infillCombinationMaxLayerHeight)
        assertEquals(false, restored.infillCombinationMaxLayerHeightPercent)
        assertEquals(37f, restored.infillDirection)
        assertEquals(123f, restored.solidInfillDirection)
        assertEquals("0,60,120", restored.quality.sparseInfillRotationTemplate)
        assertEquals("0,90", restored.quality.solidInfillRotationTemplate)
        assertEquals("5#2", restored.quality.extraSolidInfills)
        assertEquals("0,60,120", restored.toNativeConfig().sparseInfillRotationTemplate)
        assertEquals("0,90", restored.toNativeConfig().solidInfillRotationTemplate)
        assertEquals("5#2", restored.toNativeConfig().extraSolidInfills)
        assertEquals(true, restored.quality.smallAreaFlowCompensation)
        assertEquals(
            "0,0\n0.5,0.6\n10,1",
            restored.quality.smallAreaFlowCompensationModel,
        )
        assertEquals(true, restored.toNativeConfig().smallAreaFlowCompensation)
        assertEquals(
            "0,0\n0.5,0.6\n10,1",
            restored.toNativeConfig().smallAreaFlowCompensationModel,
        )
        assertEquals(4, restored.quality.fillMultiline)
        assertEquals(4, restored.toNativeConfig().fillMultiline)
        assertEquals(LateralInfillSettings(-32f, 57f, 68f), restored.quality.lateralInfill)
        assertEquals(-32f, restored.toNativeConfig().lateralLatticeAngle1)
        assertEquals(57f, restored.toNativeConfig().lateralLatticeAngle2)
        assertEquals(68f, restored.toNativeConfig().infillOverhangAngle)
        assertEquals(true, restored.alignInfillDirectionToModel)
        assertEquals(42f, restored.minimumSparseInfillArea)
        assertEquals(321f, restored.infillAnchor)
        assertEquals(true, restored.infillAnchorPercent)
        assertEquals(17.5f, restored.infillAnchorMax)
        assertEquals(false, restored.infillAnchorMaxPercent)
        assertEquals(31f, restored.quality.skeletonInfillDensity)
        assertEquals(47f, restored.quality.skinInfillDensity)
        assertEquals(3.5f, restored.quality.skinInfillDepth)
        assertEquals(1.25f, restored.quality.infillLockDepth)
        assertEquals(135f, restored.quality.skinInfillLineWidth)
        assertEquals(true, restored.quality.skinInfillLineWidthPercent)
        assertEquals(0.62f, restored.quality.skeletonInfillLineWidth)
        assertEquals(false, restored.quality.skeletonInfillLineWidthPercent)
        assertEquals("everywhere", restored.gapFillTarget)
        assertEquals(0.9f, restored.filterOutGapFill)
        assertEquals(true, restored.reduceCrossingWall)
        assertEquals(155f, restored.maxTravelDetourDistance)
        assertEquals(true, restored.maxTravelDetourDistancePercent)
        assertEquals(true, restored.reduceInfillRetraction)
        assertEquals(87f, restored.bridgeDensity)
        assertEquals(73f, restored.internalBridgeDensity)
        assertEquals(17f, restored.bridgeAngle)
        assertEquals(103f, restored.internalBridgeAngle)
        assertEquals(true, restored.bridgeNoSupport)
        assertEquals(true, restored.thickBridges)
        assertEquals(false, restored.thickInternalBridges)
        assertEquals("apply_to_all", restored.extraBridgeLayer)
        assertEquals("limited", restored.internalBridgeFilter)
        assertEquals(0.91f, restored.bridgeFlowRatio)
        assertEquals(0.96f, restored.internalBridgeFlowRatio)
        assertEquals(0.97f, restored.topSurfaceFlowRatio)
        assertEquals(0.98f, restored.bottomSurfaceFlowRatio)
        assertEquals(0.86f, restored.supportFlowRatio)
        assertEquals(1.14f, restored.supportInterfaceFlowRatio)
        assertEquals(0.86f, restored.toNativeConfig().supportFlowRatio)
        assertEquals(1.14f, restored.toNativeConfig().supportInterfaceFlowRatio)
        assertEquals(0.8f, restored.topShellThickness)
        assertEquals(0.7f, restored.bottomShellThickness)
        assertEquals(44f, restored.quality.surfaceDensity.topPercent)
        assertEquals(71f, restored.quality.surfaceDensity.bottomPercent)
        assertEquals(44f, restored.toNativeConfig().topSurfaceDensity)
        assertEquals(71f, restored.toNativeConfig().bottomSurfaceDensity)
        assertEquals(1.7f, restored.quality.infillShiftStep)
        assertEquals(true, restored.quality.symmetricInfillYAxis)
        assertEquals(1.7f, restored.toNativeConfig().infillShiftStep)
        assertEquals(true, restored.toNativeConfig().symmetricInfillYAxis)
        assertEquals(4, restored.supportInterfaceTopLayers)
        assertEquals(2, restored.supportInterfaceBottomLayers)
        assertEquals(0.24f, restored.supportInterfaceSpacing)
        assertEquals(0.28f, restored.supportBottomInterfaceSpacing)
        assertEquals(0.18f, restored.supportTopZDistance)
        assertEquals(0.22f, restored.supportBottomZDistance)
        assertEquals(0.4f, restored.supportObjectXYDistance)
        assertEquals(0.74f, restored.initialLayerLineWidth)
        assertEquals(4_000f, restored.defaultAcceleration)
        assertEquals(2_000f, restored.outerWallAcceleration)
        assertEquals(3_500f, restored.innerWallAcceleration)
        assertEquals(1_200f, restored.topSurfaceAcceleration)
        assertEquals(5_000f, restored.travelAcceleration)
        assertEquals(600f, restored.firstLayerAcceleration)
        assertEquals(37f, restored.firstLayerTravelAcceleration)
        assertEquals(true, restored.firstLayerTravelAccelerationPercent)
        assertEquals(37f, restored.toNativeConfig().firstLayerTravelAcceleration)
        assertEquals(true, restored.toNativeConfig().firstLayerTravelAccelerationPercent)
        assertEquals(47f, restored.bridgeAcceleration)
        assertEquals(true, restored.bridgeAccelerationPercent)
        assertEquals(4_321f, restored.sparseInfillAcceleration)
        assertEquals(false, restored.sparseInfillAccelerationPercent)
        assertEquals(83f, restored.internalSolidInfillAcceleration)
        assertEquals(true, restored.internalSolidInfillAccelerationPercent)
        assertEquals(8.5f, restored.defaultJerk)
        assertEquals(7.5f, restored.outerWallJerk)
        assertEquals(8f, restored.innerWallJerk)
        assertEquals(6.5f, restored.topSurfaceJerk)
        assertEquals(9.5f, restored.infillJerk)
        assertEquals(5.5f, restored.firstLayerJerk)
        assertEquals(12.5f, restored.travelJerk)
        assertEquals(12.5f, restored.toNativeConfig().travelJerk)
        assertEquals("classic", restored.wallGenerator)
        assertEquals(135f, restored.wallTransitionLength)
        assertEquals(31f, restored.wallTransitionFilterDeviation)
        assertEquals(24f, restored.wallTransitionAngle)
        assertEquals(3, restored.wallDistributionCount)
        assertEquals(22f, restored.minimumFeatureSize)
        assertEquals(
            PrecisionSettings(
                mode = "close_holes",
                closingRadius = 0.125f,
                preciseZHeight = true,
                polyholes = PolyholeSettings(
                    enabled = true,
                    detectionMargin = 6.5f,
                    detectionMarginPercent = true,
                    twist = false,
                ),
                minimumWallWidth = 74f,
                firstLayerMinimumWallWidth = 116f,
                printableOverhangs = PrintableOverhangSettings(
                    enabled = true,
                    maximumAngle = 63f,
                    holeArea = 240f,
                ),
                brimEars = BrimEarSettings(maximumAngle = 132f, detectionRadius = 1.7f),
            ),
            restored.precision,
        )
        assertEquals(74f, restored.toNativeConfig().minimumWallWidth)
        assertEquals(116f, restored.toNativeConfig().firstLayerMinimumWallWidth)
        assertEquals(0.75f, restored.minimumWallLengthFactor)
        assertEquals("outer-inner", restored.wallSequence)
        assertEquals("cw", restored.wallDirection)
        assertEquals(77f, restored.smallPerimeterSpeed)
        assertEquals(false, restored.smallPerimeterSpeedPercent)
        assertEquals(6.5f, restored.smallPerimeterThreshold)
        assertEquals(false, restored.slowdownForCurledPerimeters)
        assertEquals(0.023f, restored.resolution)
        assertEquals("close_holes", restored.toNativeConfig().slicingMode)
        assertEquals(0.125f, restored.toNativeConfig().sliceClosingRadius)
        assertEquals(true, restored.toNativeConfig().preciseZHeight)
        assertEquals(true, restored.toNativeConfig().holeToPolyhole)
        assertEquals(6.5f, restored.toNativeConfig().holeToPolyholeThreshold)
        assertEquals(true, restored.toNativeConfig().holeToPolyholeThresholdPercent)
        assertEquals(false, restored.toNativeConfig().holeToPolyholeTwisted)
        assertEquals(
            PrintableOverhangSettings(enabled = true, maximumAngle = 63f, holeArea = 240f),
            restored.printableOverhangs,
        )
        assertEquals(true, restored.toNativeConfig().makeOverhangPrintable)
        assertEquals(63f, restored.toNativeConfig().makeOverhangPrintableAngle)
        assertEquals(240f, restored.toNativeConfig().makeOverhangPrintableHoleSize)
        assertEquals(true, restored.staggeredInnerSeams)
        assertEquals(3.5f, restored.seamGap)
        assertEquals(true, restored.seamGapPercent)
        assertEquals(
            ScarfSeamSettings(
                type = "all",
                conditional = true,
                angleThreshold = 142,
                overhangThreshold = 37f,
                speed = 63f,
                speedPercent = true,
                flowRatio = 0.92f,
                startHeight = 18f,
                startHeightPercent = true,
                entireLoop = true,
                length = 24.5f,
                steps = 13,
                innerWalls = true,
            ),
            restored.scarfSeam,
        )
        assertEquals("all", restored.toNativeConfig().scarfSeamType)
        assertEquals(0.92f, restored.toNativeConfig().scarfJointFlowRatio)
        assertEquals(true, restored.wipeBeforeExternalLoop)
        assertEquals(true, restored.wipeOnLoops)
        assertEquals(false, restored.roleBasedWipeSpeed)
        assertEquals(66f, restored.wipeSpeed)
        assertEquals(false, restored.wipeSpeedPercent)
        assertEquals(true, restored.onlyOneWallFirstLayer)
        assertEquals(true, restored.extraPerimetersOnOverhangs)
        assertEquals(275f, restored.minWidthTopSurface)
        assertEquals(true, restored.minWidthTopSurfacePercent)
        assertEquals(true, restored.overhangReverse)
        assertEquals(true, restored.overhangReverseInternalOnly)
        assertEquals(0.8f, restored.overhangReverseThreshold)
        assertEquals(false, restored.overhangReverseThresholdPercent)
        assertEquals("partiallybridge", restored.counterboreHoleBridging)
        assertEquals(true, restored.alternateExtraWall)
        assertEquals("ensure_moderate", restored.ensureVerticalShellThickness)
        assertEquals(false, restored.detectNarrowInternalSolidInfill)
        assertEquals(0.11f, restored.xyHoleCompensation)
        assertEquals(-0.07f, restored.xyContourCompensation)
        assertEquals(0.23f, restored.elephantFootCompensation)
        assertEquals(3, restored.elephantFootCompensationLayers)
        assertEquals(26f, restored.maxBridgeLength)
        assertEquals(true, restored.spiralMode)
        assertEquals(true, restored.spiralModeSmooth)
        assertEquals(2.5f, restored.spiralModeMaxXySmoothing)
        assertEquals(false, restored.spiralModeMaxXySmoothingPercent)
        assertEquals(0.35f, restored.spiralStartingFlowRatio)
        assertEquals(0.2f, restored.spiralFinishingFlowRatio)
        assertEquals("perobject", restored.quality.skirtType)
        assertEquals("perobject", restored.toNativeConfig().skirtType)
        assertEquals(2, restored.skirtLoops)
        assertEquals(8f, restored.skirtDistance)
        assertEquals(-25f, restored.quality.skirtStartAngle)
        assertEquals(-25f, restored.toNativeConfig().skirtStartAngle)
        assertEquals(3, restored.skirtHeight)
        assertEquals(57f, restored.skirtSpeed)
        assertEquals(12f, restored.minimumSkirtLength)
        assertEquals("enabled", restored.draftShield)
        assertEquals(true, restored.quality.singleLoopDraftShield)
        assertEquals(true, restored.toNativeConfig().singleLoopDraftShield)
        assertEquals("inner_only", restored.brimType)
        assertEquals(5f, restored.brimWidth)
        assertEquals(0.15f, restored.brimObjectGap)
        assertEquals(132f, restored.precision.brimEars.maximumAngle)
        assertEquals(1.7f, restored.precision.brimEars.detectionRadius)
        assertEquals(2, restored.raftLayers)
        assertEquals(0.13f, restored.raftContactDistance)
        assertEquals(2.5f, restored.raftExpansion)
        assertEquals(86f, restored.raftFirstLayerDensity)
        assertEquals(3.5f, restored.raftFirstLayerExpansion)
        assertEquals("reprapfirmware", restored.gcodeFlavor)
        assertEquals(4_600f, restored.maxAccelerationTravel)
    }

    @Test
    fun legacyProjectDefaultsPrintableOverhangGeometrySafely() {
        val json = SliceOptions().toProjectJson().apply {
            put("formatVersion", 1)
            put("filamentDiameter", 2.85)
            getJSONObject("filament").remove("diameter")
            getJSONObject("filament").remove("density")
            getJSONObject("filament").remove("costPerKilogram")
            getJSONObject("filament").remove("shrinkageXyPercent")
            getJSONObject("filament").remove("shrinkageZPercent")
            getJSONObject("filament").remove("soluble")
            getJSONObject("filament").remove("supportMaterial")
            getJSONObject("filament").remove("minimalPurgeOnWipeTower")
            getJSONObject("filament").removePrimeTowerInterfaceFields()
            getJSONObject("filament").remove("additionalCoolingFanSpeed")
            getJSONObject("filament").removeCoolingParityFields()
            getJSONObject("printer").remove("auxiliaryFan")
            getJSONObject("printer").remove("fanSpeedupTime")
            getJSONObject("printer").remove("fanSpeedupOverhangs")
            getJSONObject("printer").remove("fanKickstart")
            getJSONObject("printer").remove("minLayerHeight")
            getJSONObject("printer").remove("maxLayerHeight")
            getJSONObject("printer").remove("extruderOffsetsX")
            getJSONObject("printer").remove("extruderOffsetsY")
            getJSONObject("printer").remove("beforeLayerChangeGcode")
            getJSONObject("printer").remove("layerChangeGcode")
            getJSONObject("printer").remove("changeFilamentGcode")
            getJSONObject("printer").remove("printingByObjectGcode")
            getJSONObject("printer").remove("useRelativeEDistances")
            getJSONObject("printer").remove("emitMachineLimitsToGcode")
            getJSONObject("printer").remove("manualFilamentChange")
            getJSONObject("printer").remove("disableM73")
            getJSONObject("printer").remove("coolingTubeRetraction")
            getJSONObject("printer").remove("coolingTubeLength")
            getJSONObject("printer").remove("parkingPosRetraction")
            getJSONObject("printer").remove("extraLoadingMove")
            getJSONObject("printer").remove("enableFilamentRamming")
            getJSONObject("printer").remove("purgeInPrimeTower")
            getJSONObject("printer").remove("highCurrentOnFilamentSwap")
            getJSONObject("printer").remove("toolChangeRetractLengths")
            getJSONObject("printer").remove("toolChangeRetractRestartExtras")
            getJSONArray("filamentSlots").getJSONObject(0).remove("diameter")
            getJSONArray("filamentSlots").getJSONObject(0).remove("density")
            getJSONArray("filamentSlots").getJSONObject(0).remove("costPerKilogram")
            getJSONArray("filamentSlots").getJSONObject(0).remove("shrinkageXyPercent")
            getJSONArray("filamentSlots").getJSONObject(0).remove("shrinkageZPercent")
            getJSONArray("filamentSlots").getJSONObject(0).remove("soluble")
            getJSONArray("filamentSlots").getJSONObject(0).remove("supportMaterial")
            getJSONArray("filamentSlots").getJSONObject(0).remove("minimalPurgeOnWipeTower")
            getJSONArray("filamentSlots").getJSONObject(0).removePrimeTowerInterfaceFields()
            getJSONArray("filamentSlots").getJSONObject(0).remove("additionalCoolingFanSpeed")
            getJSONArray("filamentSlots").getJSONObject(0).removeCoolingParityFields()
            getJSONObject("slicing").apply {
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
        }

        val restored = requireNotNull(json.toProjectSliceOptionsOrNull())

        assertEquals(PrintableOverhangSettings(), restored.printableOverhangs)
        assertEquals(false, restored.toNativeConfig().makeOverhangPrintable)
        assertEquals(55f, restored.toNativeConfig().makeOverhangPrintableAngle)
        assertEquals(0f, restored.toNativeConfig().makeOverhangPrintableHoleSize)
        assertEquals(PolyholeSettings(), restored.precision.polyholes)
        assertEquals(false, restored.toNativeConfig().holeToPolyhole)
        assertEquals(0.01f, restored.toNativeConfig().holeToPolyholeThreshold)
        assertEquals(false, restored.toNativeConfig().holeToPolyholeThresholdPercent)
        assertEquals(true, restored.toNativeConfig().holeToPolyholeTwisted)
        assertEquals(0, restored.gcodeSettings.slowDownLayers)
        assertEquals(0, restored.toNativeConfig().slowDownLayers)
        assertEquals(ExtrusionRateSmoothingSettings(), restored.quality.extrusionRateSmoothing)
        assertEquals(25f, restored.quality.skeletonInfillDensity)
        assertEquals(25f, restored.quality.skinInfillDensity)
        assertEquals(2f, restored.quality.skinInfillDepth)
        assertEquals(1f, restored.quality.infillLockDepth)
        assertEquals(100f, restored.quality.skinInfillLineWidth)
        assertEquals(true, restored.quality.skinInfillLineWidthPercent)
        assertEquals(100f, restored.quality.skeletonInfillLineWidth)
        assertEquals(true, restored.quality.skeletonInfillLineWidthPercent)
        assertEquals(-135f, restored.quality.skirtStartAngle)
        assertEquals(-135f, restored.toNativeConfig().skirtStartAngle)
        assertEquals(false, restored.gcodeSettings.verboseComments)
        assertEquals(false, restored.toNativeConfig().gcodeComments)
        assertEquals(DEFAULT_GCODE_FILENAME_FORMAT, restored.gcodeSettings.filenameFormat)
        assertEquals(DEFAULT_GCODE_FILENAME_FORMAT, restored.toNativeConfig().filenameFormat)
        assertEquals(SurfaceDensitySettings(), restored.quality.surfaceDensity)
        assertEquals(100f, restored.toNativeConfig().topSurfaceDensity)
        assertEquals(100f, restored.toNativeConfig().bottomSurfaceDensity)
        assertEquals(0.4f, restored.quality.infillShiftStep)
        assertEquals(false, restored.quality.symmetricInfillYAxis)
        assertEquals(0.4f, restored.toNativeConfig().infillShiftStep)
        assertEquals(false, restored.toNativeConfig().symmetricInfillYAxis)
        assertEquals(2.85f, restored.filamentProfile.diameter)
        assertEquals(1.24f, restored.filamentProfile.density)
        assertEquals(0f, restored.filamentProfile.costPerKilogram)
        assertEquals(false, restored.filamentProfile.soluble)
        assertEquals(false, restored.filamentProfile.supportMaterial)
        assertEquals(15f, restored.filamentProfile.minimalPurgeOnWipeTower)
        assertEquals(10f, restored.filamentProfile.towerInterfacePreExtrusionDistance)
        assertEquals(0f, restored.filamentProfile.towerInterfacePreExtrusionLength)
        assertEquals(4f, restored.filamentProfile.towerIroningArea)
        assertEquals(20f, restored.filamentProfile.towerInterfacePurgeLength)
        assertEquals(-1, restored.filamentProfile.towerInterfacePrintTemperature)
        assertEquals(0, restored.filamentProfile.additionalCoolingFanSpeed)
        assertEquals(60f, restored.filamentProfile.fanCoolingLayerTime)
        assertEquals(true, restored.filamentProfile.slowDownForLayerCooling)
        assertEquals(false, restored.filamentProfile.keepFanAlwaysOn)
        assertEquals(false, restored.filamentProfile.dontSlowDownOuterWall)
        assertEquals(true, restored.filamentProfile.enableOverhangBridgeFan)
        assertEquals("95%", restored.filamentProfile.overhangFanThreshold)
        assertEquals(-1, restored.filamentProfile.internalBridgeFanSpeed)
        assertEquals(-1, restored.filamentProfile.supportInterfaceFanSpeed)
        assertEquals(-1, restored.filamentProfile.ironingFanSpeed)
        assertEquals(false, restored.printerProfile.auxiliaryFan)
        assertEquals(0f, restored.printerProfile.fanSpeedupTime)
        assertEquals(true, restored.printerProfile.fanSpeedupOverhangs)
        assertEquals(0f, restored.printerProfile.fanKickstart)
        assertEquals(0.04f, restored.printerProfile.minLayerHeight)
        assertEquals(0.28f, restored.printerProfile.maxLayerHeight)
        assertEquals(listOf(0f), restored.printerProfile.extruderOffsetsX)
        assertEquals(listOf(0f), restored.printerProfile.extruderOffsetsY)
        assertEquals("", restored.printerProfile.beforeLayerChangeGcode)
        assertEquals("", restored.printerProfile.layerChangeGcode)
        assertEquals("", restored.printerProfile.changeFilamentGcode)
        assertEquals("", restored.printerProfile.printingByObjectGcode)
        assertTrue(restored.printerProfile.useRelativeEDistances)
        assertTrue(restored.printerProfile.emitMachineLimitsToGcode)
        assertFalse(restored.printerProfile.manualFilamentChange)
        assertFalse(restored.printerProfile.disableM73)
        assertEquals(91.5f, restored.printerProfile.coolingTubeRetraction)
        assertEquals(5f, restored.printerProfile.coolingTubeLength)
        assertEquals(92f, restored.printerProfile.parkingPosRetraction)
        assertEquals(-2f, restored.printerProfile.extraLoadingMove)
        assertTrue(restored.printerProfile.enableFilamentRamming)
        assertTrue(restored.printerProfile.purgeInPrimeTower)
        assertFalse(restored.printerProfile.highCurrentOnFilamentSwap)
        assertEquals(listOf(0.8f), restored.printerProfile.toolChangeRetractLengths)
        assertEquals(listOf(0f), restored.printerProfile.toolChangeRetractRestartExtras)
        assertEquals(2.85f, restored.toNativeConfig().filamentDiameter)
        assertEquals(listOf(1.24f), restored.toNativeConfig().filamentDensities.toList())
        assertEquals(listOf(0f), restored.toNativeConfig().filamentCosts.toList())
        assertEquals(listOf(0), restored.toNativeConfig().filamentSoluble.toList())
        assertEquals(listOf(0), restored.toNativeConfig().filamentIsSupport.toList())
        assertEquals(listOf(15f), restored.toNativeConfig().filamentMinimalPurgeOnWipeTower.toList())
        assertEquals(listOf(0), restored.toNativeConfig().filamentAdditionalCoolingFanSpeeds.toList())
        assertEquals(listOf(60f), restored.toNativeConfig().filamentFanCoolingLayerTimes.toList())
        assertEquals(listOf(1), restored.toNativeConfig().filamentSlowDownForLayerCooling.toList())
        assertEquals(listOf(0), restored.toNativeConfig().filamentKeepFanAlwaysOn.toList())
        assertEquals(listOf(0), restored.toNativeConfig().filamentDontSlowDownOuterWall.toList())
        assertEquals(listOf(1), restored.toNativeConfig().filamentEnableOverhangBridgeFan.toList())
        assertEquals(listOf(5), restored.toNativeConfig().filamentOverhangFanThresholds.toList())
        assertEquals(listOf(-1), restored.toNativeConfig().filamentInternalBridgeFanSpeeds.toList())
        assertEquals(listOf(-1), restored.toNativeConfig().filamentSupportInterfaceFanSpeeds.toList())
        assertEquals(listOf(-1), restored.toNativeConfig().filamentIroningFanSpeeds.toList())
        assertEquals(listOf(100f), restored.toNativeConfig().filamentShrinkages.toList())
        assertEquals(
            listOf(100f),
            restored.toNativeConfig().filamentShrinkageCompensationZ.toList(),
        )
        assertEquals(false, restored.toNativeConfig().auxiliaryFan)
        assertEquals(0f, restored.toNativeConfig().fanSpeedupTime)
        assertEquals(true, restored.toNativeConfig().fanSpeedupOverhangs)
        assertEquals(0f, restored.toNativeConfig().fanKickstart)
        assertEquals(listOf(0.04f), restored.toNativeConfig().minimumLayerHeights.toList())
        assertEquals(listOf(0.28f), restored.toNativeConfig().maximumLayerHeights.toList())
        assertEquals(listOf(0f), restored.toNativeConfig().extruderOffsetsX.toList())
        assertEquals(listOf(0f), restored.toNativeConfig().extruderOffsetsY.toList())
        assertEquals(listOf(0.8f), restored.toNativeConfig().toolChangeRetractLengths.toList())
        assertEquals(listOf(0f), restored.toNativeConfig().toolChangeRetractRestartExtras.toList())
        assertEquals(0f, restored.travelSpeedZ)
        assertEquals(0f, restored.toNativeConfig().travelSpeedZ)
        assertEquals(emptyList<Float>(), restored.multiMaterial.purgeVolumes)
        assertFalse(restored.multiMaterial.flushMultiplierOverrideEnabled)
        assertEquals(0.3f, restored.multiMaterial.flushMultiplier)
        assertFalse(restored.multiMaterial.primeTowerFramework)
        assertTrue(restored.multiMaterial.primeTowerSkipPoints)
        assertFalse(restored.multiMaterial.primeTowerFlatIroning)
        assertFalse(restored.multiMaterial.primeTowerInterfaceFeatures)
        assertFalse(restored.multiMaterial.primeTowerInterfaceCooldown)
        assertEquals(150f, restored.multiMaterial.primeTowerInfillGap)
        assertEquals(170f, restored.multiMaterial.primeTowerPositionX)
        assertEquals(140f, restored.multiMaterial.primeTowerPositionY)
        assertTrue(restored.multiMaterial.primeTowerBrimChamfer)
        assertEquals(4f, restored.multiMaterial.primeTowerBrimChamferMaxWidth)
        assertEquals(listOf(0f), restored.toNativeConfig().purgeVolumes.toList())
    }

    @Test
    fun unsafeOrUnknownStoredSettingsAreIgnored() {
        val unsafe = restoredSettingsFixture().toProjectJson()
        unsafe.getJSONObject("printer").put("maxSpeedX", 0)
        assertNull(unsafe.toProjectSliceOptionsOrNull())

        val unknown = JSONObject(restoredSettingsFixture().toProjectJson().toString())
            .put("formatVersion", 100)
        assertNull(unknown.toProjectSliceOptionsOrNull())
    }

    @Test
    fun filamentGcodeTemplateLimitCountsUtf8Bytes() {
        val unsafe = FilamentProfile.GENERIC_PLA.copy(
            filamentStartGcode = "한".repeat(87_382),
        )

        assertFalse(ProfileValidation.filament(unsafe))
        assertFalse(
            ProfileValidation.filament(FilamentProfile.GENERIC_PLA.copy(diameter = 4.01f)),
        )
        assertFalse(
            ProfileValidation.filament(FilamentProfile.GENERIC_PLA.copy(density = 10.01f)),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(costPerKilogram = 1_000_000.1f),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(shrinkageXyPercent = 9.9f),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(shrinkageZPercent = 200.1f),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(minimalPurgeOnWipeTower = 1_000.1f),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(additionalCoolingFanSpeed = 101),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(fanCoolingLayerTime = 1_000.1f),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(overhangFanThreshold = "33%"),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(internalBridgeFanSpeed = 101),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                FilamentProfile.GENERIC_PLA.copy(supportInterfaceFanSpeed = -2),
            ),
        )
    }

    @Test
    fun printerLifecycleGcodeTemplateLimitCountsUtf8Bytes() {
        val unsafe = PrinterProfile.CUSTOM_CARTESIAN.copy(
            changeFilamentGcode = "한".repeat(87_382),
        )

        assertFalse(ProfileValidation.printer(unsafe))
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(fanSpeedupTime = 60.1f),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(fanKickstart = Float.NaN),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    timeLapseGcode = "한".repeat(87_382),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    beforeLayerChangeGcode = "한".repeat(87_382),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    layerChangeGcode = "한".repeat(87_382),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    printingByObjectGcode = "한".repeat(87_382),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(defaultPrintProfile = "x".repeat(513)),
            ),
        )
        assertFalse(
            ProfileValidation.printer(
                PrinterProfile.CUSTOM_CARTESIAN.copy(
                    defaultFilamentProfiles = List(MAX_FILAMENT_SLOTS + 1) { "PLA $it" },
                ),
            ),
        )
    }
}

private fun JSONObject.removeCoolingParityFields() {
    remove("fanCoolingLayerTime")
    remove("slowDownForLayerCooling")
    remove("keepFanAlwaysOn")
    remove("dontSlowDownOuterWall")
    remove("enableOverhangBridgeFan")
    remove("overhangFanThreshold")
    remove("internalBridgeFanSpeed")
    remove("supportInterfaceFanSpeed")
    remove("ironingFanSpeed")
}

private fun JSONObject.removePrimeTowerInterfaceFields() {
    remove("towerInterfacePreExtrusionDistance")
    remove("towerInterfacePreExtrusionLength")
    remove("towerIroningArea")
    remove("towerInterfacePurgeLength")
    remove("towerInterfacePrintTemperature")
}

internal fun restoredSettingsFixture(): SliceOptions = SliceOptions()
    .selectPrinter(
        PrinterProfile.U1_06.copy(
            auxiliaryFan = true,
            fanSpeedupTime = 0.7f,
            fanSpeedupOverhangs = false,
            fanKickstart = 0.25f,
            extruderOffsetsX = listOf(0f, 10.5f),
            extruderOffsetsY = listOf(0f, -2.5f),
            timeLapseGcode = "; FIXTURE_TIMELAPSE",
            beforeLayerChangeGcode = "; FIXTURE_BEFORE_LAYER",
            layerChangeGcode = "; FIXTURE_AFTER_LAYER",
            changeFilamentGcode = "T[next_extruder] ; FIXTURE_TOOL_CHANGE",
            printingByObjectGcode = "; FIXTURE_BETWEEN_OBJECTS",
            useRelativeEDistances = false,
            emitMachineLimitsToGcode = false,
            manualFilamentChange = true,
            disableM73 = true,
            coolingTubeRetraction = 73.5f,
            coolingTubeLength = 11f,
            parkingPosRetraction = 80f,
            extraLoadingMove = -3.5f,
            enableFilamentRamming = false,
            purgeInPrimeTower = false,
            highCurrentOnFilamentSwap = true,
            scanFirstLayer = true,
            bedMeshMinX = 10f,
            bedMeshMinY = 11f,
            bedMeshMaxX = 290f,
            bedMeshMaxY = 291f,
            bedMeshProbeDistanceX = 40f,
            bedMeshProbeDistanceY = 41f,
            adaptiveBedMeshMargin = 5f,
            maxJunctionDeviation = 0.032f,
            nozzleHeight = 4.76f,
            nozzleVolume = 143f,
            gcodeThumbnails = "64x64/PNG,400x300/QOI",
            toolChangeRetractLengths = listOf(1.4f, 2.6f),
            toolChangeRetractRestartExtras = listOf(-0.2f, 0.3f),
            defaultPrintProfile = "Fixture process",
            defaultFilamentProfiles = listOf("Fixture primary", "Fixture secondary"),
        ),
    )
    .selectFilament(
        FilamentProfile.PETG.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_06.name),
            filamentStartGcode = "M117 PRIMARY_START",
            filamentEndGcode = "M117 PRIMARY_END",
            diameter = 2.85f,
            density = 1.07f,
            costPerKilogram = 42.5f,
            shrinkageXyPercent = 99.2f,
            shrinkageZPercent = 99.18f,
            minimalPurgeOnWipeTower = 9f,
            towerInterfacePreExtrusionDistance = 11f,
            towerInterfacePreExtrusionLength = 12f,
            towerIroningArea = 13f,
            towerInterfacePurgeLength = 14f,
            towerInterfacePrintTemperature = 231,
            additionalCoolingFanSpeed = 40,
            fanCoolingLayerTime = 42f,
            slowDownForLayerCooling = false,
            keepFanAlwaysOn = true,
            dontSlowDownOuterWall = true,
            enableOverhangBridgeFan = true,
            overhangFanThreshold = "25%",
            internalBridgeFanSpeed = 45,
            supportInterfaceFanSpeed = 85,
            retractLength = 1.1f,
            retractSpeed = 37f,
            deretractSpeed = 35f,
            retractionMinimumTravel = 1f,
            retractWhenChangingLayer = false,
            wipeWhileRetracting = false,
            wipeDistance = 0f,
            retractBeforeWipe = 0f,
            retractRestartExtra = 0f,
            zHop = 0.4f,
            zHopType = "auto",
        ),
    )
    .selectQuality(
        QualityProfile.FINE_06.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_06.name),
        ),
    )
    .copy(
        bedSizeX = 278f,
        bedSizeY = 282f,
        bedOriginX = -139f,
        bedOriginY = -141f,
        bedPolygon = listOf(139f, 0f, 278f, 141f, 139f, 282f, 0f, 141f),
        bedExcludeArea = listOf(0f, 0f, 18f, 0f, 18f, 28f, 0f, 28f),
        maxPrintHeight = 290f,
        extruderClearanceRadius = 73f,
        extruderClearanceHeightToRod = 29f,
        extruderClearanceHeightToLid = 117f,
        nozzleTemp = 248,
        firstLayerNozzleTemp = 252,
        buildPlate = BuildPlateSettings(
            type = BuildPlateType.HIGH_TEMP,
            temperature = 74,
            firstLayerTemperature = 78,
        ),
        flowRatio = 0.97f,
        maxVolumetricSpeed = 11f,
        fanMinSpeed = 35,
        fanMaxSpeed = 72,
        overhangFanSpeed = 88,
        slowDownLayerTime = 9f,
        slowDownMinSpeed = 14f,
        closeFanFirstLayers = 2,
        fullFanSpeedLayer = 5,
        pressureAdvanceEnabled = true,
        pressureAdvance = 0.034f,
        layerHeight = 0.24f,
        firstLayerHeight = 0.3f,
        perimeters = 4,
        fillDensity = 0.27f,
        printSpeed = 145f,
        topSolidLayers = 7,
        bottomSolidLayers = 6,
        fillPattern = "crosshatch",
        topSurfacePattern = "monotonic",
        bottomSurfacePattern = "concentric",
        internalSolidInfillPattern = "rectilinear",
        travelSpeed = 410f,
        quality = QualityProfile.FINE_06.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_06.name),
            surfaceDensity = SurfaceDensitySettings(topPercent = 44f, bottomPercent = 71f),
            fillMultiline = 4,
            lateralInfill = LateralInfillSettings(-32f, 57f, 68f),
            infillShiftStep = 1.7f,
            symmetricInfillYAxis = true,
            sparseInfillRotationTemplate = "0,60,120",
            solidInfillRotationTemplate = "0,90",
            extraSolidInfills = "5#2",
            smallAreaFlowCompensation = true,
            smallAreaFlowCompensationModel = "0,0\n0.5,0.6\n10,1",
            travelSpeedZ = 17f,
            skeletonInfillDensity = 31f,
            skinInfillDensity = 47f,
            skinInfillDepth = 3.5f,
            infillLockDepth = 1.25f,
            skinInfillLineWidth = 135f,
            skinInfillLineWidthPercent = true,
            skeletonInfillLineWidth = 0.62f,
            skeletonInfillLineWidthPercent = false,
            skirtStartAngle = -25f,
            skirtType = "perobject",
            singleLoopDraftShield = true,
        ),
        firstLayerSpeed = 32f,
        supportEnabled = true,
        supportType = "tree(auto)",
        supportAngle = 38f,
        skirtLoops = 2,
        skirtDistance = 8f,
        skirtHeight = 3,
        skirtSpeed = 57f,
        minimumSkirtLength = 12f,
        draftShield = "enabled",
        brimType = "inner_only",
        brimWidth = 5f,
        brimObjectGap = 0.15f,
        raftLayers = 2,
        raftContactDistance = 0.13f,
        raftExpansion = 2.5f,
        raftFirstLayerDensity = 86f,
        raftFirstLayerExpansion = 3.5f,
        outerWallLineWidth = 0.64f,
        innerWallLineWidth = 0.68f,
        topSurfaceLineWidth = 0.55f,
        sparseInfillLineWidth = 0.72f,
        internalSolidInfillLineWidth = 0.61f,
        supportLineWidth = 0.58f,
        innerWallSpeed = 175f,
        sparseInfillSpeed = 210f,
        internalSolidInfillSpeed = 165f,
        topSurfaceSpeed = 95f,
        supportSpeed = 85f,
        bridgeSpeed = 43f,
        gapInfillSpeed = 133f,
        firstLayerInfillSpeed = 61f,
        supportInterfaceSpeed = 52f,
        internalBridgeSpeed = 163f,
        internalBridgeSpeedPercent = true,
        overhangSpeedEnabled = false,
        overhangSpeed1 = 81f,
        overhangSpeed1Percent = true,
        overhangSpeed2 = 52f,
        overhangSpeed2Percent = false,
        overhangSpeed3 = 33f,
        overhangSpeed3Percent = true,
        overhangSpeed4 = 21f,
        overhangSpeed4Percent = false,
        printFlowRatio = 0.94f,
        bridgeFlowRatio = 0.91f,
        internalBridgeFlowRatio = 0.96f,
        topSurfaceFlowRatio = 0.97f,
        bottomSurfaceFlowRatio = 0.98f,
        supportFlowRatio = 0.86f,
        supportInterfaceFlowRatio = 1.14f,
        bridgeDensity = 87f,
        internalBridgeDensity = 73f,
        bridgeAngle = 17f,
        internalBridgeAngle = 103f,
        bridgeNoSupport = true,
        thickBridges = true,
        thickInternalBridges = false,
        extraBridgeLayer = "apply_to_all",
        internalBridgeFilter = "limited",
        topShellThickness = 0.8f,
        bottomShellThickness = 0.7f,
        supportInterfaceTopLayers = 4,
        supportInterfaceBottomLayers = 2,
        supportInterfaceSpacing = 0.24f,
        supportBottomInterfaceSpacing = 0.28f,
        supportTopZDistance = 0.18f,
        supportBottomZDistance = 0.22f,
        supportObjectXYDistance = 0.4f,
        supportBasePattern = "rectilinear-grid",
        supportInterfacePattern = "rectilinear_interlaced",
        supportStyle = "tree_strong",
        fuzzySkin = FuzzySkinSettings(
            type = "allwalls",
            firstLayer = true,
            pointDistance = 0.65f,
            thickness = 0.28f,
            mode = "combined",
            noiseType = "billow",
            scale = 3.5f,
            octaves = 6,
            persistence = 0.7f,
        ),
        supportCoverage = SupportCoverageSettings(
            onBuildPlateOnly = true,
            criticalRegionsOnly = true,
            removeSmallOverhangs = false,
            enforcedLayers = 7,
        ),
        supportAdvanced = SupportAdvancedSettings(
            patternAngle = 73f,
            thresholdOverlap = 0.33f,
            thresholdOverlapPercent = false,
            objectFirstLayerGap = 0.42f,
            avoidInterfaceFilamentForBase = false,
            ironingEnabled = true,
            ironingPattern = "concentric",
            ironingFlow = 17f,
            ironingSpacing = 0.18f,
        ),
        supportBasePatternSpacing = 3.2f,
        supportExpansion = -0.4f,
        supportInterfaceLoopPattern = true,
        independentSupportLayerHeight = false,
        treeSupportBranchAngle = 47f,
        treeSupportBranchDistance = 6.2f,
        treeSupportBranchDiameter = 2.4f,
        treeSupportWallCount = 2,
        treeSupportTipDiameter = 1.3f,
        treeSupportPreferredBranchAngle = 31f,
        treeSupportBranchDensity = 37f,
        treeSupportOrganicBranchAngle = 45f,
        treeSupportOrganicBranchDistance = 2.2f,
        treeSupportOrganicBranchDiameter = 3.1f,
        treeSupportBranchDiameterAngle = 10f,
        treeSupportAdaptiveLayerHeight = false,
        treeSupportAutoBrim = false,
        treeSupportBrimWidth = 4.6f,
        supportFilament = 1,
        supportInterfaceFilament = 1,
        featureFilaments = FeatureFilamentSettings(
            infillOverrideEnabled = true,
            baseFirstLayers = 3,
            baseLastLayers = 4,
            sparseInfillFilament = 1,
            wallFilament = 1,
            solidInfillFilament = 1,
            wipeTowerFilament = 1,
        ),
        wipeTowerEnabled = true,
        wipeTowerWidth = 48f,
        multiMaterial = MultiMaterialSettings(
            primeVolume = 58f,
            primeTowerPositionX = 123.5f,
            primeTowerPositionY = 87.5f,
            primeTowerBrimWidth = 5.5f,
            primeTowerFramework = true,
            primeTowerSkipPoints = false,
            primeTowerFlatIroning = true,
            primeTowerInterfaceFeatures = true,
            primeTowerInterfaceCooldown = true,
            primeTowerInfillGap = 175f,
            wipeTowerNoSparseLayers = true,
            wipeTowerRotationAngle = 73f,
            wipeTowerBridging = 12.5f,
            wipeTowerExtraSpacing = 145f,
            wipeTowerExtraFlow = 118f,
            wipeTowerMaxPurgeSpeed = 137f,
            wipeTowerWallType = "rib",
            wipeTowerConeAngle = 42f,
            wipeTowerExtraRibLength = 9.5f,
            wipeTowerRibWidth = 11f,
            wipeTowerFilletWall = false,
            singleExtruderMultiMaterialPriming = true,
            flushIntoInfill = true,
            flushIntoSupport = false,
            flushIntoObjects = true,
            oozePrevention = true,
            standbyTemperatureDelta = -42,
            preheatTime = 94.5f,
            preheatDeltaTemperature = -18,
            preheatSteps = 7,
            interfaceShells = true,
            segmentedRegionMaxWidth = 2.4f,
            segmentedRegionInterlockingDepth = 0.8f,
            interlockingBeam = true,
            interlockingBeamWidth = 1.25f,
            interlockingOrientation = 67.5f,
            interlockingBeamLayerCount = 3,
            interlockingDepth = 4,
            interlockingBoundaryAvoidance = 1,
        ),
        gcodeSettings = GcodeSettings(
            arcFitting = true,
            labelObjects = false,
            excludeObjects = true,
            verboseComments = true,
            initialLayerTravelSpeed = 35f,
            initialLayerTravelSpeedPercent = true,
            slowDownLayers = 4,
            accelToDecelEnabled = false,
            accelToDecelFactor = 27f,
            filenameFormat = "{input_filename_base}_{layer_height}mm_{print_time}.gcode",
        ),
        infillFirst = true,
        infillWallOverlap = 19f,
        topBottomInfillWallOverlap = 31f,
        infillCombination = true,
        infillCombinationMaxLayerHeight = 0.36f,
        infillCombinationMaxLayerHeightPercent = false,
        infillDirection = 37f,
        solidInfillDirection = 123f,
        alignInfillDirectionToModel = true,
        minimumSparseInfillArea = 42f,
        infillAnchor = 321f,
        infillAnchorPercent = true,
        infillAnchorMax = 17.5f,
        infillAnchorMaxPercent = false,
        gapFillTarget = "everywhere",
        filterOutGapFill = 0.9f,
        reduceCrossingWall = true,
        maxTravelDetourDistance = 155f,
        maxTravelDetourDistancePercent = true,
        reduceInfillRetraction = true,
        initialLayerLineWidth = 0.74f,
        smallPerimeterSpeed = 77f,
        smallPerimeterSpeedPercent = false,
        smallPerimeterThreshold = 6.5f,
        slowdownForCurledPerimeters = false,
        resolution = 0.023f,
        precision = PrecisionSettings(
            mode = "close_holes",
            closingRadius = 0.125f,
            preciseZHeight = true,
            polyholes = PolyholeSettings(
                enabled = true,
                detectionMargin = 6.5f,
                detectionMarginPercent = true,
                twist = false,
            ),
            minimumWallWidth = 74f,
            firstLayerMinimumWallWidth = 116f,
            printableOverhangs = PrintableOverhangSettings(
                enabled = true,
                maximumAngle = 63f,
                holeArea = 240f,
            ),
            brimEars = BrimEarSettings(maximumAngle = 132f, detectionRadius = 1.7f),
        ),
        seamPosition = "nearest",
        staggeredInnerSeams = true,
        seamGap = 3.5f,
        seamGapPercent = true,
        scarfSeam = ScarfSeamSettings(
            type = "all",
            conditional = true,
            angleThreshold = 142,
            overhangThreshold = 37f,
            speed = 63f,
            speedPercent = true,
            flowRatio = 0.92f,
            startHeight = 18f,
            startHeightPercent = true,
            entireLoop = true,
            length = 24.5f,
            steps = 13,
            innerWalls = true,
        ),
        wipeBeforeExternalLoop = true,
        wipeOnLoops = true,
        roleBasedWipeSpeed = false,
        wipeSpeed = 66f,
        wipeSpeedPercent = false,
        ironing = IroningSettings(
            type = "top",
            pattern = "concentric",
            flow = 13f,
            spacing = 0.17f,
            inset = 0.37f,
            speed = 27f,
            angle = 123f,
        ),
        defaultAcceleration = 4_000f,
        outerWallAcceleration = 2_000f,
        innerWallAcceleration = 3_500f,
        topSurfaceAcceleration = 1_200f,
        travelAcceleration = 5_000f,
        firstLayerAcceleration = 600f,
        firstLayerTravelAcceleration = 37f,
        firstLayerTravelAccelerationPercent = true,
        bridgeAcceleration = 47f,
        bridgeAccelerationPercent = true,
        sparseInfillAcceleration = 4_321f,
        sparseInfillAccelerationPercent = false,
        internalSolidInfillAcceleration = 83f,
        internalSolidInfillAccelerationPercent = true,
        jerk = JerkSettings(
            defaultJerk = 8.5f,
            outerWallJerk = 7.5f,
            innerWallJerk = 8f,
            topSurfaceJerk = 6.5f,
            infillJerk = 9.5f,
            firstLayerJerk = 5.5f,
            travelJerk = 12.5f,
        ),
        wallGenerator = "classic",
        wallTransitionLength = 135f,
        wallTransitionFilterDeviation = 31f,
        wallTransitionAngle = 24f,
        wallDistributionCount = 3,
        minimumFeatureSize = 22f,
        minimumWallLengthFactor = 0.75f,
        wallSequence = "outer-inner",
        wallDirection = "cw",
        detectThinWalls = true,
        detectOverhangWalls = false,
        onlyOneWallOnTop = true,
        minWidthTopSurface = 275f,
        minWidthTopSurfacePercent = true,
        onlyOneWallFirstLayer = true,
        extraPerimetersOnOverhangs = true,
        overhangReverse = true,
        overhangReverseInternalOnly = true,
        overhangReverseThreshold = 0.8f,
        overhangReverseThresholdPercent = false,
        counterboreHoleBridging = "partiallybridge",
        alternateExtraWall = true,
        ensureVerticalShellThickness = "ensure_moderate",
        detectNarrowInternalSolidInfill = false,
        xyHoleCompensation = 0.11f,
        xyContourCompensation = -0.07f,
        elephantFootCompensation = 0.23f,
        elephantFootCompensationLayers = 3,
        maxBridgeLength = 26f,
        printSequence = "by object",
        printOrder = "as_obj_list",
        spiralMode = true,
        spiralModeSmooth = true,
        spiralModeMaxXySmoothing = 2.5f,
        spiralModeMaxXySmoothingPercent = false,
        spiralStartingFlowRatio = 0.35f,
        spiralFinishingFlowRatio = 0.2f,
        preciseOuterWalls = true,
        gcodeFlavor = "reprapfirmware",
        machineMotion = MachineMotionSettings.fromProfile(PrinterProfile.U1_06).copy(
            maxSpeedX = 330f,
            maxSpeedY = 340f,
            maxAccelerationX = 4_800f,
            maxAccelerationY = 4_900f,
            maxAccelerationExtruding = 3_200f,
            maxAccelerationTravel = 4_600f,
            maxJerkX = 8f,
            maxJerkY = 9f,
            maxJunctionDeviation = 0.032f,
        ),
    )

package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliceOptionsPersistenceTest {
    @Test
    fun filamentSlotsRoundTripAndReachTheNativeExtruderConfiguration() {
        val primary = FilamentProfile.GENERIC_PLA.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_04.name),
        )
        val secondary = FilamentProfile.PETG.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_04.name),
        )
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(primary)
            .copy(
                filamentSlots = listOf(primary, secondary),
                supportFilament = 1,
                supportInterfaceFilament = 2,
                wipeTowerEnabled = true,
                wipeTowerWidth = 42f,
                multiMaterial = MultiMaterialSettings(
                    primeVolume = 61.5f,
                    primeTowerBrimWidth = 4.5f,
                    wipeTowerNoSparseLayers = true,
                    oozePrevention = true,
                    standbyTemperatureDelta = -35,
                    interfaceShells = true,
                ),
                gcodeSettings = GcodeSettings(
                    arcFitting = true,
                    labelObjects = false,
                    excludeObjects = true,
                    initialLayerTravelSpeed = 35f,
                    initialLayerTravelSpeedPercent = true,
                    accelToDecelEnabled = false,
                    accelToDecelFactor = 27f,
                ),
            )

        val restored = requireNotNull(options.toProjectJson().toProjectSliceOptionsOrNull())
        val native = restored.toNativeConfig()

        assertEquals(listOf(primary.id, secondary.id), restored.resolvedFilamentSlots().map { it.id })
        assertNull(restored.filamentProfile.retractLength)
        assertNull(restored.filamentProfile.zHopType)
        assertEquals(2, native.extruderCount)
        assertEquals(listOf("PLA", "PETG"), native.filamentTypes.toList())
        assertEquals(listOf(primary.nozzleTemp, secondary.nozzleTemp), native.extruderTemps.toList())
        assertEquals(listOf(primary.flowRatio, secondary.flowRatio), native.filamentFlowRatios.toList())
        assertEquals(1, native.supportFilament)
        assertEquals(2, native.supportInterfaceFilament)
        assertEquals(true, native.wipeTowerEnabled)
        assertEquals(42f, native.wipeTowerWidth)
        assertEquals(61.5f, native.primeVolume)
        assertEquals(4.5f, native.primeTowerBrimWidth)
        assertEquals(true, native.wipeTowerNoSparseLayers)
        assertEquals(true, native.oozePrevention)
        assertEquals(-35, native.standbyTemperatureDelta)
        assertEquals(true, native.interfaceShells)
        assertEquals(true, native.enableArcFitting)
        assertEquals(false, native.gcodeLabelObjects)
        assertEquals(true, native.excludeObject)
        assertEquals(35f, native.initialLayerTravelSpeed)
        assertEquals(true, native.initialLayerTravelSpeedPercent)
        assertEquals(false, native.accelToDecelEnabled)
        assertEquals(27f, native.accelToDecelFactor)
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
        assertEquals(options.bedOriginX, restored.bedOriginX)
        assertEquals(options.bedOriginY, restored.bedOriginY)
        assertEquals(options.bedPolygon, restored.bedPolygon)
        assertEquals(73f, restored.extruderClearanceRadius)
        assertEquals(29f, restored.extruderClearanceHeightToRod)
        assertEquals(117f, restored.extruderClearanceHeightToLid)
        assertEquals(options.filamentProfile.compatiblePrinters, restored.filamentProfile.compatiblePrinters)
        assertEquals(options.quality.compatiblePrinters, restored.quality.compatiblePrinters)
        assertEquals(248, restored.nozzleTemp)
        assertEquals(0.64f, restored.outerWallLineWidth)
        assertEquals(0.68f, restored.innerWallLineWidth)
        assertEquals(0.55f, restored.topSurfaceLineWidth)
        assertEquals(0.72f, restored.sparseInfillLineWidth)
        assertEquals(0.61f, restored.internalSolidInfillLineWidth)
        assertEquals(0.58f, restored.supportLineWidth)
        assertEquals(175f, restored.innerWallSpeed)
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
        assertEquals(false, restored.overhangSpeedEnabled)
        assertEquals(81f, restored.overhangSpeed1)
        assertEquals(true, restored.overhangSpeed1Percent)
        assertEquals("crosshatch", restored.fillPattern)
        assertEquals("monotonic", restored.topSurfacePattern)
        assertEquals("concentric", restored.bottomSurfacePattern)
        assertEquals("rectilinear", restored.internalSolidInfillPattern)
        assertEquals("nearest", restored.seamPosition)
        assertEquals("top", restored.ironingType)
        assertEquals("concentric", restored.ironingPattern)
        assertEquals(13f, restored.ironingFlow)
        assertEquals(0.17f, restored.ironingSpacing)
        assertEquals(27f, restored.ironingSpeed)
        assertEquals("tree(auto)", restored.supportType)
        assertEquals("by object", restored.printSequence)
        assertEquals("as_obj_list", restored.printOrder)
        assertEquals("rectilinear-grid", restored.supportBasePattern)
        assertEquals("rectilinear_interlaced", restored.supportInterfacePattern)
        assertEquals("snug", restored.supportStyle)
        assertEquals(true, restored.supportOnBuildPlateOnly)
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
        assertEquals(true, restored.wipeTowerEnabled)
        assertEquals(48f, restored.wipeTowerWidth)
        assertEquals(options.multiMaterial, restored.multiMaterial)
        assertEquals(options.gcodeSettings, restored.gcodeSettings)
        assertEquals(true, restored.infillFirst)
        assertEquals(19f, restored.infillWallOverlap)
        assertEquals(31f, restored.topBottomInfillWallOverlap)
        assertEquals(true, restored.infillCombination)
        assertEquals(0.36f, restored.infillCombinationMaxLayerHeight)
        assertEquals(false, restored.infillCombinationMaxLayerHeightPercent)
        assertEquals(37f, restored.infillDirection)
        assertEquals(123f, restored.solidInfillDirection)
        assertEquals(true, restored.alignInfillDirectionToModel)
        assertEquals(42f, restored.minimumSparseInfillArea)
        assertEquals(321f, restored.infillAnchor)
        assertEquals(true, restored.infillAnchorPercent)
        assertEquals(17.5f, restored.infillAnchorMax)
        assertEquals(false, restored.infillAnchorMaxPercent)
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
        assertEquals(0.8f, restored.topShellThickness)
        assertEquals(0.7f, restored.bottomShellThickness)
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
        assertEquals(0.75f, restored.minimumWallLengthFactor)
        assertEquals("outer-inner", restored.wallSequence)
        assertEquals("cw", restored.wallDirection)
        assertEquals(77f, restored.smallPerimeterSpeed)
        assertEquals(false, restored.smallPerimeterSpeedPercent)
        assertEquals(6.5f, restored.smallPerimeterThreshold)
        assertEquals(false, restored.slowdownForCurledPerimeters)
        assertEquals(0.023f, restored.resolution)
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
        assertEquals(2, restored.skirtLoops)
        assertEquals(8f, restored.skirtDistance)
        assertEquals(3, restored.skirtHeight)
        assertEquals(57f, restored.skirtSpeed)
        assertEquals(12f, restored.minimumSkirtLength)
        assertEquals("enabled", restored.draftShield)
        assertEquals("inner_only", restored.brimType)
        assertEquals(5f, restored.brimWidth)
        assertEquals(0.15f, restored.brimObjectGap)
        assertEquals(2, restored.raftLayers)
        assertEquals(0.13f, restored.raftContactDistance)
        assertEquals(2.5f, restored.raftExpansion)
        assertEquals(86f, restored.raftFirstLayerDensity)
        assertEquals(3.5f, restored.raftFirstLayerExpansion)
        assertEquals("klipper", restored.gcodeFlavor)
        assertEquals(4_600f, restored.maxAccelerationTravel)
    }

    @Test
    fun unsafeOrUnknownStoredSettingsAreIgnored() {
        val unsafe = restoredSettingsFixture().toProjectJson()
        unsafe.getJSONObject("printer").put("maxSpeedX", 0)
        assertNull(unsafe.toProjectSliceOptionsOrNull())

        val unknown = JSONObject(restoredSettingsFixture().toProjectJson().toString())
            .put("formatVersion", 99)
        assertNull(unknown.toProjectSliceOptionsOrNull())
    }
}

internal fun restoredSettingsFixture(): SliceOptions = SliceOptions()
    .selectPrinter(PrinterProfile.U1_06)
    .selectFilament(
        FilamentProfile.PETG.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_06.name),
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
        maxPrintHeight = 290f,
        extruderClearanceRadius = 73f,
        extruderClearanceHeightToRod = 29f,
        extruderClearanceHeightToLid = 117f,
        nozzleTemp = 248,
        firstLayerNozzleTemp = 252,
        bedTemp = 74,
        firstLayerBedTemp = 78,
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
        bridgeFlowRatio = 0.91f,
        internalBridgeFlowRatio = 0.96f,
        topSurfaceFlowRatio = 0.97f,
        bottomSurfaceFlowRatio = 0.98f,
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
        supportStyle = "snug",
        supportOnBuildPlateOnly = true,
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
        wipeTowerEnabled = true,
        wipeTowerWidth = 48f,
        multiMaterial = MultiMaterialSettings(
            primeVolume = 58f,
            primeTowerBrimWidth = 5.5f,
            wipeTowerNoSparseLayers = true,
            oozePrevention = true,
            standbyTemperatureDelta = -42,
            interfaceShells = true,
        ),
        gcodeSettings = GcodeSettings(
            arcFitting = true,
            labelObjects = false,
            excludeObjects = true,
            initialLayerTravelSpeed = 35f,
            initialLayerTravelSpeedPercent = true,
            accelToDecelEnabled = false,
            accelToDecelFactor = 27f,
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
        ironingType = "top",
        ironingPattern = "concentric",
        ironingFlow = 13f,
        ironingSpacing = 0.17f,
        ironingSpeed = 27f,
        defaultAcceleration = 4_000f,
        outerWallAcceleration = 2_000f,
        innerWallAcceleration = 3_500f,
        topSurfaceAcceleration = 1_200f,
        travelAcceleration = 5_000f,
        firstLayerAcceleration = 600f,
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
        gcodeFlavor = "klipper",
        maxSpeedX = 330f,
        maxSpeedY = 340f,
        maxAccelerationX = 4_800f,
        maxAccelerationY = 4_900f,
        maxAccelerationExtruding = 3_200f,
        maxAccelerationTravel = 4_600f,
        maxJerkX = 8f,
        maxJerkY = 9f,
    )

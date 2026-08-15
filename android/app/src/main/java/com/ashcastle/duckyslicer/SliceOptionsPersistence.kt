package com.ashcastle.duckyslicer

import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

internal fun SliceOptions.toProjectJson(): JSONObject {
    val printer = printerProfile.copy(
        bedSizeX = bedSizeX,
        bedSizeY = bedSizeY,
        bedOriginX = bedOriginX,
        bedOriginY = bedOriginY,
        bedPolygon = bedPolygon,
        maxPrintHeight = maxPrintHeight,
        nozzleDiameter = nozzleDiameter,
        gcodeFlavor = gcodeFlavor,
        maxSpeedX = maxSpeedX,
        maxSpeedY = maxSpeedY,
        maxSpeedZ = maxSpeedZ,
        maxSpeedE = maxSpeedE,
        maxAccelerationX = maxAccelerationX,
        maxAccelerationY = maxAccelerationY,
        maxAccelerationZ = maxAccelerationZ,
        maxAccelerationE = maxAccelerationE,
        maxAccelerationExtruding = maxAccelerationExtruding,
        maxAccelerationRetracting = maxAccelerationRetracting,
        maxAccelerationTravel = maxAccelerationTravel,
        maxJerkX = maxJerkX,
        maxJerkY = maxJerkY,
        maxJerkZ = maxJerkZ,
        maxJerkE = maxJerkE,
        extruderClearanceRadius = extruderClearanceRadius,
        extruderClearanceHeightToRod = extruderClearanceHeightToRod,
        extruderClearanceHeightToLid = extruderClearanceHeightToLid,
    )
    val filament = filamentProfile.withBedTemperature(
        buildPlate.type,
        bedTemp,
        firstLayerBedTemp,
    ).copy(
        nozzleTemp = nozzleTemp,
        firstLayerNozzleTemp = firstLayerNozzleTemp,
        flowRatio = flowRatio,
        maxVolumetricSpeed = maxVolumetricSpeed,
        diameter = filamentDiameter,
        fanMinSpeed = fanMinSpeed,
        fanMaxSpeed = fanMaxSpeed,
        overhangFanSpeed = overhangFanSpeed,
        slowDownLayerTime = slowDownLayerTime,
        slowDownMinSpeed = slowDownMinSpeed,
        closeFanFirstLayers = closeFanFirstLayers,
        fullFanSpeedLayer = fullFanSpeedLayer,
        pressureAdvanceEnabled = pressureAdvanceEnabled,
        pressureAdvance = pressureAdvance,
    )
    val slicing = quality.copy(
        layerHeightMm = layerHeight,
        firstLayerHeightMm = firstLayerHeight,
        perimeters = perimeters,
        fillDensity = fillDensity,
        printSpeed = printSpeed,
        innerWallSpeed = innerWallSpeed,
        sparseInfillSpeed = sparseInfillSpeed,
        internalSolidInfillSpeed = internalSolidInfillSpeed,
        topSurfaceSpeed = topSurfaceSpeed,
        supportSpeed = supportSpeed,
        bridgeSpeed = bridgeSpeed,
        gapInfillSpeed = gapInfillSpeed,
        firstLayerInfillSpeed = firstLayerInfillSpeed,
        supportInterfaceSpeed = supportInterfaceSpeed,
        internalBridgeSpeed = internalBridgeSpeed,
        internalBridgeSpeedPercent = internalBridgeSpeedPercent,
        overhangSpeedEnabled = overhangSpeedEnabled,
        overhangSpeed1 = overhangSpeed1,
        overhangSpeed1Percent = overhangSpeed1Percent,
        overhangSpeed2 = overhangSpeed2,
        overhangSpeed2Percent = overhangSpeed2Percent,
        overhangSpeed3 = overhangSpeed3,
        overhangSpeed3Percent = overhangSpeed3Percent,
        overhangSpeed4 = overhangSpeed4,
        overhangSpeed4Percent = overhangSpeed4Percent,
        printFlowRatio = printFlowRatio,
        bridgeFlowRatio = bridgeFlowRatio,
        internalBridgeFlowRatio = internalBridgeFlowRatio,
        topSurfaceFlowRatio = topSurfaceFlowRatio,
        bottomSurfaceFlowRatio = bottomSurfaceFlowRatio,
        bridgeDensity = bridgeDensity,
        internalBridgeDensity = internalBridgeDensity,
        bridgeAngle = bridgeAngle,
        internalBridgeAngle = internalBridgeAngle,
        bridgeNoSupport = bridgeNoSupport,
        thickBridges = thickBridges,
        thickInternalBridges = thickInternalBridges,
        extraBridgeLayer = extraBridgeLayer,
        internalBridgeFilter = internalBridgeFilter,
        defaultAcceleration = defaultAcceleration,
        outerWallAcceleration = outerWallAcceleration,
        innerWallAcceleration = innerWallAcceleration,
        topSurfaceAcceleration = topSurfaceAcceleration,
        travelAcceleration = travelAcceleration,
        firstLayerAcceleration = firstLayerAcceleration,
        bridgeAcceleration = bridgeAcceleration,
        bridgeAccelerationPercent = bridgeAccelerationPercent,
        sparseInfillAcceleration = sparseInfillAcceleration,
        sparseInfillAccelerationPercent = sparseInfillAccelerationPercent,
        internalSolidInfillAcceleration = internalSolidInfillAcceleration,
        internalSolidInfillAccelerationPercent = internalSolidInfillAccelerationPercent,
        defaultJerk = defaultJerk,
        outerWallJerk = outerWallJerk,
        innerWallJerk = innerWallJerk,
        topSurfaceJerk = topSurfaceJerk,
        infillJerk = infillJerk,
        firstLayerJerk = firstLayerJerk,
        travelJerk = travelJerk,
        fuzzySkin = fuzzySkin,
        nozzleDiameter = nozzleDiameter,
        supportEnabled = supportEnabled,
        brimType = brimType,
        brimWidth = brimWidth,
        brimObjectGap = brimObjectGap,
        raftLayers = raftLayers,
        raftContactDistance = raftContactDistance,
        raftExpansion = raftExpansion,
        raftFirstLayerDensity = raftFirstLayerDensity,
        raftFirstLayerExpansion = raftFirstLayerExpansion,
        topSolidLayers = topSolidLayers,
        bottomSolidLayers = bottomSolidLayers,
        topShellThickness = topShellThickness,
        bottomShellThickness = bottomShellThickness,
        fillPattern = fillPattern,
        topSurfacePattern = topSurfacePattern,
        bottomSurfacePattern = bottomSurfacePattern,
        internalSolidInfillPattern = internalSolidInfillPattern,
        infillFirst = infillFirst,
        infillWallOverlap = infillWallOverlap,
        topBottomInfillWallOverlap = topBottomInfillWallOverlap,
        infillCombination = infillCombination,
        infillCombinationMaxLayerHeight = infillCombinationMaxLayerHeight,
        infillCombinationMaxLayerHeightPercent = infillCombinationMaxLayerHeightPercent,
        infillDirection = infillDirection,
        solidInfillDirection = solidInfillDirection,
        alignInfillDirectionToModel = alignInfillDirectionToModel,
        minimumSparseInfillArea = minimumSparseInfillArea,
        infillAnchor = infillAnchor,
        infillAnchorPercent = infillAnchorPercent,
        infillAnchorMax = infillAnchorMax,
        infillAnchorMaxPercent = infillAnchorMaxPercent,
        gapFillTarget = gapFillTarget,
        filterOutGapFill = filterOutGapFill,
        reduceCrossingWall = reduceCrossingWall,
        maxTravelDetourDistance = maxTravelDetourDistance,
        maxTravelDetourDistancePercent = maxTravelDetourDistancePercent,
        reduceInfillRetraction = reduceInfillRetraction,
        travelSpeed = travelSpeed,
        travelSpeedZ = travelSpeedZ,
        firstLayerSpeed = firstLayerSpeed,
        supportType = normalizedSupportType(supportType),
        supportAngle = supportAngle,
        supportInterfaceTopLayers = supportInterfaceTopLayers,
        supportInterfaceBottomLayers = supportInterfaceBottomLayers,
        supportInterfaceSpacing = supportInterfaceSpacing,
        supportBottomInterfaceSpacing = supportBottomInterfaceSpacing,
        supportTopZDistance = supportTopZDistance,
        supportBottomZDistance = supportBottomZDistance,
        supportObjectXYDistance = supportObjectXYDistance,
        supportBasePattern = supportBasePattern,
        supportInterfacePattern = supportInterfacePattern,
        supportStyle = normalizedSupportStyle(supportType, supportStyle),
        supportCoverage = supportCoverage,
        supportAdvanced = supportAdvanced,
        supportBasePatternSpacing = supportBasePatternSpacing,
        supportExpansion = supportExpansion,
        supportInterfaceLoopPattern = supportInterfaceLoopPattern,
        independentSupportLayerHeight = independentSupportLayerHeight,
        treeSupportBranchAngle = treeSupportBranchAngle,
        treeSupportBranchDistance = treeSupportBranchDistance,
        treeSupportBranchDiameter = treeSupportBranchDiameter,
        treeSupportWallCount = treeSupportWallCount,
        treeSupportTipDiameter = treeSupportTipDiameter,
        treeSupportPreferredBranchAngle = treeSupportPreferredBranchAngle,
        treeSupportBranchDensity = treeSupportBranchDensity,
        treeSupportOrganicBranchAngle = treeSupportOrganicBranchAngle,
        treeSupportOrganicBranchDistance = treeSupportOrganicBranchDistance,
        treeSupportOrganicBranchDiameter = treeSupportOrganicBranchDiameter,
        treeSupportBranchDiameterAngle = treeSupportBranchDiameterAngle,
        treeSupportAdaptiveLayerHeight = treeSupportAdaptiveLayerHeight,
        treeSupportAutoBrim = treeSupportAutoBrim,
        treeSupportBrimWidth = treeSupportBrimWidth,
        supportFilament = supportFilament,
        supportInterfaceFilament = supportInterfaceFilament,
        featureFilaments = featureFilaments,
        wipeTowerEnabled = wipeTowerEnabled,
        wipeTowerWidth = wipeTowerWidth,
        multiMaterial = multiMaterial,
        gcodeSettings = gcodeSettings,
        skirtLoops = skirtLoops,
        skirtDistance = skirtDistance,
        skirtHeight = skirtHeight,
        skirtSpeed = skirtSpeed,
        minimumSkirtLength = minimumSkirtLength,
        draftShield = draftShield,
        outerWallLineWidth = outerWallLineWidth,
        innerWallLineWidth = innerWallLineWidth,
        topSurfaceLineWidth = topSurfaceLineWidth,
        sparseInfillLineWidth = sparseInfillLineWidth,
        internalSolidInfillLineWidth = internalSolidInfillLineWidth,
        supportLineWidth = supportLineWidth,
        initialLayerLineWidth = initialLayerLineWidth,
        smallPerimeterSpeed = smallPerimeterSpeed,
        smallPerimeterSpeedPercent = smallPerimeterSpeedPercent,
        smallPerimeterThreshold = smallPerimeterThreshold,
        slowdownForCurledPerimeters = slowdownForCurledPerimeters,
        resolution = resolution,
        precision = precision,
        seamPosition = seamPosition,
        staggeredInnerSeams = staggeredInnerSeams,
        seamGap = seamGap,
        seamGapPercent = seamGapPercent,
        scarfSeam = scarfSeam,
        wipeBeforeExternalLoop = wipeBeforeExternalLoop,
        wipeOnLoops = wipeOnLoops,
        roleBasedWipeSpeed = roleBasedWipeSpeed,
        wipeSpeed = wipeSpeed,
        wipeSpeedPercent = wipeSpeedPercent,
        ironing = ironing,
        wallGenerator = wallGenerator,
        wallTransitionLength = wallTransitionLength,
        wallTransitionFilterDeviation = wallTransitionFilterDeviation,
        wallTransitionAngle = wallTransitionAngle,
        wallDistributionCount = wallDistributionCount,
        minimumFeatureSize = minimumFeatureSize,
        minimumWallLengthFactor = minimumWallLengthFactor,
        wallSequence = wallSequence,
        wallDirection = wallDirection,
        detectThinWalls = detectThinWalls,
        detectOverhangWalls = detectOverhangWalls,
        onlyOneWallOnTop = onlyOneWallOnTop,
        minWidthTopSurface = minWidthTopSurface,
        minWidthTopSurfacePercent = minWidthTopSurfacePercent,
        onlyOneWallFirstLayer = onlyOneWallFirstLayer,
        extraPerimetersOnOverhangs = extraPerimetersOnOverhangs,
        overhangReverse = overhangReverse,
        overhangReverseInternalOnly = overhangReverseInternalOnly,
        overhangReverseThreshold = overhangReverseThreshold,
        overhangReverseThresholdPercent = overhangReverseThresholdPercent,
        counterboreHoleBridging = counterboreHoleBridging,
        alternateExtraWall = alternateExtraWall,
        ensureVerticalShellThickness = ensureVerticalShellThickness,
        detectNarrowInternalSolidInfill = detectNarrowInternalSolidInfill,
        xyHoleCompensation = xyHoleCompensation,
        xyContourCompensation = xyContourCompensation,
        elephantFootCompensation = elephantFootCompensation,
        elephantFootCompensationLayers = elephantFootCompensationLayers,
        maxBridgeLength = maxBridgeLength,
        preciseOuterWalls = preciseOuterWalls,
        printSequence = printSequence,
        printOrder = printOrder,
        spiralMode = spiralMode,
        spiralModeSmooth = spiralModeSmooth,
        spiralModeMaxXySmoothing = spiralModeMaxXySmoothing,
        spiralModeMaxXySmoothingPercent = spiralModeMaxXySmoothingPercent,
        spiralStartingFlowRatio = spiralStartingFlowRatio,
        spiralFinishingFlowRatio = spiralFinishingFlowRatio,
    )
    require(ProfileValidation.printer(printer)) { "Invalid project printer settings" }
    require(ProfileValidation.filament(filament)) { "Invalid project filament settings" }
    require(ProfileValidation.slicing(slicing)) { "Invalid project slicing settings" }
    require(filamentDiameter in MIN_FILAMENT_DIAMETER..MAX_FILAMENT_DIAMETER) {
        "Invalid filament diameter"
    }
    val filaments = resolvedFilamentSlots().mapIndexed { index, profile ->
        if (index == 0) filament else profile
    }
    require(
        filaments.size in 1..printer.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS) &&
            filaments.all(ProfileValidation::filament) &&
            filaments.all { it.hasCompatibleDiameter(filament) },
    ) { "Invalid project filament slots" }
    return JSONObject()
        .put("formatVersion", SLICE_OPTIONS_FORMAT_VERSION)
        .put("filamentDiameter", filamentDiameter)
        .put("buildPlateType", buildPlate.type.storageValue)
        .put("printer", printer.toProfileJson())
        .put("filament", filament.toProfileJson())
        .put("filamentSlots", JSONArray().also { values ->
            filaments.forEach { values.put(it.toProfileJson()) }
        })
        .put("slicing", slicing.toProfileJson())
}

internal fun JSONObject.toProjectSliceOptionsOrNull(): SliceOptions? = runCatching {
    val formatVersion = getInt("formatVersion")
    require(formatVersion in MIN_SLICE_OPTIONS_FORMAT_VERSION..SLICE_OPTIONS_FORMAT_VERSION) {
        "Unsupported slice settings"
    }
    val printer = requireNotNull(getJSONObject("printer").toPrinterProfileOrNull())
    val storedFilament = requireNotNull(getJSONObject("filament").toFilamentProfileOrNull())
    val storedFilaments = optJSONArray("filamentSlots")?.let { values ->
        require(values.length() in 1..printer.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS))
        List(values.length()) { index ->
            requireNotNull(values.getJSONObject(index).toFilamentProfileOrNull())
        }
    } ?: listOf(storedFilament)
    val slicing = requireNotNull(getJSONObject("slicing").toQualityProfileOrNull())
    val filamentDiameter = getDouble("filamentDiameter").toFloat()
    val buildPlateType = if (formatVersion >= 61) {
        requireNotNull(BuildPlateType.fromStorage(getString("buildPlateType")))
    } else {
        BuildPlateType.HIGH_TEMP
    }
    val filament = if (formatVersion >= 56) {
        storedFilament
    } else {
        storedFilament.copy(diameter = filamentDiameter)
    }
    val filaments = if (formatVersion >= 56) {
        storedFilaments
    } else {
        storedFilaments.map { it.copy(diameter = filamentDiameter) }
    }
    require(ProfileValidation.printer(printer)) { "Invalid project printer settings" }
    require(ProfileValidation.filament(filament)) { "Invalid project filament settings" }
    require(
        filaments.all(ProfileValidation::filament) &&
            filaments.first() == filament &&
            filaments.all { it.hasCompatibleDiameter(filament) },
    ) {
        "Invalid project filament slots"
    }
    require(ProfileValidation.slicing(slicing)) { "Invalid project slicing settings" }
    require(filamentDiameter in MIN_FILAMENT_DIAMETER..MAX_FILAMENT_DIAMETER) {
        "Invalid filament diameter"
    }
    require(abs(filamentDiameter - filament.diameter) < 0.001f) {
        "Project filament diameter does not match its profile"
    }
    SliceOptions(
        printerProfile = printer,
        filamentProfile = filament,
        filamentSlots = filaments,
        quality = slicing,
        filamentDiameter = filamentDiameter,
        buildPlate = BuildPlateSettings.fromProfile(filament, buildPlateType),
    )
}.getOrNull()

private const val SLICE_OPTIONS_FORMAT_VERSION = 68
private const val MIN_SLICE_OPTIONS_FORMAT_VERSION = 1
private const val MIN_FILAMENT_DIAMETER = 0.5f
private const val MAX_FILAMENT_DIAMETER = 4f

package com.ashcastle.duckyslicer

internal object ProfileValidation {
    fun printer(profile: PrinterProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.bedSizeX in 50f..1_500f &&
            profile.bedSizeY in 50f..1_500f &&
            profile.bedOriginX.isFinite() && profile.bedOriginX in -1_500f..1_500f &&
            profile.bedOriginY.isFinite() && profile.bedOriginY in -1_500f..1_500f &&
            bedPolygonIsValid(profile.bedPolygon, profile.bedSizeX, profile.bedSizeY) &&
            bedExcludeAreaIsValid(profile.bedExcludeArea, profile.bedSizeX, profile.bedSizeY) &&
            profile.maxPrintHeight in 50f..1_500f &&
            profile.nozzleDiameter in 0.1f..2f &&
            profile.minLayerHeight in 0.01f..2f &&
            profile.maxLayerHeight in profile.minLayerHeight..2f &&
            profile.extruderCount in 1..MAX_FILAMENT_SLOTS &&
            profile.gcodeFlavor in setOf("marlin", "marlin2", "klipper") &&
            listOf(
                profile.machineLoadFilamentTime,
                profile.machineUnloadFilamentTime,
                profile.machineToolChangeTime,
            ).all { it in 0f..3_600f } &&
            listOf(profile.maxSpeedX, profile.maxSpeedY, profile.maxSpeedZ, profile.maxSpeedE)
                .all { it in 0.1f..2_000f } &&
            listOf(
                profile.maxAccelerationX,
                profile.maxAccelerationY,
                profile.maxAccelerationZ,
                profile.maxAccelerationE,
                profile.maxAccelerationExtruding,
                profile.maxAccelerationRetracting,
                profile.maxAccelerationTravel,
            ).all { it in 0.1f..100_000f } &&
            listOf(profile.maxJerkX, profile.maxJerkY, profile.maxJerkZ, profile.maxJerkE)
                .all { it in 0f..100_000f } &&
            profile.retractLength in 0f..100f &&
            profile.retractSpeed in 0f..500f &&
            profile.deretractSpeed in 0f..500f &&
            profile.retractionMinimumTravel in 0f..1_000f &&
            profile.wipeDistance in 0f..100f &&
            profile.retractBeforeWipe in 0f..100f &&
            profile.retractRestartExtra in -100f..100f &&
            profile.extruderOffsetsX.size in 1..MAX_FILAMENT_SLOTS &&
            profile.extruderOffsetsX.all { it in -1_000f..1_000f } &&
            profile.extruderOffsetsY.size in 1..MAX_FILAMENT_SLOTS &&
            profile.extruderOffsetsX.size == profile.extruderOffsetsY.size &&
            profile.extruderOffsetsY.all { it in -1_000f..1_000f } &&
            profile.toolChangeRetractLengths.size in 1..MAX_FILAMENT_SLOTS &&
            profile.toolChangeRetractLengths.all { it in 0f..100f } &&
            profile.toolChangeRetractRestartExtras.size in 1..MAX_FILAMENT_SLOTS &&
            profile.toolChangeRetractRestartExtras.all { it in -100f..100f } &&
            profile.zHop in 0f..5f &&
            profile.zHopType in Z_HOP_TYPES &&
            profile.retractLiftAbove in 0f..1_500f &&
            profile.retractLiftBelow in 0f..1_500f &&
            (profile.retractLiftBelow == 0f || profile.retractLiftAbove <= profile.retractLiftBelow) &&
            profile.retractLiftEnforce in RETRACT_LIFT_ENFORCEMENTS &&
            profile.travelSlope in 1f..90f &&
            profile.longRetractionWhenCutLevel in 0..2 &&
            profile.retractionDistanceWhenCut in 10f..18f &&
            profile.extruderClearanceRadius in 0.1f..1_000f &&
            profile.extruderClearanceHeightToRod in 0.1f..1_500f &&
            profile.extruderClearanceHeightToLid in 0.1f..1_500f &&
            profile.coolingTubeRetraction in 0f..1_000f &&
            profile.coolingTubeLength in 0f..1_000f &&
            profile.parkingPosRetraction in 0f..1_000f &&
            profile.extraLoadingMove in -1_000f..1_000f &&
            profile.machineStartGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.machineEndGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.beforeLayerChangeGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.layerChangeGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.changeFilamentGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.printingByObjectGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH

    fun filament(profile: FilamentProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.nativeName.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.nozzleTemp in 150..400 &&
            profile.firstLayerNozzleTemp in 150..400 &&
            profile.idleTemperature in 0..500 &&
            listOf(
                profile.bedTemp,
                profile.firstLayerBedTemp,
                profile.texturedPlateTemp,
                profile.firstLayerTexturedPlateTemp,
                profile.engineeringPlateTemp,
                profile.firstLayerEngineeringPlateTemp,
                profile.coolPlateTemp,
                profile.firstLayerCoolPlateTemp,
                profile.texturedCoolPlateTemp,
                profile.firstLayerTexturedCoolPlateTemp,
                profile.superTackPlateTemp,
                profile.firstLayerSuperTackPlateTemp,
                profile.graphicEffectPlateTemp,
                profile.firstLayerGraphicEffectPlateTemp,
            ).all { it in 0..160 } &&
            profile.flowRatio in 0.5f..1.5f &&
            profile.maxVolumetricSpeed in 0.1f..100f &&
            profile.diameter in 0.5f..4f &&
            profile.density in 0f..10f &&
            profile.costPerKilogram in 0f..1_000_000f &&
            profile.shrinkageXyPercent in 10f..200f &&
            profile.shrinkageZPercent in 10f..200f &&
            listOf(
                profile.fanMinSpeed,
                profile.fanMaxSpeed,
                profile.overhangFanSpeed,
                profile.additionalCoolingFanSpeed,
            )
                .all { it in 0..100 } &&
            profile.fanCoolingLayerTime in 0f..1_000f &&
            profile.overhangFanThreshold in OVERHANG_FAN_THRESHOLDS &&
            profile.internalBridgeFanSpeed in -1..100 &&
            profile.supportInterfaceFanSpeed in -1..100 &&
            profile.minimalPurgeOnWipeTower in MIN_PURGE_VOLUME..MAX_PURGE_VOLUME &&
            listOf(
                profile.loadingSpeed,
                profile.loadingSpeedStart,
                profile.unloadingSpeed,
                profile.unloadingSpeedStart,
                profile.toolchangeDelay,
                profile.stampingLoadingSpeed,
                profile.stampingDistance,
                profile.coolingInitialSpeed,
                profile.coolingFinalSpeed,
                profile.multitoolRammingVolume,
                profile.multitoolRammingFlow,
            ).all { it in 0f..1_000f } &&
            profile.coolingMoves in 0..20 &&
            profile.rammingParameters.isSafeRammingParameters() &&
            profile.softeningTemperature in 0..500 &&
            profile.nozzleTemperatureRangeLow in 0..500 &&
            profile.nozzleTemperatureRangeHigh in profile.nozzleTemperatureRangeLow..500 &&
            profile.chamberTemperature in 0..200 &&
            profile.duringPrintExhaustFanSpeed in 0..100 &&
            profile.completePrintExhaustFanSpeed in 0..100 &&
            profile.retractLength.isNullOrIn(0f..100f) &&
            profile.retractSpeed.isNullOrIn(0f..500f) &&
            profile.deretractSpeed.isNullOrIn(0f..500f) &&
            profile.retractionMinimumTravel.isNullOrIn(0f..1_000f) &&
            profile.wipeDistance.isNullOrIn(0f..100f) &&
            profile.retractBeforeWipe.isNullOrIn(0f..100f) &&
            profile.retractRestartExtra.isNullOrIn(-100f..100f) &&
            profile.zHop.isNullOrIn(0f..5f) &&
            (profile.zHopType == null || profile.zHopType in Z_HOP_TYPES) &&
            profile.retractLiftAbove.isNullOrIn(0f..1_500f) &&
            profile.retractLiftBelow.isNullOrIn(0f..1_500f) &&
            (profile.retractLiftEnforce == null ||
                profile.retractLiftEnforce in RETRACT_LIFT_ENFORCEMENTS) &&
            profile.retractionDistanceWhenCut.isNullOrIn(10f..18f) &&
            profile.slowDownLayerTime in 0f..600f &&
            profile.slowDownMinSpeed in 0f..500f &&
            profile.closeFanFirstLayers in 0..10_000 &&
            profile.fullFanSpeedLayer in 0..10_000 &&
            profile.pressureAdvance in 0f..10f &&
            profile.filamentStartGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.filamentEndGcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.compatiblePrinters.isSafeCompatibilityList()

    fun slicing(profile: QualityProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.nozzleDiameter in 0.1f..2f &&
            profile.layerHeightMm in 0.02f..(profile.nozzleDiameter * 0.9f) &&
            profile.firstLayerHeightMm in 0.02f..1f &&
            profile.perimeters in 0..20 &&
            profile.fillDensity in 0f..1f &&
            profile.printSpeed in 1f..2_000f &&
            listOf(
                profile.innerWallSpeed,
                profile.sparseInfillSpeed,
                profile.internalSolidInfillSpeed,
                profile.topSurfaceSpeed,
                profile.supportSpeed,
                profile.bridgeSpeed,
                profile.gapInfillSpeed,
                profile.firstLayerInfillSpeed,
                profile.supportInterfaceSpeed,
            ).all { it in 0f..2_000f } &&
            featureSpeedIsValid(profile.internalBridgeSpeed, profile.internalBridgeSpeedPercent) &&
            overhangSpeedIsValid(profile.overhangSpeed1, profile.overhangSpeed1Percent) &&
            overhangSpeedIsValid(profile.overhangSpeed2, profile.overhangSpeed2Percent) &&
            overhangSpeedIsValid(profile.overhangSpeed3, profile.overhangSpeed3Percent) &&
            overhangSpeedIsValid(profile.overhangSpeed4, profile.overhangSpeed4Percent) &&
            profile.printFlowRatio in 0f..2f &&
            listOf(
                profile.bridgeFlowRatio,
                profile.internalBridgeFlowRatio,
                profile.topSurfaceFlowRatio,
                profile.bottomSurfaceFlowRatio,
            ).all { it in 0.1f..2f } &&
            listOf(
                profile.defaultAcceleration,
                profile.outerWallAcceleration,
                profile.innerWallAcceleration,
                profile.topSurfaceAcceleration,
                profile.travelAcceleration,
                profile.firstLayerAcceleration,
            ).all { it in 0f..100_000f } &&
            featureAccelerationIsValid(profile.bridgeAcceleration, profile.bridgeAccelerationPercent) &&
            featureAccelerationIsValid(profile.sparseInfillAcceleration, profile.sparseInfillAccelerationPercent) &&
            featureAccelerationIsValid(
                profile.internalSolidInfillAcceleration,
                profile.internalSolidInfillAccelerationPercent,
            ) &&
            listOf(
                profile.defaultJerk,
                profile.outerWallJerk,
                profile.innerWallJerk,
                profile.topSurfaceJerk,
                profile.infillJerk,
                profile.firstLayerJerk,
                profile.travelJerk,
            ).all { it in 0f..2_000f } &&
            profile.bridgeDensity in 10f..100f &&
            profile.internalBridgeDensity in 10f..100f &&
            profile.bridgeAngle in 0f..360f &&
            profile.internalBridgeAngle in 0f..360f &&
            profile.extraBridgeLayer in setOf(
                "disabled", "external_bridge_only", "internal_bridge_only", "apply_to_all",
            ) &&
            profile.internalBridgeFilter in setOf("disabled", "limited", "nofilter") &&
            profile.travelSpeed in 1f..2_000f &&
            profile.travelSpeedZ in 0f..2_000f &&
            featureSpeedIsValid(
                profile.gcodeSettings.initialLayerTravelSpeed,
                profile.gcodeSettings.initialLayerTravelSpeedPercent,
            ) &&
            profile.gcodeSettings.accelToDecelFactor in 1f..100f &&
            profile.extrusionRateSmoothing.maximumSlope in 0f..10_000f &&
            profile.extrusionRateSmoothing.segmentLength in 0.5f..5f &&
            profile.maxTravelDetourDistance in 0f..1_000f &&
            optionalFeatureSpeedIsValid(profile.smallPerimeterSpeed, profile.smallPerimeterSpeedPercent) &&
            profile.smallPerimeterThreshold in 0f..1_000_000f &&
            profile.resolution in 0.001f..100f &&
            profile.precision.mode in setOf("regular", "even_odd", "close_holes") &&
            profile.precision.closingRadius in 0f..10f &&
            profile.precision.polyholes.detectionMargin in 0f..10f &&
            profile.seamGap in 0f..1_000f &&
            optionalFeatureSpeedIsValid(profile.wipeSpeed, profile.wipeSpeedPercent) &&
            listOf(
                profile.outerWallLineWidth,
                profile.innerWallLineWidth,
                profile.topSurfaceLineWidth,
                profile.sparseInfillLineWidth,
                profile.internalSolidInfillLineWidth,
                profile.supportLineWidth,
                profile.initialLayerLineWidth,
            ).all { it in 0f..3f } &&
            profile.wallGenerator in setOf("arachne", "classic") &&
            profile.wallTransitionLength in 0f..10_000f &&
            profile.wallTransitionFilterDeviation in 0f..10_000f &&
            profile.wallTransitionAngle in 1f..59f &&
            profile.wallDistributionCount in 1..100 &&
            profile.minimumFeatureSize in 0f..10_000f &&
            profile.precision.minimumWallWidth in 0f..1_000f &&
            profile.precision.firstLayerMinimumWallWidth in 0f..1_000f &&
            profile.minimumWallLengthFactor in 0f..100f &&
            profile.wallSequence in setOf("inner-outer", "outer-inner", "inner-outer-inner") &&
            profile.wallDirection in setOf("auto", "ccw", "cw") &&
            profile.printableOverhangs.maximumAngle in 0f..90f &&
            profile.printableOverhangs.holeArea in 0f..1_000_000f &&
            profile.gcodeSettings.slowDownLayers in 0..1_000 &&
            profile.minWidthTopSurface in 0f..1_500f &&
            profile.overhangReverseThreshold in 0f..2_000f &&
            profile.counterboreHoleBridging in setOf("none", "partiallybridge", "sacrificiallayer") &&
            profile.topSolidLayers in 0..100 &&
            profile.bottomSolidLayers in 0..100 &&
            profile.topShellThickness in 0f..100f &&
            profile.bottomShellThickness in 0f..100f &&
            profile.surfaceDensity.topPercent in 0f..100f &&
            profile.surfaceDensity.bottomPercent in 10f..100f &&
            profile.fillPattern in SPARSE_INFILL_PATTERNS &&
            profile.fillMultiline in 1..5 &&
            (profile.fillPattern in MULTILINE_INFILL_PATTERNS || profile.fillMultiline == 1) &&
            profile.topSurfacePattern in INFILL_PATTERNS &&
            profile.bottomSurfacePattern in INFILL_PATTERNS &&
            profile.internalSolidInfillPattern in INFILL_PATTERNS &&
            profile.infillWallOverlap in 0f..100f &&
            profile.topBottomInfillWallOverlap in 0f..100f &&
            combinationHeightIsValid(
                profile.infillCombinationMaxLayerHeight,
                profile.infillCombinationMaxLayerHeightPercent,
            ) &&
            profile.infillDirection in 0f..360f &&
            profile.solidInfillDirection in 0f..360f &&
            rotationTemplateIsValid(profile.sparseInfillRotationTemplate) &&
            rotationTemplateIsValid(profile.solidInfillRotationTemplate) &&
            profile.minimumSparseInfillArea in 0f..1_000_000f &&
            profile.infillAnchor in 0f..1_000f &&
            profile.infillAnchorMax in 0f..1_000f &&
            profile.skeletonInfillDensity in 0f..100f &&
            profile.skinInfillDensity in 0f..100f &&
            profile.skinInfillDepth in 0f..100f &&
            profile.infillLockDepth in 0f..100f &&
            profile.infillShiftStep in 0f..10f &&
            lineWidthIsValid(profile.skinInfillLineWidth, profile.skinInfillLineWidthPercent) &&
            lineWidthIsValid(profile.skeletonInfillLineWidth, profile.skeletonInfillLineWidthPercent) &&
            profile.lateralInfill.firstAngle in -75f..75f &&
            profile.lateralInfill.secondAngle in -75f..75f &&
            profile.lateralInfill.overhangAngle in 15f..75f &&
            profile.gapFillTarget in setOf("everywhere", "topbottom", "nowhere") &&
            profile.filterOutGapFill in 0f..1_000_000f &&
            profile.supportType in setOf(
                "normal(auto)", "tree(auto)", "normal(manual)", "tree(manual)",
            ) &&
            profile.supportAngle in 0f..90f &&
            profile.supportInterfaceTopLayers in 0..20 &&
            profile.supportInterfaceBottomLayers in -1..20 &&
            profile.supportInterfaceSpacing in 0f..20f &&
            profile.supportBottomInterfaceSpacing in 0f..20f &&
            profile.supportTopZDistance in 0f..20f &&
            profile.supportBottomZDistance in 0f..20f &&
            profile.supportObjectXYDistance in 0f..20f &&
            profile.supportBasePattern in SUPPORT_BASE_PATTERNS &&
            profile.supportInterfacePattern in SUPPORT_INTERFACE_PATTERNS &&
            profile.supportStyle in SUPPORT_STYLES &&
            profile.supportStyle in compatibleSupportStyles(profile.supportType) &&
            profile.fuzzySkin.type in setOf("none", "external", "all", "allwalls") &&
            profile.fuzzySkin.pointDistance in 0f..5f &&
            profile.fuzzySkin.thickness in 0f..1f &&
            profile.fuzzySkin.mode in setOf("displacement", "extrusion", "combined") &&
            profile.fuzzySkin.noiseType in setOf("classic", "perlin", "billow", "ridgedmulti", "voronoi") &&
            profile.fuzzySkin.scale in 0.1f..500f &&
            profile.fuzzySkin.octaves in 1..10 &&
            profile.fuzzySkin.persistence in 0.01f..1f &&
            profile.supportAdvanced.patternAngle in 0f..359f &&
            profile.supportAdvanced.thresholdOverlap in 0f..(
                if (profile.supportAdvanced.thresholdOverlapPercent) 100f else 0.5f
            ) &&
            profile.supportAdvanced.objectFirstLayerGap in 0f..10f &&
            profile.supportAdvanced.ironingPattern in setOf("rectilinear", "concentric") &&
            profile.supportAdvanced.ironingFlow in 0f..100f &&
            profile.supportAdvanced.ironingSpacing in 0f..1f &&
            profile.supportBasePatternSpacing in 0f..100f &&
            profile.supportExpansion in -100f..100f &&
            profile.treeSupportBranchAngle in 0f..60f &&
            profile.treeSupportBranchDistance in 1f..10f &&
            profile.treeSupportBranchDiameter in 1f..10f &&
            profile.supportCoverage.enforcedLayers in 0..5_000 &&
            profile.treeSupportWallCount in 0..2 &&
            profile.treeSupportTipDiameter in 0.1f..100f &&
            profile.treeSupportPreferredBranchAngle in 10f..85f &&
            profile.treeSupportBranchDensity in 5f..100f &&
            profile.treeSupportOrganicBranchAngle in 0f..60f &&
            profile.treeSupportOrganicBranchDistance in 1f..10f &&
            profile.treeSupportOrganicBranchDiameter in 1f..10f &&
            profile.treeSupportOrganicBranchDiameter >= profile.treeSupportTipDiameter &&
            profile.treeSupportBranchDiameterAngle in 0f..15f &&
            profile.treeSupportBrimWidth in 0f..100f &&
            profile.supportFilament in 0..MAX_FILAMENT_SLOTS &&
            profile.supportInterfaceFilament in 0..MAX_FILAMENT_SLOTS &&
            profile.featureFilaments.baseFirstLayers in 0..1_000 &&
            profile.featureFilaments.baseLastLayers in 0..1_000 &&
            profile.featureFilaments.sparseInfillFilament in 1..MAX_FILAMENT_SLOTS &&
            profile.featureFilaments.wallFilament in 1..MAX_FILAMENT_SLOTS &&
            profile.featureFilaments.solidInfillFilament in 1..MAX_FILAMENT_SLOTS &&
            profile.featureFilaments.wipeTowerFilament in 0..MAX_FILAMENT_SLOTS &&
            profile.wipeTowerWidth in 10f..300f &&
            profile.multiMaterial.primeVolume in 1f..1_000f &&
            purgeVolumesAreValid(profile.multiMaterial.purgeVolumes) &&
            profile.multiMaterial.primeTowerBrimWidth in 0f..100f &&
            profile.multiMaterial.wipeTowerRotationAngle in 0f..359f &&
            profile.multiMaterial.wipeTowerBridging in 0f..1_000f &&
            profile.multiMaterial.wipeTowerExtraSpacing in 1f..1_000f &&
            profile.multiMaterial.wipeTowerExtraFlow in 1f..1_000f &&
            profile.multiMaterial.wipeTowerMaxPurgeSpeed in 1f..2_000f &&
            profile.multiMaterial.wipeTowerWallType in setOf("rectangle", "cone", "rib") &&
            profile.multiMaterial.wipeTowerConeAngle in 0f..90f &&
            profile.multiMaterial.wipeTowerExtraRibLength in -1_000f..300f &&
            profile.multiMaterial.wipeTowerRibWidth in 0f..1_000f &&
            profile.multiMaterial.standbyTemperatureDelta in -500..500 &&
            profile.multiMaterial.preheatTime in 0f..120f &&
            profile.multiMaterial.preheatDeltaTemperature in -50..50 &&
            profile.multiMaterial.preheatSteps in 1..10 &&
            profile.multiMaterial.segmentedRegionMaxWidth in 0f..1_000f &&
            profile.multiMaterial.segmentedRegionInterlockingDepth in 0f..1_000f &&
            (
                profile.multiMaterial.segmentedRegionInterlockingDepth == 0f ||
                    profile.multiMaterial.segmentedRegionInterlockingDepth <=
                    profile.multiMaterial.segmentedRegionMaxWidth
            ) &&
            profile.multiMaterial.interlockingBeamWidth in 0.01f..1_000f &&
            profile.multiMaterial.interlockingOrientation in 0f..360f &&
            profile.multiMaterial.interlockingBeamLayerCount in 1..1_000 &&
            profile.multiMaterial.interlockingDepth in 1..1_000 &&
            profile.multiMaterial.interlockingBoundaryAvoidance in 0..1_000 &&
            profile.brimType in setOf(
                "auto_brim", "brim_ears", "outer_only", "inner_only", "outer_and_inner", "no_brim",
            ) &&
            profile.brimObjectGap in 0f..20f &&
            profile.precision.brimEars.maximumAngle in 0f..180f &&
            profile.precision.brimEars.detectionRadius in 0f..1_000f &&
            profile.raftLayers in 0..100 &&
            profile.raftContactDistance in 0f..20f &&
            profile.raftExpansion in 0f..1_000f &&
            profile.raftFirstLayerDensity in 10f..100f &&
            profile.raftFirstLayerExpansion in 0f..1_000f &&
            profile.seamPosition in SEAM_POSITIONS &&
            profile.scarfSeam.type in SCARF_TYPES &&
            profile.scarfSeam.angleThreshold in 0..180 &&
            profile.scarfSeam.overhangThreshold in 0f..100f &&
            profile.scarfSeam.speed in 1f..(
                if (profile.scarfSeam.speedPercent) 1_000f else 2_000f
            ) &&
            profile.scarfSeam.flowRatio in 0f..2f &&
            profile.scarfSeam.startHeight in 0f..(
                if (profile.scarfSeam.startHeightPercent) 1_000f else 10f
            ) &&
            profile.scarfSeam.length in 0f..1_000_000f &&
            profile.scarfSeam.steps in 1..1_000 &&
            profile.ironing.type in IRONING_TYPES &&
            profile.ironing.pattern in INFILL_PATTERNS &&
            profile.ironing.flow in 0f..100f &&
            profile.ironing.spacing in 0f..1f &&
            profile.ironing.inset in 0f..100f &&
            profile.ironing.speed in 0f..2_000f &&
            profile.ironing.angle in -1f..359f &&
            profile.ensureVerticalShellThickness in setOf(
                "none", "ensure_critical_only", "ensure_moderate", "ensure_all",
            ) &&
            profile.xyHoleCompensation in -2f..2f &&
            profile.xyContourCompensation in -2f..2f &&
            profile.elephantFootCompensation in 0f..2f &&
            profile.elephantFootCompensationLayers in 1..100 &&
            profile.maxBridgeLength in 0f..1_000_000f &&
            profile.printSequence in setOf("by layer", "by object") &&
            profile.printOrder in setOf("default", "as_obj_list") &&
            profile.spiralModeMaxXySmoothing in 0f..(
                if (profile.spiralModeMaxXySmoothingPercent) 1_000f else 10f
            ) &&
            profile.spiralStartingFlowRatio in 0f..1f &&
            profile.spiralFinishingFlowRatio in 0f..1f &&
            profile.skirtLoops in 0..100 &&
            profile.skirtDistance in 0f..1_000f &&
            profile.skirtStartAngle in -180f..180f &&
            profile.skirtHeight in 0..10_000 &&
            profile.skirtSpeed in 0f..2_000f &&
            profile.minimumSkirtLength in 0f..1_000_000f &&
            profile.draftShield in setOf("disabled", "enabled") &&
            profile.brimWidth in 0f..1_000f &&
            profile.compatiblePrinters.isSafeCompatibilityList()

    private fun String.isSafeLabel(): Boolean = isNotBlank() && length <= MAX_LABEL_LENGTH

    private fun String?.isSafeOptionalLabel(): Boolean = this == null || isSafeLabel()

    private fun List<String>.isSafeCompatibilityList(): Boolean =
        size <= MAX_COMPATIBILITY_ENTRIES && all { it.isSafeLabel() }

    private fun overhangSpeedIsValid(value: Float, percent: Boolean): Boolean =
        value in 0f..(if (percent) 100f else 2_000f)

    private fun featureSpeedIsValid(value: Float, percent: Boolean): Boolean =
        value in 1f..(if (percent) 1_000f else 2_000f)

    private fun optionalFeatureSpeedIsValid(value: Float, percent: Boolean): Boolean =
        value in 0f..(if (percent) 1_000f else 2_000f)

    private fun featureAccelerationIsValid(value: Float, percent: Boolean): Boolean =
        value in 0f..(if (percent) 1_000f else 100_000f)

    private fun combinationHeightIsValid(value: Float, percent: Boolean): Boolean =
        value in 0f..(if (percent) 1_000f else 10f)

    private fun lineWidthIsValid(value: Float, percent: Boolean): Boolean =
        value in 0f..(if (percent) 1_000f else 10f)

    private fun Float?.isNullOrIn(range: ClosedFloatingPointRange<Float>): Boolean =
        this == null || this in range

    private val INFILL_PATTERNS = setOf(
        "monotonic", "monotonicline", "rectilinear", "alignedrectilinear", "zigzag",
        "crosszag", "lockedzag", "line", "grid", "triangles", "tri-hexagon", "cubic",
        "adaptivecubic", "quartercubic", "supportcubic", "lightning", "honeycomb",
        "3dhoneycomb", "lateral-honeycomb", "lateral-lattice", "crosshatch", "tpmsd",
        "tpmsfk", "gyroid", "concentric", "hilbertcurve", "archimedeanchords",
        "octagramspiral",
    )
    private val SUPPORT_BASE_PATTERNS = setOf(
        "default", "rectilinear", "lightning", "hollow", "rectilinear-grid",
    )
    private val SUPPORT_INTERFACE_PATTERNS = setOf(
        "auto", "rectilinear", "rectilinear_interlaced", "concentric", "grid",
    )
    private val SUPPORT_STYLES = setOf(
        "default", "grid", "snug", "organic", "tree_hybrid", "tree_slim", "tree_strong",
    )
    private val SEAM_POSITIONS = setOf("aligned", "nearest", "back", "random")
    private val SCARF_TYPES = setOf("none", "external", "all")
    private val IRONING_TYPES = setOf("no ironing", "top", "topmost", "solid")
    private val Z_HOP_TYPES = setOf("auto", "normal", "slope", "spiral")

    private const val MAX_LABEL_LENGTH = 512
    private const val MAX_COMPATIBILITY_ENTRIES = 512
    private const val MAX_GCODE_TEMPLATE_LENGTH = 262_144
}

private fun String.isSafeRammingParameters(): Boolean {
    if (toByteArray(Charsets.UTF_8).size > 16_384) return false
    val parts = split('|')
    if (parts.size != 2) return false
    val left = parts[0].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    val right = parts[1].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (left.size < 3 || right.size < 2 || right.size % 2 != 0) return false
    return (left + right).all { token ->
        token.toFloatOrNull()?.let { it.isFinite() && it in 0f..1_000f } == true
    }
}

private fun purgeVolumesAreValid(values: List<Float>): Boolean {
    if (values.isEmpty()) return true
    val size = (1..MAX_FILAMENT_SLOTS).firstOrNull { it * it == values.size } ?: return false
    return values.indices.all { index ->
        val value = values[index]
        value.isFinite() && value in MIN_PURGE_VOLUME..MAX_PURGE_VOLUME &&
            (index / size != index % size || value == 0f)
    }
}

internal fun rotationTemplateIsValid(value: String): Boolean {
    if (value.isBlank()) return true
    if (value.length > 128) return false
    val angles = value.split(',')
    return angles.size in 1..32 && angles.all { token ->
        token.trim().toFloatOrNull()?.let { it.isFinite() && it in -360f..360f } == true
    }
}

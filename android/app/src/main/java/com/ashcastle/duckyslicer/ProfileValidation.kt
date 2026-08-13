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
            profile.maxPrintHeight in 50f..1_500f &&
            profile.nozzleDiameter in 0.1f..2f &&
            profile.extruderCount in 1..MAX_FILAMENT_SLOTS &&
            profile.gcodeFlavor in setOf("marlin", "marlin2", "klipper") &&
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
            profile.zHop in 0f..5f &&
            profile.zHopType in Z_HOP_TYPES &&
            profile.extruderClearanceRadius in 0.1f..1_000f &&
            profile.extruderClearanceHeightToRod in 0.1f..1_500f &&
            profile.extruderClearanceHeightToLid in 0.1f..1_500f &&
            profile.machineStartGcode.length <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.machineEndGcode.length <= MAX_GCODE_TEMPLATE_LENGTH

    fun filament(profile: FilamentProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.nativeName.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.nozzleTemp in 150..400 &&
            profile.firstLayerNozzleTemp in 150..400 &&
            profile.bedTemp in 0..160 &&
            profile.firstLayerBedTemp in 0..160 &&
            profile.flowRatio in 0.5f..1.5f &&
            profile.maxVolumetricSpeed in 0.1f..100f &&
            listOf(profile.fanMinSpeed, profile.fanMaxSpeed, profile.overhangFanSpeed)
                .all { it in 0..100 } &&
            profile.retractLength.isNullOrIn(0f..100f) &&
            profile.retractSpeed.isNullOrIn(0f..500f) &&
            profile.deretractSpeed.isNullOrIn(0f..500f) &&
            profile.retractionMinimumTravel.isNullOrIn(0f..1_000f) &&
            profile.wipeDistance.isNullOrIn(0f..100f) &&
            profile.retractBeforeWipe.isNullOrIn(0f..100f) &&
            profile.retractRestartExtra.isNullOrIn(-100f..100f) &&
            profile.zHop.isNullOrIn(0f..5f) &&
            (profile.zHopType == null || profile.zHopType in Z_HOP_TYPES) &&
            profile.slowDownLayerTime in 0f..600f &&
            profile.slowDownMinSpeed in 0f..500f &&
            profile.closeFanFirstLayers in 0..10_000 &&
            profile.fullFanSpeedLayer in 0..10_000 &&
            profile.pressureAdvance in 0f..10f &&
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
            profile.maxTravelDetourDistance in 0f..1_000f &&
            optionalFeatureSpeedIsValid(profile.smallPerimeterSpeed, profile.smallPerimeterSpeedPercent) &&
            profile.smallPerimeterThreshold in 0f..1_000_000f &&
            profile.resolution in 0.001f..100f &&
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
            profile.minimumWallLengthFactor in 0f..100f &&
            profile.wallSequence in setOf("inner-outer", "outer-inner", "inner-outer-inner") &&
            profile.wallDirection in setOf("auto", "ccw", "cw") &&
            profile.minWidthTopSurface in 0f..1_500f &&
            profile.overhangReverseThreshold in 0f..2_000f &&
            profile.counterboreHoleBridging in setOf("none", "partiallybridge", "sacrificiallayer") &&
            profile.topSolidLayers in 0..100 &&
            profile.bottomSolidLayers in 0..100 &&
            profile.topShellThickness in 0f..100f &&
            profile.bottomShellThickness in 0f..100f &&
            profile.fillPattern in INFILL_PATTERNS &&
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
            profile.minimumSparseInfillArea in 0f..1_000_000f &&
            profile.infillAnchor in 0f..1_000f &&
            profile.infillAnchorMax in 0f..1_000f &&
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
            profile.supportBasePatternSpacing in 0f..100f &&
            profile.supportExpansion in -100f..100f &&
            profile.treeSupportBranchAngle in 0f..60f &&
            profile.treeSupportBranchDistance in 1f..10f &&
            profile.treeSupportBranchDiameter in 1f..10f &&
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
            profile.wipeTowerWidth in 10f..300f &&
            profile.multiMaterial.primeVolume in 1f..1_000f &&
            profile.multiMaterial.primeTowerBrimWidth in 0f..100f &&
            profile.multiMaterial.standbyTemperatureDelta in -500..500 &&
            profile.brimType in setOf(
                "auto_brim", "brim_ears", "outer_only", "inner_only", "outer_and_inner", "no_brim",
            ) &&
            profile.brimObjectGap in 0f..20f &&
            profile.raftLayers in 0..100 &&
            profile.raftContactDistance in 0f..20f &&
            profile.raftExpansion in 0f..1_000f &&
            profile.raftFirstLayerDensity in 10f..100f &&
            profile.raftFirstLayerExpansion in 0f..1_000f &&
            profile.seamPosition in SEAM_POSITIONS &&
            profile.ironingType in IRONING_TYPES &&
            profile.ironingPattern in INFILL_PATTERNS &&
            profile.ironingFlow in 0f..100f &&
            profile.ironingSpacing in 0f..1f &&
            profile.ironingSpeed in 0f..2_000f &&
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
        "default", "grid", "snug", "organic", "tree_hybrid", "tree_slim",
    )
    private val SEAM_POSITIONS = setOf("aligned", "nearest", "back", "random")
    private val IRONING_TYPES = setOf("no ironing", "top", "topmost", "solid")
    private val Z_HOP_TYPES = setOf("auto", "normal", "slope", "spiral")

    private const val MAX_LABEL_LENGTH = 512
    private const val MAX_COMPATIBILITY_ENTRIES = 512
    private const val MAX_GCODE_TEMPLATE_LENGTH = 262_144
}

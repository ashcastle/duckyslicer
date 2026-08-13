package com.ashcastle.duckyslicer

import java.io.DataInputStream

/** Bounded-allocation reader that creates one QualityProfile per binary record. */
internal class QualityProfileBinaryBuilder(base: QualityProfile = QualityProfile.STANDARD) {
    private var id: String = base.id
    private var name: String = base.name
    private var layerHeightMm: Float = base.layerHeightMm
    private var firstLayerHeightMm: Float = base.firstLayerHeightMm
    private var perimeters: Int = base.perimeters
    private var fillDensity: Float = base.fillDensity
    private var printSpeed: Float = base.printSpeed
    private var nozzleDiameter: Float = base.nozzleDiameter
    private var innerWallSpeed: Float = base.innerWallSpeed
    private var sparseInfillSpeed: Float = base.sparseInfillSpeed
    private var internalSolidInfillSpeed: Float = base.internalSolidInfillSpeed
    private var topSurfaceSpeed: Float = base.topSurfaceSpeed
    private var supportSpeed: Float = base.supportSpeed
    private var bridgeSpeed: Float = base.bridgeSpeed
    private var gapInfillSpeed: Float = base.gapInfillSpeed
    private var firstLayerInfillSpeed: Float = base.firstLayerInfillSpeed
    private var supportInterfaceSpeed: Float = base.supportInterfaceSpeed
    private var internalBridgeSpeed: Float = base.internalBridgeSpeed
    private var internalBridgeSpeedPercent: Boolean = base.internalBridgeSpeedPercent
    private var overhangSpeedEnabled: Boolean = base.overhangSpeedEnabled
    private var overhangSpeed1: Float = base.overhangSpeed1
    private var overhangSpeed1Percent: Boolean = base.overhangSpeed1Percent
    private var overhangSpeed2: Float = base.overhangSpeed2
    private var overhangSpeed2Percent: Boolean = base.overhangSpeed2Percent
    private var overhangSpeed3: Float = base.overhangSpeed3
    private var overhangSpeed3Percent: Boolean = base.overhangSpeed3Percent
    private var overhangSpeed4: Float = base.overhangSpeed4
    private var overhangSpeed4Percent: Boolean = base.overhangSpeed4Percent
    private var bridgeFlowRatio: Float = base.bridgeFlowRatio
    private var internalBridgeFlowRatio: Float = base.internalBridgeFlowRatio
    private var topSurfaceFlowRatio: Float = base.topSurfaceFlowRatio
    private var bottomSurfaceFlowRatio: Float = base.bottomSurfaceFlowRatio
    private var bridgeDensity: Float = base.bridgeDensity
    private var internalBridgeDensity: Float = base.internalBridgeDensity
    private var bridgeAngle: Float = base.bridgeAngle
    private var internalBridgeAngle: Float = base.internalBridgeAngle
    private var bridgeNoSupport: Boolean = base.bridgeNoSupport
    private var thickBridges: Boolean = base.thickBridges
    private var thickInternalBridges: Boolean = base.thickInternalBridges
    private var extraBridgeLayer: String = base.extraBridgeLayer
    private var internalBridgeFilter: String = base.internalBridgeFilter
    private var defaultAcceleration: Float = base.defaultAcceleration
    private var outerWallAcceleration: Float = base.outerWallAcceleration
    private var innerWallAcceleration: Float = base.innerWallAcceleration
    private var topSurfaceAcceleration: Float = base.topSurfaceAcceleration
    private var travelAcceleration: Float = base.travelAcceleration
    private var firstLayerAcceleration: Float = base.firstLayerAcceleration
    private var bridgeAcceleration: Float = base.bridgeAcceleration
    private var bridgeAccelerationPercent: Boolean = base.bridgeAccelerationPercent
    private var sparseInfillAcceleration: Float = base.sparseInfillAcceleration
    private var sparseInfillAccelerationPercent: Boolean = base.sparseInfillAccelerationPercent
    private var internalSolidInfillAcceleration: Float = base.internalSolidInfillAcceleration
    private var internalSolidInfillAccelerationPercent: Boolean = base.internalSolidInfillAccelerationPercent
    private var supportEnabled: Boolean = base.supportEnabled
    private var brimType: String = base.brimType
    private var brimWidth: Float = base.brimWidth
    private var brimObjectGap: Float = base.brimObjectGap
    private var raftLayers: Int = base.raftLayers
    private var raftContactDistance: Float = base.raftContactDistance
    private var raftExpansion: Float = base.raftExpansion
    private var raftFirstLayerDensity: Float = base.raftFirstLayerDensity
    private var raftFirstLayerExpansion: Float = base.raftFirstLayerExpansion
    private var topSolidLayers: Int = base.topSolidLayers
    private var bottomSolidLayers: Int = base.bottomSolidLayers
    private var topShellThickness: Float = base.topShellThickness
    private var bottomShellThickness: Float = base.bottomShellThickness
    private var fillPattern: String = base.fillPattern
    private var topSurfacePattern: String = base.topSurfacePattern
    private var bottomSurfacePattern: String = base.bottomSurfacePattern
    private var internalSolidInfillPattern: String = base.internalSolidInfillPattern
    private var infillFirst: Boolean = base.infillFirst
    private var infillWallOverlap: Float = base.infillWallOverlap
    private var topBottomInfillWallOverlap: Float = base.topBottomInfillWallOverlap
    private var infillCombination: Boolean = base.infillCombination
    private var infillCombinationMaxLayerHeight: Float = base.infillCombinationMaxLayerHeight
    private var infillCombinationMaxLayerHeightPercent: Boolean = base.infillCombinationMaxLayerHeightPercent
    private var infillDirection: Float = base.infillDirection
    private var solidInfillDirection: Float = base.solidInfillDirection
    private var alignInfillDirectionToModel: Boolean = base.alignInfillDirectionToModel
    private var minimumSparseInfillArea: Float = base.minimumSparseInfillArea
    private var infillAnchor: Float = base.infillAnchor
    private var infillAnchorPercent: Boolean = base.infillAnchorPercent
    private var infillAnchorMax: Float = base.infillAnchorMax
    private var infillAnchorMaxPercent: Boolean = base.infillAnchorMaxPercent
    private var gapFillTarget: String = base.gapFillTarget
    private var filterOutGapFill: Float = base.filterOutGapFill
    private var reduceCrossingWall: Boolean = base.reduceCrossingWall
    private var maxTravelDetourDistance: Float = base.maxTravelDetourDistance
    private var maxTravelDetourDistancePercent: Boolean = base.maxTravelDetourDistancePercent
    private var reduceInfillRetraction: Boolean = base.reduceInfillRetraction
    private var travelSpeed: Float = base.travelSpeed
    private var firstLayerSpeed: Float = base.firstLayerSpeed
    private var supportType: String = base.supportType
    private var supportAngle: Float = base.supportAngle
    private var supportInterfaceTopLayers: Int = base.supportInterfaceTopLayers
    private var supportInterfaceBottomLayers: Int = base.supportInterfaceBottomLayers
    private var supportInterfaceSpacing: Float = base.supportInterfaceSpacing
    private var supportBottomInterfaceSpacing: Float = base.supportBottomInterfaceSpacing
    private var supportTopZDistance: Float = base.supportTopZDistance
    private var supportBottomZDistance: Float = base.supportBottomZDistance
    private var supportObjectXYDistance: Float = base.supportObjectXYDistance
    private var supportBasePattern: String = base.supportBasePattern
    private var supportInterfacePattern: String = base.supportInterfacePattern
    private var supportStyle: String = base.supportStyle
    private var supportFilament: Int = base.supportFilament
    private var supportInterfaceFilament: Int = base.supportInterfaceFilament
    private var wipeTowerEnabled: Boolean = base.wipeTowerEnabled
    private var wipeTowerWidth: Float = base.wipeTowerWidth
    private var skirtLoops: Int = base.skirtLoops
    private var skirtDistance: Float = base.skirtDistance
    private var skirtHeight: Int = base.skirtHeight
    private var skirtSpeed: Float = base.skirtSpeed
    private var minimumSkirtLength: Float = base.minimumSkirtLength
    private var draftShield: String = base.draftShield
    private var outerWallLineWidth: Float = base.outerWallLineWidth
    private var innerWallLineWidth: Float = base.innerWallLineWidth
    private var topSurfaceLineWidth: Float = base.topSurfaceLineWidth
    private var sparseInfillLineWidth: Float = base.sparseInfillLineWidth
    private var internalSolidInfillLineWidth: Float = base.internalSolidInfillLineWidth
    private var supportLineWidth: Float = base.supportLineWidth
    private var initialLayerLineWidth: Float = base.initialLayerLineWidth
    private var smallPerimeterSpeed: Float = base.smallPerimeterSpeed
    private var smallPerimeterSpeedPercent: Boolean = base.smallPerimeterSpeedPercent
    private var smallPerimeterThreshold: Float = base.smallPerimeterThreshold
    private var slowdownForCurledPerimeters: Boolean = base.slowdownForCurledPerimeters
    private var resolution: Float = base.resolution
    private var seamPosition: String = base.seamPosition
    private var staggeredInnerSeams: Boolean = base.staggeredInnerSeams
    private var seamGap: Float = base.seamGap
    private var seamGapPercent: Boolean = base.seamGapPercent
    private var wipeBeforeExternalLoop: Boolean = base.wipeBeforeExternalLoop
    private var wipeOnLoops: Boolean = base.wipeOnLoops
    private var roleBasedWipeSpeed: Boolean = base.roleBasedWipeSpeed
    private var wipeSpeed: Float = base.wipeSpeed
    private var wipeSpeedPercent: Boolean = base.wipeSpeedPercent
    private var ironingType: String = base.ironingType
    private var ironingPattern: String = base.ironingPattern
    private var ironingFlow: Float = base.ironingFlow
    private var ironingSpacing: Float = base.ironingSpacing
    private var ironingSpeed: Float = base.ironingSpeed
    private var wallGenerator: String = base.wallGenerator
    private var wallTransitionLength: Float = base.wallTransitionLength
    private var wallTransitionFilterDeviation: Float = base.wallTransitionFilterDeviation
    private var wallTransitionAngle: Float = base.wallTransitionAngle
    private var wallDistributionCount: Int = base.wallDistributionCount
    private var minimumFeatureSize: Float = base.minimumFeatureSize
    private var minimumWallLengthFactor: Float = base.minimumWallLengthFactor
    private var wallSequence: String = base.wallSequence
    private var wallDirection: String = base.wallDirection
    private var detectThinWalls: Boolean = base.detectThinWalls
    private var detectOverhangWalls: Boolean = base.detectOverhangWalls
    private var onlyOneWallOnTop: Boolean = base.onlyOneWallOnTop
    private var minWidthTopSurface: Float = base.minWidthTopSurface
    private var minWidthTopSurfacePercent: Boolean = base.minWidthTopSurfacePercent
    private var onlyOneWallFirstLayer: Boolean = base.onlyOneWallFirstLayer
    private var extraPerimetersOnOverhangs: Boolean = base.extraPerimetersOnOverhangs
    private var overhangReverse: Boolean = base.overhangReverse
    private var overhangReverseInternalOnly: Boolean = base.overhangReverseInternalOnly
    private var overhangReverseThreshold: Float = base.overhangReverseThreshold
    private var overhangReverseThresholdPercent: Boolean = base.overhangReverseThresholdPercent
    private var counterboreHoleBridging: String = base.counterboreHoleBridging
    private var alternateExtraWall: Boolean = base.alternateExtraWall
    private var ensureVerticalShellThickness: String = base.ensureVerticalShellThickness
    private var detectNarrowInternalSolidInfill: Boolean = base.detectNarrowInternalSolidInfill
    private var xyHoleCompensation: Float = base.xyHoleCompensation
    private var xyContourCompensation: Float = base.xyContourCompensation
    private var elephantFootCompensation: Float = base.elephantFootCompensation
    private var elephantFootCompensationLayers: Int = base.elephantFootCompensationLayers
    private var maxBridgeLength: Float = base.maxBridgeLength
    private var preciseOuterWalls: Boolean = base.preciseOuterWalls
    private var spiralMode: Boolean = base.spiralMode
    private var spiralModeSmooth: Boolean = base.spiralModeSmooth
    private var spiralModeMaxXySmoothing: Float = base.spiralModeMaxXySmoothing
    private var spiralModeMaxXySmoothingPercent: Boolean = base.spiralModeMaxXySmoothingPercent
    private var spiralStartingFlowRatio: Float = base.spiralStartingFlowRatio
    private var spiralFinishingFlowRatio: Float = base.spiralFinishingFlowRatio
    private var brand: String? = base.brand
    private var compatiblePrinters: List<String> = base.compatiblePrinters

    fun read(input: DataInputStream) {
        readGroup0(input)
        readGroup1(input)
        readGroup2(input)
        readGroup3(input)
        readGroup4(input)
        readGroup5(input)
        readGroup6(input)
        readGroup7(input)
        readGroup8(input)
    }

    private fun readGroup0(input: DataInputStream) {
        id = input.readCatalogString()
        name = input.readCatalogString()
        brand = input.readCatalogString()
        layerHeightMm = input.readFloat()
        firstLayerHeightMm = input.readFloat()
        perimeters = input.readInt()
        fillDensity = input.readFloat()
        printSpeed = input.readFloat()
        innerWallSpeed = input.readFloat()
        sparseInfillSpeed = input.readFloat()
        internalSolidInfillSpeed = input.readFloat()
        topSurfaceSpeed = input.readFloat()
        supportSpeed = input.readFloat()
        bridgeSpeed = input.readFloat()
        gapInfillSpeed = input.readFloat()
        firstLayerInfillSpeed = input.readFloat()
        supportInterfaceSpeed = input.readFloat()
        internalBridgeSpeed = input.readFloat()
        internalBridgeSpeedPercent = input.readCatalogBoolean()
        overhangSpeedEnabled = input.readCatalogBoolean()
    }

    private fun readGroup1(input: DataInputStream) {
        overhangSpeed1 = input.readFloat()
        overhangSpeed1Percent = input.readCatalogBoolean()
        overhangSpeed2 = input.readFloat()
        overhangSpeed2Percent = input.readCatalogBoolean()
        overhangSpeed3 = input.readFloat()
        overhangSpeed3Percent = input.readCatalogBoolean()
        overhangSpeed4 = input.readFloat()
        overhangSpeed4Percent = input.readCatalogBoolean()
        bridgeFlowRatio = input.readFloat()
        internalBridgeFlowRatio = input.readFloat()
        topSurfaceFlowRatio = input.readFloat()
        bottomSurfaceFlowRatio = input.readFloat()
        bridgeDensity = input.readFloat()
        internalBridgeDensity = input.readFloat()
        bridgeAngle = input.readFloat()
        internalBridgeAngle = input.readFloat()
        bridgeNoSupport = input.readCatalogBoolean()
        thickBridges = input.readCatalogBoolean()
        thickInternalBridges = input.readCatalogBoolean()
        extraBridgeLayer = input.readCatalogString()
    }

    private fun readGroup2(input: DataInputStream) {
        internalBridgeFilter = input.readCatalogString()
        defaultAcceleration = input.readFloat()
        outerWallAcceleration = input.readFloat()
        innerWallAcceleration = input.readFloat()
        topSurfaceAcceleration = input.readFloat()
        travelAcceleration = input.readFloat()
        firstLayerAcceleration = input.readFloat()
        bridgeAcceleration = input.readFloat()
        bridgeAccelerationPercent = input.readCatalogBoolean()
        sparseInfillAcceleration = input.readFloat()
        sparseInfillAccelerationPercent = input.readCatalogBoolean()
        internalSolidInfillAcceleration = input.readFloat()
        internalSolidInfillAccelerationPercent = input.readCatalogBoolean()
        nozzleDiameter = input.readFloat()
        supportEnabled = input.readCatalogBoolean()
        brimType = input.readCatalogString()
        brimWidth = input.readFloat()
        brimObjectGap = input.readFloat()
        topSolidLayers = input.readInt()
        bottomSolidLayers = input.readInt()
    }

    private fun readGroup3(input: DataInputStream) {
        topShellThickness = input.readFloat()
        bottomShellThickness = input.readFloat()
        fillPattern = input.readCatalogString()
        topSurfacePattern = input.readCatalogString()
        bottomSurfacePattern = input.readCatalogString()
        internalSolidInfillPattern = input.readCatalogString()
        infillFirst = input.readCatalogBoolean()
        infillWallOverlap = input.readFloat()
        topBottomInfillWallOverlap = input.readFloat()
        infillCombination = input.readCatalogBoolean()
        infillCombinationMaxLayerHeight = input.readFloat()
        infillCombinationMaxLayerHeightPercent = input.readCatalogBoolean()
        infillDirection = input.readFloat()
        solidInfillDirection = input.readFloat()
        alignInfillDirectionToModel = input.readCatalogBoolean()
        minimumSparseInfillArea = input.readFloat()
        infillAnchor = input.readFloat()
        infillAnchorPercent = input.readCatalogBoolean()
        infillAnchorMax = input.readFloat()
        infillAnchorMaxPercent = input.readCatalogBoolean()
    }

    private fun readGroup4(input: DataInputStream) {
        gapFillTarget = input.readCatalogString()
        filterOutGapFill = input.readFloat()
        reduceCrossingWall = input.readCatalogBoolean()
        maxTravelDetourDistance = input.readFloat()
        maxTravelDetourDistancePercent = input.readCatalogBoolean()
        reduceInfillRetraction = input.readCatalogBoolean()
        travelSpeed = input.readFloat()
        firstLayerSpeed = input.readFloat()
        supportType = input.readCatalogString()
        supportAngle = input.readFloat()
        supportInterfaceTopLayers = input.readInt()
        supportInterfaceBottomLayers = input.readInt()
        supportInterfaceSpacing = input.readFloat()
        supportBottomInterfaceSpacing = input.readFloat()
        supportTopZDistance = input.readFloat()
        supportBottomZDistance = input.readFloat()
        supportObjectXYDistance = input.readFloat()
        supportBasePattern = input.readCatalogString()
        supportInterfacePattern = input.readCatalogString()
        supportStyle = input.readCatalogString()
    }

    private fun readGroup5(input: DataInputStream) {
        skirtLoops = input.readInt()
        skirtDistance = input.readFloat()
        skirtHeight = input.readInt()
        skirtSpeed = input.readFloat()
        minimumSkirtLength = input.readFloat()
        draftShield = input.readCatalogString()
        raftLayers = input.readInt()
        raftContactDistance = input.readFloat()
        raftExpansion = input.readFloat()
        raftFirstLayerDensity = input.readFloat()
        raftFirstLayerExpansion = input.readFloat()
        outerWallLineWidth = input.readFloat()
        innerWallLineWidth = input.readFloat()
        topSurfaceLineWidth = input.readFloat()
        sparseInfillLineWidth = input.readFloat()
        internalSolidInfillLineWidth = input.readFloat()
        supportLineWidth = input.readFloat()
        initialLayerLineWidth = input.readFloat()
        smallPerimeterSpeed = input.readFloat()
        smallPerimeterSpeedPercent = input.readCatalogBoolean()
    }

    private fun readGroup6(input: DataInputStream) {
        smallPerimeterThreshold = input.readFloat()
        slowdownForCurledPerimeters = input.readCatalogBoolean()
        resolution = input.readFloat()
        seamPosition = input.readCatalogString()
        staggeredInnerSeams = input.readCatalogBoolean()
        seamGap = input.readFloat()
        seamGapPercent = input.readCatalogBoolean()
        wipeBeforeExternalLoop = input.readCatalogBoolean()
        wipeOnLoops = input.readCatalogBoolean()
        roleBasedWipeSpeed = input.readCatalogBoolean()
        wipeSpeed = input.readFloat()
        wipeSpeedPercent = input.readCatalogBoolean()
        ironingType = input.readCatalogString()
        ironingPattern = input.readCatalogString()
        ironingFlow = input.readFloat()
        ironingSpacing = input.readFloat()
        ironingSpeed = input.readFloat()
        wallGenerator = input.readCatalogString()
        wallTransitionLength = input.readFloat()
        wallTransitionFilterDeviation = input.readFloat()
    }

    private fun readGroup7(input: DataInputStream) {
        wallTransitionAngle = input.readFloat()
        wallDistributionCount = input.readInt()
        minimumFeatureSize = input.readFloat()
        minimumWallLengthFactor = input.readFloat()
        wallSequence = input.readCatalogString()
        wallDirection = input.readCatalogString()
        detectThinWalls = input.readCatalogBoolean()
        detectOverhangWalls = input.readCatalogBoolean()
        onlyOneWallOnTop = input.readCatalogBoolean()
        minWidthTopSurface = input.readFloat()
        minWidthTopSurfacePercent = input.readCatalogBoolean()
        onlyOneWallFirstLayer = input.readCatalogBoolean()
        extraPerimetersOnOverhangs = input.readCatalogBoolean()
        overhangReverse = input.readCatalogBoolean()
        overhangReverseInternalOnly = input.readCatalogBoolean()
        overhangReverseThreshold = input.readFloat()
        overhangReverseThresholdPercent = input.readCatalogBoolean()
        counterboreHoleBridging = input.readCatalogString()
        alternateExtraWall = input.readCatalogBoolean()
        ensureVerticalShellThickness = input.readCatalogString()
    }

    private fun readGroup8(input: DataInputStream) {
        detectNarrowInternalSolidInfill = input.readCatalogBoolean()
        xyHoleCompensation = input.readFloat()
        xyContourCompensation = input.readFloat()
        elephantFootCompensation = input.readFloat()
        elephantFootCompensationLayers = input.readInt()
        maxBridgeLength = input.readFloat()
        preciseOuterWalls = input.readCatalogBoolean()
        supportFilament = input.readInt()
        supportInterfaceFilament = input.readInt()
        wipeTowerEnabled = input.readCatalogBoolean()
        wipeTowerWidth = input.readFloat()
        spiralMode = input.readCatalogBoolean()
        spiralModeSmooth = input.readCatalogBoolean()
        spiralModeMaxXySmoothing = input.readFloat()
        spiralModeMaxXySmoothingPercent = input.readCatalogBoolean()
        spiralStartingFlowRatio = input.readFloat()
        spiralFinishingFlowRatio = input.readFloat()
        compatiblePrinters = input.readCatalogStringList()
    }

    fun build(): QualityProfile = QualityProfile(
        id = id,
        name = name,
        layerHeightMm = layerHeightMm,
        firstLayerHeightMm = firstLayerHeightMm,
        perimeters = perimeters,
        fillDensity = fillDensity,
        printSpeed = printSpeed,
        nozzleDiameter = nozzleDiameter,
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
        supportEnabled = supportEnabled,
        brimType = brimType,
        brimWidth = brimWidth,
        brimObjectGap = brimObjectGap,
        raftLayers = raftLayers,
        raftContactDistance = raftContactDistance,
        raftExpansion = raftExpansion,
        raftFirstLayerDensity = raftFirstLayerDensity,
        raftFirstLayerExpansion = raftFirstLayerExpansion,
        builtIn = true,
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
        firstLayerSpeed = firstLayerSpeed,
        supportType = supportType,
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
        supportStyle = supportStyle,
        supportFilament = supportFilament,
        supportInterfaceFilament = supportInterfaceFilament,
        wipeTowerEnabled = wipeTowerEnabled,
        wipeTowerWidth = wipeTowerWidth,
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
        seamPosition = seamPosition,
        staggeredInnerSeams = staggeredInnerSeams,
        seamGap = seamGap,
        seamGapPercent = seamGapPercent,
        wipeBeforeExternalLoop = wipeBeforeExternalLoop,
        wipeOnLoops = wipeOnLoops,
        roleBasedWipeSpeed = roleBasedWipeSpeed,
        wipeSpeed = wipeSpeed,
        wipeSpeedPercent = wipeSpeedPercent,
        ironingType = ironingType,
        ironingPattern = ironingPattern,
        ironingFlow = ironingFlow,
        ironingSpacing = ironingSpacing,
        ironingSpeed = ironingSpeed,
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
        spiralMode = spiralMode,
        spiralModeSmooth = spiralModeSmooth,
        spiralModeMaxXySmoothing = spiralModeMaxXySmoothing,
        spiralModeMaxXySmoothingPercent = spiralModeMaxXySmoothingPercent,
        spiralStartingFlowRatio = spiralStartingFlowRatio,
        spiralFinishingFlowRatio = spiralFinishingFlowRatio,
        brand = brand,
        compatiblePrinters = compatiblePrinters,
    )
}

internal val QUALITY_BINARY_FIELDS = arrayOf(
    BinaryField("id", BINARY_STRING),
    BinaryField("name", BINARY_STRING),
    BinaryField("brand", BINARY_STRING),
    BinaryField("layerHeightMm", BINARY_FLOAT),
    BinaryField("firstLayerHeightMm", BINARY_FLOAT),
    BinaryField("perimeters", BINARY_INT),
    BinaryField("fillDensity", BINARY_FLOAT),
    BinaryField("printSpeed", BINARY_FLOAT),
    BinaryField("innerWallSpeed", BINARY_FLOAT),
    BinaryField("sparseInfillSpeed", BINARY_FLOAT),
    BinaryField("internalSolidInfillSpeed", BINARY_FLOAT),
    BinaryField("topSurfaceSpeed", BINARY_FLOAT),
    BinaryField("supportSpeed", BINARY_FLOAT),
    BinaryField("bridgeSpeed", BINARY_FLOAT),
    BinaryField("gapInfillSpeed", BINARY_FLOAT),
    BinaryField("firstLayerInfillSpeed", BINARY_FLOAT),
    BinaryField("supportInterfaceSpeed", BINARY_FLOAT),
    BinaryField("internalBridgeSpeed", BINARY_FLOAT),
    BinaryField("internalBridgeSpeedPercent", BINARY_BOOL),
    BinaryField("overhangSpeedEnabled", BINARY_BOOL),
    BinaryField("overhangSpeed1", BINARY_FLOAT),
    BinaryField("overhangSpeed1Percent", BINARY_BOOL),
    BinaryField("overhangSpeed2", BINARY_FLOAT),
    BinaryField("overhangSpeed2Percent", BINARY_BOOL),
    BinaryField("overhangSpeed3", BINARY_FLOAT),
    BinaryField("overhangSpeed3Percent", BINARY_BOOL),
    BinaryField("overhangSpeed4", BINARY_FLOAT),
    BinaryField("overhangSpeed4Percent", BINARY_BOOL),
    BinaryField("bridgeFlowRatio", BINARY_FLOAT),
    BinaryField("internalBridgeFlowRatio", BINARY_FLOAT),
    BinaryField("topSurfaceFlowRatio", BINARY_FLOAT),
    BinaryField("bottomSurfaceFlowRatio", BINARY_FLOAT),
    BinaryField("bridgeDensity", BINARY_FLOAT),
    BinaryField("internalBridgeDensity", BINARY_FLOAT),
    BinaryField("bridgeAngle", BINARY_FLOAT),
    BinaryField("internalBridgeAngle", BINARY_FLOAT),
    BinaryField("bridgeNoSupport", BINARY_BOOL),
    BinaryField("thickBridges", BINARY_BOOL),
    BinaryField("thickInternalBridges", BINARY_BOOL),
    BinaryField("extraBridgeLayer", BINARY_STRING),
    BinaryField("internalBridgeFilter", BINARY_STRING),
    BinaryField("defaultAcceleration", BINARY_FLOAT),
    BinaryField("outerWallAcceleration", BINARY_FLOAT),
    BinaryField("innerWallAcceleration", BINARY_FLOAT),
    BinaryField("topSurfaceAcceleration", BINARY_FLOAT),
    BinaryField("travelAcceleration", BINARY_FLOAT),
    BinaryField("firstLayerAcceleration", BINARY_FLOAT),
    BinaryField("bridgeAcceleration", BINARY_FLOAT),
    BinaryField("bridgeAccelerationPercent", BINARY_BOOL),
    BinaryField("sparseInfillAcceleration", BINARY_FLOAT),
    BinaryField("sparseInfillAccelerationPercent", BINARY_BOOL),
    BinaryField("internalSolidInfillAcceleration", BINARY_FLOAT),
    BinaryField("internalSolidInfillAccelerationPercent", BINARY_BOOL),
    BinaryField("nozzleDiameter", BINARY_FLOAT),
    BinaryField("supportEnabled", BINARY_BOOL),
    BinaryField("brimType", BINARY_STRING),
    BinaryField("brimWidth", BINARY_FLOAT),
    BinaryField("brimObjectGap", BINARY_FLOAT),
    BinaryField("topSolidLayers", BINARY_INT),
    BinaryField("bottomSolidLayers", BINARY_INT),
    BinaryField("topShellThickness", BINARY_FLOAT),
    BinaryField("bottomShellThickness", BINARY_FLOAT),
    BinaryField("fillPattern", BINARY_STRING),
    BinaryField("topSurfacePattern", BINARY_STRING),
    BinaryField("bottomSurfacePattern", BINARY_STRING),
    BinaryField("internalSolidInfillPattern", BINARY_STRING),
    BinaryField("infillFirst", BINARY_BOOL),
    BinaryField("infillWallOverlap", BINARY_FLOAT),
    BinaryField("topBottomInfillWallOverlap", BINARY_FLOAT),
    BinaryField("infillCombination", BINARY_BOOL),
    BinaryField("infillCombinationMaxLayerHeight", BINARY_FLOAT),
    BinaryField("infillCombinationMaxLayerHeightPercent", BINARY_BOOL),
    BinaryField("infillDirection", BINARY_FLOAT),
    BinaryField("solidInfillDirection", BINARY_FLOAT),
    BinaryField("alignInfillDirectionToModel", BINARY_BOOL),
    BinaryField("minimumSparseInfillArea", BINARY_FLOAT),
    BinaryField("infillAnchor", BINARY_FLOAT),
    BinaryField("infillAnchorPercent", BINARY_BOOL),
    BinaryField("infillAnchorMax", BINARY_FLOAT),
    BinaryField("infillAnchorMaxPercent", BINARY_BOOL),
    BinaryField("gapFillTarget", BINARY_STRING),
    BinaryField("filterOutGapFill", BINARY_FLOAT),
    BinaryField("reduceCrossingWall", BINARY_BOOL),
    BinaryField("maxTravelDetourDistance", BINARY_FLOAT),
    BinaryField("maxTravelDetourDistancePercent", BINARY_BOOL),
    BinaryField("reduceInfillRetraction", BINARY_BOOL),
    BinaryField("travelSpeed", BINARY_FLOAT),
    BinaryField("firstLayerSpeed", BINARY_FLOAT),
    BinaryField("supportType", BINARY_STRING),
    BinaryField("supportAngle", BINARY_FLOAT),
    BinaryField("supportInterfaceTopLayers", BINARY_INT),
    BinaryField("supportInterfaceBottomLayers", BINARY_INT),
    BinaryField("supportInterfaceSpacing", BINARY_FLOAT),
    BinaryField("supportBottomInterfaceSpacing", BINARY_FLOAT),
    BinaryField("supportTopZDistance", BINARY_FLOAT),
    BinaryField("supportBottomZDistance", BINARY_FLOAT),
    BinaryField("supportObjectXYDistance", BINARY_FLOAT),
    BinaryField("supportBasePattern", BINARY_STRING),
    BinaryField("supportInterfacePattern", BINARY_STRING),
    BinaryField("supportStyle", BINARY_STRING),
    BinaryField("skirtLoops", BINARY_INT),
    BinaryField("skirtDistance", BINARY_FLOAT),
    BinaryField("skirtHeight", BINARY_INT),
    BinaryField("skirtSpeed", BINARY_FLOAT),
    BinaryField("minimumSkirtLength", BINARY_FLOAT),
    BinaryField("draftShield", BINARY_STRING),
    BinaryField("raftLayers", BINARY_INT),
    BinaryField("raftContactDistance", BINARY_FLOAT),
    BinaryField("raftExpansion", BINARY_FLOAT),
    BinaryField("raftFirstLayerDensity", BINARY_FLOAT),
    BinaryField("raftFirstLayerExpansion", BINARY_FLOAT),
    BinaryField("outerWallLineWidth", BINARY_FLOAT),
    BinaryField("innerWallLineWidth", BINARY_FLOAT),
    BinaryField("topSurfaceLineWidth", BINARY_FLOAT),
    BinaryField("sparseInfillLineWidth", BINARY_FLOAT),
    BinaryField("internalSolidInfillLineWidth", BINARY_FLOAT),
    BinaryField("supportLineWidth", BINARY_FLOAT),
    BinaryField("initialLayerLineWidth", BINARY_FLOAT),
    BinaryField("smallPerimeterSpeed", BINARY_FLOAT),
    BinaryField("smallPerimeterSpeedPercent", BINARY_BOOL),
    BinaryField("smallPerimeterThreshold", BINARY_FLOAT),
    BinaryField("slowdownForCurledPerimeters", BINARY_BOOL),
    BinaryField("resolution", BINARY_FLOAT),
    BinaryField("seamPosition", BINARY_STRING),
    BinaryField("staggeredInnerSeams", BINARY_BOOL),
    BinaryField("seamGap", BINARY_FLOAT),
    BinaryField("seamGapPercent", BINARY_BOOL),
    BinaryField("wipeBeforeExternalLoop", BINARY_BOOL),
    BinaryField("wipeOnLoops", BINARY_BOOL),
    BinaryField("roleBasedWipeSpeed", BINARY_BOOL),
    BinaryField("wipeSpeed", BINARY_FLOAT),
    BinaryField("wipeSpeedPercent", BINARY_BOOL),
    BinaryField("ironingType", BINARY_STRING),
    BinaryField("ironingPattern", BINARY_STRING),
    BinaryField("ironingFlow", BINARY_FLOAT),
    BinaryField("ironingSpacing", BINARY_FLOAT),
    BinaryField("ironingSpeed", BINARY_FLOAT),
    BinaryField("wallGenerator", BINARY_STRING),
    BinaryField("wallTransitionLength", BINARY_FLOAT),
    BinaryField("wallTransitionFilterDeviation", BINARY_FLOAT),
    BinaryField("wallTransitionAngle", BINARY_FLOAT),
    BinaryField("wallDistributionCount", BINARY_INT),
    BinaryField("minimumFeatureSize", BINARY_FLOAT),
    BinaryField("minimumWallLengthFactor", BINARY_FLOAT),
    BinaryField("wallSequence", BINARY_STRING),
    BinaryField("wallDirection", BINARY_STRING),
    BinaryField("detectThinWalls", BINARY_BOOL),
    BinaryField("detectOverhangWalls", BINARY_BOOL),
    BinaryField("onlyOneWallOnTop", BINARY_BOOL),
    BinaryField("minWidthTopSurface", BINARY_FLOAT),
    BinaryField("minWidthTopSurfacePercent", BINARY_BOOL),
    BinaryField("onlyOneWallFirstLayer", BINARY_BOOL),
    BinaryField("extraPerimetersOnOverhangs", BINARY_BOOL),
    BinaryField("overhangReverse", BINARY_BOOL),
    BinaryField("overhangReverseInternalOnly", BINARY_BOOL),
    BinaryField("overhangReverseThreshold", BINARY_FLOAT),
    BinaryField("overhangReverseThresholdPercent", BINARY_BOOL),
    BinaryField("counterboreHoleBridging", BINARY_STRING),
    BinaryField("alternateExtraWall", BINARY_BOOL),
    BinaryField("ensureVerticalShellThickness", BINARY_STRING),
    BinaryField("detectNarrowInternalSolidInfill", BINARY_BOOL),
    BinaryField("xyHoleCompensation", BINARY_FLOAT),
    BinaryField("xyContourCompensation", BINARY_FLOAT),
    BinaryField("elephantFootCompensation", BINARY_FLOAT),
    BinaryField("elephantFootCompensationLayers", BINARY_INT),
    BinaryField("maxBridgeLength", BINARY_FLOAT),
    BinaryField("preciseOuterWalls", BINARY_BOOL),
    BinaryField("supportFilament", BINARY_INT),
    BinaryField("supportInterfaceFilament", BINARY_INT),
    BinaryField("wipeTowerEnabled", BINARY_BOOL),
    BinaryField("wipeTowerWidth", BINARY_FLOAT),
    BinaryField("spiralMode", BINARY_BOOL),
    BinaryField("spiralModeSmooth", BINARY_BOOL),
    BinaryField("spiralModeMaxXySmoothing", BINARY_FLOAT),
    BinaryField("spiralModeMaxXySmoothingPercent", BINARY_BOOL),
    BinaryField("spiralStartingFlowRatio", BINARY_FLOAT),
    BinaryField("spiralFinishingFlowRatio", BINARY_FLOAT),
    BinaryField("compatiblePrinters", BINARY_STRING_LIST),
)

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
    private var printFlowRatio: Float = base.printFlowRatio
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
    private var defaultJerk: Float = base.defaultJerk
    private var outerWallJerk: Float = base.outerWallJerk
    private var innerWallJerk: Float = base.innerWallJerk
    private var topSurfaceJerk: Float = base.topSurfaceJerk
    private var infillJerk: Float = base.infillJerk
    private var firstLayerJerk: Float = base.firstLayerJerk
    private var travelJerk: Float = base.travelJerk
    private var fuzzySkinType: String = base.fuzzySkin.type
    private var fuzzySkinFirstLayer: Boolean = base.fuzzySkin.firstLayer
    private var fuzzySkinPointDistance: Float = base.fuzzySkin.pointDistance
    private var fuzzySkinThickness: Float = base.fuzzySkin.thickness
    private var fuzzySkinMode: String = base.fuzzySkin.mode
    private var fuzzySkinNoiseType: String = base.fuzzySkin.noiseType
    private var fuzzySkinScale: Float = base.fuzzySkin.scale
    private var fuzzySkinOctaves: Int = base.fuzzySkin.octaves
    private var fuzzySkinPersistence: Float = base.fuzzySkin.persistence
    private var supportEnabled: Boolean = base.supportEnabled
    private var enforceSupportLayers: Int = base.supportCoverage.enforcedLayers
    private var brimType: String = base.brimType
    private var brimWidth: Float = base.brimWidth
    private var brimObjectGap: Float = base.brimObjectGap
    private var brimEarsMaxAngle: Float = base.precision.brimEars.maximumAngle
    private var brimEarsDetectionLength: Float = base.precision.brimEars.detectionRadius
    private var raftLayers: Int = base.raftLayers
    private var raftContactDistance: Float = base.raftContactDistance
    private var raftExpansion: Float = base.raftExpansion
    private var raftFirstLayerDensity: Float = base.raftFirstLayerDensity
    private var raftFirstLayerExpansion: Float = base.raftFirstLayerExpansion
    private var topSolidLayers: Int = base.topSolidLayers
    private var bottomSolidLayers: Int = base.bottomSolidLayers
    private var topShellThickness: Float = base.topShellThickness
    private var bottomShellThickness: Float = base.bottomShellThickness
    private var topSurfaceDensity: Float = base.surfaceDensity.topPercent
    private var bottomSurfaceDensity: Float = base.surfaceDensity.bottomPercent
    private var fillPattern: String = base.fillPattern
    private var fillMultiline: Int = base.fillMultiline
    private var lateralLatticeAngle1: Float = base.lateralInfill.firstAngle
    private var lateralLatticeAngle2: Float = base.lateralInfill.secondAngle
    private var infillOverhangAngle: Float = base.lateralInfill.overhangAngle
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
    private var sparseInfillRotationTemplate: String = base.sparseInfillRotationTemplate
    private var solidInfillRotationTemplate: String = base.solidInfillRotationTemplate
    private var smallAreaFlowCompensation: Boolean = base.smallAreaFlowCompensation
    private var smallAreaFlowCompensationModel: String = base.smallAreaFlowCompensationModel
    private var alignInfillDirectionToModel: Boolean = base.alignInfillDirectionToModel
    private var minimumSparseInfillArea: Float = base.minimumSparseInfillArea
    private var infillAnchor: Float = base.infillAnchor
    private var infillAnchorPercent: Boolean = base.infillAnchorPercent
    private var infillAnchorMax: Float = base.infillAnchorMax
    private var infillAnchorMaxPercent: Boolean = base.infillAnchorMaxPercent
    private var skeletonInfillDensity: Float = base.skeletonInfillDensity
    private var skinInfillDensity: Float = base.skinInfillDensity
    private var skinInfillDepth: Float = base.skinInfillDepth
    private var infillLockDepth: Float = base.infillLockDepth
    private var infillShiftStep: Float = base.infillShiftStep
    private var symmetricInfillYAxis: Boolean = base.symmetricInfillYAxis
    private var skinInfillLineWidth: Float = base.skinInfillLineWidth
    private var skinInfillLineWidthPercent: Boolean = base.skinInfillLineWidthPercent
    private var skeletonInfillLineWidth: Float = base.skeletonInfillLineWidth
    private var skeletonInfillLineWidthPercent: Boolean = base.skeletonInfillLineWidthPercent
    private var gapFillTarget: String = base.gapFillTarget
    private var filterOutGapFill: Float = base.filterOutGapFill
    private var reduceCrossingWall: Boolean = base.reduceCrossingWall
    private var maxTravelDetourDistance: Float = base.maxTravelDetourDistance
    private var maxTravelDetourDistancePercent: Boolean = base.maxTravelDetourDistancePercent
    private var reduceInfillRetraction: Boolean = base.reduceInfillRetraction
    private var travelSpeed: Float = base.travelSpeed
    private var travelSpeedZ: Float = base.travelSpeedZ
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
    private var supportPatternAngle: Float = base.supportAdvanced.patternAngle
    private var supportThresholdOverlap: Float = base.supportAdvanced.thresholdOverlap
    private var supportThresholdOverlapPercent: Boolean = base.supportAdvanced.thresholdOverlapPercent
    private var supportObjectFirstLayerGap: Float = base.supportAdvanced.objectFirstLayerGap
    private var avoidSupportInterfaceFilamentForBase: Boolean = base.supportAdvanced.avoidInterfaceFilamentForBase
    private var supportIroning: Boolean = base.supportAdvanced.ironingEnabled
    private var supportIroningPattern: String = base.supportAdvanced.ironingPattern
    private var supportIroningFlow: Float = base.supportAdvanced.ironingFlow
    private var supportIroningSpacing: Float = base.supportAdvanced.ironingSpacing
    private var supportFilament: Int = base.supportFilament
    private var supportInterfaceFilament: Int = base.supportInterfaceFilament
    private var infillFilamentOverrideEnabled: Boolean = base.featureFilaments.infillOverrideEnabled
    private var infillFilamentBaseFirstLayers: Int = base.featureFilaments.baseFirstLayers
    private var infillFilamentBaseLastLayers: Int = base.featureFilaments.baseLastLayers
    private var sparseInfillFilament: Int = base.featureFilaments.sparseInfillFilament
    private var wallFilament: Int = base.featureFilaments.wallFilament
    private var solidInfillFilament: Int = base.featureFilaments.solidInfillFilament
    private var wipeTowerFilament: Int = base.featureFilaments.wipeTowerFilament
    private var wipeTowerEnabled: Boolean = base.wipeTowerEnabled
    private var wipeTowerWidth: Float = base.wipeTowerWidth
    private var primeVolume: Float = base.multiMaterial.primeVolume
    private var primeTowerBrimWidth: Float = base.multiMaterial.primeTowerBrimWidth
    private var primeTowerFramework: Boolean = base.multiMaterial.primeTowerFramework
    private var primeTowerSkipPoints: Boolean = base.multiMaterial.primeTowerSkipPoints
    private var primeTowerFlatIroning: Boolean = base.multiMaterial.primeTowerFlatIroning
    private var primeTowerInterfaceFeatures: Boolean = base.multiMaterial.primeTowerInterfaceFeatures
    private var primeTowerInterfaceCooldown: Boolean = base.multiMaterial.primeTowerInterfaceCooldown
    private var primeTowerInfillGap: Float = base.multiMaterial.primeTowerInfillGap
    private var wipeTowerNoSparseLayers: Boolean = base.multiMaterial.wipeTowerNoSparseLayers
    private var wipeTowerRotationAngle: Float = base.multiMaterial.wipeTowerRotationAngle
    private var wipeTowerBridging: Float = base.multiMaterial.wipeTowerBridging
    private var wipeTowerExtraSpacing: Float = base.multiMaterial.wipeTowerExtraSpacing
    private var wipeTowerExtraFlow: Float = base.multiMaterial.wipeTowerExtraFlow
    private var wipeTowerMaxPurgeSpeed: Float = base.multiMaterial.wipeTowerMaxPurgeSpeed
    private var wipeTowerWallType: String = base.multiMaterial.wipeTowerWallType
    private var wipeTowerConeAngle: Float = base.multiMaterial.wipeTowerConeAngle
    private var wipeTowerExtraRibLength: Float = base.multiMaterial.wipeTowerExtraRibLength
    private var wipeTowerRibWidth: Float = base.multiMaterial.wipeTowerRibWidth
    private var wipeTowerFilletWall: Boolean = base.multiMaterial.wipeTowerFilletWall
    private var singleExtruderMultiMaterialPriming: Boolean =
        base.multiMaterial.singleExtruderMultiMaterialPriming
    private var flushIntoInfill: Boolean = base.multiMaterial.flushIntoInfill
    private var flushIntoSupport: Boolean = base.multiMaterial.flushIntoSupport
    private var flushIntoObjects: Boolean = base.multiMaterial.flushIntoObjects
    private var oozePrevention: Boolean = base.multiMaterial.oozePrevention
    private var standbyTemperatureDelta: Int = base.multiMaterial.standbyTemperatureDelta
    private var preheatTime: Float = base.multiMaterial.preheatTime
    private var preheatDeltaTemperature: Int = base.multiMaterial.preheatDeltaTemperature
    private var preheatSteps: Int = base.multiMaterial.preheatSteps
    private var interfaceShells: Boolean = base.multiMaterial.interfaceShells
    private var segmentedRegionMaxWidth: Float = base.multiMaterial.segmentedRegionMaxWidth
    private var segmentedRegionInterlockingDepth: Float = base.multiMaterial.segmentedRegionInterlockingDepth
    private var interlockingBeam: Boolean = base.multiMaterial.interlockingBeam
    private var interlockingBeamWidth: Float = base.multiMaterial.interlockingBeamWidth
    private var interlockingOrientation: Float = base.multiMaterial.interlockingOrientation
    private var interlockingBeamLayerCount: Int = base.multiMaterial.interlockingBeamLayerCount
    private var interlockingDepth: Int = base.multiMaterial.interlockingDepth
    private var interlockingBoundaryAvoidance: Int = base.multiMaterial.interlockingBoundaryAvoidance
    private var maxVolumetricExtrusionRateSlope: Float = base.extrusionRateSmoothing.maximumSlope
    private var maxVolumetricExtrusionRateSlopeSegmentLength: Float =
        base.extrusionRateSmoothing.segmentLength
    private var extrusionRateSmoothingExternalOnly: Boolean = base.extrusionRateSmoothing.externalOnly
    private var enableArcFitting: Boolean = base.gcodeSettings.arcFitting
    private var gcodeLabelObjects: Boolean = base.gcodeSettings.labelObjects
    private var excludeObject: Boolean = base.gcodeSettings.excludeObjects
    private var gcodeComments: Boolean = base.gcodeSettings.verboseComments
    private var timelapseType: String = base.gcodeSettings.timelapseType
    private var initialLayerTravelSpeed: Float = base.gcodeSettings.initialLayerTravelSpeed
    private var initialLayerTravelSpeedPercent: Boolean = base.gcodeSettings.initialLayerTravelSpeedPercent
    private var slowDownLayers: Int = base.gcodeSettings.slowDownLayers
    private var accelToDecelEnabled: Boolean = base.gcodeSettings.accelToDecelEnabled
    private var accelToDecelFactor: Float = base.gcodeSettings.accelToDecelFactor
    private var filenameFormat: String = base.gcodeSettings.filenameFormat
    private var skirtType: String = base.skirtType
    private var skirtLoops: Int = base.skirtLoops
    private var skirtDistance: Float = base.skirtDistance
    private var skirtStartAngle: Float = base.skirtStartAngle
    private var skirtHeight: Int = base.skirtHeight
    private var skirtSpeed: Float = base.skirtSpeed
    private var minimumSkirtLength: Float = base.minimumSkirtLength
    private var draftShield: String = base.draftShield
    private var singleLoopDraftShield: Boolean = base.singleLoopDraftShield
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
    private var slicingMode: String = base.precision.mode
    private var sliceClosingRadius: Float = base.precision.closingRadius
    private var preciseZHeight: Boolean = base.precision.preciseZHeight
    private var holeToPolyhole: Boolean = base.precision.polyholes.enabled
    private var holeToPolyholeThreshold: Float = base.precision.polyholes.detectionMargin
    private var holeToPolyholeThresholdPercent: Boolean = base.precision.polyholes.detectionMarginPercent
    private var holeToPolyholeTwisted: Boolean = base.precision.polyholes.twist
    private var seamPosition: String = base.seamPosition
    private var staggeredInnerSeams: Boolean = base.staggeredInnerSeams
    private var seamGap: Float = base.seamGap
    private var seamGapPercent: Boolean = base.seamGapPercent
    private var scarfSeamType: String = base.scarfSeam.type
    private var scarfSeamConditional: Boolean = base.scarfSeam.conditional
    private var scarfAngleThreshold: Int = base.scarfSeam.angleThreshold
    private var scarfOverhangThreshold: Float = base.scarfSeam.overhangThreshold
    private var scarfJointSpeed: Float = base.scarfSeam.speed
    private var scarfJointSpeedPercent: Boolean = base.scarfSeam.speedPercent
    private var scarfJointFlowRatio: Float = base.scarfSeam.flowRatio
    private var scarfStartHeight: Float = base.scarfSeam.startHeight
    private var scarfStartHeightPercent: Boolean = base.scarfSeam.startHeightPercent
    private var scarfEntireLoop: Boolean = base.scarfSeam.entireLoop
    private var scarfLength: Float = base.scarfSeam.length
    private var scarfSteps: Int = base.scarfSeam.steps
    private var scarfInnerWalls: Boolean = base.scarfSeam.innerWalls
    private var wipeBeforeExternalLoop: Boolean = base.wipeBeforeExternalLoop
    private var wipeOnLoops: Boolean = base.wipeOnLoops
    private var roleBasedWipeSpeed: Boolean = base.roleBasedWipeSpeed
    private var wipeSpeed: Float = base.wipeSpeed
    private var wipeSpeedPercent: Boolean = base.wipeSpeedPercent
    private var ironingType: String = base.ironing.type
    private var ironingPattern: String = base.ironing.pattern
    private var ironingFlow: Float = base.ironing.flow
    private var ironingSpacing: Float = base.ironing.spacing
    private var ironingInset: Float = base.ironing.inset
    private var ironingSpeed: Float = base.ironing.speed
    private var ironingAngle: Float = base.ironing.angle
    private var wallGenerator: String = base.wallGenerator
    private var wallTransitionLength: Float = base.wallTransitionLength
    private var wallTransitionFilterDeviation: Float = base.wallTransitionFilterDeviation
    private var wallTransitionAngle: Float = base.wallTransitionAngle
    private var wallDistributionCount: Int = base.wallDistributionCount
    private var minimumFeatureSize: Float = base.minimumFeatureSize
    private var minimumWallWidth: Float = base.precision.minimumWallWidth
    private var firstLayerMinimumWallWidth: Float = base.precision.firstLayerMinimumWallWidth
    private var minimumWallLengthFactor: Float = base.minimumWallLengthFactor
    private var wallSequence: String = base.wallSequence
    private var wallDirection: String = base.wallDirection
    private var detectThinWalls: Boolean = base.detectThinWalls
    private var detectOverhangWalls: Boolean = base.detectOverhangWalls
    private var makeOverhangPrintable: Boolean = base.printableOverhangs.enabled
    private var makeOverhangPrintableAngle: Float = base.printableOverhangs.maximumAngle
    private var makeOverhangPrintableHoleSize: Float = base.printableOverhangs.holeArea
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
    private var printSequence: String = base.printSequence
    private var printOrder: String = base.printOrder
    private var spiralMode: Boolean = base.spiralMode
    private var spiralModeSmooth: Boolean = base.spiralModeSmooth
    private var spiralModeMaxXySmoothing: Float = base.spiralModeMaxXySmoothing
    private var spiralModeMaxXySmoothingPercent: Boolean = base.spiralModeMaxXySmoothingPercent
    private var spiralStartingFlowRatio: Float = base.spiralStartingFlowRatio
    private var spiralFinishingFlowRatio: Float = base.spiralFinishingFlowRatio
    private var supportOnBuildPlateOnly: Boolean = base.supportCoverage.onBuildPlateOnly
    private var supportCriticalRegionsOnly: Boolean = base.supportCoverage.criticalRegionsOnly
    private var supportRemoveSmallOverhangs: Boolean = base.supportCoverage.removeSmallOverhangs
    private var supportBasePatternSpacing: Float = base.supportBasePatternSpacing
    private var supportExpansion: Float = base.supportExpansion
    private var supportInterfaceLoopPattern: Boolean = base.supportInterfaceLoopPattern
    private var independentSupportLayerHeight: Boolean = base.independentSupportLayerHeight
    private var treeSupportBranchAngle: Float = base.treeSupportBranchAngle
    private var treeSupportBranchDistance: Float = base.treeSupportBranchDistance
    private var treeSupportBranchDiameter: Float = base.treeSupportBranchDiameter
    private var treeSupportWallCount: Int = base.treeSupportWallCount
    private var treeSupportTipDiameter: Float = base.treeSupportTipDiameter
    private var treeSupportPreferredBranchAngle: Float = base.treeSupportPreferredBranchAngle
    private var treeSupportBranchDensity: Float = base.treeSupportBranchDensity
    private var treeSupportOrganicBranchAngle: Float = base.treeSupportOrganicBranchAngle
    private var treeSupportOrganicBranchDistance: Float = base.treeSupportOrganicBranchDistance
    private var treeSupportOrganicBranchDiameter: Float = base.treeSupportOrganicBranchDiameter
    private var treeSupportBranchDiameterAngle: Float = base.treeSupportBranchDiameterAngle
    private var treeSupportAdaptiveLayerHeight: Boolean = base.treeSupportAdaptiveLayerHeight
    private var treeSupportAutoBrim: Boolean = base.treeSupportAutoBrim
    private var treeSupportBrimWidth: Float = base.treeSupportBrimWidth
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
        readGroup9(input)
        readGroup10(input)
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
        printFlowRatio = input.readFloat()
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
        enforceSupportLayers = input.readInt()
        brimType = input.readCatalogString()
        brimWidth = input.readFloat()
        brimObjectGap = input.readFloat()
        brimEarsMaxAngle = input.readFloat()
        brimEarsDetectionLength = input.readFloat()
        topSolidLayers = input.readInt()
        bottomSolidLayers = input.readInt()
    }

    private fun readGroup3(input: DataInputStream) {
        topShellThickness = input.readFloat()
        bottomShellThickness = input.readFloat()
        topSurfaceDensity = input.readFloat()
        bottomSurfaceDensity = input.readFloat()
        fillPattern = input.readCatalogString()
        fillMultiline = input.readInt()
        lateralLatticeAngle1 = input.readFloat()
        lateralLatticeAngle2 = input.readFloat()
        infillOverhangAngle = input.readFloat()
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
        sparseInfillRotationTemplate = input.readCatalogString()
        solidInfillRotationTemplate = input.readCatalogString()
        smallAreaFlowCompensation = input.readCatalogBoolean()
        smallAreaFlowCompensationModel = input.readCatalogString()
        alignInfillDirectionToModel = input.readCatalogBoolean()
        minimumSparseInfillArea = input.readFloat()
        infillAnchor = input.readFloat()
        infillAnchorPercent = input.readCatalogBoolean()
        infillAnchorMax = input.readFloat()
        infillAnchorMaxPercent = input.readCatalogBoolean()
        skeletonInfillDensity = input.readFloat()
        skinInfillDensity = input.readFloat()
        skinInfillDepth = input.readFloat()
        infillLockDepth = input.readFloat()
        infillShiftStep = input.readFloat()
        symmetricInfillYAxis = input.readCatalogBoolean()
        skinInfillLineWidth = input.readFloat()
        skinInfillLineWidthPercent = input.readCatalogBoolean()
        skeletonInfillLineWidth = input.readFloat()
        skeletonInfillLineWidthPercent = input.readCatalogBoolean()
    }

    private fun readGroup4(input: DataInputStream) {
        gapFillTarget = input.readCatalogString()
        filterOutGapFill = input.readFloat()
        reduceCrossingWall = input.readCatalogBoolean()
        maxTravelDetourDistance = input.readFloat()
        maxTravelDetourDistancePercent = input.readCatalogBoolean()
        reduceInfillRetraction = input.readCatalogBoolean()
        travelSpeed = input.readFloat()
        travelSpeedZ = input.readFloat()
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
        supportPatternAngle = input.readFloat()
        supportThresholdOverlap = input.readFloat()
        supportThresholdOverlapPercent = input.readCatalogBoolean()
        supportObjectFirstLayerGap = input.readFloat()
        avoidSupportInterfaceFilamentForBase = input.readCatalogBoolean()
        supportIroning = input.readCatalogBoolean()
        supportIroningPattern = input.readCatalogString()
        supportIroningFlow = input.readFloat()
        supportIroningSpacing = input.readFloat()
    }

    private fun readGroup5(input: DataInputStream) {
        skirtType = input.readCatalogString()
        skirtLoops = input.readInt()
        skirtDistance = input.readFloat()
        skirtStartAngle = input.readFloat()
        skirtHeight = input.readInt()
        skirtSpeed = input.readFloat()
        minimumSkirtLength = input.readFloat()
        draftShield = input.readCatalogString()
        singleLoopDraftShield = input.readCatalogBoolean()
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
        slicingMode = input.readCatalogString()
        sliceClosingRadius = input.readFloat()
        preciseZHeight = input.readCatalogBoolean()
        holeToPolyhole = input.readCatalogBoolean()
        holeToPolyholeThreshold = input.readFloat()
        holeToPolyholeThresholdPercent = input.readCatalogBoolean()
        holeToPolyholeTwisted = input.readCatalogBoolean()
        seamPosition = input.readCatalogString()
        staggeredInnerSeams = input.readCatalogBoolean()
        seamGap = input.readFloat()
        seamGapPercent = input.readCatalogBoolean()
        scarfSeamType = input.readCatalogString()
        scarfSeamConditional = input.readCatalogBoolean()
        scarfAngleThreshold = input.readInt()
        scarfOverhangThreshold = input.readFloat()
        scarfJointSpeed = input.readFloat()
        scarfJointSpeedPercent = input.readCatalogBoolean()
        scarfJointFlowRatio = input.readFloat()
        scarfStartHeight = input.readFloat()
        scarfStartHeightPercent = input.readCatalogBoolean()
        scarfEntireLoop = input.readCatalogBoolean()
        scarfLength = input.readFloat()
        scarfSteps = input.readInt()
        scarfInnerWalls = input.readCatalogBoolean()
        wipeBeforeExternalLoop = input.readCatalogBoolean()
        wipeOnLoops = input.readCatalogBoolean()
        roleBasedWipeSpeed = input.readCatalogBoolean()
        wipeSpeed = input.readFloat()
        wipeSpeedPercent = input.readCatalogBoolean()
        ironingType = input.readCatalogString()
        ironingPattern = input.readCatalogString()
        ironingFlow = input.readFloat()
        ironingSpacing = input.readFloat()
        ironingInset = input.readFloat()
        ironingSpeed = input.readFloat()
        ironingAngle = input.readFloat()
        wallGenerator = input.readCatalogString()
        wallTransitionLength = input.readFloat()
        wallTransitionFilterDeviation = input.readFloat()
    }

    private fun readGroup7(input: DataInputStream) {
        wallTransitionAngle = input.readFloat()
        wallDistributionCount = input.readInt()
        minimumFeatureSize = input.readFloat()
        minimumWallWidth = input.readFloat()
        firstLayerMinimumWallWidth = input.readFloat()
        minimumWallLengthFactor = input.readFloat()
        wallSequence = input.readCatalogString()
        wallDirection = input.readCatalogString()
        detectThinWalls = input.readCatalogBoolean()
        detectOverhangWalls = input.readCatalogBoolean()
        makeOverhangPrintable = input.readCatalogBoolean()
        makeOverhangPrintableAngle = input.readFloat()
        makeOverhangPrintableHoleSize = input.readFloat()
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
        printSequence = input.readCatalogString()
        printOrder = input.readCatalogString()
        supportFilament = input.readInt()
        supportInterfaceFilament = input.readInt()
        infillFilamentOverrideEnabled = input.readCatalogBoolean()
        infillFilamentBaseFirstLayers = input.readInt()
        infillFilamentBaseLastLayers = input.readInt()
        sparseInfillFilament = input.readInt()
        wallFilament = input.readInt()
        solidInfillFilament = input.readInt()
        wipeTowerFilament = input.readInt()
        wipeTowerEnabled = input.readCatalogBoolean()
        wipeTowerWidth = input.readFloat()
        primeVolume = input.readFloat()
        primeTowerBrimWidth = input.readFloat()
        primeTowerFramework = input.readCatalogBoolean()
        primeTowerSkipPoints = input.readCatalogBoolean()
        primeTowerFlatIroning = input.readCatalogBoolean()
        primeTowerInterfaceFeatures = input.readCatalogBoolean()
        primeTowerInterfaceCooldown = input.readCatalogBoolean()
        primeTowerInfillGap = input.readFloat()
        wipeTowerNoSparseLayers = input.readCatalogBoolean()
        wipeTowerRotationAngle = input.readFloat()
        wipeTowerBridging = input.readFloat()
        wipeTowerExtraSpacing = input.readFloat()
        wipeTowerExtraFlow = input.readFloat()
        wipeTowerMaxPurgeSpeed = input.readFloat()
        wipeTowerWallType = input.readCatalogString()
        wipeTowerConeAngle = input.readFloat()
        wipeTowerExtraRibLength = input.readFloat()
        wipeTowerRibWidth = input.readFloat()
        wipeTowerFilletWall = input.readCatalogBoolean()
        singleExtruderMultiMaterialPriming = input.readCatalogBoolean()
        flushIntoInfill = input.readCatalogBoolean()
        flushIntoSupport = input.readCatalogBoolean()
        flushIntoObjects = input.readCatalogBoolean()
        oozePrevention = input.readCatalogBoolean()
        standbyTemperatureDelta = input.readInt()
        preheatTime = input.readFloat()
        preheatDeltaTemperature = input.readInt()
        preheatSteps = input.readInt()
        interfaceShells = input.readCatalogBoolean()
        segmentedRegionMaxWidth = input.readFloat()
        segmentedRegionInterlockingDepth = input.readFloat()
        interlockingBeam = input.readCatalogBoolean()
        interlockingBeamWidth = input.readFloat()
        interlockingOrientation = input.readFloat()
        interlockingBeamLayerCount = input.readInt()
        interlockingDepth = input.readInt()
        interlockingBoundaryAvoidance = input.readInt()
        maxVolumetricExtrusionRateSlope = input.readFloat()
        maxVolumetricExtrusionRateSlopeSegmentLength = input.readFloat()
        extrusionRateSmoothingExternalOnly = input.readCatalogBoolean()
        enableArcFitting = input.readCatalogBoolean()
        gcodeLabelObjects = input.readCatalogBoolean()
        excludeObject = input.readCatalogBoolean()
        gcodeComments = input.readCatalogBoolean()
        timelapseType = input.readCatalogString()
        initialLayerTravelSpeed = input.readFloat()
        initialLayerTravelSpeedPercent = input.readCatalogBoolean()
        slowDownLayers = input.readInt()
        accelToDecelEnabled = input.readCatalogBoolean()
        accelToDecelFactor = input.readFloat()
        filenameFormat = input.readCatalogString()
        spiralMode = input.readCatalogBoolean()
        spiralModeSmooth = input.readCatalogBoolean()
        spiralModeMaxXySmoothing = input.readFloat()
        spiralModeMaxXySmoothingPercent = input.readCatalogBoolean()
        spiralStartingFlowRatio = input.readFloat()
        spiralFinishingFlowRatio = input.readFloat()
        supportOnBuildPlateOnly = input.readCatalogBoolean()
        supportCriticalRegionsOnly = input.readCatalogBoolean()
        supportRemoveSmallOverhangs = input.readCatalogBoolean()
        supportBasePatternSpacing = input.readFloat()
        supportExpansion = input.readFloat()
        supportInterfaceLoopPattern = input.readCatalogBoolean()
        independentSupportLayerHeight = input.readCatalogBoolean()
        treeSupportBranchAngle = input.readFloat()
        treeSupportBranchDistance = input.readFloat()
        treeSupportBranchDiameter = input.readFloat()
        treeSupportWallCount = input.readInt()
        treeSupportTipDiameter = input.readFloat()
        treeSupportPreferredBranchAngle = input.readFloat()
        treeSupportBranchDensity = input.readFloat()
        treeSupportOrganicBranchAngle = input.readFloat()
        treeSupportOrganicBranchDistance = input.readFloat()
        treeSupportOrganicBranchDiameter = input.readFloat()
        treeSupportBranchDiameterAngle = input.readFloat()
        treeSupportAdaptiveLayerHeight = input.readCatalogBoolean()
        treeSupportAutoBrim = input.readCatalogBoolean()
        treeSupportBrimWidth = input.readFloat()
        compatiblePrinters = input.readCatalogStringList()
    }

    private fun readGroup9(input: DataInputStream) {
        defaultJerk = input.readFloat()
        outerWallJerk = input.readFloat()
        innerWallJerk = input.readFloat()
        topSurfaceJerk = input.readFloat()
        infillJerk = input.readFloat()
        firstLayerJerk = input.readFloat()
        travelJerk = input.readFloat()
    }

    private fun readGroup10(input: DataInputStream) {
        fuzzySkinType = input.readCatalogString()
        fuzzySkinFirstLayer = input.readCatalogBoolean()
        fuzzySkinPointDistance = input.readFloat()
        fuzzySkinThickness = input.readFloat()
        fuzzySkinMode = input.readCatalogString()
        fuzzySkinNoiseType = input.readCatalogString()
        fuzzySkinScale = input.readFloat()
        fuzzySkinOctaves = input.readInt()
        fuzzySkinPersistence = input.readFloat()
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
        fuzzySkin = FuzzySkinSettings(
            type = fuzzySkinType,
            firstLayer = fuzzySkinFirstLayer,
            pointDistance = fuzzySkinPointDistance,
            thickness = fuzzySkinThickness,
            mode = fuzzySkinMode,
            noiseType = fuzzySkinNoiseType,
            scale = fuzzySkinScale,
            octaves = fuzzySkinOctaves,
            persistence = fuzzySkinPersistence,
        ),
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
        surfaceDensity = SurfaceDensitySettings(
            topPercent = topSurfaceDensity,
            bottomPercent = bottomSurfaceDensity,
        ),
        fillPattern = fillPattern,
        fillMultiline = fillMultiline,
        lateralInfill = LateralInfillSettings(
            firstAngle = lateralLatticeAngle1,
            secondAngle = lateralLatticeAngle2,
            overhangAngle = infillOverhangAngle,
        ),
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
        sparseInfillRotationTemplate = sparseInfillRotationTemplate,
        solidInfillRotationTemplate = solidInfillRotationTemplate,
        smallAreaFlowCompensation = smallAreaFlowCompensation,
        smallAreaFlowCompensationModel = smallAreaFlowCompensationModel,
        alignInfillDirectionToModel = alignInfillDirectionToModel,
        minimumSparseInfillArea = minimumSparseInfillArea,
        infillAnchor = infillAnchor,
        infillAnchorPercent = infillAnchorPercent,
        infillAnchorMax = infillAnchorMax,
        infillAnchorMaxPercent = infillAnchorMaxPercent,
        skeletonInfillDensity = skeletonInfillDensity,
        skinInfillDensity = skinInfillDensity,
        skinInfillDepth = skinInfillDepth,
        infillLockDepth = infillLockDepth,
        infillShiftStep = infillShiftStep,
        symmetricInfillYAxis = symmetricInfillYAxis,
        skinInfillLineWidth = skinInfillLineWidth,
        skinInfillLineWidthPercent = skinInfillLineWidthPercent,
        skeletonInfillLineWidth = skeletonInfillLineWidth,
        skeletonInfillLineWidthPercent = skeletonInfillLineWidthPercent,
        gapFillTarget = gapFillTarget,
        filterOutGapFill = filterOutGapFill,
        reduceCrossingWall = reduceCrossingWall,
        maxTravelDetourDistance = maxTravelDetourDistance,
        maxTravelDetourDistancePercent = maxTravelDetourDistancePercent,
        reduceInfillRetraction = reduceInfillRetraction,
        travelSpeed = travelSpeed,
        travelSpeedZ = travelSpeedZ,
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
        supportAdvanced = SupportAdvancedSettings(
            patternAngle = supportPatternAngle,
            thresholdOverlap = supportThresholdOverlap,
            thresholdOverlapPercent = supportThresholdOverlapPercent,
            objectFirstLayerGap = supportObjectFirstLayerGap,
            avoidInterfaceFilamentForBase = avoidSupportInterfaceFilamentForBase,
            ironingEnabled = supportIroning,
            ironingPattern = supportIroningPattern,
            ironingFlow = supportIroningFlow,
            ironingSpacing = supportIroningSpacing,
        ),
        supportFilament = supportFilament,
        supportInterfaceFilament = supportInterfaceFilament,
        featureFilaments = FeatureFilamentSettings(
            infillOverrideEnabled = infillFilamentOverrideEnabled,
            baseFirstLayers = infillFilamentBaseFirstLayers,
            baseLastLayers = infillFilamentBaseLastLayers,
            sparseInfillFilament = sparseInfillFilament,
            wallFilament = wallFilament,
            solidInfillFilament = solidInfillFilament,
            wipeTowerFilament = wipeTowerFilament,
        ),
        wipeTowerEnabled = wipeTowerEnabled,
        wipeTowerWidth = wipeTowerWidth,
        multiMaterial = MultiMaterialSettings(
            primeVolume = primeVolume,
            primeTowerBrimWidth = primeTowerBrimWidth,
            primeTowerFramework = primeTowerFramework,
            primeTowerSkipPoints = primeTowerSkipPoints,
            primeTowerFlatIroning = primeTowerFlatIroning,
            primeTowerInterfaceFeatures = primeTowerInterfaceFeatures,
            primeTowerInterfaceCooldown = primeTowerInterfaceCooldown,
            primeTowerInfillGap = primeTowerInfillGap,
            wipeTowerNoSparseLayers = wipeTowerNoSparseLayers,
            wipeTowerRotationAngle = wipeTowerRotationAngle,
            wipeTowerBridging = wipeTowerBridging,
            wipeTowerExtraSpacing = wipeTowerExtraSpacing,
            wipeTowerExtraFlow = wipeTowerExtraFlow,
            wipeTowerMaxPurgeSpeed = wipeTowerMaxPurgeSpeed,
            wipeTowerWallType = wipeTowerWallType,
            wipeTowerConeAngle = wipeTowerConeAngle,
            wipeTowerExtraRibLength = wipeTowerExtraRibLength,
            wipeTowerRibWidth = wipeTowerRibWidth,
            wipeTowerFilletWall = wipeTowerFilletWall,
            singleExtruderMultiMaterialPriming = singleExtruderMultiMaterialPriming,
            flushIntoInfill = flushIntoInfill,
            flushIntoSupport = flushIntoSupport,
            flushIntoObjects = flushIntoObjects,
            oozePrevention = oozePrevention,
            standbyTemperatureDelta = standbyTemperatureDelta,
            preheatTime = preheatTime,
            preheatDeltaTemperature = preheatDeltaTemperature,
            preheatSteps = preheatSteps,
            interfaceShells = interfaceShells,
            segmentedRegionMaxWidth = segmentedRegionMaxWidth,
            segmentedRegionInterlockingDepth = segmentedRegionInterlockingDepth,
            interlockingBeam = interlockingBeam,
            interlockingBeamWidth = interlockingBeamWidth,
            interlockingOrientation = interlockingOrientation,
            interlockingBeamLayerCount = interlockingBeamLayerCount,
            interlockingDepth = interlockingDepth,
            interlockingBoundaryAvoidance = interlockingBoundaryAvoidance,
        ),
        extrusionRateSmoothing = ExtrusionRateSmoothingSettings(
            maximumSlope = maxVolumetricExtrusionRateSlope,
            segmentLength = maxVolumetricExtrusionRateSlopeSegmentLength,
            externalOnly = extrusionRateSmoothingExternalOnly,
        ),
        gcodeSettings = GcodeSettings(
            arcFitting = enableArcFitting,
            labelObjects = gcodeLabelObjects,
            excludeObjects = excludeObject,
            verboseComments = gcodeComments,
            timelapseType = timelapseType,
            initialLayerTravelSpeed = initialLayerTravelSpeed,
            initialLayerTravelSpeedPercent = initialLayerTravelSpeedPercent,
            slowDownLayers = slowDownLayers,
            accelToDecelEnabled = accelToDecelEnabled,
            accelToDecelFactor = accelToDecelFactor,
            filenameFormat = filenameFormat,
        ),
        skirtType = skirtType,
        skirtLoops = skirtLoops,
        skirtDistance = skirtDistance,
        skirtStartAngle = skirtStartAngle,
        skirtHeight = skirtHeight,
        skirtSpeed = skirtSpeed,
        minimumSkirtLength = minimumSkirtLength,
        draftShield = draftShield,
        singleLoopDraftShield = singleLoopDraftShield,
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
        precision = PrecisionSettings(
            mode = slicingMode,
            closingRadius = sliceClosingRadius,
            preciseZHeight = preciseZHeight,
            polyholes = PolyholeSettings(
                enabled = holeToPolyhole,
                detectionMargin = holeToPolyholeThreshold,
                detectionMarginPercent = holeToPolyholeThresholdPercent,
                twist = holeToPolyholeTwisted,
            ),
            minimumWallWidth = minimumWallWidth,
            firstLayerMinimumWallWidth = firstLayerMinimumWallWidth,
            printableOverhangs = PrintableOverhangSettings(
                enabled = makeOverhangPrintable,
                maximumAngle = makeOverhangPrintableAngle,
                holeArea = makeOverhangPrintableHoleSize,
            ),
            brimEars = BrimEarSettings(
                maximumAngle = brimEarsMaxAngle,
                detectionRadius = brimEarsDetectionLength,
            ),
        ),
        seamPosition = seamPosition,
        staggeredInnerSeams = staggeredInnerSeams,
        seamGap = seamGap,
        seamGapPercent = seamGapPercent,
        scarfSeam = ScarfSeamSettings(
            type = scarfSeamType,
            conditional = scarfSeamConditional,
            angleThreshold = scarfAngleThreshold,
            overhangThreshold = scarfOverhangThreshold,
            speed = scarfJointSpeed,
            speedPercent = scarfJointSpeedPercent,
            flowRatio = scarfJointFlowRatio,
            startHeight = scarfStartHeight,
            startHeightPercent = scarfStartHeightPercent,
            entireLoop = scarfEntireLoop,
            length = scarfLength,
            steps = scarfSteps,
            innerWalls = scarfInnerWalls,
        ),
        wipeBeforeExternalLoop = wipeBeforeExternalLoop,
        wipeOnLoops = wipeOnLoops,
        roleBasedWipeSpeed = roleBasedWipeSpeed,
        wipeSpeed = wipeSpeed,
        wipeSpeedPercent = wipeSpeedPercent,
        ironing = IroningSettings(
            type = ironingType,
            pattern = ironingPattern,
            flow = ironingFlow,
            spacing = ironingSpacing,
            inset = ironingInset,
            speed = ironingSpeed,
            angle = ironingAngle,
        ),
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
        supportCoverage = SupportCoverageSettings(
            onBuildPlateOnly = supportOnBuildPlateOnly,
            criticalRegionsOnly = supportCriticalRegionsOnly,
            removeSmallOverhangs = supportRemoveSmallOverhangs,
            enforcedLayers = enforceSupportLayers,
        ),
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
    BinaryField("printFlowRatio", BINARY_FLOAT),
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
    BinaryField("enforceSupportLayers", BINARY_INT),
    BinaryField("brimType", BINARY_STRING),
    BinaryField("brimWidth", BINARY_FLOAT),
    BinaryField("brimObjectGap", BINARY_FLOAT),
    BinaryField("brimEarsMaxAngle", BINARY_FLOAT),
    BinaryField("brimEarsDetectionLength", BINARY_FLOAT),
    BinaryField("topSolidLayers", BINARY_INT),
    BinaryField("bottomSolidLayers", BINARY_INT),
    BinaryField("topShellThickness", BINARY_FLOAT),
    BinaryField("bottomShellThickness", BINARY_FLOAT),
    BinaryField("topSurfaceDensity", BINARY_FLOAT),
    BinaryField("bottomSurfaceDensity", BINARY_FLOAT),
    BinaryField("fillPattern", BINARY_STRING),
    BinaryField("fillMultiline", BINARY_INT),
    BinaryField("lateralLatticeAngle1", BINARY_FLOAT),
    BinaryField("lateralLatticeAngle2", BINARY_FLOAT),
    BinaryField("infillOverhangAngle", BINARY_FLOAT),
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
    BinaryField("sparseInfillRotationTemplate", BINARY_STRING),
    BinaryField("solidInfillRotationTemplate", BINARY_STRING),
    BinaryField("smallAreaFlowCompensation", BINARY_BOOL),
    BinaryField("smallAreaFlowCompensationModel", BINARY_STRING),
    BinaryField("alignInfillDirectionToModel", BINARY_BOOL),
    BinaryField("minimumSparseInfillArea", BINARY_FLOAT),
    BinaryField("infillAnchor", BINARY_FLOAT),
    BinaryField("infillAnchorPercent", BINARY_BOOL),
    BinaryField("infillAnchorMax", BINARY_FLOAT),
    BinaryField("infillAnchorMaxPercent", BINARY_BOOL),
    BinaryField("skeletonInfillDensity", BINARY_FLOAT),
    BinaryField("skinInfillDensity", BINARY_FLOAT),
    BinaryField("skinInfillDepth", BINARY_FLOAT),
    BinaryField("infillLockDepth", BINARY_FLOAT),
    BinaryField("infillShiftStep", BINARY_FLOAT),
    BinaryField("symmetricInfillYAxis", BINARY_BOOL),
    BinaryField("skinInfillLineWidth", BINARY_FLOAT),
    BinaryField("skinInfillLineWidthPercent", BINARY_BOOL),
    BinaryField("skeletonInfillLineWidth", BINARY_FLOAT),
    BinaryField("skeletonInfillLineWidthPercent", BINARY_BOOL),
    BinaryField("gapFillTarget", BINARY_STRING),
    BinaryField("filterOutGapFill", BINARY_FLOAT),
    BinaryField("reduceCrossingWall", BINARY_BOOL),
    BinaryField("maxTravelDetourDistance", BINARY_FLOAT),
    BinaryField("maxTravelDetourDistancePercent", BINARY_BOOL),
    BinaryField("reduceInfillRetraction", BINARY_BOOL),
    BinaryField("travelSpeed", BINARY_FLOAT),
    BinaryField("travelSpeedZ", BINARY_FLOAT),
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
    BinaryField("supportPatternAngle", BINARY_FLOAT),
    BinaryField("supportThresholdOverlap", BINARY_FLOAT),
    BinaryField("supportThresholdOverlapPercent", BINARY_BOOL),
    BinaryField("supportObjectFirstLayerGap", BINARY_FLOAT),
    BinaryField("avoidSupportInterfaceFilamentForBase", BINARY_BOOL),
    BinaryField("supportIroning", BINARY_BOOL),
    BinaryField("supportIroningPattern", BINARY_STRING),
    BinaryField("supportIroningFlow", BINARY_FLOAT),
    BinaryField("supportIroningSpacing", BINARY_FLOAT),
    BinaryField("skirtType", BINARY_STRING),
    BinaryField("skirtLoops", BINARY_INT),
    BinaryField("skirtDistance", BINARY_FLOAT),
    BinaryField("skirtStartAngle", BINARY_FLOAT),
    BinaryField("skirtHeight", BINARY_INT),
    BinaryField("skirtSpeed", BINARY_FLOAT),
    BinaryField("minimumSkirtLength", BINARY_FLOAT),
    BinaryField("draftShield", BINARY_STRING),
    BinaryField("singleLoopDraftShield", BINARY_BOOL),
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
    BinaryField("slicingMode", BINARY_STRING),
    BinaryField("sliceClosingRadius", BINARY_FLOAT),
    BinaryField("preciseZHeight", BINARY_BOOL),
    BinaryField("holeToPolyhole", BINARY_BOOL),
    BinaryField("holeToPolyholeThreshold", BINARY_FLOAT),
    BinaryField("holeToPolyholeThresholdPercent", BINARY_BOOL),
    BinaryField("holeToPolyholeTwisted", BINARY_BOOL),
    BinaryField("seamPosition", BINARY_STRING),
    BinaryField("staggeredInnerSeams", BINARY_BOOL),
    BinaryField("seamGap", BINARY_FLOAT),
    BinaryField("seamGapPercent", BINARY_BOOL),
    BinaryField("scarfSeamType", BINARY_STRING),
    BinaryField("scarfSeamConditional", BINARY_BOOL),
    BinaryField("scarfAngleThreshold", BINARY_INT),
    BinaryField("scarfOverhangThreshold", BINARY_FLOAT),
    BinaryField("scarfJointSpeed", BINARY_FLOAT),
    BinaryField("scarfJointSpeedPercent", BINARY_BOOL),
    BinaryField("scarfJointFlowRatio", BINARY_FLOAT),
    BinaryField("scarfStartHeight", BINARY_FLOAT),
    BinaryField("scarfStartHeightPercent", BINARY_BOOL),
    BinaryField("scarfEntireLoop", BINARY_BOOL),
    BinaryField("scarfLength", BINARY_FLOAT),
    BinaryField("scarfSteps", BINARY_INT),
    BinaryField("scarfInnerWalls", BINARY_BOOL),
    BinaryField("wipeBeforeExternalLoop", BINARY_BOOL),
    BinaryField("wipeOnLoops", BINARY_BOOL),
    BinaryField("roleBasedWipeSpeed", BINARY_BOOL),
    BinaryField("wipeSpeed", BINARY_FLOAT),
    BinaryField("wipeSpeedPercent", BINARY_BOOL),
    BinaryField("ironingType", BINARY_STRING),
    BinaryField("ironingPattern", BINARY_STRING),
    BinaryField("ironingFlow", BINARY_FLOAT),
    BinaryField("ironingSpacing", BINARY_FLOAT),
    BinaryField("ironingInset", BINARY_FLOAT),
    BinaryField("ironingSpeed", BINARY_FLOAT),
    BinaryField("ironingAngle", BINARY_FLOAT),
    BinaryField("wallGenerator", BINARY_STRING),
    BinaryField("wallTransitionLength", BINARY_FLOAT),
    BinaryField("wallTransitionFilterDeviation", BINARY_FLOAT),
    BinaryField("wallTransitionAngle", BINARY_FLOAT),
    BinaryField("wallDistributionCount", BINARY_INT),
    BinaryField("minimumFeatureSize", BINARY_FLOAT),
    BinaryField("minimumWallWidth", BINARY_FLOAT),
    BinaryField("firstLayerMinimumWallWidth", BINARY_FLOAT),
    BinaryField("minimumWallLengthFactor", BINARY_FLOAT),
    BinaryField("wallSequence", BINARY_STRING),
    BinaryField("wallDirection", BINARY_STRING),
    BinaryField("detectThinWalls", BINARY_BOOL),
    BinaryField("detectOverhangWalls", BINARY_BOOL),
    BinaryField("makeOverhangPrintable", BINARY_BOOL),
    BinaryField("makeOverhangPrintableAngle", BINARY_FLOAT),
    BinaryField("makeOverhangPrintableHoleSize", BINARY_FLOAT),
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
    BinaryField("printSequence", BINARY_STRING),
    BinaryField("printOrder", BINARY_STRING),
    BinaryField("supportFilament", BINARY_INT),
    BinaryField("supportInterfaceFilament", BINARY_INT),
    BinaryField("infillFilamentOverrideEnabled", BINARY_BOOL),
    BinaryField("infillFilamentBaseFirstLayers", BINARY_INT),
    BinaryField("infillFilamentBaseLastLayers", BINARY_INT),
    BinaryField("sparseInfillFilament", BINARY_INT),
    BinaryField("wallFilament", BINARY_INT),
    BinaryField("solidInfillFilament", BINARY_INT),
    BinaryField("wipeTowerFilament", BINARY_INT),
    BinaryField("wipeTowerEnabled", BINARY_BOOL),
    BinaryField("wipeTowerWidth", BINARY_FLOAT),
    BinaryField("primeVolume", BINARY_FLOAT),
    BinaryField("primeTowerBrimWidth", BINARY_FLOAT),
    BinaryField("primeTowerFramework", BINARY_BOOL),
    BinaryField("primeTowerSkipPoints", BINARY_BOOL),
    BinaryField("primeTowerFlatIroning", BINARY_BOOL),
    BinaryField("primeTowerInterfaceFeatures", BINARY_BOOL),
    BinaryField("primeTowerInterfaceCooldown", BINARY_BOOL),
    BinaryField("primeTowerInfillGap", BINARY_FLOAT),
    BinaryField("wipeTowerNoSparseLayers", BINARY_BOOL),
    BinaryField("wipeTowerRotationAngle", BINARY_FLOAT),
    BinaryField("wipeTowerBridging", BINARY_FLOAT),
    BinaryField("wipeTowerExtraSpacing", BINARY_FLOAT),
    BinaryField("wipeTowerExtraFlow", BINARY_FLOAT),
    BinaryField("wipeTowerMaxPurgeSpeed", BINARY_FLOAT),
    BinaryField("wipeTowerWallType", BINARY_STRING),
    BinaryField("wipeTowerConeAngle", BINARY_FLOAT),
    BinaryField("wipeTowerExtraRibLength", BINARY_FLOAT),
    BinaryField("wipeTowerRibWidth", BINARY_FLOAT),
    BinaryField("wipeTowerFilletWall", BINARY_BOOL),
    BinaryField("singleExtruderMultiMaterialPriming", BINARY_BOOL),
    BinaryField("flushIntoInfill", BINARY_BOOL),
    BinaryField("flushIntoSupport", BINARY_BOOL),
    BinaryField("flushIntoObjects", BINARY_BOOL),
    BinaryField("oozePrevention", BINARY_BOOL),
    BinaryField("standbyTemperatureDelta", BINARY_INT),
    BinaryField("preheatTime", BINARY_FLOAT),
    BinaryField("preheatDeltaTemperature", BINARY_INT),
    BinaryField("preheatSteps", BINARY_INT),
    BinaryField("interfaceShells", BINARY_BOOL),
    BinaryField("segmentedRegionMaxWidth", BINARY_FLOAT),
    BinaryField("segmentedRegionInterlockingDepth", BINARY_FLOAT),
    BinaryField("interlockingBeam", BINARY_BOOL),
    BinaryField("interlockingBeamWidth", BINARY_FLOAT),
    BinaryField("interlockingOrientation", BINARY_FLOAT),
    BinaryField("interlockingBeamLayerCount", BINARY_INT),
    BinaryField("interlockingDepth", BINARY_INT),
    BinaryField("interlockingBoundaryAvoidance", BINARY_INT),
    BinaryField("maxVolumetricExtrusionRateSlope", BINARY_FLOAT),
    BinaryField("maxVolumetricExtrusionRateSlopeSegmentLength", BINARY_FLOAT),
    BinaryField("extrusionRateSmoothingExternalOnly", BINARY_BOOL),
    BinaryField("enableArcFitting", BINARY_BOOL),
    BinaryField("gcodeLabelObjects", BINARY_BOOL),
    BinaryField("excludeObject", BINARY_BOOL),
    BinaryField("gcodeComments", BINARY_BOOL),
    BinaryField("timelapseType", BINARY_STRING),
    BinaryField("initialLayerTravelSpeed", BINARY_FLOAT),
    BinaryField("initialLayerTravelSpeedPercent", BINARY_BOOL),
    BinaryField("slowDownLayers", BINARY_INT),
    BinaryField("accelToDecelEnabled", BINARY_BOOL),
    BinaryField("accelToDecelFactor", BINARY_FLOAT),
    BinaryField("filenameFormat", BINARY_STRING),
    BinaryField("spiralMode", BINARY_BOOL),
    BinaryField("spiralModeSmooth", BINARY_BOOL),
    BinaryField("spiralModeMaxXySmoothing", BINARY_FLOAT),
    BinaryField("spiralModeMaxXySmoothingPercent", BINARY_BOOL),
    BinaryField("spiralStartingFlowRatio", BINARY_FLOAT),
    BinaryField("spiralFinishingFlowRatio", BINARY_FLOAT),
    BinaryField("supportOnBuildPlateOnly", BINARY_BOOL),
    BinaryField("supportCriticalRegionsOnly", BINARY_BOOL),
    BinaryField("supportRemoveSmallOverhangs", BINARY_BOOL),
    BinaryField("supportBasePatternSpacing", BINARY_FLOAT),
    BinaryField("supportExpansion", BINARY_FLOAT),
    BinaryField("supportInterfaceLoopPattern", BINARY_BOOL),
    BinaryField("independentSupportLayerHeight", BINARY_BOOL),
    BinaryField("treeSupportBranchAngle", BINARY_FLOAT),
    BinaryField("treeSupportBranchDistance", BINARY_FLOAT),
    BinaryField("treeSupportBranchDiameter", BINARY_FLOAT),
    BinaryField("treeSupportWallCount", BINARY_INT),
    BinaryField("treeSupportTipDiameter", BINARY_FLOAT),
    BinaryField("treeSupportPreferredBranchAngle", BINARY_FLOAT),
    BinaryField("treeSupportBranchDensity", BINARY_FLOAT),
    BinaryField("treeSupportOrganicBranchAngle", BINARY_FLOAT),
    BinaryField("treeSupportOrganicBranchDistance", BINARY_FLOAT),
    BinaryField("treeSupportOrganicBranchDiameter", BINARY_FLOAT),
    BinaryField("treeSupportBranchDiameterAngle", BINARY_FLOAT),
    BinaryField("treeSupportAdaptiveLayerHeight", BINARY_BOOL),
    BinaryField("treeSupportAutoBrim", BINARY_BOOL),
    BinaryField("treeSupportBrimWidth", BINARY_FLOAT),
    BinaryField("compatiblePrinters", BINARY_STRING_LIST),
    BinaryField("defaultJerk", BINARY_FLOAT),
    BinaryField("outerWallJerk", BINARY_FLOAT),
    BinaryField("innerWallJerk", BINARY_FLOAT),
    BinaryField("topSurfaceJerk", BINARY_FLOAT),
    BinaryField("infillJerk", BINARY_FLOAT),
    BinaryField("firstLayerJerk", BINARY_FLOAT),
    BinaryField("travelJerk", BINARY_FLOAT),
    BinaryField("fuzzySkinType", BINARY_STRING),
    BinaryField("fuzzySkinFirstLayer", BINARY_BOOL),
    BinaryField("fuzzySkinPointDistance", BINARY_FLOAT),
    BinaryField("fuzzySkinThickness", BINARY_FLOAT),
    BinaryField("fuzzySkinMode", BINARY_STRING),
    BinaryField("fuzzySkinNoiseType", BINARY_STRING),
    BinaryField("fuzzySkinScale", BINARY_FLOAT),
    BinaryField("fuzzySkinOctaves", BINARY_INT),
    BinaryField("fuzzySkinPersistence", BINARY_FLOAT),
)

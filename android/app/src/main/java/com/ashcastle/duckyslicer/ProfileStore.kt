package com.ashcastle.duckyslicer

import android.content.Context
import com.u1.slicer.data.DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val USER_PROFILE_SCHEMA_VERSION = 88
internal const val MAX_USER_PROFILES = 4_096

/** Stores schema-versioned user profiles in app-private storage. */
class ProfileStore private constructor(
    private val file: File,
    private val systemCatalogProvider: () -> ProfileCatalog,
) {
    private val durableProfiles = DurableJsonFile(file, MAX_USER_PROFILE_BYTES)

    @Volatile
    var storageUnavailable: Boolean = false
        private set

    constructor(file: File) : this(file, { ProfileCatalog() })
    constructor(context: Context) : this(
        File(context.filesDir, "profiles/user_profiles.json"),
        { OrcaProfileCatalog(context.applicationContext).load() },
    )

    @Synchronized
    fun load(): ProfileCatalog {
        val root = readRoot()
        val system = systemCatalogProvider()
        return ProfileCatalog(
            printers = (system.printers + root.optJSONArray("printers").toPrinterProfiles())
                .filter(ProfileValidation::printer).distinctBy(PrinterProfile::id),
            filaments = (system.filaments + root.optJSONArray("filaments").toFilamentProfiles())
                .filter(ProfileValidation::filament).distinctBy(FilamentProfile::id),
            slicing = (system.slicing + root.optJSONArray("slicing").toQualityProfiles())
                .filter(ProfileValidation::slicing).distinctBy(QualityProfile::id),
            schemaVersion = system.schemaVersion,
            sourceRevision = system.sourceRevision,
            rejectedCount = system.rejectedCount,
        )
    }

    @Synchronized
    fun savePrinter(name: String, options: SliceOptions): PrinterProfile {
        val profile = PrinterProfile(
            id = userId(),
            name = requireName(name),
            bedSizeX = options.bedSizeX,
            bedSizeY = options.bedSizeY,
            bedOriginX = options.bedOriginX,
            bedOriginY = options.bedOriginY,
            bedPolygon = options.bedPolygon,
            bedExcludeArea = options.bedExcludeArea,
            maxPrintHeight = options.maxPrintHeight,
            nozzleDiameter = options.nozzleDiameter,
            minLayerHeight = options.printerProfile.minLayerHeight,
            maxLayerHeight = options.printerProfile.maxLayerHeight,
            machineStartGcode = options.printerProfile.machineStartGcode,
            machineEndGcode = options.printerProfile.machineEndGcode,
            machinePauseGcode = options.printerProfile.machinePauseGcode,
            timeLapseGcode = options.printerProfile.timeLapseGcode,
            beforeLayerChangeGcode = options.printerProfile.beforeLayerChangeGcode,
            layerChangeGcode = options.printerProfile.layerChangeGcode,
            changeFilamentGcode = options.printerProfile.changeFilamentGcode,
            printingByObjectGcode = options.printerProfile.printingByObjectGcode,
            useRelativeEDistances = options.printerProfile.useRelativeEDistances,
            emitMachineLimitsToGcode = options.printerProfile.emitMachineLimitsToGcode,
            manualFilamentChange = options.printerProfile.manualFilamentChange,
            disableM73 = options.printerProfile.disableM73,
            machineLoadFilamentTime = options.printerProfile.machineLoadFilamentTime,
            machineUnloadFilamentTime = options.printerProfile.machineUnloadFilamentTime,
            machineToolChangeTime = options.printerProfile.machineToolChangeTime,
            toolChangeTemperatureWait = options.printerProfile.toolChangeTemperatureWait,
            gcodeFlavor = options.gcodeFlavor,
            maxSpeedX = options.maxSpeedX,
            maxSpeedY = options.maxSpeedY,
            maxSpeedZ = options.maxSpeedZ,
            maxSpeedE = options.maxSpeedE,
            maxAccelerationX = options.maxAccelerationX,
            maxAccelerationY = options.maxAccelerationY,
            maxAccelerationZ = options.maxAccelerationZ,
            maxAccelerationE = options.maxAccelerationE,
            maxAccelerationExtruding = options.maxAccelerationExtruding,
            maxAccelerationRetracting = options.maxAccelerationRetracting,
            maxAccelerationTravel = options.maxAccelerationTravel,
            maxJerkX = options.maxJerkX,
            maxJerkY = options.maxJerkY,
            maxJerkZ = options.maxJerkZ,
            maxJerkE = options.maxJerkE,
            retractLength = options.printerProfile.retractLength,
            retractSpeed = options.printerProfile.retractSpeed,
            deretractSpeed = options.printerProfile.deretractSpeed,
            retractionMinimumTravel = options.printerProfile.retractionMinimumTravel,
            retractWhenChangingLayer = options.printerProfile.retractWhenChangingLayer,
            wipeWhileRetracting = options.printerProfile.wipeWhileRetracting,
            wipeDistance = options.printerProfile.wipeDistance,
            retractBeforeWipe = options.printerProfile.retractBeforeWipe,
            retractRestartExtra = options.printerProfile.retractRestartExtra,
            extruderOffsetsX = options.printerProfile.extruderOffsetsX,
            extruderOffsetsY = options.printerProfile.extruderOffsetsY,
            toolChangeRetractLengths = options.printerProfile.toolChangeRetractLengths,
            toolChangeRetractRestartExtras = options.printerProfile.toolChangeRetractRestartExtras,
            zHop = options.printerProfile.zHop,
            zHopType = options.printerProfile.zHopType,
            retractLiftAbove = options.printerProfile.retractLiftAbove,
            retractLiftBelow = options.printerProfile.retractLiftBelow,
            retractLiftEnforce = options.printerProfile.retractLiftEnforce,
            travelSlope = options.printerProfile.travelSlope,
            zHopWhenPrime = options.printerProfile.zHopWhenPrime,
            useFirmwareRetraction = options.printerProfile.useFirmwareRetraction,
            longRetractionWhenCutLevel = options.printerProfile.longRetractionWhenCutLevel,
            longRetractionWhenCut = options.printerProfile.longRetractionWhenCut,
            retractionDistanceWhenCut = options.printerProfile.retractionDistanceWhenCut,
            extruderClearanceRadius = options.extruderClearanceRadius,
            extruderClearanceHeightToRod = options.extruderClearanceHeightToRod,
            extruderClearanceHeightToLid = options.extruderClearanceHeightToLid,
            singleExtruderMultiMaterial = options.printerProfile.singleExtruderMultiMaterial,
            coolingTubeRetraction = options.printerProfile.coolingTubeRetraction,
            coolingTubeLength = options.printerProfile.coolingTubeLength,
            parkingPosRetraction = options.printerProfile.parkingPosRetraction,
            extraLoadingMove = options.printerProfile.extraLoadingMove,
            enableFilamentRamming = options.printerProfile.enableFilamentRamming,
            purgeInPrimeTower = options.printerProfile.purgeInPrimeTower,
            highCurrentOnFilamentSwap = options.printerProfile.highCurrentOnFilamentSwap,
            extruderCount = options.printerProfile.extruderCount,
            auxiliaryFan = options.printerProfile.auxiliaryFan,
            fanSpeedupTime = options.printerProfile.fanSpeedupTime,
            fanSpeedupOverhangs = options.printerProfile.fanSpeedupOverhangs,
            fanKickstart = options.printerProfile.fanKickstart,
            supportsChamberTemperatureControl = options.printerProfile.supportsChamberTemperatureControl,
            supportsAirFiltration = options.printerProfile.supportsAirFiltration,
            defaultPrintProfile = options.quality.name,
            defaultFilamentProfiles = options.resolvedFilamentSlots().map(FilamentProfile::name),
        )
        require(ProfileValidation.printer(profile)) { "Printer profile contains unsafe values" }
        append("printers", profile.toProfileJson())
        return profile
    }

    @Synchronized
    fun saveFilament(name: String, options: SliceOptions, slot: Int = 0): FilamentProfile {
        val selected = options.resolvedFilamentSlots().getOrElse(slot) {
            throw IllegalArgumentException("Filament slot is unavailable")
        }
        val effective = if (slot == 0) {
            selected.withBedTemperature(
                options.buildPlate.type,
                options.bedTemp,
                options.firstLayerBedTemp,
            ).copy(
                nozzleTemp = options.nozzleTemp,
                firstLayerNozzleTemp = options.firstLayerNozzleTemp,
                flowRatio = options.flowRatio,
                maxVolumetricSpeed = options.maxVolumetricSpeed,
                diameter = options.filamentDiameter,
                density = options.filamentProfile.density,
                costPerKilogram = options.filamentProfile.costPerKilogram,
                shrinkageXyPercent = options.filamentProfile.shrinkageXyPercent,
                shrinkageZPercent = options.filamentProfile.shrinkageZPercent,
                soluble = options.filamentProfile.soluble,
                supportMaterial = options.filamentProfile.supportMaterial,
                minimalPurgeOnWipeTower = options.filamentProfile.minimalPurgeOnWipeTower,
                additionalCoolingFanSpeed = options.filamentProfile.additionalCoolingFanSpeed,
                loadingSpeed = options.filamentProfile.loadingSpeed,
                loadingSpeedStart = options.filamentProfile.loadingSpeedStart,
                unloadingSpeed = options.filamentProfile.unloadingSpeed,
                unloadingSpeedStart = options.filamentProfile.unloadingSpeedStart,
                toolchangeDelay = options.filamentProfile.toolchangeDelay,
                coolingMoves = options.filamentProfile.coolingMoves,
                stampingLoadingSpeed = options.filamentProfile.stampingLoadingSpeed,
                stampingDistance = options.filamentProfile.stampingDistance,
                coolingInitialSpeed = options.filamentProfile.coolingInitialSpeed,
                coolingFinalSpeed = options.filamentProfile.coolingFinalSpeed,
                rammingParameters = options.filamentProfile.rammingParameters,
                multitoolRamming = options.filamentProfile.multitoolRamming,
                multitoolRammingVolume = options.filamentProfile.multitoolRammingVolume,
                multitoolRammingFlow = options.filamentProfile.multitoolRammingFlow,
                fanMinSpeed = options.fanMinSpeed,
                fanMaxSpeed = options.fanMaxSpeed,
                overhangFanSpeed = options.overhangFanSpeed,
                slowDownLayerTime = options.slowDownLayerTime,
                slowDownMinSpeed = options.slowDownMinSpeed,
                closeFanFirstLayers = options.closeFanFirstLayers,
                fullFanSpeedLayer = options.fullFanSpeedLayer,
                pressureAdvanceEnabled = options.pressureAdvanceEnabled,
                pressureAdvance = options.pressureAdvance,
            )
        } else {
            selected
        }
        val profile = FilamentProfile(
            id = userId(),
            name = requireName(name),
            nativeName = effective.nativeName,
            nozzleTemp = effective.nozzleTemp,
            firstLayerNozzleTemp = effective.firstLayerNozzleTemp,
            idleTemperature = effective.idleTemperature,
            bedTemp = effective.bedTemp,
            firstLayerBedTemp = effective.firstLayerBedTemp,
            texturedPlateTemp = effective.texturedPlateTemp,
            firstLayerTexturedPlateTemp = effective.firstLayerTexturedPlateTemp,
            engineeringPlateTemp = effective.engineeringPlateTemp,
            firstLayerEngineeringPlateTemp = effective.firstLayerEngineeringPlateTemp,
            coolPlateTemp = effective.coolPlateTemp,
            firstLayerCoolPlateTemp = effective.firstLayerCoolPlateTemp,
            texturedCoolPlateTemp = effective.texturedCoolPlateTemp,
            firstLayerTexturedCoolPlateTemp = effective.firstLayerTexturedCoolPlateTemp,
            superTackPlateTemp = effective.superTackPlateTemp,
            firstLayerSuperTackPlateTemp = effective.firstLayerSuperTackPlateTemp,
            graphicEffectPlateTemp = effective.graphicEffectPlateTemp,
            firstLayerGraphicEffectPlateTemp = effective.firstLayerGraphicEffectPlateTemp,
            flowRatio = effective.flowRatio,
            maxVolumetricSpeed = effective.maxVolumetricSpeed,
            filamentStartGcode = effective.filamentStartGcode,
            filamentEndGcode = effective.filamentEndGcode,
            retractLength = effective.retractLength,
            retractSpeed = effective.retractSpeed,
            deretractSpeed = effective.deretractSpeed,
            retractionMinimumTravel = effective.retractionMinimumTravel,
            retractWhenChangingLayer = effective.retractWhenChangingLayer,
            wipeWhileRetracting = effective.wipeWhileRetracting,
            wipeDistance = effective.wipeDistance,
            retractBeforeWipe = effective.retractBeforeWipe,
            retractRestartExtra = effective.retractRestartExtra,
            zHop = effective.zHop,
            zHopType = effective.zHopType,
            retractLiftAbove = effective.retractLiftAbove,
            retractLiftBelow = effective.retractLiftBelow,
            retractLiftEnforce = effective.retractLiftEnforce,
            longRetractionWhenCut = effective.longRetractionWhenCut,
            retractionDistanceWhenCut = effective.retractionDistanceWhenCut,
            fanMinSpeed = effective.fanMinSpeed,
            fanMaxSpeed = effective.fanMaxSpeed,
            fanCoolingLayerTime = effective.fanCoolingLayerTime,
            slowDownForLayerCooling = effective.slowDownForLayerCooling,
            keepFanAlwaysOn = effective.keepFanAlwaysOn,
            dontSlowDownOuterWall = effective.dontSlowDownOuterWall,
            enableOverhangBridgeFan = effective.enableOverhangBridgeFan,
            overhangFanSpeed = effective.overhangFanSpeed,
            overhangFanThreshold = effective.overhangFanThreshold,
            internalBridgeFanSpeed = effective.internalBridgeFanSpeed,
            supportInterfaceFanSpeed = effective.supportInterfaceFanSpeed,
            slowDownLayerTime = effective.slowDownLayerTime,
            slowDownMinSpeed = effective.slowDownMinSpeed,
            closeFanFirstLayers = effective.closeFanFirstLayers,
            fullFanSpeedLayer = effective.fullFanSpeedLayer,
            pressureAdvanceEnabled = effective.pressureAdvanceEnabled,
            pressureAdvance = effective.pressureAdvance,
            diameter = effective.diameter,
            density = effective.density,
            costPerKilogram = effective.costPerKilogram,
            shrinkageXyPercent = effective.shrinkageXyPercent,
            shrinkageZPercent = effective.shrinkageZPercent,
            soluble = effective.soluble,
            supportMaterial = effective.supportMaterial,
            minimalPurgeOnWipeTower = effective.minimalPurgeOnWipeTower,
            towerInterfacePreExtrusionDistance = effective.towerInterfacePreExtrusionDistance,
            towerInterfacePreExtrusionLength = effective.towerInterfacePreExtrusionLength,
            towerIroningArea = effective.towerIroningArea,
            towerInterfacePurgeLength = effective.towerInterfacePurgeLength,
            towerInterfacePrintTemperature = effective.towerInterfacePrintTemperature,
            additionalCoolingFanSpeed = effective.additionalCoolingFanSpeed,
            loadingSpeed = effective.loadingSpeed,
            loadingSpeedStart = effective.loadingSpeedStart,
            unloadingSpeed = effective.unloadingSpeed,
            unloadingSpeedStart = effective.unloadingSpeedStart,
            toolchangeDelay = effective.toolchangeDelay,
            coolingMoves = effective.coolingMoves,
            stampingLoadingSpeed = effective.stampingLoadingSpeed,
            stampingDistance = effective.stampingDistance,
            coolingInitialSpeed = effective.coolingInitialSpeed,
            coolingFinalSpeed = effective.coolingFinalSpeed,
            rammingParameters = effective.rammingParameters,
            multitoolRamming = effective.multitoolRamming,
            multitoolRammingVolume = effective.multitoolRammingVolume,
            multitoolRammingFlow = effective.multitoolRammingFlow,
            softeningTemperature = effective.softeningTemperature,
            nozzleTemperatureRangeLow = effective.nozzleTemperatureRangeLow,
            nozzleTemperatureRangeHigh = effective.nozzleTemperatureRangeHigh,
            chamberTemperatureControl = effective.chamberTemperatureControl,
            chamberTemperature = effective.chamberTemperature,
            airFiltration = effective.airFiltration,
            duringPrintExhaustFanSpeed = effective.duringPrintExhaustFanSpeed,
            completePrintExhaustFanSpeed = effective.completePrintExhaustFanSpeed,
        )
        require(ProfileValidation.filament(profile)) { "Filament profile contains unsafe values" }
        append("filaments", profile.toProfileJson())
        return profile
    }

    @Synchronized
    fun saveSlicing(name: String, options: SliceOptions): QualityProfile {
        val profile = QualityProfile(
            id = userId(),
            name = requireName(name),
            layerHeightMm = options.layerHeight,
            firstLayerHeightMm = options.firstLayerHeight,
            perimeters = options.perimeters,
            fillDensity = options.fillDensity,
            printSpeed = options.printSpeed,
            nozzleDiameter = options.nozzleDiameter,
            innerWallSpeed = options.innerWallSpeed,
            sparseInfillSpeed = options.sparseInfillSpeed,
            internalSolidInfillSpeed = options.internalSolidInfillSpeed,
            topSurfaceSpeed = options.topSurfaceSpeed,
            supportSpeed = options.supportSpeed,
            bridgeSpeed = options.bridgeSpeed,
            gapInfillSpeed = options.gapInfillSpeed,
            firstLayerInfillSpeed = options.firstLayerInfillSpeed,
            supportInterfaceSpeed = options.supportInterfaceSpeed,
            internalBridgeSpeed = options.internalBridgeSpeed,
            internalBridgeSpeedPercent = options.internalBridgeSpeedPercent,
            overhangSpeedEnabled = options.overhangSpeedEnabled,
            overhangSpeed1 = options.overhangSpeed1,
            overhangSpeed1Percent = options.overhangSpeed1Percent,
            overhangSpeed2 = options.overhangSpeed2,
            overhangSpeed2Percent = options.overhangSpeed2Percent,
            overhangSpeed3 = options.overhangSpeed3,
            overhangSpeed3Percent = options.overhangSpeed3Percent,
            overhangSpeed4 = options.overhangSpeed4,
            overhangSpeed4Percent = options.overhangSpeed4Percent,
            printFlowRatio = options.printFlowRatio,
            bridgeFlowRatio = options.bridgeFlowRatio,
            internalBridgeFlowRatio = options.internalBridgeFlowRatio,
            topSurfaceFlowRatio = options.topSurfaceFlowRatio,
            bottomSurfaceFlowRatio = options.bottomSurfaceFlowRatio,
            bridgeDensity = options.bridgeDensity,
            internalBridgeDensity = options.internalBridgeDensity,
            bridgeAngle = options.bridgeAngle,
            internalBridgeAngle = options.internalBridgeAngle,
            bridgeNoSupport = options.bridgeNoSupport,
            thickBridges = options.thickBridges,
            thickInternalBridges = options.thickInternalBridges,
            extraBridgeLayer = options.extraBridgeLayer,
            internalBridgeFilter = options.internalBridgeFilter,
            defaultAcceleration = options.defaultAcceleration,
            outerWallAcceleration = options.outerWallAcceleration,
            innerWallAcceleration = options.innerWallAcceleration,
            topSurfaceAcceleration = options.topSurfaceAcceleration,
            travelAcceleration = options.travelAcceleration,
            firstLayerAcceleration = options.firstLayerAcceleration,
            bridgeAcceleration = options.bridgeAcceleration,
            bridgeAccelerationPercent = options.bridgeAccelerationPercent,
            sparseInfillAcceleration = options.sparseInfillAcceleration,
            sparseInfillAccelerationPercent = options.sparseInfillAccelerationPercent,
            internalSolidInfillAcceleration = options.internalSolidInfillAcceleration,
            internalSolidInfillAccelerationPercent = options.internalSolidInfillAccelerationPercent,
            defaultJerk = options.defaultJerk,
            outerWallJerk = options.outerWallJerk,
            innerWallJerk = options.innerWallJerk,
            topSurfaceJerk = options.topSurfaceJerk,
            infillJerk = options.infillJerk,
            firstLayerJerk = options.firstLayerJerk,
            travelJerk = options.travelJerk,
            extrusionRateSmoothing = options.quality.extrusionRateSmoothing,
            fuzzySkin = options.fuzzySkin,
            supportEnabled = options.supportEnabled,
            brimType = options.brimType,
            brimWidth = options.brimWidth,
            brimObjectGap = options.brimObjectGap,
            raftLayers = options.raftLayers,
            raftContactDistance = options.raftContactDistance,
            raftExpansion = options.raftExpansion,
            raftFirstLayerDensity = options.raftFirstLayerDensity,
            raftFirstLayerExpansion = options.raftFirstLayerExpansion,
            topSolidLayers = options.topSolidLayers,
            bottomSolidLayers = options.bottomSolidLayers,
            topShellThickness = options.topShellThickness,
            bottomShellThickness = options.bottomShellThickness,
            surfaceDensity = options.quality.surfaceDensity,
            fillPattern = options.fillPattern,
            fillMultiline = options.quality.fillMultiline,
            lateralInfill = options.quality.lateralInfill,
            topSurfacePattern = options.topSurfacePattern,
            bottomSurfacePattern = options.bottomSurfacePattern,
            internalSolidInfillPattern = options.internalSolidInfillPattern,
            infillFirst = options.infillFirst,
            infillWallOverlap = options.infillWallOverlap,
            topBottomInfillWallOverlap = options.topBottomInfillWallOverlap,
            infillCombination = options.infillCombination,
            infillCombinationMaxLayerHeight = options.infillCombinationMaxLayerHeight,
            infillCombinationMaxLayerHeightPercent = options.infillCombinationMaxLayerHeightPercent,
            infillDirection = options.infillDirection,
            solidInfillDirection = options.solidInfillDirection,
            alignInfillDirectionToModel = options.alignInfillDirectionToModel,
            minimumSparseInfillArea = options.minimumSparseInfillArea,
            infillAnchor = options.infillAnchor,
            infillAnchorPercent = options.infillAnchorPercent,
            infillAnchorMax = options.infillAnchorMax,
            infillAnchorMaxPercent = options.infillAnchorMaxPercent,
            skeletonInfillDensity = options.quality.skeletonInfillDensity,
            skinInfillDensity = options.quality.skinInfillDensity,
            skinInfillDepth = options.quality.skinInfillDepth,
            infillLockDepth = options.quality.infillLockDepth,
            infillShiftStep = options.quality.infillShiftStep,
            symmetricInfillYAxis = options.quality.symmetricInfillYAxis,
            sparseInfillRotationTemplate = options.quality.sparseInfillRotationTemplate,
            solidInfillRotationTemplate = options.quality.solidInfillRotationTemplate,
            smallAreaFlowCompensation = options.quality.smallAreaFlowCompensation,
            smallAreaFlowCompensationModel = options.quality.smallAreaFlowCompensationModel,
            skinInfillLineWidth = options.quality.skinInfillLineWidth,
            skinInfillLineWidthPercent = options.quality.skinInfillLineWidthPercent,
            skeletonInfillLineWidth = options.quality.skeletonInfillLineWidth,
            skeletonInfillLineWidthPercent = options.quality.skeletonInfillLineWidthPercent,
            gapFillTarget = options.gapFillTarget,
            filterOutGapFill = options.filterOutGapFill,
            reduceCrossingWall = options.reduceCrossingWall,
            maxTravelDetourDistance = options.maxTravelDetourDistance,
            maxTravelDetourDistancePercent = options.maxTravelDetourDistancePercent,
            reduceInfillRetraction = options.reduceInfillRetraction,
            travelSpeed = options.travelSpeed,
            travelSpeedZ = options.travelSpeedZ,
            firstLayerSpeed = options.firstLayerSpeed,
            supportType = normalizedSupportType(options.supportType),
            supportAngle = options.supportAngle,
            supportInterfaceTopLayers = options.supportInterfaceTopLayers,
            supportInterfaceBottomLayers = options.supportInterfaceBottomLayers,
            supportInterfaceSpacing = options.supportInterfaceSpacing,
            supportBottomInterfaceSpacing = options.supportBottomInterfaceSpacing,
            supportTopZDistance = options.supportTopZDistance,
            supportBottomZDistance = options.supportBottomZDistance,
            supportObjectXYDistance = options.supportObjectXYDistance,
            supportBasePattern = options.supportBasePattern,
            supportInterfacePattern = options.supportInterfacePattern,
            supportStyle = normalizedSupportStyle(options.supportType, options.supportStyle),
            supportCoverage = options.supportCoverage,
            supportAdvanced = options.supportAdvanced,
            supportBasePatternSpacing = options.supportBasePatternSpacing,
            supportExpansion = options.supportExpansion,
            supportInterfaceLoopPattern = options.supportInterfaceLoopPattern,
            independentSupportLayerHeight = options.independentSupportLayerHeight,
            treeSupportBranchAngle = options.treeSupportBranchAngle,
            treeSupportBranchDistance = options.treeSupportBranchDistance,
            treeSupportBranchDiameter = options.treeSupportBranchDiameter,
            treeSupportWallCount = options.treeSupportWallCount,
            treeSupportTipDiameter = options.treeSupportTipDiameter,
            treeSupportPreferredBranchAngle = options.treeSupportPreferredBranchAngle,
            treeSupportBranchDensity = options.treeSupportBranchDensity,
            treeSupportOrganicBranchAngle = options.treeSupportOrganicBranchAngle,
            treeSupportOrganicBranchDistance = options.treeSupportOrganicBranchDistance,
            treeSupportOrganicBranchDiameter = options.treeSupportOrganicBranchDiameter,
            treeSupportBranchDiameterAngle = options.treeSupportBranchDiameterAngle,
            treeSupportAdaptiveLayerHeight = options.treeSupportAdaptiveLayerHeight,
            treeSupportAutoBrim = options.treeSupportAutoBrim,
            treeSupportBrimWidth = options.treeSupportBrimWidth,
            supportFilament = options.supportFilament,
            supportInterfaceFilament = options.supportInterfaceFilament,
            featureFilaments = options.featureFilaments,
            wipeTowerEnabled = options.wipeTowerEnabled,
            wipeTowerWidth = options.wipeTowerWidth,
            multiMaterial = options.multiMaterial,
            gcodeSettings = options.gcodeSettings,
            skirtType = options.quality.skirtType,
            skirtLoops = options.skirtLoops,
            skirtDistance = options.skirtDistance,
            skirtStartAngle = options.quality.skirtStartAngle,
            skirtHeight = options.skirtHeight,
            skirtSpeed = options.skirtSpeed,
            minimumSkirtLength = options.minimumSkirtLength,
            draftShield = options.draftShield,
            singleLoopDraftShield = options.quality.singleLoopDraftShield,
            outerWallLineWidth = options.outerWallLineWidth,
            innerWallLineWidth = options.innerWallLineWidth,
            topSurfaceLineWidth = options.topSurfaceLineWidth,
            sparseInfillLineWidth = options.sparseInfillLineWidth,
            internalSolidInfillLineWidth = options.internalSolidInfillLineWidth,
            supportLineWidth = options.supportLineWidth,
            initialLayerLineWidth = options.initialLayerLineWidth,
            smallPerimeterSpeed = options.smallPerimeterSpeed,
            smallPerimeterSpeedPercent = options.smallPerimeterSpeedPercent,
            smallPerimeterThreshold = options.smallPerimeterThreshold,
            slowdownForCurledPerimeters = options.slowdownForCurledPerimeters,
            resolution = options.resolution,
            precision = options.precision,
            seamPosition = options.seamPosition,
            staggeredInnerSeams = options.staggeredInnerSeams,
            seamGap = options.seamGap,
            seamGapPercent = options.seamGapPercent,
            scarfSeam = options.scarfSeam,
            wipeBeforeExternalLoop = options.wipeBeforeExternalLoop,
            wipeOnLoops = options.wipeOnLoops,
            roleBasedWipeSpeed = options.roleBasedWipeSpeed,
            wipeSpeed = options.wipeSpeed,
            wipeSpeedPercent = options.wipeSpeedPercent,
            ironing = options.ironing,
            wallGenerator = options.wallGenerator,
            wallTransitionLength = options.wallTransitionLength,
            wallTransitionFilterDeviation = options.wallTransitionFilterDeviation,
            wallTransitionAngle = options.wallTransitionAngle,
            wallDistributionCount = options.wallDistributionCount,
            minimumFeatureSize = options.minimumFeatureSize,
            minimumWallLengthFactor = options.minimumWallLengthFactor,
            wallSequence = options.wallSequence,
            wallDirection = options.wallDirection,
            detectThinWalls = options.detectThinWalls,
            detectOverhangWalls = options.detectOverhangWalls,
            onlyOneWallOnTop = options.onlyOneWallOnTop,
            minWidthTopSurface = options.minWidthTopSurface,
            minWidthTopSurfacePercent = options.minWidthTopSurfacePercent,
            onlyOneWallFirstLayer = options.onlyOneWallFirstLayer,
            extraPerimetersOnOverhangs = options.extraPerimetersOnOverhangs,
            overhangReverse = options.overhangReverse,
            overhangReverseInternalOnly = options.overhangReverseInternalOnly,
            overhangReverseThreshold = options.overhangReverseThreshold,
            overhangReverseThresholdPercent = options.overhangReverseThresholdPercent,
            counterboreHoleBridging = options.counterboreHoleBridging,
            alternateExtraWall = options.alternateExtraWall,
            ensureVerticalShellThickness = options.ensureVerticalShellThickness,
            detectNarrowInternalSolidInfill = options.detectNarrowInternalSolidInfill,
            xyHoleCompensation = options.xyHoleCompensation,
            xyContourCompensation = options.xyContourCompensation,
            elephantFootCompensation = options.elephantFootCompensation,
            elephantFootCompensationLayers = options.elephantFootCompensationLayers,
            maxBridgeLength = options.maxBridgeLength,
            preciseOuterWalls = options.preciseOuterWalls,
            printSequence = options.printSequence,
            printOrder = options.printOrder,
            spiralMode = options.spiralMode,
            spiralModeSmooth = options.spiralModeSmooth,
            spiralModeMaxXySmoothing = options.spiralModeMaxXySmoothing,
            spiralModeMaxXySmoothingPercent = options.spiralModeMaxXySmoothingPercent,
            spiralStartingFlowRatio = options.spiralStartingFlowRatio,
            spiralFinishingFlowRatio = options.spiralFinishingFlowRatio,
        )
        require(ProfileValidation.slicing(profile)) { "Slicing profile contains unsafe values" }
        append("slicing", profile.toProfileJson())
        return profile
    }

    @Synchronized
    internal fun exportBundle(): ByteArray {
        val stored = durableProfiles.read(::validateRoot, ::isCompatibleRoot)
        storageUnavailable = !stored.status.mutationSafe
        check(stored.value != null || stored.status == DurableJsonStatus.MISSING) {
            "saved_data_unreadable"
        }
        return encodeProfileBundle(stored.value ?: JSONObject())
    }

    @Synchronized
    internal fun importBundle(
        bytes: ByteArray,
        beforeCommit: () -> Unit = {},
    ): ProfileBundleImportResult {
        val merged = mergeProfileBundle(readRoot(forMutation = true), bytes, ::userId)
        beforeCommit()
        if (merged.result.importedTotal > 0) writeRoot(merged.root)
        return merged.result
    }

    private fun append(key: String, value: JSONObject) {
        val root = readRoot(forMutation = true)
        root.put("schemaVersion", USER_PROFILE_SCHEMA_VERSION)
        val values = root.optJSONArray(key) ?: JSONArray().also { root.put(key, it) }
        values.put(value)
        writeRoot(root)
    }

    private fun readRoot(forMutation: Boolean = false): JSONObject {
        val stored = durableProfiles.read(::validateRoot, ::isCompatibleRoot)
        storageUnavailable = !stored.status.mutationSafe
        if (forMutation) check(!storageUnavailable) { "saved_data_unreadable" }
        return stored.value ?: JSONObject()
    }

    private fun writeRoot(root: JSONObject) {
        durableProfiles.write(root, ::validateRoot, ::isCompatibleRoot)
        storageUnavailable = false
    }

    private fun validateRoot(root: JSONObject): JSONObject? {
        val schemaVersion = root.optInt("schemaVersion", 1)
        if (schemaVersion !in 1..USER_PROFILE_SCHEMA_VERSION) return null
        var total = 0
        for ((key, parseId) in PROFILE_ARRAY_PARSERS) {
            if (root.has(key) && root.optJSONArray(key) == null) return null
            val values = root.optJSONArray(key) ?: continue
            total += values.length()
            val ids = HashSet<String>()
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: return null
                val id = parseId(value) ?: return null
                if (!ids.add(id)) return null
            }
        }
        return root.takeIf { total <= MAX_USER_PROFILES }
    }

    private fun isCompatibleRoot(root: JSONObject): Boolean =
        root.optInt("schemaVersion", 1) <= USER_PROFILE_SCHEMA_VERSION

    private fun userId() = "user-${UUID.randomUUID()}"

    private fun requireName(name: String) = name.trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Profile name is required")

    private companion object {
        const val MAX_USER_PROFILE_BYTES = 16 * 1_024 * 1_024
        val PROFILE_ARRAY_PARSERS: Map<String, (JSONObject) -> String?> = mapOf(
            "printers" to { value ->
                value.toPrinterProfileOrNull()?.takeIf(ProfileValidation::printer)?.id
            },
            "filaments" to { value ->
                value.toFilamentProfileOrNull()?.takeIf(ProfileValidation::filament)?.id
            },
            "slicing" to { value ->
                value.toQualityProfileOrNull()?.takeIf(ProfileValidation::slicing)?.id
            },
        )
    }
}

internal fun PrinterProfile.toProfileJson() = JSONObject()
    .put("id", id).put("name", name)
    .put("bedSizeX", bedSizeX).put("bedSizeY", bedSizeY)
    .put("bedOriginX", bedOriginX).put("bedOriginY", bedOriginY)
    .put("bedPolygon", JSONArray(bedPolygon))
    .put("bedExcludeArea", JSONArray(bedExcludeArea))
    .put("maxPrintHeight", maxPrintHeight).put("nozzleDiameter", nozzleDiameter)
    .put("minLayerHeight", minLayerHeight).put("maxLayerHeight", maxLayerHeight)
    .put("machineStartGcode", machineStartGcode).put("machineEndGcode", machineEndGcode)
    .put("machinePauseGcode", machinePauseGcode)
    .put("timeLapseGcode", timeLapseGcode)
    .put("beforeLayerChangeGcode", beforeLayerChangeGcode)
    .put("layerChangeGcode", layerChangeGcode)
    .put("changeFilamentGcode", changeFilamentGcode)
    .put("printingByObjectGcode", printingByObjectGcode)
    .put("useRelativeEDistances", useRelativeEDistances)
    .put("emitMachineLimitsToGcode", emitMachineLimitsToGcode)
    .put("manualFilamentChange", manualFilamentChange)
    .put("disableM73", disableM73)
    .put("machineLoadFilamentTime", machineLoadFilamentTime)
    .put("machineUnloadFilamentTime", machineUnloadFilamentTime)
    .put("machineToolChangeTime", machineToolChangeTime)
    .put("toolChangeTemperatureWait", toolChangeTemperatureWait)
    .put("gcodeFlavor", gcodeFlavor)
    .put("maxSpeedX", maxSpeedX).put("maxSpeedY", maxSpeedY)
    .put("maxSpeedZ", maxSpeedZ).put("maxSpeedE", maxSpeedE)
    .put("maxAccelerationX", maxAccelerationX).put("maxAccelerationY", maxAccelerationY)
    .put("maxAccelerationZ", maxAccelerationZ).put("maxAccelerationE", maxAccelerationE)
    .put("maxAccelerationExtruding", maxAccelerationExtruding)
    .put("maxAccelerationRetracting", maxAccelerationRetracting)
    .put("maxAccelerationTravel", maxAccelerationTravel)
    .put("maxJerkX", maxJerkX).put("maxJerkY", maxJerkY)
    .put("maxJerkZ", maxJerkZ).put("maxJerkE", maxJerkE)
    .put("retractLength", retractLength).put("retractSpeed", retractSpeed)
    .put("deretractSpeed", deretractSpeed)
    .put("retractionMinimumTravel", retractionMinimumTravel)
    .put("retractWhenChangingLayer", retractWhenChangingLayer)
    .put("wipeWhileRetracting", wipeWhileRetracting).put("wipeDistance", wipeDistance)
    .put("retractBeforeWipe", retractBeforeWipe).put("retractRestartExtra", retractRestartExtra)
    .put("extruderOffsetsX", JSONArray(extruderOffsetsX))
    .put("extruderOffsetsY", JSONArray(extruderOffsetsY))
    .put("toolChangeRetractLengths", JSONArray(toolChangeRetractLengths))
    .put("toolChangeRetractRestartExtras", JSONArray(toolChangeRetractRestartExtras))
    .put("zHop", zHop).put("zHopType", zHopType)
    .put("retractLiftAbove", retractLiftAbove)
    .put("retractLiftBelow", retractLiftBelow)
    .put("retractLiftEnforce", retractLiftEnforce)
    .put("travelSlope", travelSlope)
    .put("zHopWhenPrime", zHopWhenPrime)
    .put("useFirmwareRetraction", useFirmwareRetraction)
    .put("longRetractionWhenCutLevel", longRetractionWhenCutLevel)
    .put("longRetractionWhenCut", longRetractionWhenCut)
    .put("retractionDistanceWhenCut", retractionDistanceWhenCut)
    .put("extruderClearanceRadius", extruderClearanceRadius)
    .put("extruderClearanceHeightToRod", extruderClearanceHeightToRod)
    .put("extruderClearanceHeightToLid", extruderClearanceHeightToLid)
    .put("singleExtruderMultiMaterial", singleExtruderMultiMaterial)
    .put("coolingTubeRetraction", coolingTubeRetraction)
    .put("coolingTubeLength", coolingTubeLength)
    .put("parkingPosRetraction", parkingPosRetraction)
    .put("extraLoadingMove", extraLoadingMove)
    .put("enableFilamentRamming", enableFilamentRamming)
    .put("purgeInPrimeTower", purgeInPrimeTower)
    .put("highCurrentOnFilamentSwap", highCurrentOnFilamentSwap)
    .put("extruderCount", extruderCount)
    .put("auxiliaryFan", auxiliaryFan)
    .put("fanSpeedupTime", fanSpeedupTime)
    .put("fanSpeedupOverhangs", fanSpeedupOverhangs)
    .put("fanKickstart", fanKickstart)
    .put("supportsChamberTemperatureControl", supportsChamberTemperatureControl)
    .put("supportsAirFiltration", supportsAirFiltration)
    .put("defaultPrintProfile", defaultPrintProfile)
    .put("defaultFilamentProfiles", JSONArray(defaultFilamentProfiles))
    .put("builtIn", builtIn)
    .put("brand", brand ?: JSONObject.NULL)

internal fun FilamentProfile.toProfileJson() = JSONObject()
    .put("id", id).put("name", name).put("nativeName", nativeName)
    .put("nozzleTemp", nozzleTemp).put("firstLayerNozzleTemp", firstLayerNozzleTemp)
    .put("idleTemperature", idleTemperature)
    .put("bedTemp", bedTemp).put("firstLayerBedTemp", firstLayerBedTemp)
    .put("texturedPlateTemp", texturedPlateTemp)
    .put("firstLayerTexturedPlateTemp", firstLayerTexturedPlateTemp)
    .put("engineeringPlateTemp", engineeringPlateTemp)
    .put("firstLayerEngineeringPlateTemp", firstLayerEngineeringPlateTemp)
    .put("coolPlateTemp", coolPlateTemp)
    .put("firstLayerCoolPlateTemp", firstLayerCoolPlateTemp)
    .put("texturedCoolPlateTemp", texturedCoolPlateTemp)
    .put("firstLayerTexturedCoolPlateTemp", firstLayerTexturedCoolPlateTemp)
    .put("superTackPlateTemp", superTackPlateTemp)
    .put("firstLayerSuperTackPlateTemp", firstLayerSuperTackPlateTemp)
    .put("graphicEffectPlateTemp", graphicEffectPlateTemp)
    .put("firstLayerGraphicEffectPlateTemp", firstLayerGraphicEffectPlateTemp)
    .put("flowRatio", flowRatio).put("maxVolumetricSpeed", maxVolumetricSpeed)
    .put("filamentStartGcode", filamentStartGcode).put("filamentEndGcode", filamentEndGcode)
    .put("retractLength", retractLength ?: JSONObject.NULL)
    .put("retractSpeed", retractSpeed ?: JSONObject.NULL)
    .put("deretractSpeed", deretractSpeed ?: JSONObject.NULL)
    .put("retractionMinimumTravel", retractionMinimumTravel ?: JSONObject.NULL)
    .put("retractWhenChangingLayer", retractWhenChangingLayer ?: JSONObject.NULL)
    .put("wipeWhileRetracting", wipeWhileRetracting ?: JSONObject.NULL)
    .put("wipeDistance", wipeDistance ?: JSONObject.NULL)
    .put("retractBeforeWipe", retractBeforeWipe ?: JSONObject.NULL)
    .put("retractRestartExtra", retractRestartExtra ?: JSONObject.NULL)
    .put("zHop", zHop ?: JSONObject.NULL).put("zHopType", zHopType ?: JSONObject.NULL)
    .put("retractLiftAbove", retractLiftAbove ?: JSONObject.NULL)
    .put("retractLiftBelow", retractLiftBelow ?: JSONObject.NULL)
    .put("retractLiftEnforce", retractLiftEnforce ?: JSONObject.NULL)
    .put("longRetractionWhenCut", longRetractionWhenCut ?: JSONObject.NULL)
    .put("retractionDistanceWhenCut", retractionDistanceWhenCut ?: JSONObject.NULL)
    .put("fanMinSpeed", fanMinSpeed).put("fanMaxSpeed", fanMaxSpeed)
    .put("fanCoolingLayerTime", fanCoolingLayerTime)
    .put("slowDownForLayerCooling", slowDownForLayerCooling)
    .put("keepFanAlwaysOn", keepFanAlwaysOn)
    .put("dontSlowDownOuterWall", dontSlowDownOuterWall)
    .put("enableOverhangBridgeFan", enableOverhangBridgeFan)
    .put("overhangFanSpeed", overhangFanSpeed)
    .put("overhangFanThreshold", overhangFanThreshold)
    .put("internalBridgeFanSpeed", internalBridgeFanSpeed)
    .put("supportInterfaceFanSpeed", supportInterfaceFanSpeed)
    .put("slowDownLayerTime", slowDownLayerTime).put("slowDownMinSpeed", slowDownMinSpeed)
    .put("closeFanFirstLayers", closeFanFirstLayers).put("fullFanSpeedLayer", fullFanSpeedLayer)
    .put("pressureAdvanceEnabled", pressureAdvanceEnabled).put("pressureAdvance", pressureAdvance)
    .put("diameter", diameter)
    .put("density", density)
    .put("costPerKilogram", costPerKilogram)
    .put("shrinkageXyPercent", shrinkageXyPercent)
    .put("shrinkageZPercent", shrinkageZPercent)
    .put("soluble", soluble)
    .put("supportMaterial", supportMaterial)
    .put("minimalPurgeOnWipeTower", minimalPurgeOnWipeTower)
    .put("towerInterfacePreExtrusionDistance", towerInterfacePreExtrusionDistance)
    .put("towerInterfacePreExtrusionLength", towerInterfacePreExtrusionLength)
    .put("towerIroningArea", towerIroningArea)
    .put("towerInterfacePurgeLength", towerInterfacePurgeLength)
    .put("towerInterfacePrintTemperature", towerInterfacePrintTemperature)
    .put("additionalCoolingFanSpeed", additionalCoolingFanSpeed)
    .put("loadingSpeed", loadingSpeed)
    .put("loadingSpeedStart", loadingSpeedStart)
    .put("unloadingSpeed", unloadingSpeed)
    .put("unloadingSpeedStart", unloadingSpeedStart)
    .put("toolchangeDelay", toolchangeDelay)
    .put("coolingMoves", coolingMoves)
    .put("stampingLoadingSpeed", stampingLoadingSpeed)
    .put("stampingDistance", stampingDistance)
    .put("coolingInitialSpeed", coolingInitialSpeed)
    .put("coolingFinalSpeed", coolingFinalSpeed)
    .put("rammingParameters", rammingParameters)
    .put("multitoolRamming", multitoolRamming)
    .put("multitoolRammingVolume", multitoolRammingVolume)
    .put("multitoolRammingFlow", multitoolRammingFlow)
    .put("softeningTemperature", softeningTemperature)
    .put("nozzleTemperatureRangeLow", nozzleTemperatureRangeLow)
    .put("nozzleTemperatureRangeHigh", nozzleTemperatureRangeHigh)
    .put("chamberTemperatureControl", chamberTemperatureControl)
    .put("chamberTemperature", chamberTemperature)
    .put("airFiltration", airFiltration)
    .put("duringPrintExhaustFanSpeed", duringPrintExhaustFanSpeed)
    .put("completePrintExhaustFanSpeed", completePrintExhaustFanSpeed)
    .put("builtIn", builtIn)
    .put("brand", brand ?: JSONObject.NULL)
    .put("compatiblePrinters", JSONArray(compatiblePrinters))

internal fun QualityProfile.toProfileJson() = JSONObject()
    .put("id", id).put("name", name)
    .put("layerHeightMm", layerHeightMm).put("firstLayerHeightMm", firstLayerHeightMm)
    .put("perimeters", perimeters).put("fillDensity", fillDensity).put("printSpeed", printSpeed)
    .put("nozzleDiameter", nozzleDiameter)
    .put("innerWallSpeed", innerWallSpeed)
    .put("sparseInfillSpeed", sparseInfillSpeed)
    .put("internalSolidInfillSpeed", internalSolidInfillSpeed)
    .put("topSurfaceSpeed", topSurfaceSpeed)
    .put("supportSpeed", supportSpeed)
    .put("bridgeSpeed", bridgeSpeed)
    .put("gapInfillSpeed", gapInfillSpeed)
    .put("firstLayerInfillSpeed", firstLayerInfillSpeed)
    .put("supportInterfaceSpeed", supportInterfaceSpeed)
    .put("internalBridgeSpeed", internalBridgeSpeed)
    .put("internalBridgeSpeedPercent", internalBridgeSpeedPercent)
    .put("overhangSpeedEnabled", overhangSpeedEnabled)
    .put("overhangSpeed1", overhangSpeed1).put("overhangSpeed1Percent", overhangSpeed1Percent)
    .put("overhangSpeed2", overhangSpeed2).put("overhangSpeed2Percent", overhangSpeed2Percent)
    .put("overhangSpeed3", overhangSpeed3).put("overhangSpeed3Percent", overhangSpeed3Percent)
    .put("overhangSpeed4", overhangSpeed4).put("overhangSpeed4Percent", overhangSpeed4Percent)
    .put("printFlowRatio", printFlowRatio)
    .put("bridgeFlowRatio", bridgeFlowRatio)
    .put("internalBridgeFlowRatio", internalBridgeFlowRatio)
    .put("topSurfaceFlowRatio", topSurfaceFlowRatio)
    .put("bottomSurfaceFlowRatio", bottomSurfaceFlowRatio)
    .put("bridgeDensity", bridgeDensity)
    .put("internalBridgeDensity", internalBridgeDensity)
    .put("bridgeAngle", bridgeAngle)
    .put("internalBridgeAngle", internalBridgeAngle)
    .put("bridgeNoSupport", bridgeNoSupport)
    .put("thickBridges", thickBridges)
    .put("thickInternalBridges", thickInternalBridges)
    .put("extraBridgeLayer", extraBridgeLayer)
    .put("internalBridgeFilter", internalBridgeFilter)
    .put("defaultAcceleration", defaultAcceleration)
    .put("outerWallAcceleration", outerWallAcceleration)
    .put("innerWallAcceleration", innerWallAcceleration)
    .put("topSurfaceAcceleration", topSurfaceAcceleration)
    .put("travelAcceleration", travelAcceleration)
    .put("firstLayerAcceleration", firstLayerAcceleration)
    .put("bridgeAcceleration", bridgeAcceleration)
    .put("bridgeAccelerationPercent", bridgeAccelerationPercent)
    .put("sparseInfillAcceleration", sparseInfillAcceleration)
    .put("sparseInfillAccelerationPercent", sparseInfillAccelerationPercent)
    .put("internalSolidInfillAcceleration", internalSolidInfillAcceleration)
    .put("internalSolidInfillAccelerationPercent", internalSolidInfillAccelerationPercent)
    .put("defaultJerk", defaultJerk)
    .put("outerWallJerk", outerWallJerk)
    .put("innerWallJerk", innerWallJerk)
    .put("topSurfaceJerk", topSurfaceJerk)
    .put("infillJerk", infillJerk)
    .put("firstLayerJerk", firstLayerJerk)
    .put("travelJerk", travelJerk)
    .put("maxVolumetricExtrusionRateSlope", extrusionRateSmoothing.maximumSlope)
    .put("maxVolumetricExtrusionRateSlopeSegmentLength", extrusionRateSmoothing.segmentLength)
    .put("extrusionRateSmoothingExternalOnly", extrusionRateSmoothing.externalOnly)
    .put("fuzzySkinType", fuzzySkin.type)
    .put("fuzzySkinFirstLayer", fuzzySkin.firstLayer)
    .put("fuzzySkinPointDistance", fuzzySkin.pointDistance)
    .put("fuzzySkinThickness", fuzzySkin.thickness)
    .put("fuzzySkinMode", fuzzySkin.mode)
    .put("fuzzySkinNoiseType", fuzzySkin.noiseType)
    .put("fuzzySkinScale", fuzzySkin.scale)
    .put("fuzzySkinOctaves", fuzzySkin.octaves)
    .put("fuzzySkinPersistence", fuzzySkin.persistence)
    .put("supportEnabled", supportEnabled)
    .put("enforceSupportLayers", supportCoverage.enforcedLayers)
    .put("brimType", brimType)
    .put("brimWidth", brimWidth)
    .put("brimObjectGap", brimObjectGap)
    .put("brimEarsMaxAngle", precision.brimEars.maximumAngle)
    .put("brimEarsDetectionLength", precision.brimEars.detectionRadius)
    .put("raftLayers", raftLayers)
    .put("raftContactDistance", raftContactDistance)
    .put("raftExpansion", raftExpansion)
    .put("raftFirstLayerDensity", raftFirstLayerDensity)
    .put("raftFirstLayerExpansion", raftFirstLayerExpansion)
    .put("topSolidLayers", topSolidLayers).put("bottomSolidLayers", bottomSolidLayers)
    .put("topShellThickness", topShellThickness).put("bottomShellThickness", bottomShellThickness)
    .put("topSurfaceDensity", surfaceDensity.topPercent)
    .put("bottomSurfaceDensity", surfaceDensity.bottomPercent)
    .put("fillPattern", fillPattern)
    .put("fillMultiline", fillMultiline)
    .put("lateralLatticeAngle1", lateralInfill.firstAngle)
    .put("lateralLatticeAngle2", lateralInfill.secondAngle)
    .put("infillOverhangAngle", lateralInfill.overhangAngle)
    .put("topSurfacePattern", topSurfacePattern)
    .put("bottomSurfacePattern", bottomSurfacePattern)
    .put("internalSolidInfillPattern", internalSolidInfillPattern)
    .put("infillFirst", infillFirst)
    .put("infillWallOverlap", infillWallOverlap)
    .put("topBottomInfillWallOverlap", topBottomInfillWallOverlap)
    .put("infillCombination", infillCombination)
    .put("infillCombinationMaxLayerHeight", infillCombinationMaxLayerHeight)
    .put("infillCombinationMaxLayerHeightPercent", infillCombinationMaxLayerHeightPercent)
    .put("infillDirection", infillDirection)
    .put("solidInfillDirection", solidInfillDirection)
    .put("alignInfillDirectionToModel", alignInfillDirectionToModel)
    .put("minimumSparseInfillArea", minimumSparseInfillArea)
    .put("infillAnchor", infillAnchor)
    .put("infillAnchorPercent", infillAnchorPercent)
    .put("infillAnchorMax", infillAnchorMax)
    .put("infillAnchorMaxPercent", infillAnchorMaxPercent)
    .put("skeletonInfillDensity", skeletonInfillDensity)
    .put("skinInfillDensity", skinInfillDensity)
    .put("skinInfillDepth", skinInfillDepth)
    .put("infillLockDepth", infillLockDepth)
    .put("infillShiftStep", infillShiftStep)
    .put("symmetricInfillYAxis", symmetricInfillYAxis)
    .put("sparseInfillRotationTemplate", sparseInfillRotationTemplate)
    .put("solidInfillRotationTemplate", solidInfillRotationTemplate)
    .put("smallAreaFlowCompensation", smallAreaFlowCompensation)
    .put("smallAreaFlowCompensationModel", smallAreaFlowCompensationModel)
    .put("skinInfillLineWidth", skinInfillLineWidth)
    .put("skinInfillLineWidthPercent", skinInfillLineWidthPercent)
    .put("skeletonInfillLineWidth", skeletonInfillLineWidth)
    .put("skeletonInfillLineWidthPercent", skeletonInfillLineWidthPercent)
    .put("gapFillTarget", gapFillTarget)
    .put("filterOutGapFill", filterOutGapFill)
    .put("reduceCrossingWall", reduceCrossingWall)
    .put("maxTravelDetourDistance", maxTravelDetourDistance)
    .put("maxTravelDetourDistancePercent", maxTravelDetourDistancePercent)
    .put("reduceInfillRetraction", reduceInfillRetraction)
    .put("travelSpeed", travelSpeed)
    .put("travelSpeedZ", travelSpeedZ)
    .put("firstLayerSpeed", firstLayerSpeed).put("supportType", normalizedSupportType(supportType))
    .put("supportAngle", supportAngle).put("skirtLoops", skirtLoops)
    .put("supportInterfaceTopLayers", supportInterfaceTopLayers)
    .put("supportInterfaceBottomLayers", supportInterfaceBottomLayers)
    .put("supportInterfaceSpacing", supportInterfaceSpacing)
    .put("supportBottomInterfaceSpacing", supportBottomInterfaceSpacing)
    .put("supportTopZDistance", supportTopZDistance)
    .put("supportBottomZDistance", supportBottomZDistance)
    .put("supportObjectXYDistance", supportObjectXYDistance)
    .put("supportBasePattern", supportBasePattern)
    .put("supportInterfacePattern", supportInterfacePattern)
    .put("supportStyle", normalizedSupportStyle(supportType, supportStyle))
    .put("supportOnBuildPlateOnly", supportCoverage.onBuildPlateOnly)
    .put("supportCriticalRegionsOnly", supportCoverage.criticalRegionsOnly)
    .put("supportRemoveSmallOverhangs", supportCoverage.removeSmallOverhangs)
    .put("supportPatternAngle", supportAdvanced.patternAngle)
    .put("supportThresholdOverlap", supportAdvanced.thresholdOverlap)
    .put("supportThresholdOverlapPercent", supportAdvanced.thresholdOverlapPercent)
    .put("supportObjectFirstLayerGap", supportAdvanced.objectFirstLayerGap)
    .put("avoidSupportInterfaceFilamentForBase", supportAdvanced.avoidInterfaceFilamentForBase)
    .put("supportIroning", supportAdvanced.ironingEnabled)
    .put("supportIroningPattern", supportAdvanced.ironingPattern)
    .put("supportIroningFlow", supportAdvanced.ironingFlow)
    .put("supportIroningSpacing", supportAdvanced.ironingSpacing)
    .put("supportBasePatternSpacing", supportBasePatternSpacing)
    .put("supportExpansion", supportExpansion)
    .put("supportInterfaceLoopPattern", supportInterfaceLoopPattern)
    .put("independentSupportLayerHeight", independentSupportLayerHeight)
    .put("treeSupportBranchAngle", treeSupportBranchAngle)
    .put("treeSupportBranchDistance", treeSupportBranchDistance)
    .put("treeSupportBranchDiameter", treeSupportBranchDiameter)
    .put("treeSupportWallCount", treeSupportWallCount)
    .put("treeSupportTipDiameter", treeSupportTipDiameter)
    .put("treeSupportPreferredBranchAngle", treeSupportPreferredBranchAngle)
    .put("treeSupportBranchDensity", treeSupportBranchDensity)
    .put("treeSupportOrganicBranchAngle", treeSupportOrganicBranchAngle)
    .put("treeSupportOrganicBranchDistance", treeSupportOrganicBranchDistance)
    .put("treeSupportOrganicBranchDiameter", treeSupportOrganicBranchDiameter)
    .put("treeSupportBranchDiameterAngle", treeSupportBranchDiameterAngle)
    .put("treeSupportAdaptiveLayerHeight", treeSupportAdaptiveLayerHeight)
    .put("treeSupportAutoBrim", treeSupportAutoBrim)
    .put("treeSupportBrimWidth", treeSupportBrimWidth)
    .put("supportFilament", supportFilament)
    .put("supportInterfaceFilament", supportInterfaceFilament)
    .put("infillFilamentOverrideEnabled", featureFilaments.infillOverrideEnabled)
    .put("infillFilamentBaseFirstLayers", featureFilaments.baseFirstLayers)
    .put("infillFilamentBaseLastLayers", featureFilaments.baseLastLayers)
    .put("sparseInfillFilament", featureFilaments.sparseInfillFilament)
    .put("wallFilament", featureFilaments.wallFilament)
    .put("solidInfillFilament", featureFilaments.solidInfillFilament)
    .put("wipeTowerFilament", featureFilaments.wipeTowerFilament)
    .put("wipeTowerEnabled", wipeTowerEnabled)
    .put("wipeTowerWidth", wipeTowerWidth)
    .put("primeVolume", multiMaterial.primeVolume)
    .put("purgeVolumes", JSONArray(multiMaterial.purgeVolumes))
    .put("primeTowerBrimWidth", multiMaterial.primeTowerBrimWidth)
    .put("primeTowerFramework", multiMaterial.primeTowerFramework)
    .put("primeTowerSkipPoints", multiMaterial.primeTowerSkipPoints)
    .put("primeTowerFlatIroning", multiMaterial.primeTowerFlatIroning)
    .put("primeTowerInterfaceFeatures", multiMaterial.primeTowerInterfaceFeatures)
    .put("primeTowerInterfaceCooldown", multiMaterial.primeTowerInterfaceCooldown)
    .put("primeTowerInfillGap", multiMaterial.primeTowerInfillGap)
    .put("wipeTowerNoSparseLayers", multiMaterial.wipeTowerNoSparseLayers)
    .put("wipeTowerRotationAngle", multiMaterial.wipeTowerRotationAngle)
    .put("wipeTowerBridging", multiMaterial.wipeTowerBridging)
    .put("wipeTowerExtraSpacing", multiMaterial.wipeTowerExtraSpacing)
    .put("wipeTowerExtraFlow", multiMaterial.wipeTowerExtraFlow)
    .put("wipeTowerMaxPurgeSpeed", multiMaterial.wipeTowerMaxPurgeSpeed)
    .put("wipeTowerWallType", multiMaterial.wipeTowerWallType)
    .put("wipeTowerConeAngle", multiMaterial.wipeTowerConeAngle)
    .put("wipeTowerExtraRibLength", multiMaterial.wipeTowerExtraRibLength)
    .put("wipeTowerRibWidth", multiMaterial.wipeTowerRibWidth)
    .put("wipeTowerFilletWall", multiMaterial.wipeTowerFilletWall)
    .put("singleExtruderMultiMaterialPriming", multiMaterial.singleExtruderMultiMaterialPriming)
    .put("flushIntoInfill", multiMaterial.flushIntoInfill)
    .put("flushIntoSupport", multiMaterial.flushIntoSupport)
    .put("flushIntoObjects", multiMaterial.flushIntoObjects)
    .put("oozePrevention", multiMaterial.oozePrevention)
    .put("standbyTemperatureDelta", multiMaterial.standbyTemperatureDelta)
    .put("preheatTime", multiMaterial.preheatTime)
    .put("preheatDeltaTemperature", multiMaterial.preheatDeltaTemperature)
    .put("preheatSteps", multiMaterial.preheatSteps)
    .put("interfaceShells", multiMaterial.interfaceShells)
    .put("segmentedRegionMaxWidth", multiMaterial.segmentedRegionMaxWidth)
    .put("segmentedRegionInterlockingDepth", multiMaterial.segmentedRegionInterlockingDepth)
    .put("interlockingBeam", multiMaterial.interlockingBeam)
    .put("interlockingBeamWidth", multiMaterial.interlockingBeamWidth)
    .put("interlockingOrientation", multiMaterial.interlockingOrientation)
    .put("interlockingBeamLayerCount", multiMaterial.interlockingBeamLayerCount)
    .put("interlockingDepth", multiMaterial.interlockingDepth)
    .put("interlockingBoundaryAvoidance", multiMaterial.interlockingBoundaryAvoidance)
    .put("enableArcFitting", gcodeSettings.arcFitting)
    .put("gcodeLabelObjects", gcodeSettings.labelObjects)
    .put("excludeObject", gcodeSettings.excludeObjects)
    .put("gcodeComments", gcodeSettings.verboseComments)
    .put("timelapseType", gcodeSettings.timelapseType)
    .put("initialLayerTravelSpeed", gcodeSettings.initialLayerTravelSpeed)
    .put("initialLayerTravelSpeedPercent", gcodeSettings.initialLayerTravelSpeedPercent)
    .put("slowDownLayers", gcodeSettings.slowDownLayers)
    .put("accelToDecelEnabled", gcodeSettings.accelToDecelEnabled)
    .put("accelToDecelFactor", gcodeSettings.accelToDecelFactor)
    .put("filenameFormat", gcodeSettings.filenameFormat)
    .put("skirtType", skirtType)
    .put("skirtHeight", skirtHeight)
    .put("skirtSpeed", skirtSpeed)
    .put("minimumSkirtLength", minimumSkirtLength)
    .put("draftShield", draftShield)
    .put("singleLoopDraftShield", singleLoopDraftShield)
    .put("skirtDistance", skirtDistance)
    .put("skirtStartAngle", skirtStartAngle)
    .put("outerWallLineWidth", outerWallLineWidth)
    .put("innerWallLineWidth", innerWallLineWidth)
    .put("topSurfaceLineWidth", topSurfaceLineWidth)
    .put("sparseInfillLineWidth", sparseInfillLineWidth)
    .put("internalSolidInfillLineWidth", internalSolidInfillLineWidth)
    .put("supportLineWidth", supportLineWidth)
    .put("initialLayerLineWidth", initialLayerLineWidth)
    .put("smallPerimeterSpeed", smallPerimeterSpeed)
    .put("smallPerimeterSpeedPercent", smallPerimeterSpeedPercent)
    .put("smallPerimeterThreshold", smallPerimeterThreshold)
    .put("slowdownForCurledPerimeters", slowdownForCurledPerimeters)
    .put("resolution", resolution)
    .put("slicingMode", precision.mode)
    .put("sliceClosingRadius", precision.closingRadius)
    .put("preciseZHeight", precision.preciseZHeight)
    .put("holeToPolyhole", precision.polyholes.enabled)
    .put("holeToPolyholeThreshold", precision.polyholes.detectionMargin)
    .put("holeToPolyholeThresholdPercent", precision.polyholes.detectionMarginPercent)
    .put("holeToPolyholeTwisted", precision.polyholes.twist)
    .put("seamPosition", seamPosition)
    .put("staggeredInnerSeams", staggeredInnerSeams)
    .put("seamGap", seamGap)
    .put("seamGapPercent", seamGapPercent)
    .put("scarfSeamType", scarfSeam.type)
    .put("scarfSeamConditional", scarfSeam.conditional)
    .put("scarfAngleThreshold", scarfSeam.angleThreshold)
    .put("scarfOverhangThreshold", scarfSeam.overhangThreshold)
    .put("scarfJointSpeed", scarfSeam.speed)
    .put("scarfJointSpeedPercent", scarfSeam.speedPercent)
    .put("scarfJointFlowRatio", scarfSeam.flowRatio)
    .put("scarfStartHeight", scarfSeam.startHeight)
    .put("scarfStartHeightPercent", scarfSeam.startHeightPercent)
    .put("scarfEntireLoop", scarfSeam.entireLoop)
    .put("scarfLength", scarfSeam.length)
    .put("scarfSteps", scarfSeam.steps)
    .put("scarfInnerWalls", scarfSeam.innerWalls)
    .put("wipeBeforeExternalLoop", wipeBeforeExternalLoop)
    .put("wipeOnLoops", wipeOnLoops)
    .put("roleBasedWipeSpeed", roleBasedWipeSpeed)
    .put("wipeSpeed", wipeSpeed)
    .put("wipeSpeedPercent", wipeSpeedPercent)
    .put("ironingType", ironing.type)
    .put("ironingPattern", ironing.pattern)
    .put("ironingFlow", ironing.flow)
    .put("ironingSpacing", ironing.spacing)
    .put("ironingInset", ironing.inset)
    .put("ironingSpeed", ironing.speed)
    .put("ironingAngle", ironing.angle)
    .put("wallGenerator", wallGenerator)
    .put("wallTransitionLength", wallTransitionLength)
    .put("wallTransitionFilterDeviation", wallTransitionFilterDeviation)
    .put("wallTransitionAngle", wallTransitionAngle)
    .put("wallDistributionCount", wallDistributionCount)
    .put("minimumFeatureSize", minimumFeatureSize)
    .put("minimumWallWidth", precision.minimumWallWidth)
    .put("firstLayerMinimumWallWidth", precision.firstLayerMinimumWallWidth)
    .put("minimumWallLengthFactor", minimumWallLengthFactor)
    .put("wallSequence", wallSequence)
    .put("wallDirection", wallDirection)
    .put("detectThinWalls", detectThinWalls)
    .put("detectOverhangWalls", detectOverhangWalls)
    .put("makeOverhangPrintable", printableOverhangs.enabled)
    .put("makeOverhangPrintableAngle", printableOverhangs.maximumAngle)
    .put("makeOverhangPrintableHoleSize", printableOverhangs.holeArea)
    .put("onlyOneWallOnTop", onlyOneWallOnTop)
    .put("minWidthTopSurface", minWidthTopSurface)
    .put("minWidthTopSurfacePercent", minWidthTopSurfacePercent)
    .put("onlyOneWallFirstLayer", onlyOneWallFirstLayer)
    .put("extraPerimetersOnOverhangs", extraPerimetersOnOverhangs)
    .put("overhangReverse", overhangReverse)
    .put("overhangReverseInternalOnly", overhangReverseInternalOnly)
    .put("overhangReverseThreshold", overhangReverseThreshold)
    .put("overhangReverseThresholdPercent", overhangReverseThresholdPercent)
    .put("counterboreHoleBridging", counterboreHoleBridging)
    .put("alternateExtraWall", alternateExtraWall)
    .put("ensureVerticalShellThickness", ensureVerticalShellThickness)
    .put("detectNarrowInternalSolidInfill", detectNarrowInternalSolidInfill)
    .put("xyHoleCompensation", xyHoleCompensation)
    .put("xyContourCompensation", xyContourCompensation)
    .put("elephantFootCompensation", elephantFootCompensation)
    .put("elephantFootCompensationLayers", elephantFootCompensationLayers)
    .put("maxBridgeLength", maxBridgeLength)
    .put("preciseOuterWalls", preciseOuterWalls)
    .put("printSequence", printSequence)
    .put("printOrder", printOrder)
    .put("spiralMode", spiralMode)
    .put("spiralModeSmooth", spiralModeSmooth)
    .put("spiralModeMaxXySmoothing", spiralModeMaxXySmoothing)
    .put("spiralModeMaxXySmoothingPercent", spiralModeMaxXySmoothingPercent)
    .put("spiralStartingFlowRatio", spiralStartingFlowRatio)
    .put("spiralFinishingFlowRatio", spiralFinishingFlowRatio)
    .put("builtIn", builtIn)
    .put("brand", brand ?: JSONObject.NULL)
    .put("compatiblePrinters", JSONArray(compatiblePrinters))

internal fun JSONObject.toPrinterProfileOrNull(): PrinterProfile? = runCatching {
    val bedSizeX = getDouble("bedSizeX").toFloat()
    val bedSizeY = getDouble("bedSizeY").toFloat()
    val nozzleDiameter = getDouble("nozzleDiameter").toFloat()
    val retractLength = optDouble("retractLength", 0.8).toFloat()
    val extruderCount = optInt("extruderCount", 1)
    PrinterProfile(
        getString("id"), getString("name"),
        bedSizeX, bedSizeY,
        getDouble("maxPrintHeight").toFloat(), nozzleDiameter,
        minLayerHeight = optDouble("minLayerHeight", 0.04).toFloat(),
        maxLayerHeight = optDouble("maxLayerHeight", nozzleDiameter * 0.7).toFloat(),
        builtIn = optBoolean("builtIn"),
        brand = optionalString("brand"),
        machineStartGcode = optString("machineStartGcode"),
        machineEndGcode = optString("machineEndGcode"),
        machinePauseGcode = optString("machinePauseGcode"),
        timeLapseGcode = optString("timeLapseGcode"),
        beforeLayerChangeGcode = optString("beforeLayerChangeGcode"),
        layerChangeGcode = optString("layerChangeGcode"),
        changeFilamentGcode = optString("changeFilamentGcode"),
        printingByObjectGcode = optString("printingByObjectGcode"),
        useRelativeEDistances = optBoolean("useRelativeEDistances", true),
        emitMachineLimitsToGcode = optBoolean("emitMachineLimitsToGcode", true),
        manualFilamentChange = optBoolean("manualFilamentChange"),
        disableM73 = optBoolean("disableM73"),
        machineLoadFilamentTime = optDouble("machineLoadFilamentTime", 0.0).toFloat(),
        machineUnloadFilamentTime = optDouble("machineUnloadFilamentTime", 0.0).toFloat(),
        machineToolChangeTime = optDouble("machineToolChangeTime", 0.0).toFloat(),
        toolChangeTemperatureWait = optBoolean("toolChangeTemperatureWait", true),
        gcodeFlavor = optString("gcodeFlavor", "marlin"),
        maxSpeedX = optDouble("maxSpeedX", 500.0).toFloat(),
        maxSpeedY = optDouble("maxSpeedY", 500.0).toFloat(),
        maxSpeedZ = optDouble("maxSpeedZ", 20.0).toFloat(),
        maxSpeedE = optDouble("maxSpeedE", 30.0).toFloat(),
        maxAccelerationX = optDouble("maxAccelerationX", 20_000.0).toFloat(),
        maxAccelerationY = optDouble("maxAccelerationY", 20_000.0).toFloat(),
        maxAccelerationZ = optDouble("maxAccelerationZ", 500.0).toFloat(),
        maxAccelerationE = optDouble("maxAccelerationE", 5_000.0).toFloat(),
        maxAccelerationExtruding = optDouble("maxAccelerationExtruding", 20_000.0).toFloat(),
        maxAccelerationRetracting = optDouble("maxAccelerationRetracting", 5_000.0).toFloat(),
        maxAccelerationTravel = optDouble("maxAccelerationTravel", 20_000.0).toFloat(),
        maxJerkX = optDouble("maxJerkX", 9.0).toFloat(),
        maxJerkY = optDouble("maxJerkY", 9.0).toFloat(),
        maxJerkZ = optDouble("maxJerkZ", 3.0).toFloat(),
        maxJerkE = optDouble("maxJerkE", 2.5).toFloat(),
        retractLength = retractLength,
        retractSpeed = optDouble("retractSpeed", 45.0).toFloat(),
        deretractSpeed = optDouble("deretractSpeed", 35.0).toFloat(),
        retractionMinimumTravel = optDouble("retractionMinimumTravel", 1.0).toFloat(),
        retractWhenChangingLayer = optBoolean("retractWhenChangingLayer"),
        wipeWhileRetracting = optBoolean("wipeWhileRetracting"),
        wipeDistance = optDouble("wipeDistance", 1.0).toFloat(),
        retractBeforeWipe = optDouble("retractBeforeWipe", 100.0).toFloat(),
        retractRestartExtra = optDouble("retractRestartExtra", 0.0).toFloat(),
        extruderOffsetsX = extruderFloatList("extruderOffsetsX") ?: listOf(0f),
        extruderOffsetsY = extruderFloatList("extruderOffsetsY") ?: listOf(0f),
        toolChangeRetractLengths = extruderFloatList("toolChangeRetractLengths")
            ?: listOf(retractLength),
        toolChangeRetractRestartExtras = extruderFloatList("toolChangeRetractRestartExtras")
            ?: listOf(0f),
        zHop = optDouble("zHop", 0.4).toFloat(),
        zHopType = optString("zHopType", "slope"),
        retractLiftAbove = optDouble("retractLiftAbove", 0.0).toFloat(),
        retractLiftBelow = optDouble("retractLiftBelow", 0.0).toFloat(),
        retractLiftEnforce = optString("retractLiftEnforce", "all"),
        travelSlope = optDouble("travelSlope", 3.0).toFloat(),
        zHopWhenPrime = optBoolean("zHopWhenPrime", true),
        useFirmwareRetraction = optBoolean("useFirmwareRetraction"),
        longRetractionWhenCutLevel = optInt("longRetractionWhenCutLevel", 0),
        longRetractionWhenCut = optBoolean("longRetractionWhenCut"),
        retractionDistanceWhenCut = optDouble("retractionDistanceWhenCut", 18.0).toFloat(),
        extruderClearanceRadius = optDouble("extruderClearanceRadius", 40.0).toFloat(),
        extruderClearanceHeightToRod = optDouble("extruderClearanceHeightToRod", 40.0).toFloat(),
        extruderClearanceHeightToLid = optDouble("extruderClearanceHeightToLid", 120.0).toFloat(),
        bedOriginX = optDouble("bedOriginX", 0.0).toFloat(),
        bedOriginY = optDouble("bedOriginY", 0.0).toFloat(),
        bedPolygon = if (has("bedPolygon")) {
            requireNotNull(floatList("bedPolygon")) { "Invalid bed polygon" }
        } else {
            rectangularBedPolygon(bedSizeX, bedSizeY)
        },
        bedExcludeArea = if (has("bedExcludeArea")) {
            requireNotNull(bedExcludeFloatList("bedExcludeArea")) {
                "Invalid bed exclusion geometry"
            }
        } else {
            listOf(0f, 0f)
        },
        singleExtruderMultiMaterial = optBoolean("singleExtruderMultiMaterial"),
        coolingTubeRetraction = optDouble("coolingTubeRetraction", 91.5).toFloat(),
        coolingTubeLength = optDouble("coolingTubeLength", 5.0).toFloat(),
        parkingPosRetraction = optDouble("parkingPosRetraction", 92.0).toFloat(),
        extraLoadingMove = optDouble("extraLoadingMove", -2.0).toFloat(),
        enableFilamentRamming = optBoolean("enableFilamentRamming", true),
        purgeInPrimeTower = optBoolean("purgeInPrimeTower", true),
        highCurrentOnFilamentSwap = optBoolean("highCurrentOnFilamentSwap"),
        extruderCount = extruderCount,
        auxiliaryFan = optBoolean("auxiliaryFan", false),
        fanSpeedupTime = optDouble("fanSpeedupTime", 0.0).toFloat(),
        fanSpeedupOverhangs = optBoolean("fanSpeedupOverhangs", true),
        fanKickstart = optDouble("fanKickstart", 0.0).toFloat(),
        supportsChamberTemperatureControl = optBoolean("supportsChamberTemperatureControl"),
        supportsAirFiltration = optBoolean("supportsAirFiltration"),
        defaultPrintProfile = optString("defaultPrintProfile", ""),
        defaultFilamentProfiles = stringList("defaultFilamentProfiles"),
    )
}.getOrNull()

internal fun JSONObject.toFilamentProfileOrNull(): FilamentProfile? = runCatching {
    val bedTemp = getInt("bedTemp")
    val firstLayerBedTemp = optInt("firstLayerBedTemp", bedTemp)
    FilamentProfile(
        getString("id"), getString("name"), getString("nativeName"),
        getInt("nozzleTemp"), optInt("firstLayerNozzleTemp", getInt("nozzleTemp")),
        bedTemp, firstLayerBedTemp,
        getDouble("flowRatio").toFloat(), getDouble("maxVolumetricSpeed").toFloat(),
        idleTemperature = optInt("idleTemperature", 0),
        builtIn = optBoolean("builtIn"),
        brand = optionalString("brand"),
        texturedPlateTemp = optInt("texturedPlateTemp", bedTemp),
        firstLayerTexturedPlateTemp = optInt("firstLayerTexturedPlateTemp", firstLayerBedTemp),
        engineeringPlateTemp = optInt("engineeringPlateTemp", bedTemp),
        firstLayerEngineeringPlateTemp = optInt("firstLayerEngineeringPlateTemp", firstLayerBedTemp),
        coolPlateTemp = optInt("coolPlateTemp", bedTemp),
        firstLayerCoolPlateTemp = optInt("firstLayerCoolPlateTemp", firstLayerBedTemp),
        texturedCoolPlateTemp = optInt("texturedCoolPlateTemp", bedTemp),
        firstLayerTexturedCoolPlateTemp = optInt("firstLayerTexturedCoolPlateTemp", firstLayerBedTemp),
        superTackPlateTemp = optInt("superTackPlateTemp", bedTemp),
        firstLayerSuperTackPlateTemp = optInt("firstLayerSuperTackPlateTemp", firstLayerBedTemp),
        graphicEffectPlateTemp = optInt("graphicEffectPlateTemp", bedTemp),
        firstLayerGraphicEffectPlateTemp = optInt("firstLayerGraphicEffectPlateTemp", firstLayerBedTemp),
        filamentStartGcode = optString("filamentStartGcode"),
        filamentEndGcode = optString("filamentEndGcode"),
        retractLength = nullableFloat("retractLength"),
        retractSpeed = nullableFloat("retractSpeed"),
        deretractSpeed = nullableFloat("deretractSpeed"),
        retractionMinimumTravel = nullableFloat("retractionMinimumTravel"),
        retractWhenChangingLayer = nullableBoolean("retractWhenChangingLayer"),
        wipeWhileRetracting = nullableBoolean("wipeWhileRetracting"),
        wipeDistance = nullableFloat("wipeDistance"),
        retractBeforeWipe = nullableFloat("retractBeforeWipe"),
        retractRestartExtra = nullableFloat("retractRestartExtra"),
        zHop = nullableFloat("zHop"),
        zHopType = nullableString("zHopType"),
        retractLiftAbove = nullableFloat("retractLiftAbove"),
        retractLiftBelow = nullableFloat("retractLiftBelow"),
        retractLiftEnforce = nullableString("retractLiftEnforce"),
        longRetractionWhenCut = nullableBoolean("longRetractionWhenCut"),
        retractionDistanceWhenCut = nullableFloat("retractionDistanceWhenCut"),
        fanMinSpeed = optInt("fanMinSpeed", 30),
        fanMaxSpeed = optInt("fanMaxSpeed", 100),
        fanCoolingLayerTime = optDouble("fanCoolingLayerTime", 60.0).toFloat(),
        slowDownForLayerCooling = optBoolean("slowDownForLayerCooling", true),
        keepFanAlwaysOn = optBoolean("keepFanAlwaysOn", false),
        dontSlowDownOuterWall = optBoolean("dontSlowDownOuterWall", false),
        enableOverhangBridgeFan = optBoolean("enableOverhangBridgeFan", true),
        overhangFanSpeed = optInt("overhangFanSpeed", 100),
        overhangFanThreshold = optString("overhangFanThreshold", "95%"),
        internalBridgeFanSpeed = optInt("internalBridgeFanSpeed", -1),
        supportInterfaceFanSpeed = optInt("supportInterfaceFanSpeed", -1),
        slowDownLayerTime = optDouble("slowDownLayerTime", 8.0).toFloat(),
        slowDownMinSpeed = optDouble("slowDownMinSpeed", 10.0).toFloat(),
        closeFanFirstLayers = optInt("closeFanFirstLayers", 1),
        fullFanSpeedLayer = optInt("fullFanSpeedLayer", 3),
        pressureAdvanceEnabled = optBoolean("pressureAdvanceEnabled"),
        pressureAdvance = optDouble("pressureAdvance", 0.0).toFloat(),
        compatiblePrinters = stringList("compatiblePrinters"),
        diameter = optDouble("diameter", 1.75).toFloat(),
        density = optDouble("density", 1.24).toFloat(),
        costPerKilogram = optDouble("costPerKilogram", 0.0).toFloat(),
        shrinkageXyPercent = optDouble("shrinkageXyPercent", 100.0).toFloat(),
        shrinkageZPercent = optDouble("shrinkageZPercent", 100.0).toFloat(),
        soluble = optBoolean("soluble", false),
        supportMaterial = optBoolean("supportMaterial", false),
        minimalPurgeOnWipeTower = optDouble("minimalPurgeOnWipeTower", 15.0).toFloat(),
        towerInterfacePreExtrusionDistance = optDouble(
            "towerInterfacePreExtrusionDistance",
            10.0,
        ).toFloat(),
        towerInterfacePreExtrusionLength = optDouble(
            "towerInterfacePreExtrusionLength",
            0.0,
        ).toFloat(),
        towerIroningArea = optDouble("towerIroningArea", 4.0).toFloat(),
        towerInterfacePurgeLength = optDouble("towerInterfacePurgeLength", 20.0).toFloat(),
        towerInterfacePrintTemperature = optInt("towerInterfacePrintTemperature", -1),
        additionalCoolingFanSpeed = optInt("additionalCoolingFanSpeed", 0),
        loadingSpeed = optDouble("loadingSpeed", 28.0).toFloat(),
        loadingSpeedStart = optDouble("loadingSpeedStart", 3.0).toFloat(),
        unloadingSpeed = optDouble("unloadingSpeed", 90.0).toFloat(),
        unloadingSpeedStart = optDouble("unloadingSpeedStart", 100.0).toFloat(),
        toolchangeDelay = optDouble("toolchangeDelay", 0.0).toFloat(),
        coolingMoves = optInt("coolingMoves", 4),
        stampingLoadingSpeed = optDouble("stampingLoadingSpeed", 0.0).toFloat(),
        stampingDistance = optDouble("stampingDistance", 0.0).toFloat(),
        coolingInitialSpeed = optDouble("coolingInitialSpeed", 2.2).toFloat(),
        coolingFinalSpeed = optDouble("coolingFinalSpeed", 3.4).toFloat(),
        rammingParameters = optString("rammingParameters", DEFAULT_FILAMENT_RAMMING_PARAMETERS),
        multitoolRamming = optBoolean("multitoolRamming", false),
        multitoolRammingVolume = optDouble("multitoolRammingVolume", 10.0).toFloat(),
        multitoolRammingFlow = optDouble("multitoolRammingFlow", 10.0).toFloat(),
        softeningTemperature = optInt("softeningTemperature", 100),
        nozzleTemperatureRangeLow = optInt("nozzleTemperatureRangeLow", 190),
        nozzleTemperatureRangeHigh = optInt("nozzleTemperatureRangeHigh", 240),
        chamberTemperatureControl = optBoolean("chamberTemperatureControl"),
        chamberTemperature = optInt("chamberTemperature", 0),
        airFiltration = optBoolean("airFiltration"),
        duringPrintExhaustFanSpeed = optInt("duringPrintExhaustFanSpeed", 60),
        completePrintExhaustFanSpeed = optInt("completePrintExhaustFanSpeed", 80),
    )
}.getOrNull()

internal fun JSONObject.toQualityProfileOrNull(): QualityProfile? = runCatching {
    QualityProfile(
        getString("id"), getString("name"),
        getDouble("layerHeightMm").toFloat(), getDouble("firstLayerHeightMm").toFloat(),
        getInt("perimeters"), getDouble("fillDensity").toFloat(),
        getDouble("printSpeed").toFloat(), optDouble("nozzleDiameter", 0.4).toFloat(),
        innerWallSpeed = optDouble("innerWallSpeed", 0.0).toFloat(),
        sparseInfillSpeed = optDouble("sparseInfillSpeed", 0.0).toFloat(),
        internalSolidInfillSpeed = optDouble("internalSolidInfillSpeed", 0.0).toFloat(),
        topSurfaceSpeed = optDouble("topSurfaceSpeed", 0.0).toFloat(),
        supportSpeed = optDouble("supportSpeed", 0.0).toFloat(),
        bridgeSpeed = optDouble("bridgeSpeed", 0.0).toFloat(),
        gapInfillSpeed = optDouble("gapInfillSpeed", 0.0).toFloat(),
        firstLayerInfillSpeed = optDouble("firstLayerInfillSpeed", 0.0).toFloat(),
        supportInterfaceSpeed = optDouble("supportInterfaceSpeed", 0.0).toFloat(),
        internalBridgeSpeed = optDouble("internalBridgeSpeed", 150.0).toFloat(),
        internalBridgeSpeedPercent = optBoolean("internalBridgeSpeedPercent", true),
        overhangSpeedEnabled = optBoolean("overhangSpeedEnabled", true),
        overhangSpeed1 = optDouble("overhangSpeed1", 55.0).toFloat(),
        overhangSpeed1Percent = optBoolean("overhangSpeed1Percent"),
        overhangSpeed2 = optDouble("overhangSpeed2", 30.0).toFloat(),
        overhangSpeed2Percent = optBoolean("overhangSpeed2Percent"),
        overhangSpeed3 = optDouble("overhangSpeed3", 10.0).toFloat(),
        overhangSpeed3Percent = optBoolean("overhangSpeed3Percent"),
        overhangSpeed4 = optDouble("overhangSpeed4", 10.0).toFloat(),
        overhangSpeed4Percent = optBoolean("overhangSpeed4Percent"),
        printFlowRatio = optDouble("printFlowRatio", 1.0).toFloat(),
        bridgeFlowRatio = optDouble("bridgeFlowRatio", 1.0).toFloat(),
        internalBridgeFlowRatio = optDouble("internalBridgeFlowRatio", 1.0).toFloat(),
        topSurfaceFlowRatio = optDouble("topSurfaceFlowRatio", 1.0).toFloat(),
        bottomSurfaceFlowRatio = optDouble("bottomSurfaceFlowRatio", 1.0).toFloat(),
        bridgeDensity = optDouble("bridgeDensity", 100.0).toFloat(),
        internalBridgeDensity = optDouble("internalBridgeDensity", 100.0).toFloat(),
        bridgeAngle = optDouble("bridgeAngle", 0.0).toFloat(),
        internalBridgeAngle = optDouble("internalBridgeAngle", 0.0).toFloat(),
        bridgeNoSupport = optBoolean("bridgeNoSupport"),
        thickBridges = optBoolean("thickBridges"),
        thickInternalBridges = optBoolean("thickInternalBridges", true),
        extraBridgeLayer = optString("extraBridgeLayer", "disabled"),
        internalBridgeFilter = optString("internalBridgeFilter", "disabled"),
        defaultAcceleration = optDouble("defaultAcceleration", 0.0).toFloat(),
        outerWallAcceleration = optDouble("outerWallAcceleration", 0.0).toFloat(),
        innerWallAcceleration = optDouble("innerWallAcceleration", 0.0).toFloat(),
        topSurfaceAcceleration = optDouble("topSurfaceAcceleration", 0.0).toFloat(),
        travelAcceleration = optDouble("travelAcceleration", 0.0).toFloat(),
        firstLayerAcceleration = optDouble("firstLayerAcceleration", 0.0).toFloat(),
        bridgeAcceleration = optDouble("bridgeAcceleration", 50.0).toFloat(),
        bridgeAccelerationPercent = optBoolean("bridgeAccelerationPercent", true),
        sparseInfillAcceleration = optDouble("sparseInfillAcceleration", 100.0).toFloat(),
        sparseInfillAccelerationPercent = optBoolean("sparseInfillAccelerationPercent", true),
        internalSolidInfillAcceleration = optDouble("internalSolidInfillAcceleration", 100.0).toFloat(),
        internalSolidInfillAccelerationPercent = optBoolean("internalSolidInfillAccelerationPercent", true),
        defaultJerk = optDouble("defaultJerk", 0.0).toFloat(),
        outerWallJerk = optDouble("outerWallJerk", 9.0).toFloat(),
        innerWallJerk = optDouble("innerWallJerk", 9.0).toFloat(),
        topSurfaceJerk = optDouble("topSurfaceJerk", 9.0).toFloat(),
        infillJerk = optDouble("infillJerk", 9.0).toFloat(),
        firstLayerJerk = optDouble("firstLayerJerk", 9.0).toFloat(),
        travelJerk = optDouble("travelJerk", 12.0).toFloat(),
        extrusionRateSmoothing = ExtrusionRateSmoothingSettings(
            maximumSlope = optDouble("maxVolumetricExtrusionRateSlope", 0.0).toFloat(),
            segmentLength = optDouble("maxVolumetricExtrusionRateSlopeSegmentLength", 3.0).toFloat(),
            externalOnly = optBoolean("extrusionRateSmoothingExternalOnly"),
        ),
        fuzzySkin = FuzzySkinSettings(
            type = optString("fuzzySkinType", "none"),
            firstLayer = optBoolean("fuzzySkinFirstLayer"),
            pointDistance = optDouble("fuzzySkinPointDistance", 0.3).toFloat(),
            thickness = optDouble("fuzzySkinThickness", 0.2).toFloat(),
            mode = optString("fuzzySkinMode", "displacement"),
            noiseType = optString("fuzzySkinNoiseType", "classic"),
            scale = optDouble("fuzzySkinScale", 1.0).toFloat(),
            octaves = optInt("fuzzySkinOctaves", 4),
            persistence = optDouble("fuzzySkinPersistence", 0.5).toFloat(),
        ),
        supportEnabled = optBoolean("supportEnabled"),
        brimType = optString("brimType", "no_brim"),
        brimWidth = optDouble("brimWidth", 0.0).toFloat(),
        brimObjectGap = optDouble("brimObjectGap", 0.0).toFloat(),
        raftLayers = optInt("raftLayers", 0),
        raftContactDistance = optDouble("raftContactDistance", 0.1).toFloat(),
        raftExpansion = optDouble("raftExpansion", 1.5).toFloat(),
        raftFirstLayerDensity = optDouble("raftFirstLayerDensity", 90.0).toFloat(),
        raftFirstLayerExpansion = optDouble("raftFirstLayerExpansion", 2.0).toFloat(),
        builtIn = optBoolean("builtIn"),
        topSolidLayers = optInt("topSolidLayers", 5),
        bottomSolidLayers = optInt("bottomSolidLayers", 4),
        topShellThickness = optDouble("topShellThickness", 0.0).toFloat(),
        bottomShellThickness = optDouble("bottomShellThickness", 0.0).toFloat(),
        surfaceDensity = SurfaceDensitySettings(
            topPercent = optDouble("topSurfaceDensity", 100.0).toFloat(),
            bottomPercent = optDouble("bottomSurfaceDensity", 100.0).toFloat(),
        ),
        fillPattern = optString("fillPattern", "gyroid"),
        fillMultiline = optInt("fillMultiline", 1),
        lateralInfill = LateralInfillSettings(
            firstAngle = optDouble("lateralLatticeAngle1", -45.0).toFloat(),
            secondAngle = optDouble("lateralLatticeAngle2", 45.0).toFloat(),
            overhangAngle = optDouble("infillOverhangAngle", 60.0).toFloat(),
        ),
        topSurfacePattern = optString("topSurfacePattern", "monotonicline"),
        bottomSurfacePattern = optString("bottomSurfacePattern", "monotonic"),
        internalSolidInfillPattern = optString("internalSolidInfillPattern", "monotonic"),
        infillFirst = optBoolean("infillFirst"),
        infillWallOverlap = optDouble("infillWallOverlap", 15.0).toFloat(),
        topBottomInfillWallOverlap = optDouble("topBottomInfillWallOverlap", 25.0).toFloat(),
        infillCombination = optBoolean("infillCombination"),
        infillCombinationMaxLayerHeight = optDouble("infillCombinationMaxLayerHeight", 100.0).toFloat(),
        infillCombinationMaxLayerHeightPercent = optBoolean("infillCombinationMaxLayerHeightPercent", true),
        infillDirection = optDouble("infillDirection", 45.0).toFloat(),
        solidInfillDirection = optDouble("solidInfillDirection", 45.0).toFloat(),
        alignInfillDirectionToModel = optBoolean("alignInfillDirectionToModel"),
        minimumSparseInfillArea = optDouble("minimumSparseInfillArea", 15.0).toFloat(),
        infillAnchor = optDouble("infillAnchor", 400.0).toFloat(),
        infillAnchorPercent = optBoolean("infillAnchorPercent", true),
        infillAnchorMax = optDouble("infillAnchorMax", 20.0).toFloat(),
        infillAnchorMaxPercent = optBoolean("infillAnchorMaxPercent"),
        skeletonInfillDensity = optDouble("skeletonInfillDensity", 25.0).toFloat(),
        skinInfillDensity = optDouble("skinInfillDensity", 25.0).toFloat(),
        skinInfillDepth = optDouble("skinInfillDepth", 2.0).toFloat(),
        infillLockDepth = optDouble("infillLockDepth", 1.0).toFloat(),
        infillShiftStep = optDouble("infillShiftStep", 0.4).toFloat(),
        symmetricInfillYAxis = optBoolean("symmetricInfillYAxis"),
        sparseInfillRotationTemplate = optString("sparseInfillRotationTemplate", ""),
        solidInfillRotationTemplate = optString("solidInfillRotationTemplate", ""),
        smallAreaFlowCompensation = optBoolean("smallAreaFlowCompensation"),
        smallAreaFlowCompensationModel = optString(
            "smallAreaFlowCompensationModel",
            DEFAULT_SMALL_AREA_FLOW_COMPENSATION_MODEL,
        ),
        skinInfillLineWidth = optDouble("skinInfillLineWidth", 100.0).toFloat(),
        skinInfillLineWidthPercent = optBoolean("skinInfillLineWidthPercent", true),
        skeletonInfillLineWidth = optDouble("skeletonInfillLineWidth", 100.0).toFloat(),
        skeletonInfillLineWidthPercent = optBoolean("skeletonInfillLineWidthPercent", true),
        gapFillTarget = optString("gapFillTarget", "nowhere"),
        filterOutGapFill = optDouble("filterOutGapFill", 0.0).toFloat(),
        reduceCrossingWall = optBoolean("reduceCrossingWall"),
        maxTravelDetourDistance = optDouble("maxTravelDetourDistance", 0.0).toFloat(),
        maxTravelDetourDistancePercent = optBoolean("maxTravelDetourDistancePercent"),
        reduceInfillRetraction = optBoolean("reduceInfillRetraction"),
        travelSpeed = optDouble("travelSpeed", 500.0).toFloat(),
        travelSpeedZ = optDouble("travelSpeedZ", 0.0).toFloat(),
        firstLayerSpeed = optDouble("firstLayerSpeed", 50.0).toFloat(),
        supportType = normalizedSupportType(optString("supportType", "normal(auto)")),
        supportAngle = optDouble("supportAngle", 45.0).toFloat(),
        supportInterfaceTopLayers = optInt("supportInterfaceTopLayers", 3),
        supportInterfaceBottomLayers = optInt("supportInterfaceBottomLayers", 0),
        supportInterfaceSpacing = optDouble("supportInterfaceSpacing", 0.5).toFloat(),
        supportBottomInterfaceSpacing = optDouble("supportBottomInterfaceSpacing", 0.5).toFloat(),
        supportTopZDistance = optDouble("supportTopZDistance", 0.2).toFloat(),
        supportBottomZDistance = optDouble("supportBottomZDistance", 0.2).toFloat(),
        supportObjectXYDistance = optDouble("supportObjectXYDistance", 0.35).toFloat(),
        supportBasePattern = optString("supportBasePattern", "default"),
        supportInterfacePattern = optString("supportInterfacePattern", "auto"),
        supportStyle = normalizedSupportStyle(
            optString("supportType", "normal(auto)"),
            optString("supportStyle", "default"),
        ),
        supportCoverage = SupportCoverageSettings(
            onBuildPlateOnly = optBoolean("supportOnBuildPlateOnly"),
            criticalRegionsOnly = optBoolean("supportCriticalRegionsOnly"),
            removeSmallOverhangs = optBoolean("supportRemoveSmallOverhangs", true),
            enforcedLayers = optInt("enforceSupportLayers", 0),
        ),
        supportAdvanced = SupportAdvancedSettings(
            patternAngle = optDouble("supportPatternAngle", 0.0).toFloat(),
            thresholdOverlap = optDouble("supportThresholdOverlap", 50.0).toFloat(),
            thresholdOverlapPercent = optBoolean("supportThresholdOverlapPercent", true),
            objectFirstLayerGap = optDouble("supportObjectFirstLayerGap", 0.2).toFloat(),
            avoidInterfaceFilamentForBase = optBoolean("avoidSupportInterfaceFilamentForBase", true),
            ironingEnabled = optBoolean("supportIroning"),
            ironingPattern = optString("supportIroningPattern", "rectilinear"),
            ironingFlow = optDouble("supportIroningFlow", 10.0).toFloat(),
            ironingSpacing = optDouble("supportIroningSpacing", 0.1).toFloat(),
        ),
        supportBasePatternSpacing = optDouble("supportBasePatternSpacing", 2.5).toFloat(),
        supportExpansion = optDouble("supportExpansion", 0.0).toFloat(),
        supportInterfaceLoopPattern = optBoolean("supportInterfaceLoopPattern"),
        independentSupportLayerHeight = optBoolean("independentSupportLayerHeight", true),
        treeSupportBranchAngle = optDouble("treeSupportBranchAngle", 40.0).toFloat(),
        treeSupportBranchDistance = optDouble("treeSupportBranchDistance", 5.0).toFloat(),
        treeSupportBranchDiameter = optDouble("treeSupportBranchDiameter", 5.0).toFloat(),
        treeSupportWallCount = optInt("treeSupportWallCount", 0),
        treeSupportTipDiameter = optDouble("treeSupportTipDiameter", 0.8).toFloat(),
        treeSupportPreferredBranchAngle = optDouble("treeSupportPreferredBranchAngle", 25.0).toFloat(),
        treeSupportBranchDensity = optDouble("treeSupportBranchDensity", 30.0).toFloat(),
        treeSupportOrganicBranchAngle = optDouble("treeSupportOrganicBranchAngle", 40.0).toFloat(),
        treeSupportOrganicBranchDistance = optDouble("treeSupportOrganicBranchDistance", 1.0).toFloat(),
        treeSupportOrganicBranchDiameter = optDouble("treeSupportOrganicBranchDiameter", 2.0).toFloat(),
        treeSupportBranchDiameterAngle = optDouble("treeSupportBranchDiameterAngle", 5.0).toFloat(),
        treeSupportAdaptiveLayerHeight = optBoolean("treeSupportAdaptiveLayerHeight", true),
        treeSupportAutoBrim = optBoolean("treeSupportAutoBrim", true),
        treeSupportBrimWidth = optDouble("treeSupportBrimWidth", 3.0).toFloat(),
        supportFilament = optInt("supportFilament", 0),
        supportInterfaceFilament = optInt("supportInterfaceFilament", 0),
        featureFilaments = FeatureFilamentSettings(
            infillOverrideEnabled = optBoolean("infillFilamentOverrideEnabled"),
            baseFirstLayers = optInt("infillFilamentBaseFirstLayers", 0),
            baseLastLayers = optInt("infillFilamentBaseLastLayers", 0),
            sparseInfillFilament = optInt("sparseInfillFilament", 1),
            wallFilament = optInt("wallFilament", 1),
            solidInfillFilament = optInt("solidInfillFilament", 1),
            wipeTowerFilament = optInt("wipeTowerFilament", 0),
        ),
        wipeTowerEnabled = optBoolean("wipeTowerEnabled"),
        wipeTowerWidth = optDouble("wipeTowerWidth", 60.0).toFloat(),
        multiMaterial = MultiMaterialSettings(
            primeVolume = optDouble("primeVolume", 45.0).toFloat(),
            purgeVolumes = purgeVolumeList("purgeVolumes"),
            primeTowerBrimWidth = optDouble("primeTowerBrimWidth", 3.0).toFloat(),
            primeTowerFramework = optBoolean("primeTowerFramework"),
            primeTowerSkipPoints = optBoolean("primeTowerSkipPoints", true),
            primeTowerFlatIroning = optBoolean("primeTowerFlatIroning"),
            primeTowerInterfaceFeatures = optBoolean("primeTowerInterfaceFeatures"),
            primeTowerInterfaceCooldown = optBoolean("primeTowerInterfaceCooldown"),
            primeTowerInfillGap = optDouble("primeTowerInfillGap", 150.0).toFloat(),
            wipeTowerNoSparseLayers = optBoolean("wipeTowerNoSparseLayers"),
            wipeTowerRotationAngle = optDouble("wipeTowerRotationAngle", 0.0).toFloat(),
            wipeTowerBridging = optDouble("wipeTowerBridging", 10.0).toFloat(),
            wipeTowerExtraSpacing = optDouble("wipeTowerExtraSpacing", 100.0).toFloat(),
            wipeTowerExtraFlow = optDouble("wipeTowerExtraFlow", 100.0).toFloat(),
            wipeTowerMaxPurgeSpeed = optDouble("wipeTowerMaxPurgeSpeed", 90.0).toFloat(),
            wipeTowerWallType = optString("wipeTowerWallType", "rectangle"),
            wipeTowerConeAngle = optDouble("wipeTowerConeAngle", 30.0).toFloat(),
            wipeTowerExtraRibLength = optDouble("wipeTowerExtraRibLength", 0.0).toFloat(),
            wipeTowerRibWidth = optDouble("wipeTowerRibWidth", 8.0).toFloat(),
            wipeTowerFilletWall = optBoolean("wipeTowerFilletWall", true),
            singleExtruderMultiMaterialPriming = optBoolean("singleExtruderMultiMaterialPriming"),
            flushIntoInfill = optBoolean("flushIntoInfill"),
            flushIntoSupport = optBoolean("flushIntoSupport", true),
            flushIntoObjects = optBoolean("flushIntoObjects"),
            oozePrevention = optBoolean("oozePrevention"),
            standbyTemperatureDelta = optInt("standbyTemperatureDelta", -5),
            preheatTime = optDouble("preheatTime", 30.0).toFloat(),
            preheatDeltaTemperature = optInt("preheatDeltaTemperature", 0),
            preheatSteps = optInt("preheatSteps", 1),
            interfaceShells = optBoolean("interfaceShells"),
            segmentedRegionMaxWidth = optDouble("segmentedRegionMaxWidth", 0.0).toFloat(),
            segmentedRegionInterlockingDepth = optDouble(
                "segmentedRegionInterlockingDepth",
                0.0,
            ).toFloat(),
            interlockingBeam = optBoolean("interlockingBeam"),
            interlockingBeamWidth = optDouble("interlockingBeamWidth", 0.8).toFloat(),
            interlockingOrientation = optDouble("interlockingOrientation", 22.5).toFloat(),
            interlockingBeamLayerCount = optInt("interlockingBeamLayerCount", 2),
            interlockingDepth = optInt("interlockingDepth", 2),
            interlockingBoundaryAvoidance = optInt("interlockingBoundaryAvoidance", 2),
        ),
        gcodeSettings = GcodeSettings(
            arcFitting = optBoolean("enableArcFitting"),
            labelObjects = optBoolean("gcodeLabelObjects", true),
            excludeObjects = optBoolean("excludeObject"),
            verboseComments = optBoolean("gcodeComments"),
            timelapseType = optString("timelapseType", "traditional"),
            initialLayerTravelSpeed = optDouble("initialLayerTravelSpeed", 100.0).toFloat(),
            initialLayerTravelSpeedPercent = optBoolean("initialLayerTravelSpeedPercent", true),
            slowDownLayers = optInt("slowDownLayers", 0),
            accelToDecelEnabled = optBoolean("accelToDecelEnabled", true),
            accelToDecelFactor = optDouble("accelToDecelFactor", 50.0).toFloat(),
            filenameFormat = optString("filenameFormat", DEFAULT_GCODE_FILENAME_FORMAT),
        ),
        skirtType = optString("skirtType", "combined"),
        skirtLoops = optInt("skirtLoops", 0),
        skirtDistance = optDouble("skirtDistance", 6.0).toFloat(),
        skirtStartAngle = optDouble("skirtStartAngle", -135.0).toFloat(),
        skirtHeight = optInt("skirtHeight", 1),
        skirtSpeed = optDouble("skirtSpeed", 50.0).toFloat(),
        minimumSkirtLength = optDouble("minimumSkirtLength", 0.0).toFloat(),
        draftShield = optString("draftShield", "disabled"),
        singleLoopDraftShield = optBoolean("singleLoopDraftShield"),
        outerWallLineWidth = optDouble("outerWallLineWidth", 0.0).toFloat(),
        innerWallLineWidth = optDouble("innerWallLineWidth", 0.0).toFloat(),
        topSurfaceLineWidth = optDouble("topSurfaceLineWidth", 0.0).toFloat(),
        sparseInfillLineWidth = optDouble("sparseInfillLineWidth", 0.0).toFloat(),
        internalSolidInfillLineWidth = optDouble("internalSolidInfillLineWidth", 0.0).toFloat(),
        supportLineWidth = optDouble("supportLineWidth", 0.0).toFloat(),
        initialLayerLineWidth = optDouble("initialLayerLineWidth", 0.0).toFloat(),
        smallPerimeterSpeed = optDouble("smallPerimeterSpeed", 50.0).toFloat(),
        smallPerimeterSpeedPercent = optBoolean("smallPerimeterSpeedPercent", true),
        smallPerimeterThreshold = optDouble("smallPerimeterThreshold", 0.0).toFloat(),
        slowdownForCurledPerimeters = optBoolean("slowdownForCurledPerimeters", true),
        resolution = optDouble("resolution", 0.01).toFloat(),
        precision = PrecisionSettings(
            mode = optString("slicingMode", "regular"),
            closingRadius = optDouble("sliceClosingRadius", 0.049).toFloat(),
            preciseZHeight = optBoolean("preciseZHeight"),
            polyholes = PolyholeSettings(
                enabled = optBoolean("holeToPolyhole"),
                detectionMargin = optDouble("holeToPolyholeThreshold", 0.01).toFloat(),
                detectionMarginPercent = optBoolean("holeToPolyholeThresholdPercent"),
                twist = optBoolean("holeToPolyholeTwisted", true),
            ),
            minimumWallWidth = optDouble("minimumWallWidth", 85.0).toFloat(),
            firstLayerMinimumWallWidth = optDouble("firstLayerMinimumWallWidth", 85.0).toFloat(),
            printableOverhangs = PrintableOverhangSettings(
                enabled = optBoolean("makeOverhangPrintable"),
                maximumAngle = optDouble("makeOverhangPrintableAngle", 55.0).toFloat(),
                holeArea = optDouble("makeOverhangPrintableHoleSize", 0.0).toFloat(),
            ),
            brimEars = BrimEarSettings(
                maximumAngle = optDouble("brimEarsMaxAngle", 125.0).toFloat(),
                detectionRadius = optDouble("brimEarsDetectionLength", 1.0).toFloat(),
            ),
        ),
        seamPosition = optString("seamPosition", "aligned"),
        staggeredInnerSeams = optBoolean("staggeredInnerSeams"),
        seamGap = optDouble("seamGap", 10.0).toFloat(),
        seamGapPercent = optBoolean("seamGapPercent", true),
        scarfSeam = ScarfSeamSettings(
            type = optString("scarfSeamType", "none"),
            conditional = optBoolean("scarfSeamConditional"),
            angleThreshold = optInt("scarfAngleThreshold", 155),
            overhangThreshold = optDouble("scarfOverhangThreshold", 40.0).toFloat(),
            speed = optDouble("scarfJointSpeed", 100.0).toFloat(),
            speedPercent = optBoolean("scarfJointSpeedPercent", true),
            flowRatio = optDouble("scarfJointFlowRatio", 1.0).toFloat(),
            startHeight = optDouble("scarfStartHeight", 0.0).toFloat(),
            startHeightPercent = optBoolean("scarfStartHeightPercent"),
            entireLoop = optBoolean("scarfEntireLoop"),
            length = optDouble("scarfLength", 20.0).toFloat(),
            steps = optInt("scarfSteps", 10),
            innerWalls = optBoolean("scarfInnerWalls"),
        ),
        wipeBeforeExternalLoop = optBoolean("wipeBeforeExternalLoop"),
        wipeOnLoops = optBoolean("wipeOnLoops"),
        roleBasedWipeSpeed = optBoolean("roleBasedWipeSpeed", true),
        wipeSpeed = optDouble("wipeSpeed", 80.0).toFloat(),
        wipeSpeedPercent = optBoolean("wipeSpeedPercent", true),
        ironing = IroningSettings(
            type = optString("ironingType", "no ironing"),
            pattern = optString("ironingPattern", "rectilinear"),
            flow = optDouble("ironingFlow", 10.0).toFloat(),
            spacing = optDouble("ironingSpacing", 0.1).toFloat(),
            inset = optDouble("ironingInset", 0.0).toFloat(),
            speed = optDouble("ironingSpeed", 20.0).toFloat(),
            angle = optDouble("ironingAngle", -1.0).toFloat(),
        ),
        wallGenerator = optString("wallGenerator", "arachne"),
        wallTransitionLength = optDouble("wallTransitionLength", 100.0).toFloat(),
        wallTransitionFilterDeviation = optDouble("wallTransitionFilterDeviation", 25.0).toFloat(),
        wallTransitionAngle = optDouble("wallTransitionAngle", 10.0).toFloat(),
        wallDistributionCount = optInt("wallDistributionCount", 1),
        minimumFeatureSize = optDouble("minimumFeatureSize", 25.0).toFloat(),
        minimumWallLengthFactor = optDouble("minimumWallLengthFactor", 0.5).toFloat(),
        wallSequence = optString("wallSequence", "inner-outer"),
        wallDirection = optString("wallDirection", "auto"),
        detectThinWalls = optBoolean("detectThinWalls"),
        detectOverhangWalls = optBoolean("detectOverhangWalls", true),
        onlyOneWallOnTop = optBoolean("onlyOneWallOnTop"),
        minWidthTopSurface = optDouble("minWidthTopSurface", 300.0).toFloat(),
        minWidthTopSurfacePercent = optBoolean("minWidthTopSurfacePercent", true),
        onlyOneWallFirstLayer = optBoolean("onlyOneWallFirstLayer"),
        extraPerimetersOnOverhangs = optBoolean("extraPerimetersOnOverhangs"),
        overhangReverse = optBoolean("overhangReverse"),
        overhangReverseInternalOnly = optBoolean("overhangReverseInternalOnly"),
        overhangReverseThreshold = optDouble("overhangReverseThreshold", 50.0).toFloat(),
        overhangReverseThresholdPercent = optBoolean("overhangReverseThresholdPercent", true),
        counterboreHoleBridging = optString("counterboreHoleBridging", "none"),
        alternateExtraWall = optBoolean("alternateExtraWall"),
        ensureVerticalShellThickness = optString("ensureVerticalShellThickness", "ensure_all"),
        detectNarrowInternalSolidInfill = optBoolean("detectNarrowInternalSolidInfill", true),
        xyHoleCompensation = optDouble("xyHoleCompensation", 0.0).toFloat(),
        xyContourCompensation = optDouble("xyContourCompensation", 0.0).toFloat(),
        elephantFootCompensation = optDouble("elephantFootCompensation", 0.0).toFloat(),
        elephantFootCompensationLayers = optInt("elephantFootCompensationLayers", 1),
        maxBridgeLength = optDouble("maxBridgeLength", 10.0).toFloat(),
        preciseOuterWalls = optBoolean("preciseOuterWalls", true),
        printSequence = optString("printSequence", "by layer"),
        printOrder = optString("printOrder", "default"),
        spiralMode = optBoolean("spiralMode"),
        spiralModeSmooth = optBoolean("spiralModeSmooth"),
        spiralModeMaxXySmoothing = optDouble("spiralModeMaxXySmoothing", 200.0).toFloat(),
        spiralModeMaxXySmoothingPercent = optBoolean("spiralModeMaxXySmoothingPercent", true),
        spiralStartingFlowRatio = optDouble("spiralStartingFlowRatio", 0.0).toFloat(),
        spiralFinishingFlowRatio = optDouble("spiralFinishingFlowRatio", 0.0).toFloat(),
        brand = optionalString("brand"),
        compatiblePrinters = stringList("compatiblePrinters"),
    )
}.getOrNull()

private fun JSONArray?.toPrinterProfiles() = objects().mapNotNull(JSONObject::toPrinterProfileOrNull)

private fun JSONArray?.toFilamentProfiles() = objects().mapNotNull(JSONObject::toFilamentProfileOrNull)

private fun JSONArray?.toQualityProfiles() = objects().mapNotNull(JSONObject::toQualityProfileOrNull)

private fun JSONObject.optionalString(key: String): String? =
    takeUnless { isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.nullableFloat(key: String): Float? =
    if (!has(key) || isNull(key)) null else getDouble(key).toFloat()

private fun JSONObject.nullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else getBoolean(key)

private fun JSONObject.stringList(key: String): List<String> =
    optJSONArray(key)?.let { values ->
        List(values.length()) { index -> values.optString(index) }.filter(String::isNotBlank)
    }.orEmpty()

private fun JSONObject.floatList(key: String): List<Float>? = optJSONArray(key)?.let { values ->
    if (values.length() !in 6..512 || values.length() % 2 != 0) return null
    List(values.length()) { index -> values.getDouble(index).toFloat() }
}

private fun JSONObject.bedExcludeFloatList(key: String): List<Float>? =
    optJSONArray(key)?.let { values ->
        if (
            values.length() > 512 || values.length() % 2 != 0 ||
            values.length() !in setOf(0, 2) && values.length() < 6
        ) {
            return null
        }
        List(values.length()) { index -> values.getDouble(index).toFloat() }
    }

private fun JSONObject.extruderFloatList(key: String): List<Float>? = optJSONArray(key)?.let { values ->
    if (values.length() !in 1..MAX_FILAMENT_SLOTS) return null
    List(values.length()) { index -> values.getDouble(index).toFloat() }
}

private fun JSONObject.purgeVolumeList(key: String): List<Float> = optJSONArray(key)?.let { values ->
    val dimension = (1..MAX_FILAMENT_SLOTS).firstOrNull { it * it == values.length() }
        ?: return emptyList()
    List(dimension * dimension) { index -> values.getDouble(index).toFloat() }
}.orEmpty()

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    List(length()) { index -> optJSONObject(index) }.filterNotNull()
}

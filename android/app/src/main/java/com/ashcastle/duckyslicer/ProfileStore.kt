package com.ashcastle.duckyslicer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

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
            maxPrintHeight = options.maxPrintHeight,
            nozzleDiameter = options.nozzleDiameter,
            machineStartGcode = options.printerProfile.machineStartGcode,
            machineEndGcode = options.printerProfile.machineEndGcode,
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
            extruderCount = options.printerProfile.extruderCount,
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
            selected.copy(
                nozzleTemp = options.nozzleTemp,
                firstLayerNozzleTemp = options.firstLayerNozzleTemp,
                bedTemp = options.bedTemp,
                firstLayerBedTemp = options.firstLayerBedTemp,
                flowRatio = options.flowRatio,
                maxVolumetricSpeed = options.maxVolumetricSpeed,
                retractLength = options.retractLength,
                retractSpeed = options.retractSpeed,
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
            bedTemp = effective.bedTemp,
            firstLayerBedTemp = effective.firstLayerBedTemp,
            flowRatio = effective.flowRatio,
            maxVolumetricSpeed = effective.maxVolumetricSpeed,
            retractLength = effective.retractLength,
            retractSpeed = effective.retractSpeed,
            fanMinSpeed = effective.fanMinSpeed,
            fanMaxSpeed = effective.fanMaxSpeed,
            overhangFanSpeed = effective.overhangFanSpeed,
            slowDownLayerTime = effective.slowDownLayerTime,
            slowDownMinSpeed = effective.slowDownMinSpeed,
            closeFanFirstLayers = effective.closeFanFirstLayers,
            fullFanSpeedLayer = effective.fullFanSpeedLayer,
            pressureAdvanceEnabled = effective.pressureAdvanceEnabled,
            pressureAdvance = effective.pressureAdvance,
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
            fillPattern = options.fillPattern,
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
            gapFillTarget = options.gapFillTarget,
            filterOutGapFill = options.filterOutGapFill,
            reduceCrossingWall = options.reduceCrossingWall,
            maxTravelDetourDistance = options.maxTravelDetourDistance,
            maxTravelDetourDistancePercent = options.maxTravelDetourDistancePercent,
            reduceInfillRetraction = options.reduceInfillRetraction,
            travelSpeed = options.travelSpeed,
            firstLayerSpeed = options.firstLayerSpeed,
            supportType = options.supportType,
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
            supportStyle = options.supportStyle,
            skirtLoops = options.skirtLoops,
            skirtDistance = options.skirtDistance,
            skirtHeight = options.skirtHeight,
            skirtSpeed = options.skirtSpeed,
            minimumSkirtLength = options.minimumSkirtLength,
            draftShield = options.draftShield,
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
            seamPosition = options.seamPosition,
            staggeredInnerSeams = options.staggeredInnerSeams,
            seamGap = options.seamGap,
            seamGapPercent = options.seamGapPercent,
            wipeBeforeExternalLoop = options.wipeBeforeExternalLoop,
            wipeOnLoops = options.wipeOnLoops,
            roleBasedWipeSpeed = options.roleBasedWipeSpeed,
            wipeSpeed = options.wipeSpeed,
            wipeSpeedPercent = options.wipeSpeedPercent,
            ironingType = options.ironingType,
            ironingPattern = options.ironingPattern,
            ironingFlow = options.ironingFlow,
            ironingSpacing = options.ironingSpacing,
            ironingSpeed = options.ironingSpeed,
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
        )
        require(ProfileValidation.slicing(profile)) { "Slicing profile contains unsafe values" }
        append("slicing", profile.toProfileJson())
        return profile
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
        const val USER_PROFILE_SCHEMA_VERSION = 16
        const val MAX_USER_PROFILE_BYTES = 16 * 1_024 * 1_024
        const val MAX_USER_PROFILES = 4_096
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
    .put("maxPrintHeight", maxPrintHeight).put("nozzleDiameter", nozzleDiameter)
    .put("machineStartGcode", machineStartGcode).put("machineEndGcode", machineEndGcode)
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
    .put("extruderCount", extruderCount)
    .put("builtIn", builtIn)
    .put("brand", brand ?: JSONObject.NULL)

internal fun FilamentProfile.toProfileJson() = JSONObject()
    .put("id", id).put("name", name).put("nativeName", nativeName)
    .put("nozzleTemp", nozzleTemp).put("firstLayerNozzleTemp", firstLayerNozzleTemp)
    .put("bedTemp", bedTemp).put("firstLayerBedTemp", firstLayerBedTemp)
    .put("flowRatio", flowRatio).put("maxVolumetricSpeed", maxVolumetricSpeed)
    .put("retractLength", retractLength).put("retractSpeed", retractSpeed)
    .put("fanMinSpeed", fanMinSpeed).put("fanMaxSpeed", fanMaxSpeed)
    .put("overhangFanSpeed", overhangFanSpeed)
    .put("slowDownLayerTime", slowDownLayerTime).put("slowDownMinSpeed", slowDownMinSpeed)
    .put("closeFanFirstLayers", closeFanFirstLayers).put("fullFanSpeedLayer", fullFanSpeedLayer)
    .put("pressureAdvanceEnabled", pressureAdvanceEnabled).put("pressureAdvance", pressureAdvance)
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
    .put("supportEnabled", supportEnabled)
    .put("brimType", brimType)
    .put("brimWidth", brimWidth)
    .put("brimObjectGap", brimObjectGap)
    .put("raftLayers", raftLayers)
    .put("raftContactDistance", raftContactDistance)
    .put("raftExpansion", raftExpansion)
    .put("raftFirstLayerDensity", raftFirstLayerDensity)
    .put("raftFirstLayerExpansion", raftFirstLayerExpansion)
    .put("topSolidLayers", topSolidLayers).put("bottomSolidLayers", bottomSolidLayers)
    .put("topShellThickness", topShellThickness).put("bottomShellThickness", bottomShellThickness)
    .put("fillPattern", fillPattern)
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
    .put("gapFillTarget", gapFillTarget)
    .put("filterOutGapFill", filterOutGapFill)
    .put("reduceCrossingWall", reduceCrossingWall)
    .put("maxTravelDetourDistance", maxTravelDetourDistance)
    .put("maxTravelDetourDistancePercent", maxTravelDetourDistancePercent)
    .put("reduceInfillRetraction", reduceInfillRetraction)
    .put("travelSpeed", travelSpeed)
    .put("firstLayerSpeed", firstLayerSpeed).put("supportType", supportType)
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
    .put("supportStyle", supportStyle)
    .put("skirtHeight", skirtHeight)
    .put("skirtSpeed", skirtSpeed)
    .put("minimumSkirtLength", minimumSkirtLength)
    .put("draftShield", draftShield)
    .put("skirtDistance", skirtDistance)
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
    .put("seamPosition", seamPosition)
    .put("staggeredInnerSeams", staggeredInnerSeams)
    .put("seamGap", seamGap)
    .put("seamGapPercent", seamGapPercent)
    .put("wipeBeforeExternalLoop", wipeBeforeExternalLoop)
    .put("wipeOnLoops", wipeOnLoops)
    .put("roleBasedWipeSpeed", roleBasedWipeSpeed)
    .put("wipeSpeed", wipeSpeed)
    .put("wipeSpeedPercent", wipeSpeedPercent)
    .put("ironingType", ironingType)
    .put("ironingPattern", ironingPattern)
    .put("ironingFlow", ironingFlow)
    .put("ironingSpacing", ironingSpacing)
    .put("ironingSpeed", ironingSpeed)
    .put("wallGenerator", wallGenerator)
    .put("wallTransitionLength", wallTransitionLength)
    .put("wallTransitionFilterDeviation", wallTransitionFilterDeviation)
    .put("wallTransitionAngle", wallTransitionAngle)
    .put("wallDistributionCount", wallDistributionCount)
    .put("minimumFeatureSize", minimumFeatureSize)
    .put("minimumWallLengthFactor", minimumWallLengthFactor)
    .put("wallSequence", wallSequence)
    .put("wallDirection", wallDirection)
    .put("detectThinWalls", detectThinWalls)
    .put("detectOverhangWalls", detectOverhangWalls)
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
    .put("builtIn", builtIn)
    .put("brand", brand ?: JSONObject.NULL)
    .put("compatiblePrinters", JSONArray(compatiblePrinters))

internal fun JSONObject.toPrinterProfileOrNull(): PrinterProfile? = runCatching {
    val bedSizeX = getDouble("bedSizeX").toFloat()
    val bedSizeY = getDouble("bedSizeY").toFloat()
    PrinterProfile(
        getString("id"), getString("name"),
        bedSizeX, bedSizeY,
        getDouble("maxPrintHeight").toFloat(), getDouble("nozzleDiameter").toFloat(),
        builtIn = optBoolean("builtIn"),
        brand = optionalString("brand"),
        machineStartGcode = optString("machineStartGcode"),
        machineEndGcode = optString("machineEndGcode"),
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
        bedOriginX = optDouble("bedOriginX", 0.0).toFloat(),
        bedOriginY = optDouble("bedOriginY", 0.0).toFloat(),
        bedPolygon = if (has("bedPolygon")) {
            requireNotNull(floatList("bedPolygon")) { "Invalid bed polygon" }
        } else {
            rectangularBedPolygon(bedSizeX, bedSizeY)
        },
        extruderCount = optInt("extruderCount", 1),
    )
}.getOrNull()

internal fun JSONObject.toFilamentProfileOrNull(): FilamentProfile? = runCatching {
    FilamentProfile(
        getString("id"), getString("name"), getString("nativeName"),
        getInt("nozzleTemp"), optInt("firstLayerNozzleTemp", getInt("nozzleTemp")),
        getInt("bedTemp"), optInt("firstLayerBedTemp", getInt("bedTemp")),
        getDouble("flowRatio").toFloat(), getDouble("maxVolumetricSpeed").toFloat(),
        builtIn = optBoolean("builtIn"),
        brand = optionalString("brand"),
        retractLength = optDouble("retractLength", 0.8).toFloat(),
        retractSpeed = optDouble("retractSpeed", 45.0).toFloat(),
        fanMinSpeed = optInt("fanMinSpeed", 30),
        fanMaxSpeed = optInt("fanMaxSpeed", 100),
        overhangFanSpeed = optInt("overhangFanSpeed", 100),
        slowDownLayerTime = optDouble("slowDownLayerTime", 8.0).toFloat(),
        slowDownMinSpeed = optDouble("slowDownMinSpeed", 10.0).toFloat(),
        closeFanFirstLayers = optInt("closeFanFirstLayers", 1),
        fullFanSpeedLayer = optInt("fullFanSpeedLayer", 3),
        pressureAdvanceEnabled = optBoolean("pressureAdvanceEnabled"),
        pressureAdvance = optDouble("pressureAdvance", 0.0).toFloat(),
        compatiblePrinters = stringList("compatiblePrinters"),
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
        fillPattern = optString("fillPattern", "gyroid"),
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
        gapFillTarget = optString("gapFillTarget", "nowhere"),
        filterOutGapFill = optDouble("filterOutGapFill", 0.0).toFloat(),
        reduceCrossingWall = optBoolean("reduceCrossingWall"),
        maxTravelDetourDistance = optDouble("maxTravelDetourDistance", 0.0).toFloat(),
        maxTravelDetourDistancePercent = optBoolean("maxTravelDetourDistancePercent"),
        reduceInfillRetraction = optBoolean("reduceInfillRetraction"),
        travelSpeed = optDouble("travelSpeed", 500.0).toFloat(),
        firstLayerSpeed = optDouble("firstLayerSpeed", 50.0).toFloat(),
        supportType = optString("supportType", "normal"),
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
        supportStyle = optString("supportStyle", "default"),
        skirtLoops = optInt("skirtLoops", 0),
        skirtDistance = optDouble("skirtDistance", 6.0).toFloat(),
        skirtHeight = optInt("skirtHeight", 1),
        skirtSpeed = optDouble("skirtSpeed", 50.0).toFloat(),
        minimumSkirtLength = optDouble("minimumSkirtLength", 0.0).toFloat(),
        draftShield = optString("draftShield", "disabled"),
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
        seamPosition = optString("seamPosition", "aligned"),
        staggeredInnerSeams = optBoolean("staggeredInnerSeams"),
        seamGap = optDouble("seamGap", 10.0).toFloat(),
        seamGapPercent = optBoolean("seamGapPercent", true),
        wipeBeforeExternalLoop = optBoolean("wipeBeforeExternalLoop"),
        wipeOnLoops = optBoolean("wipeOnLoops"),
        roleBasedWipeSpeed = optBoolean("roleBasedWipeSpeed", true),
        wipeSpeed = optDouble("wipeSpeed", 80.0).toFloat(),
        wipeSpeedPercent = optBoolean("wipeSpeedPercent", true),
        ironingType = optString("ironingType", "no ironing"),
        ironingPattern = optString("ironingPattern", "rectilinear"),
        ironingFlow = optDouble("ironingFlow", 10.0).toFloat(),
        ironingSpacing = optDouble("ironingSpacing", 0.1).toFloat(),
        ironingSpeed = optDouble("ironingSpeed", 20.0).toFloat(),
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
        brand = optionalString("brand"),
        compatiblePrinters = stringList("compatiblePrinters"),
    )
}.getOrNull()

private fun JSONArray?.toPrinterProfiles() = objects().mapNotNull(JSONObject::toPrinterProfileOrNull)

private fun JSONArray?.toFilamentProfiles() = objects().mapNotNull(JSONObject::toFilamentProfileOrNull)

private fun JSONArray?.toQualityProfiles() = objects().mapNotNull(JSONObject::toQualityProfileOrNull)

private fun JSONObject.optionalString(key: String): String? =
    takeUnless { isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)

private fun JSONObject.stringList(key: String): List<String> =
    optJSONArray(key)?.let { values ->
        List(values.length()) { index -> values.optString(index) }.filter(String::isNotBlank)
    }.orEmpty()

private fun JSONObject.floatList(key: String): List<Float>? = optJSONArray(key)?.let { values ->
    if (values.length() !in 6..512 || values.length() % 2 != 0) return null
    List(values.length()) { index -> values.getDouble(index).toFloat() }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    List(length()) { index -> optJSONObject(index) }.filterNotNull()
}

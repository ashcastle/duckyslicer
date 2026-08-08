package com.ashcastle.duckyslicer

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader

private const val CATALOG_ASSET = "profile_catalog_v9.json"

class OrcaProfileCatalog(private val context: Context) {
    fun load(): ProfileCatalog = runCatching {
        context.assets.open(CATALOG_ASSET).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use(::readCatalog)
        }
    }.getOrElse { ProfileCatalog() }

    private fun readCatalog(reader: JsonReader): ProfileCatalog {
        var schemaVersion = 0
        var sourceRevision = ""
        var rejectedCount = 0
        var printers = emptyList<PrinterProfile>()
        var filaments = emptyList<FilamentProfile>()
        var slicing = emptyList<QualityProfile>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schemaVersion" -> schemaVersion = reader.nextInt()
                "sourceRevision" -> sourceRevision = reader.nextString()
                "rejected" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName()
                        rejectedCount += reader.nextInt()
                    }
                    reader.endObject()
                }
                "printers" -> printers = readArray(reader, ::readPrinter)
                "filaments" -> filaments = readArray(reader, ::readFilament)
                "slicing" -> slicing = readArray(reader, ::readQuality)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        check(schemaVersion == 9) { "Unsupported profile catalog schema" }
        return ProfileCatalog(
            printers = (PrinterProfile.builtIns + printers).distinctBy(PrinterProfile::id),
            filaments = (FilamentProfile.builtIns + filaments).distinctBy(FilamentProfile::id),
            slicing = (QualityProfile.builtIns + slicing).distinctBy(QualityProfile::id),
            schemaVersion = schemaVersion,
            sourceRevision = sourceRevision,
            rejectedCount = rejectedCount,
        )
    }

    private fun <T> readArray(reader: JsonReader, readValue: (JsonReader) -> T): List<T> = buildList {
        reader.beginArray()
        while (reader.hasNext()) add(readValue(reader))
        reader.endArray()
    }

    private fun readPrinter(reader: JsonReader): PrinterProfile {
        var profile = PrinterProfile.CUSTOM_CARTESIAN
        var id = ""
        var name = ""
        var brand = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "brand" -> brand = reader.nextString()
                "bedSizeX" -> profile = profile.copy(bedSizeX = reader.nextFloat())
                "bedSizeY" -> profile = profile.copy(bedSizeY = reader.nextFloat())
                "maxPrintHeight" -> profile = profile.copy(maxPrintHeight = reader.nextFloat())
                "nozzleDiameter" -> profile = profile.copy(nozzleDiameter = reader.nextFloat())
                "machineStartGcode" -> profile = profile.copy(machineStartGcode = reader.nextString())
                "machineEndGcode" -> profile = profile.copy(machineEndGcode = reader.nextString())
                "gcodeFlavor" -> profile = profile.copy(gcodeFlavor = reader.nextString())
                "maxSpeedX" -> profile = profile.copy(maxSpeedX = reader.nextFloat())
                "maxSpeedY" -> profile = profile.copy(maxSpeedY = reader.nextFloat())
                "maxSpeedZ" -> profile = profile.copy(maxSpeedZ = reader.nextFloat())
                "maxSpeedE" -> profile = profile.copy(maxSpeedE = reader.nextFloat())
                "maxAccelerationX" -> profile = profile.copy(maxAccelerationX = reader.nextFloat())
                "maxAccelerationY" -> profile = profile.copy(maxAccelerationY = reader.nextFloat())
                "maxAccelerationZ" -> profile = profile.copy(maxAccelerationZ = reader.nextFloat())
                "maxAccelerationE" -> profile = profile.copy(maxAccelerationE = reader.nextFloat())
                "maxAccelerationExtruding" -> profile =
                    profile.copy(maxAccelerationExtruding = reader.nextFloat())
                "maxAccelerationRetracting" -> profile =
                    profile.copy(maxAccelerationRetracting = reader.nextFloat())
                "maxAccelerationTravel" -> profile = profile.copy(maxAccelerationTravel = reader.nextFloat())
                "maxJerkX" -> profile = profile.copy(maxJerkX = reader.nextFloat())
                "maxJerkY" -> profile = profile.copy(maxJerkY = reader.nextFloat())
                "maxJerkZ" -> profile = profile.copy(maxJerkZ = reader.nextFloat())
                "maxJerkE" -> profile = profile.copy(maxJerkE = reader.nextFloat())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return profile.copy(id = id, name = name, brand = brand, builtIn = true)
    }

    private fun readFilament(reader: JsonReader): FilamentProfile {
        var profile = FilamentProfile.GENERIC_PLA
        var id = ""
        var name = ""
        var brand = ""
        var compatiblePrinters = emptyList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "brand" -> brand = reader.nextString()
                "nativeName" -> profile = profile.copy(nativeName = reader.nextString())
                "nozzleTemp" -> profile = profile.copy(nozzleTemp = reader.nextInt())
                "firstLayerNozzleTemp" -> profile = profile.copy(firstLayerNozzleTemp = reader.nextInt())
                "bedTemp" -> profile = profile.copy(bedTemp = reader.nextInt())
                "firstLayerBedTemp" -> profile = profile.copy(firstLayerBedTemp = reader.nextInt())
                "flowRatio" -> profile = profile.copy(flowRatio = reader.nextFloat())
                "maxVolumetricSpeed" -> profile = profile.copy(maxVolumetricSpeed = reader.nextFloat())
                "retractLength" -> profile = profile.copy(retractLength = reader.nextFloat())
                "retractSpeed" -> profile = profile.copy(retractSpeed = reader.nextFloat())
                "fanMinSpeed" -> profile = profile.copy(fanMinSpeed = reader.nextInt())
                "fanMaxSpeed" -> profile = profile.copy(fanMaxSpeed = reader.nextInt())
                "overhangFanSpeed" -> profile = profile.copy(overhangFanSpeed = reader.nextInt())
                "slowDownLayerTime" -> profile = profile.copy(slowDownLayerTime = reader.nextFloat())
                "slowDownMinSpeed" -> profile = profile.copy(slowDownMinSpeed = reader.nextFloat())
                "closeFanFirstLayers" -> profile = profile.copy(closeFanFirstLayers = reader.nextInt())
                "fullFanSpeedLayer" -> profile = profile.copy(fullFanSpeedLayer = reader.nextInt())
                "pressureAdvanceEnabled" ->
                    profile = profile.copy(pressureAdvanceEnabled = reader.nextBoolean())
                "pressureAdvance" -> profile = profile.copy(pressureAdvance = reader.nextFloat())
                "compatiblePrinters" -> compatiblePrinters = reader.readStringList()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return profile.copy(
            id = id,
            name = name,
            brand = brand,
            builtIn = true,
            compatiblePrinters = compatiblePrinters,
        )
    }

    private fun readQuality(reader: JsonReader): QualityProfile {
        var profile = QualityProfile.STANDARD
        var id = ""
        var name = ""
        var brand = ""
        var compatiblePrinters = emptyList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "brand" -> brand = reader.nextString()
                "layerHeightMm" -> profile = profile.copy(layerHeightMm = reader.nextFloat())
                "firstLayerHeightMm" -> profile = profile.copy(firstLayerHeightMm = reader.nextFloat())
                "perimeters" -> profile = profile.copy(perimeters = reader.nextInt())
                "fillDensity" -> profile = profile.copy(fillDensity = reader.nextFloat())
                "printSpeed" -> profile = profile.copy(printSpeed = reader.nextFloat())
                "innerWallSpeed" -> profile = profile.copy(innerWallSpeed = reader.nextFloat())
                "sparseInfillSpeed" -> profile = profile.copy(sparseInfillSpeed = reader.nextFloat())
                "internalSolidInfillSpeed" -> profile = profile.copy(internalSolidInfillSpeed = reader.nextFloat())
                "topSurfaceSpeed" -> profile = profile.copy(topSurfaceSpeed = reader.nextFloat())
                "supportSpeed" -> profile = profile.copy(supportSpeed = reader.nextFloat())
                "bridgeSpeed" -> profile = profile.copy(bridgeSpeed = reader.nextFloat())
                "gapInfillSpeed" -> profile = profile.copy(gapInfillSpeed = reader.nextFloat())
                "firstLayerInfillSpeed" -> profile = profile.copy(firstLayerInfillSpeed = reader.nextFloat())
                "supportInterfaceSpeed" -> profile = profile.copy(supportInterfaceSpeed = reader.nextFloat())
                "internalBridgeSpeed" -> profile = profile.copy(internalBridgeSpeed = reader.nextFloat())
                "internalBridgeSpeedPercent" -> profile = profile.copy(internalBridgeSpeedPercent = reader.nextBoolean())
                "overhangSpeedEnabled" -> profile = profile.copy(overhangSpeedEnabled = reader.nextBoolean())
                "overhangSpeed1" -> profile = profile.copy(overhangSpeed1 = reader.nextFloat())
                "overhangSpeed1Percent" -> profile = profile.copy(overhangSpeed1Percent = reader.nextBoolean())
                "overhangSpeed2" -> profile = profile.copy(overhangSpeed2 = reader.nextFloat())
                "overhangSpeed2Percent" -> profile = profile.copy(overhangSpeed2Percent = reader.nextBoolean())
                "overhangSpeed3" -> profile = profile.copy(overhangSpeed3 = reader.nextFloat())
                "overhangSpeed3Percent" -> profile = profile.copy(overhangSpeed3Percent = reader.nextBoolean())
                "overhangSpeed4" -> profile = profile.copy(overhangSpeed4 = reader.nextFloat())
                "overhangSpeed4Percent" -> profile = profile.copy(overhangSpeed4Percent = reader.nextBoolean())
                "bridgeFlowRatio" -> profile = profile.copy(bridgeFlowRatio = reader.nextFloat())
                "internalBridgeFlowRatio" -> profile = profile.copy(internalBridgeFlowRatio = reader.nextFloat())
                "topSurfaceFlowRatio" -> profile = profile.copy(topSurfaceFlowRatio = reader.nextFloat())
                "bottomSurfaceFlowRatio" -> profile = profile.copy(bottomSurfaceFlowRatio = reader.nextFloat())
                "bridgeDensity" -> profile = profile.copy(bridgeDensity = reader.nextFloat())
                "internalBridgeDensity" -> profile = profile.copy(internalBridgeDensity = reader.nextFloat())
                "bridgeNoSupport" -> profile = profile.copy(bridgeNoSupport = reader.nextBoolean())
                "thickBridges" -> profile = profile.copy(thickBridges = reader.nextBoolean())
                "thickInternalBridges" -> profile = profile.copy(thickInternalBridges = reader.nextBoolean())
                "defaultAcceleration" -> profile = profile.copy(defaultAcceleration = reader.nextFloat())
                "outerWallAcceleration" -> profile = profile.copy(outerWallAcceleration = reader.nextFloat())
                "innerWallAcceleration" -> profile = profile.copy(innerWallAcceleration = reader.nextFloat())
                "topSurfaceAcceleration" -> profile = profile.copy(topSurfaceAcceleration = reader.nextFloat())
                "travelAcceleration" -> profile = profile.copy(travelAcceleration = reader.nextFloat())
                "firstLayerAcceleration" -> profile = profile.copy(firstLayerAcceleration = reader.nextFloat())
                "bridgeAcceleration" -> profile = profile.copy(bridgeAcceleration = reader.nextFloat())
                "bridgeAccelerationPercent" -> profile = profile.copy(bridgeAccelerationPercent = reader.nextBoolean())
                "sparseInfillAcceleration" -> profile = profile.copy(sparseInfillAcceleration = reader.nextFloat())
                "sparseInfillAccelerationPercent" -> profile = profile.copy(sparseInfillAccelerationPercent = reader.nextBoolean())
                "internalSolidInfillAcceleration" -> profile = profile.copy(internalSolidInfillAcceleration = reader.nextFloat())
                "internalSolidInfillAccelerationPercent" -> profile = profile.copy(internalSolidInfillAccelerationPercent = reader.nextBoolean())
                "nozzleDiameter" -> profile = profile.copy(nozzleDiameter = reader.nextFloat())
                "supportEnabled" -> profile = profile.copy(supportEnabled = reader.nextBoolean())
                "brimWidth" -> profile = profile.copy(brimWidth = reader.nextFloat())
                "topSolidLayers" -> profile = profile.copy(topSolidLayers = reader.nextInt())
                "bottomSolidLayers" -> profile = profile.copy(bottomSolidLayers = reader.nextInt())
                "topShellThickness" -> profile = profile.copy(topShellThickness = reader.nextFloat())
                "bottomShellThickness" -> profile = profile.copy(bottomShellThickness = reader.nextFloat())
                "fillPattern" -> profile = profile.copy(fillPattern = reader.nextString())
                "topSurfacePattern" -> profile = profile.copy(topSurfacePattern = reader.nextString())
                "bottomSurfacePattern" -> profile = profile.copy(bottomSurfacePattern = reader.nextString())
                "internalSolidInfillPattern" -> profile = profile.copy(internalSolidInfillPattern = reader.nextString())
                "infillFirst" -> profile = profile.copy(infillFirst = reader.nextBoolean())
                "infillWallOverlap" -> profile = profile.copy(infillWallOverlap = reader.nextFloat())
                "topBottomInfillWallOverlap" -> profile = profile.copy(topBottomInfillWallOverlap = reader.nextFloat())
                "infillCombination" -> profile = profile.copy(infillCombination = reader.nextBoolean())
                "infillCombinationMaxLayerHeight" -> profile = profile.copy(infillCombinationMaxLayerHeight = reader.nextFloat())
                "infillCombinationMaxLayerHeightPercent" -> profile = profile.copy(infillCombinationMaxLayerHeightPercent = reader.nextBoolean())
                "infillDirection" -> profile = profile.copy(infillDirection = reader.nextFloat())
                "solidInfillDirection" -> profile = profile.copy(solidInfillDirection = reader.nextFloat())
                "alignInfillDirectionToModel" -> profile = profile.copy(alignInfillDirectionToModel = reader.nextBoolean())
                "minimumSparseInfillArea" -> profile = profile.copy(minimumSparseInfillArea = reader.nextFloat())
                "infillAnchor" -> profile = profile.copy(infillAnchor = reader.nextFloat())
                "infillAnchorPercent" -> profile = profile.copy(infillAnchorPercent = reader.nextBoolean())
                "infillAnchorMax" -> profile = profile.copy(infillAnchorMax = reader.nextFloat())
                "infillAnchorMaxPercent" -> profile = profile.copy(infillAnchorMaxPercent = reader.nextBoolean())
                "gapFillTarget" -> profile = profile.copy(gapFillTarget = reader.nextString())
                "filterOutGapFill" -> profile = profile.copy(filterOutGapFill = reader.nextFloat())
                "reduceCrossingWall" -> profile = profile.copy(reduceCrossingWall = reader.nextBoolean())
                "maxTravelDetourDistance" -> profile = profile.copy(maxTravelDetourDistance = reader.nextFloat())
                "maxTravelDetourDistancePercent" -> profile = profile.copy(maxTravelDetourDistancePercent = reader.nextBoolean())
                "reduceInfillRetraction" -> profile = profile.copy(reduceInfillRetraction = reader.nextBoolean())
                "travelSpeed" -> profile = profile.copy(travelSpeed = reader.nextFloat())
                "firstLayerSpeed" -> profile = profile.copy(firstLayerSpeed = reader.nextFloat())
                "supportType" -> profile = profile.copy(supportType = reader.nextString())
                "supportAngle" -> profile = profile.copy(supportAngle = reader.nextFloat())
                "supportInterfaceTopLayers" -> profile = profile.copy(supportInterfaceTopLayers = reader.nextInt())
                "supportInterfaceBottomLayers" -> profile = profile.copy(supportInterfaceBottomLayers = reader.nextInt())
                "supportInterfaceSpacing" -> profile = profile.copy(supportInterfaceSpacing = reader.nextFloat())
                "supportBottomInterfaceSpacing" -> profile = profile.copy(supportBottomInterfaceSpacing = reader.nextFloat())
                "supportTopZDistance" -> profile = profile.copy(supportTopZDistance = reader.nextFloat())
                "supportBottomZDistance" -> profile = profile.copy(supportBottomZDistance = reader.nextFloat())
                "supportObjectXYDistance" -> profile = profile.copy(supportObjectXYDistance = reader.nextFloat())
                "supportBasePattern" -> profile = profile.copy(supportBasePattern = reader.nextString())
                "supportInterfacePattern" -> profile = profile.copy(supportInterfacePattern = reader.nextString())
                "supportStyle" -> profile = profile.copy(supportStyle = reader.nextString())
                "skirtLoops" -> profile = profile.copy(skirtLoops = reader.nextInt())
                "skirtDistance" -> profile = profile.copy(skirtDistance = reader.nextFloat())
                "outerWallLineWidth" -> profile = profile.copy(outerWallLineWidth = reader.nextFloat())
                "innerWallLineWidth" -> profile = profile.copy(innerWallLineWidth = reader.nextFloat())
                "topSurfaceLineWidth" -> profile = profile.copy(topSurfaceLineWidth = reader.nextFloat())
                "sparseInfillLineWidth" -> profile = profile.copy(sparseInfillLineWidth = reader.nextFloat())
                "internalSolidInfillLineWidth" -> profile = profile.copy(internalSolidInfillLineWidth = reader.nextFloat())
                "supportLineWidth" -> profile = profile.copy(supportLineWidth = reader.nextFloat())
                "initialLayerLineWidth" -> profile = profile.copy(initialLayerLineWidth = reader.nextFloat())
                "smallPerimeterSpeed" -> profile = profile.copy(smallPerimeterSpeed = reader.nextFloat())
                "smallPerimeterSpeedPercent" -> profile = profile.copy(smallPerimeterSpeedPercent = reader.nextBoolean())
                "smallPerimeterThreshold" -> profile = profile.copy(smallPerimeterThreshold = reader.nextFloat())
                "slowdownForCurledPerimeters" -> profile = profile.copy(slowdownForCurledPerimeters = reader.nextBoolean())
                "resolution" -> profile = profile.copy(resolution = reader.nextFloat())
                "seamPosition" -> profile = profile.copy(seamPosition = reader.nextString())
                "staggeredInnerSeams" -> profile = profile.copy(staggeredInnerSeams = reader.nextBoolean())
                "seamGap" -> profile = profile.copy(seamGap = reader.nextFloat())
                "seamGapPercent" -> profile = profile.copy(seamGapPercent = reader.nextBoolean())
                "wipeBeforeExternalLoop" -> profile = profile.copy(wipeBeforeExternalLoop = reader.nextBoolean())
                "wipeOnLoops" -> profile = profile.copy(wipeOnLoops = reader.nextBoolean())
                "roleBasedWipeSpeed" -> profile = profile.copy(roleBasedWipeSpeed = reader.nextBoolean())
                "wipeSpeed" -> profile = profile.copy(wipeSpeed = reader.nextFloat())
                "wipeSpeedPercent" -> profile = profile.copy(wipeSpeedPercent = reader.nextBoolean())
                "ironingType" -> profile = profile.copy(ironingType = reader.nextString())
                "ironingPattern" -> profile = profile.copy(ironingPattern = reader.nextString())
                "ironingFlow" -> profile = profile.copy(ironingFlow = reader.nextFloat())
                "ironingSpacing" -> profile = profile.copy(ironingSpacing = reader.nextFloat())
                "ironingSpeed" -> profile = profile.copy(ironingSpeed = reader.nextFloat())
                "wallGenerator" -> profile = profile.copy(wallGenerator = reader.nextString())
                "wallSequence" -> profile = profile.copy(wallSequence = reader.nextString())
                "wallDirection" -> profile = profile.copy(wallDirection = reader.nextString())
                "detectThinWalls" -> profile = profile.copy(detectThinWalls = reader.nextBoolean())
                "detectOverhangWalls" -> profile = profile.copy(detectOverhangWalls = reader.nextBoolean())
                "onlyOneWallOnTop" -> profile = profile.copy(onlyOneWallOnTop = reader.nextBoolean())
                "onlyOneWallFirstLayer" -> profile = profile.copy(onlyOneWallFirstLayer = reader.nextBoolean())
                "extraPerimetersOnOverhangs" -> profile = profile.copy(extraPerimetersOnOverhangs = reader.nextBoolean())
                "ensureVerticalShellThickness" -> profile = profile.copy(ensureVerticalShellThickness = reader.nextString())
                "detectNarrowInternalSolidInfill" -> profile = profile.copy(detectNarrowInternalSolidInfill = reader.nextBoolean())
                "xyHoleCompensation" -> profile = profile.copy(xyHoleCompensation = reader.nextFloat())
                "xyContourCompensation" -> profile = profile.copy(xyContourCompensation = reader.nextFloat())
                "elephantFootCompensation" -> profile = profile.copy(elephantFootCompensation = reader.nextFloat())
                "elephantFootCompensationLayers" -> profile = profile.copy(elephantFootCompensationLayers = reader.nextInt())
                "maxBridgeLength" -> profile = profile.copy(maxBridgeLength = reader.nextFloat())
                "preciseOuterWalls" -> profile = profile.copy(preciseOuterWalls = reader.nextBoolean())
                "compatiblePrinters" -> compatiblePrinters = reader.readStringList()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return profile.copy(
            id = id,
            name = name,
            brand = brand,
            builtIn = true,
            compatiblePrinters = compatiblePrinters,
        )
    }
}

private fun JsonReader.nextFloat(): Float = nextDouble().toFloat()

private fun JsonReader.readStringList(): List<String> = buildList {
    beginArray()
    while (hasNext()) add(nextString())
    endArray()
}

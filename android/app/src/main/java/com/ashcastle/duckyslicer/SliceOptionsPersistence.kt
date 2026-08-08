package com.ashcastle.duckyslicer

import org.json.JSONObject

internal fun SliceOptions.toProjectJson(): JSONObject {
    val printer = printerProfile.copy(
        bedSizeX = bedSizeX,
        bedSizeY = bedSizeY,
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
    )
    val filament = filamentProfile.copy(
        nozzleTemp = nozzleTemp,
        firstLayerNozzleTemp = firstLayerNozzleTemp,
        bedTemp = bedTemp,
        firstLayerBedTemp = firstLayerBedTemp,
        flowRatio = flowRatio,
        maxVolumetricSpeed = maxVolumetricSpeed,
        retractLength = retractLength,
        retractSpeed = retractSpeed,
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
        nozzleDiameter = nozzleDiameter,
        supportEnabled = supportEnabled,
        brimWidth = brimWidth,
        topSolidLayers = topSolidLayers,
        bottomSolidLayers = bottomSolidLayers,
        fillPattern = fillPattern,
        travelSpeed = travelSpeed,
        firstLayerSpeed = firstLayerSpeed,
        supportType = supportType,
        supportAngle = supportAngle,
        skirtLoops = skirtLoops,
        skirtDistance = skirtDistance,
        outerWallLineWidth = outerWallLineWidth,
        innerWallLineWidth = innerWallLineWidth,
        wallSequence = wallSequence,
        detectThinWalls = detectThinWalls,
        detectOverhangWalls = detectOverhangWalls,
        onlyOneWallOnTop = onlyOneWallOnTop,
        preciseOuterWalls = preciseOuterWalls,
    )
    require(ProfileValidation.printer(printer)) { "Invalid project printer settings" }
    require(ProfileValidation.filament(filament)) { "Invalid project filament settings" }
    require(ProfileValidation.slicing(slicing)) { "Invalid project slicing settings" }
    require(filamentDiameter in MIN_FILAMENT_DIAMETER..MAX_FILAMENT_DIAMETER) {
        "Invalid filament diameter"
    }
    return JSONObject()
        .put("formatVersion", SLICE_OPTIONS_FORMAT_VERSION)
        .put("filamentDiameter", filamentDiameter)
        .put("printer", printer.toProfileJson())
        .put("filament", filament.toProfileJson())
        .put("slicing", slicing.toProfileJson())
}

internal fun JSONObject.toProjectSliceOptionsOrNull(): SliceOptions? = runCatching {
    require(getInt("formatVersion") == SLICE_OPTIONS_FORMAT_VERSION) {
        "Unsupported slice settings"
    }
    val printer = requireNotNull(getJSONObject("printer").toPrinterProfileOrNull())
    val filament = requireNotNull(getJSONObject("filament").toFilamentProfileOrNull())
    val slicing = requireNotNull(getJSONObject("slicing").toQualityProfileOrNull())
    val filamentDiameter = getDouble("filamentDiameter").toFloat()
    require(ProfileValidation.printer(printer)) { "Invalid project printer settings" }
    require(ProfileValidation.filament(filament)) { "Invalid project filament settings" }
    require(ProfileValidation.slicing(slicing)) { "Invalid project slicing settings" }
    require(filamentDiameter in MIN_FILAMENT_DIAMETER..MAX_FILAMENT_DIAMETER) {
        "Invalid filament diameter"
    }
    SliceOptions(
        printerProfile = printer,
        filamentProfile = filament,
        quality = slicing,
        filamentDiameter = filamentDiameter,
    )
}.getOrNull()

private const val SLICE_OPTIONS_FORMAT_VERSION = 1
private const val MIN_FILAMENT_DIAMETER = 0.5f
private const val MAX_FILAMENT_DIAMETER = 4f

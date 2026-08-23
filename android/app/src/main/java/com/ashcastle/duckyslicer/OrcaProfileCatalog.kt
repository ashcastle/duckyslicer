package com.ashcastle.duckyslicer

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream

private const val CATALOG_ASSET = "profile_catalog_v99.bin"
private val CATALOG_MAGIC = "DUCKYPC1".toByteArray(Charsets.US_ASCII)
private const val MAX_BINARY_FIELDS = 512
private const val MAX_BINARY_RECORDS = 100_000
private const val MAX_BINARY_STRING_BYTES = 16 * 1024 * 1024

internal const val BINARY_STRING = 1
internal const val BINARY_FLOAT = 2
internal const val BINARY_INT = 3
internal const val BINARY_BOOL = 4
internal const val BINARY_STRING_LIST = 5
internal const val BINARY_FLOAT_LIST = 6
internal const val BINARY_NULLABLE_FLOAT = 7
internal const val BINARY_NULLABLE_BOOL = 8
internal const val BINARY_NULLABLE_STRING = 9

internal data class BinaryField(val name: String, val kind: Int)

class OrcaProfileCatalog(private val context: Context) {
    fun load(): ProfileCatalog = runCatching {
        context.assets.open(CATALOG_ASSET).use { asset ->
            DataInputStream(BufferedInputStream(asset, 64 * 1024)).use(::readCatalog)
        }
    }.getOrElse { ProfileCatalog() }

    private fun readCatalog(input: DataInputStream): ProfileCatalog {
        val magic = ByteArray(CATALOG_MAGIC.size)
        input.readFully(magic)
        check(magic.contentEquals(CATALOG_MAGIC)) { "Invalid profile catalog header" }
        val schemaVersion = input.readInt()
        check(schemaVersion == 99) { "Unsupported profile catalog schema" }
        val sourceRevision = input.readCatalogString()
        val rejectedCount = input.readBoundedCount(MAX_BINARY_RECORDS, "rejected profiles")
        val printers = input.readSection(PRINTER_BINARY_FIELDS, ::readPrinter)
        val filaments = input.readSection(FILAMENT_BINARY_FIELDS, ::readFilament)
        val slicing = input.readSection(QUALITY_BINARY_FIELDS, ::readQuality)
        check(printers.all(ProfileValidation::printer)) { "Invalid printer profile in catalog" }
        check(filaments.all(ProfileValidation::filament)) { "Invalid filament profile in catalog" }
        check(slicing.all(ProfileValidation::slicing)) { "Invalid slicing profile in catalog" }
        check(input.read() == -1) { "Unexpected trailing profile catalog data" }
        return ProfileCatalog(
            printers = (PrinterProfile.builtIns + printers).distinctBy(PrinterProfile::id),
            filaments = (FilamentProfile.builtIns + filaments).distinctBy(FilamentProfile::id),
            slicing = (QualityProfile.builtIns + slicing).distinctBy(QualityProfile::id),
            schemaVersion = schemaVersion,
            sourceRevision = sourceRevision,
            rejectedCount = rejectedCount,
        )
    }

    private fun readPrinter(input: DataInputStream): PrinterProfile = PrinterProfile(
        id = input.readCatalogString(),
        name = input.readCatalogString(),
        brand = input.readCatalogString(),
        bedSizeX = input.readFloat(),
        bedSizeY = input.readFloat(),
        bedOriginX = input.readFloat(),
        bedOriginY = input.readFloat(),
        bedPolygon = input.readCatalogFloatList(),
        bedExcludeArea = input.readCatalogFloatList(),
        maxPrintHeight = input.readFloat(),
        nozzleDiameter = input.readFloat(),
        nozzleMaterial = checkNotNull(
            NozzleMaterial.fromStorage(input.readCatalogString()),
        ) { "Unsupported nozzle material" },
        nozzleHrc = input.readInt(),
        nozzleHeight = input.readFloat(),
        nozzleVolume = input.readFloat(),
        minLayerHeight = input.readFloat(),
        maxLayerHeight = input.readFloat(),
        singleExtruderMultiMaterial = input.readCatalogBoolean(),
        coolingTubeRetraction = input.readFloat(),
        coolingTubeLength = input.readFloat(),
        parkingPosRetraction = input.readFloat(),
        extraLoadingMove = input.readFloat(),
        enableFilamentRamming = input.readCatalogBoolean(),
        purgeInPrimeTower = input.readCatalogBoolean(),
        highCurrentOnFilamentSwap = input.readCatalogBoolean(),
        extruderCount = input.readInt(),
        auxiliaryFan = input.readCatalogBoolean(),
        fanSpeedupTime = input.readFloat(),
        fanSpeedupOverhangs = input.readCatalogBoolean(),
        fanKickstart = input.readFloat(),
        supportsChamberTemperatureControl = input.readCatalogBoolean(),
        supportsAirFiltration = input.readCatalogBoolean(),
        scanFirstLayer = input.readCatalogBoolean(),
        bedMeshMinX = input.readFloat(),
        bedMeshMinY = input.readFloat(),
        bedMeshMaxX = input.readFloat(),
        bedMeshMaxY = input.readFloat(),
        bedMeshProbeDistanceX = input.readFloat(),
        bedMeshProbeDistanceY = input.readFloat(),
        adaptiveBedMeshMargin = input.readFloat(),
        gcodeThumbnails = input.readCatalogString(),
        machineStartGcode = input.readCatalogString(),
        machineEndGcode = input.readCatalogString(),
        machinePauseGcode = input.readCatalogString(),
        timeLapseGcode = input.readCatalogString(),
        beforeLayerChangeGcode = input.readCatalogString(),
        layerChangeGcode = input.readCatalogString(),
        changeFilamentGcode = input.readCatalogString(),
        printingByObjectGcode = input.readCatalogString(),
        useRelativeEDistances = input.readCatalogBoolean(),
        emitMachineLimitsToGcode = input.readCatalogBoolean(),
        manualFilamentChange = input.readCatalogBoolean(),
        disableM73 = input.readCatalogBoolean(),
        machineLoadFilamentTime = input.readFloat(),
        machineUnloadFilamentTime = input.readFloat(),
        machineToolChangeTime = input.readFloat(),
        toolChangeTemperatureWait = input.readCatalogBoolean(),
        gcodeFlavor = input.readCatalogString(),
        maxSpeedX = input.readFloat(),
        maxSpeedY = input.readFloat(),
        maxSpeedZ = input.readFloat(),
        maxSpeedE = input.readFloat(),
        maxAccelerationX = input.readFloat(),
        maxAccelerationY = input.readFloat(),
        maxAccelerationZ = input.readFloat(),
        maxAccelerationE = input.readFloat(),
        maxAccelerationExtruding = input.readFloat(),
        maxAccelerationRetracting = input.readFloat(),
        maxAccelerationTravel = input.readFloat(),
        maxJerkX = input.readFloat(),
        maxJerkY = input.readFloat(),
        maxJerkZ = input.readFloat(),
        maxJerkE = input.readFloat(),
        maxJunctionDeviation = input.readFloat(),
        retractLength = input.readFloat(),
        retractSpeed = input.readFloat(),
        deretractSpeed = input.readFloat(),
        retractionMinimumTravel = input.readFloat(),
        retractWhenChangingLayer = input.readCatalogBoolean(),
        wipeWhileRetracting = input.readCatalogBoolean(),
        wipeDistance = input.readFloat(),
        retractBeforeWipe = input.readFloat(),
        retractRestartExtra = input.readFloat(),
        extruderOffsetsX = input.readCatalogFloatList(),
        extruderOffsetsY = input.readCatalogFloatList(),
        toolChangeRetractLengths = input.readCatalogFloatList(),
        toolChangeRetractRestartExtras = input.readCatalogFloatList(),
        zHop = input.readFloat(),
        zHopType = input.readCatalogString(),
        retractLiftAbove = input.readFloat(),
        retractLiftBelow = input.readFloat(),
        retractLiftEnforce = input.readCatalogString(),
        travelSlope = input.readFloat(),
        zHopWhenPrime = input.readCatalogBoolean(),
        useFirmwareRetraction = input.readCatalogBoolean(),
        longRetractionWhenCutLevel = input.readInt(),
        longRetractionWhenCut = input.readCatalogBoolean(),
        retractionDistanceWhenCut = input.readFloat(),
        extruderClearanceRadius = input.readFloat(),
        extruderClearanceHeightToRod = input.readFloat(),
        extruderClearanceHeightToLid = input.readFloat(),
        defaultPrintProfile = input.readCatalogString(),
        defaultFilamentProfiles = input.readCatalogStringList(),
        builtIn = true,
    )

    private fun readFilament(input: DataInputStream): FilamentProfile = FilamentProfile(
        id = input.readCatalogString(),
        name = input.readCatalogString(),
        brand = input.readCatalogString(),
        nativeName = input.readCatalogString(),
        nozzleTemp = input.readInt(),
        firstLayerNozzleTemp = input.readInt(),
        idleTemperature = input.readInt(),
        bedTemp = input.readInt(),
        firstLayerBedTemp = input.readInt(),
        texturedPlateTemp = input.readInt(),
        firstLayerTexturedPlateTemp = input.readInt(),
        engineeringPlateTemp = input.readInt(),
        firstLayerEngineeringPlateTemp = input.readInt(),
        coolPlateTemp = input.readInt(),
        firstLayerCoolPlateTemp = input.readInt(),
        texturedCoolPlateTemp = input.readInt(),
        firstLayerTexturedCoolPlateTemp = input.readInt(),
        superTackPlateTemp = input.readInt(),
        firstLayerSuperTackPlateTemp = input.readInt(),
        graphicEffectPlateTemp = input.readInt(),
        firstLayerGraphicEffectPlateTemp = input.readInt(),
        flowRatio = input.readFloat(),
        maxVolumetricSpeed = input.readFloat(),
        diameter = input.readFloat(),
        density = input.readFloat(),
        costPerKilogram = input.readFloat(),
        shrinkageXyPercent = input.readFloat(),
        shrinkageZPercent = input.readFloat(),
        soluble = input.readBoolean(),
        supportMaterial = input.readBoolean(),
        minimalPurgeOnWipeTower = input.readFloat(),
        towerInterfacePreExtrusionDistance = input.readFloat(),
        towerInterfacePreExtrusionLength = input.readFloat(),
        towerIroningArea = input.readFloat(),
        towerInterfacePurgeLength = input.readFloat(),
        towerInterfacePrintTemperature = input.readInt(),
        additionalCoolingFanSpeed = input.readInt(),
        loadingSpeed = input.readFloat(),
        loadingSpeedStart = input.readFloat(),
        unloadingSpeed = input.readFloat(),
        unloadingSpeedStart = input.readFloat(),
        toolchangeDelay = input.readFloat(),
        coolingMoves = input.readInt(),
        stampingLoadingSpeed = input.readFloat(),
        stampingDistance = input.readFloat(),
        coolingInitialSpeed = input.readFloat(),
        coolingFinalSpeed = input.readFloat(),
        rammingParameters = input.readCatalogString(),
        multitoolRamming = input.readCatalogBoolean(),
        multitoolRammingVolume = input.readFloat(),
        multitoolRammingFlow = input.readFloat(),
        softeningTemperature = input.readInt(),
        nozzleTemperatureRangeLow = input.readInt(),
        nozzleTemperatureRangeHigh = input.readInt(),
        chamberTemperatureControl = input.readCatalogBoolean(),
        chamberTemperature = input.readInt(),
        airFiltration = input.readCatalogBoolean(),
        duringPrintExhaustFanSpeed = input.readInt(),
        completePrintExhaustFanSpeed = input.readInt(),
        filamentStartGcode = input.readCatalogString(),
        filamentEndGcode = input.readCatalogString(),
        retractLength = input.readCatalogNullableFloat(),
        retractSpeed = input.readCatalogNullableFloat(),
        deretractSpeed = input.readCatalogNullableFloat(),
        retractionMinimumTravel = input.readCatalogNullableFloat(),
        retractWhenChangingLayer = input.readCatalogNullableBoolean(),
        wipeWhileRetracting = input.readCatalogNullableBoolean(),
        wipeDistance = input.readCatalogNullableFloat(),
        retractBeforeWipe = input.readCatalogNullableFloat(),
        retractRestartExtra = input.readCatalogNullableFloat(),
        zHop = input.readCatalogNullableFloat(),
        zHopType = input.readCatalogNullableString(),
        retractLiftAbove = input.readCatalogNullableFloat(),
        retractLiftBelow = input.readCatalogNullableFloat(),
        retractLiftEnforce = input.readCatalogNullableString(),
        longRetractionWhenCut = input.readCatalogNullableBoolean(),
        retractionDistanceWhenCut = input.readCatalogNullableFloat(),
        fanMinSpeed = input.readInt(),
        fanMaxSpeed = input.readInt(),
        fanCoolingLayerTime = input.readFloat(),
        slowDownForLayerCooling = input.readCatalogBoolean(),
        keepFanAlwaysOn = input.readCatalogBoolean(),
        dontSlowDownOuterWall = input.readCatalogBoolean(),
        enableOverhangBridgeFan = input.readCatalogBoolean(),
        overhangFanSpeed = input.readInt(),
        overhangFanThreshold = input.readCatalogString(),
        internalBridgeFanSpeed = input.readInt(),
        supportInterfaceFanSpeed = input.readInt(),
        slowDownLayerTime = input.readFloat(),
        slowDownMinSpeed = input.readFloat(),
        closeFanFirstLayers = input.readInt(),
        fullFanSpeedLayer = input.readInt(),
        pressureAdvanceEnabled = input.readCatalogBoolean(),
        pressureAdvance = input.readFloat(),
        adaptivePressureAdvance = AdaptivePressureAdvanceSettings(
            enabled = input.readCatalogBoolean(),
            model = input.readCatalogString(),
            overhangs = input.readCatalogBoolean(),
            bridge = input.readFloat(),
        ),
        requiredNozzleHrc = input.readInt(),
        compatiblePrinters = input.readCatalogStringList(),
        builtIn = true,
    )

    private fun readQuality(input: DataInputStream): QualityProfile =
        QualityProfileBinaryBuilder().apply { read(input) }.build()
}

private inline fun <T> DataInputStream.readSection(
    expectedFields: Array<BinaryField>,
    readRecord: (DataInputStream) -> T,
): List<T> {
    val fieldCount = readBoundedCount(MAX_BINARY_FIELDS, "section fields")
    check(fieldCount == expectedFields.size) { "Profile catalog field count changed" }
    repeat(fieldCount) { index ->
        val actual = BinaryField(readCatalogString(), readUnsignedByte())
        check(actual == expectedFields[index]) {
            "Profile catalog field mismatch at $index: $actual"
        }
    }
    val recordCount = readBoundedCount(MAX_BINARY_RECORDS, "section records")
    return List(recordCount) { readRecord(this) }
}

internal fun DataInputStream.readCatalogString(): String {
    val length = readBoundedCount(MAX_BINARY_STRING_BYTES, "string bytes")
    val bytes = ByteArray(length)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

internal fun DataInputStream.readCatalogBoolean(): Boolean = when (val value = readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> error("Invalid profile catalog boolean: $value")
}

private fun DataInputStream.readCatalogPresence(): Boolean = when (val value = readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> error("Invalid nullable field marker: $value")
}

internal fun DataInputStream.readCatalogNullableFloat(): Float? =
    if (readCatalogPresence()) readFloat() else null

internal fun DataInputStream.readCatalogNullableBoolean(): Boolean? =
    if (readCatalogPresence()) readCatalogBoolean() else null

internal fun DataInputStream.readCatalogNullableString(): String? =
    if (readCatalogPresence()) readCatalogString() else null

internal fun DataInputStream.readCatalogStringList(): List<String> {
    val count = readBoundedCount(MAX_BINARY_RECORDS, "string list")
    return List(count) { readCatalogString() }
}

internal fun DataInputStream.readCatalogFloatList(): List<Float> {
    val count = readBoundedCount(MAX_BED_POLYGON_COORDINATES, "bed polygon coordinates")
    return List(count) { readFloat() }
}

private fun DataInputStream.readBoundedCount(maximum: Int, label: String): Int {
    val value = readInt()
    check(value in 0..maximum) { "Invalid $label count: $value" }
    return value
}

private val PRINTER_BINARY_FIELDS = arrayOf(
    BinaryField("id", BINARY_STRING),
    BinaryField("name", BINARY_STRING),
    BinaryField("brand", BINARY_STRING),
    BinaryField("bedSizeX", BINARY_FLOAT),
    BinaryField("bedSizeY", BINARY_FLOAT),
    BinaryField("bedOriginX", BINARY_FLOAT),
    BinaryField("bedOriginY", BINARY_FLOAT),
    BinaryField("bedPolygon", BINARY_FLOAT_LIST),
    BinaryField("bedExcludeArea", BINARY_FLOAT_LIST),
    BinaryField("maxPrintHeight", BINARY_FLOAT),
    BinaryField("nozzleDiameter", BINARY_FLOAT),
    BinaryField("nozzleMaterial", BINARY_STRING),
    BinaryField("nozzleHrc", BINARY_INT),
    BinaryField("nozzleHeight", BINARY_FLOAT),
    BinaryField("nozzleVolume", BINARY_FLOAT),
    BinaryField("minLayerHeight", BINARY_FLOAT),
    BinaryField("maxLayerHeight", BINARY_FLOAT),
    BinaryField("singleExtruderMultiMaterial", BINARY_BOOL),
    BinaryField("coolingTubeRetraction", BINARY_FLOAT),
    BinaryField("coolingTubeLength", BINARY_FLOAT),
    BinaryField("parkingPosRetraction", BINARY_FLOAT),
    BinaryField("extraLoadingMove", BINARY_FLOAT),
    BinaryField("enableFilamentRamming", BINARY_BOOL),
    BinaryField("purgeInPrimeTower", BINARY_BOOL),
    BinaryField("highCurrentOnFilamentSwap", BINARY_BOOL),
    BinaryField("extruderCount", BINARY_INT),
    BinaryField("auxiliaryFan", BINARY_BOOL),
    BinaryField("fanSpeedupTime", BINARY_FLOAT),
    BinaryField("fanSpeedupOverhangs", BINARY_BOOL),
    BinaryField("fanKickstart", BINARY_FLOAT),
    BinaryField("supportsChamberTemperatureControl", BINARY_BOOL),
    BinaryField("supportsAirFiltration", BINARY_BOOL),
    BinaryField("scanFirstLayer", BINARY_BOOL),
    BinaryField("bedMeshMinX", BINARY_FLOAT),
    BinaryField("bedMeshMinY", BINARY_FLOAT),
    BinaryField("bedMeshMaxX", BINARY_FLOAT),
    BinaryField("bedMeshMaxY", BINARY_FLOAT),
    BinaryField("bedMeshProbeDistanceX", BINARY_FLOAT),
    BinaryField("bedMeshProbeDistanceY", BINARY_FLOAT),
    BinaryField("adaptiveBedMeshMargin", BINARY_FLOAT),
    BinaryField("gcodeThumbnails", BINARY_STRING),
    BinaryField("machineStartGcode", BINARY_STRING),
    BinaryField("machineEndGcode", BINARY_STRING),
    BinaryField("machinePauseGcode", BINARY_STRING),
    BinaryField("timeLapseGcode", BINARY_STRING),
    BinaryField("beforeLayerChangeGcode", BINARY_STRING),
    BinaryField("layerChangeGcode", BINARY_STRING),
    BinaryField("changeFilamentGcode", BINARY_STRING),
    BinaryField("printingByObjectGcode", BINARY_STRING),
    BinaryField("useRelativeEDistances", BINARY_BOOL),
    BinaryField("emitMachineLimitsToGcode", BINARY_BOOL),
    BinaryField("manualFilamentChange", BINARY_BOOL),
    BinaryField("disableM73", BINARY_BOOL),
    BinaryField("machineLoadFilamentTime", BINARY_FLOAT),
    BinaryField("machineUnloadFilamentTime", BINARY_FLOAT),
    BinaryField("machineToolChangeTime", BINARY_FLOAT),
    BinaryField("toolChangeTemperatureWait", BINARY_BOOL),
    BinaryField("gcodeFlavor", BINARY_STRING),
    BinaryField("maxSpeedX", BINARY_FLOAT),
    BinaryField("maxSpeedY", BINARY_FLOAT),
    BinaryField("maxSpeedZ", BINARY_FLOAT),
    BinaryField("maxSpeedE", BINARY_FLOAT),
    BinaryField("maxAccelerationX", BINARY_FLOAT),
    BinaryField("maxAccelerationY", BINARY_FLOAT),
    BinaryField("maxAccelerationZ", BINARY_FLOAT),
    BinaryField("maxAccelerationE", BINARY_FLOAT),
    BinaryField("maxAccelerationExtruding", BINARY_FLOAT),
    BinaryField("maxAccelerationRetracting", BINARY_FLOAT),
    BinaryField("maxAccelerationTravel", BINARY_FLOAT),
    BinaryField("maxJerkX", BINARY_FLOAT),
    BinaryField("maxJerkY", BINARY_FLOAT),
    BinaryField("maxJerkZ", BINARY_FLOAT),
    BinaryField("maxJerkE", BINARY_FLOAT),
    BinaryField("maxJunctionDeviation", BINARY_FLOAT),
    BinaryField("retractLength", BINARY_FLOAT),
    BinaryField("retractSpeed", BINARY_FLOAT),
    BinaryField("deretractSpeed", BINARY_FLOAT),
    BinaryField("retractionMinimumTravel", BINARY_FLOAT),
    BinaryField("retractWhenChangingLayer", BINARY_BOOL),
    BinaryField("wipeWhileRetracting", BINARY_BOOL),
    BinaryField("wipeDistance", BINARY_FLOAT),
    BinaryField("retractBeforeWipe", BINARY_FLOAT),
    BinaryField("retractRestartExtra", BINARY_FLOAT),
    BinaryField("extruderOffsetsX", BINARY_FLOAT_LIST),
    BinaryField("extruderOffsetsY", BINARY_FLOAT_LIST),
    BinaryField("toolChangeRetractLengths", BINARY_FLOAT_LIST),
    BinaryField("toolChangeRetractRestartExtras", BINARY_FLOAT_LIST),
    BinaryField("zHop", BINARY_FLOAT),
    BinaryField("zHopType", BINARY_STRING),
    BinaryField("retractLiftAbove", BINARY_FLOAT),
    BinaryField("retractLiftBelow", BINARY_FLOAT),
    BinaryField("retractLiftEnforce", BINARY_STRING),
    BinaryField("travelSlope", BINARY_FLOAT),
    BinaryField("zHopWhenPrime", BINARY_BOOL),
    BinaryField("useFirmwareRetraction", BINARY_BOOL),
    BinaryField("longRetractionWhenCutLevel", BINARY_INT),
    BinaryField("longRetractionWhenCut", BINARY_BOOL),
    BinaryField("retractionDistanceWhenCut", BINARY_FLOAT),
    BinaryField("extruderClearanceRadius", BINARY_FLOAT),
    BinaryField("extruderClearanceHeightToRod", BINARY_FLOAT),
    BinaryField("extruderClearanceHeightToLid", BINARY_FLOAT),
    BinaryField("defaultPrintProfile", BINARY_STRING),
    BinaryField("defaultFilamentProfiles", BINARY_STRING_LIST),
)

private const val MAX_BED_POLYGON_COORDINATES = 512

private val FILAMENT_BINARY_FIELDS = arrayOf(
    BinaryField("id", BINARY_STRING),
    BinaryField("name", BINARY_STRING),
    BinaryField("brand", BINARY_STRING),
    BinaryField("nativeName", BINARY_STRING),
    BinaryField("nozzleTemp", BINARY_INT),
    BinaryField("firstLayerNozzleTemp", BINARY_INT),
    BinaryField("idleTemperature", BINARY_INT),
    BinaryField("bedTemp", BINARY_INT),
    BinaryField("firstLayerBedTemp", BINARY_INT),
    BinaryField("texturedPlateTemp", BINARY_INT),
    BinaryField("firstLayerTexturedPlateTemp", BINARY_INT),
    BinaryField("engineeringPlateTemp", BINARY_INT),
    BinaryField("firstLayerEngineeringPlateTemp", BINARY_INT),
    BinaryField("coolPlateTemp", BINARY_INT),
    BinaryField("firstLayerCoolPlateTemp", BINARY_INT),
    BinaryField("texturedCoolPlateTemp", BINARY_INT),
    BinaryField("firstLayerTexturedCoolPlateTemp", BINARY_INT),
    BinaryField("superTackPlateTemp", BINARY_INT),
    BinaryField("firstLayerSuperTackPlateTemp", BINARY_INT),
    BinaryField("graphicEffectPlateTemp", BINARY_INT),
    BinaryField("firstLayerGraphicEffectPlateTemp", BINARY_INT),
    BinaryField("flowRatio", BINARY_FLOAT),
    BinaryField("maxVolumetricSpeed", BINARY_FLOAT),
    BinaryField("diameter", BINARY_FLOAT),
    BinaryField("density", BINARY_FLOAT),
    BinaryField("costPerKilogram", BINARY_FLOAT),
    BinaryField("shrinkageXyPercent", BINARY_FLOAT),
    BinaryField("shrinkageZPercent", BINARY_FLOAT),
    BinaryField("soluble", BINARY_BOOL),
    BinaryField("supportMaterial", BINARY_BOOL),
    BinaryField("minimalPurgeOnWipeTower", BINARY_FLOAT),
    BinaryField("towerInterfacePreExtrusionDistance", BINARY_FLOAT),
    BinaryField("towerInterfacePreExtrusionLength", BINARY_FLOAT),
    BinaryField("towerIroningArea", BINARY_FLOAT),
    BinaryField("towerInterfacePurgeLength", BINARY_FLOAT),
    BinaryField("towerInterfacePrintTemperature", BINARY_INT),
    BinaryField("additionalCoolingFanSpeed", BINARY_INT),
    BinaryField("loadingSpeed", BINARY_FLOAT),
    BinaryField("loadingSpeedStart", BINARY_FLOAT),
    BinaryField("unloadingSpeed", BINARY_FLOAT),
    BinaryField("unloadingSpeedStart", BINARY_FLOAT),
    BinaryField("toolchangeDelay", BINARY_FLOAT),
    BinaryField("coolingMoves", BINARY_INT),
    BinaryField("stampingLoadingSpeed", BINARY_FLOAT),
    BinaryField("stampingDistance", BINARY_FLOAT),
    BinaryField("coolingInitialSpeed", BINARY_FLOAT),
    BinaryField("coolingFinalSpeed", BINARY_FLOAT),
    BinaryField("rammingParameters", BINARY_STRING),
    BinaryField("multitoolRamming", BINARY_BOOL),
    BinaryField("multitoolRammingVolume", BINARY_FLOAT),
    BinaryField("multitoolRammingFlow", BINARY_FLOAT),
    BinaryField("softeningTemperature", BINARY_INT),
    BinaryField("nozzleTemperatureRangeLow", BINARY_INT),
    BinaryField("nozzleTemperatureRangeHigh", BINARY_INT),
    BinaryField("chamberTemperatureControl", BINARY_BOOL),
    BinaryField("chamberTemperature", BINARY_INT),
    BinaryField("airFiltration", BINARY_BOOL),
    BinaryField("duringPrintExhaustFanSpeed", BINARY_INT),
    BinaryField("completePrintExhaustFanSpeed", BINARY_INT),
    BinaryField("filamentStartGcode", BINARY_STRING),
    BinaryField("filamentEndGcode", BINARY_STRING),
    BinaryField("retractLength", BINARY_NULLABLE_FLOAT),
    BinaryField("retractSpeed", BINARY_NULLABLE_FLOAT),
    BinaryField("deretractSpeed", BINARY_NULLABLE_FLOAT),
    BinaryField("retractionMinimumTravel", BINARY_NULLABLE_FLOAT),
    BinaryField("retractWhenChangingLayer", BINARY_NULLABLE_BOOL),
    BinaryField("wipeWhileRetracting", BINARY_NULLABLE_BOOL),
    BinaryField("wipeDistance", BINARY_NULLABLE_FLOAT),
    BinaryField("retractBeforeWipe", BINARY_NULLABLE_FLOAT),
    BinaryField("retractRestartExtra", BINARY_NULLABLE_FLOAT),
    BinaryField("zHop", BINARY_NULLABLE_FLOAT),
    BinaryField("zHopType", BINARY_NULLABLE_STRING),
    BinaryField("retractLiftAbove", BINARY_NULLABLE_FLOAT),
    BinaryField("retractLiftBelow", BINARY_NULLABLE_FLOAT),
    BinaryField("retractLiftEnforce", BINARY_NULLABLE_STRING),
    BinaryField("longRetractionWhenCut", BINARY_NULLABLE_BOOL),
    BinaryField("retractionDistanceWhenCut", BINARY_NULLABLE_FLOAT),
    BinaryField("fanMinSpeed", BINARY_INT),
    BinaryField("fanMaxSpeed", BINARY_INT),
    BinaryField("fanCoolingLayerTime", BINARY_FLOAT),
    BinaryField("slowDownForLayerCooling", BINARY_BOOL),
    BinaryField("keepFanAlwaysOn", BINARY_BOOL),
    BinaryField("dontSlowDownOuterWall", BINARY_BOOL),
    BinaryField("enableOverhangBridgeFan", BINARY_BOOL),
    BinaryField("overhangFanSpeed", BINARY_INT),
    BinaryField("overhangFanThreshold", BINARY_STRING),
    BinaryField("internalBridgeFanSpeed", BINARY_INT),
    BinaryField("supportInterfaceFanSpeed", BINARY_INT),
    BinaryField("slowDownLayerTime", BINARY_FLOAT),
    BinaryField("slowDownMinSpeed", BINARY_FLOAT),
    BinaryField("closeFanFirstLayers", BINARY_INT),
    BinaryField("fullFanSpeedLayer", BINARY_INT),
    BinaryField("pressureAdvanceEnabled", BINARY_BOOL),
    BinaryField("pressureAdvance", BINARY_FLOAT),
    BinaryField("adaptivePressureAdvanceEnabled", BINARY_BOOL),
    BinaryField("adaptivePressureAdvanceModel", BINARY_STRING),
    BinaryField("adaptivePressureAdvanceOverhangs", BINARY_BOOL),
    BinaryField("adaptivePressureAdvanceBridge", BINARY_FLOAT),
    BinaryField("requiredNozzleHrc", BINARY_INT),
    BinaryField("compatiblePrinters", BINARY_STRING_LIST),
)

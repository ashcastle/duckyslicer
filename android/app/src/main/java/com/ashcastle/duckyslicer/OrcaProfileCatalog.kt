package com.ashcastle.duckyslicer

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream

private const val CATALOG_ASSET = "profile_catalog_v60.bin"
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
        check(schemaVersion == 60) { "Unsupported profile catalog schema" }
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
        maxPrintHeight = input.readFloat(),
        nozzleDiameter = input.readFloat(),
        singleExtruderMultiMaterial = input.readCatalogBoolean(),
        extruderCount = input.readInt(),
        auxiliaryFan = input.readCatalogBoolean(),
        machineStartGcode = input.readCatalogString(),
        machineEndGcode = input.readCatalogString(),
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
        retractLength = input.readFloat(),
        retractSpeed = input.readFloat(),
        deretractSpeed = input.readFloat(),
        retractionMinimumTravel = input.readFloat(),
        retractWhenChangingLayer = input.readCatalogBoolean(),
        wipeWhileRetracting = input.readCatalogBoolean(),
        wipeDistance = input.readFloat(),
        retractBeforeWipe = input.readFloat(),
        retractRestartExtra = input.readFloat(),
        zHop = input.readFloat(),
        zHopType = input.readCatalogString(),
        extruderClearanceRadius = input.readFloat(),
        extruderClearanceHeightToRod = input.readFloat(),
        extruderClearanceHeightToLid = input.readFloat(),
        builtIn = true,
    )

    private fun readFilament(input: DataInputStream): FilamentProfile = FilamentProfile(
        id = input.readCatalogString(),
        name = input.readCatalogString(),
        brand = input.readCatalogString(),
        nativeName = input.readCatalogString(),
        nozzleTemp = input.readInt(),
        firstLayerNozzleTemp = input.readInt(),
        bedTemp = input.readInt(),
        firstLayerBedTemp = input.readInt(),
        flowRatio = input.readFloat(),
        maxVolumetricSpeed = input.readFloat(),
        diameter = input.readFloat(),
        density = input.readFloat(),
        costPerKilogram = input.readFloat(),
        soluble = input.readBoolean(),
        supportMaterial = input.readBoolean(),
        minimalPurgeOnWipeTower = input.readFloat(),
        additionalCoolingFanSpeed = input.readInt(),
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
        fanMinSpeed = input.readInt(),
        fanMaxSpeed = input.readInt(),
        overhangFanSpeed = input.readInt(),
        slowDownLayerTime = input.readFloat(),
        slowDownMinSpeed = input.readFloat(),
        closeFanFirstLayers = input.readInt(),
        fullFanSpeedLayer = input.readInt(),
        pressureAdvanceEnabled = input.readCatalogBoolean(),
        pressureAdvance = input.readFloat(),
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
    BinaryField("maxPrintHeight", BINARY_FLOAT),
    BinaryField("nozzleDiameter", BINARY_FLOAT),
    BinaryField("singleExtruderMultiMaterial", BINARY_BOOL),
    BinaryField("extruderCount", BINARY_INT),
    BinaryField("auxiliaryFan", BINARY_BOOL),
    BinaryField("machineStartGcode", BINARY_STRING),
    BinaryField("machineEndGcode", BINARY_STRING),
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
    BinaryField("retractLength", BINARY_FLOAT),
    BinaryField("retractSpeed", BINARY_FLOAT),
    BinaryField("deretractSpeed", BINARY_FLOAT),
    BinaryField("retractionMinimumTravel", BINARY_FLOAT),
    BinaryField("retractWhenChangingLayer", BINARY_BOOL),
    BinaryField("wipeWhileRetracting", BINARY_BOOL),
    BinaryField("wipeDistance", BINARY_FLOAT),
    BinaryField("retractBeforeWipe", BINARY_FLOAT),
    BinaryField("retractRestartExtra", BINARY_FLOAT),
    BinaryField("zHop", BINARY_FLOAT),
    BinaryField("zHopType", BINARY_STRING),
    BinaryField("extruderClearanceRadius", BINARY_FLOAT),
    BinaryField("extruderClearanceHeightToRod", BINARY_FLOAT),
    BinaryField("extruderClearanceHeightToLid", BINARY_FLOAT),
)

private const val MAX_BED_POLYGON_COORDINATES = 512

private val FILAMENT_BINARY_FIELDS = arrayOf(
    BinaryField("id", BINARY_STRING),
    BinaryField("name", BINARY_STRING),
    BinaryField("brand", BINARY_STRING),
    BinaryField("nativeName", BINARY_STRING),
    BinaryField("nozzleTemp", BINARY_INT),
    BinaryField("firstLayerNozzleTemp", BINARY_INT),
    BinaryField("bedTemp", BINARY_INT),
    BinaryField("firstLayerBedTemp", BINARY_INT),
    BinaryField("flowRatio", BINARY_FLOAT),
    BinaryField("maxVolumetricSpeed", BINARY_FLOAT),
    BinaryField("diameter", BINARY_FLOAT),
    BinaryField("density", BINARY_FLOAT),
    BinaryField("costPerKilogram", BINARY_FLOAT),
    BinaryField("soluble", BINARY_BOOL),
    BinaryField("supportMaterial", BINARY_BOOL),
    BinaryField("minimalPurgeOnWipeTower", BINARY_FLOAT),
    BinaryField("additionalCoolingFanSpeed", BINARY_INT),
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
    BinaryField("fanMinSpeed", BINARY_INT),
    BinaryField("fanMaxSpeed", BINARY_INT),
    BinaryField("overhangFanSpeed", BINARY_INT),
    BinaryField("slowDownLayerTime", BINARY_FLOAT),
    BinaryField("slowDownMinSpeed", BINARY_FLOAT),
    BinaryField("closeFanFirstLayers", BINARY_INT),
    BinaryField("fullFanSpeedLayer", BINARY_INT),
    BinaryField("pressureAdvanceEnabled", BINARY_BOOL),
    BinaryField("pressureAdvance", BINARY_FLOAT),
    BinaryField("compatiblePrinters", BINARY_STRING_LIST),
)

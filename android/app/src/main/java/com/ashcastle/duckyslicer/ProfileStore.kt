package com.ashcastle.duckyslicer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Stores schema-versioned user profiles in app-private storage. */
class ProfileStore private constructor(
    private val file: File,
    private val systemCatalogProvider: () -> ProfileCatalog,
) {
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
        )
        require(ProfileValidation.printer(profile)) { "Printer profile contains unsafe values" }
        append("printers", profile.toJson())
        return profile
    }

    @Synchronized
    fun saveFilament(name: String, options: SliceOptions): FilamentProfile {
        val profile = FilamentProfile(
            id = userId(),
            name = requireName(name),
            nativeName = options.filamentProfile.nativeName,
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
        require(ProfileValidation.filament(profile)) { "Filament profile contains unsafe values" }
        append("filaments", profile.toJson())
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
            supportEnabled = options.supportEnabled,
            brimWidth = options.brimWidth,
            topSolidLayers = options.topSolidLayers,
            bottomSolidLayers = options.bottomSolidLayers,
            fillPattern = options.fillPattern,
            travelSpeed = options.travelSpeed,
            firstLayerSpeed = options.firstLayerSpeed,
            supportType = options.supportType,
            supportAngle = options.supportAngle,
            skirtLoops = options.skirtLoops,
            skirtDistance = options.skirtDistance,
            outerWallLineWidth = options.outerWallLineWidth,
            innerWallLineWidth = options.innerWallLineWidth,
            wallSequence = options.wallSequence,
            detectThinWalls = options.detectThinWalls,
            detectOverhangWalls = options.detectOverhangWalls,
            onlyOneWallOnTop = options.onlyOneWallOnTop,
            preciseOuterWalls = options.preciseOuterWalls,
        )
        require(ProfileValidation.slicing(profile)) { "Slicing profile contains unsafe values" }
        append("slicing", profile.toJson())
        return profile
    }

    private fun append(key: String, value: JSONObject) {
        val root = readRoot()
        root.put("schemaVersion", USER_PROFILE_SCHEMA_VERSION)
        val values = root.optJSONArray(key) ?: JSONArray().also { root.put(key, it) }
        values.put(value)
        writeRoot(root)
    }

    private fun readRoot(): JSONObject = runCatching {
        val root = if (file.isFile) JSONObject(file.readText()) else JSONObject()
        if (root.optInt("schemaVersion", 1) > USER_PROFILE_SCHEMA_VERSION) JSONObject() else root
    }.getOrDefault(JSONObject())

    private fun writeRoot(root: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        check(temporary.renameTo(file) || runCatching {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }.isSuccess) { "Profile could not be saved" }
    }

    private fun userId() = "user-${UUID.randomUUID()}"

    private fun requireName(name: String) = name.trim().takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException("Profile name is required")

    private companion object {
        const val USER_PROFILE_SCHEMA_VERSION = 3
    }
}

private fun PrinterProfile.toJson() = JSONObject()
    .put("id", id).put("name", name)
    .put("bedSizeX", bedSizeX).put("bedSizeY", bedSizeY)
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

private fun FilamentProfile.toJson() = JSONObject()
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

private fun QualityProfile.toJson() = JSONObject()
    .put("id", id).put("name", name)
    .put("layerHeightMm", layerHeightMm).put("firstLayerHeightMm", firstLayerHeightMm)
    .put("perimeters", perimeters).put("fillDensity", fillDensity).put("printSpeed", printSpeed)
    .put("nozzleDiameter", nozzleDiameter)
    .put("supportEnabled", supportEnabled).put("brimWidth", brimWidth)
    .put("topSolidLayers", topSolidLayers).put("bottomSolidLayers", bottomSolidLayers)
    .put("fillPattern", fillPattern).put("travelSpeed", travelSpeed)
    .put("firstLayerSpeed", firstLayerSpeed).put("supportType", supportType)
    .put("supportAngle", supportAngle).put("skirtLoops", skirtLoops)
    .put("skirtDistance", skirtDistance)
    .put("outerWallLineWidth", outerWallLineWidth)
    .put("innerWallLineWidth", innerWallLineWidth)
    .put("wallSequence", wallSequence)
    .put("detectThinWalls", detectThinWalls)
    .put("detectOverhangWalls", detectOverhangWalls)
    .put("onlyOneWallOnTop", onlyOneWallOnTop)
    .put("preciseOuterWalls", preciseOuterWalls)

private fun JSONArray?.toPrinterProfiles() = objects().mapNotNull { value ->
    runCatching {
        PrinterProfile(
            value.getString("id"), value.getString("name"),
            value.getDouble("bedSizeX").toFloat(), value.getDouble("bedSizeY").toFloat(),
            value.getDouble("maxPrintHeight").toFloat(), value.getDouble("nozzleDiameter").toFloat(),
            machineStartGcode = value.optString("machineStartGcode"),
            machineEndGcode = value.optString("machineEndGcode"),
            gcodeFlavor = value.optString("gcodeFlavor", "marlin"),
            maxSpeedX = value.optDouble("maxSpeedX", 500.0).toFloat(),
            maxSpeedY = value.optDouble("maxSpeedY", 500.0).toFloat(),
            maxSpeedZ = value.optDouble("maxSpeedZ", 20.0).toFloat(),
            maxSpeedE = value.optDouble("maxSpeedE", 30.0).toFloat(),
            maxAccelerationX = value.optDouble("maxAccelerationX", 20_000.0).toFloat(),
            maxAccelerationY = value.optDouble("maxAccelerationY", 20_000.0).toFloat(),
            maxAccelerationZ = value.optDouble("maxAccelerationZ", 500.0).toFloat(),
            maxAccelerationE = value.optDouble("maxAccelerationE", 5_000.0).toFloat(),
            maxAccelerationExtruding = value.optDouble("maxAccelerationExtruding", 20_000.0).toFloat(),
            maxAccelerationRetracting = value.optDouble("maxAccelerationRetracting", 5_000.0).toFloat(),
            maxAccelerationTravel = value.optDouble("maxAccelerationTravel", 20_000.0).toFloat(),
            maxJerkX = value.optDouble("maxJerkX", 9.0).toFloat(),
            maxJerkY = value.optDouble("maxJerkY", 9.0).toFloat(),
            maxJerkZ = value.optDouble("maxJerkZ", 3.0).toFloat(),
            maxJerkE = value.optDouble("maxJerkE", 2.5).toFloat(),
        )
    }.getOrNull()
}

private fun JSONArray?.toFilamentProfiles() = objects().mapNotNull { value ->
    runCatching {
        FilamentProfile(
            value.getString("id"), value.getString("name"), value.getString("nativeName"),
            value.getInt("nozzleTemp"), value.optInt("firstLayerNozzleTemp", value.getInt("nozzleTemp")),
            value.getInt("bedTemp"), value.optInt("firstLayerBedTemp", value.getInt("bedTemp")),
            value.getDouble("flowRatio").toFloat(), value.getDouble("maxVolumetricSpeed").toFloat(),
            retractLength = value.optDouble("retractLength", 0.8).toFloat(),
            retractSpeed = value.optDouble("retractSpeed", 45.0).toFloat(),
            fanMinSpeed = value.optInt("fanMinSpeed", 30),
            fanMaxSpeed = value.optInt("fanMaxSpeed", 100),
            overhangFanSpeed = value.optInt("overhangFanSpeed", 100),
            slowDownLayerTime = value.optDouble("slowDownLayerTime", 8.0).toFloat(),
            slowDownMinSpeed = value.optDouble("slowDownMinSpeed", 10.0).toFloat(),
            closeFanFirstLayers = value.optInt("closeFanFirstLayers", 1),
            fullFanSpeedLayer = value.optInt("fullFanSpeedLayer", 3),
            pressureAdvanceEnabled = value.optBoolean("pressureAdvanceEnabled"),
            pressureAdvance = value.optDouble("pressureAdvance", 0.0).toFloat(),
        )
    }.getOrNull()
}

private fun JSONArray?.toQualityProfiles() = objects().mapNotNull { value ->
    runCatching {
        QualityProfile(
            value.getString("id"), value.getString("name"),
            value.getDouble("layerHeightMm").toFloat(), value.getDouble("firstLayerHeightMm").toFloat(),
            value.getInt("perimeters"), value.getDouble("fillDensity").toFloat(),
            value.getDouble("printSpeed").toFloat(), value.optDouble("nozzleDiameter", 0.4).toFloat(),
            value.optBoolean("supportEnabled"),
            value.optDouble("brimWidth", 0.0).toFloat(),
            topSolidLayers = value.optInt("topSolidLayers", 5),
            bottomSolidLayers = value.optInt("bottomSolidLayers", 4),
            fillPattern = value.optString("fillPattern", "gyroid"),
            travelSpeed = value.optDouble("travelSpeed", 500.0).toFloat(),
            firstLayerSpeed = value.optDouble("firstLayerSpeed", 50.0).toFloat(),
            supportType = value.optString("supportType", "normal"),
            supportAngle = value.optDouble("supportAngle", 45.0).toFloat(),
            skirtLoops = value.optInt("skirtLoops", 0),
            skirtDistance = value.optDouble("skirtDistance", 6.0).toFloat(),
            outerWallLineWidth = value.optDouble("outerWallLineWidth", 0.0).toFloat(),
            innerWallLineWidth = value.optDouble("innerWallLineWidth", 0.0).toFloat(),
            wallSequence = value.optString("wallSequence", "inner-outer"),
            detectThinWalls = value.optBoolean("detectThinWalls"),
            detectOverhangWalls = value.optBoolean("detectOverhangWalls", true),
            onlyOneWallOnTop = value.optBoolean("onlyOneWallOnTop"),
            preciseOuterWalls = value.optBoolean("preciseOuterWalls", true),
        )
    }.getOrNull()
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    List(length()) { index -> optJSONObject(index) }.filterNotNull()
}

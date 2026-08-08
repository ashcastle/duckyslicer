package com.ashcastle.duckyslicer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Stores user-created profile snapshots in app-private storage. */
class ProfileStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "profiles/user_profiles.json"))

    @Synchronized
    fun load(): ProfileCatalog {
        val root = readRoot()
        return ProfileCatalog(
            printers = PrinterProfile.builtIns + root.optJSONArray("printers").toPrinterProfiles(),
            filaments = FilamentProfile.builtIns + root.optJSONArray("filaments").toFilamentProfiles(),
            slicing = QualityProfile.builtIns + root.optJSONArray("slicing").toQualityProfiles(),
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
        )
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
        )
        append("slicing", profile.toJson())
        return profile
    }

    private fun append(key: String, value: JSONObject) {
        val root = readRoot()
        val values = root.optJSONArray(key) ?: JSONArray().also { root.put(key, it) }
        values.put(value)
        writeRoot(root)
    }

    private fun readRoot(): JSONObject = runCatching {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
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
}

private fun PrinterProfile.toJson() = JSONObject()
    .put("id", id).put("name", name)
    .put("bedSizeX", bedSizeX).put("bedSizeY", bedSizeY)
    .put("maxPrintHeight", maxPrintHeight).put("nozzleDiameter", nozzleDiameter)
    .put("machineStartGcode", machineStartGcode).put("machineEndGcode", machineEndGcode)

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

private fun JSONArray?.toPrinterProfiles() = objects().mapNotNull { value ->
    runCatching {
        PrinterProfile(
            value.getString("id"), value.getString("name"),
            value.getDouble("bedSizeX").toFloat(), value.getDouble("bedSizeY").toFloat(),
            value.getDouble("maxPrintHeight").toFloat(), value.getDouble("nozzleDiameter").toFloat(),
            machineStartGcode = value.optString("machineStartGcode"),
            machineEndGcode = value.optString("machineEndGcode"),
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
        )
    }.getOrNull()
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    List(length()) { index -> optJSONObject(index) }.filterNotNull()
}

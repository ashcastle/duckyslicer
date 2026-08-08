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

private fun FilamentProfile.toJson() = JSONObject()
    .put("id", id).put("name", name).put("nativeName", nativeName)
    .put("nozzleTemp", nozzleTemp).put("firstLayerNozzleTemp", firstLayerNozzleTemp)
    .put("bedTemp", bedTemp).put("firstLayerBedTemp", firstLayerBedTemp)
    .put("flowRatio", flowRatio).put("maxVolumetricSpeed", maxVolumetricSpeed)

private fun QualityProfile.toJson() = JSONObject()
    .put("id", id).put("name", name)
    .put("layerHeightMm", layerHeightMm).put("firstLayerHeightMm", firstLayerHeightMm)
    .put("perimeters", perimeters).put("fillDensity", fillDensity).put("printSpeed", printSpeed)
    .put("nozzleDiameter", nozzleDiameter)
    .put("supportEnabled", supportEnabled).put("brimWidth", brimWidth)

private fun JSONArray?.toPrinterProfiles() = objects().mapNotNull { value ->
    runCatching {
        PrinterProfile(
            value.getString("id"), value.getString("name"),
            value.getDouble("bedSizeX").toFloat(), value.getDouble("bedSizeY").toFloat(),
            value.getDouble("maxPrintHeight").toFloat(), value.getDouble("nozzleDiameter").toFloat(),
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
        )
    }.getOrNull()
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    List(length()) { index -> optJSONObject(index) }.filterNotNull()
}

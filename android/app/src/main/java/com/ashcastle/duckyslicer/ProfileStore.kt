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
        append("printers", profile.toProfileJson())
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
            bridgeFlowRatio = options.bridgeFlowRatio,
            internalBridgeFlowRatio = options.internalBridgeFlowRatio,
            topSurfaceFlowRatio = options.topSurfaceFlowRatio,
            bottomSurfaceFlowRatio = options.bottomSurfaceFlowRatio,
            defaultAcceleration = options.defaultAcceleration,
            outerWallAcceleration = options.outerWallAcceleration,
            innerWallAcceleration = options.innerWallAcceleration,
            topSurfaceAcceleration = options.topSurfaceAcceleration,
            travelAcceleration = options.travelAcceleration,
            firstLayerAcceleration = options.firstLayerAcceleration,
            supportEnabled = options.supportEnabled,
            brimWidth = options.brimWidth,
            topSolidLayers = options.topSolidLayers,
            bottomSolidLayers = options.bottomSolidLayers,
            topShellThickness = options.topShellThickness,
            bottomShellThickness = options.bottomShellThickness,
            fillPattern = options.fillPattern,
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
            skirtLoops = options.skirtLoops,
            skirtDistance = options.skirtDistance,
            outerWallLineWidth = options.outerWallLineWidth,
            innerWallLineWidth = options.innerWallLineWidth,
            topSurfaceLineWidth = options.topSurfaceLineWidth,
            sparseInfillLineWidth = options.sparseInfillLineWidth,
            internalSolidInfillLineWidth = options.internalSolidInfillLineWidth,
            supportLineWidth = options.supportLineWidth,
            initialLayerLineWidth = options.initialLayerLineWidth,
            wallGenerator = options.wallGenerator,
            wallSequence = options.wallSequence,
            detectThinWalls = options.detectThinWalls,
            detectOverhangWalls = options.detectOverhangWalls,
            onlyOneWallOnTop = options.onlyOneWallOnTop,
            preciseOuterWalls = options.preciseOuterWalls,
        )
        require(ProfileValidation.slicing(profile)) { "Slicing profile contains unsafe values" }
        append("slicing", profile.toProfileJson())
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
        const val USER_PROFILE_SCHEMA_VERSION = 7
    }
}

internal fun PrinterProfile.toProfileJson() = JSONObject()
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
    .put("bridgeFlowRatio", bridgeFlowRatio)
    .put("internalBridgeFlowRatio", internalBridgeFlowRatio)
    .put("topSurfaceFlowRatio", topSurfaceFlowRatio)
    .put("bottomSurfaceFlowRatio", bottomSurfaceFlowRatio)
    .put("defaultAcceleration", defaultAcceleration)
    .put("outerWallAcceleration", outerWallAcceleration)
    .put("innerWallAcceleration", innerWallAcceleration)
    .put("topSurfaceAcceleration", topSurfaceAcceleration)
    .put("travelAcceleration", travelAcceleration)
    .put("firstLayerAcceleration", firstLayerAcceleration)
    .put("supportEnabled", supportEnabled).put("brimWidth", brimWidth)
    .put("topSolidLayers", topSolidLayers).put("bottomSolidLayers", bottomSolidLayers)
    .put("topShellThickness", topShellThickness).put("bottomShellThickness", bottomShellThickness)
    .put("fillPattern", fillPattern).put("travelSpeed", travelSpeed)
    .put("firstLayerSpeed", firstLayerSpeed).put("supportType", supportType)
    .put("supportAngle", supportAngle).put("skirtLoops", skirtLoops)
    .put("supportInterfaceTopLayers", supportInterfaceTopLayers)
    .put("supportInterfaceBottomLayers", supportInterfaceBottomLayers)
    .put("supportInterfaceSpacing", supportInterfaceSpacing)
    .put("supportBottomInterfaceSpacing", supportBottomInterfaceSpacing)
    .put("supportTopZDistance", supportTopZDistance)
    .put("supportBottomZDistance", supportBottomZDistance)
    .put("supportObjectXYDistance", supportObjectXYDistance)
    .put("skirtDistance", skirtDistance)
    .put("outerWallLineWidth", outerWallLineWidth)
    .put("innerWallLineWidth", innerWallLineWidth)
    .put("topSurfaceLineWidth", topSurfaceLineWidth)
    .put("sparseInfillLineWidth", sparseInfillLineWidth)
    .put("internalSolidInfillLineWidth", internalSolidInfillLineWidth)
    .put("supportLineWidth", supportLineWidth)
    .put("initialLayerLineWidth", initialLayerLineWidth)
    .put("wallGenerator", wallGenerator)
    .put("wallSequence", wallSequence)
    .put("detectThinWalls", detectThinWalls)
    .put("detectOverhangWalls", detectOverhangWalls)
    .put("onlyOneWallOnTop", onlyOneWallOnTop)
    .put("preciseOuterWalls", preciseOuterWalls)
    .put("builtIn", builtIn)
    .put("brand", brand ?: JSONObject.NULL)
    .put("compatiblePrinters", JSONArray(compatiblePrinters))

internal fun JSONObject.toPrinterProfileOrNull(): PrinterProfile? = runCatching {
    PrinterProfile(
        getString("id"), getString("name"),
        getDouble("bedSizeX").toFloat(), getDouble("bedSizeY").toFloat(),
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
        bridgeFlowRatio = optDouble("bridgeFlowRatio", 1.0).toFloat(),
        internalBridgeFlowRatio = optDouble("internalBridgeFlowRatio", 1.0).toFloat(),
        topSurfaceFlowRatio = optDouble("topSurfaceFlowRatio", 1.0).toFloat(),
        bottomSurfaceFlowRatio = optDouble("bottomSurfaceFlowRatio", 1.0).toFloat(),
        defaultAcceleration = optDouble("defaultAcceleration", 0.0).toFloat(),
        outerWallAcceleration = optDouble("outerWallAcceleration", 0.0).toFloat(),
        innerWallAcceleration = optDouble("innerWallAcceleration", 0.0).toFloat(),
        topSurfaceAcceleration = optDouble("topSurfaceAcceleration", 0.0).toFloat(),
        travelAcceleration = optDouble("travelAcceleration", 0.0).toFloat(),
        firstLayerAcceleration = optDouble("firstLayerAcceleration", 0.0).toFloat(),
        optBoolean("supportEnabled"),
        optDouble("brimWidth", 0.0).toFloat(),
        builtIn = optBoolean("builtIn"),
        topSolidLayers = optInt("topSolidLayers", 5),
        bottomSolidLayers = optInt("bottomSolidLayers", 4),
        topShellThickness = optDouble("topShellThickness", 0.0).toFloat(),
        bottomShellThickness = optDouble("bottomShellThickness", 0.0).toFloat(),
        fillPattern = optString("fillPattern", "gyroid"),
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
        skirtLoops = optInt("skirtLoops", 0),
        skirtDistance = optDouble("skirtDistance", 6.0).toFloat(),
        outerWallLineWidth = optDouble("outerWallLineWidth", 0.0).toFloat(),
        innerWallLineWidth = optDouble("innerWallLineWidth", 0.0).toFloat(),
        topSurfaceLineWidth = optDouble("topSurfaceLineWidth", 0.0).toFloat(),
        sparseInfillLineWidth = optDouble("sparseInfillLineWidth", 0.0).toFloat(),
        internalSolidInfillLineWidth = optDouble("internalSolidInfillLineWidth", 0.0).toFloat(),
        supportLineWidth = optDouble("supportLineWidth", 0.0).toFloat(),
        initialLayerLineWidth = optDouble("initialLayerLineWidth", 0.0).toFloat(),
        wallGenerator = optString("wallGenerator", "arachne"),
        wallSequence = optString("wallSequence", "inner-outer"),
        detectThinWalls = optBoolean("detectThinWalls"),
        detectOverhangWalls = optBoolean("detectOverhangWalls", true),
        onlyOneWallOnTop = optBoolean("onlyOneWallOnTop"),
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

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    List(length()) { index -> optJSONObject(index) }.filterNotNull()
}

package com.ashcastle.duckyslicer

import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import java.io.File
import kotlin.math.abs
import org.json.JSONObject

data class SliceOutcome(
    val output: File,
    val layers: Int,
    val estimatedSeconds: Float,
    val filamentGrams: Float,
)

data class PrinterProfile(
    val id: String,
    val name: String,
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val nozzleDiameter: Float,
    val builtIn: Boolean = false,
    val brand: String? = null,
    val machineStartGcode: String = "",
    val machineEndGcode: String = "",
    val gcodeFlavor: String = "marlin",
    val maxSpeedX: Float = 500f,
    val maxSpeedY: Float = 500f,
    val maxSpeedZ: Float = 20f,
    val maxSpeedE: Float = 30f,
    val maxAccelerationX: Float = 20_000f,
    val maxAccelerationY: Float = 20_000f,
    val maxAccelerationZ: Float = 500f,
    val maxAccelerationE: Float = 5_000f,
    val maxAccelerationExtruding: Float = 20_000f,
    val maxAccelerationRetracting: Float = 5_000f,
    val maxAccelerationTravel: Float = 20_000f,
    val maxJerkX: Float = 9f,
    val maxJerkY: Float = 9f,
    val maxJerkZ: Float = 3f,
    val maxJerkE: Float = 2.5f,
) {
    companion object {
        val U1_02 = PrinterProfile("snapmaker-u1-02", "U1 · 0.2 mm", 270f, 270f, 270f, 0.2f, true, "Snapmaker")
        val U1_04 = PrinterProfile("snapmaker-u1-04", "U1 · 0.4 mm", 270f, 270f, 270f, 0.4f, true, "Snapmaker")
        val U1_06 = PrinterProfile("snapmaker-u1-06", "U1 · 0.6 mm", 270f, 270f, 270f, 0.6f, true, "Snapmaker")
        val U1_08 = PrinterProfile("snapmaker-u1-08", "U1 · 0.8 mm", 270f, 270f, 270f, 0.8f, true, "Snapmaker")
        val CUSTOM_CARTESIAN = PrinterProfile(
            id = "custom-cartesian-04",
            name = "Custom Cartesian · 0.4 mm",
            bedSizeX = 220f,
            bedSizeY = 220f,
            maxPrintHeight = 250f,
            nozzleDiameter = 0.4f,
            builtIn = true,
            brand = "Custom",
            maxSpeedX = 300f,
            maxSpeedY = 300f,
            maxSpeedZ = 15f,
            maxSpeedE = 25f,
            maxAccelerationX = 3_000f,
            maxAccelerationY = 3_000f,
            maxAccelerationZ = 200f,
            maxAccelerationE = 2_000f,
            maxAccelerationExtruding = 3_000f,
            maxAccelerationRetracting = 2_000f,
            maxAccelerationTravel = 3_000f,
            maxJerkX = 8f,
            maxJerkY = 8f,
            maxJerkZ = 0.4f,
            maxJerkE = 5f,
        )
        val builtIns = listOf(U1_02, U1_04, U1_06, U1_08, CUSTOM_CARTESIAN)
    }
}

data class FilamentProfile(
    val id: String,
    val name: String,
    val nativeName: String,
    val nozzleTemp: Int,
    val firstLayerNozzleTemp: Int,
    val bedTemp: Int,
    val firstLayerBedTemp: Int,
    val flowRatio: Float,
    val maxVolumetricSpeed: Float,
    val builtIn: Boolean = false,
    val brand: String? = null,
    val retractLength: Float = 0.8f,
    val retractSpeed: Float = 45f,
    val fanMinSpeed: Int = 30,
    val fanMaxSpeed: Int = 100,
    val overhangFanSpeed: Int = 100,
    val slowDownLayerTime: Float = 8f,
    val slowDownMinSpeed: Float = 10f,
    val closeFanFirstLayers: Int = 1,
    val fullFanSpeedLayer: Int = 3,
    val pressureAdvanceEnabled: Boolean = false,
    val pressureAdvance: Float = 0f,
    val compatiblePrinters: List<String> = emptyList(),
) {
    companion object {
        // Curated from the included Snapmaker U1 filament catalog.
        val PLA = FilamentProfile(
            "snapmaker-u1-pla", "Snapmaker PLA", "PLA",
            220, 220, 60, 60, 0.98f, 14f, true, "Snapmaker",
        )
        val PETG = FilamentProfile(
            "snapmaker-u1-petg", "Snapmaker PETG", "PETG",
            245, 250, 70, 70, 0.95f, 10f, true, "Snapmaker",
        )
        val ABS = FilamentProfile(
            "snapmaker-u1-abs", "Snapmaker ABS", "ABS",
            260, 260, 110, 105, 0.95f, 8f, true, "Snapmaker",
        )
        val ASA = FilamentProfile(
            "snapmaker-u1-asa", "Snapmaker ASA", "ASA",
            255, 255, 110, 100, 0.94f, 8f, true, "Snapmaker",
        )
        val PLA_CF = FilamentProfile(
            "snapmaker-u1-pla-cf", "Snapmaker PLA-CF", "PLA-CF",
            230, 230, 55, 55, 0.98f, 15f, true, "Snapmaker",
        )
        val PETG_CF = FilamentProfile(
            "snapmaker-u1-petg-cf", "Snapmaker PETG-CF", "PETG-CF",
            245, 250, 70, 70, 0.95f, 6.4f, true, "Snapmaker",
        )
        val TPU_95A = FilamentProfile(
            "snapmaker-u1-tpu-95a", "Snapmaker TPU 95A", "TPU",
            240, 240, 35, 35, 1.0f, 15f, true, "Snapmaker",
        )
        val PA_CF = FilamentProfile(
            "snapmaker-u1-pa-cf", "Snapmaker PA-CF", "PA-CF",
            250, 255, 100, 95, 0.96f, 8f, true, "Snapmaker",
        )
        val GENERIC_PLA = FilamentProfile(
            "generic-pla", "Generic PLA", "PLA", 220, 220, 60, 60, 0.98f, 12f, true, "Generic",
            fanMinSpeed = 100,
        )
        val GENERIC_PETG = FilamentProfile(
            "generic-petg", "Generic PETG", "PETG", 245, 250, 70, 70, 0.98f, 10f, true, "Generic",
            fanMinSpeed = 30, fanMaxSpeed = 70,
        )
        val GENERIC_ABS = FilamentProfile(
            "generic-abs", "Generic ABS", "ABS", 260, 260, 100, 100, 0.98f, 12f, true, "Generic",
            fanMinSpeed = 10, fanMaxSpeed = 50,
        )
        val GENERIC_ASA = FilamentProfile(
            "generic-asa", "Generic ASA", "ASA", 260, 260, 100, 100, 0.98f, 12f, true, "Generic",
            fanMinSpeed = 10, fanMaxSpeed = 50,
        )
        val GENERIC_TPU = FilamentProfile(
            "generic-tpu", "Generic TPU 95A", "TPU", 240, 240, 35, 35, 1f, 6f, true, "Generic",
            retractLength = 0.4f, retractSpeed = 30f,
        )
        val PRUSAMENT_PLA = FilamentProfile(
            "prusa-pla", "Prusament PLA", "PLA", 220, 220, 60, 60, 1f, 12f, true, "Prusa",
            fanMinSpeed = 100,
        )
        val CREALITY_PLA = FilamentProfile(
            "creality-pla", "Creality Generic PLA", "PLA", 220, 220, 60, 60, 0.98f, 12f, true, "Creality",
            fanMinSpeed = 100,
        )
        val ANYCUBIC_PLA = FilamentProfile(
            "anycubic-pla", "Anycubic Generic PLA", "PLA", 220, 220, 45, 45, 0.98f, 12f, true, "Anycubic",
            fanMinSpeed = 100,
        )
        val ELEGOO_PLA = FilamentProfile(
            "elegoo-pla", "Elegoo PLA", "PLA", 220, 220, 60, 60, 1f, 12f, true, "Elegoo",
            fanMinSpeed = 50,
        )
        val builtIns = listOf(
            GENERIC_PLA, GENERIC_PETG, GENERIC_ABS, GENERIC_ASA, GENERIC_TPU,
            PLA, PETG, ABS, ASA, PLA_CF, PETG_CF, TPU_95A, PA_CF,
            PRUSAMENT_PLA, CREALITY_PLA, ANYCUBIC_PLA, ELEGOO_PLA,
        )
    }
}

data class QualityProfile(
    val id: String,
    val name: String,
    val layerHeightMm: Float,
    val firstLayerHeightMm: Float,
    val perimeters: Int,
    val fillDensity: Float,
    val printSpeed: Float,
    val nozzleDiameter: Float,
    val supportEnabled: Boolean = false,
    val brimWidth: Float = 0f,
    val builtIn: Boolean = false,
    val topSolidLayers: Int = 5,
    val bottomSolidLayers: Int = 4,
    val fillPattern: String = "gyroid",
    val travelSpeed: Float = 500f,
    val firstLayerSpeed: Float = 50f,
    val supportType: String = "normal",
    val supportAngle: Float = 45f,
    val skirtLoops: Int = 0,
    val skirtDistance: Float = 6f,
    val outerWallLineWidth: Float = 0f,
    val innerWallLineWidth: Float = 0f,
    val wallGenerator: String = "arachne",
    val wallSequence: String = "inner-outer",
    val detectThinWalls: Boolean = false,
    val detectOverhangWalls: Boolean = true,
    val onlyOneWallOnTop: Boolean = false,
    val preciseOuterWalls: Boolean = true,
    val brand: String? = null,
    val compatiblePrinters: List<String> = emptyList(),
) {
    companion object {
        // Curated from the included Snapmaker U1 process catalog.
        val FINE_02 = QualityProfile(
            "snapmaker-u1-02-006", "0.06 mm Fine",
            0.06f, 0.10f, 4, 0.15f, 120f, 0.2f, builtIn = true,
        )
        val STANDARD_02 = QualityProfile(
            "snapmaker-u1-02-012", "0.12 mm Standard",
            0.12f, 0.10f, 4, 0.15f, 120f, 0.2f, builtIn = true,
        )
        val DRAFT_02 = QualityProfile(
            "snapmaker-u1-02-014", "0.14 mm Draft",
            0.14f, 0.10f, 4, 0.15f, 120f, 0.2f, builtIn = true,
        )
        val DRAFT = QualityProfile(
            "snapmaker-u1-04-028", "0.28 mm Extra Draft",
            0.28f, 0.20f, 2, 0.15f, 200f, 0.4f, builtIn = true,
        )
        val STANDARD = QualityProfile(
            "snapmaker-u1-04-020", "0.20 mm Standard",
            0.20f, 0.25f, 2, 0.15f, 200f, 0.4f, builtIn = true,
        )
        val FINE = QualityProfile(
            "snapmaker-u1-04-012", "0.12 mm Fine",
            0.12f, 0.20f, 2, 0.15f, 200f, 0.4f, builtIn = true,
        )
        val DRAFT_06 = QualityProfile(
            "snapmaker-u1-06-040", "0.40 mm Extra Draft",
            0.40f, 0.35f, 2, 0.15f, 220f, 0.6f, builtIn = true,
        )
        val STANDARD_06 = QualityProfile(
            "snapmaker-u1-06-030", "0.30 mm Standard",
            0.30f, 0.30f, 2, 0.15f, 200f, 0.6f, builtIn = true,
        )
        val FINE_06 = QualityProfile(
            "snapmaker-u1-06-020", "0.20 mm Fine",
            0.20f, 0.25f, 2, 0.15f, 200f, 0.6f, builtIn = true,
        )
        val FINE_08 = QualityProfile(
            "snapmaker-u1-08-024", "0.24 mm Fine",
            0.24f, 0.40f, 2, 0.15f, 200f, 0.8f, builtIn = true,
        )
        val STANDARD_08 = QualityProfile(
            "snapmaker-u1-08-040", "0.40 mm Standard",
            0.40f, 0.40f, 2, 0.15f, 200f, 0.8f, builtIn = true,
        )
        val DRAFT_08 = QualityProfile(
            "snapmaker-u1-08-056", "0.56 mm Draft",
            0.56f, 0.40f, 2, 0.15f, 200f, 0.8f, builtIn = true,
        )
        val builtIns = listOf(
            FINE_02, STANDARD_02, DRAFT_02,
            FINE, STANDARD, DRAFT,
            FINE_06, STANDARD_06, DRAFT_06,
            FINE_08, STANDARD_08, DRAFT_08,
        )

        fun standardFor(nozzleDiameter: Float) = when {
            abs(nozzleDiameter - 0.2f) < 0.05f -> STANDARD_02
            abs(nozzleDiameter - 0.6f) < 0.05f -> STANDARD_06
            abs(nozzleDiameter - 0.8f) < 0.05f -> STANDARD_08
            else -> STANDARD
        }
    }
}

data class ProfileCatalog(
    val printers: List<PrinterProfile> = PrinterProfile.builtIns,
    val filaments: List<FilamentProfile> = FilamentProfile.builtIns,
    val slicing: List<QualityProfile> = QualityProfile.builtIns,
    val schemaVersion: Int = 3,
    val sourceRevision: String = "ducky-fallback",
    val rejectedCount: Int = 0,
)

data class SliceOptions(
    val printerProfile: PrinterProfile = PrinterProfile.U1_04,
    val filamentProfile: FilamentProfile = FilamentProfile.PLA,
    val quality: QualityProfile = QualityProfile.STANDARD,
    val bedSizeX: Float = printerProfile.bedSizeX,
    val bedSizeY: Float = printerProfile.bedSizeY,
    val maxPrintHeight: Float = printerProfile.maxPrintHeight,
    val nozzleDiameter: Float = printerProfile.nozzleDiameter,
    val nozzleTemp: Int = filamentProfile.nozzleTemp,
    val firstLayerNozzleTemp: Int = filamentProfile.firstLayerNozzleTemp,
    val bedTemp: Int = filamentProfile.bedTemp,
    val firstLayerBedTemp: Int = filamentProfile.firstLayerBedTemp,
    val filamentDiameter: Float = 1.75f,
    val flowRatio: Float = filamentProfile.flowRatio,
    val maxVolumetricSpeed: Float = filamentProfile.maxVolumetricSpeed,
    val retractLength: Float = filamentProfile.retractLength,
    val retractSpeed: Float = filamentProfile.retractSpeed,
    val fanMinSpeed: Int = filamentProfile.fanMinSpeed,
    val fanMaxSpeed: Int = filamentProfile.fanMaxSpeed,
    val overhangFanSpeed: Int = filamentProfile.overhangFanSpeed,
    val slowDownLayerTime: Float = filamentProfile.slowDownLayerTime,
    val slowDownMinSpeed: Float = filamentProfile.slowDownMinSpeed,
    val closeFanFirstLayers: Int = filamentProfile.closeFanFirstLayers,
    val fullFanSpeedLayer: Int = filamentProfile.fullFanSpeedLayer,
    val pressureAdvanceEnabled: Boolean = filamentProfile.pressureAdvanceEnabled,
    val pressureAdvance: Float = filamentProfile.pressureAdvance,
    val layerHeight: Float = quality.layerHeightMm,
    val firstLayerHeight: Float = quality.firstLayerHeightMm,
    val perimeters: Int = quality.perimeters,
    val fillDensity: Float = quality.fillDensity,
    val printSpeed: Float = quality.printSpeed,
    val topSolidLayers: Int = quality.topSolidLayers,
    val bottomSolidLayers: Int = quality.bottomSolidLayers,
    val fillPattern: String = quality.fillPattern,
    val travelSpeed: Float = quality.travelSpeed,
    val firstLayerSpeed: Float = quality.firstLayerSpeed,
    val supportEnabled: Boolean = quality.supportEnabled,
    val supportType: String = quality.supportType,
    val supportAngle: Float = quality.supportAngle,
    val skirtLoops: Int = quality.skirtLoops,
    val skirtDistance: Float = quality.skirtDistance,
    val brimWidth: Float = quality.brimWidth,
    val outerWallLineWidth: Float = quality.outerWallLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.05f,
    val innerWallLineWidth: Float = quality.innerWallLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.125f,
    val wallGenerator: String = quality.wallGenerator,
    val wallSequence: String = quality.wallSequence,
    val detectThinWalls: Boolean = quality.detectThinWalls,
    val detectOverhangWalls: Boolean = quality.detectOverhangWalls,
    val onlyOneWallOnTop: Boolean = quality.onlyOneWallOnTop,
    val preciseOuterWalls: Boolean = quality.preciseOuterWalls,
    val gcodeFlavor: String = printerProfile.gcodeFlavor,
    val maxSpeedX: Float = printerProfile.maxSpeedX,
    val maxSpeedY: Float = printerProfile.maxSpeedY,
    val maxSpeedZ: Float = printerProfile.maxSpeedZ,
    val maxSpeedE: Float = printerProfile.maxSpeedE,
    val maxAccelerationX: Float = printerProfile.maxAccelerationX,
    val maxAccelerationY: Float = printerProfile.maxAccelerationY,
    val maxAccelerationZ: Float = printerProfile.maxAccelerationZ,
    val maxAccelerationE: Float = printerProfile.maxAccelerationE,
    val maxAccelerationExtruding: Float = printerProfile.maxAccelerationExtruding,
    val maxAccelerationRetracting: Float = printerProfile.maxAccelerationRetracting,
    val maxAccelerationTravel: Float = printerProfile.maxAccelerationTravel,
    val maxJerkX: Float = printerProfile.maxJerkX,
    val maxJerkY: Float = printerProfile.maxJerkY,
    val maxJerkZ: Float = printerProfile.maxJerkZ,
    val maxJerkE: Float = printerProfile.maxJerkE,
) {
    fun selectPrinter(profile: PrinterProfile): SliceOptions {
        val nozzleMatches = abs(quality.nozzleDiameter - profile.nozzleDiameter) < 0.05f
        val updated = copy(
            printerProfile = profile,
            bedSizeX = profile.bedSizeX,
            bedSizeY = profile.bedSizeY,
            maxPrintHeight = profile.maxPrintHeight,
            nozzleDiameter = profile.nozzleDiameter,
            gcodeFlavor = profile.gcodeFlavor,
            maxSpeedX = profile.maxSpeedX,
            maxSpeedY = profile.maxSpeedY,
            maxSpeedZ = profile.maxSpeedZ,
            maxSpeedE = profile.maxSpeedE,
            maxAccelerationX = profile.maxAccelerationX,
            maxAccelerationY = profile.maxAccelerationY,
            maxAccelerationZ = profile.maxAccelerationZ,
            maxAccelerationE = profile.maxAccelerationE,
            maxAccelerationExtruding = profile.maxAccelerationExtruding,
            maxAccelerationRetracting = profile.maxAccelerationRetracting,
            maxAccelerationTravel = profile.maxAccelerationTravel,
            maxJerkX = profile.maxJerkX,
            maxJerkY = profile.maxJerkY,
            maxJerkZ = profile.maxJerkZ,
            maxJerkE = profile.maxJerkE,
        )
        return if (nozzleMatches) {
            updated
        } else {
            updated.selectQuality(QualityProfile.standardFor(profile.nozzleDiameter))
        }
    }

    fun selectFilament(profile: FilamentProfile) = copy(
        filamentProfile = profile,
        nozzleTemp = profile.nozzleTemp,
        firstLayerNozzleTemp = profile.firstLayerNozzleTemp,
        bedTemp = profile.bedTemp,
        firstLayerBedTemp = profile.firstLayerBedTemp,
        flowRatio = profile.flowRatio,
        maxVolumetricSpeed = profile.maxVolumetricSpeed,
        retractLength = profile.retractLength,
        retractSpeed = profile.retractSpeed,
        fanMinSpeed = profile.fanMinSpeed,
        fanMaxSpeed = profile.fanMaxSpeed,
        overhangFanSpeed = profile.overhangFanSpeed,
        slowDownLayerTime = profile.slowDownLayerTime,
        slowDownMinSpeed = profile.slowDownMinSpeed,
        closeFanFirstLayers = profile.closeFanFirstLayers,
        fullFanSpeedLayer = profile.fullFanSpeedLayer,
        pressureAdvanceEnabled = profile.pressureAdvanceEnabled,
        pressureAdvance = profile.pressureAdvance,
    )

    fun selectQuality(profile: QualityProfile) = copy(
        quality = profile,
        layerHeight = profile.layerHeightMm,
        firstLayerHeight = profile.firstLayerHeightMm,
        perimeters = profile.perimeters,
        fillDensity = profile.fillDensity,
        printSpeed = profile.printSpeed,
        topSolidLayers = profile.topSolidLayers,
        bottomSolidLayers = profile.bottomSolidLayers,
        fillPattern = profile.fillPattern,
        travelSpeed = profile.travelSpeed,
        firstLayerSpeed = profile.firstLayerSpeed,
        supportEnabled = profile.supportEnabled,
        supportType = profile.supportType,
        supportAngle = profile.supportAngle,
        skirtLoops = profile.skirtLoops,
        skirtDistance = profile.skirtDistance,
        brimWidth = profile.brimWidth,
        outerWallLineWidth = profile.outerWallLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.05f,
        innerWallLineWidth = profile.innerWallLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.125f,
        wallGenerator = profile.wallGenerator,
        wallSequence = profile.wallSequence,
        detectThinWalls = profile.detectThinWalls,
        detectOverhangWalls = profile.detectOverhangWalls,
        onlyOneWallOnTop = profile.onlyOneWallOnTop,
        preciseOuterWalls = profile.preciseOuterWalls,
    )

    fun toNativeConfig() = SliceConfig(
        layerHeight = layerHeight,
        firstLayerHeight = firstLayerHeight,
        perimeters = perimeters,
        topSolidLayers = topSolidLayers,
        bottomSolidLayers = bottomSolidLayers,
        fillDensity = fillDensity,
        fillPattern = fillPattern,
        printSpeed = printSpeed,
        travelSpeed = travelSpeed,
        firstLayerSpeed = firstLayerSpeed,
        nozzleTemp = nozzleTemp,
        bedTemp = bedTemp,
        retractLength = retractLength,
        retractSpeed = retractSpeed,
        supportEnabled = supportEnabled,
        supportType = supportType,
        supportAngle = supportAngle,
        skirtLoops = skirtLoops,
        skirtDistance = skirtDistance,
        brimWidth = brimWidth,
        outerWallLineWidth = outerWallLineWidth,
        innerWallLineWidth = innerWallLineWidth,
        wallGenerator = wallGenerator,
        wallSequence = wallSequence,
        detectThinWalls = detectThinWalls,
        detectOverhangWalls = detectOverhangWalls,
        onlyOneWallOnTop = onlyOneWallOnTop,
        preciseOuterWalls = preciseOuterWalls,
        bedSizeX = bedSizeX,
        bedSizeY = bedSizeY,
        maxPrintHeight = maxPrintHeight,
        nozzleDiameter = nozzleDiameter,
        filamentDiameter = filamentDiameter,
        filamentType = filamentProfile.nativeName,
        filamentTypes = arrayOf(filamentProfile.nativeName),
        extruderCount = 1,
        extruderTemps = intArrayOf(nozzleTemp),
        extruderRetractLength = floatArrayOf(retractLength),
        extruderRetractSpeed = floatArrayOf(retractSpeed),
        machineStartGcode = printerProfile.machineStartGcode,
        machineEndGcode = printerProfile.machineEndGcode,
        gcodeFlavor = gcodeFlavor,
        machineMaxSpeedX = maxSpeedX,
        machineMaxSpeedY = maxSpeedY,
        machineMaxSpeedZ = maxSpeedZ,
        machineMaxSpeedE = maxSpeedE,
        machineMaxAccelerationX = maxAccelerationX,
        machineMaxAccelerationY = maxAccelerationY,
        machineMaxAccelerationZ = maxAccelerationZ,
        machineMaxAccelerationE = maxAccelerationE,
        machineMaxAccelerationExtruding = maxAccelerationExtruding,
        machineMaxAccelerationRetracting = maxAccelerationRetracting,
        machineMaxAccelerationTravel = maxAccelerationTravel,
        machineMaxJerkX = maxJerkX,
        machineMaxJerkY = maxJerkY,
        machineMaxJerkZ = maxJerkZ,
        machineMaxJerkE = maxJerkE,
        filamentFlowRatios = floatArrayOf(flowRatio),
        filamentMaxVolumetricSpeeds = floatArrayOf(maxVolumetricSpeed),
        filamentFanMinSpeeds = intArrayOf(fanMinSpeed),
        filamentFanMaxSpeeds = intArrayOf(fanMaxSpeed),
        filamentOverhangFanSpeeds = intArrayOf(overhangFanSpeed),
        filamentSlowDownLayerTimes = floatArrayOf(slowDownLayerTime),
        filamentSlowDownMinSpeeds = floatArrayOf(slowDownMinSpeed),
        filamentCloseFanFirstLayers = intArrayOf(closeFanFirstLayers),
        filamentFullFanSpeedLayers = intArrayOf(fullFanSpeedLayer),
        filamentEnablePressureAdvance = intArrayOf(if (pressureAdvanceEnabled) 1 else 0),
        filamentPressureAdvances = floatArrayOf(pressureAdvance),
        filamentNozzleTempInitialLayers = intArrayOf(firstLayerNozzleTemp),
        filamentBedTempInitialLayers = intArrayOf(firstLayerBedTemp),
    )
}

object OnDeviceSlicer {
    fun slice(
        model: File,
        options: SliceOptions = SliceOptions(),
        modelTransform: ModelTransform = ModelTransform(),
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome = slice(
        listOf(
            ProjectObject(
                id = model.absolutePath,
                model = ModelInfo.fromJson(NativeEngine.inspectStl(model.absolutePath), model.absolutePath),
                transform = modelTransform,
            ),
        ),
        options,
        onProgress,
    )

    fun slice(
        objects: List<ProjectObject>,
        options: SliceOptions = SliceOptions(),
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome {
        require(objects.isNotEmpty()) { "Project has no objects" }
        require(objects.all { File(it.model.localPath).isFile }) { "Model file is unavailable" }

        val transformedModels = objects.mapIndexed { index, projectObject ->
            File.createTempFile(
                "slice-input-$index-",
                ".stl",
                File(projectObject.model.localPath).parentFile,
            )
        }
        val runtime = NativeLibrary(onProgress)
        return try {
            objects.zip(transformedModels).forEach { (projectObject, transformedModel) ->
                val transformed = JSONObject(
                    NativeEngine.transformStl(
                        projectObject.model.localPath,
                        transformedModel.absolutePath,
                        projectObject.transform.toJson(options.bedSizeX, options.bedSizeY),
                    ),
                )
                check(transformed.optBoolean("ok")) { "Model transform failed" }
            }
            check(runtime.loadModel(transformedModels.first().absolutePath)) {
                "Model could not be prepared"
            }
            transformedModels.drop(1).forEach { transformedModel ->
                check(runtime.addModel(transformedModel.absolutePath)) {
                    "Additional model could not be prepared"
                }
            }
            val result = requireNotNull(runtime.slice(options.toNativeConfig())) {
                "Slicer returned no output"
            }
            check(result.success) {
                if (result.cancelled) "Slicing was cancelled" else "Slicer could not produce output"
            }
            val output = File(result.gcodePath)
            check(output.isFile && output.length() > 0L) { "G-code output is unavailable" }
            SliceOutcome(
                output = output,
                layers = result.totalLayers,
                estimatedSeconds = result.estimatedTimeSeconds,
                filamentGrams = result.estimatedFilamentGrams,
            )
        } finally {
            runtime.clearModel()
            transformedModels.forEach(File::delete)
        }
    }
}

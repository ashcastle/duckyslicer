package com.ashcastle.duckyslicer

import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import java.io.File
import kotlin.math.abs

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
) {
    companion object {
        val U1_04 = PrinterProfile("snapmaker-u1-04", "U1 · 0.4 mm", 270f, 270f, 270f, 0.4f, true)
        val U1_06 = PrinterProfile("snapmaker-u1-06", "U1 · 0.6 mm", 270f, 270f, 270f, 0.6f, true)
        val builtIns = listOf(U1_04, U1_06)
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
) {
    companion object {
        // Curated from OrcaSlicer resources/profiles/Snapmaker/filament for Snapmaker U1.
        val PLA = FilamentProfile(
            "snapmaker-u1-pla", "Snapmaker PLA", "PLA",
            220, 220, 60, 60, 0.98f, 14f, true,
        )
        val PETG = FilamentProfile(
            "snapmaker-u1-petg", "Snapmaker PETG", "PETG",
            245, 250, 70, 70, 0.95f, 10f, true,
        )
        val ABS = FilamentProfile(
            "snapmaker-u1-abs", "Snapmaker ABS", "ABS",
            260, 260, 110, 105, 0.95f, 8f, true,
        )
        val builtIns = listOf(PLA, PETG, ABS)
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
) {
    companion object {
        // Curated from OrcaSlicer Snapmaker U1 0.4 mm and 0.6 mm process profiles.
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
        val builtIns = listOf(DRAFT, STANDARD, FINE, DRAFT_06, STANDARD_06, FINE_06)

        fun standardFor(nozzleDiameter: Float) = if (abs(nozzleDiameter - 0.6f) < 0.05f) STANDARD_06 else STANDARD
    }
}

data class ProfileCatalog(
    val printers: List<PrinterProfile> = PrinterProfile.builtIns,
    val filaments: List<FilamentProfile> = FilamentProfile.builtIns,
    val slicing: List<QualityProfile> = QualityProfile.builtIns,
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
    val layerHeight: Float = quality.layerHeightMm,
    val firstLayerHeight: Float = quality.firstLayerHeightMm,
    val perimeters: Int = quality.perimeters,
    val fillDensity: Float = quality.fillDensity,
    val printSpeed: Float = quality.printSpeed,
    val supportEnabled: Boolean = false,
    val brimWidth: Float = 0f,
) {
    fun selectPrinter(profile: PrinterProfile): SliceOptions {
        val compatibleQuality = if (abs(quality.nozzleDiameter - profile.nozzleDiameter) < 0.05f) {
            quality
        } else {
            QualityProfile.standardFor(profile.nozzleDiameter)
        }
        return copy(
            printerProfile = profile,
            bedSizeX = profile.bedSizeX,
            bedSizeY = profile.bedSizeY,
            maxPrintHeight = profile.maxPrintHeight,
            nozzleDiameter = profile.nozzleDiameter,
        ).selectQuality(compatibleQuality)
    }

    fun selectFilament(profile: FilamentProfile) = copy(
        filamentProfile = profile,
        nozzleTemp = profile.nozzleTemp,
        firstLayerNozzleTemp = profile.firstLayerNozzleTemp,
        bedTemp = profile.bedTemp,
        firstLayerBedTemp = profile.firstLayerBedTemp,
        flowRatio = profile.flowRatio,
        maxVolumetricSpeed = profile.maxVolumetricSpeed,
    )

    fun selectQuality(profile: QualityProfile) = copy(
        quality = profile,
        layerHeight = profile.layerHeightMm,
        firstLayerHeight = profile.firstLayerHeightMm,
        perimeters = profile.perimeters,
        fillDensity = profile.fillDensity,
        printSpeed = profile.printSpeed,
        supportEnabled = profile.supportEnabled,
        brimWidth = profile.brimWidth,
    )

    fun toNativeConfig() = SliceConfig(
        layerHeight = layerHeight,
        firstLayerHeight = firstLayerHeight,
        perimeters = perimeters,
        fillDensity = fillDensity,
        printSpeed = printSpeed,
        nozzleTemp = nozzleTemp,
        bedTemp = bedTemp,
        supportEnabled = supportEnabled,
        brimWidth = brimWidth,
        bedSizeX = bedSizeX,
        bedSizeY = bedSizeY,
        maxPrintHeight = maxPrintHeight,
        nozzleDiameter = nozzleDiameter,
        filamentDiameter = filamentDiameter,
        filamentType = filamentProfile.nativeName,
        filamentTypes = arrayOf(filamentProfile.nativeName),
        extruderCount = 1,
        extruderTemps = intArrayOf(nozzleTemp),
        filamentFlowRatios = floatArrayOf(flowRatio),
        filamentMaxVolumetricSpeeds = floatArrayOf(maxVolumetricSpeed),
        filamentNozzleTempInitialLayers = intArrayOf(firstLayerNozzleTemp),
        filamentBedTempInitialLayers = intArrayOf(firstLayerBedTemp),
    )
}

object OnDeviceSlicer {
    fun slice(
        model: File,
        options: SliceOptions = SliceOptions(),
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome {
        require(model.isFile) { "Model file is unavailable" }

        val runtime = NativeLibrary(onProgress)
        return try {
            check(runtime.loadModel(model.absolutePath)) { "Model could not be prepared" }
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
        }
    }
}

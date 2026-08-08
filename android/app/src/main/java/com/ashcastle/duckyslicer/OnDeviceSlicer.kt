package com.ashcastle.duckyslicer

import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import java.io.File

data class SliceOutcome(
    val output: File,
    val layers: Int,
    val estimatedSeconds: Float,
    val filamentGrams: Float,
)

enum class PrinterProfile(
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val nozzleDiameter: Float,
) {
    STANDARD_270(270f, 270f, 270f, 0.4f),
    COMPACT_220(220f, 220f, 250f, 0.4f),
}

enum class FilamentProfile(
    val nativeName: String,
    val nozzleTemp: Int,
    val bedTemp: Int,
    val flowRatio: Float,
    val maxVolumetricSpeed: Float,
) {
    PLA("PLA", 210, 60, 0.98f, 18f),
    PETG("PETG", 245, 75, 0.98f, 12f),
    ABS("ABS", 255, 100, 0.98f, 16f),
}

enum class QualityProfile(
    val layerHeightMm: Float,
    val firstLayerHeightMm: Float,
    val perimeters: Int,
    val fillDensity: Float,
    val printSpeed: Float,
) {
    DRAFT(0.28f, 0.30f, 2, 0.15f, 200f),
    STANDARD(0.20f, 0.24f, 3, 0.15f, 160f),
    FINE(0.12f, 0.20f, 3, 0.20f, 120f),
}

data class SliceOptions(
    val printerProfile: PrinterProfile = PrinterProfile.STANDARD_270,
    val filamentProfile: FilamentProfile = FilamentProfile.PLA,
    val quality: QualityProfile = QualityProfile.STANDARD,
    val bedSizeX: Float = printerProfile.bedSizeX,
    val bedSizeY: Float = printerProfile.bedSizeY,
    val maxPrintHeight: Float = printerProfile.maxPrintHeight,
    val nozzleDiameter: Float = printerProfile.nozzleDiameter,
    val nozzleTemp: Int = filamentProfile.nozzleTemp,
    val bedTemp: Int = filamentProfile.bedTemp,
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
    fun selectPrinter(profile: PrinterProfile) = copy(
        printerProfile = profile,
        bedSizeX = profile.bedSizeX,
        bedSizeY = profile.bedSizeY,
        maxPrintHeight = profile.maxPrintHeight,
        nozzleDiameter = profile.nozzleDiameter,
    )

    fun selectFilament(profile: FilamentProfile) = copy(
        filamentProfile = profile,
        nozzleTemp = profile.nozzleTemp,
        bedTemp = profile.bedTemp,
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
        filamentNozzleTempInitialLayers = intArrayOf(nozzleTemp),
        filamentBedTempInitialLayers = intArrayOf(bedTemp),
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

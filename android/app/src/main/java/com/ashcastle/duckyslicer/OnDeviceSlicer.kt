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
) {
    companion object {
        val U1_02 = PrinterProfile("snapmaker-u1-02", "U1 · 0.2 mm", 270f, 270f, 270f, 0.2f, true, "Snapmaker")
        val U1_04 = PrinterProfile("snapmaker-u1-04", "U1 · 0.4 mm", 270f, 270f, 270f, 0.4f, true, "Snapmaker")
        val U1_06 = PrinterProfile("snapmaker-u1-06", "U1 · 0.6 mm", 270f, 270f, 270f, 0.6f, true, "Snapmaker")
        val U1_08 = PrinterProfile("snapmaker-u1-08", "U1 · 0.8 mm", 270f, 270f, 270f, 0.8f, true, "Snapmaker")
        val builtIns = listOf(U1_02, U1_04, U1_06, U1_08)
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
        val builtIns = listOf(PLA, PETG, ABS, ASA, PLA_CF, PETG_CF, TPU_95A, PA_CF)
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
        val nozzleMatches = abs(quality.nozzleDiameter - profile.nozzleDiameter) < 0.05f
        val updated = copy(
            printerProfile = profile,
            bedSizeX = profile.bedSizeX,
            bedSizeY = profile.bedSizeY,
            maxPrintHeight = profile.maxPrintHeight,
            nozzleDiameter = profile.nozzleDiameter,
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
        modelTransform: ModelTransform = ModelTransform(),
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome {
        require(model.isFile) { "Model file is unavailable" }

        val transformedModel = File.createTempFile("slice-input-", ".stl", model.parentFile)
        val runtime = NativeLibrary(onProgress)
        return try {
            val transformed = JSONObject(
                NativeEngine.transformStl(
                    model.absolutePath,
                    transformedModel.absolutePath,
                    modelTransform.toJson(options.bedSizeX, options.bedSizeY),
                ),
            )
            check(transformed.optBoolean("ok")) { "Model transform failed" }
            check(runtime.loadModel(transformedModel.absolutePath)) { "Model could not be prepared" }
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
            transformedModel.delete()
        }
    }
}

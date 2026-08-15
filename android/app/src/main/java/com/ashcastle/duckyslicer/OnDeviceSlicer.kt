package com.ashcastle.duckyslicer

import com.u1.slicer.data.SliceConfig
import java.io.File
import java.io.Serializable
import java.util.UUID
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

data class SliceOutcome(
    val output: File,
    val layers: Int,
    val estimatedSeconds: Float,
    val filamentMm: Float,
    val filamentGrams: Float,
) : Serializable

internal fun SliceOutcome.isRestorableFrom(filesRoot: File): Boolean = runCatching {
    val canonicalOutput = output.canonicalFile
    val outputRoot = File(filesRoot, SliceArtifactStore.OUTPUT_DIRECTORY).canonicalFile
    canonicalOutput.parentFile == outputRoot &&
        canonicalOutput.isFile &&
        canonicalOutput.length() in 1..SliceArtifactStore.MAXIMUM_OUTPUT_BYTES &&
        layers > 0 &&
        estimatedSeconds.isFinite() && estimatedSeconds >= 0f &&
        filamentMm.isFinite() && filamentMm >= 0f &&
        filamentGrams.isFinite() && filamentGrams >= 0f
}.getOrDefault(false)

internal fun normalizedSupportType(value: String): String = when (val candidate = value.trim().lowercase()) {
    "normal(auto)", "normal" -> "normal(auto)"
    "tree(auto)", "tree", "hybrid(auto)" -> "tree(auto)"
    "normal(manual)" -> "normal(manual)"
    "tree(manual)" -> "tree(manual)"
    else -> candidate
}

internal fun String.isTreeSupportType(): Boolean = normalizedSupportType(this).let { normalized ->
    normalized == "tree(auto)" || normalized == "tree(manual)"
}

internal fun String.isAutomaticSupportType(): Boolean = normalizedSupportType(this).let { normalized ->
    normalized == "normal(auto)" || normalized == "tree(auto)"
}

internal fun compatibleSupportStyles(supportType: String): List<String> =
    if (supportType.isTreeSupportType()) {
        listOf("default", "organic", "tree_slim", "tree_strong", "tree_hybrid")
    } else {
        listOf("default", "grid", "snug")
    }

internal fun normalizedSupportStyle(supportType: String, supportStyle: String): String {
    val candidate = supportStyle.trim().lowercase()
    return candidate.takeIf { it in compatibleSupportStyles(supportType) } ?: "default"
}

internal enum class TreeSupportSettingsKind {
    NONE,
    ORGANIC,
    BRANCHED,
}

internal fun treeSupportSettingsKind(
    supportEnabled: Boolean,
    supportType: String,
    supportStyle: String,
): TreeSupportSettingsKind {
    if (!supportEnabled || !supportType.isTreeSupportType()) return TreeSupportSettingsKind.NONE
    return when (normalizedSupportStyle(supportType, supportStyle)) {
        "tree_slim", "tree_strong", "tree_hybrid" -> TreeSupportSettingsKind.BRANCHED
        else -> TreeSupportSettingsKind.ORGANIC
    }
}

internal fun minimumOrganicTreeTipDiameter(supportLineWidth: Float): Float =
    supportLineWidth.coerceAtLeast(0.1f)

internal fun minimumOrganicTreeBranchDiameter(
    supportLineWidth: Float,
    tipDiameter: Float,
): Float = maxOf(1f, supportLineWidth * 2f, tipDiameter)

internal data class SupportSettingsAvailability(
    val haveSupportMaterial: Boolean,
    val automatic: Boolean,
    val treeKind: TreeSupportSettingsKind,
    val haveInterface: Boolean,
    val canIron: Boolean,
    val ironingActive: Boolean,
)

internal fun SliceOptions.supportSettingsAvailability(): SupportSettingsAvailability {
    val haveSupportMaterial = supportEnabled || raftLayers > 0
    val haveInterface = supportInterfaceTopLayers > 0 || supportInterfaceBottomLayers > 0
    val canIron = raftLayers > 0 || (haveSupportMaterial && supportInterfaceTopLayers > 0)
    return SupportSettingsAvailability(
        haveSupportMaterial = haveSupportMaterial,
        automatic = supportType.isAutomaticSupportType(),
        treeKind = treeSupportSettingsKind(supportEnabled, supportType, supportStyle),
        haveInterface = haveInterface,
        canIron = canIron,
        ironingActive = canIron && supportAdvanced.ironingEnabled,
    )
}

data class PrinterProfile(
    val id: String,
    val name: String,
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val nozzleDiameter: Float,
    val builtIn: Boolean = false,
    val brand: String? = null,
    val minLayerHeight: Float = 0.04f,
    val maxLayerHeight: Float = nozzleDiameter * 0.7f,
    val machineStartGcode: String = "",
    val machineEndGcode: String = "",
    val beforeLayerChangeGcode: String = "",
    val layerChangeGcode: String = "",
    val changeFilamentGcode: String = "",
    val printingByObjectGcode: String = "",
    val useRelativeEDistances: Boolean = true,
    val emitMachineLimitsToGcode: Boolean = true,
    val manualFilamentChange: Boolean = false,
    val disableM73: Boolean = false,
    val machineLoadFilamentTime: Float = 0f,
    val machineUnloadFilamentTime: Float = 0f,
    val machineToolChangeTime: Float = 0f,
    val toolChangeTemperatureWait: Boolean = true,
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
    val retractLength: Float = 0.8f,
    val retractSpeed: Float = 45f,
    val deretractSpeed: Float = 35f,
    val retractionMinimumTravel: Float = 1f,
    val retractWhenChangingLayer: Boolean = false,
    val wipeWhileRetracting: Boolean = false,
    val wipeDistance: Float = 1f,
    val retractBeforeWipe: Float = 100f,
    val retractRestartExtra: Float = 0f,
    val extruderOffsetsX: List<Float> = listOf(0f),
    val extruderOffsetsY: List<Float> = listOf(0f),
    val toolChangeRetractLengths: List<Float> = listOf(retractLength),
    val toolChangeRetractRestartExtras: List<Float> = listOf(0f),
    val zHop: Float = 0.4f,
    val zHopType: String = "slope",
    val retractLiftAbove: Float = 0f,
    val retractLiftBelow: Float = 0f,
    val retractLiftEnforce: String = "all",
    val travelSlope: Float = 3f,
    val zHopWhenPrime: Boolean = true,
    val useFirmwareRetraction: Boolean = false,
    val longRetractionWhenCutLevel: Int = 0,
    val longRetractionWhenCut: Boolean = false,
    val retractionDistanceWhenCut: Float = 18f,
    val extruderClearanceRadius: Float = 40f,
    val extruderClearanceHeightToRod: Float = 40f,
    val extruderClearanceHeightToLid: Float = 120f,
    val bedOriginX: Float = 0f,
    val bedOriginY: Float = 0f,
    val bedPolygon: List<Float> = rectangularBedPolygon(bedSizeX, bedSizeY),
    val bedExcludeArea: List<Float> = listOf(0f, 0f),
    val singleExtruderMultiMaterial: Boolean = false,
    val coolingTubeRetraction: Float = 91.5f,
    val coolingTubeLength: Float = 5f,
    val parkingPosRetraction: Float = 92f,
    val extraLoadingMove: Float = -2f,
    val enableFilamentRamming: Boolean = true,
    val purgeInPrimeTower: Boolean = true,
    val highCurrentOnFilamentSwap: Boolean = false,
    val extruderCount: Int = 1,
    val auxiliaryFan: Boolean = false,
    val supportsChamberTemperatureControl: Boolean = false,
    val supportsAirFiltration: Boolean = false,
) {
    fun resolvedExtruderOffsetsX(count: Int = extruderCount): List<Float> =
        extruderOffsetsX.resizedExtruderValues(count, 0f)

    fun resolvedExtruderOffsetsY(count: Int = extruderCount): List<Float> =
        extruderOffsetsY.resizedExtruderValues(count, 0f)

    fun resolvedToolChangeRetractLengths(count: Int = extruderCount): List<Float> =
        toolChangeRetractLengths.resizedExtruderValues(count, retractLength)

    fun resolvedToolChangeRetractRestartExtras(count: Int = extruderCount): List<Float> =
        toolChangeRetractRestartExtras.resizedExtruderValues(count, 0f)

    companion object {
        val U1_02 = PrinterProfile(
            "snapmaker-u1-02", "U1 · 0.2 mm", 270f, 270f, 270f, 0.2f, true, "Snapmaker",
            minLayerHeight = 0.04f, maxLayerHeight = 0.14f,
            extruderCount = 4, extruderClearanceRadius = 72.5f, extruderClearanceHeightToRod = 27.5f,
        )
        val U1_04 = PrinterProfile(
            "snapmaker-u1-04", "U1 · 0.4 mm", 270f, 270f, 270f, 0.4f, true, "Snapmaker",
            minLayerHeight = 0.08f, maxLayerHeight = 0.32f,
            extruderCount = 4, extruderClearanceRadius = 72.5f, extruderClearanceHeightToRod = 27.5f,
        )
        val U1_06 = PrinterProfile(
            "snapmaker-u1-06", "U1 · 0.6 mm", 270f, 270f, 270f, 0.6f, true, "Snapmaker",
            minLayerHeight = 0.12f, maxLayerHeight = 0.42f,
            extruderCount = 4, extruderClearanceRadius = 72.5f, extruderClearanceHeightToRod = 27.5f,
        )
        val U1_08 = PrinterProfile(
            "snapmaker-u1-08", "U1 · 0.8 mm", 270f, 270f, 270f, 0.8f, true, "Snapmaker",
            minLayerHeight = 0.16f, maxLayerHeight = 0.56f,
            extruderCount = 4, extruderClearanceRadius = 72.5f, extruderClearanceHeightToRod = 27.5f,
        )
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
            retractSpeed = 30f,
            deretractSpeed = 0f,
            retractionMinimumTravel = 2f,
        )
        val builtIns = listOf(U1_02, U1_04, U1_06, U1_08, CUSTOM_CARTESIAN)
    }
}

enum class BuildPlateType(val storageValue: String, val nativeValue: Int) {
    COOL("cool", 1),
    ENGINEERING("engineering", 2),
    HIGH_TEMP("high_temp", 3),
    TEXTURED_PEI("textured_pei", 4),
    TEXTURED_COOL("textured_cool", 5),
    GRAPHIC_EFFECT("graphic_effect", 6),
    SUPER_TACK("super_tack", 7),
    ;

    companion object {
        fun fromStorage(value: String?): BuildPlateType? = entries.firstOrNull {
            it.storageValue == value
        }
    }
}

internal val BUILD_PLATE_TYPES = listOf(
    BuildPlateType.TEXTURED_PEI,
    BuildPlateType.HIGH_TEMP,
    BuildPlateType.ENGINEERING,
    BuildPlateType.COOL,
    BuildPlateType.TEXTURED_COOL,
    BuildPlateType.SUPER_TACK,
    BuildPlateType.GRAPHIC_EFFECT,
)

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
    val texturedPlateTemp: Int = bedTemp,
    val firstLayerTexturedPlateTemp: Int = firstLayerBedTemp,
    val engineeringPlateTemp: Int = bedTemp,
    val firstLayerEngineeringPlateTemp: Int = firstLayerBedTemp,
    val coolPlateTemp: Int = bedTemp,
    val firstLayerCoolPlateTemp: Int = firstLayerBedTemp,
    val texturedCoolPlateTemp: Int = coolPlateTemp,
    val firstLayerTexturedCoolPlateTemp: Int = firstLayerCoolPlateTemp,
    val superTackPlateTemp: Int = coolPlateTemp,
    val firstLayerSuperTackPlateTemp: Int = firstLayerCoolPlateTemp,
    val graphicEffectPlateTemp: Int = texturedPlateTemp,
    val firstLayerGraphicEffectPlateTemp: Int = firstLayerTexturedPlateTemp,
    val filamentStartGcode: String = "",
    val filamentEndGcode: String = "",
    val retractLength: Float? = null,
    val retractSpeed: Float? = null,
    val deretractSpeed: Float? = null,
    val retractionMinimumTravel: Float? = null,
    val retractWhenChangingLayer: Boolean? = null,
    val wipeWhileRetracting: Boolean? = null,
    val wipeDistance: Float? = null,
    val retractBeforeWipe: Float? = null,
    val retractRestartExtra: Float? = null,
    val zHop: Float? = null,
    val zHopType: String? = null,
    val retractLiftAbove: Float? = null,
    val retractLiftBelow: Float? = null,
    val retractLiftEnforce: String? = null,
    val longRetractionWhenCut: Boolean? = null,
    val retractionDistanceWhenCut: Float? = null,
    val fanMinSpeed: Int = 30,
    val fanMaxSpeed: Int = 100,
    val fanCoolingLayerTime: Float = 60f,
    val slowDownForLayerCooling: Boolean = true,
    val keepFanAlwaysOn: Boolean = false,
    val dontSlowDownOuterWall: Boolean = false,
    val enableOverhangBridgeFan: Boolean = true,
    val overhangFanSpeed: Int = 100,
    val overhangFanThreshold: String = "95%",
    val internalBridgeFanSpeed: Int = -1,
    val supportInterfaceFanSpeed: Int = -1,
    val slowDownLayerTime: Float = 8f,
    val slowDownMinSpeed: Float = 10f,
    val closeFanFirstLayers: Int = 1,
    val fullFanSpeedLayer: Int = 3,
    val pressureAdvanceEnabled: Boolean = false,
    val pressureAdvance: Float = 0f,
    val compatiblePrinters: List<String> = emptyList(),
    val diameter: Float = 1.75f,
    val density: Float = 1.24f,
    val costPerKilogram: Float = 0f,
    val shrinkageXyPercent: Float = 100f,
    val shrinkageZPercent: Float = 100f,
    val soluble: Boolean = false,
    val supportMaterial: Boolean = false,
    val minimalPurgeOnWipeTower: Float = 15f,
    val additionalCoolingFanSpeed: Int = 0,
    val loadingSpeed: Float = 28f,
    val loadingSpeedStart: Float = 3f,
    val unloadingSpeed: Float = 90f,
    val unloadingSpeedStart: Float = 100f,
    val toolchangeDelay: Float = 0f,
    val coolingMoves: Int = 4,
    val stampingLoadingSpeed: Float = 0f,
    val stampingDistance: Float = 0f,
    val coolingInitialSpeed: Float = 2.2f,
    val coolingFinalSpeed: Float = 3.4f,
    val rammingParameters: String = DEFAULT_FILAMENT_RAMMING_PARAMETERS,
    val multitoolRamming: Boolean = false,
    val multitoolRammingVolume: Float = 10f,
    val multitoolRammingFlow: Float = 10f,
    val softeningTemperature: Int = 100,
    val nozzleTemperatureRangeLow: Int = 190,
    val nozzleTemperatureRangeHigh: Int = 240,
    val chamberTemperatureControl: Boolean = false,
    val chamberTemperature: Int = 0,
    val airFiltration: Boolean = false,
    val duringPrintExhaustFanSpeed: Int = 60,
    val completePrintExhaustFanSpeed: Int = 80,
    val idleTemperature: Int = 0,
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

internal const val DEFAULT_FILAMENT_RAMMING_PARAMETERS =
    "120 100 6.6 6.8 7.2 7.6 7.9 8.2 8.7 9.4 9.9 10.0|" +
        " 0.05 6.6 0.45 6.8 0.95 7.8 1.45 8.3 1.95 9.7 2.45 10" +
        " 2.95 7.6 3.45 7.6 3.95 7.6 4.45 7.6 4.95 7.6"

internal fun FilamentProfile.bedTemperature(type: BuildPlateType): Int = when (type) {
    BuildPlateType.COOL -> coolPlateTemp
    BuildPlateType.ENGINEERING -> engineeringPlateTemp
    BuildPlateType.HIGH_TEMP -> bedTemp
    BuildPlateType.TEXTURED_PEI -> texturedPlateTemp
    BuildPlateType.TEXTURED_COOL -> texturedCoolPlateTemp
    BuildPlateType.GRAPHIC_EFFECT -> graphicEffectPlateTemp
    BuildPlateType.SUPER_TACK -> superTackPlateTemp
}

internal fun FilamentProfile.firstLayerBedTemperature(type: BuildPlateType): Int = when (type) {
    BuildPlateType.COOL -> firstLayerCoolPlateTemp
    BuildPlateType.ENGINEERING -> firstLayerEngineeringPlateTemp
    BuildPlateType.HIGH_TEMP -> firstLayerBedTemp
    BuildPlateType.TEXTURED_PEI -> firstLayerTexturedPlateTemp
    BuildPlateType.TEXTURED_COOL -> firstLayerTexturedCoolPlateTemp
    BuildPlateType.GRAPHIC_EFFECT -> firstLayerGraphicEffectPlateTemp
    BuildPlateType.SUPER_TACK -> firstLayerSuperTackPlateTemp
}

internal fun FilamentProfile.withBedTemperature(
    type: BuildPlateType,
    temperature: Int = bedTemperature(type),
    firstLayerTemperature: Int = firstLayerBedTemperature(type),
): FilamentProfile = when (type) {
    BuildPlateType.COOL -> copy(
        coolPlateTemp = temperature,
        firstLayerCoolPlateTemp = firstLayerTemperature,
    )
    BuildPlateType.ENGINEERING -> copy(
        engineeringPlateTemp = temperature,
        firstLayerEngineeringPlateTemp = firstLayerTemperature,
    )
    BuildPlateType.HIGH_TEMP -> copy(
        bedTemp = temperature,
        firstLayerBedTemp = firstLayerTemperature,
    )
    BuildPlateType.TEXTURED_PEI -> copy(
        texturedPlateTemp = temperature,
        firstLayerTexturedPlateTemp = firstLayerTemperature,
    )
    BuildPlateType.TEXTURED_COOL -> copy(
        texturedCoolPlateTemp = temperature,
        firstLayerTexturedCoolPlateTemp = firstLayerTemperature,
    )
    BuildPlateType.GRAPHIC_EFFECT -> copy(
        graphicEffectPlateTemp = temperature,
        firstLayerGraphicEffectPlateTemp = firstLayerTemperature,
    )
    BuildPlateType.SUPER_TACK -> copy(
        superTackPlateTemp = temperature,
        firstLayerSuperTackPlateTemp = firstLayerTemperature,
    )
}

data class BuildPlateSettings(
    val type: BuildPlateType = BuildPlateType.TEXTURED_PEI,
    val temperature: Int = 60,
    val firstLayerTemperature: Int = temperature,
) {
    fun withProfile(profile: FilamentProfile, newType: BuildPlateType = type) = BuildPlateSettings(
        type = newType,
        temperature = profile.bedTemperature(newType),
        firstLayerTemperature = profile.firstLayerBedTemperature(newType),
    )

    companion object {
        fun fromProfile(
            profile: FilamentProfile,
            type: BuildPlateType = BuildPlateType.TEXTURED_PEI,
        ) = BuildPlateSettings().withProfile(profile, type)
    }
}

internal fun FilamentProfile.hasCompatibleDiameter(other: FilamentProfile): Boolean =
    abs(diameter - other.diameter) < 0.001f

data class RetractionSettings(
    val length: Float,
    val speed: Float,
    val deretractSpeed: Float,
    val minimumTravel: Float,
    val whenChangingLayer: Boolean,
    val wipe: Boolean,
    val wipeDistance: Float,
    val beforeWipe: Float,
    val restartExtra: Float,
    val zHop: Float,
    val zHopType: String,
    val liftAbove: Float,
    val liftBelow: Float,
    val liftEnforce: String,
    val longRetractionWhenCut: Boolean,
    val retractionDistanceWhenCut: Float,
)

data class MultiMaterialSettings(
    val primeVolume: Float = 45f,
    val purgeVolumes: List<Float> = emptyList(),
    val primeTowerBrimWidth: Float = 3f,
    val wipeTowerNoSparseLayers: Boolean = false,
    val wipeTowerRotationAngle: Float = 0f,
    val wipeTowerBridging: Float = 10f,
    val wipeTowerExtraSpacing: Float = 100f,
    val wipeTowerExtraFlow: Float = 100f,
    val wipeTowerMaxPurgeSpeed: Float = 90f,
    val wipeTowerWallType: String = "rectangle",
    val wipeTowerConeAngle: Float = 30f,
    val wipeTowerExtraRibLength: Float = 0f,
    val wipeTowerRibWidth: Float = 8f,
    val wipeTowerFilletWall: Boolean = true,
    val singleExtruderMultiMaterialPriming: Boolean = false,
    val flushIntoInfill: Boolean = false,
    val flushIntoSupport: Boolean = true,
    val flushIntoObjects: Boolean = false,
    val oozePrevention: Boolean = false,
    val standbyTemperatureDelta: Int = -5,
    val preheatTime: Float = 30f,
    val preheatDeltaTemperature: Int = 0,
    val preheatSteps: Int = 1,
    val interfaceShells: Boolean = false,
    val segmentedRegionMaxWidth: Float = 0f,
    val segmentedRegionInterlockingDepth: Float = 0f,
    val interlockingBeam: Boolean = false,
    val interlockingBeamWidth: Float = 0.8f,
    val interlockingOrientation: Float = 22.5f,
    val interlockingBeamLayerCount: Int = 2,
    val interlockingDepth: Int = 2,
    val interlockingBoundaryAvoidance: Int = 2,
) {
    fun resolvedPurgeVolumes(slotCount: Int): List<Float> {
        val targetSize = slotCount.coerceIn(1, MAX_FILAMENT_SLOTS)
        val sourceSize = (1..MAX_FILAMENT_SLOTS).firstOrNull { it * it == purgeVolumes.size } ?: 0
        return List(targetSize * targetSize) { index ->
            val from = index / targetSize
            val to = index % targetSize
            when {
                from == to -> 0f
                from < sourceSize && to < sourceSize -> purgeVolumes[from * sourceSize + to]
                    .takeIf(Float::isFinite)
                    ?.coerceIn(MIN_PURGE_VOLUME, MAX_PURGE_VOLUME)
                    ?: DEFAULT_PURGE_VOLUME
                else -> DEFAULT_PURGE_VOLUME
            }
        }
    }

    fun resizedPurgeVolumes(slotCount: Int): MultiMaterialSettings = copy(
        purgeVolumes = resolvedPurgeVolumes(slotCount),
    )

    fun withPurgeVolume(slotCount: Int, fromSlot: Int, toSlot: Int, volume: Float): MultiMaterialSettings {
        val size = slotCount.coerceIn(1, MAX_FILAMENT_SLOTS)
        require(fromSlot in 0 until size && toSlot in 0 until size && fromSlot != toSlot) {
            "Purge transition is unavailable"
        }
        val updated = resolvedPurgeVolumes(size).toMutableList()
        updated[fromSlot * size + toSlot] = volume
            .takeIf(Float::isFinite)
            ?.coerceIn(MIN_PURGE_VOLUME, MAX_PURGE_VOLUME)
            ?: DEFAULT_PURGE_VOLUME
        return copy(purgeVolumes = updated)
    }
}

internal const val DEFAULT_PURGE_VOLUME = 140f
internal const val MIN_PURGE_VOLUME = 0f
internal const val MAX_PURGE_VOLUME = 1_000f
internal val OVERHANG_FAN_THRESHOLDS = listOf("0%", "10%", "25%", "50%", "75%", "95%")
internal val RETRACT_LIFT_ENFORCEMENTS = listOf("all", "top", "bottom", "top_bottom")

data class FeatureFilamentSettings(
    val infillOverrideEnabled: Boolean = false,
    val baseFirstLayers: Int = 0,
    val baseLastLayers: Int = 0,
    val sparseInfillFilament: Int = 1,
    val wallFilament: Int = 1,
    val solidInfillFilament: Int = 1,
    val wipeTowerFilament: Int = 0,
)

private fun FeatureFilamentSettings.boundedTo(slotCount: Int): FeatureFilamentSettings {
    val maximum = slotCount.coerceAtLeast(1)
    return copy(
        baseFirstLayers = baseFirstLayers.coerceAtLeast(0),
        baseLastLayers = baseLastLayers.coerceAtLeast(0),
        sparseInfillFilament = sparseInfillFilament.coerceIn(1, maximum),
        wallFilament = wallFilament.coerceIn(1, maximum),
        solidInfillFilament = solidInfillFilament.coerceIn(1, maximum),
        wipeTowerFilament = wipeTowerFilament.coerceIn(0, maximum),
    )
}

internal fun FeatureFilamentSettings.nativeVolumeSlot(projectSlot: Int): Int {
    require(projectSlot >= 0) { "Filament slot must be non-negative" }
    if (projectSlot != 0) return projectSlot + 1
    val routesDefaultVolumeByFeature = wallFilament != 1 ||
        solidInfillFilament != wallFilament ||
        (infillOverrideEnabled && sparseInfillFilament != wallFilament)
    return if (routesDefaultVolumeByFeature) 0 else 1
}

data class GcodeSettings(
    val arcFitting: Boolean = false,
    val labelObjects: Boolean = true,
    val excludeObjects: Boolean = false,
    val verboseComments: Boolean = false,
    val initialLayerTravelSpeed: Float = 100f,
    val initialLayerTravelSpeedPercent: Boolean = true,
    val slowDownLayers: Int = 0,
    val accelToDecelEnabled: Boolean = true,
    val accelToDecelFactor: Float = 50f,
)

data class SurfaceDensitySettings(
    val topPercent: Float = 100f,
    val bottomPercent: Float = 100f,
)

data class ExtrusionRateSmoothingSettings(
    val maximumSlope: Float = 0f,
    val segmentLength: Float = 3f,
    val externalOnly: Boolean = false,
)

data class BrimEarSettings(
    val maximumAngle: Float = 125f,
    val detectionRadius: Float = 1f,
)

data class PolyholeSettings(
    val enabled: Boolean = false,
    val detectionMargin: Float = 0.01f,
    val detectionMarginPercent: Boolean = false,
    val twist: Boolean = true,
)

data class ScarfSeamSettings(
    val type: String = "none",
    val conditional: Boolean = false,
    val angleThreshold: Int = 155,
    val overhangThreshold: Float = 40f,
    val speed: Float = 100f,
    val speedPercent: Boolean = true,
    val flowRatio: Float = 1f,
    val startHeight: Float = 0f,
    val startHeightPercent: Boolean = false,
    val entireLoop: Boolean = false,
    val length: Float = 20f,
    val steps: Int = 10,
    val innerWalls: Boolean = false,
)

data class PrecisionSettings(
    val mode: String = "regular",
    val closingRadius: Float = 0.049f,
    val preciseZHeight: Boolean = false,
    val polyholes: PolyholeSettings = PolyholeSettings(),
    val minimumWallWidth: Float = 85f,
    val firstLayerMinimumWallWidth: Float = 85f,
    val printableOverhangs: PrintableOverhangSettings = PrintableOverhangSettings(),
    val brimEars: BrimEarSettings = BrimEarSettings(),
)

data class PrintableOverhangSettings(
    val enabled: Boolean = false,
    val maximumAngle: Float = 55f,
    val holeArea: Float = 0f,
)

data class IroningSettings(
    val type: String = "no ironing",
    val pattern: String = "rectilinear",
    val flow: Float = 10f,
    val spacing: Float = 0.1f,
    val inset: Float = 0f,
    val speed: Float = 20f,
    val angle: Float = -1f,
)

data class SupportCoverageSettings(
    val onBuildPlateOnly: Boolean = false,
    val criticalRegionsOnly: Boolean = false,
    val removeSmallOverhangs: Boolean = true,
)

data class SupportAdvancedSettings(
    val patternAngle: Float = 0f,
    val thresholdOverlap: Float = 50f,
    val thresholdOverlapPercent: Boolean = true,
    val objectFirstLayerGap: Float = 0.2f,
    val avoidInterfaceFilamentForBase: Boolean = true,
    val ironingEnabled: Boolean = false,
    val ironingPattern: String = "rectilinear",
    val ironingFlow: Float = 10f,
    val ironingSpacing: Float = 0.1f,
)

data class FuzzySkinSettings(
    val type: String = "none",
    val firstLayer: Boolean = false,
    val pointDistance: Float = 0.3f,
    val thickness: Float = 0.2f,
    val mode: String = "displacement",
    val noiseType: String = "classic",
    val scale: Float = 1f,
    val octaves: Int = 4,
    val persistence: Float = 0.5f,
)

internal fun FilamentProfile.resolveRetraction(printer: PrinterProfile) = RetractionSettings(
    length = retractLength ?: printer.retractLength,
    speed = retractSpeed ?: printer.retractSpeed,
    deretractSpeed = deretractSpeed ?: printer.deretractSpeed,
    minimumTravel = retractionMinimumTravel ?: printer.retractionMinimumTravel,
    whenChangingLayer = retractWhenChangingLayer ?: printer.retractWhenChangingLayer,
    wipe = wipeWhileRetracting ?: printer.wipeWhileRetracting,
    wipeDistance = wipeDistance ?: printer.wipeDistance,
    beforeWipe = retractBeforeWipe ?: printer.retractBeforeWipe,
    restartExtra = retractRestartExtra ?: printer.retractRestartExtra,
    zHop = zHop ?: printer.zHop,
    zHopType = zHopType ?: printer.zHopType,
    liftAbove = retractLiftAbove ?: printer.retractLiftAbove,
    liftBelow = retractLiftBelow ?: printer.retractLiftBelow,
    liftEnforce = retractLiftEnforce ?: printer.retractLiftEnforce,
    longRetractionWhenCut = !printer.useFirmwareRetraction && when (printer.longRetractionWhenCutLevel) {
        1 -> printer.longRetractionWhenCut
        2 -> longRetractionWhenCut ?: printer.longRetractionWhenCut
        else -> false
    },
    retractionDistanceWhenCut = when (printer.longRetractionWhenCutLevel) {
        2 -> retractionDistanceWhenCut ?: printer.retractionDistanceWhenCut
        else -> printer.retractionDistanceWhenCut
    },
)

data class QualityProfile(
    val id: String,
    val name: String,
    val layerHeightMm: Float,
    val firstLayerHeightMm: Float,
    val perimeters: Int,
    val fillDensity: Float,
    val printSpeed: Float,
    val nozzleDiameter: Float,
    val innerWallSpeed: Float = 0f,
    val sparseInfillSpeed: Float = 0f,
    val internalSolidInfillSpeed: Float = 0f,
    val topSurfaceSpeed: Float = 0f,
    val supportSpeed: Float = 0f,
    val bridgeSpeed: Float = 0f,
    val gapInfillSpeed: Float = 0f,
    val firstLayerInfillSpeed: Float = 0f,
    val supportInterfaceSpeed: Float = 0f,
    val internalBridgeSpeed: Float = 150f,
    val internalBridgeSpeedPercent: Boolean = true,
    val overhangSpeedEnabled: Boolean = true,
    val overhangSpeed1: Float = 55f,
    val overhangSpeed1Percent: Boolean = false,
    val overhangSpeed2: Float = 30f,
    val overhangSpeed2Percent: Boolean = false,
    val overhangSpeed3: Float = 10f,
    val overhangSpeed3Percent: Boolean = false,
    val overhangSpeed4: Float = 10f,
    val overhangSpeed4Percent: Boolean = false,
    val printFlowRatio: Float = 1f,
    val bridgeFlowRatio: Float = 1f,
    val internalBridgeFlowRatio: Float = 1f,
    val topSurfaceFlowRatio: Float = 1f,
    val bottomSurfaceFlowRatio: Float = 1f,
    val bridgeDensity: Float = 100f,
    val internalBridgeDensity: Float = 100f,
    val bridgeAngle: Float = 0f,
    val internalBridgeAngle: Float = 0f,
    val bridgeNoSupport: Boolean = false,
    val thickBridges: Boolean = false,
    val thickInternalBridges: Boolean = true,
    val extraBridgeLayer: String = "disabled",
    val internalBridgeFilter: String = "disabled",
    val defaultAcceleration: Float = 0f,
    val outerWallAcceleration: Float = 0f,
    val innerWallAcceleration: Float = 0f,
    val topSurfaceAcceleration: Float = 0f,
    val travelAcceleration: Float = 0f,
    val firstLayerAcceleration: Float = 0f,
    val bridgeAcceleration: Float = 50f,
    val bridgeAccelerationPercent: Boolean = true,
    val sparseInfillAcceleration: Float = 100f,
    val sparseInfillAccelerationPercent: Boolean = true,
    val internalSolidInfillAcceleration: Float = 100f,
    val internalSolidInfillAccelerationPercent: Boolean = true,
    val defaultJerk: Float = 0f,
    val outerWallJerk: Float = 9f,
    val innerWallJerk: Float = 9f,
    val topSurfaceJerk: Float = 9f,
    val infillJerk: Float = 9f,
    val firstLayerJerk: Float = 9f,
    val travelJerk: Float = 12f,
    val extrusionRateSmoothing: ExtrusionRateSmoothingSettings = ExtrusionRateSmoothingSettings(),
    val fuzzySkin: FuzzySkinSettings = FuzzySkinSettings(),
    val supportEnabled: Boolean = false,
    val brimType: String = "no_brim",
    val brimWidth: Float = 0f,
    val brimObjectGap: Float = 0f,
    val raftLayers: Int = 0,
    val raftContactDistance: Float = 0.1f,
    val raftExpansion: Float = 1.5f,
    val raftFirstLayerDensity: Float = 90f,
    val raftFirstLayerExpansion: Float = 2f,
    val builtIn: Boolean = false,
    val topSolidLayers: Int = 5,
    val bottomSolidLayers: Int = 4,
    val topShellThickness: Float = 0f,
    val bottomShellThickness: Float = 0f,
    val surfaceDensity: SurfaceDensitySettings = SurfaceDensitySettings(),
    val fillPattern: String = "gyroid",
    val topSurfacePattern: String = "monotonicline",
    val bottomSurfacePattern: String = "monotonic",
    val internalSolidInfillPattern: String = "monotonic",
    val infillFirst: Boolean = false,
    val infillWallOverlap: Float = 15f,
    val topBottomInfillWallOverlap: Float = 25f,
    val infillCombination: Boolean = false,
    val infillCombinationMaxLayerHeight: Float = 100f,
    val infillCombinationMaxLayerHeightPercent: Boolean = true,
    val infillDirection: Float = 45f,
    val solidInfillDirection: Float = 45f,
    val sparseInfillRotationTemplate: String = "",
    val solidInfillRotationTemplate: String = "",
    val alignInfillDirectionToModel: Boolean = false,
    val minimumSparseInfillArea: Float = 15f,
    val infillAnchor: Float = 400f,
    val infillAnchorPercent: Boolean = true,
    val infillAnchorMax: Float = 20f,
    val infillAnchorMaxPercent: Boolean = false,
    val skeletonInfillDensity: Float = 25f,
    val skinInfillDensity: Float = 25f,
    val skinInfillDepth: Float = 2f,
    val infillLockDepth: Float = 1f,
    val infillShiftStep: Float = 0.4f,
    val symmetricInfillYAxis: Boolean = false,
    val skinInfillLineWidth: Float = 100f,
    val skinInfillLineWidthPercent: Boolean = true,
    val skeletonInfillLineWidth: Float = 100f,
    val skeletonInfillLineWidthPercent: Boolean = true,
    val gapFillTarget: String = "nowhere",
    val filterOutGapFill: Float = 0f,
    val reduceCrossingWall: Boolean = false,
    val maxTravelDetourDistance: Float = 0f,
    val maxTravelDetourDistancePercent: Boolean = false,
    val reduceInfillRetraction: Boolean = false,
    val travelSpeed: Float = 500f,
    val travelSpeedZ: Float = 0f,
    val firstLayerSpeed: Float = 50f,
    val supportType: String = "normal(auto)",
    val supportAngle: Float = 45f,
    val supportInterfaceTopLayers: Int = 3,
    val supportInterfaceBottomLayers: Int = 0,
    val supportInterfaceSpacing: Float = 0.5f,
    val supportBottomInterfaceSpacing: Float = 0.5f,
    val supportTopZDistance: Float = 0.2f,
    val supportBottomZDistance: Float = 0.2f,
    val supportObjectXYDistance: Float = 0.35f,
    val supportBasePattern: String = "default",
    val supportInterfacePattern: String = "auto",
    val supportStyle: String = "default",
    val supportCoverage: SupportCoverageSettings = SupportCoverageSettings(),
    val supportAdvanced: SupportAdvancedSettings = SupportAdvancedSettings(),
    val supportBasePatternSpacing: Float = 2.5f,
    val supportExpansion: Float = 0f,
    val supportInterfaceLoopPattern: Boolean = false,
    val independentSupportLayerHeight: Boolean = true,
    val treeSupportBranchAngle: Float = 40f,
    val treeSupportBranchDistance: Float = 5f,
    val treeSupportBranchDiameter: Float = 5f,
    val treeSupportWallCount: Int = 0,
    val treeSupportTipDiameter: Float = 0.8f,
    val treeSupportPreferredBranchAngle: Float = 25f,
    val treeSupportBranchDensity: Float = 30f,
    val treeSupportOrganicBranchAngle: Float = 40f,
    val treeSupportOrganicBranchDistance: Float = 1f,
    val treeSupportOrganicBranchDiameter: Float = 2f,
    val treeSupportBranchDiameterAngle: Float = 5f,
    val treeSupportAdaptiveLayerHeight: Boolean = true,
    val treeSupportAutoBrim: Boolean = true,
    val treeSupportBrimWidth: Float = 3f,
    val supportFilament: Int = 0,
    val supportInterfaceFilament: Int = 0,
    val featureFilaments: FeatureFilamentSettings = FeatureFilamentSettings(),
    val wipeTowerEnabled: Boolean = false,
    val wipeTowerWidth: Float = 60f,
    val multiMaterial: MultiMaterialSettings = MultiMaterialSettings(),
    val gcodeSettings: GcodeSettings = GcodeSettings(),
    val skirtLoops: Int = 0,
    val skirtDistance: Float = 6f,
    val skirtStartAngle: Float = -135f,
    val skirtHeight: Int = 1,
    val skirtSpeed: Float = 50f,
    val minimumSkirtLength: Float = 0f,
    val draftShield: String = "disabled",
    val outerWallLineWidth: Float = 0f,
    val innerWallLineWidth: Float = 0f,
    val topSurfaceLineWidth: Float = 0f,
    val sparseInfillLineWidth: Float = 0f,
    val internalSolidInfillLineWidth: Float = 0f,
    val supportLineWidth: Float = 0f,
    val initialLayerLineWidth: Float = 0f,
    val smallPerimeterSpeed: Float = 50f,
    val smallPerimeterSpeedPercent: Boolean = true,
    val smallPerimeterThreshold: Float = 0f,
    val slowdownForCurledPerimeters: Boolean = true,
    val resolution: Float = 0.01f,
    val precision: PrecisionSettings = PrecisionSettings(),
    val seamPosition: String = "aligned",
    val staggeredInnerSeams: Boolean = false,
    val seamGap: Float = 10f,
    val seamGapPercent: Boolean = true,
    val scarfSeam: ScarfSeamSettings = ScarfSeamSettings(),
    val wipeBeforeExternalLoop: Boolean = false,
    val wipeOnLoops: Boolean = false,
    val roleBasedWipeSpeed: Boolean = true,
    val wipeSpeed: Float = 80f,
    val wipeSpeedPercent: Boolean = true,
    val ironing: IroningSettings = IroningSettings(),
    val wallGenerator: String = "arachne",
    val wallTransitionLength: Float = 100f,
    val wallTransitionFilterDeviation: Float = 25f,
    val wallTransitionAngle: Float = 10f,
    val wallDistributionCount: Int = 1,
    val minimumFeatureSize: Float = 25f,
    val minimumWallLengthFactor: Float = 0.5f,
    val wallSequence: String = "inner-outer",
    val wallDirection: String = "auto",
    val detectThinWalls: Boolean = false,
    val detectOverhangWalls: Boolean = true,
    val onlyOneWallOnTop: Boolean = false,
    val minWidthTopSurface: Float = 300f,
    val minWidthTopSurfacePercent: Boolean = true,
    val onlyOneWallFirstLayer: Boolean = false,
    val extraPerimetersOnOverhangs: Boolean = false,
    val overhangReverse: Boolean = false,
    val overhangReverseInternalOnly: Boolean = false,
    val overhangReverseThreshold: Float = 50f,
    val overhangReverseThresholdPercent: Boolean = true,
    val counterboreHoleBridging: String = "none",
    val alternateExtraWall: Boolean = false,
    val ensureVerticalShellThickness: String = "ensure_all",
    val detectNarrowInternalSolidInfill: Boolean = true,
    val xyHoleCompensation: Float = 0f,
    val xyContourCompensation: Float = 0f,
    val elephantFootCompensation: Float = 0f,
    val elephantFootCompensationLayers: Int = 1,
    val maxBridgeLength: Float = 10f,
    val preciseOuterWalls: Boolean = true,
    val printSequence: String = "by layer",
    val printOrder: String = "default",
    val spiralMode: Boolean = false,
    val spiralModeSmooth: Boolean = false,
    val spiralModeMaxXySmoothing: Float = 200f,
    val spiralModeMaxXySmoothingPercent: Boolean = true,
    val spiralStartingFlowRatio: Float = 0f,
    val spiralFinishingFlowRatio: Float = 0f,
    val brand: String? = null,
    val compatiblePrinters: List<String> = emptyList(),
) {
    val printableOverhangs: PrintableOverhangSettings
        get() = precision.printableOverhangs

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

data class JerkSettings(
    val defaultJerk: Float = 0f,
    val outerWallJerk: Float = 9f,
    val innerWallJerk: Float = 9f,
    val topSurfaceJerk: Float = 9f,
    val infillJerk: Float = 9f,
    val firstLayerJerk: Float = 9f,
    val travelJerk: Float = 12f,
)

private fun QualityProfile.jerkSettings() = JerkSettings(
    defaultJerk = defaultJerk,
    outerWallJerk = outerWallJerk,
    innerWallJerk = innerWallJerk,
    topSurfaceJerk = topSurfaceJerk,
    infillJerk = infillJerk,
    firstLayerJerk = firstLayerJerk,
    travelJerk = travelJerk,
)

data class ProfileCatalog(
    val printers: List<PrinterProfile> = PrinterProfile.builtIns,
    val filaments: List<FilamentProfile> = FilamentProfile.builtIns,
    val slicing: List<QualityProfile> = QualityProfile.builtIns,
    val schemaVersion: Int = 63,
    val sourceRevision: String = "ducky-fallback",
    val rejectedCount: Int = 0,
)

data class SliceOptions(
    val printerProfile: PrinterProfile = PrinterProfile.U1_04,
    val filamentProfile: FilamentProfile = FilamentProfile.PLA,
    val filamentSlots: List<FilamentProfile> = listOf(filamentProfile),
    val quality: QualityProfile = QualityProfile.STANDARD,
    val bedSizeX: Float = printerProfile.bedSizeX,
    val bedSizeY: Float = printerProfile.bedSizeY,
    val bedOriginX: Float = printerProfile.bedOriginX,
    val bedOriginY: Float = printerProfile.bedOriginY,
    val bedPolygon: List<Float> = printerProfile.bedPolygon,
    val bedExcludeArea: List<Float> = printerProfile.bedExcludeArea,
    val maxPrintHeight: Float = printerProfile.maxPrintHeight,
    val nozzleDiameter: Float = printerProfile.nozzleDiameter,
    val nozzleTemp: Int = filamentProfile.nozzleTemp,
    val firstLayerNozzleTemp: Int = filamentProfile.firstLayerNozzleTemp,
    val buildPlate: BuildPlateSettings = BuildPlateSettings.fromProfile(filamentProfile),
    val filamentDiameter: Float = 1.75f,
    val flowRatio: Float = filamentProfile.flowRatio,
    val maxVolumetricSpeed: Float = filamentProfile.maxVolumetricSpeed,
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
    val innerWallSpeed: Float = quality.innerWallSpeed.takeIf { it > 0f } ?: printSpeed * 1.5f,
    val sparseInfillSpeed: Float = quality.sparseInfillSpeed.takeIf { it > 0f } ?: printSpeed * 1.35f,
    val internalSolidInfillSpeed: Float = quality.internalSolidInfillSpeed.takeIf { it > 0f } ?: printSpeed * 1.25f,
    val topSurfaceSpeed: Float = quality.topSurfaceSpeed.takeIf { it > 0f } ?: printSpeed,
    val supportSpeed: Float = quality.supportSpeed.takeIf { it > 0f } ?: 100f,
    val bridgeSpeed: Float = quality.bridgeSpeed.takeIf { it > 0f } ?: 50f,
    val gapInfillSpeed: Float = quality.gapInfillSpeed.takeIf { it > 0f } ?: printSpeed * 1.25f,
    val firstLayerInfillSpeed: Float = quality.firstLayerInfillSpeed.takeIf { it > 0f }
        ?: quality.firstLayerSpeed * 2.1f,
    val supportInterfaceSpeed: Float = quality.supportInterfaceSpeed.takeIf { it > 0f }
        ?: supportSpeed * 0.8f,
    val internalBridgeSpeed: Float = quality.internalBridgeSpeed,
    val internalBridgeSpeedPercent: Boolean = quality.internalBridgeSpeedPercent,
    val overhangSpeedEnabled: Boolean = quality.overhangSpeedEnabled,
    val overhangSpeed1: Float = quality.overhangSpeed1,
    val overhangSpeed1Percent: Boolean = quality.overhangSpeed1Percent,
    val overhangSpeed2: Float = quality.overhangSpeed2,
    val overhangSpeed2Percent: Boolean = quality.overhangSpeed2Percent,
    val overhangSpeed3: Float = quality.overhangSpeed3,
    val overhangSpeed3Percent: Boolean = quality.overhangSpeed3Percent,
    val overhangSpeed4: Float = quality.overhangSpeed4,
    val overhangSpeed4Percent: Boolean = quality.overhangSpeed4Percent,
    val printFlowRatio: Float = quality.printFlowRatio,
    val bridgeFlowRatio: Float = quality.bridgeFlowRatio,
    val internalBridgeFlowRatio: Float = quality.internalBridgeFlowRatio,
    val topSurfaceFlowRatio: Float = quality.topSurfaceFlowRatio,
    val bottomSurfaceFlowRatio: Float = quality.bottomSurfaceFlowRatio,
    val bridgeDensity: Float = quality.bridgeDensity,
    val internalBridgeDensity: Float = quality.internalBridgeDensity,
    val bridgeAngle: Float = quality.bridgeAngle,
    val internalBridgeAngle: Float = quality.internalBridgeAngle,
    val bridgeNoSupport: Boolean = quality.bridgeNoSupport,
    val thickBridges: Boolean = quality.thickBridges,
    val thickInternalBridges: Boolean = quality.thickInternalBridges,
    val extraBridgeLayer: String = quality.extraBridgeLayer,
    val internalBridgeFilter: String = quality.internalBridgeFilter,
    val defaultAcceleration: Float = quality.defaultAcceleration,
    val outerWallAcceleration: Float = quality.outerWallAcceleration,
    val innerWallAcceleration: Float = quality.innerWallAcceleration,
    val topSurfaceAcceleration: Float = quality.topSurfaceAcceleration,
    val travelAcceleration: Float = quality.travelAcceleration,
    val firstLayerAcceleration: Float = quality.firstLayerAcceleration,
    val bridgeAcceleration: Float = quality.bridgeAcceleration,
    val bridgeAccelerationPercent: Boolean = quality.bridgeAccelerationPercent,
    val sparseInfillAcceleration: Float = quality.sparseInfillAcceleration,
    val sparseInfillAccelerationPercent: Boolean = quality.sparseInfillAccelerationPercent,
    val internalSolidInfillAcceleration: Float = quality.internalSolidInfillAcceleration,
    val internalSolidInfillAccelerationPercent: Boolean = quality.internalSolidInfillAccelerationPercent,
    val jerk: JerkSettings = quality.jerkSettings(),
    val fuzzySkin: FuzzySkinSettings = quality.fuzzySkin,
    val topSolidLayers: Int = quality.topSolidLayers,
    val bottomSolidLayers: Int = quality.bottomSolidLayers,
    val topShellThickness: Float = quality.topShellThickness,
    val bottomShellThickness: Float = quality.bottomShellThickness,
    val fillPattern: String = quality.fillPattern,
    val topSurfacePattern: String = quality.topSurfacePattern,
    val bottomSurfacePattern: String = quality.bottomSurfacePattern,
    val internalSolidInfillPattern: String = quality.internalSolidInfillPattern,
    val infillFirst: Boolean = quality.infillFirst,
    val infillWallOverlap: Float = quality.infillWallOverlap,
    val topBottomInfillWallOverlap: Float = quality.topBottomInfillWallOverlap,
    val infillCombination: Boolean = quality.infillCombination,
    val infillCombinationMaxLayerHeight: Float = quality.infillCombinationMaxLayerHeight,
    val infillCombinationMaxLayerHeightPercent: Boolean = quality.infillCombinationMaxLayerHeightPercent,
    val infillDirection: Float = quality.infillDirection,
    val solidInfillDirection: Float = quality.solidInfillDirection,
    val alignInfillDirectionToModel: Boolean = quality.alignInfillDirectionToModel,
    val minimumSparseInfillArea: Float = quality.minimumSparseInfillArea,
    val infillAnchor: Float = quality.infillAnchor,
    val infillAnchorPercent: Boolean = quality.infillAnchorPercent,
    val infillAnchorMax: Float = quality.infillAnchorMax,
    val infillAnchorMaxPercent: Boolean = quality.infillAnchorMaxPercent,
    val gapFillTarget: String = quality.gapFillTarget,
    val filterOutGapFill: Float = quality.filterOutGapFill,
    val reduceCrossingWall: Boolean = quality.reduceCrossingWall,
    val maxTravelDetourDistance: Float = quality.maxTravelDetourDistance,
    val maxTravelDetourDistancePercent: Boolean = quality.maxTravelDetourDistancePercent,
    val reduceInfillRetraction: Boolean = quality.reduceInfillRetraction,
    val travelSpeed: Float = quality.travelSpeed,
    val firstLayerSpeed: Float = quality.firstLayerSpeed,
    val supportEnabled: Boolean = quality.supportEnabled,
    val supportType: String = quality.supportType,
    val supportAngle: Float = quality.supportAngle,
    val supportInterfaceTopLayers: Int = quality.supportInterfaceTopLayers,
    val supportInterfaceBottomLayers: Int = quality.supportInterfaceBottomLayers,
    val supportInterfaceSpacing: Float = quality.supportInterfaceSpacing,
    val supportBottomInterfaceSpacing: Float = quality.supportBottomInterfaceSpacing,
    val supportTopZDistance: Float = quality.supportTopZDistance,
    val supportBottomZDistance: Float = quality.supportBottomZDistance,
    val supportObjectXYDistance: Float = quality.supportObjectXYDistance,
    val supportBasePattern: String = quality.supportBasePattern,
    val supportInterfacePattern: String = quality.supportInterfacePattern,
    val supportStyle: String = quality.supportStyle,
    val supportCoverage: SupportCoverageSettings = quality.supportCoverage,
    val supportAdvanced: SupportAdvancedSettings = quality.supportAdvanced,
    val supportBasePatternSpacing: Float = quality.supportBasePatternSpacing,
    val supportExpansion: Float = quality.supportExpansion,
    val supportInterfaceLoopPattern: Boolean = quality.supportInterfaceLoopPattern,
    val independentSupportLayerHeight: Boolean = quality.independentSupportLayerHeight,
    val treeSupportBranchAngle: Float = quality.treeSupportBranchAngle,
    val treeSupportBranchDistance: Float = quality.treeSupportBranchDistance,
    val treeSupportBranchDiameter: Float = quality.treeSupportBranchDiameter,
    val treeSupportWallCount: Int = quality.treeSupportWallCount,
    val treeSupportTipDiameter: Float = quality.treeSupportTipDiameter,
    val treeSupportPreferredBranchAngle: Float = quality.treeSupportPreferredBranchAngle,
    val treeSupportBranchDensity: Float = quality.treeSupportBranchDensity,
    val treeSupportOrganicBranchAngle: Float = quality.treeSupportOrganicBranchAngle,
    val treeSupportOrganicBranchDistance: Float = quality.treeSupportOrganicBranchDistance,
    val treeSupportOrganicBranchDiameter: Float = quality.treeSupportOrganicBranchDiameter,
    val treeSupportBranchDiameterAngle: Float = quality.treeSupportBranchDiameterAngle,
    val treeSupportAdaptiveLayerHeight: Boolean = quality.treeSupportAdaptiveLayerHeight,
    val treeSupportAutoBrim: Boolean = quality.treeSupportAutoBrim,
    val treeSupportBrimWidth: Float = quality.treeSupportBrimWidth,
    val supportFilament: Int = quality.supportFilament,
    val supportInterfaceFilament: Int = quality.supportInterfaceFilament,
    val featureFilaments: FeatureFilamentSettings = quality.featureFilaments,
    val wipeTowerEnabled: Boolean = quality.wipeTowerEnabled,
    val wipeTowerWidth: Float = quality.wipeTowerWidth,
    val multiMaterial: MultiMaterialSettings = quality.multiMaterial,
    val gcodeSettings: GcodeSettings = quality.gcodeSettings,
    val skirtLoops: Int = quality.skirtLoops,
    val skirtDistance: Float = quality.skirtDistance,
    val skirtHeight: Int = quality.skirtHeight,
    val skirtSpeed: Float = quality.skirtSpeed,
    val minimumSkirtLength: Float = quality.minimumSkirtLength,
    val draftShield: String = quality.draftShield,
    val brimType: String = quality.brimType,
    val brimWidth: Float = quality.brimWidth,
    val brimObjectGap: Float = quality.brimObjectGap,
    val raftLayers: Int = quality.raftLayers,
    val raftContactDistance: Float = quality.raftContactDistance,
    val raftExpansion: Float = quality.raftExpansion,
    val raftFirstLayerDensity: Float = quality.raftFirstLayerDensity,
    val raftFirstLayerExpansion: Float = quality.raftFirstLayerExpansion,
    val outerWallLineWidth: Float = quality.outerWallLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.05f,
    val innerWallLineWidth: Float = quality.innerWallLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.125f,
    val topSurfaceLineWidth: Float = quality.topSurfaceLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.05f,
    val sparseInfillLineWidth: Float = quality.sparseInfillLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.125f,
    val internalSolidInfillLineWidth: Float = quality.internalSolidInfillLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.125f,
    val supportLineWidth: Float = quality.supportLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.05f,
    val initialLayerLineWidth: Float = quality.initialLayerLineWidth.takeIf { it > 0f }
        ?: nozzleDiameter * 1.25f,
    val smallPerimeterSpeed: Float = quality.smallPerimeterSpeed,
    val smallPerimeterSpeedPercent: Boolean = quality.smallPerimeterSpeedPercent,
    val smallPerimeterThreshold: Float = quality.smallPerimeterThreshold,
    val slowdownForCurledPerimeters: Boolean = quality.slowdownForCurledPerimeters,
    val resolution: Float = quality.resolution,
    val precision: PrecisionSettings = quality.precision,
    val seamPosition: String = quality.seamPosition,
    val staggeredInnerSeams: Boolean = quality.staggeredInnerSeams,
    val seamGap: Float = quality.seamGap,
    val seamGapPercent: Boolean = quality.seamGapPercent,
    val scarfSeam: ScarfSeamSettings = quality.scarfSeam,
    val wipeBeforeExternalLoop: Boolean = quality.wipeBeforeExternalLoop,
    val wipeOnLoops: Boolean = quality.wipeOnLoops,
    val roleBasedWipeSpeed: Boolean = quality.roleBasedWipeSpeed,
    val wipeSpeed: Float = quality.wipeSpeed,
    val wipeSpeedPercent: Boolean = quality.wipeSpeedPercent,
    val ironing: IroningSettings = quality.ironing,
    val wallGenerator: String = quality.wallGenerator,
    val wallTransitionLength: Float = quality.wallTransitionLength,
    val wallTransitionFilterDeviation: Float = quality.wallTransitionFilterDeviation,
    val wallTransitionAngle: Float = quality.wallTransitionAngle,
    val wallDistributionCount: Int = quality.wallDistributionCount,
    val minimumFeatureSize: Float = quality.minimumFeatureSize,
    val minimumWallLengthFactor: Float = quality.minimumWallLengthFactor,
    val wallSequence: String = quality.wallSequence,
    val wallDirection: String = quality.wallDirection,
    val detectThinWalls: Boolean = quality.detectThinWalls,
    val detectOverhangWalls: Boolean = quality.detectOverhangWalls,
    val onlyOneWallOnTop: Boolean = quality.onlyOneWallOnTop,
    val minWidthTopSurface: Float = quality.minWidthTopSurface,
    val minWidthTopSurfacePercent: Boolean = quality.minWidthTopSurfacePercent,
    val onlyOneWallFirstLayer: Boolean = quality.onlyOneWallFirstLayer,
    val extraPerimetersOnOverhangs: Boolean = quality.extraPerimetersOnOverhangs,
    val overhangReverse: Boolean = quality.overhangReverse,
    val overhangReverseInternalOnly: Boolean = quality.overhangReverseInternalOnly,
    val overhangReverseThreshold: Float = quality.overhangReverseThreshold,
    val overhangReverseThresholdPercent: Boolean = quality.overhangReverseThresholdPercent,
    val counterboreHoleBridging: String = quality.counterboreHoleBridging,
    val alternateExtraWall: Boolean = quality.alternateExtraWall,
    val ensureVerticalShellThickness: String = quality.ensureVerticalShellThickness,
    val detectNarrowInternalSolidInfill: Boolean = quality.detectNarrowInternalSolidInfill,
    val xyHoleCompensation: Float = quality.xyHoleCompensation,
    val xyContourCompensation: Float = quality.xyContourCompensation,
    val elephantFootCompensation: Float = quality.elephantFootCompensation,
    val elephantFootCompensationLayers: Int = quality.elephantFootCompensationLayers,
    val maxBridgeLength: Float = quality.maxBridgeLength,
    val preciseOuterWalls: Boolean = quality.preciseOuterWalls,
    val printSequence: String = quality.printSequence,
    val printOrder: String = quality.printOrder,
    val spiralMode: Boolean = quality.spiralMode,
    val spiralModeSmooth: Boolean = quality.spiralModeSmooth,
    val spiralModeMaxXySmoothing: Float = quality.spiralModeMaxXySmoothing,
    val spiralModeMaxXySmoothingPercent: Boolean = quality.spiralModeMaxXySmoothingPercent,
    val spiralStartingFlowRatio: Float = quality.spiralStartingFlowRatio,
    val spiralFinishingFlowRatio: Float = quality.spiralFinishingFlowRatio,
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
    val extruderClearanceRadius: Float = printerProfile.extruderClearanceRadius,
    val extruderClearanceHeightToRod: Float = printerProfile.extruderClearanceHeightToRod,
    val extruderClearanceHeightToLid: Float = printerProfile.extruderClearanceHeightToLid,
) {
    val bedTemp: Int get() = buildPlate.temperature
    val firstLayerBedTemp: Int get() = buildPlate.firstLayerTemperature

    val travelSpeedZ: Float get() = quality.travelSpeedZ

    val printableOverhangs: PrintableOverhangSettings
        get() = precision.printableOverhangs

    val retraction: RetractionSettings get() = filamentProfile.resolveRetraction(printerProfile)
    val retractLength: Float get() = retraction.length
    val retractSpeed: Float get() = retraction.speed
    val deretractSpeed: Float get() = retraction.deretractSpeed
    val retractionMinimumTravel: Float get() = retraction.minimumTravel
    val retractWhenChangingLayer: Boolean get() = retraction.whenChangingLayer
    val wipeWhileRetracting: Boolean get() = retraction.wipe
    val wipeDistance: Float get() = retraction.wipeDistance
    val retractBeforeWipe: Float get() = retraction.beforeWipe
    val retractRestartExtra: Float get() = retraction.restartExtra
    val zHop: Float get() = retraction.zHop
    val zHopType: String get() = retraction.zHopType

    val defaultJerk: Float get() = jerk.defaultJerk
    val outerWallJerk: Float get() = jerk.outerWallJerk
    val innerWallJerk: Float get() = jerk.innerWallJerk
    val topSurfaceJerk: Float get() = jerk.topSurfaceJerk
    val infillJerk: Float get() = jerk.infillJerk
    val firstLayerJerk: Float get() = jerk.firstLayerJerk
    val travelJerk: Float get() = jerk.travelJerk

    fun selectPrinter(profile: PrinterProfile): SliceOptions {
        val nozzleMatches = abs(quality.nozzleDiameter - profile.nozzleDiameter) < 0.05f
        val retainedFilaments = resolvedFilamentSlots().take(profile.extruderCount.coerceAtLeast(1))
        val updated = copy(
            printerProfile = profile,
            filamentSlots = retainedFilaments,
            bedSizeX = profile.bedSizeX,
            bedSizeY = profile.bedSizeY,
            bedOriginX = profile.bedOriginX,
            bedOriginY = profile.bedOriginY,
            bedPolygon = profile.bedPolygon,
            bedExcludeArea = profile.bedExcludeArea,
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
            extruderClearanceRadius = profile.extruderClearanceRadius,
            extruderClearanceHeightToRod = profile.extruderClearanceHeightToRod,
            extruderClearanceHeightToLid = profile.extruderClearanceHeightToLid,
        ).boundedToFilamentSlots(retainedFilaments.size)
        return if (nozzleMatches) {
            updated
        } else {
            updated.selectQuality(QualityProfile.standardFor(profile.nozzleDiameter))
        }
    }

    fun selectFilament(profile: FilamentProfile) = copy(
        filamentProfile = profile,
        filamentSlots = resolvedFilamentSlots().toMutableList().apply {
            if (isEmpty()) {
                add(profile)
            } else {
                indices.forEach { index ->
                    if (index == 0 || !this[index].hasCompatibleDiameter(profile)) this[index] = profile
                }
            }
        }.take(printerProfile.extruderCount.coerceAtLeast(1)),
        nozzleTemp = profile.nozzleTemp,
        firstLayerNozzleTemp = profile.firstLayerNozzleTemp,
        buildPlate = buildPlate.withProfile(profile),
        flowRatio = profile.flowRatio,
        maxVolumetricSpeed = profile.maxVolumetricSpeed,
        filamentDiameter = profile.diameter,
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

    fun selectBuildPlate(type: BuildPlateType): SliceOptions = copy(
        buildPlate = buildPlate.withProfile(filamentProfile, type),
    )

    fun updatePrinterRetraction(profile: PrinterProfile): SliceOptions {
        return copy(printerProfile = profile).boundedToFilamentSlots(profile.extruderCount)
    }

    fun resolvedFilamentSlots(): List<FilamentProfile> = filamentSlots
        .take(printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS))
        .ifEmpty { listOf(filamentProfile) }

    fun updateFilamentSlot(index: Int, profile: FilamentProfile): SliceOptions {
        require(index in resolvedFilamentSlots().indices) { "Filament slot is unavailable" }
        if (index == 0) return selectFilament(profile)
        require(profile.hasCompatibleDiameter(filamentProfile)) {
            "Filament slots must use the same diameter"
        }
        val updated = resolvedFilamentSlots().toMutableList().apply { this[index] = profile }
        return copy(filamentSlots = updated)
    }

    fun assignFilament(profile: FilamentProfile): FilamentSlotAssignment {
        val current = resolvedFilamentSlots()
        val existing = current.indexOfFirst { it.id == profile.id }
        if (existing >= 0) return FilamentSlotAssignment(this, existing)
        require(current.size < printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS)) {
            "No filament slot is available"
        }
        require(profile.hasCompatibleDiameter(filamentProfile)) {
            "Filament slots must use the same diameter"
        }
        return FilamentSlotAssignment(
            options = copy(filamentSlots = current + profile)
                .boundedToFilamentSlots(current.size + 1),
            slot = current.size,
        )
    }

    fun addFilamentSlot(profile: FilamentProfile = filamentProfile): SliceOptions {
        val current = resolvedFilamentSlots()
        require(current.size < printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS)) {
            "No filament slot is available"
        }
        require(profile.hasCompatibleDiameter(filamentProfile)) {
            "Filament slots must use the same diameter"
        }
        return copy(filamentSlots = current + profile)
            .boundedToFilamentSlots(current.size + 1)
    }

    fun removeLastFilamentSlot(): SliceOptions {
        val current = resolvedFilamentSlots()
        require(current.size > 1) { "The primary filament slot cannot be removed" }
        return copy(filamentSlots = current.dropLast(1)).boundedToFilamentSlots(current.size - 1)
    }

    private fun boundedToFilamentSlots(slotCount: Int): SliceOptions {
        val maximum = slotCount.coerceIn(1, MAX_FILAMENT_SLOTS)
        return copy(
            supportFilament = supportFilament.coerceIn(0, maximum),
            supportInterfaceFilament = supportInterfaceFilament.coerceIn(0, maximum),
            featureFilaments = featureFilaments.boundedTo(maximum),
            multiMaterial = multiMaterial.resizedPurgeVolumes(maximum),
        )
    }

    fun selectQuality(profile: QualityProfile) = copy(
        quality = profile,
        layerHeight = profile.layerHeightMm,
        firstLayerHeight = profile.firstLayerHeightMm,
        perimeters = profile.perimeters,
        fillDensity = profile.fillDensity,
        printSpeed = profile.printSpeed,
        innerWallSpeed = profile.innerWallSpeed.takeIf { it > 0f } ?: profile.printSpeed * 1.5f,
        sparseInfillSpeed = profile.sparseInfillSpeed.takeIf { it > 0f } ?: profile.printSpeed * 1.35f,
        internalSolidInfillSpeed = profile.internalSolidInfillSpeed.takeIf { it > 0f } ?: profile.printSpeed * 1.25f,
        topSurfaceSpeed = profile.topSurfaceSpeed.takeIf { it > 0f } ?: profile.printSpeed,
        supportSpeed = profile.supportSpeed.takeIf { it > 0f } ?: 100f,
        bridgeSpeed = profile.bridgeSpeed.takeIf { it > 0f } ?: 50f,
        gapInfillSpeed = profile.gapInfillSpeed.takeIf { it > 0f } ?: profile.printSpeed * 1.25f,
        firstLayerInfillSpeed = profile.firstLayerInfillSpeed.takeIf { it > 0f }
            ?: profile.firstLayerSpeed * 2.1f,
        supportInterfaceSpeed = profile.supportInterfaceSpeed.takeIf { it > 0f }
            ?: (profile.supportSpeed.takeIf { it > 0f } ?: 100f) * 0.8f,
        internalBridgeSpeed = profile.internalBridgeSpeed,
        internalBridgeSpeedPercent = profile.internalBridgeSpeedPercent,
        overhangSpeedEnabled = profile.overhangSpeedEnabled,
        overhangSpeed1 = profile.overhangSpeed1,
        overhangSpeed1Percent = profile.overhangSpeed1Percent,
        overhangSpeed2 = profile.overhangSpeed2,
        overhangSpeed2Percent = profile.overhangSpeed2Percent,
        overhangSpeed3 = profile.overhangSpeed3,
        overhangSpeed3Percent = profile.overhangSpeed3Percent,
        overhangSpeed4 = profile.overhangSpeed4,
        overhangSpeed4Percent = profile.overhangSpeed4Percent,
        printFlowRatio = profile.printFlowRatio,
        bridgeFlowRatio = profile.bridgeFlowRatio,
        internalBridgeFlowRatio = profile.internalBridgeFlowRatio,
        topSurfaceFlowRatio = profile.topSurfaceFlowRatio,
        bottomSurfaceFlowRatio = profile.bottomSurfaceFlowRatio,
        bridgeDensity = profile.bridgeDensity,
        internalBridgeDensity = profile.internalBridgeDensity,
        bridgeAngle = profile.bridgeAngle,
        internalBridgeAngle = profile.internalBridgeAngle,
        bridgeNoSupport = profile.bridgeNoSupport,
        thickBridges = profile.thickBridges,
        thickInternalBridges = profile.thickInternalBridges,
        extraBridgeLayer = profile.extraBridgeLayer,
        internalBridgeFilter = profile.internalBridgeFilter,
        defaultAcceleration = profile.defaultAcceleration,
        outerWallAcceleration = profile.outerWallAcceleration,
        innerWallAcceleration = profile.innerWallAcceleration,
        topSurfaceAcceleration = profile.topSurfaceAcceleration,
        travelAcceleration = profile.travelAcceleration,
        firstLayerAcceleration = profile.firstLayerAcceleration,
        bridgeAcceleration = profile.bridgeAcceleration,
        bridgeAccelerationPercent = profile.bridgeAccelerationPercent,
        sparseInfillAcceleration = profile.sparseInfillAcceleration,
        sparseInfillAccelerationPercent = profile.sparseInfillAccelerationPercent,
        internalSolidInfillAcceleration = profile.internalSolidInfillAcceleration,
        internalSolidInfillAccelerationPercent = profile.internalSolidInfillAccelerationPercent,
        jerk = profile.jerkSettings(),
        fuzzySkin = profile.fuzzySkin,
        topSolidLayers = profile.topSolidLayers,
        bottomSolidLayers = profile.bottomSolidLayers,
        topShellThickness = profile.topShellThickness,
        bottomShellThickness = profile.bottomShellThickness,
        fillPattern = profile.fillPattern,
        topSurfacePattern = profile.topSurfacePattern,
        bottomSurfacePattern = profile.bottomSurfacePattern,
        internalSolidInfillPattern = profile.internalSolidInfillPattern,
        infillFirst = profile.infillFirst,
        infillWallOverlap = profile.infillWallOverlap,
        topBottomInfillWallOverlap = profile.topBottomInfillWallOverlap,
        infillCombination = profile.infillCombination,
        infillCombinationMaxLayerHeight = profile.infillCombinationMaxLayerHeight,
        infillCombinationMaxLayerHeightPercent = profile.infillCombinationMaxLayerHeightPercent,
        infillDirection = profile.infillDirection,
        solidInfillDirection = profile.solidInfillDirection,
        alignInfillDirectionToModel = profile.alignInfillDirectionToModel,
        minimumSparseInfillArea = profile.minimumSparseInfillArea,
        infillAnchor = profile.infillAnchor,
        infillAnchorPercent = profile.infillAnchorPercent,
        infillAnchorMax = profile.infillAnchorMax,
        infillAnchorMaxPercent = profile.infillAnchorMaxPercent,
        gapFillTarget = profile.gapFillTarget,
        filterOutGapFill = profile.filterOutGapFill,
        reduceCrossingWall = profile.reduceCrossingWall,
        maxTravelDetourDistance = profile.maxTravelDetourDistance,
        maxTravelDetourDistancePercent = profile.maxTravelDetourDistancePercent,
        reduceInfillRetraction = profile.reduceInfillRetraction,
        travelSpeed = profile.travelSpeed,
        firstLayerSpeed = profile.firstLayerSpeed,
        supportEnabled = profile.supportEnabled,
        supportType = normalizedSupportType(profile.supportType),
        supportAngle = profile.supportAngle,
        supportInterfaceTopLayers = profile.supportInterfaceTopLayers,
        supportInterfaceBottomLayers = profile.supportInterfaceBottomLayers,
        supportInterfaceSpacing = profile.supportInterfaceSpacing,
        supportBottomInterfaceSpacing = profile.supportBottomInterfaceSpacing,
        supportTopZDistance = profile.supportTopZDistance,
        supportBottomZDistance = profile.supportBottomZDistance,
        supportObjectXYDistance = profile.supportObjectXYDistance,
        supportBasePattern = profile.supportBasePattern,
        supportInterfacePattern = profile.supportInterfacePattern,
        supportStyle = profile.supportStyle,
        supportCoverage = profile.supportCoverage,
        supportAdvanced = profile.supportAdvanced,
        supportBasePatternSpacing = profile.supportBasePatternSpacing,
        supportExpansion = profile.supportExpansion,
        supportInterfaceLoopPattern = profile.supportInterfaceLoopPattern,
        independentSupportLayerHeight = profile.independentSupportLayerHeight,
        treeSupportBranchAngle = profile.treeSupportBranchAngle,
        treeSupportBranchDistance = profile.treeSupportBranchDistance,
        treeSupportBranchDiameter = profile.treeSupportBranchDiameter,
        treeSupportWallCount = profile.treeSupportWallCount,
        treeSupportTipDiameter = profile.treeSupportTipDiameter,
        treeSupportPreferredBranchAngle = profile.treeSupportPreferredBranchAngle,
        treeSupportBranchDensity = profile.treeSupportBranchDensity,
        treeSupportOrganicBranchAngle = profile.treeSupportOrganicBranchAngle,
        treeSupportOrganicBranchDistance = profile.treeSupportOrganicBranchDistance,
        treeSupportOrganicBranchDiameter = profile.treeSupportOrganicBranchDiameter,
        treeSupportBranchDiameterAngle = profile.treeSupportBranchDiameterAngle,
        treeSupportAdaptiveLayerHeight = profile.treeSupportAdaptiveLayerHeight,
        treeSupportAutoBrim = profile.treeSupportAutoBrim,
        treeSupportBrimWidth = profile.treeSupportBrimWidth,
        supportFilament = profile.supportFilament.coerceIn(0, resolvedFilamentSlots().size),
        supportInterfaceFilament = profile.supportInterfaceFilament.coerceIn(0, resolvedFilamentSlots().size),
        featureFilaments = profile.featureFilaments.boundedTo(resolvedFilamentSlots().size),
        wipeTowerEnabled = profile.wipeTowerEnabled,
        wipeTowerWidth = profile.wipeTowerWidth,
        multiMaterial = profile.multiMaterial,
        gcodeSettings = profile.gcodeSettings,
        skirtLoops = profile.skirtLoops,
        skirtDistance = profile.skirtDistance,
        skirtHeight = profile.skirtHeight,
        skirtSpeed = profile.skirtSpeed,
        minimumSkirtLength = profile.minimumSkirtLength,
        draftShield = profile.draftShield,
        brimType = profile.brimType,
        brimWidth = profile.brimWidth,
        brimObjectGap = profile.brimObjectGap,
        raftLayers = profile.raftLayers,
        raftContactDistance = profile.raftContactDistance,
        raftExpansion = profile.raftExpansion,
        raftFirstLayerDensity = profile.raftFirstLayerDensity,
        raftFirstLayerExpansion = profile.raftFirstLayerExpansion,
        outerWallLineWidth = profile.outerWallLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.05f,
        innerWallLineWidth = profile.innerWallLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.125f,
        topSurfaceLineWidth = profile.topSurfaceLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.05f,
        sparseInfillLineWidth = profile.sparseInfillLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.125f,
        internalSolidInfillLineWidth = profile.internalSolidInfillLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.125f,
        supportLineWidth = profile.supportLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.05f,
        initialLayerLineWidth = profile.initialLayerLineWidth.takeIf { it > 0f }
            ?: nozzleDiameter * 1.25f,
        smallPerimeterSpeed = profile.smallPerimeterSpeed,
        smallPerimeterSpeedPercent = profile.smallPerimeterSpeedPercent,
        smallPerimeterThreshold = profile.smallPerimeterThreshold,
        slowdownForCurledPerimeters = profile.slowdownForCurledPerimeters,
        resolution = profile.resolution,
        precision = profile.precision,
        seamPosition = profile.seamPosition,
        staggeredInnerSeams = profile.staggeredInnerSeams,
        seamGap = profile.seamGap,
        seamGapPercent = profile.seamGapPercent,
        wipeBeforeExternalLoop = profile.wipeBeforeExternalLoop,
        wipeOnLoops = profile.wipeOnLoops,
        roleBasedWipeSpeed = profile.roleBasedWipeSpeed,
        wipeSpeed = profile.wipeSpeed,
        wipeSpeedPercent = profile.wipeSpeedPercent,
        ironing = profile.ironing,
        wallGenerator = profile.wallGenerator,
        wallTransitionLength = profile.wallTransitionLength,
        wallTransitionFilterDeviation = profile.wallTransitionFilterDeviation,
        wallTransitionAngle = profile.wallTransitionAngle,
        wallDistributionCount = profile.wallDistributionCount,
        minimumFeatureSize = profile.minimumFeatureSize,
        minimumWallLengthFactor = profile.minimumWallLengthFactor,
        wallSequence = profile.wallSequence,
        wallDirection = profile.wallDirection,
        detectThinWalls = profile.detectThinWalls,
        detectOverhangWalls = profile.detectOverhangWalls,
        onlyOneWallOnTop = profile.onlyOneWallOnTop,
        minWidthTopSurface = profile.minWidthTopSurface,
        minWidthTopSurfacePercent = profile.minWidthTopSurfacePercent,
        onlyOneWallFirstLayer = profile.onlyOneWallFirstLayer,
        extraPerimetersOnOverhangs = profile.extraPerimetersOnOverhangs,
        overhangReverse = profile.overhangReverse,
        overhangReverseInternalOnly = profile.overhangReverseInternalOnly,
        overhangReverseThreshold = profile.overhangReverseThreshold,
        overhangReverseThresholdPercent = profile.overhangReverseThresholdPercent,
        counterboreHoleBridging = profile.counterboreHoleBridging,
        alternateExtraWall = profile.alternateExtraWall,
        ensureVerticalShellThickness = profile.ensureVerticalShellThickness,
        detectNarrowInternalSolidInfill = profile.detectNarrowInternalSolidInfill,
        xyHoleCompensation = profile.xyHoleCompensation,
        xyContourCompensation = profile.xyContourCompensation,
        elephantFootCompensation = profile.elephantFootCompensation,
        elephantFootCompensationLayers = profile.elephantFootCompensationLayers,
        maxBridgeLength = profile.maxBridgeLength,
        preciseOuterWalls = profile.preciseOuterWalls,
        printSequence = profile.printSequence,
        printOrder = profile.printOrder,
        spiralMode = profile.spiralMode,
        spiralModeSmooth = profile.spiralModeSmooth,
        spiralModeMaxXySmoothing = profile.spiralModeMaxXySmoothing,
        spiralModeMaxXySmoothingPercent = profile.spiralModeMaxXySmoothingPercent,
        spiralStartingFlowRatio = profile.spiralStartingFlowRatio,
        spiralFinishingFlowRatio = profile.spiralFinishingFlowRatio,
    )

    fun toNativeConfig(): SliceConfig {
        val nativeFilaments = resolvedFilamentSlots().mapIndexed { index, profile ->
            if (index != 0) {
                profile
            } else {
                profile.withBedTemperature(
                    buildPlate.type,
                    bedTemp,
                    firstLayerBedTemp,
                ).copy(
                    nozzleTemp = nozzleTemp,
                    firstLayerNozzleTemp = firstLayerNozzleTemp,
                    flowRatio = flowRatio,
                    maxVolumetricSpeed = maxVolumetricSpeed,
                    diameter = filamentDiameter,
                    retractLength = retractLength,
                    retractSpeed = retractSpeed,
                    deretractSpeed = deretractSpeed,
                    retractionMinimumTravel = retractionMinimumTravel,
                    retractWhenChangingLayer = retractWhenChangingLayer,
                    wipeWhileRetracting = wipeWhileRetracting,
                    wipeDistance = wipeDistance,
                    retractBeforeWipe = retractBeforeWipe,
                    retractRestartExtra = retractRestartExtra,
                    zHop = zHop,
                    zHopType = zHopType,
                    fanMinSpeed = fanMinSpeed,
                    fanMaxSpeed = fanMaxSpeed,
                    overhangFanSpeed = overhangFanSpeed,
                    slowDownLayerTime = slowDownLayerTime,
                    slowDownMinSpeed = slowDownMinSpeed,
                    closeFanFirstLayers = closeFanFirstLayers,
                    fullFanSpeedLayer = fullFanSpeedLayer,
                    pressureAdvanceEnabled = pressureAdvanceEnabled,
                    pressureAdvance = pressureAdvance,
                )
            }
        }
        require(nativeFilaments.all { it.hasCompatibleDiameter(nativeFilaments.first()) }) {
            "Filament slots must use the same diameter"
        }
        val nativeRetractions = nativeFilaments.map { it.resolveRetraction(printerProfile) }
        return SliceConfig(
            layerHeight = layerHeight,
            firstLayerHeight = firstLayerHeight,
            perimeters = perimeters,
            topSolidLayers = topSolidLayers,
            bottomSolidLayers = bottomSolidLayers,
            fillDensity = fillDensity,
            fillPattern = fillPattern,
            topSurfacePattern = topSurfacePattern,
            bottomSurfacePattern = bottomSurfacePattern,
            internalSolidInfillPattern = internalSolidInfillPattern,
            infillFirst = infillFirst,
            infillWallOverlap = infillWallOverlap,
            topBottomInfillWallOverlap = topBottomInfillWallOverlap,
            infillCombination = infillCombination,
            infillCombinationMaxLayerHeight = infillCombinationMaxLayerHeight,
            infillCombinationMaxLayerHeightPercent = infillCombinationMaxLayerHeightPercent,
            infillDirection = infillDirection,
            solidInfillDirection = solidInfillDirection,
            alignInfillDirectionToModel = alignInfillDirectionToModel,
            minimumSparseInfillArea = minimumSparseInfillArea,
            infillAnchor = infillAnchor,
            infillAnchorPercent = infillAnchorPercent,
            infillAnchorMax = infillAnchorMax,
            infillAnchorMaxPercent = infillAnchorMaxPercent,
            gapFillTarget = gapFillTarget,
            filterOutGapFill = filterOutGapFill,
            reduceCrossingWall = reduceCrossingWall,
            maxTravelDetourDistance = maxTravelDetourDistance,
            maxTravelDetourDistancePercent = maxTravelDetourDistancePercent,
            reduceInfillRetraction = reduceInfillRetraction,
            printSpeed = printSpeed,
            innerWallSpeed = innerWallSpeed,
            sparseInfillSpeed = sparseInfillSpeed,
            internalSolidInfillSpeed = internalSolidInfillSpeed,
            topSurfaceSpeed = topSurfaceSpeed,
            supportSpeed = supportSpeed,
            bridgeSpeed = bridgeSpeed,
            gapInfillSpeed = gapInfillSpeed,
            firstLayerInfillSpeed = firstLayerInfillSpeed,
            supportInterfaceSpeed = supportInterfaceSpeed,
            internalBridgeSpeed = internalBridgeSpeed,
            internalBridgeSpeedPercent = internalBridgeSpeedPercent,
            overhangSpeedEnabled = overhangSpeedEnabled,
            overhangSpeed1 = overhangSpeed1,
            overhangSpeed1Percent = overhangSpeed1Percent,
            overhangSpeed2 = overhangSpeed2,
            overhangSpeed2Percent = overhangSpeed2Percent,
            overhangSpeed3 = overhangSpeed3,
            overhangSpeed3Percent = overhangSpeed3Percent,
            overhangSpeed4 = overhangSpeed4,
            overhangSpeed4Percent = overhangSpeed4Percent,
            printFlowRatio = printFlowRatio,
            bridgeFlowRatio = bridgeFlowRatio,
            internalBridgeFlowRatio = internalBridgeFlowRatio,
            topSurfaceFlowRatio = topSurfaceFlowRatio,
            bottomSurfaceFlowRatio = bottomSurfaceFlowRatio,
            bridgeDensity = bridgeDensity,
            internalBridgeDensity = internalBridgeDensity,
            bridgeAngle = bridgeAngle,
            internalBridgeAngle = internalBridgeAngle,
            bridgeNoSupport = bridgeNoSupport,
            thickBridges = thickBridges,
            thickInternalBridges = thickInternalBridges,
            extraBridgeLayer = extraBridgeLayer,
            internalBridgeFilter = internalBridgeFilter,
            defaultAcceleration = defaultAcceleration,
            outerWallAcceleration = outerWallAcceleration,
            innerWallAcceleration = innerWallAcceleration,
            topSurfaceAcceleration = topSurfaceAcceleration,
            travelAcceleration = travelAcceleration,
            firstLayerAcceleration = firstLayerAcceleration,
            bridgeAcceleration = bridgeAcceleration,
            bridgeAccelerationPercent = bridgeAccelerationPercent,
            sparseInfillAcceleration = sparseInfillAcceleration,
            sparseInfillAccelerationPercent = sparseInfillAccelerationPercent,
            internalSolidInfillAcceleration = internalSolidInfillAcceleration,
            internalSolidInfillAccelerationPercent = internalSolidInfillAccelerationPercent,
            travelSpeed = travelSpeed,
            travelSpeedZ = travelSpeedZ,
            firstLayerSpeed = firstLayerSpeed,
            nozzleTemp = nozzleTemp,
            bedTemp = bedTemp,
            retractLength = retractLength,
            retractSpeed = retractSpeed,
            supportEnabled = supportEnabled,
            supportType = normalizedSupportType(supportType),
            supportAngle = supportAngle,
            supportInterfaceTopLayers = supportInterfaceTopLayers,
            supportInterfaceBottomLayers = supportInterfaceBottomLayers,
            supportInterfaceSpacing = supportInterfaceSpacing,
            supportBottomInterfaceSpacing = supportBottomInterfaceSpacing,
            supportTopZDistance = supportTopZDistance,
            supportBottomZDistance = supportBottomZDistance,
            supportObjectXYDistance = supportObjectXYDistance,
            supportBasePattern = supportBasePattern,
            supportInterfacePattern = supportInterfacePattern,
            supportStyle = normalizedSupportStyle(supportType, supportStyle),
            supportBasePatternSpacing = supportBasePatternSpacing,
            supportExpansion = supportExpansion,
            supportInterfaceLoopPattern = supportInterfaceLoopPattern,
            independentSupportLayerHeight = independentSupportLayerHeight,
            treeSupportBranchAngle = treeSupportBranchAngle,
            treeSupportBranchDistance = treeSupportBranchDistance,
            treeSupportBranchDiameter = treeSupportBranchDiameter,
            treeSupportWallCount = treeSupportWallCount,
            treeSupportTipDiameter = treeSupportTipDiameter,
            treeSupportPreferredBranchAngle = treeSupportPreferredBranchAngle,
            treeSupportBranchDensity = treeSupportBranchDensity,
            treeSupportAdaptiveLayerHeight = treeSupportAdaptiveLayerHeight,
            treeSupportAutoBrim = treeSupportAutoBrim,
            treeSupportBrimWidth = treeSupportBrimWidth,
            supportFilament = supportFilament.coerceIn(0, nativeFilaments.size),
            supportInterfaceFilament = supportInterfaceFilament.coerceIn(0, nativeFilaments.size),
            skirtLoops = skirtLoops,
            skirtDistance = skirtDistance,
            skirtHeight = skirtHeight,
            skirtSpeed = skirtSpeed,
            minimumSkirtLength = minimumSkirtLength,
            draftShield = draftShield,
            brimType = brimType,
            brimWidth = brimWidth,
            brimObjectGap = brimObjectGap,
            raftLayers = raftLayers,
            raftContactDistance = raftContactDistance,
            raftExpansion = raftExpansion,
            raftFirstLayerDensity = raftFirstLayerDensity,
            raftFirstLayerExpansion = raftFirstLayerExpansion,
            outerWallLineWidth = outerWallLineWidth,
            innerWallLineWidth = innerWallLineWidth,
            topSurfaceLineWidth = topSurfaceLineWidth,
            sparseInfillLineWidth = sparseInfillLineWidth,
            internalSolidInfillLineWidth = internalSolidInfillLineWidth,
            supportLineWidth = supportLineWidth,
            initialLayerLineWidth = initialLayerLineWidth,
            smallPerimeterSpeed = smallPerimeterSpeed,
            smallPerimeterSpeedPercent = smallPerimeterSpeedPercent,
            smallPerimeterThreshold = smallPerimeterThreshold,
            slowdownForCurledPerimeters = slowdownForCurledPerimeters,
            resolution = resolution,
            seamPosition = seamPosition,
            staggeredInnerSeams = staggeredInnerSeams,
            seamGap = seamGap,
            seamGapPercent = seamGapPercent,
            wipeBeforeExternalLoop = wipeBeforeExternalLoop,
            wipeOnLoops = wipeOnLoops,
            roleBasedWipeSpeed = roleBasedWipeSpeed,
            wipeSpeed = wipeSpeed,
            wipeSpeedPercent = wipeSpeedPercent,
            topShellThickness = topShellThickness,
            bottomShellThickness = bottomShellThickness,
            wallGenerator = wallGenerator,
            wallTransitionLength = wallTransitionLength,
            wallTransitionFilterDeviation = wallTransitionFilterDeviation,
            wallTransitionAngle = wallTransitionAngle,
            wallDistributionCount = wallDistributionCount,
            minimumFeatureSize = minimumFeatureSize,
            minimumWallLengthFactor = minimumWallLengthFactor,
            wallSequence = wallSequence,
            wallDirection = wallDirection,
            detectThinWalls = detectThinWalls,
            detectOverhangWalls = detectOverhangWalls,
            onlyOneWallOnTop = onlyOneWallOnTop,
            minWidthTopSurface = minWidthTopSurface,
            minWidthTopSurfacePercent = minWidthTopSurfacePercent,
            onlyOneWallFirstLayer = onlyOneWallFirstLayer,
            extraPerimetersOnOverhangs = extraPerimetersOnOverhangs,
            overhangReverse = overhangReverse,
            overhangReverseInternalOnly = overhangReverseInternalOnly,
            overhangReverseThreshold = overhangReverseThreshold,
            overhangReverseThresholdPercent = overhangReverseThresholdPercent,
            counterboreHoleBridging = counterboreHoleBridging,
            alternateExtraWall = alternateExtraWall,
            ensureVerticalShellThickness = ensureVerticalShellThickness,
            detectNarrowInternalSolidInfill = detectNarrowInternalSolidInfill,
            xyHoleCompensation = xyHoleCompensation,
            xyContourCompensation = xyContourCompensation,
            elephantFootCompensation = elephantFootCompensation,
            elephantFootCompensationLayers = elephantFootCompensationLayers,
            maxBridgeLength = maxBridgeLength,
            preciseOuterWalls = preciseOuterWalls,
            spiralMode = spiralMode,
            spiralModeSmooth = spiralModeSmooth,
            spiralModeMaxXySmoothing = spiralModeMaxXySmoothing,
            spiralModeMaxXySmoothingPercent = spiralModeMaxXySmoothingPercent,
            spiralStartingFlowRatio = spiralStartingFlowRatio,
            spiralFinishingFlowRatio = spiralFinishingFlowRatio,
            bedSizeX = bedSizeX,
            bedSizeY = bedSizeY,
            bedOriginX = bedOriginX,
            bedOriginY = bedOriginY,
            bedPolygon = bedPolygon.toFloatArray(),
            maxPrintHeight = maxPrintHeight,
            nozzleDiameter = nozzleDiameter,
            filamentDiameter = filamentDiameter,
            filamentType = nativeFilaments.first().nativeName,
            filamentTypes = nativeFilaments.map(FilamentProfile::nativeName).toTypedArray(),
            extruderCount = nativeFilaments.size,
            extruderTemps = nativeFilaments.map(FilamentProfile::nozzleTemp).toIntArray(),
            extruderRetractLength = nativeRetractions.map(RetractionSettings::length).toFloatArray(),
            extruderRetractSpeed = nativeRetractions.map(RetractionSettings::speed).toFloatArray(),
            wipeTowerEnabled = wipeTowerEnabled && nativeFilaments.size > 1,
            wipeTowerWidth = wipeTowerWidth,
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
            filamentFlowRatios = nativeFilaments.map(FilamentProfile::flowRatio).toFloatArray(),
            filamentStartGcodes = nativeFilaments.map(FilamentProfile::filamentStartGcode).toTypedArray(),
            filamentEndGcodes = nativeFilaments.map(FilamentProfile::filamentEndGcode).toTypedArray(),
            filamentMaxVolumetricSpeeds = nativeFilaments.map(FilamentProfile::maxVolumetricSpeed).toFloatArray(),
            filamentFanMinSpeeds = nativeFilaments.map(FilamentProfile::fanMinSpeed).toIntArray(),
            filamentFanMaxSpeeds = nativeFilaments.map(FilamentProfile::fanMaxSpeed).toIntArray(),
            filamentOverhangFanSpeeds = nativeFilaments.map(FilamentProfile::overhangFanSpeed).toIntArray(),
            filamentSlowDownLayerTimes = nativeFilaments.map(FilamentProfile::slowDownLayerTime).toFloatArray(),
            filamentSlowDownMinSpeeds = nativeFilaments.map(FilamentProfile::slowDownMinSpeed).toFloatArray(),
            filamentCloseFanFirstLayers = nativeFilaments.map(FilamentProfile::closeFanFirstLayers).toIntArray(),
            filamentFullFanSpeedLayers = nativeFilaments.map(FilamentProfile::fullFanSpeedLayer).toIntArray(),
            filamentEnablePressureAdvance = nativeFilaments.map {
                if (it.pressureAdvanceEnabled) 1 else 0
            }.toIntArray(),
            filamentPressureAdvances = nativeFilaments.map(FilamentProfile::pressureAdvance).toFloatArray(),
            filamentNozzleTempInitialLayers = nativeFilaments.map(FilamentProfile::firstLayerNozzleTemp).toIntArray(),
            filamentBedTempInitialLayers = nativeFilaments
                .map { it.firstLayerBedTemperature(buildPlate.type) }
                .toIntArray(),
            filamentDensities = nativeFilaments.map(FilamentProfile::density).toFloatArray(),
            filamentCosts = nativeFilaments.map(FilamentProfile::costPerKilogram).toFloatArray(),
        ).also { native ->
            native.beforeLayerChangeGcode = printerProfile.beforeLayerChangeGcode
            native.layerChangeGcode = printerProfile.layerChangeGcode
            native.changeFilamentGcode = printerProfile.changeFilamentGcode
            native.printingByObjectGcode = printerProfile.printingByObjectGcode
            native.useRelativeEDistances = printerProfile.useRelativeEDistances
            native.emitMachineLimitsToGcode = printerProfile.emitMachineLimitsToGcode
            native.manualFilamentChange = printerProfile.manualFilamentChange
            native.disableM73 = printerProfile.disableM73
            native.filamentIdleTemperatures = nativeFilaments
                .map(FilamentProfile::idleTemperature).toIntArray()
            native.bedExcludeArea = bedExcludeArea.toFloatArray()
            native.machineLoadFilamentTime = printerProfile.machineLoadFilamentTime
            native.machineUnloadFilamentTime = printerProfile.machineUnloadFilamentTime
            native.machineToolChangeTime = printerProfile.machineToolChangeTime
            native.toolChangeTemperatureWait = printerProfile.toolChangeTemperatureWait
            native.minimumLayerHeights = FloatArray(nativeFilaments.size) {
                printerProfile.minLayerHeight
            }
            native.maximumLayerHeights = FloatArray(nativeFilaments.size) {
                printerProfile.maxLayerHeight
            }
            native.extruderOffsetsX = printerProfile
                .resolvedExtruderOffsetsX(nativeFilaments.size)
                .toFloatArray()
            native.extruderOffsetsY = printerProfile
                .resolvedExtruderOffsetsY(nativeFilaments.size)
                .toFloatArray()
            native.toolChangeRetractLengths = printerProfile
                .resolvedToolChangeRetractLengths(nativeFilaments.size)
                .toFloatArray()
            native.toolChangeRetractRestartExtras = printerProfile
                .resolvedToolChangeRetractRestartExtras(nativeFilaments.size)
                .toFloatArray()
            native.filamentShrinkages = nativeFilaments
                .map(FilamentProfile::shrinkageXyPercent)
                .toFloatArray()
            native.filamentShrinkageCompensationZ = nativeFilaments
                .map(FilamentProfile::shrinkageZPercent)
                .toFloatArray()
            native.filamentSoluble = nativeFilaments.map { if (it.soluble) 1 else 0 }.toIntArray()
            native.filamentIsSupport = nativeFilaments.map { if (it.supportMaterial) 1 else 0 }.toIntArray()
            native.filamentMinimalPurgeOnWipeTower = nativeFilaments
                .map(FilamentProfile::minimalPurgeOnWipeTower)
                .toFloatArray()
            native.filamentAdditionalCoolingFanSpeeds = nativeFilaments
                .map(FilamentProfile::additionalCoolingFanSpeed)
                .toIntArray()
            native.bedType = buildPlate.type.nativeValue
            native.filamentBedTemps = nativeFilaments
                .map { it.bedTemperature(buildPlate.type) }
                .toIntArray()
            native.filamentFanCoolingLayerTimes = nativeFilaments
                .map(FilamentProfile::fanCoolingLayerTime)
                .toFloatArray()
            native.filamentSlowDownForLayerCooling = nativeFilaments
                .map { if (it.slowDownForLayerCooling) 1 else 0 }
                .toIntArray()
            native.filamentKeepFanAlwaysOn = nativeFilaments
                .map { if (it.keepFanAlwaysOn) 1 else 0 }
                .toIntArray()
            native.filamentDontSlowDownOuterWall = nativeFilaments
                .map { if (it.dontSlowDownOuterWall) 1 else 0 }
                .toIntArray()
            native.filamentEnableOverhangBridgeFan = nativeFilaments
                .map { if (it.enableOverhangBridgeFan) 1 else 0 }
                .toIntArray()
            native.filamentOverhangFanThresholds = nativeFilaments
                .map {
                    OVERHANG_FAN_THRESHOLDS.indexOf(it.overhangFanThreshold)
                        .takeIf { index -> index >= 0 }
                        ?: OVERHANG_FAN_THRESHOLDS.lastIndex
                }
                .toIntArray()
            native.filamentInternalBridgeFanSpeeds = nativeFilaments
                .map(FilamentProfile::internalBridgeFanSpeed)
                .toIntArray()
            native.filamentSupportInterfaceFanSpeeds = nativeFilaments
                .map(FilamentProfile::supportInterfaceFanSpeed)
                .toIntArray()
            native.filamentLoadingSpeeds = nativeFilaments.map(FilamentProfile::loadingSpeed).toFloatArray()
            native.filamentLoadingSpeedStarts = nativeFilaments
                .map(FilamentProfile::loadingSpeedStart).toFloatArray()
            native.filamentUnloadingSpeeds = nativeFilaments
                .map(FilamentProfile::unloadingSpeed).toFloatArray()
            native.filamentUnloadingSpeedStarts = nativeFilaments
                .map(FilamentProfile::unloadingSpeedStart).toFloatArray()
            native.filamentToolchangeDelays = nativeFilaments
                .map(FilamentProfile::toolchangeDelay).toFloatArray()
            native.filamentCoolingMoves = nativeFilaments.map(FilamentProfile::coolingMoves).toIntArray()
            native.filamentStampingLoadingSpeeds = nativeFilaments
                .map(FilamentProfile::stampingLoadingSpeed).toFloatArray()
            native.filamentStampingDistances = nativeFilaments
                .map(FilamentProfile::stampingDistance).toFloatArray()
            native.filamentCoolingInitialSpeeds = nativeFilaments
                .map(FilamentProfile::coolingInitialSpeed).toFloatArray()
            native.filamentCoolingFinalSpeeds = nativeFilaments
                .map(FilamentProfile::coolingFinalSpeed).toFloatArray()
            native.filamentRammingParameters = nativeFilaments
                .map(FilamentProfile::rammingParameters).toTypedArray()
            native.filamentMultitoolRamming = nativeFilaments
                .map { if (it.multitoolRamming) 1 else 0 }.toIntArray()
            native.filamentMultitoolRammingVolumes = nativeFilaments
                .map(FilamentProfile::multitoolRammingVolume).toFloatArray()
            native.filamentMultitoolRammingFlows = nativeFilaments
                .map(FilamentProfile::multitoolRammingFlow).toFloatArray()
            native.auxiliaryFan = printerProfile.auxiliaryFan
            native.supportsChamberTemperatureControl = printerProfile.supportsChamberTemperatureControl
            native.supportsAirFiltration = printerProfile.supportsAirFiltration
            native.filamentSofteningTemperatures = nativeFilaments
                .map(FilamentProfile::softeningTemperature).toIntArray()
            native.filamentNozzleTemperatureRangeLows = nativeFilaments
                .map(FilamentProfile::nozzleTemperatureRangeLow).toIntArray()
            native.filamentNozzleTemperatureRangeHighs = nativeFilaments
                .map(FilamentProfile::nozzleTemperatureRangeHigh).toIntArray()
            native.filamentChamberTemperatureControl = nativeFilaments
                .map { if (it.chamberTemperatureControl) 1 else 0 }.toIntArray()
            native.filamentChamberTemperatures = nativeFilaments
                .map(FilamentProfile::chamberTemperature).toIntArray()
            native.filamentAirFiltration = nativeFilaments
                .map { if (it.airFiltration) 1 else 0 }.toIntArray()
            native.filamentDuringPrintExhaustFanSpeeds = nativeFilaments
                .map(FilamentProfile::duringPrintExhaustFanSpeed).toIntArray()
            native.filamentCompletePrintExhaustFanSpeeds = nativeFilaments
                .map(FilamentProfile::completePrintExhaustFanSpeed).toIntArray()
            native.topSurfaceDensity = quality.surfaceDensity.topPercent
            native.bottomSurfaceDensity = quality.surfaceDensity.bottomPercent
            native.skeletonInfillDensity = quality.skeletonInfillDensity
            native.skinInfillDensity = quality.skinInfillDensity
            native.skinInfillDepth = quality.skinInfillDepth
            native.infillLockDepth = quality.infillLockDepth
            native.infillShiftStep = quality.infillShiftStep
            native.symmetricInfillYAxis = quality.symmetricInfillYAxis
            native.sparseInfillRotationTemplate = quality.sparseInfillRotationTemplate
            native.solidInfillRotationTemplate = quality.solidInfillRotationTemplate
            native.skinInfillLineWidth = quality.skinInfillLineWidth
            native.skinInfillLineWidthPercent = quality.skinInfillLineWidthPercent
            native.skeletonInfillLineWidth = quality.skeletonInfillLineWidth
            native.skeletonInfillLineWidthPercent = quality.skeletonInfillLineWidthPercent
            native.skirtStartAngle = quality.skirtStartAngle
            native.fuzzySkinType = fuzzySkin.type
            native.fuzzySkinFirstLayer = fuzzySkin.firstLayer
            native.fuzzySkinPointDistance = fuzzySkin.pointDistance
            native.fuzzySkinThickness = fuzzySkin.thickness
            native.fuzzySkinMode = fuzzySkin.mode.takeIf { wallGenerator == "arachne" } ?: "displacement"
            native.fuzzySkinNoiseType = fuzzySkin.noiseType
            native.fuzzySkinScale = fuzzySkin.scale
            native.fuzzySkinOctaves = fuzzySkin.octaves
            native.fuzzySkinPersistence = fuzzySkin.persistence
            native.infillFilamentOverrideEnabled = featureFilaments.infillOverrideEnabled
            native.infillFilamentBaseFirstLayers = featureFilaments.baseFirstLayers.coerceIn(0, 1_000)
            native.infillFilamentBaseLastLayers = featureFilaments.baseLastLayers.coerceIn(0, 1_000)
            native.sparseInfillFilament = featureFilaments.sparseInfillFilament.coerceIn(1, nativeFilaments.size)
            native.wallFilament = featureFilaments.wallFilament.coerceIn(1, nativeFilaments.size)
            native.solidInfillFilament = featureFilaments.solidInfillFilament.coerceIn(1, nativeFilaments.size)
            native.wipeTowerFilament = featureFilaments.wipeTowerFilament.coerceIn(0, nativeFilaments.size)
            native.printSequence = printSequence
            native.printOrder = printOrder
            native.extruderClearanceRadius = extruderClearanceRadius
            native.extruderClearanceHeightToRod = extruderClearanceHeightToRod
            native.extruderClearanceHeightToLid = extruderClearanceHeightToLid
            native.treeSupportOrganicBranchAngle = treeSupportOrganicBranchAngle
            native.treeSupportOrganicBranchDistance = treeSupportOrganicBranchDistance
            native.treeSupportOrganicBranchDiameter = treeSupportOrganicBranchDiameter
            native.treeSupportBranchDiameterAngle = treeSupportBranchDiameterAngle
            native.defaultJerk = defaultJerk
            native.outerWallJerk = outerWallJerk
            native.innerWallJerk = innerWallJerk
            native.topSurfaceJerk = topSurfaceJerk
            native.infillJerk = infillJerk
            native.firstLayerJerk = firstLayerJerk
            native.travelJerk = travelJerk
            native.extruderDeretractSpeed = nativeRetractions
                .map(RetractionSettings::deretractSpeed).toFloatArray()
            native.extruderRetractionMinimumTravel = nativeRetractions
                .map(RetractionSettings::minimumTravel).toFloatArray()
            native.extruderRetractWhenChangingLayer = nativeRetractions
                .map { if (it.whenChangingLayer) 1 else 0 }.toIntArray()
            native.extruderWipeWhileRetracting = nativeRetractions
                .map { if (it.wipe && !printerProfile.useFirmwareRetraction) 1 else 0 }.toIntArray()
            native.extruderWipeDistance = nativeRetractions
                .map(RetractionSettings::wipeDistance).toFloatArray()
            native.extruderRetractBeforeWipe = nativeRetractions
                .map(RetractionSettings::beforeWipe).toFloatArray()
            native.extruderRetractRestartExtra = nativeRetractions
                .map(RetractionSettings::restartExtra).toFloatArray()
            native.extruderZHop = nativeRetractions.map(RetractionSettings::zHop).toFloatArray()
            native.extruderZHopType = nativeRetractions.map(RetractionSettings::zHopType).toTypedArray()
            native.extruderRetractLiftAbove = nativeRetractions
                .map(RetractionSettings::liftAbove).toFloatArray()
            native.extruderRetractLiftBelow = nativeRetractions
                .map(RetractionSettings::liftBelow).toFloatArray()
            native.extruderRetractLiftEnforce = nativeRetractions
                .map(RetractionSettings::liftEnforce).toTypedArray()
            native.extruderTravelSlope = FloatArray(nativeRetractions.size) {
                printerProfile.travelSlope
            }
            native.extruderZHopWhenPrime = IntArray(nativeRetractions.size) {
                if (printerProfile.zHopWhenPrime) 1 else 0
            }
            native.useFirmwareRetraction = printerProfile.useFirmwareRetraction
            native.longRetractionWhenCutLevel = printerProfile.longRetractionWhenCutLevel
            native.extruderLongRetractionWhenCut = nativeRetractions
                .map { if (it.longRetractionWhenCut) 1 else 0 }.toIntArray()
            native.extruderRetractionDistanceWhenCut = nativeRetractions
                .map(RetractionSettings::retractionDistanceWhenCut).toFloatArray()
            native.primeVolume = multiMaterial.primeVolume
            native.purgeVolumes = multiMaterial.resolvedPurgeVolumes(nativeFilaments.size).toFloatArray()
            native.singleExtruderMultiMaterial = printerProfile.singleExtruderMultiMaterial
            native.coolingTubeRetraction = printerProfile.coolingTubeRetraction
            native.coolingTubeLength = printerProfile.coolingTubeLength
            native.parkingPosRetraction = printerProfile.parkingPosRetraction
            native.extraLoadingMove = printerProfile.extraLoadingMove
            native.enableFilamentRamming = printerProfile.enableFilamentRamming
            native.highCurrentOnFilamentSwap = printerProfile.highCurrentOnFilamentSwap
            native.purgeInPrimeTower = printerProfile.singleExtruderMultiMaterial &&
                printerProfile.purgeInPrimeTower &&
                multiMaterial.purgeVolumes.isNotEmpty()
            native.primeTowerBrimWidth = multiMaterial.primeTowerBrimWidth
            native.wipeTowerNoSparseLayers = multiMaterial.wipeTowerNoSparseLayers
            native.wipeTowerRotationAngle = multiMaterial.wipeTowerRotationAngle
            native.wipeTowerBridging = multiMaterial.wipeTowerBridging
            native.wipeTowerExtraSpacing = multiMaterial.wipeTowerExtraSpacing
            native.wipeTowerExtraFlow = multiMaterial.wipeTowerExtraFlow
            native.wipeTowerMaxPurgeSpeed = multiMaterial.wipeTowerMaxPurgeSpeed
            native.wipeTowerWallType = multiMaterial.wipeTowerWallType
            native.wipeTowerConeAngle = multiMaterial.wipeTowerConeAngle
            native.wipeTowerExtraRibLength = multiMaterial.wipeTowerExtraRibLength
            native.wipeTowerRibWidth = multiMaterial.wipeTowerRibWidth
            native.wipeTowerFilletWall = multiMaterial.wipeTowerFilletWall
            native.singleExtruderMultiMaterialPriming = multiMaterial.singleExtruderMultiMaterialPriming
            native.flushIntoInfill = multiMaterial.flushIntoInfill
            native.flushIntoSupport = multiMaterial.flushIntoSupport
            native.flushIntoObjects = multiMaterial.flushIntoObjects
            native.oozePrevention = multiMaterial.oozePrevention
            native.standbyTemperatureDelta = multiMaterial.standbyTemperatureDelta
            native.preheatTime = multiMaterial.preheatTime
            native.preheatDeltaTemperature = multiMaterial.preheatDeltaTemperature
            native.preheatSteps = multiMaterial.preheatSteps
            native.interfaceShells = multiMaterial.interfaceShells
            native.segmentedRegionMaxWidth = multiMaterial.segmentedRegionMaxWidth
            native.segmentedRegionInterlockingDepth = multiMaterial.segmentedRegionInterlockingDepth
            native.interlockingBeam = multiMaterial.interlockingBeam
            native.interlockingBeamWidth = multiMaterial.interlockingBeamWidth
            native.interlockingOrientation = multiMaterial.interlockingOrientation
            native.interlockingBeamLayerCount = multiMaterial.interlockingBeamLayerCount
            native.interlockingDepth = multiMaterial.interlockingDepth
            native.interlockingBoundaryAvoidance = multiMaterial.interlockingBoundaryAvoidance
            native.maxVolumetricExtrusionRateSlope = quality.extrusionRateSmoothing.maximumSlope
            native.maxVolumetricExtrusionRateSlopeSegmentLength = quality.extrusionRateSmoothing.segmentLength
            native.extrusionRateSmoothingExternalOnly = quality.extrusionRateSmoothing.externalOnly
            native.enableArcFitting = gcodeSettings.arcFitting &&
                quality.extrusionRateSmoothing.maximumSlope <= 0f
            native.slicingMode = precision.mode
            native.sliceClosingRadius = precision.closingRadius
            native.preciseZHeight = precision.preciseZHeight
            native.holeToPolyhole = precision.polyholes.enabled
            native.holeToPolyholeThreshold = precision.polyholes.detectionMargin
            native.holeToPolyholeThresholdPercent = precision.polyholes.detectionMarginPercent
            native.holeToPolyholeTwisted = precision.polyholes.twist
            native.minimumWallWidth = precision.minimumWallWidth
            native.firstLayerMinimumWallWidth = precision.firstLayerMinimumWallWidth
            native.makeOverhangPrintable = printableOverhangs.enabled
            native.makeOverhangPrintableAngle = printableOverhangs.maximumAngle
            native.makeOverhangPrintableHoleSize = printableOverhangs.holeArea
            native.ironingType = ironing.type
            native.ironingPattern = ironing.pattern
            native.ironingFlow = ironing.flow
            native.ironingSpacing = ironing.spacing
            native.ironingInset = ironing.inset
            native.ironingSpeed = ironing.speed
            native.ironingAngle = ironing.angle
            native.supportOnBuildPlateOnly = supportCoverage.onBuildPlateOnly
            native.supportCriticalRegionsOnly = supportCoverage.criticalRegionsOnly
            native.supportRemoveSmallOverhangs = supportCoverage.removeSmallOverhangs
            native.supportPatternAngle = supportAdvanced.patternAngle
            native.supportThresholdOverlap = supportAdvanced.thresholdOverlap
            native.supportThresholdOverlapPercent = supportAdvanced.thresholdOverlapPercent
            native.supportObjectFirstLayerGap = supportAdvanced.objectFirstLayerGap
            native.avoidSupportInterfaceFilamentForBase = supportAdvanced.avoidInterfaceFilamentForBase
            native.supportIroning = supportAdvanced.ironingEnabled
            native.supportIroningPattern = supportAdvanced.ironingPattern
            native.supportIroningFlow = supportAdvanced.ironingFlow
            native.supportIroningSpacing = supportAdvanced.ironingSpacing
            native.gcodeLabelObjects = gcodeSettings.labelObjects
            native.excludeObject = gcodeSettings.excludeObjects
            native.gcodeComments = gcodeSettings.verboseComments
            native.initialLayerTravelSpeed = gcodeSettings.initialLayerTravelSpeed
            native.initialLayerTravelSpeedPercent = gcodeSettings.initialLayerTravelSpeedPercent
            native.slowDownLayers = gcodeSettings.slowDownLayers
            native.accelToDecelEnabled = gcodeSettings.accelToDecelEnabled
            native.accelToDecelFactor = gcodeSettings.accelToDecelFactor
            native.brimEarsMaxAngle = precision.brimEars.maximumAngle
            native.brimEarsDetectionLength = precision.brimEars.detectionRadius
            native.scarfSeamType = scarfSeam.type
            native.scarfSeamConditional = scarfSeam.conditional
            native.scarfAngleThreshold = scarfSeam.angleThreshold
            native.scarfOverhangThreshold = scarfSeam.overhangThreshold
            native.scarfJointSpeed = scarfSeam.speed
            native.scarfJointSpeedPercent = scarfSeam.speedPercent
            native.scarfJointFlowRatio = scarfSeam.flowRatio
            native.scarfStartHeight = scarfSeam.startHeight
            native.scarfStartHeightPercent = scarfSeam.startHeightPercent
            native.scarfEntireLoop = scarfSeam.entireLoop
            native.scarfLength = scarfSeam.length
            native.scarfSteps = scarfSeam.steps
            native.scarfInnerWalls = scarfSeam.innerWalls
        }
    }
}

private fun List<Float>.resizedExtruderValues(count: Int, fallback: Float): List<Float> {
    val safeCount = count.coerceIn(1, MAX_FILAMENT_SLOTS)
    val source = if (isEmpty()) listOf(fallback) else this
    return List(safeCount) { index -> source.getOrElse(index) { source.last() } }
}

internal fun SliceOptions.withSpiralMode(enabled: Boolean): SliceOptions =
    if (enabled) {
        copy(
            spiralMode = true,
            perimeters = 1,
            fillDensity = 0f,
            topSolidLayers = 0,
            supportEnabled = false,
        )
    } else {
        copy(spiralMode = false)
    }

data class FilamentSlotAssignment(
    val options: SliceOptions,
    val slot: Int,
)

internal const val MAX_FILAMENT_SLOTS = 16

internal data class TransformedProjectModels(
    val files: List<File>,
    val objectVolumeCounts: IntArray,
    val brimPoints: List<BrimPoints>,
)

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
                model = inspectModel(model.absolutePath),
                transform = modelTransform,
            ),
        ),
        options,
        onProgress = onProgress,
    )

    internal fun slice(
        objects: List<ProjectObject>,
        options: SliceOptions = SliceOptions(),
        foregroundSession: ForegroundSliceSession? = null,
        cancellationRequested: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome {
        val filamentSlots = options.resolvedFilamentSlots()
        if (options.spiralMode) {
            require(objects.size == 1) { "Spiral vase supports one object" }
            require(
                options.perimeters == 1 &&
                    options.fillDensity == 0f &&
                    options.topSolidLayers == 0 &&
                    !options.supportEnabled,
            ) { "Spiral vase requires one wall, no infill, no top layers, and no supports" }
            val usedFilamentSlots = buildSet {
                objects.single().volumes.forEach { volume ->
                    if (volume.role.acceptsFilament) add(volume.filamentSlot)
                    if (volume.role.acceptsFacetPaint) {
                        addAll(volume.multiColorPaint.facets.values)
                    }
                }
            }
            require(usedFilamentSlots.size <= 1) { "Spiral vase supports one filament" }
        }
        require(objects.all { projectObject ->
            projectObject.volumes.all { it.filamentSlot in filamentSlots.indices }
        }) {
            "Volume filament assignment is unavailable"
        }
        require(objects.all { projectObject ->
            projectObject.volumes.all { volume ->
                volume.supportPaint.facets.keys.all { it in 0 until volume.model.triangles }
            }
        }) { "Support paint references an unavailable facet" }
        require(objects.all { projectObject ->
            projectObject.volumes.all { volume ->
                volume.seamPaint.facets.keys.all { it in 0 until volume.model.triangles }
            }
        }) { "Seam paint references an unavailable facet" }
        val availableFilamentSlots = options.resolvedFilamentSlots().indices
        require(objects.all { projectObject ->
            projectObject.volumes.all { volume ->
                volume.multiColorPaint.facets.keys.all {
                    it in 0 until volume.model.triangles
                } && volume.multiColorPaint.facets.values.all { it in availableFilamentSlots }
            }
        }) { "Multi-color paint references unavailable geometry or filament" }
        val layerHeightRange = options.printerProfile.minLayerHeight..
            options.printerProfile.maxLayerHeight
        require(objects.all { projectObject ->
            projectObject.variableLayerHeights.ranges.all { range ->
                range.layerHeightMm in layerHeightRange
            }
        }) { "Variable layer height is unavailable for this nozzle" }
        require(objects.all { projectObject ->
            projectObject.processOverrides.layerHeightMm?.let {
                it in 0.04f..options.printerProfile.maxLayerHeight
            } != false
        }) { "Object layer height is unavailable for this nozzle" }

        return withTransformedModels(
            objects,
            options,
            cancellationRequested = cancellationRequested,
        ) { transformedModels ->
            val volumes = objects.flatMap(ProjectObject::volumes)
            val supportPaintFiles = volumes.mapIndexed { index, volume ->
                volume.supportPaint
                    .takeIf { it.facets.isNotEmpty() }
                    ?.let {
                        File.createTempFile(
                            "slice-support-$index-",
                            ".bin",
                            File(volume.model.localPath).parentFile,
                        ).also(it::writeSidecar)
                    }
            }
            val seamPaintFiles = volumes.mapIndexed { index, volume ->
                volume.seamPaint
                    .takeIf { it.facets.isNotEmpty() }
                    ?.let {
                        File.createTempFile(
                            "slice-seam-$index-",
                            ".bin",
                            File(volume.model.localPath).parentFile,
                        ).also(it::writeSidecar)
                    }
            }
            val variableLayerHeightFiles = objects.mapIndexed { index, projectObject ->
                projectObject.variableLayerHeights
                    .takeIf { it.ranges.isNotEmpty() }
                    ?.let {
                        File.createTempFile(
                            "slice-layers-$index-",
                            ".bin",
                            File(projectObject.primaryModelPart.model.localPath).parentFile,
                        ).also(it::writeSidecar)
                    }
            }
            val multiColorPaintFiles = volumes.mapIndexed { index, volume ->
                volume.multiColorPaint
                    .takeIf { it.facets.isNotEmpty() }
                    ?.let {
                        File.createTempFile(
                            "slice-colors-$index-",
                            ".bin",
                            File(volume.model.localPath).parentFile,
                        ).also(it::writeSidecar)
                    }
            }
            val processOverrideFiles = objects.mapIndexed { index, projectObject ->
                projectObject.processOverrides
                    .takeUnless(ObjectProcessOverrides::isEmpty)
                    ?.let {
                        File.createTempFile(
                            "slice-process-$index-",
                            ".bin",
                            File(projectObject.primaryModelPart.model.localPath).parentFile,
                        ).also(it::writeSidecar)
                    }
            }
            val brimPointFiles = transformedModels.brimPoints.mapIndexed { index, points ->
                points.takeIf { it.points.isNotEmpty() }?.let {
                    File.createTempFile(
                        "slice-brim-$index-",
                        ".bin",
                        File(objects[index].primaryModelPart.model.localPath).parentFile,
                    ).also(it::writeSidecar)
                }
            }
            val volumeConfigFiles = volumes.mapIndexed { index, volume ->
                volume.config.takeUnless { it.isEmpty }?.let {
                    File.createTempFile(
                        "slice-volume-config-$index-",
                        ".bin",
                        File(volume.model.localPath).parentFile,
                    ).also(it::writeSidecar)
                }
            }
            try {
                SlicerProcessClient.slice(
                    transformedModels.files,
                    supportPaintFiles,
                    seamPaintFiles,
                    multiColorPaintFiles,
                    variableLayerHeightFiles,
                    processOverrideFiles,
                    brimPointFiles,
                    options,
                    objectVolumeCounts = transformedModels.objectVolumeCounts,
                    filamentSlots = volumes.map(ProjectVolume::filamentSlot).toIntArray(),
                    volumeRoles = volumes.map { it.role.nativeValue }.toIntArray(),
                    volumeConfigFiles = volumeConfigFiles,
                    foregroundSession = foregroundSession,
                    cancellationRequested = cancellationRequested,
                    onProgress = onProgress,
                )
            } finally {
                supportPaintFiles.filterNotNull().forEach(File::delete)
                seamPaintFiles.filterNotNull().forEach(File::delete)
                multiColorPaintFiles.filterNotNull().forEach(File::delete)
                variableLayerHeightFiles.filterNotNull().forEach(File::delete)
                processOverrideFiles.filterNotNull().forEach(File::delete)
                brimPointFiles.filterNotNull().forEach(File::delete)
                volumeConfigFiles.filterNotNull().forEach(File::delete)
            }
        }
    }

    fun arrange(
        objects: List<ProjectObject>,
        options: SliceOptions = SliceOptions(),
        minimumGap: Float = 6f,
        requestId: String = UUID.randomUUID().toString(),
    ): OrcaArrangement {
        require(objects.size >= 2) { "At least two objects are required" }
        return withTransformedModels(
            objects,
            options,
            includePlacement = false,
            cancellationRequested = {
                SlicerProcessClient.projectRequestCancellationRequested(requestId)
            },
        ) { transformedModels ->
            val volumeRoles = objects.flatMap(ProjectObject::volumes).map(ProjectVolume::role)
            val printableModels = transformedModels.files.filterIndexed { index, _ ->
                volumeRoles[index] == ProjectVolumeRole.MODEL_PART
            }
            SlicerProcessClient.autoArrange(
                transformedModels = printableModels,
                bedSizeX = options.bedSizeX,
                bedSizeY = options.bedSizeY,
                bedOriginX = options.bedOriginX,
                bedOriginY = options.bedOriginY,
                bedPolygon = options.bedPolygon,
                bedExcludeArea = options.bedExcludeArea,
                objectVolumeCounts = objects.map { it.modelPartVolumes.size }.toIntArray(),
                minimumGap = minimumGap,
                requestId = requestId,
            )
        }
    }

    private fun <Result> withTransformedModels(
        objects: List<ProjectObject>,
        options: SliceOptions,
        includePlacement: Boolean = true,
        cancellationRequested: () -> Boolean = { false },
        block: (TransformedProjectModels) -> Result,
    ): Result {
        require(objects.isNotEmpty()) { "Project has no objects" }
        require(objects.all { projectObject ->
            projectObject.volumes.all { File(it.model.localPath).isFile }
        }) { "Model file is unavailable" }
        val transformedModels = ArrayList<File>(objects.sumOf { it.volumes.size })
        val transformedBrimPoints = ArrayList<BrimPoints>(objects.size)
        return try {
            if (cancellationRequested()) throw SlicingCancelledException()
            objects.forEachIndexed { index, projectObject ->
                if (cancellationRequested()) throw SlicingCancelledException()
                val objectOutputs = projectObject.volumes.mapIndexed { volumeIndex, volume ->
                    val modelRoot = File(volume.model.localPath).parentFile
                    File.createTempFile(
                        "slicer-input-$index-$volumeIndex-",
                        ".stl",
                        modelRoot,
                    ).also(transformedModels::add)
                }
                val transform = if (includePlacement) {
                    projectObject.transform.toJson(
                        options.bedSizeX,
                        options.bedSizeY,
                        options.bedOriginX,
                        options.bedOriginY,
                    )
                } else {
                    projectObject.transform
                        .copy(offsetXmm = 0f, offsetYmm = 0f, offsetZmm = 0f)
                        .toJson(0f, 0f)
                }
                val transformed = if (projectObject.volumes.size == 1) {
                    JSONObject(
                        NativeEngine.transformStl(
                            projectObject.singleVolume.model.localPath,
                            objectOutputs.single().absolutePath,
                            transform,
                        ),
                    )
                } else {
                    JSONObject(
                        NativeEngine.transformStlGroup(
                            JSONObject()
                                .put(
                                    "inputPaths",
                                    JSONArray(projectObject.volumes.map { it.model.localPath }),
                                )
                                .put(
                                    "outputPaths",
                                    JSONArray(objectOutputs.map(File::getAbsolutePath)),
                                )
                                .put(
                                    "boundsMask",
                                    JSONArray(projectObject.volumes.map { volume ->
                                        volume.role == ProjectVolumeRole.MODEL_PART
                                    }),
                                )
                                .put("transform", JSONObject(transform))
                                .toString(),
                        ),
                    )
                }
                check(transformed.optBoolean("ok")) { "Model transform failed" }
                val sourceCenter = transformed.getJSONArray("sourceCenterMm")
                val sourceCenterMm = FloatArray(3) { axis ->
                    sourceCenter.getDouble(axis).toFloat()
                }
                val transformedMinZ = transformed.getDouble("transformedMinZ").toFloat()
                transformedBrimPoints += projectObject.transform.transformBrimPointsForSlicing(
                    brimPoints = projectObject.brimPoints,
                    sourceCenterMm = sourceCenterMm,
                    transformedMinZ = transformedMinZ,
                    bedCenterXmm = if (includePlacement) {
                        options.bedOriginX + options.bedSizeX / 2f
                    } else {
                        0f
                    },
                    bedCenterYmm = if (includePlacement) {
                        options.bedOriginY + options.bedSizeY / 2f
                    } else {
                        0f
                    },
                )
                if (cancellationRequested()) throw SlicingCancelledException()
            }
            if (cancellationRequested()) throw SlicingCancelledException()
            block(
                TransformedProjectModels(
                    files = transformedModels,
                    objectVolumeCounts = objects.map { it.volumes.size }.toIntArray(),
                    brimPoints = transformedBrimPoints,
                ),
            )
        } finally {
            transformedModels.forEach(File::delete)
        }
    }
}

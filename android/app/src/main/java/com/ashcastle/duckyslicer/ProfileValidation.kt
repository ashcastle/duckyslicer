package com.ashcastle.duckyslicer

internal object ProfileValidation {
    fun printer(profile: PrinterProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.bedSizeX in 50f..1_500f &&
            profile.bedSizeY in 50f..1_500f &&
            profile.maxPrintHeight in 50f..1_500f &&
            profile.nozzleDiameter in 0.1f..2f &&
            profile.gcodeFlavor in setOf("marlin", "marlin2", "klipper") &&
            listOf(profile.maxSpeedX, profile.maxSpeedY, profile.maxSpeedZ, profile.maxSpeedE)
                .all { it in 0.1f..2_000f } &&
            listOf(
                profile.maxAccelerationX,
                profile.maxAccelerationY,
                profile.maxAccelerationZ,
                profile.maxAccelerationE,
                profile.maxAccelerationExtruding,
                profile.maxAccelerationRetracting,
                profile.maxAccelerationTravel,
            ).all { it in 0.1f..100_000f } &&
            listOf(profile.maxJerkX, profile.maxJerkY, profile.maxJerkZ, profile.maxJerkE)
                .all { it in 0f..100_000f } &&
            profile.machineStartGcode.length <= MAX_GCODE_TEMPLATE_LENGTH &&
            profile.machineEndGcode.length <= MAX_GCODE_TEMPLATE_LENGTH

    fun filament(profile: FilamentProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.nativeName.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.nozzleTemp in 150..400 &&
            profile.firstLayerNozzleTemp in 150..400 &&
            profile.bedTemp in 0..160 &&
            profile.firstLayerBedTemp in 0..160 &&
            profile.flowRatio in 0.5f..1.5f &&
            profile.maxVolumetricSpeed in 0.1f..100f &&
            listOf(profile.fanMinSpeed, profile.fanMaxSpeed, profile.overhangFanSpeed)
                .all { it in 0..100 } &&
            profile.retractLength in 0f..100f &&
            profile.retractSpeed in 0f..500f &&
            profile.slowDownLayerTime in 0f..600f &&
            profile.slowDownMinSpeed in 0f..500f &&
            profile.closeFanFirstLayers in 0..10_000 &&
            profile.fullFanSpeedLayer in 0..10_000 &&
            profile.pressureAdvance in 0f..10f &&
            profile.compatiblePrinters.isSafeCompatibilityList()

    fun slicing(profile: QualityProfile): Boolean =
        profile.id.isSafeLabel() &&
            profile.name.isSafeLabel() &&
            profile.brand.isSafeOptionalLabel() &&
            profile.nozzleDiameter in 0.1f..2f &&
            profile.layerHeightMm in 0.02f..(profile.nozzleDiameter * 0.9f) &&
            profile.firstLayerHeightMm in 0.02f..1f &&
            profile.perimeters in 0..20 &&
            profile.fillDensity in 0f..1f &&
            profile.printSpeed in 1f..2_000f &&
            profile.travelSpeed in 1f..2_000f &&
            profile.outerWallLineWidth in 0f..3f &&
            profile.innerWallLineWidth in 0f..3f &&
            profile.wallSequence in setOf("inner-outer", "outer-inner", "inner-outer-inner") &&
            profile.topSolidLayers in 0..100 &&
            profile.bottomSolidLayers in 0..100 &&
            profile.fillPattern.isSafeLabel() &&
            profile.supportType in setOf("normal", "tree") &&
            profile.supportAngle in 0f..90f &&
            profile.skirtLoops in 0..100 &&
            profile.skirtDistance in 0f..1_000f &&
            profile.brimWidth in 0f..1_000f &&
            profile.compatiblePrinters.isSafeCompatibilityList()

    private fun String.isSafeLabel(): Boolean = isNotBlank() && length <= MAX_LABEL_LENGTH

    private fun String?.isSafeOptionalLabel(): Boolean = this == null || isSafeLabel()

    private fun List<String>.isSafeCompatibilityList(): Boolean =
        size <= MAX_COMPATIBILITY_ENTRIES && all { it.isSafeLabel() }

    private const val MAX_LABEL_LENGTH = 512
    private const val MAX_COMPATIBILITY_ENTRIES = 512
    private const val MAX_GCODE_TEMPLATE_LENGTH = 262_144
}

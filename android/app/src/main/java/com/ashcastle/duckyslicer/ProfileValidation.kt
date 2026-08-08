package com.ashcastle.duckyslicer

internal object ProfileValidation {
    fun printer(profile: PrinterProfile): Boolean =
        profile.id.isNotBlank() &&
            profile.name.isNotBlank() &&
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
            ).all { it in 0.1f..100_000f }

    fun filament(profile: FilamentProfile): Boolean =
        profile.id.isNotBlank() &&
            profile.name.isNotBlank() &&
            profile.nativeName.isNotBlank() &&
            profile.nozzleTemp in 150..400 &&
            profile.firstLayerNozzleTemp in 150..400 &&
            profile.bedTemp in 0..160 &&
            profile.firstLayerBedTemp in 0..160 &&
            profile.flowRatio in 0.5f..1.5f &&
            profile.maxVolumetricSpeed in 0.1f..100f &&
            listOf(profile.fanMinSpeed, profile.fanMaxSpeed, profile.overhangFanSpeed)
                .all { it in 0..100 }

    fun slicing(profile: QualityProfile): Boolean =
        profile.id.isNotBlank() &&
            profile.name.isNotBlank() &&
            profile.nozzleDiameter in 0.1f..2f &&
            profile.layerHeightMm in 0.02f..(profile.nozzleDiameter * 0.9f) &&
            profile.firstLayerHeightMm in 0.02f..1f &&
            profile.perimeters in 0..20 &&
            profile.fillDensity in 0f..1f &&
            profile.printSpeed in 1f..2_000f &&
            profile.travelSpeed in 1f..2_000f
}

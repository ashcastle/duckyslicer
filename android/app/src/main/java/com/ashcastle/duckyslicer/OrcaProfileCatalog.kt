package com.ashcastle.duckyslicer

import android.content.Context
import android.util.JsonReader
import java.io.InputStreamReader

private const val CATALOG_ASSET = "profile_catalog_v2.json"

class OrcaProfileCatalog(private val context: Context) {
    fun load(): ProfileCatalog = runCatching {
        context.assets.open(CATALOG_ASSET).use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use(::readCatalog)
        }
    }.getOrElse { ProfileCatalog() }

    private fun readCatalog(reader: JsonReader): ProfileCatalog {
        var schemaVersion = 0
        var sourceRevision = ""
        var rejectedCount = 0
        var printers = emptyList<PrinterProfile>()
        var filaments = emptyList<FilamentProfile>()
        var slicing = emptyList<QualityProfile>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "schemaVersion" -> schemaVersion = reader.nextInt()
                "sourceRevision" -> sourceRevision = reader.nextString()
                "rejected" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName()
                        rejectedCount += reader.nextInt()
                    }
                    reader.endObject()
                }
                "printers" -> printers = readArray(reader, ::readPrinter)
                "filaments" -> filaments = readArray(reader, ::readFilament)
                "slicing" -> slicing = readArray(reader, ::readQuality)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        check(schemaVersion == 2) { "Unsupported profile catalog schema" }
        return ProfileCatalog(
            printers = (PrinterProfile.builtIns + printers).distinctBy(PrinterProfile::id),
            filaments = (FilamentProfile.builtIns + filaments).distinctBy(FilamentProfile::id),
            slicing = (QualityProfile.builtIns + slicing).distinctBy(QualityProfile::id),
            schemaVersion = schemaVersion,
            sourceRevision = sourceRevision,
            rejectedCount = rejectedCount,
        )
    }

    private fun <T> readArray(reader: JsonReader, readValue: (JsonReader) -> T): List<T> = buildList {
        reader.beginArray()
        while (reader.hasNext()) add(readValue(reader))
        reader.endArray()
    }

    private fun readPrinter(reader: JsonReader): PrinterProfile {
        var profile = PrinterProfile.CUSTOM_CARTESIAN
        var id = ""
        var name = ""
        var brand = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "brand" -> brand = reader.nextString()
                "bedSizeX" -> profile = profile.copy(bedSizeX = reader.nextFloat())
                "bedSizeY" -> profile = profile.copy(bedSizeY = reader.nextFloat())
                "maxPrintHeight" -> profile = profile.copy(maxPrintHeight = reader.nextFloat())
                "nozzleDiameter" -> profile = profile.copy(nozzleDiameter = reader.nextFloat())
                "machineStartGcode" -> profile = profile.copy(machineStartGcode = reader.nextString())
                "machineEndGcode" -> profile = profile.copy(machineEndGcode = reader.nextString())
                "gcodeFlavor" -> profile = profile.copy(gcodeFlavor = reader.nextString())
                "maxSpeedX" -> profile = profile.copy(maxSpeedX = reader.nextFloat())
                "maxSpeedY" -> profile = profile.copy(maxSpeedY = reader.nextFloat())
                "maxSpeedZ" -> profile = profile.copy(maxSpeedZ = reader.nextFloat())
                "maxSpeedE" -> profile = profile.copy(maxSpeedE = reader.nextFloat())
                "maxAccelerationX" -> profile = profile.copy(maxAccelerationX = reader.nextFloat())
                "maxAccelerationY" -> profile = profile.copy(maxAccelerationY = reader.nextFloat())
                "maxAccelerationZ" -> profile = profile.copy(maxAccelerationZ = reader.nextFloat())
                "maxAccelerationE" -> profile = profile.copy(maxAccelerationE = reader.nextFloat())
                "maxAccelerationExtruding" -> profile =
                    profile.copy(maxAccelerationExtruding = reader.nextFloat())
                "maxAccelerationRetracting" -> profile =
                    profile.copy(maxAccelerationRetracting = reader.nextFloat())
                "maxAccelerationTravel" -> profile = profile.copy(maxAccelerationTravel = reader.nextFloat())
                "maxJerkX" -> profile = profile.copy(maxJerkX = reader.nextFloat())
                "maxJerkY" -> profile = profile.copy(maxJerkY = reader.nextFloat())
                "maxJerkZ" -> profile = profile.copy(maxJerkZ = reader.nextFloat())
                "maxJerkE" -> profile = profile.copy(maxJerkE = reader.nextFloat())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return profile.copy(id = id, name = name, brand = brand, builtIn = true)
    }

    private fun readFilament(reader: JsonReader): FilamentProfile {
        var profile = FilamentProfile.GENERIC_PLA
        var id = ""
        var name = ""
        var brand = ""
        var compatiblePrinters = emptyList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "brand" -> brand = reader.nextString()
                "nativeName" -> profile = profile.copy(nativeName = reader.nextString())
                "nozzleTemp" -> profile = profile.copy(nozzleTemp = reader.nextInt())
                "firstLayerNozzleTemp" -> profile = profile.copy(firstLayerNozzleTemp = reader.nextInt())
                "bedTemp" -> profile = profile.copy(bedTemp = reader.nextInt())
                "firstLayerBedTemp" -> profile = profile.copy(firstLayerBedTemp = reader.nextInt())
                "flowRatio" -> profile = profile.copy(flowRatio = reader.nextFloat())
                "maxVolumetricSpeed" -> profile = profile.copy(maxVolumetricSpeed = reader.nextFloat())
                "retractLength" -> profile = profile.copy(retractLength = reader.nextFloat())
                "retractSpeed" -> profile = profile.copy(retractSpeed = reader.nextFloat())
                "fanMinSpeed" -> profile = profile.copy(fanMinSpeed = reader.nextInt())
                "fanMaxSpeed" -> profile = profile.copy(fanMaxSpeed = reader.nextInt())
                "overhangFanSpeed" -> profile = profile.copy(overhangFanSpeed = reader.nextInt())
                "slowDownLayerTime" -> profile = profile.copy(slowDownLayerTime = reader.nextFloat())
                "slowDownMinSpeed" -> profile = profile.copy(slowDownMinSpeed = reader.nextFloat())
                "closeFanFirstLayers" -> profile = profile.copy(closeFanFirstLayers = reader.nextInt())
                "fullFanSpeedLayer" -> profile = profile.copy(fullFanSpeedLayer = reader.nextInt())
                "pressureAdvanceEnabled" ->
                    profile = profile.copy(pressureAdvanceEnabled = reader.nextBoolean())
                "pressureAdvance" -> profile = profile.copy(pressureAdvance = reader.nextFloat())
                "compatiblePrinters" -> compatiblePrinters = reader.readStringList()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return profile.copy(
            id = id,
            name = name,
            brand = brand,
            builtIn = true,
            compatiblePrinters = compatiblePrinters,
        )
    }

    private fun readQuality(reader: JsonReader): QualityProfile {
        var profile = QualityProfile.STANDARD
        var id = ""
        var name = ""
        var brand = ""
        var compatiblePrinters = emptyList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "name" -> name = reader.nextString()
                "brand" -> brand = reader.nextString()
                "layerHeightMm" -> profile = profile.copy(layerHeightMm = reader.nextFloat())
                "firstLayerHeightMm" -> profile = profile.copy(firstLayerHeightMm = reader.nextFloat())
                "perimeters" -> profile = profile.copy(perimeters = reader.nextInt())
                "fillDensity" -> profile = profile.copy(fillDensity = reader.nextFloat())
                "printSpeed" -> profile = profile.copy(printSpeed = reader.nextFloat())
                "nozzleDiameter" -> profile = profile.copy(nozzleDiameter = reader.nextFloat())
                "supportEnabled" -> profile = profile.copy(supportEnabled = reader.nextBoolean())
                "brimWidth" -> profile = profile.copy(brimWidth = reader.nextFloat())
                "topSolidLayers" -> profile = profile.copy(topSolidLayers = reader.nextInt())
                "bottomSolidLayers" -> profile = profile.copy(bottomSolidLayers = reader.nextInt())
                "fillPattern" -> profile = profile.copy(fillPattern = reader.nextString())
                "travelSpeed" -> profile = profile.copy(travelSpeed = reader.nextFloat())
                "firstLayerSpeed" -> profile = profile.copy(firstLayerSpeed = reader.nextFloat())
                "supportType" -> profile = profile.copy(supportType = reader.nextString())
                "supportAngle" -> profile = profile.copy(supportAngle = reader.nextFloat())
                "skirtLoops" -> profile = profile.copy(skirtLoops = reader.nextInt())
                "skirtDistance" -> profile = profile.copy(skirtDistance = reader.nextFloat())
                "outerWallLineWidth" -> profile = profile.copy(outerWallLineWidth = reader.nextFloat())
                "innerWallLineWidth" -> profile = profile.copy(innerWallLineWidth = reader.nextFloat())
                "wallSequence" -> profile = profile.copy(wallSequence = reader.nextString())
                "detectThinWalls" -> profile = profile.copy(detectThinWalls = reader.nextBoolean())
                "detectOverhangWalls" -> profile = profile.copy(detectOverhangWalls = reader.nextBoolean())
                "onlyOneWallOnTop" -> profile = profile.copy(onlyOneWallOnTop = reader.nextBoolean())
                "preciseOuterWalls" -> profile = profile.copy(preciseOuterWalls = reader.nextBoolean())
                "compatiblePrinters" -> compatiblePrinters = reader.readStringList()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return profile.copy(
            id = id,
            name = name,
            brand = brand,
            builtIn = true,
            compatiblePrinters = compatiblePrinters,
        )
    }
}

private fun JsonReader.nextFloat(): Float = nextDouble().toFloat()

private fun JsonReader.readStringList(): List<String> = buildList {
    beginArray()
    while (hasNext()) add(nextString())
    endArray()
}

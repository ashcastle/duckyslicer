package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliceOptionsPersistenceTest {
    @Test
    fun effectivePrinterFilamentAndSlicingOverridesRoundTripCanonically() {
        val options = restoredSettingsFixture()
        val stored = options.toProjectJson()

        val restored = stored.toProjectSliceOptionsOrNull()

        requireNotNull(restored)
        assertEquals(stored.toString(), restored.toProjectJson().toString())
        assertEquals(options.printerProfile.id, restored.printerProfile.id)
        assertEquals(options.printerProfile.brand, restored.printerProfile.brand)
        assertEquals(options.printerProfile.builtIn, restored.printerProfile.builtIn)
        assertEquals(options.filamentProfile.compatiblePrinters, restored.filamentProfile.compatiblePrinters)
        assertEquals(options.quality.compatiblePrinters, restored.quality.compatiblePrinters)
        assertEquals(248, restored.nozzleTemp)
        assertEquals(0.64f, restored.outerWallLineWidth)
        assertEquals(0.68f, restored.innerWallLineWidth)
        assertEquals("outer-inner", restored.wallSequence)
        assertEquals("klipper", restored.gcodeFlavor)
        assertEquals(4_600f, restored.maxAccelerationTravel)
    }

    @Test
    fun unsafeOrUnknownStoredSettingsAreIgnored() {
        val unsafe = restoredSettingsFixture().toProjectJson()
        unsafe.getJSONObject("printer").put("maxSpeedX", 0)
        assertNull(unsafe.toProjectSliceOptionsOrNull())

        val unknown = JSONObject(restoredSettingsFixture().toProjectJson().toString())
            .put("formatVersion", 99)
        assertNull(unknown.toProjectSliceOptionsOrNull())
    }
}

internal fun restoredSettingsFixture(): SliceOptions = SliceOptions()
    .selectPrinter(PrinterProfile.U1_06)
    .selectFilament(
        FilamentProfile.PETG.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_06.name),
        ),
    )
    .selectQuality(
        QualityProfile.FINE_06.copy(
            compatiblePrinters = listOf(PrinterProfile.U1_06.name),
        ),
    )
    .copy(
        bedSizeX = 278f,
        bedSizeY = 282f,
        maxPrintHeight = 290f,
        nozzleTemp = 248,
        firstLayerNozzleTemp = 252,
        bedTemp = 74,
        firstLayerBedTemp = 78,
        flowRatio = 0.97f,
        maxVolumetricSpeed = 11f,
        retractLength = 1.1f,
        retractSpeed = 37f,
        fanMinSpeed = 35,
        fanMaxSpeed = 72,
        overhangFanSpeed = 88,
        slowDownLayerTime = 9f,
        slowDownMinSpeed = 14f,
        closeFanFirstLayers = 2,
        fullFanSpeedLayer = 5,
        pressureAdvanceEnabled = true,
        pressureAdvance = 0.034f,
        layerHeight = 0.24f,
        firstLayerHeight = 0.3f,
        perimeters = 4,
        fillDensity = 0.27f,
        printSpeed = 145f,
        topSolidLayers = 7,
        bottomSolidLayers = 6,
        fillPattern = "grid",
        travelSpeed = 410f,
        firstLayerSpeed = 32f,
        supportEnabled = true,
        supportType = "tree",
        supportAngle = 38f,
        skirtLoops = 2,
        skirtDistance = 8f,
        brimWidth = 5f,
        outerWallLineWidth = 0.64f,
        innerWallLineWidth = 0.68f,
        wallSequence = "outer-inner",
        detectThinWalls = true,
        detectOverhangWalls = false,
        onlyOneWallOnTop = true,
        preciseOuterWalls = true,
        gcodeFlavor = "klipper",
        maxSpeedX = 330f,
        maxSpeedY = 340f,
        maxAccelerationX = 4_800f,
        maxAccelerationY = 4_900f,
        maxAccelerationExtruding = 3_200f,
        maxAccelerationTravel = 4_600f,
        maxJerkX = 8f,
        maxJerkY = 9f,
    )

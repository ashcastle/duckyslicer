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
        assertEquals(0.55f, restored.topSurfaceLineWidth)
        assertEquals(0.72f, restored.sparseInfillLineWidth)
        assertEquals(0.61f, restored.internalSolidInfillLineWidth)
        assertEquals(0.58f, restored.supportLineWidth)
        assertEquals(175f, restored.innerWallSpeed)
        assertEquals(210f, restored.sparseInfillSpeed)
        assertEquals(165f, restored.internalSolidInfillSpeed)
        assertEquals(95f, restored.topSurfaceSpeed)
        assertEquals(85f, restored.supportSpeed)
        assertEquals(43f, restored.bridgeSpeed)
        assertEquals(133f, restored.gapInfillSpeed)
        assertEquals(61f, restored.firstLayerInfillSpeed)
        assertEquals(52f, restored.supportInterfaceSpeed)
        assertEquals(false, restored.overhangSpeedEnabled)
        assertEquals(81f, restored.overhangSpeed1)
        assertEquals(true, restored.overhangSpeed1Percent)
        assertEquals("crosshatch", restored.fillPattern)
        assertEquals("monotonic", restored.topSurfacePattern)
        assertEquals("concentric", restored.bottomSurfacePattern)
        assertEquals("rectilinear", restored.internalSolidInfillPattern)
        assertEquals("nearest", restored.seamPosition)
        assertEquals("top", restored.ironingType)
        assertEquals("concentric", restored.ironingPattern)
        assertEquals(13f, restored.ironingFlow)
        assertEquals(0.17f, restored.ironingSpacing)
        assertEquals(27f, restored.ironingSpeed)
        assertEquals("rectilinear-grid", restored.supportBasePattern)
        assertEquals("rectilinear_interlaced", restored.supportInterfacePattern)
        assertEquals("snug", restored.supportStyle)
        assertEquals(0.91f, restored.bridgeFlowRatio)
        assertEquals(0.96f, restored.internalBridgeFlowRatio)
        assertEquals(0.97f, restored.topSurfaceFlowRatio)
        assertEquals(0.98f, restored.bottomSurfaceFlowRatio)
        assertEquals(0.8f, restored.topShellThickness)
        assertEquals(0.7f, restored.bottomShellThickness)
        assertEquals(4, restored.supportInterfaceTopLayers)
        assertEquals(2, restored.supportInterfaceBottomLayers)
        assertEquals(0.24f, restored.supportInterfaceSpacing)
        assertEquals(0.28f, restored.supportBottomInterfaceSpacing)
        assertEquals(0.18f, restored.supportTopZDistance)
        assertEquals(0.22f, restored.supportBottomZDistance)
        assertEquals(0.4f, restored.supportObjectXYDistance)
        assertEquals(0.74f, restored.initialLayerLineWidth)
        assertEquals(4_000f, restored.defaultAcceleration)
        assertEquals(2_000f, restored.outerWallAcceleration)
        assertEquals(3_500f, restored.innerWallAcceleration)
        assertEquals(1_200f, restored.topSurfaceAcceleration)
        assertEquals(5_000f, restored.travelAcceleration)
        assertEquals(600f, restored.firstLayerAcceleration)
        assertEquals("classic", restored.wallGenerator)
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
        fillPattern = "crosshatch",
        topSurfacePattern = "monotonic",
        bottomSurfacePattern = "concentric",
        internalSolidInfillPattern = "rectilinear",
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
        topSurfaceLineWidth = 0.55f,
        sparseInfillLineWidth = 0.72f,
        internalSolidInfillLineWidth = 0.61f,
        supportLineWidth = 0.58f,
        innerWallSpeed = 175f,
        sparseInfillSpeed = 210f,
        internalSolidInfillSpeed = 165f,
        topSurfaceSpeed = 95f,
        supportSpeed = 85f,
        bridgeSpeed = 43f,
        gapInfillSpeed = 133f,
        firstLayerInfillSpeed = 61f,
        supportInterfaceSpeed = 52f,
        overhangSpeedEnabled = false,
        overhangSpeed1 = 81f,
        overhangSpeed1Percent = true,
        overhangSpeed2 = 52f,
        overhangSpeed2Percent = false,
        overhangSpeed3 = 33f,
        overhangSpeed3Percent = true,
        overhangSpeed4 = 21f,
        overhangSpeed4Percent = false,
        bridgeFlowRatio = 0.91f,
        internalBridgeFlowRatio = 0.96f,
        topSurfaceFlowRatio = 0.97f,
        bottomSurfaceFlowRatio = 0.98f,
        topShellThickness = 0.8f,
        bottomShellThickness = 0.7f,
        supportInterfaceTopLayers = 4,
        supportInterfaceBottomLayers = 2,
        supportInterfaceSpacing = 0.24f,
        supportBottomInterfaceSpacing = 0.28f,
        supportTopZDistance = 0.18f,
        supportBottomZDistance = 0.22f,
        supportObjectXYDistance = 0.4f,
        supportBasePattern = "rectilinear-grid",
        supportInterfacePattern = "rectilinear_interlaced",
        supportStyle = "snug",
        initialLayerLineWidth = 0.74f,
        seamPosition = "nearest",
        ironingType = "top",
        ironingPattern = "concentric",
        ironingFlow = 13f,
        ironingSpacing = 0.17f,
        ironingSpeed = 27f,
        defaultAcceleration = 4_000f,
        outerWallAcceleration = 2_000f,
        innerWallAcceleration = 3_500f,
        topSurfaceAcceleration = 1_200f,
        travelAcceleration = 5_000f,
        firstLayerAcceleration = 600f,
        wallGenerator = "classic",
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

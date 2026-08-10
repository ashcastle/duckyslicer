package com.ashcastle.duckyslicer

import java.io.DataInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectProcessOverridesTest {
    @Test
    fun sidecarStoresAStableBoundedMaskAndValues() {
        val sidecar = Files.createTempFile("object-process-", ".bin").toFile()
        try {
            val overrides = ObjectProcessOverrides(
                layerHeightMm = 0.12f,
                wallLoops = 5,
                sparseInfillDensityPercent = 35f,
                innerWallSpeedMmS = 80f,
                supportEnabled = false,
            )

            overrides.writeSidecar(sidecar)

            assertEquals(ObjectProcessOverrides.SIDECAR_BYTES, sidecar.length())
            DataInputStream(sidecar.inputStream().buffered()).use { reader ->
                val magic = ByteArray(4).also(reader::readFully)
                assertTrue(magic.contentEquals(ObjectProcessOverrides.MAGIC))
                assertEquals(overrides.mask, reader.readInt())
                assertEquals(0.12f, reader.readFloat())
                assertEquals(5, reader.readInt())
            }
        } finally {
            sidecar.delete()
        }
    }

    @Test
    fun invalidOverridesAreRejectedBeforeTheyReachNativeCode() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjectProcessOverrides(layerHeightMm = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObjectProcessOverrides(wallLoops = 21)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObjectProcessOverrides(sparseInfillDensityPercent = 101f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ObjectProcessOverrides(outerWallSpeedMmS = 0f)
        }
    }
}

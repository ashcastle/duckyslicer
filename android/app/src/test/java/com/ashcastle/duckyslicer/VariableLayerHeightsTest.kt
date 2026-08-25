package com.ashcastle.duckyslicer

import java.io.DataInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VariableLayerHeightsTest {
    @Test
    fun rangesAreSortedNonOverlappingAndWriteBoundedSidecar() {
        val ranges = VariableLayerHeights(
            listOf(
                VariableLayerRange(0.1f, 0.3f, 0.08f),
                VariableLayerRange(0.3f, 0.8f, 0.16f),
            ),
        )
        val output = Files.createTempFile("variable-layers", ".bin").toFile()
        try {
            ranges.writeSidecar(output)
            DataInputStream(output.inputStream().buffered()).use { reader ->
                val magic = ByteArray(4)
                reader.readFully(magic)
                assertArrayEquals(VariableLayerHeights.MAGIC, magic)
                assertEquals(VariableLayerHeights.MODE_MANUAL, reader.readInt())
                assertEquals(0f, reader.readFloat(), 0f)
                assertEquals(2, reader.readInt())
                assertEquals(0.1f, reader.readFloat(), 0f)
                assertEquals(0.3f, reader.readFloat(), 0f)
                assertEquals(0.08f, reader.readFloat(), 0f)
                assertEquals(0.3f, reader.readFloat(), 0f)
                assertEquals(0.8f, reader.readFloat(), 0f)
                assertEquals(0.16f, reader.readFloat(), 0f)
            }
            assertEquals(
                VariableLayerHeights.HEADER_BYTES + 2 * VariableLayerHeights.ENTRY_BYTES,
                output.length(),
            )
        } finally {
            output.delete()
        }
    }

    @Test
    fun adaptiveModeWritesQualityWithoutManualRanges() {
        val adaptive = VariableLayerHeights(adaptiveQuality = 0.35f)
        val output = Files.createTempFile("adaptive-layers", ".bin").toFile()
        try {
            adaptive.writeSidecar(output)
            DataInputStream(output.inputStream().buffered()).use { reader ->
                reader.skipBytes(4)
                assertEquals(VariableLayerHeights.MODE_ADAPTIVE, reader.readInt())
                assertEquals(0.35f, reader.readFloat(), 0f)
                assertEquals(0, reader.readInt())
            }
            assertEquals(VariableLayerHeights.HEADER_BYTES, output.length())
        } finally {
            output.delete()
        }
        assertThrows(IllegalArgumentException::class.java) {
            VariableLayerHeights(
                ranges = listOf(VariableLayerRange(0.1f, 0.2f, 0.1f)),
                adaptiveQuality = 0.5f,
            )
        }
    }

    @Test
    fun invalidOrOverlappingRangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VariableLayerRange(0.4f, 0.2f, 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VariableLayerHeights(
                listOf(
                    VariableLayerRange(0.1f, 0.6f, 0.1f),
                    VariableLayerRange(0.5f, 0.8f, 0.2f),
                ),
            )
        }
    }
}

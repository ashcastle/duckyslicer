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

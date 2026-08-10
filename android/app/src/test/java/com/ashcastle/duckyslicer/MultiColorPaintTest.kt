package com.ashcastle.duckyslicer

import java.io.DataInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MultiColorPaintTest {
    @Test
    fun paintWritesSortedOneBasedOrcaExtruderStates() {
        val paint = MultiColorPaint()
            .paint(9, 1)
            .paint(2, 0)
        val output = Files.createTempFile("multi-color-paint", ".bin").toFile()
        try {
            paint.writeSidecar(output)
            DataInputStream(output.inputStream().buffered()).use { reader ->
                val magic = ByteArray(4)
                reader.readFully(magic)
                assertArrayEquals(MultiColorPaint.MAGIC, magic)
                assertEquals(2, reader.readInt())
                assertEquals(2, reader.readInt())
                assertEquals(1, reader.readUnsignedByte())
                assertEquals(9, reader.readInt())
                assertEquals(2, reader.readUnsignedByte())
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun erasedAndUnavailableSlotsAreRemoved() {
        val paint = MultiColorPaint()
            .paint(2, 0)
            .paint(3, 2)
            .paint(2, null)

        assertEquals(mapOf(3 to 2), paint.facets)
        assertEquals(MultiColorPaint(), paint.constrainedToSlotCount(2))
        assertThrows(IllegalArgumentException::class.java) { MultiColorPaint(mapOf(0 to 16)) }
    }
}

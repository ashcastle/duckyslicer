package com.ashcastle.duckyslicer

import java.io.DataInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SupportPaintTest {
    @Test
    fun supportPaintReplacesErasesAndWritesSortedBoundedSidecar() {
        val paint = SupportPaint()
            .paint(12, SupportPaintState.BLOCK)
            .paint(3, SupportPaintState.ENFORCE)
            .paint(12, SupportPaintState.ENFORCE)
        val erased = paint.paint(3, null)
        val output = Files.createTempFile("duckyslicer-support-paint-", ".bin").toFile()
        try {
            paint.writeSidecar(output)
            DataInputStream(output.inputStream()).use { reader ->
                assertEquals("DSP1", String(reader.readNBytes(4), Charsets.US_ASCII))
                assertEquals(2, reader.readInt())
                assertEquals(3, reader.readInt())
                assertEquals(SupportPaintState.ENFORCE.code, reader.readUnsignedByte())
                assertEquals(12, reader.readInt())
                assertEquals(SupportPaintState.ENFORCE.code, reader.readUnsignedByte())
            }
            assertEquals(SupportPaint.HEADER_BYTES + 2 * SupportPaint.ENTRY_BYTES, output.length())
            assertFalse(erased.facets.containsKey(3))
        } finally {
            output.delete()
        }
    }
}

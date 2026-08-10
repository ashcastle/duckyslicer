package com.ashcastle.duckyslicer

import java.io.DataInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SeamPaintTest {
    @Test
    fun seamPaintReplacesErasesAndWritesSortedBoundedSidecar() {
        val paint = SeamPaint()
            .paint(12, SeamPaintState.BLOCK)
            .paint(3, SeamPaintState.ENFORCE)
            .paint(12, SeamPaintState.ENFORCE)
        val output = Files.createTempFile("ducky-seam-paint-", ".bin").toFile()
        try {
            paint.writeSidecar(output)
            DataInputStream(output.inputStream().buffered()).use { reader ->
                val magic = ByteArray(SeamPaint.MAGIC.size)
                reader.readFully(magic)
                assertEquals(SeamPaint.MAGIC.toList(), magic.toList())
                assertEquals(2, reader.readInt())
                assertEquals(3, reader.readInt())
                assertEquals(SeamPaintState.ENFORCE.code, reader.readUnsignedByte())
                assertEquals(12, reader.readInt())
                assertEquals(SeamPaintState.ENFORCE.code, reader.readUnsignedByte())
            }
            assertEquals(SeamPaint.HEADER_BYTES + 2 * SeamPaint.ENTRY_BYTES, output.length())
            assertEquals(mapOf(3 to SeamPaintState.ENFORCE), paint.paint(12, null).facets)
        } finally {
            output.delete()
        }
    }
}

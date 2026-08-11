package com.ashcastle.duckyslicer

import java.io.DataInputStream
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrimPointsTest {
    @Test
    fun sidecarIsBoundedVersionedAndBigEndian() {
        val points = BrimPoints(
            listOf(
                BrimPoint(1f, 2f, 0f, 4f),
                BrimPoint(-3f, 5f, -0.0001f, 6.5f),
            ),
        )
        val output = Files.createTempFile("ducky-brim-points-", ".bin").toFile()
        try {
            points.writeSidecar(output)

            assertEquals(BrimPoints.HEADER_BYTES + 2L * BrimPoints.ENTRY_BYTES, output.length())
            DataInputStream(output.inputStream().buffered()).use { reader ->
                assertArrayEquals(BrimPoints.MAGIC, ByteArray(4).also(reader::readFully))
                assertEquals(2, reader.readInt())
                assertEquals(1f, reader.readFloat(), 0f)
                assertEquals(2f, reader.readFloat(), 0f)
                assertEquals(0f, reader.readFloat(), 0f)
                assertEquals(4f, reader.readFloat(), 0f)
                assertEquals(-3f, reader.readFloat(), 0f)
                assertEquals(5f, reader.readFloat(), 0f)
                assertEquals(-0.0001f, reader.readFloat(), 0f)
                assertEquals(6.5f, reader.readFloat(), 0f)
            }
        } finally {
            output.delete()
        }
    }

    @Test
    fun invalidCoordinatesRadiusAndCapacityFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            BrimPoint(Float.NaN, 0f, 0f, BrimPoint.DEFAULT_RADIUS_MM)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrimPoint(0f, 0f, 0f, BrimPoint.MIN_RADIUS_MM - 0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrimPoints(
                List(BrimPoints.MAX_POINTS + 1) {
                    BrimPoint(0f, 0f, 0f, BrimPoint.DEFAULT_RADIUS_MM)
                },
            )
        }
    }
}

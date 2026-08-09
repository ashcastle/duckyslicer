package com.ashcastle.duckyslicer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelImportTest {
    @Test
    fun copiesAFileAtTheExactLimit() {
        val source = ByteArray(16_384) { index -> (index % 251).toByte() }
        val output = ByteArrayOutputStream()

        val copied = copyModelWithLimit(ByteArrayInputStream(source), output, source.size.toLong())

        assertEquals(source.size.toLong(), copied)
        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun rejectsAFileBeforeWritingBytesPastTheLimit() {
        val source = ByteArray(20_000) { 7 }
        val output = ByteArrayOutputStream()

        assertThrows(ModelTooLargeException::class.java) {
            copyModelWithLimit(ByteArrayInputStream(source), output, 10_000)
        }

        assertEquals(8_192, output.size())
    }

    @Test
    fun zeroLimitAcceptsOnlyAnEmptyFile() {
        assertEquals(
            0,
            copyModelWithLimit(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0),
        )
        assertThrows(ModelTooLargeException::class.java) {
            copyModelWithLimit(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 0)
        }
    }
}

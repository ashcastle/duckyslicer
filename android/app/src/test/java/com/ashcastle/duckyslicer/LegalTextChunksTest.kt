package com.ashcastle.duckyslicer

import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextChunksTest {
    @Test
    fun largeLegalDocumentIsLosslesslySplitForLazyRendering() {
        val source = ("license text\n").repeat(10_000)
        val chunks = legalTextChunks(
            source.byteInputStream(),
            maximumCharacters = 4_096,
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4_096 })
        assertEquals(source, chunks.joinToString(separator = ""))
    }

    @Test
    fun emptyLegalDocumentStillHasOneRenderableChunk() {
        assertEquals(listOf(""), legalTextChunks("".byteInputStream()))
    }

    @Test
    fun oversizedLegalDocumentIsRejectedWithoutReadingItIntoOneString() {
        val source = CountingInputStream(ByteArray(128 * 1_024) { 'x'.code.toByte() }.inputStream())

        val failure = assertThrows(IllegalArgumentException::class.java) {
            legalTextChunks(source, maximumBytes = 4_096)
        }

        assertEquals("legal_document_too_large", failure.message)
        assertEquals(4_097L, source.bytesRead)
    }

    @Test
    fun canceledLegalDocumentReadStopsBetweenBoundedChunks() {
        val source = ByteArray(32 * 1_024) { 'x'.code.toByte() }
        var checks = 0

        assertThrows(CancellationException::class.java) {
            legalTextChunks(
                source.inputStream(),
                cancellationRequested = { ++checks >= 3 },
            )
        }
        assertTrue(checks >= 3)
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
            private set

        override fun read(): Int = super.read().also { value ->
            if (value >= 0) bytesRead++
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) bytesRead += count
            }
    }
}

package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalTextChunksTest {
    @Test
    fun largeLegalDocumentIsLosslesslySplitForLazyRendering() {
        val source = ("license text\n").repeat(10_000)
        val chunks = legalTextChunks(source, maximumCharacters = 4_096)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 4_096 })
        assertEquals(source, chunks.joinToString(separator = ""))
    }

    @Test
    fun emptyLegalDocumentStillHasOneRenderableChunk() {
        assertEquals(listOf(""), legalTextChunks(""))
    }
}

package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSearchTest {
    private val entries = listOf(
        listOf("MK4", "Prusa", "0.4"),
        listOf("MK4", "Prusa", "0.6"),
        listOf("A1", "Bambu", "0.4"),
    )

    @Test
    fun combinesBrandAndNozzleInEitherOrder() {
        val index = ProfileSearchIndex(entries) { it }
        assertEquals(listOf(entries[0]), index.matching("PRUSA 0.4"))
        assertEquals(listOf(entries[0]), index.matching(" 0.4\tprusa\n"))
        assertEquals(emptyList<List<String>>(), index.matching("Prusa 0.8"))
    }

    @Test
    fun preservesCatalogOrderAndDoesNotRebuildFieldsWhileTyping() {
        var calls = 0
        val index = ProfileSearchIndex(entries) { calls++; it }
        assertEquals(entries, index.matching("  "))
        assertEquals(entries.take(2), index.matching("prusa"))
        assertEquals(listOf(entries[1]), index.matching("prusa 0.6"))
        assertEquals(entries.size, calls)
    }
}

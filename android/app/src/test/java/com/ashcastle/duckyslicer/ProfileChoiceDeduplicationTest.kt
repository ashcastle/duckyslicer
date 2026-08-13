package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileChoiceDeduplicationTest {
    private data class Choice(
        val id: String,
        val name: String,
        val brand: String?,
        val builtIn: Boolean,
    )

    @Test
    fun generatedBuiltInReplacesFallbackWithTheSameDisplayIdentity() {
        val fallback = Choice("fallback", "Generic PLA", "Creality", true)
        val generated = Choice("generated", "Generic PLA", "Creality", true)
        val selected = Choice("other", "PETG", "Generic", true)

        val result = choices(listOf(fallback, generated, selected), selected)

        assertEquals(listOf(generated, selected), result)
    }

    @Test
    fun currentlySelectedFallbackRemainsVisibleInsteadOfBeingSilentlyReplaced() {
        val fallback = Choice("fallback", "Generic PLA", "Creality", true)
        val generated = Choice("generated", "Generic PLA", "Creality", true)

        assertEquals(listOf(fallback), choices(listOf(fallback, generated), fallback))
    }

    @Test
    fun personalProfilesWithTheSameNameRemainSeparate() {
        val first = Choice("user-1", "My PLA", null, false)
        val second = Choice("user-2", "My PLA", null, false)

        assertEquals(listOf(first, second), choices(listOf(first, second), first))
    }

    private fun choices(entries: List<Choice>, selected: Choice): List<Choice> =
        deduplicateProfileChoices(
            entries = entries,
            selected = selected,
            id = Choice::id,
            name = Choice::name,
            brand = Choice::brand,
            builtIn = Choice::builtIn,
        )
}

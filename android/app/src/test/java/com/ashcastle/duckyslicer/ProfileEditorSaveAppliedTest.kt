package com.ashcastle.duckyslicer

import org.junit.Assert.*
import org.junit.Test

class ProfileEditorSaveAppliedTest {
    private val original = SliceOptions()
    private val submitted = original.copy(layerHeight = 0.235f)
    private val saved = submitted.copy(flowRatio = 0.98f)
    private val result = ProfileEditorSaveApplied(1, "project/plate", original, submitted, saved)

    @Test fun onlyConfirmedSaveClearsSubmittedDraft() {
        val draft = ProfileEditSession(original, submitted)
        assertTrue(draft.isDirty)
        val confirmed = draft.saved(result)
        assertFalse(confirmed.isDirty)
        assertEquals(saved, confirmed.working)
    }

    @Test fun editsMadeWhileSavingArePreserved() {
        val newer = submitted.copy(layerHeight = 0.245f)
        val confirmed = ProfileEditSession(original, newer).saved(result)
        assertEquals(newer, confirmed.working)
        assertEquals(saved, confirmed.opening)
        assertTrue(confirmed.isDirty)
    }

    @Test fun unrelatedSessionIsNotReplaced() {
        val other = ProfileEditSession(original.copy(layerHeight = 0.1f))
        assertEquals(other, other.saved(result))
    }
}

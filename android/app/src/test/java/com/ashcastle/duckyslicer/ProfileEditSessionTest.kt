package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditSessionTest {
    @Test
    fun changesStayStagedUntilApplied() {
        val opening = SliceOptions()
        val changed = opening.copy(layerHeight = 0.24f)

        val staged = ProfileEditSession(opening).update(changed)

        assertTrue(staged.isDirty)
        assertEquals(opening, staged.opening)
        assertEquals(changed, staged.working)
    }

    @Test
    fun revertRestoresTheOpeningSnapshot() {
        val opening = SliceOptions()
        val staged = ProfileEditSession(opening).update(opening.copy(fillDensity = 0.35f))

        val reverted = staged.revert()

        assertFalse(reverted.isDirty)
        assertEquals(opening, reverted.working)
    }

    @Test
    fun applyPromotesWorkingValuesWithoutClosingTheSession() {
        val opening = SliceOptions()
        val changed = opening.copy(printSpeed = 72f)

        val applied = ProfileEditSession(opening).update(changed).applied()

        assertFalse(applied.isDirty)
        assertEquals(changed, applied.opening)
        assertEquals(changed, applied.working)
        assertEquals(changed, applied.update(changed.copy(printSpeed = 90f)).revert().working)
    }
}

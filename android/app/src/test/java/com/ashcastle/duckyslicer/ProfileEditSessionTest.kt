package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditSessionTest {
    @Test
    fun dismissAndReopenRetainsDraftAcrossProfileKinds() {
        val options = SliceOptions()
        val model = ProfileEditorDraft()
        model.open(ProfileSettingsKind.SLICING, options)
        val draft = options.copy(fillDensity = 0.35f)
        model.editor = model.editor!!.copy(session = model.editor!!.session.update(draft))
        model.dismiss()
        assertFalse(model.visible)
        model.open(ProfileSettingsKind.FILAMENT, options)
        assertTrue(model.visible)
        assertEquals(draft, model.editor!!.session.working)
        assertEquals(options, model.editor!!.session.revert().working)
    }

    @Test
    fun cleanDraftReopensWithLatestAppliedOptions() {
        val model = ProfileEditorDraft()
        model.open(ProfileSettingsKind.SLICING, SliceOptions())
        model.dismiss()
        val latest = SliceOptions().copy(fillDensity = 0.45f)
        model.open(ProfileSettingsKind.SLICING, latest)
        assertEquals(latest, model.editor!!.session.working)
        assertFalse(model.editor!!.session.isDirty)
    }

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

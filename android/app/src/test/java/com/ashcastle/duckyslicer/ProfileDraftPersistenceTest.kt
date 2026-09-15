package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileDraftPersistenceTest {
    @Test fun restoresDirtyValuesButDoesNotApplyThem() {
        val original = SliceOptions()
        val state = ProfileEditorState(ProfileSettingsKind.SLICING,
            ProfileEditSession(original, original.copy(layerHeight = 0.24f)))
        val restored = requireNotNull(restoreProfileDraft(state.encodeDraft(), original))
        assertEquals(state.kind, restored.kind)
        assertEquals(original, restored.session.opening)
        assertEquals(state.session.working.toProjectJson().toProjectSliceOptionsOrNull(), restored.session.working)
        assertEquals(0.24f, restored.session.working.layerHeight, 0f)
    }

    @Test fun refusesUnrelatedBaselineAndMalformedData() {
        val original = SliceOptions()
        val state = ProfileEditorState(ProfileSettingsKind.FILAMENT,
            ProfileEditSession(original, original.copy(layerHeight = 0.24f)))
        assertNull(restoreProfileDraft(state.encodeDraft(), original.copy(layerHeight = 0.28f)))
        assertNull(restoreProfileDraft("broken", original))
        assertNull(restoreProfileDraft(null, original))
    }

    @Test fun appliedAndRevertedSessionsRemovePersistentDraft() {
        val original = SliceOptions()
        val session = ProfileEditSession(original, original.copy(layerHeight = 0.24f))
        assertNull(ProfileEditorState(ProfileSettingsKind.SLICING, session.applied()).encodeDraft())
        assertNull(ProfileEditorState(ProfileSettingsKind.SLICING, session.revert()).encodeDraft())
    }
}

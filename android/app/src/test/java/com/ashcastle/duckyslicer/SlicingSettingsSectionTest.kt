package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Test

class SlicingSettingsSectionTest {
    @Test
    fun processEditorUsesOrcaStyleSectionOrder() {
        assertEquals(
            listOf("QUALITY", "STRENGTH", "SPEED", "SUPPORT", "OTHERS"),
            SlicingSettingsSection.entries.map(SlicingSettingsSection::name),
        )
        assertEquals(
            "Every section must have its own localized title",
            SlicingSettingsSection.entries.size,
            SlicingSettingsSection.entries.map(SlicingSettingsSection::titleResource).distinct().size,
        )
    }
}

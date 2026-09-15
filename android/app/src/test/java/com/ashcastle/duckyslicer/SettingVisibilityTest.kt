package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingVisibilityTest {
    @Test fun basicModeShowsCommonSettingsAndSearchFindsAdvancedSettings() {
        val basic = setOf("Layer height", "Infill", "Support type")
        basic.forEach { assertTrue(settingVisible("", it, basic)) }
        assertFalse(settingVisible("", "Top Z distance", basic))
        assertTrue(settingVisible("z distance", "Top Z distance", basic))
        assertFalse(settingVisible("speed", "Top Z distance", basic))
        assertFalse(settingVisible("   ", "Top Z distance", basic))
    }

    @Test fun expertModeShowsAllButStillRespectsSearch() {
        assertTrue(settingVisible("", "Top Z distance", null))
        assertTrue(settingVisible("Z DISTANCE", "Top Z distance", null))
        assertFalse(settingVisible("speed", "Top Z distance", null))
    }
}

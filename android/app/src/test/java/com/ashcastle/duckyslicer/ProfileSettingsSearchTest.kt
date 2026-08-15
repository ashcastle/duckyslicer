package com.ashcastle.duckyslicer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsSearchTest {
    @Test
    fun settingSearchTargetsOptionLabelsInsteadOfProfileNames() {
        assertTrue(settingQueryMatches("Z distance", "Top Z distance"))
        assertTrue(settingQueryMatches("outer speed", "Outer wall speed"))
        assertTrue(settingQueryMatches("  speed  ", "Outer wall speed"))
        assertTrue(settingQueryMatches("거리", "상단 Z 거리"))
        assertTrue(settingQueryMatches("maximum layer", "Maximum layer height"))
        assertTrue(settingQueryMatches("offset", "X offset"))
        assertTrue(settingQueryMatches("tool change", "Tool change retraction length"))
        assertTrue(settingQueryMatches("layer change", "Before layer change G-code"))
        assertTrue(settingQueryMatches("filament", "Change filament G-code"))
        assertTrue(settingQueryMatches("relative E", "Use relative E distances"))
        assertTrue(settingQueryMatches("machine limits", "Emit machine limits to G-code"))
        assertTrue(settingQueryMatches("printing object", "Printing by object G-code"))
        assertTrue(settingQueryMatches("cooling tube", "Cooling tube position"))
        assertTrue(settingQueryMatches("parking", "Filament parking position"))
        assertTrue(settingQueryMatches("ramming", "Enable filament ramming"))
        assertTrue(settingQueryMatches("프라임", "프라임 타워에서 퍼지"))
        assertTrue(settingQueryMatches("interface purge", "Interface purge length"))
        assertTrue(settingQueryMatches("flow compensation", "Flow compensation model"))
        assertTrue(settingQueryMatches("timelapse", "Timelapse"))
        assertTrue(settingQueryMatches("타임랩스", "타임랩스"))
        assertTrue(settingQueryMatches("first layer", "Scan first layer"))
        assertTrue(settingQueryMatches("레이어 검사", "첫 레이어 검사"))
        assertFalse(settingQueryMatches("Z distance", "0.20 mm Standard"))
    }

    @Test
    fun blankSettingSearchKeepsTheWholeEditorVisible() {
        assertTrue(settingQueryMatches("", "Layer height"))
        assertTrue(settingQueryMatches("   ", "Nozzle temperature"))
    }
}

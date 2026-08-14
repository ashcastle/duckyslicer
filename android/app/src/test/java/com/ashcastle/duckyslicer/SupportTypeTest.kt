package com.ashcastle.duckyslicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTypeTest {
    @Test
    fun legacyAutomaticSelectionsMigrateWithoutBecomingManual() {
        assertEquals("normal(auto)", normalizedSupportType("normal"))
        assertEquals("tree(auto)", normalizedSupportType("tree"))
        assertEquals("tree(auto)", normalizedSupportType("hybrid(auto)"))
        assertTrue("tree".isTreeSupportType())
        assertFalse("normal".isTreeSupportType())
    }

    @Test
    fun explicitManualSelectionsRemainManual() {
        assertEquals("normal(manual)", normalizedSupportType("normal(manual)"))
        assertEquals("tree(manual)", normalizedSupportType("tree(manual)"))
        assertEquals("unsupported", normalizedSupportType("UNSUPPORTED"))
    }

    @Test
    fun supportStylesFollowTheSelectedSupportAlgorithm() {
        assertEquals(listOf("default", "grid", "snug"), compatibleSupportStyles("normal(auto)"))
        assertEquals(
            listOf("default", "organic", "tree_slim", "tree_strong", "tree_hybrid"),
            compatibleSupportStyles("tree(manual)"),
        )
        assertEquals("default", normalizedSupportStyle("normal(auto)", "organic"))
        assertEquals("default", normalizedSupportStyle("tree(auto)", "snug"))
        assertEquals("tree_strong", normalizedSupportStyle("tree(auto)", "TREE_STRONG"))
    }

    @Test
    fun fuzzySkinSettingsReachTheNativeContractAndRespectWallGeneratorCompatibility() {
        val fuzzy = FuzzySkinSettings(
            type = "allwalls",
            firstLayer = true,
            pointDistance = 0.65f,
            thickness = 0.28f,
            mode = "combined",
            noiseType = "billow",
            scale = 3.5f,
            octaves = 6,
            persistence = 0.7f,
        )
        val arachne = SliceOptions(wallGenerator = "arachne", fuzzySkin = fuzzy).toNativeConfig()
        val classic = SliceOptions(wallGenerator = "classic", fuzzySkin = fuzzy).toNativeConfig()

        assertEquals("allwalls", arachne.fuzzySkinType)
        assertTrue(arachne.fuzzySkinFirstLayer)
        assertEquals(0.65f, arachne.fuzzySkinPointDistance)
        assertEquals(0.28f, arachne.fuzzySkinThickness)
        assertEquals("combined", arachne.fuzzySkinMode)
        assertEquals("billow", arachne.fuzzySkinNoiseType)
        assertEquals(3.5f, arachne.fuzzySkinScale)
        assertEquals(6, arachne.fuzzySkinOctaves)
        assertEquals(0.7f, arachne.fuzzySkinPersistence)
        assertEquals("displacement", classic.fuzzySkinMode)
    }
}

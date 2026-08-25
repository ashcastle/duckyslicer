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
        assertEquals(listOf("grid", "snug"), compatibleSupportStyles("normal(auto)"))
        assertEquals(
            listOf("organic", "tree_slim", "tree_strong", "tree_hybrid"),
            compatibleSupportStyles("tree(manual)"),
        )
        assertEquals("grid", normalizedSupportStyle("normal(auto)", "default"))
        assertEquals("organic", normalizedSupportStyle("tree(auto)", "default"))
        assertEquals("grid", normalizedSupportStyle("normal(auto)", "organic"))
        assertEquals("organic", normalizedSupportStyle("tree(auto)", "snug"))
        assertEquals("tree_strong", normalizedSupportStyle("tree(auto)", "TREE_STRONG"))
        assertTrue(ProfileValidation.slicing(QualityProfile.STANDARD))
        assertTrue(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(supportType = "tree(auto)", supportStyle = "default"),
            ),
        )
        assertFalse(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(supportType = "normal(auto)", supportStyle = "organic"),
            ),
        )
    }

    @Test
    fun supportFlowRatiosUseTheEngineBounds() {
        assertTrue(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(
                    supportFlowRatio = 0f,
                    supportInterfaceFlowRatio = 2f,
                ),
            ),
        )
        assertFalse(ProfileValidation.slicing(QualityProfile.STANDARD.copy(supportFlowRatio = -0.01f)))
        assertFalse(
            ProfileValidation.slicing(
                QualityProfile.STANDARD.copy(supportInterfaceFlowRatio = 2.01f),
            ),
        )
    }

    @Test
    fun supportSettingFamiliesFollowTypeStyleAndEnabledState() {
        assertTrue("normal(auto)".isAutomaticSupportType())
        assertTrue("tree(auto)".isAutomaticSupportType())
        assertFalse("tree(manual)".isAutomaticSupportType())
        assertEquals(
            TreeSupportSettingsKind.NONE,
            treeSupportSettingsKind(false, "tree(auto)", "organic"),
        )
        assertEquals(
            TreeSupportSettingsKind.ORGANIC,
            treeSupportSettingsKind(true, "tree(auto)", "default"),
        )
        assertEquals(
            TreeSupportSettingsKind.ORGANIC,
            treeSupportSettingsKind(true, "tree(manual)", "organic"),
        )
        assertEquals(
            TreeSupportSettingsKind.BRANCHED,
            treeSupportSettingsKind(true, "tree(auto)", "tree_slim"),
        )
        assertEquals(
            TreeSupportSettingsKind.BRANCHED,
            treeSupportSettingsKind(true, "tree(auto)", "tree_hybrid"),
        )
        assertEquals(
            TreeSupportSettingsKind.NONE,
            treeSupportSettingsKind(true, "normal(auto)", "grid"),
        )
    }

    @Test
    fun supportMaterialInterfaceAndIroningAvailabilityMatchesTheEngine() {
        val disabled = SliceOptions().supportSettingsAvailability()
        assertFalse(disabled.haveSupportMaterial)
        assertFalse(disabled.canIron)

        val raftOnly = SliceOptions(
            raftLayers = 2,
            supportEnabled = false,
            supportInterfaceTopLayers = 0,
        ).supportSettingsAvailability()
        assertTrue(raftOnly.haveSupportMaterial)
        assertTrue(raftOnly.canIron)
        assertEquals(TreeSupportSettingsKind.NONE, raftOnly.treeKind)

        val forcedTree = SliceOptions(
            supportEnabled = false,
            supportCoverage = SupportCoverageSettings(enforcedLayers = 3),
            supportType = "tree(auto)",
            supportStyle = "organic",
        ).supportSettingsAvailability()
        assertTrue(forcedTree.haveSupportMaterial)
        assertEquals(TreeSupportSettingsKind.ORGANIC, forcedTree.treeKind)

        val supportedInterface = SliceOptions(
            supportEnabled = true,
            supportType = "tree(auto)",
            supportStyle = "organic",
            supportInterfaceTopLayers = 2,
            supportAdvanced = SupportAdvancedSettings(ironingEnabled = true),
        ).supportSettingsAvailability()
        assertTrue(supportedInterface.automatic)
        assertTrue(supportedInterface.haveInterface)
        assertTrue(supportedInterface.canIron)
        assertTrue(supportedInterface.ironingActive)
        assertEquals(TreeSupportSettingsKind.ORGANIC, supportedInterface.treeKind)
    }

    @Test
    fun organicTreeDiametersRespectTheActiveSupportExtrusionWidth() {
        assertEquals(0.4f, minimumOrganicTreeTipDiameter(0.4f))
        assertEquals(0.1f, minimumOrganicTreeTipDiameter(0f))
        assertEquals(1f, minimumOrganicTreeBranchDiameter(0.4f, 0.4f))
        assertEquals(1.2f, minimumOrganicTreeBranchDiameter(0.6f, 0.6f))
        assertEquals(1.5f, minimumOrganicTreeBranchDiameter(0.4f, 1.5f))
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

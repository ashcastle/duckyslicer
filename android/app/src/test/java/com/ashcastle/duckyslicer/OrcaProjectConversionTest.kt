package com.ashcastle.duckyslicer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrcaProjectConversionTest {
    @Test fun preservesBrimTypeTogetherWithWidthAndGap() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "brim_type":"outer_only", "brim_width":"7.5", "brim_object_gap":"0.15"
        }"""), SliceOptions())
        assertEquals("outer_only", result.options.brimType)
        assertEquals(7.5f, result.options.brimWidth)
        assertEquals(0.15f, result.options.brimObjectGap)
    }

    @Test fun restoresSurfaceWallAndSequenceChoices() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "seam_position":"random", "wall_generator":"classic",
            "top_surface_pattern":"concentric", "bottom_surface_pattern":"rectilinear",
            "print_sequence":"by object"
        }"""), SliceOptions())
        assertEquals("random", result.options.seamPosition)
        assertEquals("classic", result.options.wallGenerator)
        assertEquals("concentric", result.options.topSurfacePattern)
        assertEquals("rectilinear", result.options.bottomSurfacePattern)
        assertEquals("by object", result.options.printSequence)
    }

    @Test fun rejectsUnknownSurfaceChoices() {
        for (key in listOf("seam_position", "wall_generator", "top_surface_pattern", "print_sequence")) {
            assertThrows(IllegalArgumentException::class.java) {
                convertOrcaProjectSettings(JSONObject().put(key, "not-supported"), SliceOptions())
            }
        }
    }

    @Test fun preservesFirstLayerAndTravelSpeeds() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "initial_layer_speed":"22.5", "travel_speed":"180", "travel_speed_z":"12"
        }"""), SliceOptions())
        assertEquals(22.5f, result.options.firstLayerSpeed)
        assertEquals(180f, result.options.travelSpeed)
        assertEquals(12f, result.options.travelSpeedZ)
    }

    @Test fun preservesPerMaterialCoolingAndPressureAdvance() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "filament_settings_id":["A","B"],
            "enable_pressure_advance":["1","0"],"pressure_advance":["0.035","0.02"],
            "slow_down_layer_time":["8","12"],"close_fan_the_first_x_layers":["1","3"]
        }"""), SliceOptions())
        val slots = result.options.resolvedFilamentSlots()
        assertTrue(slots[0].pressureAdvanceEnabled)
        assertFalse(slots[1].pressureAdvanceEnabled)
        assertEquals(0.035f, slots[0].pressureAdvance)
        assertEquals(12f, slots[1].slowDownLayerTime)
        assertEquals(3, slots[1].closeFanFirstLayers)
    }

    @Test fun retainsPrinterGcodeVerbatimIncludingExplicitEmptyCommands() {
        val start = "G28\nM104 S[first_layer_temperature]\n; original comment"
        val result = convertOrcaProjectSettings(JSONObject()
            .put("machine_start_gcode", start)
            .put("machine_end_gcode", "")
            .put("before_layer_change_gcode", "; before [layer_num]")
            .put("layer_change_gcode", "; after [layer_z]")
            .put("change_filament_gcode", "T[next_extruder]"), SliceOptions())
        assertEquals(start, result.options.printerProfile.machineStartGcode)
        assertEquals("", result.options.printerProfile.machineEndGcode)
        assertEquals("; before [layer_num]", result.options.printerProfile.beforeLayerChangeGcode)
        assertEquals("T[next_extruder]", result.options.printerProfile.changeFilamentGcode)
    }

    @Test fun importedFirmwareReachesProjectOptions() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "gcode_flavor":"klipper", "printer_structure":"corexy"
        }"""), SliceOptions())
        assertEquals("klipper", result.options.gcodeFlavor)
        assertEquals("corexy", result.options.printerStructure)
        assertTrue("gcode_flavor" in result.appliedKeys)
    }

    @Test fun unsupportedFirmwareIsNotSilentlyReplaced() {
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaProjectSettings(JSONObject().put("gcode_flavor", "unknown"), SliceOptions())
        }
    }

    @Test fun restoresExplicitPurgeMultiplierWithoutScalingMatrixTwice() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "filament_settings_id":["A","B"],
            "flush_volumes_matrix":[0,100,200,0],"flush_multiplier":"0.65"
        }"""), SliceOptions())
        assertTrue(result.options.multiMaterial.flushMultiplierOverrideEnabled)
        assertEquals(0.65f, result.options.multiMaterial.flushMultiplier)
        assertEquals(listOf(0f, 100f, 200f, 0f), result.options.multiMaterial.purgeVolumes)
    }

    @Test fun rejectsInvalidPurgeMultiplier() {
        for (value in listOf("-1", "11", "NaN")) {
            assertThrows(IllegalArgumentException::class.java) {
                convertOrcaProjectSettings(JSONObject().put("flush_multiplier", value), SliceOptions())
            }
        }
    }

    @Test fun preservesDirectionalPurgeMatrix() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "filament_settings_id":["A","B"],"flush_volumes_matrix":[0,110,230,0]
        }"""), SliceOptions())
        assertEquals(listOf(0f, 110f, 230f, 0f), result.options.multiMaterial.resolvedPurgeVolumes(2))
        assertTrue("flush_volumes_matrix" in result.appliedKeys)
        assertFalse("flush_volumes_matrix" in result.unsupportedKeys)
    }

    @Test fun rejectsMalformedPurgeMatrices() {
        for (matrix in listOf("[0,110,0]", "[0,-1,230,0]", "[1,110,230,0]", "[0,1001,230,0]")) {
            assertThrows(IllegalArgumentException::class.java) {
                convertOrcaProjectSettings(JSONObject("""{
                    "filament_settings_id":["A","B"],"flush_volumes_matrix":$matrix
                }"""), SliceOptions())
            }
        }
    }

    @Test fun retainsPurgeTowerAndFlushDestinationSettings() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "enable_prime_tower":"1", "prime_tower_width":"45",
            "prime_volume":"36", "flush_into_infill":"1", "flush_into_support":"0",
            "wipe_tower_max_purge_speed":"70", "wipe_tower_filament":"2",
            "filament_settings_id":["A","B"]
        }"""), SliceOptions())
        assertTrue(result.options.wipeTowerEnabled)
        assertEquals(45f, result.options.wipeTowerWidth)
        assertEquals(36f, result.options.multiMaterial.primeVolume)
        assertTrue(result.options.multiMaterial.flushIntoInfill)
        assertFalse(result.options.multiMaterial.flushIntoSupport)
        assertEquals(70f, result.options.multiMaterial.wipeTowerMaxPurgeSpeed)
        assertEquals(2, result.options.featureFilaments.wipeTowerFilament)
    }

    @Test fun restoresIndependentSupportAndInterfaceMaterials() {
        val result = convertOrcaProjectSettings(JSONObject("""{
            "filament_settings_id":["Model PLA","Support PETG"],
            "support_filament":"1", "support_interface_filament":"2"
        }"""), SliceOptions())
        assertEquals(1, result.options.supportFilament)
        assertEquals(2, result.options.supportInterfaceFilament)
    }

    @Test fun refusesMissingSupportMaterialRatherThanClampingItsIndex() {
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaProjectSettings(JSONObject("""{
                "filament_settings_id":["Only one"],"support_interface_filament":"2"
            }"""), SliceOptions())
        }
    }

    @Test fun restoresMappedSettingsWithoutCollapsingMaterials() {
        val raw = JSONObject("""{
            "layer_height":"0.235", "filament_settings_id":["A","B"],
            "nozzle_temperature":["210","240"], "filament_colour":["#FFFF00","#00FFFF"],
            "unknown_feature":"1"
        }""")
        val result = convertOrcaProjectSettings(raw, SliceOptions())
        assertEquals(0.235f, result.options.layerHeight)
        assertEquals(listOf(210, 240), result.options.resolvedFilamentSlots().map { it.nozzleTemp })
        assertEquals(listOf(0xffff00, 0x00ffff), result.options.resolvedFilamentColors())
        assertEquals(setOf("unknown_feature"), result.unsupportedKeys)
        assertTrue(result.defaultedKeys.contains("printer:printable_area"))
    }

    @Test fun refusesToSilentlyDropMaterialsBeyondPrinterCapacity() {
        val baseline = SliceOptions().copy(printerProfile = PrinterProfile.U1_04.copy(extruderCount = 1))
        assertThrows(IllegalArgumentException::class.java) {
            convertOrcaProjectSettings(JSONObject("""{"filament_settings_id":["A","B"]}"""), baseline)
        }
    }
}

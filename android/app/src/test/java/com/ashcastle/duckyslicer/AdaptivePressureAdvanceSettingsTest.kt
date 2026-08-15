package com.ashcastle.duckyslicer

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptivePressureAdvanceSettingsTest {
    private val model = "0.060,2,1000\n0.040,12,1000\n0.070,2,5000\n0.050,12,5000"
    private val settings = AdaptivePressureAdvanceSettings(
        enabled = true,
        model = model,
        overhangs = true,
        bridge = 0.065f,
    )
    private val filament = FilamentProfile.GENERIC_PLA.copy(
        pressureAdvanceEnabled = true,
        pressureAdvance = 0.04f,
        adaptivePressureAdvance = settings,
    )

    @Test
    fun modelValidationMatchesTheEngineContract() {
        assertTrue(adaptivePressureAdvanceModelIsValid(model))
        assertTrue(
            adaptivePressureAdvanceModelIsValid(
                "0.060,2,1000\n0.070,2,5000\n0.040,12,1000\n0.050,12,5000",
            ),
        )
        listOf(
            "0.060,2,1000",
            "0.060,2,1000\n0.040,2,1000",
            "0.060,12,1000\n0.040,2,1000",
            "0.060,2,1000\n0.040,12,5000",
            "NaN,2,1000\n0.040,12,1000",
            "0.060,0,1000\n0.040,12,1000",
            "0.060,2,0\n0.040,12,0",
            "0.060,2\n0.040,12",
        ).forEach { invalid -> assertFalse(adaptivePressureAdvanceModelIsValid(invalid)) }
        assertFalse(
            adaptivePressureAdvanceModelIsValid(
                "0.060,2,1000\n0.040,12,1000" + " ".repeat(MAX_ADAPTIVE_PRESSURE_ADVANCE_MODEL_BYTES),
            ),
        )
    }

    @Test
    fun filamentValidationRequiresRegularPressureAdvanceAndAValidModel() {
        assertTrue(ProfileValidation.filament(filament))
        assertFalse(ProfileValidation.filament(filament.copy(pressureAdvanceEnabled = false)))
        assertFalse(
            ProfileValidation.filament(
                filament.copy(
                    adaptivePressureAdvance = settings.copy(model = "0.060,2,1000"),
                ),
            ),
        )
        assertFalse(
            ProfileValidation.filament(
                filament.copy(adaptivePressureAdvance = settings.copy(bridge = 2.001f)),
            ),
        )
    }

    @Test
    fun settingsRoundTripThroughProfilesProjectsAndNativeArrays() {
        val secondary = filament.copy(
            id = "adaptive-secondary",
            name = "Adaptive secondary",
            adaptivePressureAdvance = settings.copy(overhangs = false, bridge = 0.08f),
        )
        val options = SliceOptions()
            .selectFilament(filament)
            .copy(
                pressureAdvanceEnabled = true,
                pressureAdvance = 0.04f,
                filamentSlots = listOf(filament, secondary),
            )

        val profileJson = filament.toProfileJson()
        assertEquals(settings, requireNotNull(profileJson.toFilamentProfileOrNull()).adaptivePressureAdvance)

        val projectJson = options.toProjectJson()
        assertEquals(91, projectJson.getInt("formatVersion"))
        val restored = requireNotNull(projectJson.toProjectSliceOptionsOrNull())
        assertEquals(settings, restored.filamentSlots.first().adaptivePressureAdvance)
        assertEquals(0.08f, restored.filamentSlots[1].adaptivePressureAdvance.bridge)

        val native = restored.toNativeConfig()
        assertEquals(listOf(1, 1), native.filamentAdaptivePressureAdvance.toList())
        assertEquals(listOf(model, model), native.filamentAdaptivePressureAdvanceModels.toList())
        assertEquals(listOf(1, 0), native.filamentAdaptivePressureAdvanceOverhangs.toList())
        assertEquals(listOf(0.065f, 0.08f), native.filamentAdaptivePressureAdvanceBridges.toList())
    }

    @Test
    fun userProfilePersistenceAndLegacyDefaultsAreStable() {
        val directory = Files.createTempDirectory("duckyslicer-adaptive-pa-").toFile()
        val file = directory.resolve("profiles.json")
        try {
            val options = SliceOptions()
                .selectFilament(filament)
                .copy(pressureAdvanceEnabled = true, pressureAdvance = 0.04f)
            val saved = ProfileStore(file).saveFilament("Adaptive PLA", options)
            val restored = ProfileStore(file).load().filaments.single { it.id == saved.id }
            assertEquals(settings, restored.adaptivePressureAdvance)

            val legacy = JSONObject(filament.toProfileJson().toString()).apply {
                remove("adaptivePressureAdvanceEnabled")
                remove("adaptivePressureAdvanceModel")
                remove("adaptivePressureAdvanceOverhangs")
                remove("adaptivePressureAdvanceBridge")
            }
            assertEquals(
                AdaptivePressureAdvanceSettings(),
                requireNotNull(legacy.toFilamentProfileOrNull()).adaptivePressureAdvance,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsafeAdaptiveModelCannotCrossTheNativeBoundary() {
        val options = SliceOptions()
            .selectFilament(
                filament.copy(
                    adaptivePressureAdvance = settings.copy(model = "0.060,2,1000"),
                ),
            )
            .copy(pressureAdvanceEnabled = true, pressureAdvance = 0.04f)

        assertThrows(IllegalArgumentException::class.java) { options.toNativeConfig() }
    }
}

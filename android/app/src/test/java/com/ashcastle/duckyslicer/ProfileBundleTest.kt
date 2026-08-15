package com.ashcastle.duckyslicer

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBundleTest {
    @Test
    fun bundleRoundTripCarriesOnlyUserProfilesAndRepeatImportIsStable() {
        val sourceDirectory = Files.createTempDirectory("duckyslicer-profile-source-").toFile()
        val destinationDirectory = Files.createTempDirectory("duckyslicer-profile-destination-").toFile()
        try {
            val source = ProfileStore(sourceDirectory.resolve("user_profiles.json"))
            source.savePrinter(
                "Portable printer",
                SliceOptions().selectPrinter(
                    PrinterProfile.CUSTOM_CARTESIAN.copy(
                        auxiliaryFan = true,
                        minLayerHeight = 0.09f,
                        maxLayerHeight = 0.31f,
                        extruderOffsetsX = listOf(0f, 12.5f),
                        extruderOffsetsY = listOf(0f, -3.25f),
                        beforeLayerChangeGcode = "; BUNDLE_BEFORE_LAYER",
                        layerChangeGcode = "; BUNDLE_AFTER_LAYER",
                        changeFilamentGcode = "T[next_extruder] ; BUNDLE_TOOL_CHANGE",
                        printingByObjectGcode = "; BUNDLE_BETWEEN_OBJECTS",
                        useRelativeEDistances = false,
                        emitMachineLimitsToGcode = false,
                        manualFilamentChange = true,
                        disableM73 = true,
                        coolingTubeRetraction = 73.5f,
                        coolingTubeLength = 11f,
                        parkingPosRetraction = 80f,
                        extraLoadingMove = -3.5f,
                        enableFilamentRamming = false,
                        purgeInPrimeTower = false,
                        highCurrentOnFilamentSwap = true,
                        toolChangeRetractLengths = listOf(1.2f, 2.3f),
                        toolChangeRetractRestartExtras = listOf(-0.1f, 0.2f),
                    ),
                ),
            )
            source.saveFilament(
                "Portable filament",
                SliceOptions().selectFilament(
                    FilamentProfile.GENERIC_PLA.copy(
                        filamentStartGcode = "M117 BUNDLE_FILAMENT_START",
                        filamentEndGcode = "M117 BUNDLE_FILAMENT_END",
                        minimalPurgeOnWipeTower = 35f,
                        additionalCoolingFanSpeed = 70,
                        fanCoolingLayerTime = 42f,
                        slowDownForLayerCooling = false,
                        keepFanAlwaysOn = true,
                        dontSlowDownOuterWall = true,
                        overhangFanThreshold = "25%",
                        internalBridgeFanSpeed = 45,
                        supportInterfaceFanSpeed = 85,
                        bedTemp = 71,
                        firstLayerBedTemp = 72,
                        texturedPlateTemp = 53,
                        firstLayerTexturedPlateTemp = 54,
                        engineeringPlateTemp = 61,
                        firstLayerEngineeringPlateTemp = 62,
                        coolPlateTemp = 31,
                        firstLayerCoolPlateTemp = 32,
                        texturedCoolPlateTemp = 33,
                        firstLayerTexturedCoolPlateTemp = 34,
                        superTackPlateTemp = 35,
                        firstLayerSuperTackPlateTemp = 36,
                        graphicEffectPlateTemp = 55,
                        firstLayerGraphicEffectPlateTemp = 56,
                        shrinkageXyPercent = 99.2f,
                        shrinkageZPercent = 99.18f,
                    ),
                ),
            )
            source.saveSlicing("Portable slicing", SliceOptions())

            val bytes = source.exportBundle()
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            assertEquals(
                setOf("type", "bundleVersion", "profileSchemaVersion", "profiles"),
                root.keys().asSequence().toSet(),
            )
            assertEquals(
                setOf("printers", "filaments", "slicing"),
                root.getJSONObject("profiles").keys().asSequence().toSet(),
            )
            assertFalse(root.toString().contains("credentialCiphertext"))
            assertFalse(root.toString().contains("baseUrl"))

            val destinationFile = destinationDirectory.resolve("user_profiles.json")
            val destination = ProfileStore(destinationFile)
            val imported = destination.importBundle(bytes)
            assertEquals(3, imported.importedTotal)
            assertEquals(0, imported.skippedDuplicates)
            val catalog = destination.load()
            assertTrue(
                catalog.printers.any {
                        it.name == "Portable printer" && !it.builtIn && it.auxiliaryFan &&
                        it.minLayerHeight == 0.09f && it.maxLayerHeight == 0.31f &&
                        it.extruderOffsetsX == listOf(0f, 12.5f) &&
                        it.extruderOffsetsY == listOf(0f, -3.25f) &&
                        it.beforeLayerChangeGcode == "; BUNDLE_BEFORE_LAYER" &&
                        it.layerChangeGcode == "; BUNDLE_AFTER_LAYER" &&
                        it.changeFilamentGcode == "T[next_extruder] ; BUNDLE_TOOL_CHANGE" &&
                        it.printingByObjectGcode == "; BUNDLE_BETWEEN_OBJECTS" &&
                        !it.useRelativeEDistances && !it.emitMachineLimitsToGcode &&
                        it.manualFilamentChange && it.disableM73 &&
                        it.coolingTubeRetraction == 73.5f && it.coolingTubeLength == 11f &&
                        it.parkingPosRetraction == 80f && it.extraLoadingMove == -3.5f &&
                        !it.enableFilamentRamming && !it.purgeInPrimeTower &&
                        it.highCurrentOnFilamentSwap &&
                        it.toolChangeRetractLengths == listOf(1.2f, 2.3f) &&
                        it.toolChangeRetractRestartExtras == listOf(-0.1f, 0.2f)
                },
            )
            val importedFilament = catalog.filaments.single {
                it.name == "Portable filament" && !it.builtIn
            }
            assertEquals("M117 BUNDLE_FILAMENT_START", importedFilament.filamentStartGcode)
            assertEquals("M117 BUNDLE_FILAMENT_END", importedFilament.filamentEndGcode)
            assertEquals(35f, importedFilament.minimalPurgeOnWipeTower)
            assertEquals(70, importedFilament.additionalCoolingFanSpeed)
            assertEquals(42f, importedFilament.fanCoolingLayerTime)
            assertFalse(importedFilament.slowDownForLayerCooling)
            assertTrue(importedFilament.keepFanAlwaysOn)
            assertTrue(importedFilament.dontSlowDownOuterWall)
            assertEquals("25%", importedFilament.overhangFanThreshold)
            assertEquals(45, importedFilament.internalBridgeFanSpeed)
            assertEquals(85, importedFilament.supportInterfaceFanSpeed)
            assertEquals(71, importedFilament.bedTemp)
            assertEquals(72, importedFilament.firstLayerBedTemp)
            assertEquals(53, importedFilament.texturedPlateTemp)
            assertEquals(54, importedFilament.firstLayerTexturedPlateTemp)
            assertEquals(61, importedFilament.engineeringPlateTemp)
            assertEquals(62, importedFilament.firstLayerEngineeringPlateTemp)
            assertEquals(31, importedFilament.coolPlateTemp)
            assertEquals(32, importedFilament.firstLayerCoolPlateTemp)
            assertEquals(33, importedFilament.texturedCoolPlateTemp)
            assertEquals(34, importedFilament.firstLayerTexturedCoolPlateTemp)
            assertEquals(35, importedFilament.superTackPlateTemp)
            assertEquals(36, importedFilament.firstLayerSuperTackPlateTemp)
            assertEquals(55, importedFilament.graphicEffectPlateTemp)
            assertEquals(56, importedFilament.firstLayerGraphicEffectPlateTemp)
            assertEquals(99.2f, importedFilament.shrinkageXyPercent)
            assertEquals(99.18f, importedFilament.shrinkageZPercent)
            assertTrue(catalog.slicing.any { it.name == "Portable slicing" && !it.builtIn })

            val firstGeneration = destinationFile.readBytes()
            val repeated = destination.importBundle(bytes)
            assertEquals(0, repeated.importedTotal)
            assertEquals(3, repeated.skippedDuplicates)
            assertTrue(firstGeneration.contentEquals(destinationFile.readBytes()))
        } finally {
            sourceDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    @Test
    fun changedProfileWithCollidingIdentityReceivesANewUserIdentity() {
        val directory = Files.createTempDirectory("duckyslicer-profile-collision-").toFile()
        val sourceDirectory = Files.createTempDirectory("duckyslicer-profile-collision-source-").toFile()
        try {
            val source = ProfileStore(sourceDirectory.resolve("user_profiles.json"))
            source.savePrinter("Original portable printer", SliceOptions())
            source.saveFilament("Original portable filament", SliceOptions())
            source.saveSlicing("Original portable slicing", SliceOptions())
            val originalRoot = JSONObject(source.exportBundle().toString(Charsets.UTF_8))
            val originalProfiles = originalRoot.getJSONObject("profiles")
            val originalPrinterId = originalProfiles.getJSONArray("printers")
                .getJSONObject(0).getString("id")
            originalProfiles.getJSONArray("filaments").getJSONObject(0)
                .put("compatiblePrinters", org.json.JSONArray().put(originalPrinterId))
            originalProfiles.getJSONArray("slicing").getJSONObject(0)
                .put("compatiblePrinters", org.json.JSONArray().put(originalPrinterId))
            val originalBundle = originalRoot.toString().toByteArray(Charsets.UTF_8)

            val destination = ProfileStore(directory.resolve("user_profiles.json"))
            assertEquals(3, destination.importBundle(originalBundle).importedTotal)
            val first = destination.load().printers.single { it.name == "Original portable printer" }

            val changedRoot = JSONObject(originalBundle.toString(Charsets.UTF_8))
            val changedProfiles = changedRoot.getJSONObject("profiles")
            changedProfiles.getJSONArray("printers").getJSONObject(0)
                .put("name", "Changed portable printer")
            changedProfiles.getJSONArray("filaments").getJSONObject(0)
                .put("name", "Changed portable filament")
            changedProfiles.getJSONArray("slicing").getJSONObject(0)
                .put("name", "Changed portable slicing")
            val changed = changedRoot.toString().toByteArray(Charsets.UTF_8)
            assertEquals(3, destination.importBundle(changed).importedTotal)

            val catalog = destination.load()
            val second = catalog.printers.single { it.name == "Changed portable printer" }
            assertNotEquals(first.id, second.id)
            assertTrue(first.id.startsWith("user-"))
            assertTrue(second.id.startsWith("user-"))
            assertEquals(
                listOf(second.id),
                catalog.filaments.single { it.name == "Changed portable filament" }
                    .compatiblePrinters,
            )
            assertEquals(
                listOf(second.id),
                catalog.slicing.single { it.name == "Changed portable slicing" }
                    .compatiblePrinters,
            )
        } finally {
            directory.deleteRecursively()
            sourceDirectory.deleteRecursively()
        }
    }

    @Test
    fun malformedOrFutureBundleNeverChangesSavedProfiles() {
        val directory = Files.createTempDirectory("duckyslicer-profile-atomic-").toFile()
        val sourceDirectory = Files.createTempDirectory("duckyslicer-profile-atomic-source-").toFile()
        try {
            val destinationFile = directory.resolve("user_profiles.json")
            val destination = ProfileStore(destinationFile)
            destination.savePrinter("Keep me", SliceOptions())
            val original = destinationFile.readBytes()

            val source = ProfileStore(sourceDirectory.resolve("user_profiles.json"))
            source.saveFilament("Incoming", SliceOptions())
            val valid = JSONObject(source.exportBundle().toString(Charsets.UTF_8))
            val unsafe = JSONObject(valid.toString()).also { root ->
                root.getJSONObject("profiles").getJSONArray("filaments")
                    .getJSONObject(0).put("nozzleTemp", 9999)
            }
            val invalidBundles = listOf(
                "{broken".toByteArray(),
                JSONObject(valid.toString()).put("bundleVersion", 999).toString().toByteArray(),
                JSONObject(valid.toString()).put("credential", "must-not-be-accepted")
                    .toString().toByteArray(),
                unsafe.toString().toByteArray(),
            )
            invalidBundles.forEach { bytes ->
                assertThrows(Exception::class.java) { destination.importBundle(bytes) }
                assertTrue(original.contentEquals(destinationFile.readBytes()))
            }

            assertThrows(IllegalStateException::class.java) {
                destination.importBundle(source.exportBundle()) {
                    throw IllegalStateException("canceled-before-commit")
                }
            }
            assertTrue(original.contentEquals(destinationFile.readBytes()))
        } finally {
            directory.deleteRecursively()
            sourceDirectory.deleteRecursively()
        }
    }

    @Test
    fun unknownPerProfileFieldsAreDiscardedBeforeTheAtomicWrite() {
        val sourceDirectory = Files.createTempDirectory("duckyslicer-profile-canonical-source-").toFile()
        val destinationDirectory = Files.createTempDirectory("duckyslicer-profile-canonical-destination-").toFile()
        try {
            val source = ProfileStore(sourceDirectory.resolve("user_profiles.json"))
            source.saveFilament("Canonical filament", SliceOptions())
            val bundle = JSONObject(source.exportBundle().toString(Charsets.UTF_8))
            bundle.getJSONObject("profiles").getJSONArray("filaments").getJSONObject(0)
                .put("credentialCiphertext", "must be discarded")

            val destinationFile = destinationDirectory.resolve("user_profiles.json")
            val result = ProfileStore(destinationFile).importBundle(
                bundle.toString().toByteArray(Charsets.UTF_8),
            )

            assertEquals(1, result.importedTotal)
            assertFalse(destinationFile.readText().contains("credentialCiphertext"))
        } finally {
            sourceDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    @Test
    fun providerInputIsBoundedAndHonorsCancellation() {
        val oversized = ByteArray(MAX_PROFILE_BUNDLE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            readProfileBundleBytes(
                ByteArrayInputStream(oversized),
                DocumentTransferCancellation(),
            )
        }

        val cancellation = DocumentTransferCancellation()
        assertTrue(cancellation.cancel())
        assertThrows(DocumentTransferCancelledException::class.java) {
            readProfileBundleBytes(ByteArrayInputStream("{}".toByteArray()), cancellation)
        }
    }

    @Test
    fun unreadableSavedProfilesCannotBeMisrepresentedAsAnEmptyExport() {
        val directory = Files.createTempDirectory("duckyslicer-profile-export-block-").toFile()
        val file = directory.resolve("user_profiles.json")
        try {
            file.writeText("{broken")
            assertThrows(IllegalStateException::class.java) { ProfileStore(file).exportBundle() }
            assertEquals("{broken", file.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}

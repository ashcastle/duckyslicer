package com.ashcastle.duckyslicer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectStoreTest {
    @Test
    fun emptyProjectWithNullSelectionRemainsWritableAndReadable() = withStore { _, store ->
        store.save(ProjectSnapshot(), SliceOptions())

        val restored = store.loadProject()

        assertFalse(restored.storageUnavailable)
        assertTrue(restored.snapshot.objects.isEmpty())
        assertEquals(null, restored.snapshot.selectedObjectId)
        assertTrue(restored.sliceOptions != null)
    }

    @Test
    fun projectRoundTripsAndPrunesOnlyUnreferencedPrivateModels() = withStore { root, store ->
        val modelFile = store.createModelDestination("오리 모형.stl").apply { writeText("solid duck") }
        val orphan = store.createModelDestination("old.stl").apply { writeText("solid old") }
        val outside = File(root.parentFile, "outside-${root.name}.stl").apply { writeText("keep") }
        val snapshot = ProjectSnapshot(
            objects = listOf(
                ProjectObject(
                    id = "duck",
                    model = inspectedModel(modelFile).copy(fileName = "오리 모형.stl"),
                    transform = ModelTransform(
                        offsetXmm = 12f,
                        offsetYmm = -7f,
                        offsetZmm = 9f,
                        rotationZdeg = 45f,
                        scale = 1.25f,
                        scaleY = 1.5f,
                        scaleZ = 0.8f,
                        mirrorX = true,
                        mirrorZ = true,
                    ),
                    supportPaint = SupportPaint().paint(0, SupportPaintState.ENFORCE),
                    seamPaint = SeamPaint().paint(0, SeamPaintState.BLOCK),
                    multiColorPaint = MultiColorPaint().paint(0, 0),
                    variableLayerHeights = VariableLayerHeights(
                        listOf(VariableLayerRange(0.25f, 0.75f, 0.08f)),
                    ),
                    processOverrides = ObjectProcessOverrides(
                        wallLoops = 5,
                        topShellLayers = 7,
                        sparseInfillSpeedMmS = 75f,
                        supportEnabled = false,
                    ),
                    brimPoints = BrimPoints(
                        listOf(BrimPoint(2f, 3f, -0.0001f, 4f)),
                    ),
                ),
            ),
            selectedObjectId = "duck",
        )

        store.save(snapshot)
        val restored = store.load()

        assertEquals("duck", restored.selectedObjectId)
        assertEquals("오리 모형.stl", restored.selectedObject!!.model.fileName)
        assertEquals(modelFile.canonicalPath, restored.selectedObject!!.model.localPath)
        assertEquals(snapshot.selectedObject!!.transform, restored.selectedObject!!.transform)
        assertEquals(snapshot.selectedObject!!.supportPaint, restored.selectedObject!!.supportPaint)
        assertEquals(snapshot.selectedObject!!.seamPaint, restored.selectedObject!!.seamPaint)
        assertEquals(snapshot.selectedObject!!.multiColorPaint, restored.selectedObject!!.multiColorPaint)
        assertEquals(
            snapshot.selectedObject!!.variableLayerHeights,
            restored.selectedObject!!.variableLayerHeights,
        )
        assertEquals(snapshot.selectedObject!!.processOverrides, restored.selectedObject!!.processOverrides)
        assertEquals(snapshot.selectedObject!!.brimPoints, restored.selectedObject!!.brimPoints)
        assertTrue(modelFile.isFile)
        assertFalse(orphan.exists())
        assertTrue(outside.isFile)
        outside.delete()
    }

    @Test
    fun corruptedPrimaryRestoresBackupWithoutDeletingModels() = withStore { root, store ->
        val modelFile = store.createModelDestination("part.stl").apply { writeText("solid part") }
        val first = ProjectSnapshot(
            listOf(ProjectObject("part", inspectedModel(modelFile))),
            "part",
        )
        store.save(first)
        store.save(first.copy(selectedObjectId = null))
        File(root, "current_project.json").writeText("{broken")

        val restored = store.load()

        assertEquals("part", restored.selectedObjectId)
        assertEquals(1, restored.objects.size)
        assertTrue(modelFile.isFile)
        assertTrue(File(root, "current_project.json").readText().startsWith("{"))
        assertFalse(File(root, "current_project.json").readText().contains("broken"))
    }

    @Test
    fun currentSchemaPersistsStableVolumeAndSchemaOneMigratesDeterministically() = withStore { root, store ->
        val modelFile = store.createModelDestination("settings.stl").apply { writeText("solid part") }
        val options = multiFilamentSettingsFixture()
        val snapshot = ProjectSnapshot(
            listOf(ProjectObject("settings", inspectedModel(modelFile), filamentSlot = 1)),
            "settings",
        )
        store.save(snapshot, options)

        val restored = ProjectStore(root, ::inspectedModel).loadProject()

        val persisted = JSONObject(File(root, "current_project.json").readText())
        assertEquals(51, persisted.getInt("schemaVersion"))
        assertEquals(
            setOf("schemaVersion", "selectedPlateId", "plates"),
            persisted.keys().asSequence().toSet(),
        )
        val persistedPlate = persisted.getJSONArray("plates").getJSONObject(0)
        assertEquals(
            setOf("id", "selectedObjectId", "sliceOptions", "objects"),
            persistedPlate.keys().asSequence().toSet(),
        )
        assertEquals(legacyProjectPlateId(), persistedPlate.getString("id"))
        val persistedObject = persistedPlate.getJSONArray("objects").getJSONObject(0)
        assertEquals(
            setOf(
                "id", "transform", "variableLayerHeights", "processOverrides",
                "brimPoints", "volumes",
            ),
            persistedObject.keys().asSequence().toSet(),
        )
        val persistedVolume = persistedObject.getJSONArray("volumes").getJSONObject(0)
        assertEquals(
            setOf(
                "id", "displayName", "modelFile", "supportPaint", "seamPaint",
                "multiColorPaint", "filamentSlot", "role", "config",
            ),
            persistedVolume.keys().asSequence().toSet(),
        )
        assertEquals(legacyProjectVolumeId("settings"), persistedVolume.getString("id"))
        assertEquals(snapshot.selectedObjectId, restored.snapshot.selectedObjectId)
        assertEquals(snapshot.objects.single().id, restored.snapshot.objects.single().id)
        assertEquals(
            snapshot.objects.single().singleVolume.id,
            restored.snapshot.objects.single().singleVolume.id,
        )
        assertEquals(snapshot.objects.single().transform, restored.snapshot.objects.single().transform)
        assertEquals(1, restored.snapshot.objects.single().filamentSlot)
        assertEquals(
            options.toProjectJson().toString(),
            restored.sliceOptions?.toProjectJson()?.toString(),
        )
        assertEquals(
            listOf("M117 PRIMARY_START", "M117 SECONDARY_START"),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::filamentStartGcode),
        )
        assertEquals(
            listOf("M117 PRIMARY_END", "M117 SECONDARY_END"),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::filamentEndGcode),
        )
        assertEquals(
            listOf(2.85f, 2.85f),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::diameter),
        )
        assertEquals(
            listOf(1.07f, 1.32f),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::density),
        )
        assertEquals(
            listOf(42.5f, 75f),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::costPerKilogram),
        )
        assertEquals(
            listOf(9f, 35f),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::minimalPurgeOnWipeTower),
        )
        assertEquals(
            listOf(40, 70),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::additionalCoolingFanSpeed),
        )
        assertEquals(
            listOf(42f, 91f),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::fanCoolingLayerTime),
        )
        assertEquals(
            listOf("25%", "75%"),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::overhangFanThreshold),
        )
        assertEquals(
            listOf(45, -1),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::internalBridgeFanSpeed),
        )
        assertEquals(true, restored.sliceOptions?.printerProfile?.auxiliaryFan)
        assertEquals(0.12f, restored.sliceOptions?.printerProfile?.minLayerHeight)
        assertEquals(0.42f, restored.sliceOptions?.printerProfile?.maxLayerHeight)
        assertEquals(
            listOf(0f, 10.5f),
            restored.sliceOptions?.printerProfile?.extruderOffsetsX,
        )
        assertEquals(
            listOf(0f, -2.5f),
            restored.sliceOptions?.printerProfile?.extruderOffsetsY,
        )
        assertEquals(
            "; FIXTURE_BEFORE_LAYER",
            restored.sliceOptions?.printerProfile?.beforeLayerChangeGcode,
        )
        assertEquals(
            "; FIXTURE_AFTER_LAYER",
            restored.sliceOptions?.printerProfile?.layerChangeGcode,
        )
        assertEquals(
            "T[next_extruder] ; FIXTURE_TOOL_CHANGE",
            restored.sliceOptions?.printerProfile?.changeFilamentGcode,
        )
        assertEquals(
            "; FIXTURE_BETWEEN_OBJECTS",
            restored.sliceOptions?.printerProfile?.printingByObjectGcode,
        )
        assertEquals(false, restored.sliceOptions?.printerProfile?.useRelativeEDistances)
        assertEquals(false, restored.sliceOptions?.printerProfile?.emitMachineLimitsToGcode)
        assertEquals(true, restored.sliceOptions?.printerProfile?.manualFilamentChange)
        assertEquals(true, restored.sliceOptions?.printerProfile?.disableM73)
        assertEquals(73.5f, restored.sliceOptions?.printerProfile?.coolingTubeRetraction)
        assertEquals(11f, restored.sliceOptions?.printerProfile?.coolingTubeLength)
        assertEquals(80f, restored.sliceOptions?.printerProfile?.parkingPosRetraction)
        assertEquals(-3.5f, restored.sliceOptions?.printerProfile?.extraLoadingMove)
        assertEquals(false, restored.sliceOptions?.printerProfile?.enableFilamentRamming)
        assertEquals(false, restored.sliceOptions?.printerProfile?.purgeInPrimeTower)
        assertEquals(true, restored.sliceOptions?.printerProfile?.highCurrentOnFilamentSwap)
        assertEquals(
            listOf(1.4f, 2.6f),
            restored.sliceOptions?.printerProfile?.toolChangeRetractLengths,
        )
        assertEquals(
            listOf(-0.2f, 0.3f),
            restored.sliceOptions?.printerProfile?.toolChangeRetractRestartExtras,
        )
        assertEquals(
            listOf(false, true),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::soluble),
        )
        assertEquals(
            listOf(false, true),
            restored.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::supportMaterial),
        )

        val legacyTransform = JSONObject(persistedObject.getJSONObject("transform").toString()).apply {
            remove("scaleY")
            remove("scaleZ")
        }
        val legacy = JSONObject()
            .put("schemaVersion", 1)
            .put("selectedObjectId", "settings")
            .put(
                "objects",
                JSONArray().put(
                    JSONObject()
                        .put("id", "settings")
                        .put("displayName", persistedVolume.getString("displayName"))
                        .put("modelFile", persistedVolume.getString("modelFile"))
                        .put("transform", legacyTransform)
                        .put("supportPaint", persistedVolume.getJSONArray("supportPaint")),
                ),
            )
        File(root, "current_project.json").writeText(legacy.toString())
        val migrated = ProjectStore(root, ::inspectedModel).loadProject()
        assertEquals("settings", migrated.snapshot.selectedObjectId)
        assertEquals(legacyProjectPlateId(), migrated.snapshot.selectedPlateId)
        assertEquals(
            SliceOptions().toProjectJson().toString(),
            migrated.activeSliceOptions.toProjectJson().toString(),
        )
        assertEquals(
            legacyProjectVolumeId("settings"),
            migrated.snapshot.selectedObject!!.singleVolume.id,
        )
        assertTrue(migrated.snapshot.selectedObject!!.seamPaint.facets.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.multiColorPaint.facets.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.variableLayerHeights.ranges.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.processOverrides.isEmpty)
        assertTrue(migrated.snapshot.selectedObject!!.brimPoints.points.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.transform.hasUniformScale())
    }

    @Test
    fun multiplePlatesAndTheirSettingsRoundTripWithoutSharingObjects() = withStore { root, store ->
        val firstModel = store.createModelDestination("plate-a.stl").apply {
            writeText("solid plate-a")
        }
        val secondModel = store.createModelDestination("plate-b.stl").apply {
            writeText("solid plate-b")
        }
        val orphan = store.createModelDestination("orphan.stl").apply { writeText("orphan") }
        val firstOptions = restoredSettingsFixture().copy(fillDensity = 0.11f)
        val secondOptions = restoredSettingsFixture().copy(fillDensity = 0.42f)
        val snapshot = ProjectSnapshot(
            selectedPlateId = "plate-b",
            plates = listOf(
                ProjectPlate(
                    id = "plate-a",
                    objects = listOf(ProjectObject("object-a", inspectedModel(firstModel))),
                    selectedObjectId = "object-a",
                ),
                ProjectPlate(
                    id = "plate-b",
                    objects = listOf(ProjectObject("object-b", inspectedModel(secondModel))),
                    selectedObjectId = "object-b",
                ),
            ),
        )

        store.save(snapshot, mapOf("plate-a" to firstOptions, "plate-b" to secondOptions))
        val restored = ProjectStore(root, ::inspectedModel).loadProject()

        assertEquals("plate-b", restored.snapshot.selectedPlateId)
        assertEquals(listOf("plate-a", "plate-b"), restored.snapshot.plates.map(ProjectPlate::id))
        assertEquals(listOf("object-a"), restored.snapshot.plates[0].objects.map(ProjectObject::id))
        assertEquals(listOf("object-b"), restored.snapshot.plates[1].objects.map(ProjectObject::id))
        assertEquals(
            firstOptions.toProjectJson().toString(),
            restored.plateOptions.getValue("plate-a").toProjectJson().toString(),
        )
        assertEquals(
            secondOptions.toProjectJson().toString(),
            restored.plateOptions.getValue("plate-b").toProjectJson().toString(),
        )
        assertEquals(
            secondOptions.toProjectJson().toString(),
            restored.activeSliceOptions.toProjectJson().toString(),
        )
        assertTrue(firstModel.isFile)
        assertTrue(secondModel.isFile)
        assertFalse(orphan.exists())
    }

    @Test
    fun pathTraversalAndOutsideModelsAreRejected() = withStore { root, store ->
        val outside = File(root.parentFile, "outside-${root.name}.stl").apply { writeText("solid outside") }
        val transform = JSONObject()
            .put("offsetXmm", 0).put("offsetYmm", 0)
            .put("rotationXdeg", 0).put("rotationYdeg", 0).put("rotationZdeg", 0)
            .put("scale", 1)
        File(root, "current_project.json").apply {
            check(parentFile?.isDirectory == true || parentFile?.mkdirs() == true)
            writeText(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("selectedObjectId", "escape")
                    .put(
                        "objects",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "escape")
                                .put("displayName", "outside.stl")
                                .put("modelFile", "../${outside.name}")
                                .put("transform", transform),
                        ),
                    )
                    .toString(),
            )
        }

        assertTrue(store.load().objects.isEmpty())
        assertTrue(outside.isFile)
        assertThrows(IllegalArgumentException::class.java) {
            store.save(
                ProjectSnapshot(
                    listOf(ProjectObject("outside", inspectedModel(outside))),
                    "outside",
                ),
            )
        }
        outside.delete()
    }

    @Test
    fun multiVolumeProjectPersistsAndArchivesAfterRendererAndSlicerIndexingIsEnabled() =
        withStore { root, store ->
            val first = store.createModelDestination("first.stl").apply { writeText("solid first") }
            val second = store.createModelDestination("second.stl").apply { writeText("solid second") }
            val snapshot = ProjectSnapshot(
                objects = listOf(
                    ProjectObject(
                        id = "compound",
                        volumes = listOf(
                            ProjectVolume("first-volume", inspectedModel(first)),
                            ProjectVolume("second-volume", inspectedModel(second)),
                        ),
                    ),
                ),
                selectedObjectId = "compound",
            )

            store.save(snapshot)
            val restored = store.load()
            assertEquals("compound", restored.selectedObjectId)
            assertEquals(
                listOf("first-volume", "second-volume"),
                restored.selectedObject!!.volumes.map(ProjectVolume::id),
            )
            assertEquals(
                listOf(first.canonicalPath, second.canonicalPath),
                restored.selectedObject!!.volumes.map { it.model.localPath },
            )

            val archive = ByteArrayOutputStream().also {
                store.exportArchive(snapshot, SliceOptions(), it)
            }.toByteArray()
            val destinationRoot = Files.createTempDirectory("ducky-multi-volume-import-").toFile()
            try {
                val imported = ProjectStore(destinationRoot, ::inspectedModel)
                    .importArchive(ByteArrayInputStream(archive))
                assertEquals(1, imported.snapshot.objects.size)
                assertEquals(
                    listOf("first-volume", "second-volume"),
                    imported.snapshot.selectedObject!!.volumes.map(ProjectVolume::id),
                )
                assertEquals(
                    listOf("solid first", "solid second"),
                    imported.snapshot.selectedObject!!.volumes.map {
                        File(it.model.localPath).readText()
                    },
                )
            } finally {
                destinationRoot.deleteRecursively()
            }
            assertTrue(File(root, ProjectStore.PROJECT_FILE).isFile)
        }

    @Test
    fun invalidMetadataPreservesRecoverablePrivateModelFiles() = withStore { root, store ->
        val modelFile = store.createModelDestination("recover-me.stl").apply { writeText("solid part") }
        File(root, "current_project.json").writeText(
            """{"schemaVersion":1,"selectedObjectId":"part","objects":[{"id":"part","displayName":"part.stl","modelFile":"${modelFile.name}","transform":{"offsetXmm":0,"offsetYmm":0,"rotationXdeg":0,"rotationYdeg":0,"rotationZdeg":0,"scale":0}}]}""",
        )

        assertTrue(store.load().objects.isEmpty())
        assertTrue(modelFile.isFile)

        File(root, "current_project.json").writeText("{broken")
        assertTrue(store.load().objects.isEmpty())
        assertTrue(modelFile.isFile)
    }

    @Test
    fun unreadablePrimaryAndBackupBlockAutosaveWithoutChangingEither() = withStore { root, store ->
        val primary = File(root, "current_project.json").apply {
            parentFile?.mkdirs()
            writeText("{broken-primary")
        }
        val backup = File(root, "current_project.json.bak").apply { writeText("{broken-backup") }
        val primaryBytes = primary.readBytes()
        val backupBytes = backup.readBytes()

        val restored = store.loadProject()

        assertTrue(restored.storageUnavailable)
        assertTrue(restored.snapshot.objects.isEmpty())
        assertThrows(IllegalStateException::class.java) {
            store.save(ProjectSnapshot())
        }
        assertTrue(primaryBytes.contentEquals(primary.readBytes()))
        assertTrue(backupBytes.contentEquals(backup.readBytes()))
    }

    @Test
    fun validProjectStaysVisibleButReadOnlyWhenBackupRefreshFails() = withStore { root, store ->
        val modelFile = store.createModelDestination("part.stl").apply { writeText("solid part") }
        val snapshot = ProjectSnapshot(
            objects = listOf(ProjectObject("part", inspectedModel(modelFile))),
            selectedObjectId = "part",
        )
        store.save(snapshot)
        val primary = File(root, "current_project.json")
        val primaryBytes = primary.readBytes()
        val backup = File(root, "current_project.json.bak")
        assertTrue(backup.mkdir())
        File(backup, "unexpected-entry").writeText("keep")

        val restored = store.loadProject()

        assertTrue(restored.storageUnavailable)
        assertEquals("part", restored.snapshot.selectedObjectId)
        assertEquals(modelFile.canonicalPath, restored.snapshot.selectedObject?.model?.localPath)
        assertThrows(IllegalStateException::class.java) {
            store.save(restored.snapshot)
        }
        assertTrue(primaryBytes.contentEquals(primary.readBytes()))
        assertTrue(File(backup, "unexpected-entry").isFile)
    }

    private fun withStore(block: (File, ProjectStore) -> Unit) {
        val root = Files.createTempDirectory("duckyslicer-project-store-").toFile()
        try {
            block(root, ProjectStore(root, ::inspectedModel))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun inspectedModel(file: File) = ModelInfo(
        fileName = file.name,
        triangles = 1,
        dimensions = listOf(1.0, 1.0, 1.0),
        localPath = file.canonicalPath,
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(1.0, 1.0, 1.0),
        previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
    )
}

internal fun multiFilamentSettingsFixture(): SliceOptions {
    val options = restoredSettingsFixture()
    val secondary = FilamentProfile.GENERIC_PLA.copy(
        compatiblePrinters = listOf(options.printerProfile.name),
        filamentStartGcode = "M117 SECONDARY_START",
        filamentEndGcode = "M117 SECONDARY_END",
        diameter = options.filamentDiameter,
        density = 1.32f,
        costPerKilogram = 75f,
        soluble = true,
        supportMaterial = true,
        minimalPurgeOnWipeTower = 35f,
        additionalCoolingFanSpeed = 70,
        fanCoolingLayerTime = 91f,
        slowDownForLayerCooling = true,
        keepFanAlwaysOn = false,
        dontSlowDownOuterWall = false,
        enableOverhangBridgeFan = false,
        overhangFanThreshold = "75%",
        internalBridgeFanSpeed = -1,
        supportInterfaceFanSpeed = 65,
    )
    return options.copy(filamentSlots = listOf(options.filamentProfile, secondary))
}

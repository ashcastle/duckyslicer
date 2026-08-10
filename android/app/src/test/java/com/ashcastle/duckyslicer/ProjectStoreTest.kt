package com.ashcastle.duckyslicer

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
    fun schemaEightRestoresAxisScalesAndSchemaOneRemainsReadable() = withStore { root, store ->
        val modelFile = store.createModelDestination("settings.stl").apply { writeText("solid part") }
        val options = multiFilamentSettingsFixture()
        val snapshot = ProjectSnapshot(
            listOf(ProjectObject("settings", inspectedModel(modelFile), filamentSlot = 1)),
            "settings",
        )
        store.save(snapshot, options)

        val restored = ProjectStore(root, ::inspectedModel).loadProject()

        assertEquals(8, JSONObject(File(root, "current_project.json").readText()).getInt("schemaVersion"))
        assertEquals(snapshot.selectedObjectId, restored.snapshot.selectedObjectId)
        assertEquals(snapshot.objects.single().id, restored.snapshot.objects.single().id)
        assertEquals(snapshot.objects.single().transform, restored.snapshot.objects.single().transform)
        assertEquals(1, restored.snapshot.objects.single().filamentSlot)
        assertEquals(
            options.toProjectJson().toString(),
            restored.sliceOptions?.toProjectJson()?.toString(),
        )

        val current = JSONObject(File(root, "current_project.json").readText())
        current.put("schemaVersion", 1).remove("sliceOptions")
        current.getJSONArray("objects").getJSONObject(0).apply {
            remove("filamentSlot")
            remove("seamPaint")
            remove("multiColorPaint")
            remove("variableLayerHeights")
            remove("processOverrides")
            getJSONObject("transform").apply {
                remove("scaleY")
                remove("scaleZ")
            }
        }
        File(root, "current_project.json").writeText(current.toString())
        val migrated = ProjectStore(root, ::inspectedModel).loadProject()
        assertEquals("settings", migrated.snapshot.selectedObjectId)
        assertEquals(null, migrated.sliceOptions)
        assertTrue(migrated.snapshot.selectedObject!!.seamPaint.facets.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.multiColorPaint.facets.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.variableLayerHeights.ranges.isEmpty())
        assertTrue(migrated.snapshot.selectedObject!!.processOverrides.isEmpty)
        assertTrue(migrated.snapshot.selectedObject!!.transform.hasUniformScale())
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
    )
    return options.copy(filamentSlots = listOf(options.filamentProfile, secondary))
}

package com.ashcastle.duckyslicer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectArchiveTest {
    @Test
    fun startupRecoveryRemovesOnlyExactAbandonedArchiveDirectories() {
        val root = Files.createTempDirectory("ducky-project-recovery-").toFile()
        try {
            val abandoned = File(root, ".archive-${UUID.randomUUID()}").apply {
                mkdirs()
                File(this, "partial.stl").writeText("partial")
            }
            val unrelated = File(root, ".archive-not-a-request").apply { mkdirs() }
            val lookalikeFile = File(root, ".archive-${UUID.randomUUID()}").apply {
                writeText("keep")
            }

            assertEquals(1, ProjectStore.recoverAbandonedArchiveStaging(root))
            assertFalse(abandoned.exists())
            assertTrue(unrelated.isDirectory)
            assertTrue(lookalikeFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically() {
        val sourceRoot = Files.createTempDirectory("ducky-project-source-").toFile()
        val destinationRoot = Files.createTempDirectory("ducky-project-destination-").toFile()
        try {
            val source = ProjectStore(sourceRoot, ::inspectedModel)
            val model = source.createModelDestination("오리 모델.stl").apply {
                writeText("solid duck\nendsolid duck\n")
            }
            val first = ProjectObject(
                id = "duck-a",
                model = inspectedModel(model).copy(fileName = "오리 모델.stl"),
                transform = ModelTransform(
                    offsetXmm = 12f,
                    offsetZmm = 4f,
                    rotationZdeg = 35f,
                    scale = 1.2f,
                    scaleY = 0.8f,
                    scaleZ = 1.6f,
                    mirrorX = true,
                    mirrorY = true,
                ),
                supportPaint = SupportPaint().paint(1, SupportPaintState.ENFORCE),
                seamPaint = SeamPaint().paint(0, SeamPaintState.ENFORCE),
                multiColorPaint = MultiColorPaint().paint(1, 1),
                variableLayerHeights = VariableLayerHeights(
                    listOf(VariableLayerRange(0.2f, 0.7f, 0.08f)),
                ),
                processOverrides = ObjectProcessOverrides(
                    layerHeightMm = 0.1f,
                    wallLoops = 4,
                    sparseInfillDensityPercent = 28f,
                    outerWallSpeedMmS = 42f,
                    supportEnabled = true,
                ),
                brimPoints = BrimPoints(
                    listOf(
                        BrimPoint(1f, 2f, -0.0001f, 4f),
                        BrimPoint(8f, 9f, -0.0001f, 5f),
                    ),
                ),
            ).let { projectObject ->
                projectObject.copy(
                    volumes = listOf(
                        projectObject.singleVolume.copy(
                            config = ProjectVolumeConfig(
                                mapOf("wall_loops" to "5", "sparse_infill_density" to "31%"),
                            ),
                        ),
                    ),
                )
            }
            val second = first.copy(
                id = "duck-b",
                volumes = listOf(
                    first.singleVolume.copy(
                        id = legacyProjectVolumeId("duck-b"),
                        model = first.model.copy(fileName = "duck-copy.stl"),
                        supportPaint = SupportPaint().paint(0, SupportPaintState.BLOCK),
                        seamPaint = SeamPaint().paint(1, SeamPaintState.BLOCK),
                        multiColorPaint = MultiColorPaint().paint(0, 1),
                        filamentSlot = 1,
                    ),
                    first.singleVolume.copy(
                        id = legacyProjectVolumeId("duck-b", 1),
                        model = first.model.copy(fileName = "cutout.stl"),
                        supportPaint = SupportPaint(),
                        seamPaint = SeamPaint(),
                        multiColorPaint = MultiColorPaint(),
                        filamentSlot = 0,
                        role = ProjectVolumeRole.NEGATIVE_VOLUME,
                        config = ProjectVolumeConfig(),
                    ),
                ),
                transform = ModelTransform(offsetXmm = -18f, rotationXdeg = 90f),
                variableLayerHeights = VariableLayerHeights(
                    listOf(VariableLayerRange(0.1f, 0.4f, 0.12f)),
                ),
            )
            val snapshot = ProjectSnapshot(listOf(first, second), selectedObjectId = second.id)
            val options = multiFilamentSettingsFixture()

            val firstArchive = ByteArrayOutputStream().also {
                source.exportArchive(snapshot, options, it)
            }.toByteArray()
            val secondArchive = ByteArrayOutputStream().also {
                source.exportArchive(snapshot, options, it)
            }.toByteArray()

            assertArrayEquals(firstArchive, secondArchive)
            val archiveEntries = archiveEntries(firstArchive)
            assertEquals(setOf("manifest.json", "models/000.stl"), archiveEntries.keys)
            assertArrayEquals(model.readBytes(), archiveEntries["models/000.stl"])
            val manifest = JSONObject(requireNotNull(archiveEntries["manifest.json"]).toString(Charsets.UTF_8))
            assertEquals(
                setOf("format", "schemaVersion", "selectedPlateId", "plates"),
                manifest.keys().asSequence().toSet(),
            )
            assertEquals(56, manifest.getInt("schemaVersion"))
            assertEquals(legacyProjectPlateId(), manifest.getString("selectedPlateId"))
            val manifestPlate = manifest.getJSONArray("plates").getJSONObject(0)
            assertEquals(
                setOf("id", "selectedObjectId", "sliceOptions", "objects"),
                manifestPlate.keys().asSequence().toSet(),
            )
            assertEquals(
                setOf(
                    "id", "transform", "variableLayerHeights", "processOverrides",
                    "brimPoints", "volumes",
                ),
                manifestPlate.getJSONArray("objects").getJSONObject(0).keys().asSequence().toSet(),
            )
            val manifestVolume = manifestPlate.getJSONArray("objects").getJSONObject(0)
                .getJSONArray("volumes").getJSONObject(0)
            assertEquals(
                setOf(
                    "id", "displayName", "modelEntry", "supportPaint", "seamPaint",
                    "multiColorPaint", "filamentSlot", "role", "config",
                ),
                manifestVolume.keys().asSequence().toSet(),
            )
            assertEquals(first.singleVolume.id, manifestVolume.getString("id"))
            assertEquals(
                setOf(
                    "offsetXmm", "offsetYmm", "offsetZmm", "rotationXdeg", "rotationYdeg",
                    "rotationZdeg", "scale", "scaleY", "scaleZ", "mirrorX", "mirrorY",
                    "mirrorZ",
                ),
                manifestPlate.getJSONArray("objects").getJSONObject(0)
                    .getJSONObject("transform").keys().asSequence().toSet(),
            )

            val destination = ProjectStore(destinationRoot, ::inspectedModel)
            val imported = destination.importArchive(ByteArrayInputStream(firstArchive))

            assertEquals(second.id, imported.snapshot.selectedObjectId)
            assertEquals(2, imported.snapshot.objects.size)
            assertEquals(first.singleVolume.id, imported.snapshot.objects[0].singleVolume.id)
            assertEquals(second.volumes.map(ProjectVolume::id), imported.snapshot.objects[1].volumes.map(ProjectVolume::id))
            assertEquals("오리 모델.stl", imported.snapshot.objects[0].model.fileName)
            assertEquals("duck-copy.stl", imported.snapshot.objects[1].volumes[0].model.fileName)
            assertEquals(first.transform, imported.snapshot.objects[0].transform)
            assertEquals(second.transform, imported.snapshot.objects[1].transform)
            assertEquals(first.supportPaint, imported.snapshot.objects[0].supportPaint)
            assertEquals(second.volumes[0].supportPaint, imported.snapshot.objects[1].volumes[0].supportPaint)
            assertEquals(first.seamPaint, imported.snapshot.objects[0].seamPaint)
            assertEquals(second.volumes[0].seamPaint, imported.snapshot.objects[1].volumes[0].seamPaint)
            assertEquals(first.multiColorPaint, imported.snapshot.objects[0].multiColorPaint)
            assertEquals(second.volumes[0].multiColorPaint, imported.snapshot.objects[1].volumes[0].multiColorPaint)
            assertEquals(first.singleVolume.config, imported.snapshot.objects[0].singleVolume.config)
            assertEquals(
                ProjectVolumeRole.NEGATIVE_VOLUME,
                imported.snapshot.objects[1].volumes[1].role,
            )
            assertEquals(
                first.variableLayerHeights,
                imported.snapshot.objects[0].variableLayerHeights,
            )
            assertEquals(
                second.variableLayerHeights,
                imported.snapshot.objects[1].variableLayerHeights,
            )
            assertEquals(first.processOverrides, imported.snapshot.objects[0].processOverrides)
            assertEquals(second.processOverrides, imported.snapshot.objects[1].processOverrides)
            assertEquals(first.brimPoints, imported.snapshot.objects[0].brimPoints)
            assertEquals(second.brimPoints, imported.snapshot.objects[1].brimPoints)
            assertEquals(0, imported.snapshot.objects[0].filamentSlot)
            assertEquals(1, imported.snapshot.objects[1].volumes[0].filamentSlot)
            assertEquals(
                imported.snapshot.objects[0].model.localPath,
                imported.snapshot.objects[1].volumes[0].model.localPath,
            )
            assertEquals(
                options.toProjectJson().toString(),
                imported.sliceOptions?.toProjectJson()?.toString(),
            )
            assertEquals(
                listOf("M117 PRIMARY_START", "M117 SECONDARY_START"),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::filamentStartGcode),
            )
            assertEquals(
                listOf("M117 PRIMARY_END", "M117 SECONDARY_END"),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::filamentEndGcode),
            )
            assertEquals(
                listOf(2.85f, 2.85f),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::diameter),
            )
            assertEquals(
                listOf(1.07f, 1.32f),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::density),
            )
            assertEquals(
                listOf(42.5f, 75f),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::costPerKilogram),
            )
            assertEquals(
                listOf(9f, 35f),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::minimalPurgeOnWipeTower),
            )
            assertEquals(
                listOf(40, 70),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::additionalCoolingFanSpeed),
            )
            assertEquals(
                listOf(42f, 91f),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::fanCoolingLayerTime),
            )
            assertEquals(
                listOf("25%", "75%"),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::overhangFanThreshold),
            )
            assertEquals(
                listOf(45, -1),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::internalBridgeFanSpeed),
            )
            assertEquals(true, imported.sliceOptions?.printerProfile?.auxiliaryFan)
            assertEquals(0.7f, imported.sliceOptions?.printerProfile?.fanSpeedupTime)
            assertEquals(false, imported.sliceOptions?.printerProfile?.fanSpeedupOverhangs)
            assertEquals(0.25f, imported.sliceOptions?.printerProfile?.fanKickstart)
            assertEquals(0.12f, imported.sliceOptions?.printerProfile?.minLayerHeight)
            assertEquals(0.42f, imported.sliceOptions?.printerProfile?.maxLayerHeight)
            assertEquals(
                listOf(0f, 10.5f),
                imported.sliceOptions?.printerProfile?.extruderOffsetsX,
            )
            assertEquals(
                listOf(0f, -2.5f),
                imported.sliceOptions?.printerProfile?.extruderOffsetsY,
            )
            assertEquals(
                "; FIXTURE_TIMELAPSE",
                imported.sliceOptions?.printerProfile?.timeLapseGcode,
            )
            assertEquals(
                "; FIXTURE_BEFORE_LAYER",
                imported.sliceOptions?.printerProfile?.beforeLayerChangeGcode,
            )
            assertEquals(
                "; FIXTURE_AFTER_LAYER",
                imported.sliceOptions?.printerProfile?.layerChangeGcode,
            )
            assertEquals(
                "T[next_extruder] ; FIXTURE_TOOL_CHANGE",
                imported.sliceOptions?.printerProfile?.changeFilamentGcode,
            )
            assertEquals(
                "; FIXTURE_BETWEEN_OBJECTS",
                imported.sliceOptions?.printerProfile?.printingByObjectGcode,
            )
            assertEquals(false, imported.sliceOptions?.printerProfile?.useRelativeEDistances)
            assertEquals(false, imported.sliceOptions?.printerProfile?.emitMachineLimitsToGcode)
            assertEquals(true, imported.sliceOptions?.printerProfile?.manualFilamentChange)
            assertEquals(true, imported.sliceOptions?.printerProfile?.disableM73)
            assertEquals(73.5f, imported.sliceOptions?.printerProfile?.coolingTubeRetraction)
            assertEquals(11f, imported.sliceOptions?.printerProfile?.coolingTubeLength)
            assertEquals(80f, imported.sliceOptions?.printerProfile?.parkingPosRetraction)
            assertEquals(-3.5f, imported.sliceOptions?.printerProfile?.extraLoadingMove)
            assertEquals(false, imported.sliceOptions?.printerProfile?.enableFilamentRamming)
            assertEquals(false, imported.sliceOptions?.printerProfile?.purgeInPrimeTower)
            assertEquals(true, imported.sliceOptions?.printerProfile?.highCurrentOnFilamentSwap)
            assertEquals(true, imported.sliceOptions?.quality?.smallAreaFlowCompensation)
            assertEquals(
                "0,0\n0.5,0.6\n10,1",
                imported.sliceOptions?.quality?.smallAreaFlowCompensationModel,
            )
            assertEquals(
                listOf(1.4f, 2.6f),
                imported.sliceOptions?.printerProfile?.toolChangeRetractLengths,
            )
            assertEquals(
                listOf(-0.2f, 0.3f),
                imported.sliceOptions?.printerProfile?.toolChangeRetractRestartExtras,
            )
            assertEquals(
                listOf(false, true),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::soluble),
            )
            assertEquals(
                listOf(false, true),
                imported.sliceOptions?.resolvedFilamentSlots()?.map(FilamentProfile::supportMaterial),
            )
            assertEquals("solid duck\nendsolid duck\n", File(imported.snapshot.objects[0].model.localPath).readText())

            val legacyManifest = JSONObject()
                .put("format", manifest.getString("format"))
                .put("schemaVersion", 1)
                .put("selectedObjectId", manifestPlate.getString("selectedObjectId"))
                .put("sliceOptions", manifestPlate.getJSONObject("sliceOptions"))
                .put(
                    "objects",
                    JSONArray().also { legacyObjects ->
                        val objects = manifestPlate.getJSONArray("objects")
                        repeat(objects.length()) { index ->
                            val objectValue = objects.getJSONObject(index)
                            val volumeValue = objectValue.getJSONArray("volumes").getJSONObject(0)
                            val transform = JSONObject(
                                objectValue.getJSONObject("transform").toString(),
                            ).apply {
                                remove("scaleY")
                                remove("scaleZ")
                            }
                            legacyObjects.put(
                                JSONObject()
                                    .put("id", objectValue.getString("id"))
                                    .put("displayName", volumeValue.getString("displayName"))
                                    .put("modelEntry", volumeValue.getString("modelEntry"))
                                    .put("transform", transform)
                                    .put("supportPaint", volumeValue.getJSONArray("supportPaint"))
                                    .put("filamentSlot", volumeValue.getInt("filamentSlot")),
                            )
                        }
                    },
                )
            val legacyArchive = zipOf(
                "manifest.json" to legacyManifest.toString().toByteArray(),
                "models/000.stl" to requireNotNull(archiveEntries["models/000.stl"]),
            )
            val legacy = destination.importArchive(ByteArrayInputStream(legacyArchive))
            assertTrue(legacy.snapshot.objects.all { it.seamPaint.facets.isEmpty() })
            assertTrue(legacy.snapshot.objects.all { it.multiColorPaint.facets.isEmpty() })
            assertTrue(legacy.snapshot.objects.all { it.variableLayerHeights.ranges.isEmpty() })
            assertTrue(legacy.snapshot.objects.all { it.processOverrides.isEmpty })
            assertTrue(legacy.snapshot.objects.all { it.brimPoints.points.isEmpty() })
            assertEquals(
                legacyProjectVolumeId("duck-a"),
                legacy.snapshot.objects.first().singleVolume.id,
            )
            assertEquals(
                1,
                File(destinationRoot, ProjectStore.MODELS_DIRECTORY).listFiles().orEmpty().size,
            )
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun multiplePlatesAndTheirSettingsRoundTripThroughThePortableArchive() {
        val sourceRoot = Files.createTempDirectory("ducky-project-multiplate-source-").toFile()
        val destinationRoot = Files.createTempDirectory("ducky-project-multiplate-destination-").toFile()
        try {
            val source = ProjectStore(sourceRoot, ::inspectedModel)
            val firstModel = source.createModelDestination("first-plate.stl").apply {
                writeText("solid first plate")
            }
            val secondModel = source.createModelDestination("second-plate.stl").apply {
                writeText("solid second plate")
            }
            val firstOptions = restoredSettingsFixture().copy(fillDensity = 0.13f)
            val secondOptions = restoredSettingsFixture().copy(fillDensity = 0.47f)
            val snapshot = ProjectSnapshot(
                selectedPlateId = "plate-two",
                plates = listOf(
                    ProjectPlate(
                        id = "plate-one",
                        objects = listOf(ProjectObject("first-object", inspectedModel(firstModel))),
                        selectedObjectId = "first-object",
                    ),
                    ProjectPlate(
                        id = "plate-two",
                        objects = listOf(ProjectObject("second-object", inspectedModel(secondModel))),
                        selectedObjectId = "second-object",
                    ),
                ),
            )
            val plateOptions = mapOf(
                "plate-one" to firstOptions,
                "plate-two" to secondOptions,
            )

            val archive = ByteArrayOutputStream().also { output ->
                source.exportArchive(snapshot, plateOptions, output)
            }.toByteArray()
            val manifest = JSONObject(
                requireNotNull(archiveEntries(archive)["manifest.json"]).toString(Charsets.UTF_8),
            )
            assertEquals("plate-two", manifest.getString("selectedPlateId"))
            assertEquals(2, manifest.getJSONArray("plates").length())

            val imported = ProjectStore(destinationRoot, ::inspectedModel)
                .importArchive(ByteArrayInputStream(archive))

            assertEquals("plate-two", imported.snapshot.selectedPlateId)
            assertEquals(listOf("plate-one", "plate-two"), imported.snapshot.plates.map(ProjectPlate::id))
            assertEquals(
                listOf("first-object", "second-object"),
                imported.snapshot.allObjects.map(ProjectObject::id),
            )
            assertEquals(
                firstOptions.toProjectJson().toString(),
                imported.plateOptions.getValue("plate-one").toProjectJson().toString(),
            )
            assertEquals(
                secondOptions.toProjectJson().toString(),
                imported.plateOptions.getValue("plate-two").toProjectJson().toString(),
            )
            assertEquals(
                listOf("solid first plate", "solid second plate"),
                imported.snapshot.allObjects.map { File(it.model.localPath).readText() },
            )
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject() {
        val root = Files.createTempDirectory("ducky-project-invalid-").toFile()
        val outside = File(root.parentFile, "escaped-${root.name}.stl")
        try {
            val store = ProjectStore(root, ::inspectedModel)
            val currentModel = store.createModelDestination("current.stl").apply { writeText("current") }
            val current = ProjectSnapshot(
                listOf(ProjectObject("current", inspectedModel(currentModel))),
                "current",
            )
            store.save(current, restoredSettingsFixture())
            val primaryBefore = File(root, ProjectStore.PROJECT_FILE).readBytes()
            val malicious = zipOf("../${outside.name}" to "escaped".toByteArray())

            assertThrows(ProjectArchiveException::class.java) {
                store.importArchive(ByteArrayInputStream(malicious))
            }

            assertFalse(outside.exists())
            assertArrayEquals(primaryBefore, File(root, ProjectStore.PROJECT_FILE).readBytes())
            assertTrue(currentModel.isFile)
            assertEquals("current", store.load().selectedObjectId)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".archive-") })
        } finally {
            outside.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun canceledArchiveCopyRemovesStagingAndPreservesTheCurrentProject() {
        val sourceRoot = Files.createTempDirectory("ducky-project-cancel-source-").toFile()
        val destinationRoot = Files.createTempDirectory("ducky-project-cancel-destination-").toFile()
        try {
            val source = ProjectStore(sourceRoot, ::inspectedModel)
            val sourceModel = source.createModelDestination("incoming.stl").apply {
                writeBytes(ByteArray(128 * 1_024) { index -> (index * 31).toByte() })
            }
            val archive = ByteArrayOutputStream().also { output ->
                source.exportArchive(
                    ProjectSnapshot(
                        listOf(ProjectObject("incoming", inspectedModel(sourceModel))),
                        "incoming",
                    ),
                    restoredSettingsFixture(),
                    output,
                )
            }.toByteArray()
            val destination = ProjectStore(destinationRoot, ::inspectedModel)
            val currentModel = destination.createModelDestination("current.stl").apply {
                writeText("current")
            }
            val current = ProjectSnapshot(
                listOf(ProjectObject("current", inspectedModel(currentModel))),
                "current",
            )
            destination.save(current, restoredSettingsFixture())
            val primaryBefore = File(destinationRoot, ProjectStore.PROJECT_FILE).readBytes()
            var sawPartialModel = false

            assertThrows(DocumentTransferCancelledException::class.java) {
                destination.importArchive(
                    ByteArrayInputStream(archive),
                    checkCancellation = {
                        sawPartialModel = destinationRoot.listFiles().orEmpty()
                            .filter { it.name.startsWith(".archive-") }
                            .flatMap { it.listFiles().orEmpty().asList() }
                            .any { it.length() > 0L }
                        if (sawPartialModel) throw DocumentTransferCancelledException()
                    },
                )
            }

            assertTrue(sawPartialModel)
            assertArrayEquals(primaryBefore, File(destinationRoot, ProjectStore.PROJECT_FILE).readBytes())
            assertEquals("current", destination.load().selectedObjectId)
            assertTrue(currentModel.isFile)
            assertTrue(destinationRoot.listFiles().orEmpty().none { it.name.startsWith(".archive-") })
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun cancellationWinningTheCommitGateRemovesInstalledModelsAndPreservesCurrentProject() {
        val sourceRoot = Files.createTempDirectory("ducky-project-gate-source-").toFile()
        val destinationRoot = Files.createTempDirectory("ducky-project-gate-destination-").toFile()
        try {
            val source = ProjectStore(sourceRoot, ::inspectedModel)
            val sourceModel = source.createModelDestination("incoming.stl").apply {
                writeText("solid incoming\nendsolid incoming\n")
            }
            val archive = ByteArrayOutputStream().also { output ->
                source.exportArchive(
                    ProjectSnapshot(
                        listOf(ProjectObject("incoming", inspectedModel(sourceModel))),
                        "incoming",
                    ),
                    restoredSettingsFixture(),
                    output,
                )
            }.toByteArray()
            val destination = ProjectStore(destinationRoot, ::inspectedModel)
            val currentModel = destination.createModelDestination("current.stl").apply {
                writeText("current")
            }
            val current = ProjectSnapshot(
                listOf(ProjectObject("current", inspectedModel(currentModel))),
                "current",
            )
            destination.save(current, restoredSettingsFixture())
            val primaryBefore = File(destinationRoot, ProjectStore.PROJECT_FILE).readBytes()

            assertThrows(DocumentTransferCancelledException::class.java) {
                destination.importArchive(
                    ByteArrayInputStream(archive),
                    beginCommit = { throw DocumentTransferCancelledException() },
                )
            }

            assertArrayEquals(primaryBefore, File(destinationRoot, ProjectStore.PROJECT_FILE).readBytes())
            assertEquals("current", destination.load().selectedObjectId)
            assertTrue(currentModel.isFile)
            assertEquals(
                setOf(currentModel.canonicalPath),
                File(destinationRoot, ProjectStore.MODELS_DIRECTORY)
                    .listFiles().orEmpty().map(File::getCanonicalPath).toSet(),
            )
            assertTrue(destinationRoot.listFiles().orEmpty().none { it.name.startsWith(".archive-") })
        } finally {
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun oversizedManifestIsRejectedBeforeProjectStateChanges() {
        val root = Files.createTempDirectory("ducky-project-oversized-").toFile()
        try {
            val store = ProjectStore(root, ::inspectedModel)
            val archive = zipOf(
                "manifest.json" to ByteArray(MAX_PROJECT_ARCHIVE_MANIFEST_BYTES + 1) { 'x'.code.toByte() },
            )

            assertThrows(ProjectArchiveException::class.java) {
                store.importArchive(ByteArrayInputStream(archive))
            }
            assertFalse(File(root, ProjectStore.PROJECT_FILE).exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".archive-") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archiveEntries(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { archive ->
            buildMap {
                while (true) {
                    val entry = archive.nextEntry ?: break
                    put(entry.name, archive.readBytes())
                }
            }
        }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { archive ->
                entries.forEach { (name, value) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(value)
                    archive.closeEntry()
                }
            }
        }.toByteArray()

    private fun inspectedModel(file: File) = ModelInfo(
        fileName = file.name,
        triangles = 2,
        dimensions = listOf(1.0, 1.0, 1.0),
        localPath = file.canonicalPath,
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(1.0, 1.0, 1.0),
        previewTriangles = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f,
        ),
    )
}

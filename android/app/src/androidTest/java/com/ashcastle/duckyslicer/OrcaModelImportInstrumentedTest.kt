package com.ashcastle.duckyslicer

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrcaModelImportInstrumentedTest {
    @Test
    fun printerFanResponseTimingUsesOrcaFanMover() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "fan-response.3mf")
        val native = NativeLibrary()
        archive.delete()
        try {
            writeStandard3mf(archive)
            val filament = FilamentProfile.PLA.copy(
                fanMinSpeed = 30,
                fanMaxSpeed = 30,
                closeFanFirstLayers = 0,
                fullFanSpeedLayer = 1,
                keepFanAlwaysOn = true,
            )

            fun sliceWith(fanSpeedupTime: Float, fanKickstart: Float): String {
                assertTrue("The fan response fixture must load through Orca", native.loadModel(archive.absolutePath))
                val config = SliceOptions()
                    .selectPrinter(
                        PrinterProfile.CUSTOM_CARTESIAN.copy(
                            fanSpeedupTime = fanSpeedupTime,
                            fanSpeedupOverhangs = false,
                            fanKickstart = fanKickstart,
                        ),
                    )
                    .selectFilament(filament)
                    .toNativeConfig()
                val result = requireNotNull(native.slice(config))
                assertTrue(result.errorMessage, result.success)
                val output = File(result.gcodePath)
                return try {
                    output.readText()
                } finally {
                    output.delete()
                    native.clearModel()
                }
            }

            val baseline = sliceWith(fanSpeedupTime = 0f, fanKickstart = 0f)
            val tuned = sliceWith(fanSpeedupTime = 2f, fanKickstart = 0.5f)
            val baselinePhysical = baseline.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("G0 ") || it.startsWith("G1 ") || it.startsWith("M106 ") }
                .toList()
            val tunedPhysical = tuned.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("G0 ") || it.startsWith("G1 ") || it.startsWith("M106 ") }
                .toList()

            assertTrue(baseline.contains("; fan_speedup_time = 0"))
            assertTrue(tuned.contains("; fan_speedup_time = 2"))
            assertTrue(tuned.contains("; fan_speedup_overhangs = 0"))
            assertTrue(tuned.contains("; fan_kickstart = 0.5"))
            val baselineFullSpeedPulses = baselinePhysical.count { it.startsWith("M106 S255") }
            val tunedFullSpeedPulses = tunedPhysical.count { it.startsWith("M106 S255") }
            assertTrue(
                "FanMover kick-start must add full-speed pulses ($baselineFullSpeedPulses -> $tunedFullSpeedPulses)",
                tunedFullSpeedPulses > baselineFullSpeedPulses,
            )
            val kickstartIndex = tunedPhysical.indexOfFirst { it.startsWith("M106 S255") }
            assertTrue("FanMover must emit a full-speed kick-start pulse", kickstartIndex >= 0)
            assertTrue(
                "FanMover must return from the pulse to the configured 30% fan speed",
                tunedPhysical.drop(kickstartIndex + 1).any { it.startsWith("M106 S76") },
            )
            assertTrue("FanMover must change the physical command stream", baselinePhysical != tunedPhysical)
        } finally {
            native.clearModel()
            archive.delete()
        }
    }

    @Test
    fun selectedPrinterTimelapseCommandIsExpandedForEveryLayer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "timelapse-command.3mf")
        val native = NativeLibrary()
        var gcode: File? = null
        archive.delete()
        try {
            writeStandard3mf(archive)
            assertTrue("The timelapse fixture must load through Orca", native.loadModel(archive.absolutePath))
            val config = SliceOptions()
                .selectPrinter(
                    PrinterProfile.CUSTOM_CARTESIAN.copy(
                        timeLapseGcode = "; DUCKY_TIMELAPSE layer={layer_num} z={layer_z}",
                    ),
                )
                .toNativeConfig()

            val result = requireNotNull(native.slice(config))
            assertTrue(result.errorMessage, result.success)
            gcode = File(result.gcodePath)
            val commands = gcode.readLines()
                .map(String::trim)
                .filter { it.startsWith("; DUCKY_TIMELAPSE layer=") }
            assertTrue("Timelapse G-code must be emitted on multiple layers", commands.size > 2)
            assertTrue("Layer placeholders must expand to changing values", commands.distinct().size > 2)
            assertTrue("Raw layer placeholders must not reach G-code", commands.none { '{' in it || '}' in it })
        } finally {
            native.clearModel()
            gcode?.delete()
            archive.delete()
        }
    }

    @Test
    fun bbs3mfPauseUsesTheSelectedPrinterPauseCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "pause-command.3mf")
        val native = NativeLibrary()
        var gcode: File? = null
        archive.delete()
        try {
            writeStandard3mf(archive, pauseAtZ = 5f)
            assertTrue("The pause fixture must load through Orca", native.loadModel(archive.absolutePath))
            val config = SliceOptions()
                .selectPrinter(
                    PrinterProfile.CUSTOM_CARTESIAN.copy(
                        machinePauseGcode = "M25 ; DUCKY_PROFILE_PAUSE",
                    ),
                )
                .toNativeConfig()

            val result = requireNotNull(native.slice(config))
            assertTrue(result.errorMessage, result.success)
            gcode = File(result.gcodePath)
            assertTrue(
                "The selected printer's pause command must replace the generic 3MF event",
                gcode.readText().lineSequence().any { it.trim() == "M25 ; DUCKY_PROFILE_PAUSE" },
            )
        } finally {
            native.clearModel()
            gcode?.delete()
            archive.delete()
        }
    }

    @Test
    fun objImportsAsAProjectObjectAndSlicesThroughOrca() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val source = File(context.cacheDir, "cube.obj")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        source.writeText(CUBE_OBJ)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                source,
            )
            val objects = importOrcaModels(
                context,
                uri,
                ProjectStore(context),
                SliceOptions(),
            )
            assertEquals(1, objects.size)
            assertEquals("cube.stl", objects.single().model.fileName)
            val outcome = OnDeviceSlicer.slice(objects, SliceOptions())
            gcode = outcome.output
            assertTrue("Imported OBJ must produce real G-code", outcome.output.length() > 1_000L)
        } finally {
            gcode?.delete()
            source.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun standard3mfImportsTwoPlacedObjectsAndSlicesThroughOrca() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = File(context.cacheDir, "two-objects.3mf")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        archive.delete()
        try {
            writeStandard3mf(archive)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                archive,
            )
            val options = SliceOptions().copy(
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val objects = importOrcaModels(context, uri, ProjectStore(context), options)

            assertEquals(2, objects.size)
            assertTrue(objects.all { it.model.localPath.endsWith(".stl") })
            assertEquals(-15f, objects[0].transform.offsetXmm, 0.01f)
            assertEquals(-5f, objects[0].transform.offsetYmm, 0.01f)
            assertEquals(15f, objects[1].transform.offsetXmm, 0.01f)
            assertEquals(-5f, objects[1].transform.offsetYmm, 0.01f)

            val outcome = OnDeviceSlicer.slice(objects, options)
            gcode = outcome.output
            assertTrue("Imported 3MF objects must produce real G-code", outcome.output.length() > 1_000L)
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun standard3mfPreservesExactFacetPaintThroughStorageAndNativeSlice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = File(context.cacheDir, "painted-object.3mf")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        archive.delete()
        try {
            writeStandard3mf(archive, painted = true)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                archive,
            )
            val options = SliceOptions().copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val store = ProjectStore(context)
            val imported = importOrcaModels(context, uri, store, options)
            val painted = imported.first().singleVolume.orcaFacetAnnotations

            assertEquals("841", painted.support.triangles[0])
            assertEquals("8", painted.seam.triangles[0])
            assertEquals("8", painted.multiColor.triangles[0])
            val editedMultiColor = painted.multiColor.paintAt(
                FacetPaintTarget(0, 0.8f, 0.1f, 0.1f, subdivisionDepth = 2),
                state = 1,
            )
            val editedObjects = imported.mapIndexed { index, projectObject ->
                if (index != 0) {
                    projectObject
                } else {
                    projectObject.updateSingleVolume { volume ->
                        volume.copy(
                            orcaFacetAnnotations = volume.orcaFacetAnnotations.copy(
                                multiColor = editedMultiColor,
                            ),
                        )
                    }
                }
            }
            assertTrue(editedMultiColor != painted.multiColor)
            store.save(ProjectSnapshot(editedObjects, editedObjects.first().id), options)
            val restored = store.loadProject().snapshot.objects
            assertEquals(
                editedMultiColor,
                restored.first().singleVolume.orcaFacetAnnotations.multiColor,
            )

            val outcome = OnDeviceSlicer.slice(restored, options)
            gcode = outcome.output
            val commands = outcome.output.readLines().map(String::trim)
            assertTrue("Exact imported facet paint must produce real G-code", outcome.layers > 0)
            assertTrue("Imported color annotation must reach Orca as tool 1", commands.any { it == "T1" })
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun bbs3mfPreservesExactFacetPaintThroughStorageAndNativeSlice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = File(context.cacheDir, "bbs-painted-object.3mf")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        archive.delete()
        try {
            writeStandard3mf(archive, bbsPainted = true)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                archive,
            )
            val options = SliceOptions().copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val store = ProjectStore(context)
            val imported = importOrcaModels(context, uri, store, options)
            val painted = imported.first().singleVolume.orcaFacetAnnotations

            assertEquals("841", painted.support.triangles[0])
            assertEquals("8", painted.seam.triangles[0])
            assertEquals("8", painted.multiColor.triangles[0])
            store.save(ProjectSnapshot(imported, imported.first().id), options)
            val restored = store.loadProject().snapshot.objects
            assertEquals(
                painted,
                restored.first().singleVolume.orcaFacetAnnotations,
            )

            val outcome = OnDeviceSlicer.slice(restored, options)
            gcode = outcome.output
            val commands = outcome.output.readLines().map(String::trim)
            assertTrue("Exact BBS facet paint must produce real G-code", outcome.layers > 0)
            assertTrue("Imported BBS color annotation must reach Orca as tool 1", commands.any { it == "T1" })
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun standard3mfPreservesOneMultiVolumeObjectAndSlicesThroughOrca() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = File(context.cacheDir, "multi-volume.3mf")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        archive.delete()
        try {
            writeMultiVolume3mf(archive)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                archive,
            )
            val options = SliceOptions().copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val store = ProjectStore(context)
            val objects = importOrcaModels(context, uri, store, options)

            assertEquals(1, objects.size)
            val imported = objects.single()
            assertEquals(2, imported.volumes.size)
            assertTrue(imported.volumes.all { it.model.localPath.endsWith(".stl") })
            assertEquals(2, imported.volumes.map(ProjectVolume::id).toSet().size)
            assertEquals(listOf(0, 1), imported.volumes.map(ProjectVolume::filamentSlot))
            assertEquals(-7.5f, imported.transform.offsetXmm, 0.01f)
            assertEquals(-5f, imported.transform.offsetYmm, 0.01f)

            store.save(ProjectSnapshot(objects, imported.id), options)
            val restored = store.loadProject().snapshot.selectedObject!!
            assertEquals(
                imported.volumes.map(ProjectVolume::id),
                restored.volumes.map(ProjectVolume::id),
            )
            assertEquals(listOf(0, 1), restored.volumes.map(ProjectVolume::filamentSlot))

            val outcome = OnDeviceSlicer.slice(objects, options)
            gcode = outcome.output
            val gcodeText = outcome.output.readText()
            val commands = gcodeText.lineSequence().map(String::trim).toList()
            assertTrue(
                "Imported multi-volume 3MF must produce real G-code",
                outcome.output.length() > 1_000L,
            )
            assertTrue(
                "Imported part filament definitions must reach Orca",
                gcodeText.contains("filament_type = PLA;PETG"),
            )
            assertTrue("Imported body must use tool 0", commands.any { it == "T0" })
            assertTrue("Imported accent must use tool 1", commands.any { it == "T1" })
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun exportedThreeMfRoundTripsVolumesFilamentsAndFacetAnnotations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val source = File(context.cacheDir, "export-round-trip-source.3mf")
        val exportDirectory = File(
            context.cacheDir,
            "$THREE_MF_EXPORT_DIRECTORY_PREFIX${java.util.UUID.randomUUID()}",
        )
        val exported = File(exportDirectory, THREE_MF_EXPORT_FILE_NAME)
        projectRoot.deleteRecursively()
        source.delete()
        exportDirectory.deleteRecursively()
        try {
            writeMultiVolume3mf(source, bbsPainted = true)
            val options = SliceOptions().copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val sourceUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                source,
            )
            val imported = importOrcaModels(
                context,
                sourceUri,
                ProjectStore(context),
                options,
            )
            assertEquals(2, imported.single().volumes.size)
            assertEquals(
                "841",
                imported.single().volumes[0].orcaFacetAnnotations.support.triangles[0],
            )
            assertTrue(exportDirectory.mkdir())
            val unicodeName = "🦆".repeat(50)
            val namedProject = imported.map { projectObject ->
                projectObject.copy(
                    volumes = projectObject.volumes.map { volume ->
                        volume.copy(model = volume.model.copy(fileName = "$unicodeName.stl"))
                    },
                )
            }

            OnDeviceSlicer.exportThreeMf(namedProject, options, exported)

            assertTrue(exported.length() > 0L)
            assertEquals(
                listOf(0x50, 0x4b, 0x03, 0x04),
                exported.inputStream().use { input -> List(4) { input.read() } },
            )
            val (exportedModelXml, exportedConfigXml) = java.util.zip.ZipFile(exported).use { zip ->
                val modelXml = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))
                    .bufferedReader().use { it.readText() }
                val configXml = zip.getInputStream(
                    zip.getEntry("Metadata/Slic3r_PE_model.config"),
                ).bufferedReader().use { it.readText() }
                modelXml to configXml
            }
            assertTrue(exportedModelXml.contains("<metadata name=\"Application\">DuckySlicer-"))
            assertTrue(exportedConfigXml.contains(unicodeName))
            val exportedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                exported,
            )
            val restored = importOrcaModels(
                context,
                exportedUri,
                ProjectStore(context),
                options,
            ).single()

            assertEquals(2, restored.volumes.size)
            assertEquals(listOf(0, 1), restored.volumes.map(ProjectVolume::filamentSlot))
            assertEquals(
                listOf(ProjectVolumeRole.MODEL_PART, ProjectVolumeRole.MODEL_PART),
                restored.volumes.map(ProjectVolume::role),
            )
            assertEquals("841", restored.volumes[0].orcaFacetAnnotations.support.triangles[0])
            assertEquals("8", restored.volumes[0].orcaFacetAnnotations.seam.triangles[0])
            assertEquals("8", restored.volumes[0].orcaFacetAnnotations.multiColor.triangles[0])
        } finally {
            source.delete()
            exportDirectory.deleteRecursively()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun negative3mfVolumePreservesItsRoleAndDoesNotBecomePrintableGeometry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val archive = File(context.cacheDir, "modifier-volume.3mf")
        var gcode: File? = null
        projectRoot.deleteRecursively()
        archive.delete()
        try {
            writeMultiVolume3mf(archive, accentSubtype = "negative_part")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.debug-files",
                archive,
            )
            val options = SliceOptions().copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val store = ProjectStore(context)
            val imported = importOrcaModels(context, uri, store, options).single()

            assertEquals(
                listOf(ProjectVolumeRole.MODEL_PART, ProjectVolumeRole.NEGATIVE_VOLUME),
                imported.volumes.map(ProjectVolume::role),
            )
            assertEquals(listOf(0, 0), imported.volumes.map(ProjectVolume::filamentSlot))
            assertEquals("2", imported.volumes[1].config.values["extruder"])
            store.save(ProjectSnapshot(listOf(imported), imported.id), options)
            val restored = store.loadProject().snapshot.selectedObject!!
            assertEquals(ProjectVolumeRole.NEGATIVE_VOLUME, restored.volumes[1].role)
            assertEquals(imported.volumes[1].config, restored.volumes[1].config)

            val outcome = OnDeviceSlicer.slice(listOf(restored), options)
            gcode = outcome.output
            val commands = outcome.output.readLines().map(String::trim)
            assertTrue("Negative-volume 3MF must slice through Orca", outcome.output.length() > 1_000L)
            assertTrue("The printable body must use tool 0", commands.any { it == "T0" })
            assertTrue("A negative volume must not become tool-1 geometry", commands.none { it == "T1" })
        } finally {
            gcode?.delete()
            archive.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun supportVolumeRolesSurvive3mfImportAndNativeSlice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val outputs = ArrayList<File>()
        projectRoot.deleteRecursively()
        try {
            val options = SliceOptions().copy(
                supportEnabled = true,
                bedSizeX = 100f,
                bedSizeY = 100f,
                bedPolygon = rectangularBedPolygon(100f, 100f),
            )
            listOf(
                "support_blocker" to ProjectVolumeRole.SUPPORT_BLOCKER,
                "support_enforcer" to ProjectVolumeRole.SUPPORT_ENFORCER,
            ).forEachIndexed { index, (subtype, expectedRole) ->
                val archive = File(context.cacheDir, "support-role-$index.3mf")
                archive.delete()
                try {
                    writeMultiVolume3mf(archive, accentSubtype = subtype)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.debug-files",
                        archive,
                    )
                    val imported = importOrcaModels(
                        context,
                        uri,
                        ProjectStore(context),
                        options,
                    ).single()
                    assertEquals(expectedRole, imported.volumes[1].role)
                    assertEquals(0, imported.volumes[1].filamentSlot)
                    val outcome = OnDeviceSlicer.slice(listOf(imported), options)
                    outputs += outcome.output
                    assertTrue("$subtype must reach the Orca slice boundary", outcome.layers > 0)
                } finally {
                    archive.delete()
                }
            }
        } finally {
            outputs.forEach(File::delete)
            projectRoot.deleteRecursively()
        }
    }

    private fun writeStandard3mf(
        destination: File,
        pauseAtZ: Float? = null,
        painted: Boolean = false,
        bbsPainted: Boolean = false,
    ) {
        require(!(painted && bbsPainted))
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.writeEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
                    </Types>
                """.trimIndent(),
            )
            zip.writeEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
                    </Relationships>
                """.trimIndent(),
            )
            zip.writeEntry(
                "3D/3dmodel.model",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:slic3rpe="http://schemas.slic3r.org/3mf/2017/06">
                      ${if (painted) """<metadata name="Application">PrusaSlicer-2.9.0</metadata>
                      <metadata name="slic3rpe:Version3mf">1</metadata>
                      <metadata name="slic3rpe:FdmSupportsPaintingVersion">1</metadata>
                      <metadata name="slic3rpe:SeamPaintingVersion">1</metadata>
                      <metadata name="slic3rpe:MmPaintingVersion">1</metadata>""" else if (bbsPainted) """<metadata name="Application">BambuStudio-01.09.00.00</metadata>
                      <metadata name="BambuStudio:3mfVersion">1</metadata>""" else ""}
                      <resources>
                        ${tetrahedronObject(1, "Left", painted = painted, bbsPainted = bbsPainted)}
                        ${tetrahedronObject(2, "Right")}
                      </resources>
                      <build>
                        <item objectid="1" transform="1 0 0 0 1 0 0 0 1 30 40 0"/>
                        <item objectid="2" transform="1 0 0 0 1 0 0 0 1 60 40 0"/>
                      </build>
                    </model>
                """.trimIndent(),
            )
            if (pauseAtZ != null) {
                zip.writeEntry(
                    "Metadata/custom_gcode_per_layer.xml",
                    """<?xml version="1.0" encoding="UTF-8"?>
                        <custom_gcodes_per_layer>
                          <plate>
                            <plate_info id="1"/>
                            <layer top_z="$pauseAtZ" type="1" extruder="1" color="" extra="Ducky pause"/>
                            <mode value="SingleExtruder"/>
                          </plate>
                        </custom_gcodes_per_layer>
                    """.trimIndent(),
                )
            }
        }
    }

    private fun writeMultiVolume3mf(
        destination: File,
        accentSubtype: String = "normal_part",
        bbsPainted: Boolean = false,
    ) {
        require(
            accentSubtype in setOf(
                "normal_part",
                "negative_part",
                "modifier_part",
                "support_blocker",
                "support_enforcer",
            ),
        )
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.writeEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
                    </Types>
                """.trimIndent(),
            )
            zip.writeEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
                    </Relationships>
                """.trimIndent(),
            )
            zip.writeEntry(
                "3D/3dmodel.model",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:slic3rpe="http://schemas.slic3r.org/3mf/2017/06">
                      ${if (bbsPainted) "<metadata name=\"Application\">BambuStudio-01.09.00.00</metadata>" else ""}
                      <resources>
                        ${tetrahedronObject(1, "Body", bbsPainted = bbsPainted)}
                        ${tetrahedronObject(2, "Accent")}
                        <object id="3" type="model" name="Assembly">
                          <components>
                            <component objectid="1"/>
                            <component objectid="2" transform="1 0 0 0 1 0 0 0 1 15 0 0"/>
                          </components>
                        </object>
                      </resources>
                      <build>
                        <item objectid="3" transform="1 0 0 0 1 0 0 0 1 30 40 0"/>
                      </build>
                    </model>
                """.trimIndent(),
            )
            zip.writeEntry(
                "Metadata/Slic3r_PE_model.config",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <config>
                      <object id="1" instances_count="1">
                        <metadata type="object" key="name" value="Assembly"/>
                        <volume firstid="0" lastid="3">
                          <metadata type="volume" key="name" value="Body"/>
                          <metadata type="volume" key="volume_type" value="normal_part"/>
                          <metadata type="volume" key="extruder" value="1"/>
                        </volume>
                        <volume firstid="4" lastid="7">
                          <metadata type="volume" key="name" value="Accent"/>
                          <metadata type="volume" key="volume_type" value="$accentSubtype"/>
                          <metadata type="volume" key="extruder" value="2"/>
                        </volume>
                      </object>
                    </config>
                """.trimIndent(),
            )
            zip.writeEntry(
                "Metadata/model_settings.config",
                """<?xml version="1.0" encoding="UTF-8"?>
                    <config>
                      <object id="3">
                        <metadata key="name" value="Assembly"/>
                        <part id="1" subtype="normal_part">
                          <metadata key="name" value="Body"/>
                          <metadata key="extruder" value="1"/>
                        </part>
                        <part id="2" subtype="$accentSubtype">
                          <metadata key="name" value="Accent"/>
                          <metadata key="extruder" value="2"/>
                        </part>
                      </object>
                    </config>
                """.trimIndent(),
            )
        }
    }

    private fun tetrahedronObject(
        id: Int,
        name: String,
        painted: Boolean = false,
        bbsPainted: Boolean = false,
    ): String =
        """<object id="$id" type="model" name="$name">
             <mesh>
               <vertices>
                 <vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/>
                 <vertex x="0" y="10" z="0"/><vertex x="0" y="0" z="10"/>
               </vertices>
               <triangles>
                 <triangle v1="0" v2="2" v3="1"${if (painted) " slic3rpe:custom_supports=\"841\" slic3rpe:custom_seam=\"8\" slic3rpe:mmu_segmentation=\"8\"" else if (bbsPainted) " paint_supports=\"841\" paint_seam=\"8\" paint_color=\"8\"" else ""}/><triangle v1="0" v2="1" v3="3"/>
                 <triangle v1="0" v2="3" v3="2"/><triangle v1="1" v2="2" v3="3"/>
               </triangles>
             </mesh>
           </object>"""

    private fun ZipOutputStream.writeEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        val CUBE_OBJ = """
            o Cube
            v 0 0 0
            v 10 0 0
            v 10 10 0
            v 0 10 0
            v 0 0 10
            v 10 0 10
            v 10 10 10
            v 0 10 10
            f 1 3 2
            f 1 4 3
            f 5 6 7
            f 5 7 8
            f 1 2 6
            f 1 6 5
            f 2 3 7
            f 2 7 6
            f 3 4 8
            f 3 8 7
            f 4 1 5
            f 4 5 8
        """.trimIndent()
    }
}

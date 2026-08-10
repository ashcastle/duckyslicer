package com.ashcastle.duckyslicer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectArchiveTest {
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
                transform = ModelTransform(offsetXmm = 12f, rotationZdeg = 35f, scale = 1.2f),
                supportPaint = SupportPaint().paint(1, SupportPaintState.ENFORCE),
            )
            val second = first.copy(
                id = "duck-b",
                model = first.model.copy(fileName = "duck-copy.stl"),
                transform = ModelTransform(offsetXmm = -18f, rotationXdeg = 90f),
                supportPaint = SupportPaint().paint(0, SupportPaintState.BLOCK),
            )
            val snapshot = ProjectSnapshot(listOf(first, second), selectedObjectId = second.id)
            val options = restoredSettingsFixture()

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
                setOf("format", "schemaVersion", "selectedObjectId", "sliceOptions", "objects"),
                manifest.keys().asSequence().toSet(),
            )
            assertEquals(
                setOf("id", "displayName", "modelEntry", "transform", "supportPaint"),
                manifest.getJSONArray("objects").getJSONObject(0).keys().asSequence().toSet(),
            )

            val destination = ProjectStore(destinationRoot, ::inspectedModel)
            val imported = destination.importArchive(ByteArrayInputStream(firstArchive))

            assertEquals(second.id, imported.snapshot.selectedObjectId)
            assertEquals(2, imported.snapshot.objects.size)
            assertEquals("오리 모델.stl", imported.snapshot.objects[0].model.fileName)
            assertEquals("duck-copy.stl", imported.snapshot.objects[1].model.fileName)
            assertEquals(first.transform, imported.snapshot.objects[0].transform)
            assertEquals(second.transform, imported.snapshot.objects[1].transform)
            assertEquals(first.supportPaint, imported.snapshot.objects[0].supportPaint)
            assertEquals(second.supportPaint, imported.snapshot.objects[1].supportPaint)
            assertEquals(
                imported.snapshot.objects[0].model.localPath,
                imported.snapshot.objects[1].model.localPath,
            )
            assertEquals(
                options.toProjectJson().toString(),
                imported.sliceOptions?.toProjectJson()?.toString(),
            )
            assertEquals("solid duck\nendsolid duck\n", File(imported.snapshot.objects[0].model.localPath).readText())
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

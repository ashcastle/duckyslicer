package com.ashcastle.duckyslicer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

internal class ProjectArchiveException : IllegalArgumentException("project_archive_invalid")

internal data class ArchivedProjectObject(
    val id: String,
    val displayName: String,
    val modelEntry: String,
    val transform: ModelTransform,
    val supportPaint: SupportPaint,
    val seamPaint: SeamPaint,
    val multiColorPaint: MultiColorPaint,
    val variableLayerHeights: VariableLayerHeights,
    val processOverrides: ObjectProcessOverrides,
    val filamentSlot: Int,
)

internal data class StagedArchiveModel(
    val file: File,
    val info: ModelInfo,
)

internal data class DecodedProjectArchive(
    val objects: List<ArchivedProjectObject>,
    val selectedObjectId: String?,
    val sliceOptions: SliceOptions,
    val models: Map<String, StagedArchiveModel>,
)

internal object ProjectArchiveCodec {
    fun write(
        snapshot: ProjectSnapshot,
        sliceOptions: SliceOptions,
        output: OutputStream,
    ) = archiveBoundary {
        require(snapshot.objects.size <= ProjectStore.MAX_PROJECT_OBJECTS)
        require(snapshot.objects.map(ProjectObject::id).toSet().size == snapshot.objects.size)
        require(
            snapshot.selectedObjectId == null ||
                snapshot.objects.any { it.id == snapshot.selectedObjectId },
        )
        val availableSlots = sliceOptions.resolvedFilamentSlots().indices
        require(snapshot.objects.all { projectObject ->
            projectObject.filamentSlot in availableSlots &&
                projectObject.multiColorPaint.facets.values.all { it in availableSlots }
        })
        val modelEntries = LinkedHashMap<File, String>()
        snapshot.objects.forEach { projectObject ->
            val model = File(projectObject.model.localPath).canonicalFile
            require(model.isFile && model.length() in 1..MAX_MODEL_IMPORT_BYTES)
            modelEntries.getOrPut(model) { archiveModelEntry(modelEntries.size) }
        }
        val contentBytes = modelEntries.keys.fold(0L) { total, model ->
            checkedArchiveTotal(total, model.length())
        }
        require(contentBytes <= MAX_PROJECT_ARCHIVE_CONTENT_BYTES)

        val manifest = JSONObject()
            .put("format", PROJECT_ARCHIVE_FORMAT)
            .put("schemaVersion", PROJECT_ARCHIVE_SCHEMA_VERSION)
            .put("selectedObjectId", snapshot.selectedObjectId ?: JSONObject.NULL)
            .put("sliceOptions", sliceOptions.toProjectJson())
            .put(
                "objects",
                JSONArray().also { objects ->
                    snapshot.objects.forEach { projectObject ->
                        val model = File(projectObject.model.localPath).canonicalFile
                        objects.put(
                            JSONObject()
                                .put("id", checkedArchiveId(projectObject.id))
                                .put("displayName", checkedArchiveDisplayName(projectObject.model.fileName))
                                .put("modelEntry", requireNotNull(modelEntries[model]))
                                .put("transform", projectObject.transform.toArchiveJson())
                                .put("supportPaint", projectObject.supportPaint.toArchiveJson())
                                .put("seamPaint", projectObject.seamPaint.toArchiveJson())
                                .put("multiColorPaint", projectObject.multiColorPaint.toArchiveJson())
                                .put(
                                    "variableLayerHeights",
                                    projectObject.variableLayerHeights.toArchiveJson(),
                                )
                                .put("processOverrides", projectObject.processOverrides.toProjectJson())
                                .put("filamentSlot", projectObject.filamentSlot),
                        )
                    }
                },
            )
        val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)
        require(manifestBytes.size in 1..MAX_PROJECT_ARCHIVE_MANIFEST_BYTES)
        checkedArchiveTotal(contentBytes, manifestBytes.size.toLong())

        val zip = ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(output)))
        zip.use { archive ->
            archive.putNextEntry(projectArchiveEntry(PROJECT_ARCHIVE_MANIFEST))
            archive.write(manifestBytes)
            archive.closeEntry()
            modelEntries.forEach { (model, entryName) ->
                archive.putNextEntry(projectArchiveEntry(entryName))
                model.inputStream().use { input ->
                    copyArchiveBytes(input, archive, model.length())
                }
                archive.closeEntry()
            }
            archive.finish()
        }
        output.flush()
    }

    fun read(
        input: InputStream,
        stagingDirectory: File,
        inspectModel: (File) -> ModelInfo,
    ): DecodedProjectArchive = archiveBoundary {
        require(stagingDirectory.isDirectory)
        val entries = HashSet<String>()
        val models = LinkedHashMap<String, File>()
        var manifestBytes: ByteArray? = null
        var totalContentBytes = 0L
        val limitedInput = ArchiveInputLimit(input, MAX_PROJECT_ARCHIVE_FILE_BYTES)
        ZipInputStream(BufferedInputStream(limitedInput)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(entries.size < MAX_PROJECT_ARCHIVE_ENTRIES)
                require(!entry.isDirectory && entry.name.length in 1..MAX_PROJECT_ARCHIVE_ENTRY_NAME)
                require(entries.add(entry.name))
                require(entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED)
                when {
                    entry.name == PROJECT_ARCHIVE_MANIFEST -> {
                        require(manifestBytes == null)
                        manifestBytes = readArchiveBytes(archive, MAX_PROJECT_ARCHIVE_MANIFEST_BYTES)
                        totalContentBytes = checkedArchiveTotal(
                            totalContentBytes,
                            requireNotNull(manifestBytes).size.toLong(),
                        )
                    }
                    PROJECT_ARCHIVE_MODEL_ENTRY.matches(entry.name) -> {
                        require(models.size < ProjectStore.MAX_PROJECT_OBJECTS)
                        if (entry.size >= 0) require(entry.size in 1..MAX_MODEL_IMPORT_BYTES)
                        val staged = File(stagingDirectory, "model-${models.size}.stl")
                        FileOutputStream(staged).use { output ->
                            val copied = copyArchiveBytes(
                                archive,
                                output,
                                minOf(
                                    MAX_MODEL_IMPORT_BYTES,
                                    MAX_PROJECT_ARCHIVE_CONTENT_BYTES - totalContentBytes,
                                ),
                            )
                            require(copied > 0L)
                            totalContentBytes = checkedArchiveTotal(totalContentBytes, copied)
                            output.flush()
                            output.fd.sync()
                        }
                        models[entry.name] = staged
                    }
                    else -> throw ProjectArchiveException()
                }
                require(totalContentBytes <= MAX_PROJECT_ARCHIVE_CONTENT_BYTES)
                archive.closeEntry()
            }
        }
        val manifest = parseBoundedJsonObject(
            requireNotNull(manifestBytes),
            MAX_PROJECT_ARCHIVE_MANIFEST_BYTES,
        )
        val metadata = parseManifest(manifest)
        val referencedEntries = metadata.objects.mapTo(HashSet(), ArchivedProjectObject::modelEntry)
        require(referencedEntries == models.keys)
        val inspected = models.mapValues { (_, file) ->
            val info = inspectModel(file)
            require(info.triangles > 0)
            StagedArchiveModel(file, info)
        }
        val validatedObjects = metadata.objects.map { archived ->
            val triangleCount = requireNotNull(inspected[archived.modelEntry]).info.triangles
            require(archived.supportPaint.facets.keys.all { it in 0 until triangleCount })
            require(archived.seamPaint.facets.keys.all { it in 0 until triangleCount })
            require(archived.multiColorPaint.facets.keys.all { it in 0 until triangleCount })
            archived
        }
        DecodedProjectArchive(
            objects = validatedObjects,
            selectedObjectId = metadata.selectedObjectId,
            sliceOptions = metadata.sliceOptions,
            models = inspected,
        )
    }

    private fun parseManifest(root: JSONObject): DecodedProjectArchive {
        require(root.optString("format") == PROJECT_ARCHIVE_FORMAT)
        val schemaVersion = root.optInt("schemaVersion", 0)
        require(schemaVersion in MIN_PROJECT_ARCHIVE_SCHEMA_VERSION..PROJECT_ARCHIVE_SCHEMA_VERSION)
        val values = root.getJSONArray("objects")
        require(values.length() <= ProjectStore.MAX_PROJECT_OBJECTS)
        val ids = HashSet<String>()
        val objects = List(values.length()) { index ->
            val value = values.getJSONObject(index)
            val id = checkedArchiveId(value.getString("id"))
            require(ids.add(id))
            ArchivedProjectObject(
                id = id,
                displayName = checkedArchiveDisplayName(value.getString("displayName")),
                modelEntry = value.getString("modelEntry").takeIf(PROJECT_ARCHIVE_MODEL_ENTRY::matches)
                    ?: throw ProjectArchiveException(),
                transform = value.getJSONObject("transform").toArchiveTransform(
                    requireAxisScales = schemaVersion >= 6,
                ),
                supportPaint = value.getJSONArray("supportPaint").toArchiveSupportPaint(),
                seamPaint = if (schemaVersion >= 2) {
                    value.getJSONArray("seamPaint").toArchiveSeamPaint()
                } else {
                    SeamPaint()
                },
                multiColorPaint = if (schemaVersion >= 4) {
                    value.getJSONArray("multiColorPaint").toArchiveMultiColorPaint()
                } else {
                    MultiColorPaint()
                },
                variableLayerHeights = if (schemaVersion >= 3) {
                    value.getJSONArray("variableLayerHeights").toArchiveVariableLayerHeights()
                } else {
                    VariableLayerHeights()
                },
                processOverrides = if (schemaVersion >= 5) {
                    value.getJSONObject("processOverrides").toObjectProcessOverrides()
                } else {
                    ObjectProcessOverrides()
                },
                filamentSlot = value.optInt("filamentSlot", 0).takeIf {
                    it in 0 until MAX_FILAMENT_SLOTS
                } ?: throw ProjectArchiveException(),
            )
        }
        val selected = root.takeUnless { it.isNull("selectedObjectId") }
            ?.getString("selectedObjectId")
        require(selected == null || selected in ids)
        val options = root.getJSONObject("sliceOptions").toProjectSliceOptionsOrNull()
            ?: throw ProjectArchiveException()
        val availableSlots = options.resolvedFilamentSlots().indices
        require(objects.all { archived ->
            archived.filamentSlot in availableSlots &&
                archived.multiColorPaint.facets.values.all { it in availableSlots }
        })
        return DecodedProjectArchive(objects, selected, options, emptyMap())
    }
}

private fun ModelTransform.toArchiveJson() = JSONObject()
    .put("offsetXmm", offsetXmm.checkedArchiveTransform(-ProjectStore.MAX_OFFSET_MM, ProjectStore.MAX_OFFSET_MM))
    .put("offsetYmm", offsetYmm.checkedArchiveTransform(-ProjectStore.MAX_OFFSET_MM, ProjectStore.MAX_OFFSET_MM))
    .put("offsetZmm", offsetZmm.checkedArchiveTransform(-ProjectStore.MAX_OFFSET_MM, ProjectStore.MAX_OFFSET_MM))
    .put("rotationXdeg", rotationXdeg.checkedArchiveTransform(-ProjectStore.MAX_ROTATION_DEG, ProjectStore.MAX_ROTATION_DEG))
    .put("rotationYdeg", rotationYdeg.checkedArchiveTransform(-ProjectStore.MAX_ROTATION_DEG, ProjectStore.MAX_ROTATION_DEG))
    .put("rotationZdeg", rotationZdeg.checkedArchiveTransform(-ProjectStore.MAX_ROTATION_DEG, ProjectStore.MAX_ROTATION_DEG))
    .put("scale", scale.checkedArchiveTransform(ProjectStore.MIN_SCALE, ProjectStore.MAX_SCALE))
    .put("scaleY", scaleY.checkedArchiveTransform(ProjectStore.MIN_SCALE, ProjectStore.MAX_SCALE))
    .put("scaleZ", scaleZ.checkedArchiveTransform(ProjectStore.MIN_SCALE, ProjectStore.MAX_SCALE))
    .put("mirrorX", mirrorX)
    .put("mirrorY", mirrorY)
    .put("mirrorZ", mirrorZ)

private fun JSONObject.toArchiveTransform(requireAxisScales: Boolean): ModelTransform {
    val uniformScale = checkedArchiveFloat("scale", ProjectStore.MIN_SCALE, ProjectStore.MAX_SCALE)
    if (requireAxisScales) require(has("scaleY") && has("scaleZ"))
    return ModelTransform(
        offsetXmm = checkedArchiveFloat("offsetXmm", -ProjectStore.MAX_OFFSET_MM, ProjectStore.MAX_OFFSET_MM),
        offsetYmm = checkedArchiveFloat("offsetYmm", -ProjectStore.MAX_OFFSET_MM, ProjectStore.MAX_OFFSET_MM),
        offsetZmm = checkedOptionalArchiveFloat("offsetZmm", -ProjectStore.MAX_OFFSET_MM, ProjectStore.MAX_OFFSET_MM),
        rotationXdeg = checkedArchiveFloat("rotationXdeg", -ProjectStore.MAX_ROTATION_DEG, ProjectStore.MAX_ROTATION_DEG),
        rotationYdeg = checkedArchiveFloat("rotationYdeg", -ProjectStore.MAX_ROTATION_DEG, ProjectStore.MAX_ROTATION_DEG),
        rotationZdeg = checkedArchiveFloat("rotationZdeg", -ProjectStore.MAX_ROTATION_DEG, ProjectStore.MAX_ROTATION_DEG),
        scale = uniformScale,
        scaleY = checkedOptionalArchiveFloat(
            "scaleY",
            ProjectStore.MIN_SCALE,
            ProjectStore.MAX_SCALE,
            uniformScale,
        ),
        scaleZ = checkedOptionalArchiveFloat(
            "scaleZ",
            ProjectStore.MIN_SCALE,
            ProjectStore.MAX_SCALE,
            uniformScale,
        ),
        mirrorX = checkedArchiveBoolean("mirrorX"),
        mirrorY = checkedArchiveBoolean("mirrorY"),
        mirrorZ = checkedArchiveBoolean("mirrorZ"),
    )
}

private fun JSONObject.checkedArchiveBoolean(name: String): Boolean {
    if (!has(name)) return false
    return get(name) as? Boolean ?: throw ProjectArchiveException()
}

private fun JSONObject.checkedOptionalArchiveFloat(
    name: String,
    minimum: Float,
    maximum: Float,
    default: Float = 0f,
): Float = if (has(name)) checkedArchiveFloat(name, minimum, maximum) else default

private fun SupportPaint.toArchiveJson() = JSONArray().also { values ->
    require(facets.size <= SupportPaint.MAX_PAINTED_FACETS)
    facets.toSortedMap().forEach { (facetIndex, state) ->
        require(facetIndex >= 0)
        values.put(facetIndex)
        values.put(state.code)
    }
}

private fun JSONArray.toArchiveSupportPaint(): SupportPaint {
    require(length() % 2 == 0 && length() / 2 <= SupportPaint.MAX_PAINTED_FACETS)
    val facets = LinkedHashMap<Int, SupportPaintState>(length() / 2)
    var previousIndex = -1
    for (offset in 0 until length() step 2) {
        val index = getInt(offset)
        val state = SupportPaintState.fromCode(getInt(offset + 1)) ?: throw ProjectArchiveException()
        require(index >= 0 && index > previousIndex)
        facets[index] = state
        previousIndex = index
    }
    return SupportPaint(facets)
}

private fun SeamPaint.toArchiveJson() = JSONArray().also { values ->
    require(facets.size <= SeamPaint.MAX_PAINTED_FACETS)
    facets.toSortedMap().forEach { (facetIndex, state) ->
        require(facetIndex >= 0)
        values.put(facetIndex)
        values.put(state.code)
    }
}

private fun JSONArray.toArchiveSeamPaint(): SeamPaint {
    require(length() % 2 == 0 && length() / 2 <= SeamPaint.MAX_PAINTED_FACETS)
    val facets = LinkedHashMap<Int, SeamPaintState>(length() / 2)
    var previousIndex = -1
    for (offset in 0 until length() step 2) {
        val index = getInt(offset)
        val state = SeamPaintState.fromCode(getInt(offset + 1)) ?: throw ProjectArchiveException()
        require(index >= 0 && index > previousIndex)
        facets[index] = state
        previousIndex = index
    }
    return SeamPaint(facets)
}

private fun MultiColorPaint.toArchiveJson() = JSONArray().also { values ->
    facets.toSortedMap().forEach { (facetIndex, filamentSlot) ->
        require(facetIndex >= 0 && filamentSlot in 0 until MAX_FILAMENT_SLOTS)
        values.put(facetIndex)
        values.put(filamentSlot)
    }
}

private fun JSONArray.toArchiveMultiColorPaint(): MultiColorPaint {
    require(length() % 2 == 0 && length() / 2 <= MultiColorPaint.MAX_PAINTED_FACETS)
    val facets = LinkedHashMap<Int, Int>(length() / 2)
    var previousIndex = -1
    for (offset in 0 until length() step 2) {
        val index = getInt(offset)
        val filamentSlot = getInt(offset + 1)
        require(
            index >= 0 && index > previousIndex &&
                filamentSlot in 0 until MAX_FILAMENT_SLOTS,
        )
        facets[index] = filamentSlot
        previousIndex = index
    }
    return MultiColorPaint(facets)
}

private fun VariableLayerHeights.toArchiveJson() = JSONArray().also { values ->
    ranges.forEach { range ->
        values.put(range.startRatio.toDouble())
        values.put(range.endRatio.toDouble())
        values.put(range.layerHeightMm.toDouble())
    }
}

private fun JSONArray.toArchiveVariableLayerHeights(): VariableLayerHeights {
    require(length() % 3 == 0 && length() / 3 <= VariableLayerHeights.MAX_RANGES)
    return VariableLayerHeights(
        List(length() / 3) { index ->
            val offset = index * 3
            VariableLayerRange(
                startRatio = getDouble(offset).toFloat(),
                endRatio = getDouble(offset + 1).toFloat(),
                layerHeightMm = getDouble(offset + 2).toFloat(),
            )
        },
    )
}

private fun JSONObject.checkedArchiveFloat(key: String, minimum: Float, maximum: Float): Float =
    getDouble(key).toFloat().checkedArchiveTransform(minimum, maximum)

private fun Float.checkedArchiveTransform(minimum: Float, maximum: Float): Float =
    takeIf { it.isFinite() && it in minimum..maximum } ?: throw ProjectArchiveException()

private fun checkedArchiveId(value: String): String =
    value.takeIf { it.length in 1..ProjectStore.MAX_ID_LENGTH } ?: throw ProjectArchiveException()

private fun checkedArchiveDisplayName(value: String): String = value
    .takeIf {
        it.length in 1..ProjectStore.MAX_DISPLAY_NAME_LENGTH &&
            it.endsWith(".stl", ignoreCase = true) &&
            it.none(Char::isISOControl)
    }
    ?: throw ProjectArchiveException()

private fun archiveModelEntry(index: Int): String {
    require(index in 0 until ProjectStore.MAX_PROJECT_OBJECTS)
    return "models/${index.toString().padStart(3, '0')}.stl"
}

private fun projectArchiveEntry(name: String) = ZipEntry(name).apply { time = 0L }

private fun checkedArchiveTotal(current: Long, additional: Long): Long {
    require(current >= 0L && additional >= 0L && additional <= Long.MAX_VALUE - current)
    val total = current + additional
    require(total <= MAX_PROJECT_ARCHIVE_CONTENT_BYTES)
    return total
}

private fun copyArchiveBytes(input: InputStream, output: OutputStream, maximumBytes: Long): Long {
    require(maximumBytes >= 0L)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count.toLong() > maximumBytes - total) throw ProjectArchiveException()
        output.write(buffer, 0, count)
        total += count
    }
    return total
}

private fun readArchiveBytes(input: InputStream, maximumBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    copyArchiveBytes(input, output, maximumBytes.toLong())
    return output.toByteArray()
}

private inline fun <T> archiveBoundary(block: () -> T): T = try {
    block()
} catch (failure: ProjectArchiveException) {
    throw failure
} catch (failure: Exception) {
    throw ProjectArchiveException().also { it.initCause(failure) }
}

private class ArchiveInputLimit(input: InputStream, private val maximumBytes: Long) :
    FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int {
        if (total >= maximumBytes) {
            if (super.read() < 0) return -1
            throw ProjectArchiveException()
        }
        val value = super.read()
        if (value >= 0) total += 1
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (total >= maximumBytes) {
            if (super.read() < 0) return -1
            throw ProjectArchiveException()
        }
        val allowed = minOf(length.toLong(), maximumBytes - total).toInt()
        val count = super.read(buffer, offset, allowed)
        if (count > 0) total += count
        return count
    }
}

private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun close() = flush()
}

internal const val PROJECT_ARCHIVE_MIME_TYPE = "application/vnd.duckyslicer.project+zip"
internal const val PROJECT_ARCHIVE_FILE_EXTENSION = ".duckyproject"
internal const val MAX_PROJECT_ARCHIVE_MANIFEST_BYTES = 1_048_576
internal const val MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L
internal const val MAX_PROJECT_ARCHIVE_FILE_BYTES = 1_082_130_432L
private const val MAX_PROJECT_ARCHIVE_ENTRIES = ProjectStore.MAX_PROJECT_OBJECTS + 1
private const val MAX_PROJECT_ARCHIVE_ENTRY_NAME = 128
private const val PROJECT_ARCHIVE_FORMAT = "com.ashcastle.duckyslicer.project"
private const val MIN_PROJECT_ARCHIVE_SCHEMA_VERSION = 1
private const val PROJECT_ARCHIVE_SCHEMA_VERSION = 6
private const val PROJECT_ARCHIVE_MANIFEST = "manifest.json"
private val PROJECT_ARCHIVE_MODEL_ENTRY = Regex("models/[0-9]{3}\\.stl")

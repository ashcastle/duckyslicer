package com.ashcastle.duckyslicer

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class ProjectStore(
    private val projectRoot: File,
    private val inspectModel: (File) -> ModelInfo,
) {
    constructor(context: Context) : this(
        projectRoot = File(context.filesDir, PROJECT_DIRECTORY),
        inspectModel = { model ->
            ModelInfo.fromJson(NativeEngine.inspectStl(model.absolutePath), model.absolutePath)
        },
    )

    private val modelsDirectory = File(projectRoot, MODELS_DIRECTORY)
    private val projectFile = File(projectRoot, PROJECT_FILE)
    private val durableProject = DurableJsonFile(projectFile, MAX_PROJECT_BYTES.toInt())

    fun createModelDestination(displayName: String): File {
        check(modelsDirectory.isDirectory || modelsDirectory.mkdirs()) {
            "Project storage is unavailable"
        }
        val safeName = displayName
            .replace(Regex("[^A-Za-z0-9가-힣._-]"), "_")
            .takeLast(160)
            .ifBlank { "model.stl" }
        return File(modelsDirectory, "${UUID.randomUUID()}-$safeName")
    }

    @Synchronized
    fun exportArchive(
        snapshot: ProjectSnapshot,
        sliceOptions: SliceOptions,
        output: OutputStream,
    ) {
        val modelRoot = modelsDirectory.canonicalFile
        snapshot.objects.forEach { projectObject ->
            val modelFile = File(projectObject.model.localPath).canonicalFile
            require(modelFile.parentFile == modelRoot && modelFile.isFile) {
                "Project model is outside private storage"
            }
        }
        ProjectArchiveCodec.write(snapshot, sliceOptions, output)
    }

    @Synchronized
    fun importArchive(input: InputStream): StoredProjectDocument {
        check(projectRoot.isDirectory || projectRoot.mkdirs()) { "Project storage is unavailable" }
        val staging = File(projectRoot, ".archive-${UUID.randomUUID()}")
        val installed = ArrayList<File>()
        try {
            check(staging.mkdirs()) { "Project import storage is unavailable" }
            val decoded = ProjectArchiveCodec.read(input, staging, inspectModel)
            val installedModels = LinkedHashMap<String, Pair<File, ModelInfo>>()
            decoded.models.forEach { (entryName, stagedModel) ->
                val displayName = decoded.objects.first { it.modelEntry == entryName }.displayName
                val destination = createModelDestination(displayName)
                moveArchiveModel(stagedModel.file, destination)
                installed += destination
                installedModels[entryName] = destination to stagedModel.info
            }
            val snapshot = ProjectSnapshot(
                objects = decoded.objects.map { archived ->
                    val (file, info) = requireNotNull(installedModels[archived.modelEntry])
                    ProjectObject(
                        id = archived.id,
                        model = info.copy(
                            fileName = archived.displayName,
                            localPath = file.canonicalPath,
                        ),
                        transform = archived.transform,
                        supportPaint = archived.supportPaint,
                    )
                },
                selectedObjectId = decoded.selectedObjectId,
            )
            save(snapshot, decoded.sliceOptions)
            // The imported generation is already durable. Cleanup is best-effort so a
            // filesystem cleanup hiccup cannot turn a committed project into a false failure.
            runCatching { pruneUnreferencedModels(snapshot) }
            return StoredProjectDocument(snapshot = snapshot, sliceOptions = decoded.sliceOptions)
        } catch (failure: Throwable) {
            installed.forEach(File::delete)
            throw failure
        } finally {
            staging.deleteRecursively()
        }
    }

    @Synchronized
    fun load(): ProjectSnapshot = loadProject().snapshot

    @Synchronized
    fun loadProject(): StoredProjectDocument {
        val stored = durableProject.read(::readSnapshot, ::isCompatibleProjectRoot)
        if (stored.value != null) {
            pruneUnreferencedModels(stored.value.declaredModels)
            return stored.value.document
        }
        if (stored.status == DurableJsonStatus.MISSING) {
            pruneUnreferencedModels(ProjectSnapshot())
        }
        return StoredProjectDocument(
            storageUnavailable = stored.status in setOf(
                DurableJsonStatus.UNREADABLE,
                DurableJsonStatus.INCOMPATIBLE,
            ),
        )
    }

    private fun readSnapshot(root: JSONObject): StoredProject? {
        if (validateProjectRoot(root) == null) return null
        val schemaVersion = root.optInt("schemaVersion", 0)
        if (schemaVersion !in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION) return null
        val values = root.optJSONArray("objects") ?: JSONArray()
        if (values.length() > MAX_PROJECT_OBJECTS) return null
        val objects = ArrayList<ProjectObject>(values.length())
        val objectIds = HashSet<String>()
        val declaredModels = HashSet<File>()
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            value.optString("modelFile")
                .takeIf(String::isNotBlank)
                ?.let(::resolveStoredModel)
                ?.let(declaredModels::add)
            val restored = restoreObject(value)
            if (!objectIds.add(restored.id)) return null
            objects += restored
        }
        val requestedSelection = root.takeUnless { it.isNull("selectedObjectId") }
            ?.optString("selectedObjectId")?.takeIf(String::isNotBlank)
        return StoredProject(
            document = StoredProjectDocument(
                snapshot = ProjectSnapshot(
                    objects = objects,
                    selectedObjectId = requestedSelection?.takeIf(objectIds::contains)
                        ?: objects.lastOrNull()?.id,
                ),
                sliceOptions = if (schemaVersion >= 2) {
                    root.optJSONObject("sliceOptions")?.toProjectSliceOptionsOrNull()
                } else {
                    null
                },
            ),
            declaredModels = declaredModels,
        )
    }

    @Synchronized
    fun save(snapshot: ProjectSnapshot, sliceOptions: SliceOptions? = null) {
        require(snapshot.objects.size <= MAX_PROJECT_OBJECTS) { "Project has too many objects" }
        require(snapshot.objects.map(ProjectObject::id).toSet().size == snapshot.objects.size) {
            "Project contains duplicate object ids"
        }
        require(snapshot.selectedObjectId == null || snapshot.objects.any { it.id == snapshot.selectedObjectId }) {
            "Project selection is invalid"
        }
        check(projectRoot.isDirectory || projectRoot.mkdirs()) { "Project storage is unavailable" }
        check(modelsDirectory.isDirectory || modelsDirectory.mkdirs()) {
            "Project model storage is unavailable"
        }
        val objects = JSONArray()
        snapshot.objects.forEach { projectObject -> objects.put(projectObject.toStoredJson()) }
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("selectedObjectId", snapshot.selectedObjectId ?: JSONObject.NULL)
            .put("objects", objects)
        if (sliceOptions != null) root.put("sliceOptions", sliceOptions.toProjectJson())
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PROJECT_BYTES) { "Project metadata is too large" }

        durableProject.write(root, ::validateProjectRoot, ::isCompatibleProjectRoot)
    }

    @Synchronized
    fun pruneUnreferencedModels(snapshot: ProjectSnapshot) {
        val modelRoot = modelsDirectory.canonicalFile
        val referenced = snapshot.objects.mapNotNullTo(HashSet()) { projectObject ->
            runCatching { File(projectObject.model.localPath).canonicalFile }
                .getOrNull()
                ?.takeIf { it.parentFile == modelRoot }
        }
        pruneUnreferencedModels(referenced)
    }

    private fun pruneUnreferencedModels(referenced: Set<File>) {
        val modelRoot = modelsDirectory.canonicalFile
        modelsDirectory.listFiles().orEmpty().forEach { candidate ->
            val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
            if (candidate.isFile && resolved.parentFile == modelRoot && resolved !in referenced) {
                candidate.delete()
            }
        }
    }

    private fun restoreObject(value: JSONObject): ProjectObject {
        val id = value.getString("id").takeIf { it.length in 1..MAX_ID_LENGTH }
            ?: error("Invalid object id")
        val storedName = value.getString("modelFile")
        require(storedName.length in 1..MAX_FILE_NAME_LENGTH && File(storedName).name == storedName) {
            "Invalid model file"
        }
        val modelFile = requireNotNull(resolveStoredModel(storedName)) { "Model is unavailable" }
        require(modelFile.length() in 1..MAX_MODEL_IMPORT_BYTES) { "Model size is invalid" }
        val displayName = value.optString("displayName", "model.stl")
            .take(MAX_DISPLAY_NAME_LENGTH)
            .takeIf { it.endsWith(".stl", ignoreCase = true) }
            ?: "model.stl"
        val transform = value.getJSONObject("transform").toModelTransform()
        val model = inspectModel(modelFile).copy(fileName = displayName)
        val supportPaint = value.optJSONArray("supportPaint")
            ?.toSupportPaint(model.triangles)
            ?: SupportPaint()
        return ProjectObject(
            id = id,
            model = model,
            transform = transform,
            supportPaint = supportPaint,
        )
    }

    private fun resolveStoredModel(storedName: String): File? {
        if (storedName.length !in 1..MAX_FILE_NAME_LENGTH || File(storedName).name != storedName) {
            return null
        }
        val modelRoot = modelsDirectory.canonicalFile
        return runCatching { File(modelsDirectory, storedName).canonicalFile }
            .getOrNull()
            ?.takeIf { it.parentFile == modelRoot && it.isFile }
    }

    private fun moveArchiveModel(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun validateProjectRoot(root: JSONObject): JSONObject? = runCatching {
        val schemaVersion = root.optInt("schemaVersion", 0)
        require(schemaVersion in MIN_SUPPORTED_SCHEMA_VERSION..SCHEMA_VERSION)
        val values = root.optJSONArray("objects") ?: error("Project objects are missing")
        require(values.length() <= MAX_PROJECT_OBJECTS)
        val ids = HashSet<String>()
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            val id = value.getString("id")
            require(id.length in 1..MAX_ID_LENGTH && ids.add(id))
            val storedName = value.getString("modelFile")
            require(storedName.length in 1..MAX_FILE_NAME_LENGTH && File(storedName).name == storedName)
            val model = requireNotNull(resolveStoredModel(storedName))
            require(model.length() in 1..MAX_MODEL_IMPORT_BYTES)
            value.getJSONObject("transform").toModelTransform()
            if (schemaVersion >= 3) {
                require(value.optJSONArray("supportPaint")?.isValidSupportPaintArray() == true)
            }
        }
        val selected = root.takeUnless { it.isNull("selectedObjectId") }
            ?.optString("selectedObjectId")?.takeIf(String::isNotBlank)
        require(selected == null || selected in ids)
        if (schemaVersion >= 2 && root.has("sliceOptions")) {
            require(root.optJSONObject("sliceOptions")?.toProjectSliceOptionsOrNull() != null)
        }
        root
    }.getOrNull()

    private fun isCompatibleProjectRoot(root: JSONObject): Boolean =
        root.optInt("schemaVersion", 0) <= SCHEMA_VERSION

    private fun ProjectObject.toStoredJson(): JSONObject {
        val modelRoot = modelsDirectory.canonicalFile
        val modelFile = File(model.localPath).canonicalFile
        require(modelFile.parentFile == modelRoot && modelFile.isFile) {
            "Project model is outside private storage"
        }
        return JSONObject()
            .put("id", id.takeIf { it.length in 1..MAX_ID_LENGTH } ?: error("Invalid object id"))
            .put("displayName", model.fileName.take(MAX_DISPLAY_NAME_LENGTH))
            .put("modelFile", modelFile.name)
            .put("transform", transform.toStoredJson())
            .put("supportPaint", supportPaint.toStoredJson())
    }

    private fun ModelTransform.toStoredJson() = JSONObject()
        .put("offsetXmm", offsetXmm.checkedTransformValue(-MAX_OFFSET_MM, MAX_OFFSET_MM))
        .put("offsetYmm", offsetYmm.checkedTransformValue(-MAX_OFFSET_MM, MAX_OFFSET_MM))
        .put("rotationXdeg", rotationXdeg.checkedTransformValue(-MAX_ROTATION_DEG, MAX_ROTATION_DEG))
        .put("rotationYdeg", rotationYdeg.checkedTransformValue(-MAX_ROTATION_DEG, MAX_ROTATION_DEG))
        .put("rotationZdeg", rotationZdeg.checkedTransformValue(-MAX_ROTATION_DEG, MAX_ROTATION_DEG))
        .put("scale", scale.checkedTransformValue(MIN_SCALE, MAX_SCALE))

    private fun JSONObject.toModelTransform() = ModelTransform(
        offsetXmm = checkedFloat("offsetXmm", -MAX_OFFSET_MM, MAX_OFFSET_MM),
        offsetYmm = checkedFloat("offsetYmm", -MAX_OFFSET_MM, MAX_OFFSET_MM),
        rotationXdeg = checkedFloat("rotationXdeg", -MAX_ROTATION_DEG, MAX_ROTATION_DEG),
        rotationYdeg = checkedFloat("rotationYdeg", -MAX_ROTATION_DEG, MAX_ROTATION_DEG),
        rotationZdeg = checkedFloat("rotationZdeg", -MAX_ROTATION_DEG, MAX_ROTATION_DEG),
        scale = checkedFloat("scale", MIN_SCALE, MAX_SCALE),
    )

    private fun SupportPaint.toStoredJson() = JSONArray().also { values ->
        require(facets.size <= SupportPaint.MAX_PAINTED_FACETS) { "Support paint is too large" }
        facets.toSortedMap().forEach { (facetIndex, state) ->
            values.put(facetIndex)
            values.put(state.code)
        }
    }

    private fun JSONArray.toSupportPaint(triangleCount: Int): SupportPaint {
        require(isValidSupportPaintArray()) { "Invalid support paint" }
        val facets = LinkedHashMap<Int, SupportPaintState>(length() / 2)
        var previousIndex = -1
        for (offset in 0 until length() step 2) {
            val facetIndex = getInt(offset)
            val state = requireNotNull(SupportPaintState.fromCode(getInt(offset + 1)))
            require(facetIndex in 0 until triangleCount && facetIndex > previousIndex) {
                "Invalid support paint facet"
            }
            facets[facetIndex] = state
            previousIndex = facetIndex
        }
        return SupportPaint(facets)
    }

    private fun JSONArray.isValidSupportPaintArray(): Boolean = runCatching {
        require(length() % 2 == 0 && length() / 2 <= SupportPaint.MAX_PAINTED_FACETS)
        var previousIndex = -1
        for (offset in 0 until length() step 2) {
            val facetIndex = getInt(offset)
            require(facetIndex >= 0 && facetIndex > previousIndex)
            require(SupportPaintState.fromCode(getInt(offset + 1)) != null)
            previousIndex = facetIndex
        }
    }.isSuccess

    private fun JSONObject.checkedFloat(
        key: String,
        minimum: Float,
        maximum: Float,
    ): Float = getDouble(key).toFloat().checkedTransformValue(minimum, maximum)

    private fun Float.checkedTransformValue(minimum: Float, maximum: Float): Float =
        takeIf { it.isFinite() && it in minimum..maximum } ?: error("Invalid model transform")

    internal companion object {
        internal fun modelStorageRoot(filesRoot: File): File =
            File(File(filesRoot, PROJECT_DIRECTORY), MODELS_DIRECTORY)

        internal fun recoverAbandonedArchiveStaging(projectRoot: File): Int {
            val canonicalRoot = runCatching { projectRoot.canonicalFile }.getOrNull() ?: return 0
            var removed = 0
            projectRoot.listFiles().orEmpty().forEach { candidate ->
                val identifier = candidate.name.removePrefix(".archive-")
                val expectedName = runCatching { UUID.fromString(identifier).toString() }
                    .getOrNull()
                    ?.let { ".archive-$it" }
                    ?: return@forEach
                val resolved = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
                if (
                    candidate.name == expectedName && candidate.isDirectory &&
                    !Files.isSymbolicLink(candidate.toPath()) && resolved.parentFile == canonicalRoot &&
                    candidate.deleteRecursively()
                ) {
                    removed += 1
                }
            }
            return removed
        }

        const val SCHEMA_VERSION = 3
        const val MIN_SUPPORTED_SCHEMA_VERSION = 1
        const val PROJECT_DIRECTORY = "projects"
        const val MODELS_DIRECTORY = "models"
        const val PROJECT_FILE = "current_project.json"
        const val MAX_PROJECT_BYTES = 1_048_576L
        const val MAX_PROJECT_OBJECTS = 256
        const val MAX_ID_LENGTH = 128
        const val MAX_FILE_NAME_LENGTH = 240
        const val MAX_DISPLAY_NAME_LENGTH = 200
        const val MAX_OFFSET_MM = 10_000f
        const val MAX_ROTATION_DEG = 100_000f
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 10f
    }

    private data class StoredProject(
        val document: StoredProjectDocument,
        val declaredModels: Set<File>,
    )
}

internal data class StoredProjectDocument(
    val snapshot: ProjectSnapshot = ProjectSnapshot(),
    val sliceOptions: SliceOptions? = null,
    val storageUnavailable: Boolean = false,
)

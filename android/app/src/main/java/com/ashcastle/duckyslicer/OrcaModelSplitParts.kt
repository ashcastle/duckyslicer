package com.ashcastle.duckyslicer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SplitProjectVolumeResult(
    val projectObject: ProjectObject,
    val createdPartCount: Int,
    val clearedSurfacePaint: Boolean,
    val installedModelPaths: List<String>,
)

/**
 * Reconstructs the owning Orca ModelObject from its project volumes and delegates connected-
 * component splitting to ModelVolume::split. The owning object pose and object settings remain
 * outside the native edit, so the returned parts stay in the same project coordinate space.
 */
internal suspend fun splitProjectObjectVolume(
    projectObject: ProjectObject,
    sourceVolumeId: String,
    projectStore: ProjectStore,
    maximumResultingVolumes: Int = MAX_PROJECT_VOLUMES_PER_OBJECT,
    requestId: String = UUID.randomUUID().toString(),
): SplitProjectVolumeResult = withContext(Dispatchers.IO) {
    val sourceIndex = projectObject.volumes.indexOfFirst { it.id == sourceVolumeId }
    require(sourceIndex >= 0) { "Source volume is unavailable" }
    require(maximumResultingVolumes in 2..MAX_PROJECT_VOLUMES_PER_OBJECT) {
        "Project has no room for split parts"
    }
    val source = projectObject.volumes[sourceIndex]
    require(source.role == ProjectVolumeRole.MODEL_PART) {
        "Only printable model parts can be split"
    }
    val staging = projectStore.createModelImportStaging()
    val installed = ArrayList<File>()
    try {
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val exported = SlicerProcessClient.splitModelVolume(
            models = projectObject.volumes.map { File(it.model.localPath) },
            sourceVolumeIndex = sourceIndex,
            stagingDirectory = staging,
            requestId = requestId,
        )
        require(exported.size >= 2) { "Invalid split part count" }
        require(projectObject.volumes.size - 1 + exported.size <= maximumResultingVolumes) {
            "Project has too many split parts"
        }

        val sourceBase = source.model.fileName
            .substringBeforeLast('.')
            .trim()
            .take(164)
            .ifBlank { "part" }
        val splitVolumes = exported.mapIndexed { index, part ->
            if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
                throw ProjectEditCancelledException()
            }
            val displayName = "$sourceBase part ${index + 1}.stl"
            val model = projectStore.installImportedModel(part.file, displayName)
            installed += File(model.localPath)
            ProjectVolume(
                id = if (index == 0) {
                    source.id
                } else {
                    splitProjectVolumeId(projectObject.id, source.id, index)
                },
                model = model,
                filamentSlot = source.filamentSlot,
                role = source.role,
                config = source.config,
            )
        }
        val replacementVolumes = buildList {
            addAll(projectObject.volumes.take(sourceIndex))
            addAll(splitVolumes)
            addAll(projectObject.volumes.drop(sourceIndex + 1))
        }
        require(replacementVolumes.map(ProjectVolume::id).toSet().size == replacementVolumes.size) {
            "Split part identities conflict"
        }
        SplitProjectVolumeResult(
            projectObject = projectObject.copy(volumes = replacementVolumes),
            createdPartCount = splitVolumes.size,
            clearedSurfacePaint = source.supportPaint.facets.isNotEmpty() ||
                source.seamPaint.facets.isNotEmpty() ||
                source.multiColorPaint.facets.isNotEmpty(),
            installedModelPaths = installed.map(File::getAbsolutePath),
        )
    } catch (failure: Throwable) {
        installed.forEach(File::delete)
        throw failure
    } finally {
        staging.deleteRecursively()
    }
}

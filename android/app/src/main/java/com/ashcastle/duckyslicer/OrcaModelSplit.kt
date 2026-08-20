package com.ashcastle.duckyslicer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SplitProjectResult(
    val objects: List<ProjectObject>,
    val clearedObjectSettings: Boolean,
)

/**
 * Bakes the selected Android pose once, delegates connected-component splitting to
 * Orca, then restores every exported piece at its exact machine-space position.
 */
internal suspend fun splitProjectObject(
    projectObject: ProjectObject,
    projectStore: ProjectStore,
    options: SliceOptions,
    maximumObjects: Int = ProjectStore.MAX_PROJECT_OBJECTS,
    requestId: String = UUID.randomUUID().toString(),
): SplitProjectResult = withContext(Dispatchers.IO) {
    require(maximumObjects in 2..ProjectStore.MAX_PROJECT_OBJECTS) {
        "Project has no room for split objects"
    }
    val staging = projectStore.createModelImportStaging()
    val installed = ArrayList<File>()
    try {
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val transformed = File(staging, "split-source.stl")
        val transformResult = JSONObject(
            NativeEngine.transformStl(
                projectObject.model.localPath,
                transformed.absolutePath,
                projectObject.transform.toJson(
                    options.bedSizeX,
                    options.bedSizeY,
                    options.bedOriginX,
                    options.bedOriginY,
                ),
            ),
        )
        check(transformResult.optBoolean("ok")) { "Model transform failed" }
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }

        val exported = SlicerProcessClient.splitModel(transformed, staging, requestId)
        require(exported.size in 2..maximumObjects) {
            "Invalid split object count"
        }
        val sourceBase = projectObject.model.fileName
            .substringBeforeLast('.')
            .trim()
            .take(170)
            .ifBlank { "model" }
        val bedCenterX = options.bedOriginX + options.bedSizeX / 2f
        val bedCenterY = options.bedOriginY + options.bedSizeY / 2f
        val objects = exported.mapIndexed { index, split ->
            if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
                throw ProjectEditCancelledException()
            }
            val displayName = "$sourceBase ${index + 1}.stl"
            val model = projectStore.installImportedModel(split.file, displayName)
            installed += File(model.localPath)
            val centerX = ((model.minMm[0] + model.maxMm[0]) / 2.0).toFloat()
            val centerY = ((model.minMm[1] + model.maxMm[1]) / 2.0).toFloat()
            val minimumZ = model.minMm[2].toFloat()
            require(centerX.isFinite() && centerY.isFinite() && minimumZ.isFinite()) {
                "Split object placement is invalid"
            }
            ProjectObject(
                id = UUID.randomUUID().toString(),
                model = model,
                transform = ModelTransform(
                    offsetXmm = centerX - bedCenterX,
                    offsetYmm = centerY - bedCenterY,
                    offsetZmm = minimumZ,
                ),
                processOverrides = projectObject.processOverrides,
                filamentSlot = projectObject.filamentSlot,
            )
        }
        SplitProjectResult(
            objects = objects,
            clearedObjectSettings = projectObject.supportPaint.facets.isNotEmpty() ||
                projectObject.seamPaint.facets.isNotEmpty() ||
                projectObject.multiColorPaint.facets.isNotEmpty() ||
                projectObject.variableLayerHeights.ranges.isNotEmpty() ||
                projectObject.heightRangeModifiers.ranges.isNotEmpty() ||
                projectObject.brimPoints.points.isNotEmpty(),
        )
    } catch (failure: Throwable) {
        installed.forEach(File::delete)
        throw failure
    } finally {
        staging.deleteRecursively()
    }
}

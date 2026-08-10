package com.ashcastle.duckyslicer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class CutProjectResult(
    val objects: List<ProjectObject>,
    val clearedObjectSettings: Boolean,
)

/**
 * Bakes the Android pose once, delegates planar cutting to Orca, then installs
 * the two resulting solids as ordinary undoable project objects.
 */
internal suspend fun cutProjectObject(
    projectObject: ProjectObject,
    projectStore: ProjectStore,
    options: SliceOptions,
    heightRatio: Float,
    placeOnCut: Boolean,
    maximumObjects: Int = ProjectStore.MAX_PROJECT_OBJECTS,
): CutProjectResult = withContext(Dispatchers.IO) {
    require(heightRatio.isFinite() && heightRatio in 0.02f..0.98f) {
        "Cut height is invalid"
    }
    require(maximumObjects >= 2) { "Project has no room for cut objects" }
    val staging = projectStore.createModelImportStaging()
    val installed = ArrayList<File>()
    try {
        val transformed = File(staging, "cut-source.stl")
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
        val transformedModel = ModelInfo.fromJson(
            NativeEngine.inspectStl(transformed.absolutePath),
            transformed.absolutePath,
        )
        val originalMinimumZ = transformedModel.minMm[2].toFloat()
        val cutPlaneZ = originalMinimumZ + transformedModel.dimensions[2].toFloat() * heightRatio
        val originalCenterX = ((transformedModel.minMm[0] + transformedModel.maxMm[0]) / 2.0).toFloat()
        val originalCenterY = ((transformedModel.minMm[1] + transformedModel.maxMm[1]) / 2.0).toFloat()
        require(
            originalMinimumZ.isFinite() && cutPlaneZ.isFinite() &&
                originalCenterX.isFinite() && originalCenterY.isFinite(),
        ) {
            "Cut object placement is invalid"
        }

        val exported = SlicerProcessClient.cutModel(
            transformed,
            staging,
            heightRatio,
            placeOnCut,
        )
        require(exported.size == 2) { "Invalid cut object count" }
        val sourceBase = projectObject.model.fileName
            .substringBeforeLast('.')
            .trim()
            .take(168)
            .ifBlank { "model" }
        val bedCenterX = options.bedOriginX + options.bedSizeX / 2f
        val bedCenterY = options.bedOriginY + options.bedSizeY / 2f
        val objects = exported.mapIndexed { index, cut ->
            val displayName = "$sourceBase ${if (index == 0) "A" else "B"}.stl"
            val model = projectStore.installImportedModel(cut.file, displayName)
            installed += File(model.localPath)
            val targetMinimumZ = when {
                placeOnCut -> 0f
                index == 0 -> cutPlaneZ
                else -> originalMinimumZ
            }
            require(targetMinimumZ.isFinite()) {
                "Cut object placement is invalid"
            }
            ProjectObject(
                id = UUID.randomUUID().toString(),
                model = model,
                transform = ModelTransform(
                    // Orca may rotate a part around the global origin while placing its cut
                    // face on the bed. Keep both results at the source object's XY location;
                    // ModelTransform centers each imported mesh before applying this offset.
                    offsetXmm = originalCenterX - bedCenterX,
                    offsetYmm = originalCenterY - bedCenterY,
                    offsetZmm = targetMinimumZ,
                ),
                filamentSlot = projectObject.filamentSlot,
            )
        }
        CutProjectResult(
            objects = objects,
            clearedObjectSettings = projectObject.supportPaint.facets.isNotEmpty() ||
                projectObject.seamPaint.facets.isNotEmpty() ||
                projectObject.multiColorPaint.facets.isNotEmpty() ||
                projectObject.variableLayerHeights.ranges.isNotEmpty(),
        )
    } catch (failure: Throwable) {
        installed.forEach(File::delete)
        throw failure
    } finally {
        staging.deleteRecursively()
    }
}

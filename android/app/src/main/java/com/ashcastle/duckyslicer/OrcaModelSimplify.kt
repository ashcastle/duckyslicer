package com.ashcastle.duckyslicer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MINIMUM_SIMPLIFY_KEEP_PERCENT = 10
internal const val MAXIMUM_SIMPLIFY_KEEP_PERCENT = 90
internal const val DEFAULT_SIMPLIFY_KEEP_PERCENT = 50
internal const val MINIMUM_SIMPLIFIED_TRIANGLES = 4
internal const val MINIMUM_SIMPLIFIABLE_TRIANGLES = 8

internal data class SimplifyProjectResult(
    val projectObject: ProjectObject,
    val clearedSurfacePaint: Boolean,
)

internal fun simplificationTargetTriangleCount(
    originalTriangles: Int,
    keepPercent: Int,
): Int {
    require(originalTriangles >= MINIMUM_SIMPLIFIABLE_TRIANGLES) {
        "Model has too little detail to simplify"
    }
    require(keepPercent in MINIMUM_SIMPLIFY_KEEP_PERCENT..MAXIMUM_SIMPLIFY_KEEP_PERCENT) {
        "Simplified model detail is invalid"
    }
    return ((originalTriangles.toLong() * keepPercent + 50L) / 100L)
        .coerceAtLeast(MINIMUM_SIMPLIFIED_TRIANGLES.toLong())
        .coerceAtMost((originalTriangles - 1).toLong())
        .toInt()
}

internal fun ProjectObject.withSimplifiedModel(model: ModelInfo): SimplifyProjectResult {
    require(model.triangles in MINIMUM_SIMPLIFIED_TRIANGLES until this.model.triangles) {
        "Simplified model did not reduce detail"
    }
    val clearedSurfacePaint = supportPaint.facets.isNotEmpty() ||
        seamPaint.facets.isNotEmpty() || multiColorPaint.facets.isNotEmpty()
    return SimplifyProjectResult(
        projectObject = copy(
            model = model,
            supportPaint = SupportPaint(),
            seamPaint = SeamPaint(),
            multiColorPaint = MultiColorPaint(),
        ),
        clearedSurfacePaint = clearedSurfacePaint,
    )
}

/**
 * Replaces one private STL with Orca's QEM-simplified output. The Android pose and
 * non-facet settings stay outside the native operation, so they can be preserved exactly.
 */
internal suspend fun simplifyProjectObject(
    projectObject: ProjectObject,
    projectStore: ProjectStore,
    keepPercent: Int,
    requestId: String = UUID.randomUUID().toString(),
): SimplifyProjectResult = withContext(Dispatchers.IO) {
    val targetTriangles = simplificationTargetTriangleCount(
        projectObject.model.triangles,
        keepPercent,
    )
    val staging = projectStore.createModelImportStaging()
    var installed: File? = null
    try {
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val exported = SlicerProcessClient.simplifyModel(
            model = File(projectObject.model.localPath),
            stagingDirectory = staging,
            targetTriangles = targetTriangles,
            requestId = requestId,
        )
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val simplifiedModel = projectStore.installImportedModel(
            exported.file,
            projectObject.model.fileName,
        )
        installed = File(simplifiedModel.localPath)
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        projectObject.withSimplifiedModel(simplifiedModel)
    } catch (failure: Throwable) {
        installed?.delete()
        throw failure
    } finally {
        staging.deleteRecursively()
    }
}

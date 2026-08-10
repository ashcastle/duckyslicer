package com.ashcastle.duckyslicer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class OrcaPrimitive(
    val nativeId: Int,
    val wireName: String,
    val label: Int,
) {
    CUBE(0, "cube", R.string.shape_cube),
    CYLINDER(1, "cylinder", R.string.shape_cylinder),
    SPHERE(2, "sphere", R.string.shape_sphere),
    CONE(3, "cone", R.string.shape_cone),
    DISC(4, "disc", R.string.shape_disc),
    TORUS(5, "torus", R.string.shape_torus),
}

internal suspend fun createOrcaPrimitive(
    primitive: OrcaPrimitive,
    sizeMm: Float,
    displayName: String,
    projectStore: ProjectStore,
): ProjectObject = withContext(Dispatchers.IO) {
    require(sizeMm.isFinite() && sizeMm in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM) {
        "Shape size is invalid"
    }
    val staging = projectStore.createModelImportStaging()
    var installed: File? = null
    try {
        val generated = SlicerProcessClient.createPrimitive(primitive, sizeMm, staging)
        val fileName = "${displayName.substringBeforeLast('.').take(180).ifBlank { "shape" }}.stl"
        val model = projectStore.installImportedModel(generated.file, fileName)
        installed = File(model.localPath)
        ProjectObject(
            id = UUID.randomUUID().toString(),
            model = model,
        )
    } catch (failure: Throwable) {
        installed?.delete()
        throw failure
    } finally {
        staging.deleteRecursively()
    }
}

internal const val MIN_PRIMITIVE_SIZE_MM = 5f
internal const val MAX_PRIMITIVE_SIZE_MM = 200f

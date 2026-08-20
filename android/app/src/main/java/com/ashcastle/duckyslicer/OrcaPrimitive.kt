package com.ashcastle.duckyslicer

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

internal val CREATABLE_AUXILIARY_VOLUME_ROLES = listOf(
    ProjectVolumeRole.NEGATIVE_VOLUME,
    ProjectVolumeRole.PARAMETER_MODIFIER,
    ProjectVolumeRole.SUPPORT_BLOCKER,
    ProjectVolumeRole.SUPPORT_ENFORCER,
)

internal data class OrcaAuxiliaryPrimitiveDraft(
    val primitive: OrcaPrimitive,
    val role: ProjectVolumeRole,
    val sizeMm: Float,
    val centerOffsetXmm: Float = 0f,
    val centerOffsetYmm: Float = 0f,
    val centerOffsetZmm: Float = 0f,
    val modifierInfillPercent: Int = 100,
) {
    init {
        require(role in CREATABLE_AUXILIARY_VOLUME_ROLES) { "Auxiliary volume role is invalid" }
        require(sizeMm.isFinite() && sizeMm in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM) {
            "Shape size is invalid"
        }
        require(
            listOf(centerOffsetXmm, centerOffsetYmm, centerOffsetZmm).all {
                it.isFinite() && kotlin.math.abs(it) <= ProjectStore.MAX_OFFSET_MM
            },
        ) { "Auxiliary volume position is invalid" }
        require(modifierInfillPercent in 0..100) { "Modifier infill is invalid" }
    }

    val config: ProjectVolumeConfig
        get() = if (role == ProjectVolumeRole.PARAMETER_MODIFIER) {
            ProjectVolumeConfig(
                mapOf("sparse_infill_density" to "$modifierInfillPercent%"),
            )
        } else {
            ProjectVolumeConfig()
        }
}

internal data class OrcaAuxiliaryVolumeEditDraft(
    val volumeId: String,
    val scalePercent: Int = 100,
    val centerOffsetXmm: Float = 0f,
    val centerOffsetYmm: Float = 0f,
    val centerOffsetZmm: Float = 0f,
    val modifierInfillPercent: Int = 100,
) {
    init {
        require(volumeId.isNotBlank() && volumeId.length <= ProjectStore.MAX_ID_LENGTH) {
            "Auxiliary volume id is invalid"
        }
        require(scalePercent in MIN_AUXILIARY_EDIT_SCALE_PERCENT..MAX_AUXILIARY_EDIT_SCALE_PERCENT) {
            "Auxiliary volume scale is invalid"
        }
        require(
            listOf(centerOffsetXmm, centerOffsetYmm, centerOffsetZmm).all {
                it.isFinite() && kotlin.math.abs(it) <= ProjectStore.MAX_OFFSET_MM
            },
        ) { "Auxiliary volume position is invalid" }
        require(modifierInfillPercent in 0..100) { "Modifier infill is invalid" }
    }

    internal fun updatedConfig(volume: ProjectVolume): ProjectVolumeConfig =
        if (volume.role == ProjectVolumeRole.PARAMETER_MODIFIER) {
            ProjectVolumeConfig(
                volume.config.values +
                    ("sparse_infill_density" to "$modifierInfillPercent%"),
            )
        } else {
            volume.config
        }
}

internal suspend fun createOrcaPrimitive(
    primitive: OrcaPrimitive,
    sizeMm: Float,
    displayName: String,
    projectStore: ProjectStore,
    requestId: String = UUID.randomUUID().toString(),
): ProjectObject = withContext(Dispatchers.IO) {
    require(sizeMm.isFinite() && sizeMm in MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM) {
        "Shape size is invalid"
    }
    val staging = projectStore.createModelImportStaging()
    var installed: File? = null
    try {
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val generated = SlicerProcessClient.createPrimitive(primitive, sizeMm, staging, requestId)
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
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

internal suspend fun createOrcaAuxiliaryPrimitive(
    draft: OrcaAuxiliaryPrimitiveDraft,
    displayName: String,
    target: ProjectObject,
    projectStore: ProjectStore,
    requestId: String = UUID.randomUUID().toString(),
): ProjectVolume = withContext(Dispatchers.IO) {
    val targetGeometry = target.geometry()
    val targetCenter = targetGeometry.center
    val staging = projectStore.createModelImportStaging()
    var installed: File? = null
    try {
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val generated = SlicerProcessClient.createPrimitive(
            draft.primitive,
            draft.sizeMm,
            staging,
            requestId,
        )
        val generatedModel = inspectModel(generated.file.absolutePath)
        val positioned = File(staging, "positioned-${draft.primitive.wireName}.stl")
        val positionedMinZ = targetCenter[2] + draft.centerOffsetZmm -
            generatedModel.dimensions[2].toFloat() / 2f
        val transform = ModelTransform(
            offsetXmm = draft.centerOffsetXmm,
            offsetYmm = draft.centerOffsetYmm,
            offsetZmm = positionedMinZ,
        ).toJson(
            bedSizeX = 0f,
            bedSizeY = 0f,
            bedOriginX = targetCenter[0],
            bedOriginY = targetCenter[1],
        )
        val transformed = JSONObject(
            NativeEngine.transformStl(
                generated.file.absolutePath,
                positioned.absolutePath,
                transform,
            ),
        )
        check(transformed.optBoolean("ok")) {
            transformed.optString("error").ifBlank { "Shape placement failed" }
        }
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val fileName = "${displayName.substringBeforeLast('.').take(180).ifBlank { "volume" }}.stl"
        val model = projectStore.installImportedModel(positioned, fileName)
        installed = File(model.localPath)
        ProjectVolume(
            id = UUID.randomUUID().toString(),
            model = model,
            role = draft.role,
            config = draft.config,
        )
    } catch (failure: Throwable) {
        installed?.delete()
        throw failure
    } finally {
        staging.deleteRecursively()
    }
}

internal suspend fun editOrcaAuxiliaryVolume(
    draft: OrcaAuxiliaryVolumeEditDraft,
    target: ProjectObject,
    projectStore: ProjectStore,
    requestId: String = UUID.randomUUID().toString(),
): ProjectVolume = withContext(Dispatchers.IO) {
    val source = target.volumes.firstOrNull { it.id == draft.volumeId }
        ?: throw IllegalArgumentException("Auxiliary volume is unavailable")
    require(source.role != ProjectVolumeRole.MODEL_PART) {
        "Printable model parts cannot be edited as auxiliary volumes"
    }
    val targetCenter = target.geometry().center
    val scale = draft.scalePercent / 100f
    val scaledHeight = source.model.dimensions[2].toFloat() * scale
    val staging = projectStore.createModelImportStaging()
    var installed: File? = null
    try {
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val positioned = File(staging, "edited-volume.stl")
        val desiredMinZ = targetCenter[2] + draft.centerOffsetZmm - scaledHeight / 2f
        val transform = ModelTransform(
            offsetZmm = desiredMinZ,
            scale = scale,
        ).toJson(
            bedSizeX = 0f,
            bedSizeY = 0f,
            bedOriginX = targetCenter[0] + draft.centerOffsetXmm,
            bedOriginY = targetCenter[1] + draft.centerOffsetYmm,
        )
        val transformed = JSONObject(
            NativeEngine.transformStl(
                source.model.localPath,
                positioned.absolutePath,
                transform,
            ),
        )
        check(transformed.optBoolean("ok")) {
            transformed.optString("error").ifBlank { "Region update failed" }
        }
        if (SlicerProcessClient.projectRequestCancellationRequested(requestId)) {
            throw ProjectEditCancelledException()
        }
        val model = projectStore.installImportedModel(positioned, source.model.fileName)
        installed = File(model.localPath)
        source.copy(
            model = model,
            config = draft.updatedConfig(source),
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
internal const val MIN_AUXILIARY_EDIT_SCALE_PERCENT = 25
internal const val MAX_AUXILIARY_EDIT_SCALE_PERCENT = 400

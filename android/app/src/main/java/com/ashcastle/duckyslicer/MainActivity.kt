package com.ashcastle.duckyslicer

import android.content.Intent
import android.content.res.Resources
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val DuckyColors = darkColorScheme(
    primary = Color(0xFFF6C945),
    onPrimary = Color(0xFF202124),
    secondary = Color(0xFFD99A00),
    background = Color(0xFF1D1D1B),
    onBackground = Color(0xFFF7F5EF),
    surface = Color(0xFF2A2A27),
    onSurface = Color(0xFFF7F5EF),
)

internal const val GCODE_DOCUMENT_MIME_TYPE = "application/octet-stream"
internal const val THREE_MF_DOCUMENT_MIME_TYPE = "model/3mf"
internal const val STL_DOCUMENT_MIME_TYPE = "model/stl"
private const val DEFAULT_PROJECT_ARCHIVE_NAME = "DuckySlicer-project$PROJECT_ARCHIVE_FILE_EXTENSION"
private const val DEFAULT_THREE_MF_NAME = "DuckySlicer-model.3mf"
private const val DEFAULT_PROFILE_BUNDLE_NAME = "DuckySlicer-profiles$PROFILE_BUNDLE_FILE_EXTENSION"

data class ModelInfo(
    val fileName: String,
    val triangles: Int,
    val dimensions: List<Double>,
    val localPath: String,
    val minMm: List<Double>,
    val maxMm: List<Double>,
    val previewTriangles: FloatArray,
    val previewTriangleIndices: IntArray = IntArray(previewTriangles.size / 9) { it },
    /** A scene-budget fallback LOD. Editing and exact source-facet mapping keep previewTriangles. */
    val coarsePreviewTriangles: FloatArray = previewTriangles,
    /** A bounded visual-only LOD for nearby rendering. */
    val detailPreviewTriangles: FloatArray = previewTriangles,
) {
    /**
     * Exact unique preview vertices used by repeated support-direction queries such as tilted
     * bed placement. STL triangle streams duplicate shared vertices; retaining one occurrence
     * removes redundant transform work without simplifying or changing the visible mesh.
     */
    internal val placementVertices: FloatArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        uniqueModelVertices(previewTriangles)
    }

    companion object {
        fun fromNative(raw: FloatArray?, localPath: String): ModelInfo {
            checkNotNull(raw) { "model_invalid" }
            check(raw.size >= MODEL_PREVIEW_HEADER_FLOATS) { "model_invalid" }
            check(raw[0] == MODEL_PREVIEW_PAYLOAD_MAGIC) { "model_invalid" }
            check(raw[1] == MODEL_PREVIEW_PAYLOAD_VERSION) { "model_invalid" }
            val sourceTriangleCount = raw[2].exactModelIntegerOrNull()
            check(sourceTriangleCount != null && sourceTriangleCount in 1..MODEL_MAX_SOURCE_TRIANGLES) {
                "model_invalid"
            }
            val minMm = List(3) { index -> raw[index + 3].toDouble() }
            val maxMm = List(3) { index -> raw[index + 6].toDouble() }
            check(
                minMm.indices.all { axis ->
                    minMm[axis].isFinite() && maxMm[axis].isFinite() &&
                        minMm[axis] <= maxMm[axis] &&
                        kotlin.math.abs(minMm[axis]) <= MODEL_MAX_COORDINATE_ABS_MM &&
                        kotlin.math.abs(maxMm[axis]) <= MODEL_MAX_COORDINATE_ABS_MM
                },
            ) { "model_invalid" }
            val previewTriangleCount = raw[9].exactModelIntegerOrNull()
            check(previewTriangleCount != null && previewTriangleCount in 1..MODEL_MAX_PREVIEW_TRIANGLES) {
                "model_invalid"
            }
            val detailPreviewTriangleCount = raw[10].exactModelIntegerOrNull()
            check(
                detailPreviewTriangleCount != null &&
                    (detailPreviewTriangleCount == 0 ||
                        detailPreviewTriangleCount in
                        previewTriangleCount..MODEL_MAX_DETAIL_PREVIEW_TRIANGLES),
            ) { "model_invalid" }
            val coarsePreviewTriangleCount = raw[11].exactModelIntegerOrNull()
            check(
                coarsePreviewTriangleCount != null &&
                    (coarsePreviewTriangleCount == 0 ||
                        coarsePreviewTriangleCount in
                        1..minOf(previewTriangleCount, MODEL_MAX_COARSE_PREVIEW_TRIANGLES)),
            ) { "model_invalid" }
            val expectedFloats = MODEL_PREVIEW_HEADER_FLOATS.toLong() +
                previewTriangleCount.toLong() * MODEL_PREVIEW_FLOATS_PER_TRIANGLE +
                detailPreviewTriangleCount.toLong() * MODEL_PREVIEW_VERTEX_FLOATS +
                coarsePreviewTriangleCount.toLong() * MODEL_PREVIEW_VERTEX_FLOATS
            check(expectedFloats == raw.size.toLong()) { "model_invalid" }
            val vertexStart = MODEL_PREVIEW_HEADER_FLOATS
            val vertexEnd = vertexStart + previewTriangleCount * MODEL_PREVIEW_VERTEX_FLOATS
            val previewTriangles = raw.copyOfRange(vertexStart, vertexEnd)
            check(
                previewTriangles.all { value ->
                    value.isFinite() && kotlin.math.abs(value) <= MODEL_MAX_COORDINATE_ABS_MM
                },
            ) { "model_invalid" }
            val previewTriangleIndices = IntArray(previewTriangleCount) { index ->
                val sourceIndex = raw[vertexEnd + index].exactModelIntegerOrNull()
                check(sourceIndex != null && sourceIndex in 0 until sourceTriangleCount) {
                    "model_invalid"
                }
                sourceIndex
            }
            val detailVertexStart = vertexEnd + previewTriangleCount
            val detailVertexEnd =
                detailVertexStart + detailPreviewTriangleCount * MODEL_PREVIEW_VERTEX_FLOATS
            val detailPreviewTriangles = if (detailPreviewTriangleCount == 0) {
                previewTriangles
            } else {
                raw.copyOfRange(
                    detailVertexStart,
                    detailVertexEnd,
                ).also { values ->
                    check(
                        values.all { value ->
                            value.isFinite() &&
                                kotlin.math.abs(value) <= MODEL_MAX_COORDINATE_ABS_MM
                        },
                    ) { "model_invalid" }
                }
            }
            val coarsePreviewTriangles = if (coarsePreviewTriangleCount == 0) {
                previewTriangles
            } else {
                raw.copyOfRange(
                    detailVertexEnd,
                    detailVertexEnd + coarsePreviewTriangleCount * MODEL_PREVIEW_VERTEX_FLOATS,
                ).also { values ->
                    check(
                        values.all { value ->
                            value.isFinite() &&
                                kotlin.math.abs(value) <= MODEL_MAX_COORDINATE_ABS_MM
                        },
                    ) { "model_invalid" }
                }
            }
            return ModelInfo(
                fileName = java.io.File(localPath).name.ifBlank { "model.stl" },
                triangles = sourceTriangleCount,
                dimensions = List(3) { axis -> maxMm[axis] - minMm[axis] },
                localPath = localPath,
                minMm = minMm,
                maxMm = maxMm,
                previewTriangles = previewTriangles,
                previewTriangleIndices = previewTriangleIndices,
                coarsePreviewTriangles = coarsePreviewTriangles,
                detailPreviewTriangles = detailPreviewTriangles,
            )
        }

    }
}

private fun uniqueModelVertices(vertices: FloatArray): FloatArray {
    require(vertices.size % 3 == 0) { "model_invalid" }
    val seen = ModelVertexBitSet(vertices.size / 3)
    val unique = FloatArray(vertices.size)
    var source = 0
    var output = 0
    while (source + 2 < vertices.size) {
        val x = vertices[source]
        val y = vertices[source + 1]
        val z = vertices[source + 2]
        if (seen.add(
                if (x == 0f) 0 else x.toRawBits(),
                if (y == 0f) 0 else y.toRawBits(),
                if (z == 0f) 0 else z.toRawBits(),
            )
        ) {
            unique[output++] = x
            unique[output++] = y
            unique[output++] = z
        }
        source += 3
    }
    return if (output == vertices.size) vertices else unique.copyOf(output)
}

private class ModelVertexBitSet(maximumSize: Int) {
    private val capacity = run {
        val requested = (maximumSize.toLong() * 3L / 2L + 1L).coerceAtLeast(2L)
        var value = 2
        while (value.toLong() < requested) value = value shl 1
        value
    }
    private val mask = capacity - 1
    private val occupied = BooleanArray(capacity)
    private val xs = IntArray(capacity)
    private val ys = IntArray(capacity)
    private val zs = IntArray(capacity)

    fun add(x: Int, y: Int, z: Int): Boolean {
        var hash = x * -0x7a143595 xor y * -0x3d4d51cb xor z * 0x165667b1
        hash = hash xor (hash ushr 16)
        var index = hash and mask
        while (occupied[index]) {
            if (xs[index] == x && ys[index] == y && zs[index] == z) return false
            index = (index + 1) and mask
        }
        occupied[index] = true
        xs[index] = x
        ys[index] = y
        zs[index] = z
        return true
    }
}

private fun Float.exactModelIntegerOrNull(): Int? {
    if (!isFinite() || this < 0f || this > MODEL_MAX_EXACT_FLOAT_INTEGER.toFloat()) return null
    val value = toInt()
    return value.takeIf { it.toFloat() == this }
}

private const val MODEL_PREVIEW_PAYLOAD_MAGIC = 17_492f
private const val MODEL_PREVIEW_PAYLOAD_VERSION = 3f
private const val MODEL_PREVIEW_HEADER_FLOATS = 12
private const val MODEL_PREVIEW_VERTEX_FLOATS = 9
private const val MODEL_PREVIEW_FLOATS_PER_TRIANGLE = 10
private const val MODEL_MAX_PREVIEW_TRIANGLES = 12_000
private const val MODEL_MAX_COARSE_PREVIEW_TRIANGLES = 2_000
private const val MODEL_MAX_DETAIL_PREVIEW_TRIANGLES = 48_000
private const val MODEL_MAX_SOURCE_TRIANGLES = 11_000_000
private const val MODEL_MAX_EXACT_FLOAT_INTEGER = 16_777_216
private const val MODEL_MAX_COORDINATE_ABS_MM = 1_000_000.0

class MainActivity : ComponentActivity() {
    private lateinit var appSettingsModel: AppSettingsViewModel
    private lateinit var externalModelModel: ExternalModelRequestViewModel
    private lateinit var externalProfileModel: ExternalProfileRequestViewModel
    private lateinit var externalProjectModel: ExternalProjectRequestViewModel
    private lateinit var profileLibraryModel: ProfileLibraryViewModel
    private lateinit var projectTransferModel: ProjectTransferViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sliceOperationModel = ViewModelProvider(this)[SliceOperationViewModel::class.java]
        val plateSliceBatchModel = ViewModelProvider(this)[PlateSliceBatchViewModel::class.java]
        val remoteOperationModel = ViewModelProvider(this)[RemoteOperationViewModel::class.java]
        profileLibraryModel = ViewModelProvider(this)[ProfileLibraryViewModel::class.java]
        appSettingsModel = ViewModelProvider(this)[AppSettingsViewModel::class.java]
        val gcodeExportModel = ViewModelProvider(this)[GcodeExportViewModel::class.java]
        val supportReportExportModel =
            ViewModelProvider(this)[SupportReportExportViewModel::class.java]
        externalModelModel = ViewModelProvider(this)[ExternalModelRequestViewModel::class.java]
        externalProfileModel = ViewModelProvider(this)[ExternalProfileRequestViewModel::class.java]
        externalProjectModel = ViewModelProvider(this)[ExternalProjectRequestViewModel::class.java]
        projectTransferModel = ViewModelProvider(this)[ProjectTransferViewModel::class.java]
        if (savedInstanceState == null) {
            externalModelModel.enqueue(intent)
            externalProfileModel.enqueue(intent)
            externalProjectModel.enqueue(intent)
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = DuckyColors) {
                val externalModelRequest by
                    externalModelModel.request.collectAsStateWithLifecycle()
                val externalProjectRequest by
                    externalProjectModel.request.collectAsStateWithLifecycle()
                val externalProfileRequest by
                    externalProfileModel.request.collectAsStateWithLifecycle()
                DuckySlicerScreen(
                    sliceOperationModel = sliceOperationModel,
                    plateSliceBatchModel = plateSliceBatchModel,
                    remoteOperationModel = remoteOperationModel,
                    profileLibraryModel = profileLibraryModel,
                    appSettingsModel = appSettingsModel,
                    gcodeExportModel = gcodeExportModel,
                    supportReportExportModel = supportReportExportModel,
                    projectTransferModel = projectTransferModel,
                    externalModelRequest = externalModelRequest,
                    onExternalModelRequestStarted = externalModelModel::markStarted,
                    onExternalModelRequestConsumed = externalModelModel::consume,
                    onExternalModelRequestDiscarded = externalModelModel::discardUnstarted,
                    externalProfileRequest = externalProfileRequest,
                    onExternalProfileRequestStarted = externalProfileModel::markStarted,
                    onExternalProfileRequestConsumed = externalProfileModel::consume,
                    externalProjectRequest = externalProjectRequest,
                    onExternalProjectRequestStarted = externalProjectModel::markStarted,
                    onExternalProjectRequestConsumed = externalProjectModel::consume,
                    onExternalProjectRequestDiscarded = externalProjectModel::discardUnstarted,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalModelModel.enqueue(intent)
        externalProfileModel.enqueue(intent)
        externalProjectModel.enqueue(intent)
    }

    override fun onStop() {
        projectTransferModel.flushPersistence()
        profileLibraryModel.flushRecentPersistence()
        appSettingsModel.flushPersistence()
        super.onStop()
    }
}

private fun duplicateActivePlate(
    projectTransferModel: ProjectTransferViewModel,
    sliceOperationModel: SliceOperationViewModel,
    remoteOperationModel: RemoteOperationViewModel,
): Boolean {
    val current = projectTransferModel.state.value.history
    val snapshot = current.current
    val source = snapshot.activePlate
    val sourceVolumeCount = source.objects.sumOf { it.volumes.size }
    if (
        snapshot.plates.size >= MAX_PROJECT_PLATES ||
        snapshot.allObjects.size + source.objects.size > ProjectStore.MAX_PROJECT_OBJECTS ||
        snapshot.allObjects.sumOf { it.volumes.size } + sourceVolumeCount >
        ProjectStore.MAX_PROJECT_VOLUMES
    ) {
        return false
    }

    val next = current.duplicateSelectedPlate(
        newPlateId = UUID.randomUUID().toString(),
        newObjectIds = List(source.objects.size) { UUID.randomUUID().toString() },
    )
    if (!projectTransferModel.updateHistory(current, next)) return false
    sliceOperationModel.clearCompleted()
    remoteOperationModel.invalidateUpload()
    return true
}

private fun selectProjectPlate(
    plateId: String,
    projectTransferModel: ProjectTransferViewModel,
    sliceOperationModel: SliceOperationViewModel,
    remoteOperationModel: RemoteOperationViewModel,
): Boolean {
    val current = projectTransferModel.state.value.history
    val next = current.selectPlate(plateId)
    if (!projectTransferModel.updateHistory(current, next)) return false
    sliceOperationModel.clearCompleted()
    remoteOperationModel.invalidateUpload()
    return true
}

private fun addEmptyProjectPlate(
    projectTransferModel: ProjectTransferViewModel,
    sliceOperationModel: SliceOperationViewModel,
    remoteOperationModel: RemoteOperationViewModel,
): Boolean {
    val current = projectTransferModel.state.value.history
    val next = current.addPlate(UUID.randomUUID().toString())
    if (!projectTransferModel.updateHistory(current, next)) return false
    sliceOperationModel.clearCompleted()
    remoteOperationModel.invalidateUpload()
    return true
}

private fun saveProjectDocument(
    projectTransferModel: ProjectTransferViewModel,
    documentPicker: ActivityResultLauncher<String>,
    saveAs: Boolean,
) {
    val current = projectTransferModel.state.value
    if (saveAs) {
        documentPicker.launch(
            current.linkedDocument?.displayName ?: DEFAULT_PROJECT_ARCHIVE_NAME,
        )
        return
    }
    val started = projectTransferModel.saveLinkedProject(
        current.history.current,
        current.plateOptions,
    )
    val latest = projectTransferModel.state.value
    if (
        !started && !latest.busy &&
        latest.completion == null && latest.editCompletion == null
    ) {
        documentPicker.launch(
            current.linkedDocument?.displayName ?: DEFAULT_PROJECT_ARCHIVE_NAME,
        )
    }
}

private fun projectSaveAction(
    projectTransferModel: ProjectTransferViewModel,
    documentPicker: ActivityResultLauncher<String>,
): (Boolean) -> Unit = { saveAs ->
    saveProjectDocument(projectTransferModel, documentPicker, saveAs)
}

private class FacetPaintActions(
    private val projectTransferModel: ProjectTransferViewModel,
    private val onPreviewChanged: () -> Unit,
) {
    fun previewSupport(
        objectId: String,
        volumeId: String,
        targets: List<FacetPaintTarget>,
        state: SupportPaintState?,
    ) {
        val current = projectTransferModel.state.value.history
        val projectObject = current.current.objects.firstOrNull { it.id == objectId }
        val volume = projectObject?.volumes?.firstOrNull { it.id == volumeId }
        if (
            volume?.role?.acceptsFacetPaint == true &&
            targets.isNotEmpty() &&
            targets.all { it.facetIndex in 0 until volume.model.triangles }
        ) {
            val previousAnnotation = volume.orcaFacetAnnotations.support
            val nextAnnotation = previousAnnotation.paintAll(
                targets,
                state?.code ?: 0,
            ) { facetIndex ->
                volume.supportPaint.facets[facetIndex]?.code ?: 0
            }
            val nextPaint = exactPaintFacetsToClear(
                previousAnnotation,
                nextAnnotation,
                targets,
            ).fold(volume.supportPaint) { paint, facetIndex ->
                paint.paint(facetIndex, null)
            }
            val nextHistory = current.updateExactSupportPaint(
                objectId,
                volumeId,
                nextPaint,
                nextAnnotation,
                recordHistory = false,
            )
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                onPreviewChanged()
            }
        }
    }

    fun commitSupport(
        objectId: String,
        volumeId: String,
        previousPaint: SupportPaint,
        previousAnnotation: OrcaFacetAnnotation,
    ) {
        val current = projectTransferModel.state.value.history
        projectTransferModel.updateHistory(
            current,
            current.commitExactSupportPaint(
                objectId,
                volumeId,
                previousPaint,
                previousAnnotation,
            ),
        )
    }

    fun previewSeam(
        objectId: String,
        volumeId: String,
        targets: List<FacetPaintTarget>,
        state: SeamPaintState?,
    ) {
        val current = projectTransferModel.state.value.history
        val projectObject = current.current.objects.firstOrNull { it.id == objectId }
        val volume = projectObject?.volumes?.firstOrNull { it.id == volumeId }
        if (
            volume?.role?.acceptsFacetPaint == true &&
            targets.isNotEmpty() &&
            targets.all { it.facetIndex in 0 until volume.model.triangles }
        ) {
            val previousAnnotation = volume.orcaFacetAnnotations.seam
            val nextAnnotation = previousAnnotation.paintAll(
                targets,
                state?.code ?: 0,
            ) { facetIndex ->
                volume.seamPaint.facets[facetIndex]?.code ?: 0
            }
            val nextPaint = exactPaintFacetsToClear(
                previousAnnotation,
                nextAnnotation,
                targets,
            ).fold(volume.seamPaint) { paint, facetIndex ->
                paint.paint(facetIndex, null)
            }
            val nextHistory = current.updateExactSeamPaint(
                objectId,
                volumeId,
                nextPaint,
                nextAnnotation,
                recordHistory = false,
            )
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                onPreviewChanged()
            }
        }
    }

    fun commitSeam(
        objectId: String,
        volumeId: String,
        previousPaint: SeamPaint,
        previousAnnotation: OrcaFacetAnnotation,
    ) {
        val current = projectTransferModel.state.value.history
        projectTransferModel.updateHistory(
            current,
            current.commitExactSeamPaint(
                objectId,
                volumeId,
                previousPaint,
                previousAnnotation,
            ),
        )
    }

    fun previewMultiColor(
        objectId: String,
        volumeId: String,
        targets: List<FacetPaintTarget>,
        slot: Int?,
    ) {
        val session = projectTransferModel.state.value
        val current = session.history
        val projectObject = current.current.objects.firstOrNull { it.id == objectId }
        val volume = projectObject?.volumes?.firstOrNull { it.id == volumeId }
        val availableSlots = session.sliceOptions.resolvedFilamentSlots().indices
        if (
            volume != null &&
            volume.role.acceptsFacetPaint &&
            targets.isNotEmpty() &&
            targets.all { it.facetIndex in 0 until volume.model.triangles } &&
            (slot == null || slot in availableSlots)
        ) {
            val previousAnnotation = volume.orcaFacetAnnotations.multiColor
            val nextAnnotation = previousAnnotation.paintAll(
                targets,
                slot?.plus(1) ?: 0,
            ) { facetIndex ->
                volume.multiColorPaint.facets[facetIndex]?.plus(1) ?: 0
            }
            val nextPaint = exactPaintFacetsToClear(
                previousAnnotation,
                nextAnnotation,
                targets,
            ).fold(volume.multiColorPaint) { paint, facetIndex ->
                paint.paint(facetIndex, null)
            }
            val nextHistory = current.updateExactMultiColorPaint(
                objectId,
                volumeId,
                nextPaint,
                nextAnnotation,
                recordHistory = false,
            )
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                onPreviewChanged()
            }
        }
    }

    fun commitMultiColor(
        objectId: String,
        volumeId: String,
        previousPaint: MultiColorPaint,
        previousAnnotation: OrcaFacetAnnotation,
    ) {
        val current = projectTransferModel.state.value.history
        projectTransferModel.updateHistory(
            current,
            current.commitExactMultiColorPaint(
                objectId,
                volumeId,
                previousPaint,
                previousAnnotation,
            ),
        )
    }
}

@Composable
private fun rememberProjectDocumentCreator(
    projectTransferModel: ProjectTransferViewModel,
    enabled: Boolean,
    onExportStarted: () -> Unit,
): ActivityResultLauncher<String> = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE),
) { uri ->
    if (uri != null && enabled) {
        val state = projectTransferModel.state.value
        if (
            projectTransferModel.exportProject(
                uri,
                state.history.current,
                state.plateOptions,
            )
        ) {
            onExportStarted()
        }
    }
}

@Composable
private fun rememberModelDocumentCreator(
    projectTransferModel: ProjectTransferViewModel,
    enabled: Boolean,
    onExportStarted: () -> Unit,
): ActivityResultLauncher<String> = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(THREE_MF_DOCUMENT_MIME_TYPE),
) { uri ->
    if (uri != null && enabled) {
        val state = projectTransferModel.state.value
        val snapshot = state.history.current
        val options = state.plateOptions[snapshot.selectedPlateId] ?: state.sliceOptions
        if (projectTransferModel.exportThreeMf(uri, snapshot, options)) {
            onExportStarted()
        }
    }
}

@Composable
private fun ExternalProjectImportEffect(
    request: ExternalProjectRequest?,
    enabled: Boolean,
    replacementConfirmationRequired: Boolean,
    projectTransferModel: ProjectTransferViewModel,
    onImport: (Uri) -> Boolean,
    onRequestStarted: (Long, Long) -> Boolean,
    onConfirmationRequired: (ExternalProjectRequest) -> Unit,
) {
    LaunchedEffect(
        request?.id,
        request?.startedOperationId,
        enabled,
        replacementConfirmationRequired,
    ) {
        val pending = request ?: return@LaunchedEffect
        if (pending.startedOperationId != null || !enabled) return@LaunchedEffect
        if (!replacementConfirmationRequired) {
            startExternalProjectImport(
                pending,
                projectTransferModel,
                onImport,
                onRequestStarted,
            )
        } else {
            onConfirmationRequired(pending)
        }
    }
}

@Composable
private fun DuckySlicerScreen(
    sliceOperationModel: SliceOperationViewModel,
    plateSliceBatchModel: PlateSliceBatchViewModel,
    remoteOperationModel: RemoteOperationViewModel,
    profileLibraryModel: ProfileLibraryViewModel,
    appSettingsModel: AppSettingsViewModel,
    gcodeExportModel: GcodeExportViewModel,
    supportReportExportModel: SupportReportExportViewModel,
    projectTransferModel: ProjectTransferViewModel,
    externalModelRequest: ExternalModelRequest?,
    onExternalModelRequestStarted: (Long, Long) -> Boolean,
    onExternalModelRequestConsumed: (Long, Long) -> Boolean,
    onExternalModelRequestDiscarded: (Long) -> Boolean,
    externalProfileRequest: ExternalProfileRequest?,
    onExternalProfileRequestStarted: (Long, Long) -> Boolean,
    onExternalProfileRequestConsumed: (Long, Long) -> Boolean,
    externalProjectRequest: ExternalProjectRequest?,
    onExternalProjectRequestStarted: (Long, Long) -> Boolean,
    onExternalProjectRequestConsumed: (Long, Long) -> Boolean,
    onExternalProjectRequestDiscarded: (Long) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resources = LocalResources.current
    val modelReadError = resources.getString(R.string.model_read_error)
    val shapeError = resources.getString(R.string.shape_error)
    val layOnFaceDone = resources.getString(R.string.lay_on_face_done)
    val layOnFaceError = resources.getString(R.string.lay_on_face_error)
    val splitError = resources.getString(R.string.split_error)
    val splitPartsError = resources.getString(R.string.split_parts_error)
    val cutError = resources.getString(R.string.cut_error)
    val sliceError = resources.getString(R.string.slice_error)
    val sliceCanceledNotice = resources.getString(R.string.slice_canceled)
    val allPlatesSlicedNotice = resources.getString(R.string.all_plates_sliced)
    val profileSavedNotice = resources.getString(R.string.profile_saved)
    val profileSaveError = resources.getString(R.string.profile_save_error)
    val profileDeletedNotice = resources.getString(R.string.profile_deleted)
    val profileDeleteError = resources.getString(R.string.profile_delete_error)
    val filamentSlotUnavailable = resources.getString(R.string.filament_slot_unavailable)
    val newProjectStartedNotice = resources.getString(R.string.new_project_started)
    val recentProjectUnavailable = resources.getString(R.string.recent_project_unavailable)
    val savedDataUnavailable = resources.getString(R.string.saved_data_unavailable)
    val previewError = resources.getString(R.string.preview_error)
    val remoteSavedNotice = resources.getString(R.string.device_saved)
    val remoteDeletedNotice = resources.getString(R.string.device_deleted)
    val remoteConnectedNotice = resources.getString(R.string.device_connected)
    val remoteUploadNotice = resources.getString(R.string.gcode_sent)
    val remoteStartedNotice = resources.getString(R.string.print_started)
    val remotePausedNotice = resources.getString(R.string.print_paused)
    val remoteResumedNotice = resources.getString(R.string.print_resumed)
    val remoteCanceledNotice = resources.getString(R.string.print_canceled)
    val remoteUploadCanceledNotice = resources.getString(R.string.upload_canceled)
    val remoteRequestCanceledNotice = resources.getString(R.string.remote_request_canceled)
    val remoteConnectionError = resources.getString(R.string.device_connection_error)
    val remoteUnauthorizedError = resources.getString(R.string.device_access_denied)
    val remoteCommandError = resources.getString(R.string.device_command_error)
    val remoteSaveError = resources.getString(R.string.device_save_error)

    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val acceptProfileSave: (Boolean) -> Unit = { started ->
        if (!started) {
            error = profileSaveError
            notice = null
        }
    }
    val profileActions = ProfileLibraryActions(
        model = profileLibraryModel,
        sessionRevision = { projectTransferModel.state.value.sessionRevision },
        accept = acceptProfileSave,
    )
    var externalProjectConfirmation by remember { mutableStateOf<ExternalProjectRequest?>(null) }
    var plateSliceResults by rememberSaveable { mutableStateOf(PlateSliceResults()) }
    var pendingGcodeExport by rememberSaveable { mutableStateOf<PlateSliceResult?>(null) }
    var pendingGcodeBatch by rememberSaveable { mutableStateOf<GcodeExportBatch?>(null) }
    var pendingStlExportObjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.SLICE) }
    var layerPreview by remember { mutableStateOf<GcodeLayerPreview?>(null) }
    var stalePreviewResult by remember { mutableStateOf<PlateSliceResult?>(null) }
    val sliceOperationState by sliceOperationModel.state.collectAsStateWithLifecycle()
    val plateSliceBatchState by plateSliceBatchModel.state.collectAsStateWithLifecycle()
    val operationSlicing = sliceOperationState.slicing
    val slicing = operationSlicing || plateSliceBatchState.active
    val sliceCancellationRequested = sliceOperationState.cancellationRequested ||
        plateSliceBatchState.cancellationRequested
    val sliceProgress = if (plateSliceBatchState.active) {
        val currentProgress = if (plateSliceBatchState.currentPlateId == null) {
            0
        } else {
            sliceOperationState.progress
        }
        (
            (plateSliceBatchState.completedCount * 100 + currentProgress) /
                plateSliceBatchState.plateIds.size
            ).coerceIn(0, 100)
    } else {
        sliceOperationState.progress
    }
    val plateSliceBatchProgress = plateSliceBatchState.currentNumber?.let { current ->
        PlateSliceBatchProgress(current, plateSliceBatchState.plateIds.size)
    }
    val previewLoading = sliceOperationState.previewLoading
    val projectTransferState by projectTransferModel.state.collectAsStateWithLifecycle()
    val projectTransferBusy = projectTransferState.busy ||
        projectTransferState.completion != null || projectTransferState.editCompletion != null
    val projectEditActive = projectTransferState.activeEdit != null
    val projectEditCancellationRequested =
        projectTransferState.activeEdit?.cancellationRequested == true
    val projectImporting =
        projectTransferState.activeTransferDirection == ProjectTransferDirection.IMPORT
    val projectExporting =
        projectTransferState.activeTransferDirection == ProjectTransferDirection.EXPORT
    val projectTransferCancellationRequested =
        projectTransferState.transferCancellationRequested
    val visibleEdit = projectTransferState.activeEdit?.kind
        ?: projectTransferState.editCompletion?.kind
    val importing = visibleEdit == ProjectEditKind.MODEL_IMPORT ||
        visibleEdit == ProjectEditKind.PRIMITIVE ||
        visibleEdit == ProjectEditKind.AUXILIARY_VOLUME
    val autoLaying = visibleEdit == ProjectEditKind.AUTO_LAY
    val arranging = visibleEdit == ProjectEditKind.ARRANGE
    val splitting = visibleEdit == ProjectEditKind.SPLIT ||
        visibleEdit == ProjectEditKind.SPLIT_PARTS
    val cutting = visibleEdit == ProjectEditKind.CUT
    val simplifying = visibleEdit == ProjectEditKind.SIMPLIFY
    val projectFileBusy = !projectTransferState.restored ||
        (projectTransferBusy && visibleEdit == null)
    val projectHistory = projectTransferState.history
    val projectRestored = projectTransferState.restored
    val remoteOperationState by remoteOperationModel.state.collectAsStateWithLifecycle()
    val profileLibraryState by profileLibraryModel.state.collectAsStateWithLifecycle()
    val appSettingsState by appSettingsModel.state.collectAsStateWithLifecycle()
    val gcodeExportState by gcodeExportModel.state.collectAsStateWithLifecycle()
    val supportReportExportState by
        supportReportExportModel.state.collectAsStateWithLifecycle()
    val exportingGcode = gcodeExportState.busy
    val gcodeExportCancellationRequested = gcodeExportState.cancellationRequested
    val sliceOptions = projectTransferState.sliceOptions
    val projectPlates = projectHistory.current.plates
    val selectedPlateId = projectHistory.current.selectedPlateId
    val projectObjects = projectHistory.current.objects
    val selectedProjectObject = projectHistory.current.selectedObject
    val sliceOutcome = plateSliceResults.outcomeFor(selectedPlateId)
    val gcodeExportBatch = plateSliceResults.completeExportBatch(projectHistory.current)
    val modelTransform = selectedProjectObject?.transform ?: ModelTransform()
    val profileCatalog = profileLibraryState.catalog
    val profileRecents = profileLibraryState.recents
    val profileRecentsLoaded = profileLibraryState.recentsLoaded
    val profileBusy = profileLibraryState.busy || profileLibraryState.completion != null ||
        profileLibraryState.deletionCompletion != null ||
        profileLibraryState.transferCompletion != null
    val profileTransferDirection = profileLibraryState.activeTransferDirection
    val profileTransferCancellationRequested =
        profileLibraryState.transferCancellationRequested
    val supportEvents = remember(context.applicationContext) {
        SupportEventJournal(context.applicationContext)
    }
    val appSettings = appSettingsState.settings
    val remoteDevices = remoteOperationState.profiles
    val selectedRemoteDeviceId = remoteOperationState.selectedProfileId
    val remoteStatus = remoteOperationState.statusFor(selectedRemoteDeviceId)
    val remoteUpload = remoteOperationState.uploadFor(selectedRemoteDeviceId)
    val remoteUploadProgress = remoteOperationState.progressFor(selectedRemoteDeviceId)
    val remoteRequestActive = remoteOperationState.networkRequestActiveFor(selectedRemoteDeviceId)
    val remoteUploadActive = remoteOperationState.uploadActiveFor(selectedRemoteDeviceId)
    val remoteRequestCancellationRequested =
        remoteOperationState.requestCancellationRequestedFor(selectedRemoteDeviceId)
    val remoteOperationMessage = remoteOperationState.messageFor(selectedRemoteDeviceId)
    val remoteMessage = when (remoteOperationMessage) {
        RemoteOperationMessage.CONNECTED -> remoteConnectedNotice
        RemoteOperationMessage.UPLOADED -> remoteUploadNotice
        RemoteOperationMessage.STARTED -> remoteStartedNotice
        RemoteOperationMessage.PAUSED -> remotePausedNotice
        RemoteOperationMessage.RESUMED -> remoteResumedNotice
        RemoteOperationMessage.CANCELED -> remoteCanceledNotice
        RemoteOperationMessage.UPLOAD_CANCELED -> remoteUploadCanceledNotice
        RemoteOperationMessage.REQUEST_CANCELED -> remoteRequestCanceledNotice
        RemoteOperationMessage.PROFILE_SAVED -> remoteSavedNotice
        RemoteOperationMessage.PROFILE_DELETED -> remoteDeletedNotice
        RemoteOperationMessage.ACCESS_DENIED -> remoteUnauthorizedError
        RemoteOperationMessage.CONNECTION_FAILED -> remoteConnectionError
        RemoteOperationMessage.COMMAND_FAILED -> remoteCommandError
        RemoteOperationMessage.PROFILE_SAVE_FAILED -> remoteSaveError
        RemoteOperationMessage.STORAGE_UNAVAILABLE -> savedDataUnavailable
        null -> null
    }
    val remoteMessageIsError = remoteOperationMessage?.isError ?: false
    val remoteBusy = remoteOperationState.busy

    ReportDrawnWhen {
        initialWorkspaceReady(
            projectRestored = projectTransferState.restored,
            profileCatalogLoaded = profileLibraryState.catalogLoaded,
            profileRecentsLoaded = profileLibraryState.recentsLoaded,
        )
    }

    fun clearCompletedSlice(plateId: String = selectedPlateId) {
        sliceOperationModel.clearCompleted()
        plateSliceResults = plateSliceResults.clear(plateId)
        layerPreview = null
        stalePreviewResult = null
        remoteOperationModel.invalidateUpload()
    }

    fun clearAllCompletedSlices() {
        sliceOperationModel.clearCompleted()
        plateSliceResults = PlateSliceResults()
        pendingGcodeExport = null
        pendingGcodeBatch = null
        layerPreview = null
        stalePreviewResult = null
        remoteOperationModel.invalidateUpload()
    }

    val facetPaintActions = FacetPaintActions(projectTransferModel) {
        clearCompletedSlice()
        notice = null
    }

    fun invalidateSliceAfterPreviewEdit() {
        stalePreviewResult = plateSliceResults.resultFor(selectedPlateId) ?: stalePreviewResult
        sliceOperationModel.clearCompleted()
        plateSliceResults = plateSliceResults.clear(selectedPlateId)
        remoteOperationModel.invalidateUpload()
    }

    ProjectTransferCompletionEffect(
        completion = projectTransferState.completion,
        externalRequest = externalProjectRequest,
        supportEvents = supportEvents,
        onExternalConsumed = onExternalProjectRequestConsumed,
        onConsumeCompletion = projectTransferModel::consumeCompletion,
        onImported = ::clearAllCompletedSlices,
        onDismissExternalConfirmation = { externalProjectConfirmation = null },
        onPresentation = { nextNotice, nextError ->
            notice = nextNotice
            error = nextError
        },
    )

    ProjectEditCompletionEffect(
        completion = projectTransferState.editCompletion,
        externalModelRequest = externalModelRequest,
        onExternalModelRequestConsumed = onExternalModelRequestConsumed,
        onConsumeCompletion = projectTransferModel::consumeEditCompletion,
        onSessionChanged = ::clearCompletedSlice,
        onTabSelected = { selectedTab = it },
        onPresentation = { nextNotice, nextError ->
            notice = nextNotice
            error = nextError
        },
    )

    SliceResultLifecycleEffects(
        filesDirectory = context.filesDir,
        selectedOutcome = sliceOutcome,
        selectedTab = selectedTab,
        selectedPlateId = selectedPlateId,
        projectPlateIds = projectPlates.mapTo(HashSet(), ProjectPlate::id),
        results = plateSliceResults,
        operationState = sliceOperationState,
        batchState = plateSliceBatchState,
        operationModel = sliceOperationModel,
        batchModel = plateSliceBatchModel,
        supportEvents = supportEvents,
        messages = SliceLifecycleMessages(
            canceled = sliceCanceledNotice,
            failed = sliceError,
            previewFailed = previewError,
            allPlatesCompleted = allPlatesSlicedNotice,
        ),
        onInvalidSelectedResult = ::clearCompletedSlice,
        onResultsChanged = { plateSliceResults = it },
        onPreviewChanged = { layerPreview = it },
        onClearStalePreview = { stalePreviewResult = null },
        onTabSelected = { selectedTab = it },
        onPresentation = { nextNotice, nextError ->
            notice = nextNotice
            error = nextError
        },
        onRemoteResultInvalidated = remoteOperationModel::invalidateUpload,
    )
    val keepScreenAwake = appSettings.keepScreenAwakeWhileWorking &&
        (importing || projectTransferBusy || autoLaying || arranging || splitting || cutting || slicing ||
            previewLoading || exportingGcode || remoteBusy || profileBusy)
    WorkspaceStatusEffects(
        gcodeExportState = gcodeExportState,
        gcodeExportModel = gcodeExportModel,
        persistenceMessage = projectTransferState.persistenceMessage,
        projectRestored = projectRestored,
        profileRecentsLoaded = profileRecentsLoaded,
        sliceOptions = sliceOptions,
        profileLibraryState = profileLibraryState,
        profileLibraryModel = profileLibraryModel,
        remoteProfilesLoaded = remoteOperationState.profilesLoaded,
        remoteStorageUnavailable = remoteOperationState.storageUnavailable,
        keepScreenAwake = keepScreenAwake,
        onPresentation = { nextNotice, nextError ->
            notice = nextNotice
            error = nextError
        },
    )
    fun applyOptions(options: SliceOptions) {
        val session = projectTransferModel.state.value
        val previous = session.sliceOptions
        if (options != previous) {
            val nextHistory = session.history.constrainFilamentSlots(
                options.resolvedFilamentSlots().size,
            )
            if (
                projectTransferModel.updateSession(
                    expectedHistory = session.history,
                    nextHistory = nextHistory,
                    expectedOptions = previous,
                    nextOptions = options,
                )
            ) {
                profileLibraryModel.recordSelection(options)
                clearCompletedSlice()
                notice = null
            }
        }
    }

    LaunchedEffect(profileLibraryState.completion?.id) {
        val completion = profileLibraryState.completion ?: return@LaunchedEffect
        val session = projectTransferModel.state.value
        completion.optionsForSession(session.sessionRevision)?.let(::applyOptions)
        notice = profileSavedNotice
        error = null
        profileLibraryModel.consumeCompletion(completion.id)
    }

    LaunchedEffect(profileLibraryState.deletionCompletion?.id) {
        val completion = profileLibraryState.deletionCompletion ?: return@LaunchedEffect
        notice = profileDeletedNotice
        error = null
        profileLibraryModel.consumeDeletionCompletion(completion.id)
    }

    ProfileTransferCompletionEffect(
        completion = profileLibraryState.transferCompletion,
        externalRequest = externalProfileRequest,
        onExternalConsumed = onExternalProfileRequestConsumed,
        onConsumeCompletion = profileLibraryModel::consumeTransferCompletion,
        onPresentation = { nextNotice, nextError ->
            notice = nextNotice
            error = nextError
        },
    )

    fun applyModelTransform(transform: ModelTransform, recordHistory: Boolean = true) {
        val current = projectTransferModel.state.value.history
        val nextHistory = current.updateSelectedTransform(transform, recordHistory)
        if (nextHistory != current && projectTransferModel.updateHistory(current, nextHistory)) {
            clearCompletedSlice()
            notice = null
        }
    }

    fun autoLaySelectedModel() {
        if (
            projectTransferBusy || importing || slicing || previewLoading ||
            projectHistory.current.selectedObject == null
        ) return
        if (projectTransferModel.autoLaySelectedModel()) {
            error = null
            notice = null
        }
    }

    fun laySelectedFaceOnBed(objectId: String, triangle: FloatArray): Boolean {
        if (projectTransferBusy || importing || slicing || previewLoading) return false
        val current = projectTransferModel.state.value.history
        val target = current.current.objects.firstOrNull { it.id == objectId } ?: return false
        return runCatching {
            val transform = target.withFaceOnBed(triangle)
            val nextHistory = current.updateTransform(objectId, transform)
            val applied = nextHistory == current ||
                projectTransferModel.updateHistory(current, nextHistory)
            check(applied) { "Selected face transform was not applied" }
            nextHistory != current
        }.fold(
            onSuccess = { changed ->
                if (changed) {
                    clearCompletedSlice()
                }
                notice = layOnFaceDone
                error = null
                true
            },
            onFailure = { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Place on face failed", failure)
                supportEvents.record(SupportEvent.LAY_ON_FACE_FAILED)
                error = layOnFaceError
                notice = null
                false
            },
        )
    }

    fun arrangeProjectObjects() {
        if (
            projectHistory.current.objects.size < 2 || projectTransferBusy || importing ||
            slicing || previewLoading
        ) return
        if (projectTransferModel.arrangeProjectObjects()) {
            clearCompletedSlice()
            error = null
            notice = null
        }
    }

    fun splitSelectedModel() {
        if (
            projectHistory.current.selectedObject == null || projectTransferBusy || importing ||
            slicing || previewLoading
        ) return
        val maximumObjects =
            ProjectStore.MAX_PROJECT_OBJECTS - projectHistory.current.allObjects.size + 1
        if (maximumObjects < 2) {
            error = splitError
            notice = null
            return
        }
        if (projectTransferModel.splitSelectedModel()) {
            clearCompletedSlice()
            error = null
            notice = null
        }
    }

    fun splitSelectedVolume(volumeId: String) {
        if (
            projectHistory.current.selectedObject?.volumes?.none { it.id == volumeId } != false ||
            projectTransferBusy || importing || slicing || previewLoading
        ) return
        if (projectTransferModel.splitSelectedVolume(volumeId)) {
            clearCompletedSlice()
            error = null
            notice = null
        } else {
            error = splitPartsError
            notice = null
        }
    }

    fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean) {
        if (
            projectHistory.current.selectedObject == null || projectTransferBusy || importing ||
            slicing || previewLoading
        ) return
        val maximumObjects =
            ProjectStore.MAX_PROJECT_OBJECTS - projectHistory.current.allObjects.size + 1
        if (maximumObjects < 2) {
            error = cutError
            notice = null
            return
        }
        if (projectTransferModel.cutSelectedModel(heightRatio, placeOnCut)) {
            clearCompletedSlice()
            error = null
            notice = null
        }
    }

    fun simplifySelectedModel(keepPercent: Int) {
        val selected = projectHistory.current.selectedObject
        val selectedVolume = selected?.singleVolumeOrNull
        if (
            selectedVolume == null || projectTransferBusy || importing || slicing ||
            previewLoading || selectedVolume.model.triangles < MINIMUM_SIMPLIFIABLE_TRIANGLES
        ) return
        if (projectTransferModel.simplifySelectedModel(keepPercent)) {
            clearCompletedSlice()
            error = null
            notice = null
        }
    }

    fun addPrimitive(primitive: OrcaPrimitive, sizeMm: Float) {
        if (
            projectTransferBusy || !projectRestored || slicing || previewLoading
        ) return
        if (projectHistory.current.allObjects.size >= ProjectStore.MAX_PROJECT_OBJECTS) {
            error = shapeError
            notice = null
            return
        }
        val displayName = resources.getString(primitive.label)
        if (projectTransferModel.createPrimitive(primitive, sizeMm, displayName)) {
            error = null
            notice = null
        }
    }

    fun addAuxiliaryPrimitive(draft: OrcaAuxiliaryPrimitiveDraft) {
        val selected = projectHistory.current.selectedObject ?: return
        if (
            projectTransferBusy || !projectRestored || slicing || previewLoading ||
            selected.volumes.size >= MAX_PROJECT_VOLUMES_PER_OBJECT ||
            projectHistory.current.allObjects.sumOf { it.volumes.size } >=
            ProjectStore.MAX_PROJECT_VOLUMES
        ) return
        val roleLabel = resources.getString(
            when (draft.role) {
                ProjectVolumeRole.NEGATIVE_VOLUME -> R.string.region_cutout
                ProjectVolumeRole.PARAMETER_MODIFIER -> R.string.region_settings
                ProjectVolumeRole.SUPPORT_BLOCKER -> R.string.region_support_blocker
                ProjectVolumeRole.SUPPORT_ENFORCER -> R.string.region_support_enforcer
                ProjectVolumeRole.MODEL_PART -> return
            },
        )
        val displayName = resources.getString(
            R.string.auxiliary_shape_name,
            roleLabel,
            resources.getString(draft.primitive.label),
        )
        if (projectTransferModel.createAuxiliaryPrimitive(draft, displayName)) {
            error = null
            notice = null
        }
    }

    fun editAuxiliaryVolume(draft: OrcaAuxiliaryVolumeEditDraft) {
        val selected = projectHistory.current.selectedObject ?: return
        val volume = selected.volumes.firstOrNull { it.id == draft.volumeId } ?: return
        if (
            volume.role == ProjectVolumeRole.MODEL_PART || projectTransferBusy ||
            !projectRestored || slicing || previewLoading
        ) return
        val displayName = resources.getString(
            when (volume.role) {
                ProjectVolumeRole.NEGATIVE_VOLUME -> R.string.region_cutout
                ProjectVolumeRole.PARAMETER_MODIFIER -> R.string.region_settings
                ProjectVolumeRole.SUPPORT_BLOCKER -> R.string.region_support_blocker
                ProjectVolumeRole.SUPPORT_ENFORCER -> R.string.region_support_enforcer
                ProjectVolumeRole.MODEL_PART -> return
            },
        )
        if (projectTransferModel.editAuxiliaryVolume(draft, displayName)) {
            error = null
            notice = null
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (
            uris.isNotEmpty() && projectRestored && !projectTransferBusy && !slicing &&
            !previewLoading && projectTransferModel.importModels(uris)
        ) {
            error = null
            notice = null
        }
    }

    LaunchedEffect(
        externalModelRequest?.id,
        externalModelRequest?.startedOperationId,
        projectRestored,
        projectTransferBusy,
        importing,
        autoLaying,
        arranging,
        splitting,
        cutting,
        slicing,
        previewLoading,
        projectTransferState.editCompletion?.id,
    ) {
        val request = externalModelRequest ?: return@LaunchedEffect
        if (request.startedOperationId != null) return@LaunchedEffect
        if (
            !projectRestored || projectTransferBusy || importing || autoLaying || arranging ||
            splitting || cutting || slicing || previewLoading ||
            projectTransferState.editCompletion != null
        ) return@LaunchedEffect
        if (projectTransferModel.importModels(request.uri)) {
            val operation = projectTransferModel.state.value.activeEdit
            if (
                operation?.kind != ProjectEditKind.MODEL_IMPORT ||
                !onExternalModelRequestStarted(request.id, operation.id)
            ) {
                projectTransferModel.cancelActiveEdit()
            }
            error = null
            notice = null
        } else if (onExternalModelRequestDiscarded(request.id)) {
            error = modelReadError
            notice = null
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GCODE_DOCUMENT_MIME_TYPE),
    ) { uri ->
        val requested = pendingGcodeExport
        pendingGcodeExport = null
        if (uri != null && requested != null && gcodeExportModel.export(uri, requested.outcome)) {
            error = null
            notice = null
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val requested = pendingGcodeBatch
        pendingGcodeBatch = null
        if (uri != null && requested != null && gcodeExportModel.exportAll(uri, requested)) {
            error = null
            notice = null
        }
    }

    fun importProject(uri: Uri): Boolean {
        if (
            projectRestored && !projectTransferBusy && !importing && !autoLaying &&
            !arranging && !splitting && !cutting && !slicing && !previewLoading &&
            projectTransferState.completion == null
        ) {
            if (projectTransferModel.importProject(uri)) {
                error = null
                notice = null
                return true
            }
        }
        return false
    }

    fun importRecentProject(document: LinkedProjectDocument) {
        if (
            projectRestored && !projectTransferBusy && !importing && !autoLaying &&
            !arranging && !splitting && !cutting && !slicing && !previewLoading &&
            projectTransferState.completion == null
        ) {
            if (projectTransferModel.importRecentProject(document)) {
                error = null
                notice = null
            } else {
                error = recentProjectUnavailable
                notice = null
            }
        }
    }

    val projectOpenPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importProject)
    }

    ExternalProjectImportEffect(
        request = externalProjectRequest,
        enabled = projectRestored && !projectTransferBusy && !importing && !autoLaying &&
            !arranging && !splitting && !cutting && !slicing && !previewLoading &&
            projectTransferState.completion == null,
        replacementConfirmationRequired = requiresProjectReplacementConfirmation(
            plates = projectHistory.current.plates,
            linkedDocumentDirty = projectTransferState.linkedDocumentDirty,
        ),
        projectTransferModel = projectTransferModel,
        onImport = ::importProject,
        onRequestStarted = onExternalProjectRequestStarted,
        onConfirmationRequired = { externalProjectConfirmation = it },
    )

    val documentExportEnabled =
        projectRestored && !projectTransferBusy && !importing && !autoLaying &&
            !arranging && !splitting && !cutting && !slicing && !previewLoading
    val projectSavePicker = rememberProjectDocumentCreator(
        projectTransferModel = projectTransferModel,
        enabled = documentExportEnabled,
        onExportStarted = {
            error = null
            notice = null
        },
    )
    val modelExportPicker = rememberModelDocumentCreator(
        projectTransferModel = projectTransferModel,
        enabled = documentExportEnabled,
        onExportStarted = {
            error = null
            notice = null
        },
    )

    val stlExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(STL_DOCUMENT_MIME_TYPE),
    ) { uri ->
        val objectId = pendingStlExportObjectId
        pendingStlExportObjectId = null
        if (
            uri != null && objectId != null && projectRestored && !projectTransferBusy &&
            !importing && !autoLaying && !arranging && !splitting && !cutting &&
            !slicing && !previewLoading
        ) {
            val state = projectTransferModel.state.value
            val snapshot = state.history.current
            val projectObject = snapshot.allObjects.firstOrNull { it.id == objectId }
            val objectPlate = snapshot.plates.firstOrNull { plate ->
                plate.objects.any { it.id == objectId }
            }
            val options = objectPlate?.let { state.plateOptions[it.id] } ?: state.sliceOptions
            if (
                projectObject != null &&
                projectTransferModel.exportStl(uri, projectObject, options)
            ) {
                error = null
                notice = null
            }
        }
    }

    fun importProfiles(uri: Uri): Boolean {
        if (
            !profileBusy && projectRestored && !projectTransferBusy &&
            !importing && !autoLaying && !arranging && !splitting && !cutting &&
            !slicing && !previewLoading && profileLibraryModel.importBundle(uri)
        ) {
            error = null
            notice = null
            return true
        }
        return false
    }

    val profileImportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importProfiles)
    }

    LaunchedEffect(
        externalProfileRequest?.id,
        externalProfileRequest?.startedOperationId,
        profileBusy,
        projectRestored,
        projectTransferBusy,
        importing,
        autoLaying,
        arranging,
        splitting,
        cutting,
        slicing,
        previewLoading,
    ) {
        val request = externalProfileRequest ?: return@LaunchedEffect
        if (request.startedOperationId != null) return@LaunchedEffect
        if (importProfiles(request.uri)) {
            val operationId = profileLibraryModel.state.value.activeOperationId
            onExternalProfileRequestStarted(request.id, operationId)
        }
    }

    val profileExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PROFILE_BUNDLE_MIME_TYPE),
    ) { uri ->
        if (
            uri != null && !profileBusy && projectRestored && !projectTransferBusy &&
            !importing && !autoLaying && !arranging && !splitting && !cutting &&
            !slicing && !previewLoading && profileLibraryModel.exportBundle(uri)
        ) {
            error = null
            notice = null
        }
    }

    val loadPreviewRange: (Int, Int) -> Unit = { startLayer, endLayer ->
        val requested = plateSliceResults.resultFor(selectedPlateId)
        if (requested != null && !slicing && !autoLaying && !arranging && !splitting && !cutting) {
            sliceOperationModel.loadPreview(
                requested.plateId,
                requested.outcome,
                startLayer,
                endLayer,
            )
        }
    }
    LaunchedEffect(selectedTab, sliceOutcome?.output?.absolutePath) {
        val completed = sliceOutcome
        if (
            selectedTab == WorkspaceTab.PREVIEW &&
            completed?.isRestorableFrom(context.filesDir) == true &&
            layerPreview == null
        ) {
            loadPreviewRange(0, Int.MAX_VALUE)
        }
    }

    val sliceStartControls = rememberSliceStartControls(
        ready = projectRestored && !projectTransferBusy,
        blocked = slicing || importing || autoLaying || arranging || splitting || cutting ||
            previewLoading,
        snapshot = projectHistory.current,
        plateOptions = projectTransferState.plateOptions,
        results = plateSliceResults,
        operationState = sliceOperationState,
        batchState = plateSliceBatchState,
        operationModel = sliceOperationModel,
        batchModel = plateSliceBatchModel,
        onResultsChanged = { plateSliceResults = it },
        onVisualResultsCleared = {
            layerPreview = null
            stalePreviewResult = null
        },
        onPresentationCleared = {
            error = null
            notice = null
        },
        onRemoteResultInvalidated = remoteOperationModel::invalidateUpload,
    )

    val saveGcode: (Boolean) -> Unit = { allPlates ->
        if (!exportingGcode) {
            if (allPlates) {
                gcodeExportBatch?.let { requested ->
                    pendingGcodeBatch = requested
                    folderPicker.launch(null)
                }
            } else {
                val requested = plateSliceResults.resultFor(selectedPlateId)
                if (requested != null && projectObjects.isNotEmpty()) {
                    pendingGcodeExport = requested
                    savePicker.launch(requested.outcome.suggestedName)
                }
            }
        }
    }

    fun selectedRemoteDevice(): RemoteDeviceProfile? = remoteOperationState.selectedProfile()

    LaunchedEffect(
        lifecycleOwner,
        selectedTab,
        selectedRemoteDeviceId,
        appSettings.connectionTimeoutSeconds,
    ) {
        val profileId = selectedRemoteDeviceId
        if (selectedTab != WorkspaceTab.DEVICE || profileId == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (currentCoroutineContext().isActive) {
                    val current = remoteOperationModel.state.value
                    remoteOperationModel.refreshInBackground(
                        profileId,
                        appSettings.connectionTimeoutSeconds,
                    )
                    delay(
                        remoteMonitoringIntervalMillis(
                            current.statusFor(profileId),
                            current.messageFor(profileId),
                        ),
                    )
                }
            } finally {
                remoteOperationModel.stopBackgroundRefresh(profileId)
            }
        }
    }

    WorkspaceScreen(
        selectedTab = selectedTab,
        projectPlates = projectPlates,
        selectedPlateId = selectedPlateId,
        projectObjects = projectObjects,
        selectedObjectId = projectHistory.current.selectedObjectId,
        layerPauseEvents = projectHistory.current.activePlate.layerPauseEvents,
        layerFilamentChanges = projectHistory.current.activePlate.layerFilamentChanges,
        layerCustomGCodeEvents = projectHistory.current.activePlate.layerCustomGCodeEvents,
        sliceOptions = sliceOptions,
        profileCatalog = profileCatalog,
        profileRecents = profileRecents,
        appSettings = appSettings,
        remoteDevices = remoteDevices,
        selectedRemoteDeviceId = selectedRemoteDeviceId,
        remoteStatus = remoteStatus,
        remoteUpload = remoteUpload,
        remoteBusy = remoteBusy,
        remoteUploadProgress = remoteUploadProgress,
        remoteRequestActive = remoteRequestActive,
        remoteUploadActive = remoteUploadActive,
        remoteRequestCancellationRequested = remoteRequestCancellationRequested,
        remoteMessage = remoteMessage,
        remoteMessageIsError = remoteMessageIsError,
        profileBusy = profileBusy,
        profileTransferDirection = profileTransferDirection,
        profileTransferCancellationRequested = profileTransferCancellationRequested,
        appSettingsSaveFailed = appSettingsState.message == AppSettingsMessage.SAVE_FAILED,
        supportReportExportState = supportReportExportState,
        sliceOutcome = sliceOutcome,
        previewOutcome = sliceOutcome ?: stalePreviewResult?.outcome,
        layerPreview = layerPreview,
        previewStale = sliceOutcome == null && stalePreviewResult != null,
        importing = importing || projectFileBusy,
        autoLaying = autoLaying,
        arranging = arranging,
        splitting = splitting,
        cutting = cutting,
        simplifying = simplifying,
        projectEditActive = projectEditActive,
        projectEditCancellationRequested = projectEditCancellationRequested,
        projectImporting = projectImporting,
        projectExporting = projectExporting,
        projectTransferCancellationRequested = projectTransferCancellationRequested,
        linkedProjectName = projectTransferState.linkedDocument?.displayName,
        linkedProjectDirty = projectTransferState.linkedDocumentDirty,
        recentProjectDocuments = projectTransferState.recentDocuments.filterNot { document ->
            document.uri == projectTransferState.linkedDocument?.uri
        },
        slicing = slicing,
        sliceCancellationRequested = sliceCancellationRequested,
        sliceProgress = SliceProgress(sliceProgress, plateSliceBatchProgress),
        previewLoading = previewLoading,
        gcodeExportState = gcodeExportState,
        canExportAllGcode = gcodeExportBatch != null,
        error = error,
        notice = notice,
        onTabSelected = { tab ->
            selectedTab = tab
            if (tab == WorkspaceTab.PREVIEW && sliceOutcome != null && layerPreview == null) {
                loadPreviewRange(0, Int.MAX_VALUE)
            }
        },
        onChoose = {
            filePicker.launch(
                arrayOf(
                    "model/stl",
                    "model/3mf",
                    "model/obj",
                    "application/sla",
                    "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
                    "application/x-tgif",
                    "*/*",
                ),
            )
        },
        onImportProfiles = {
            profileImportPicker.launch(
                arrayOf(PROFILE_BUNDLE_MIME_TYPE, "application/json", "*/*"),
            )
        },
        onExportProfiles = { profileExportPicker.launch(DEFAULT_PROFILE_BUNDLE_NAME) },
        onCancelProfileTransfer = profileLibraryModel::cancelTransfer,
        onCreatePrimitive = ::addPrimitive,
        onCreateAuxiliaryPrimitive = ::addAuxiliaryPrimitive,
        onEditAuxiliaryVolume = ::editAuxiliaryVolume,
        onNewProject = {
            if (projectTransferModel.newProject()) {
                clearAllCompletedSlices()
                notice = newProjectStartedNotice
                error = null
            }
        },
        onOpenProject = {
            projectOpenPicker.launch(
                arrayOf(PROJECT_ARCHIVE_MIME_TYPE, "application/zip"),
            )
        },
        onOpenRecentProject = ::importRecentProject,
        onSaveProject = projectSaveAction(projectTransferModel, projectSavePicker),
        onExportModel = {
            val sourceName = projectHistory.current.selectedObject
                ?.primaryModelPart?.model?.fileName
                ?: projectObjects.firstOrNull()?.primaryModelPart?.model?.fileName
            val suggestedName = sourceName?.let {
                "${threeMfDisplayName(it, "DuckySlicer-model")}.3mf"
            } ?: DEFAULT_THREE_MF_NAME
            modelExportPicker.launch(suggestedName)
        },
        onExportSelectedStl = {
            projectHistory.current.selectedObject?.let { selected ->
                pendingStlExportObjectId = selected.id
                stlExportPicker.launch(
                    "${threeMfDisplayName(selected.primaryModelPart.model.fileName, "model")}.stl",
                )
            }
        },
        onPlateSelected = { plateId ->
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy && plateId != selectedPlateId && selectProjectPlate(
                    plateId = plateId,
                    projectTransferModel = projectTransferModel,
                    sliceOperationModel = sliceOperationModel,
                    remoteOperationModel = remoteOperationModel,
                )
            ) {
                layerPreview = null
                stalePreviewResult = null
                notice = null
                error = null
            }
        },
        onAddPlate = {
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy && projectPlates.size < MAX_PROJECT_PLATES && addEmptyProjectPlate(
                    projectTransferModel = projectTransferModel,
                    sliceOperationModel = sliceOperationModel,
                    remoteOperationModel = remoteOperationModel,
                )
            ) {
                layerPreview = null
                stalePreviewResult = null
                selectedTab = WorkspaceTab.SLICE
                notice = null
                error = null
            }
        },
        onDuplicatePlate = {
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy && duplicateActivePlate(
                    projectTransferModel = projectTransferModel,
                    sliceOperationModel = sliceOperationModel,
                    remoteOperationModel = remoteOperationModel,
                )
            ) {
                layerPreview = null
                stalePreviewResult = null
                selectedTab = WorkspaceTab.SLICE
                notice = resources.getString(R.string.plate_duplicated)
                error = null
            }
        },
        onRenamePlate = { name ->
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy
            ) {
                val current = projectTransferModel.state.value.history
                val next = current.renameSelectedPlate(name)
                if (projectTransferModel.updateHistory(current, next)) {
                    notice = resources.getString(R.string.plate_renamed)
                    error = null
                }
            }
        },
        onMovePlate = { targetIndex ->
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy
            ) {
                val current = projectTransferModel.state.value.history
                if (targetIndex in current.current.plates.indices) {
                    val next = current.moveSelectedPlateTo(targetIndex)
                    if (projectTransferModel.updateHistory(current, next)) {
                        notice = resources.getString(R.string.plate_moved)
                        error = null
                    }
                }
            }
        },
        onRemovePlate = {
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy && projectPlates.size > 1
            ) {
                val removedPlateId = selectedPlateId
                val current = projectTransferModel.state.value.history
                val next = current.removeSelectedPlate()
                if (projectTransferModel.updateHistory(current, next)) {
                    plateSliceResults = plateSliceResults.clear(removedPlateId)
                    sliceOperationModel.clearCompleted()
                    layerPreview = null
                    stalePreviewResult = null
                    remoteOperationModel.invalidateUpload()
                    selectedTab = WorkspaceTab.SLICE
                    notice = null
                    error = null
                }
            }
        },
        canUndo = projectHistory.canUndo,
        canRedo = projectHistory.canRedo,
        onObjectSelected = { objectId ->
            val current = projectTransferModel.state.value.history
            projectTransferModel.updateHistory(current, current.select(objectId))
        },
        onModelTransformChanged = { transform -> applyModelTransform(transform) },
        onModelTransformPreview = { transform -> applyModelTransform(transform, recordHistory = false) },
        onModelTransformCommitted = { previous ->
            val current = projectTransferModel.state.value.history
            projectTransferModel.updateHistory(
                current,
                current.commitSelectedTransform(previous),
            )
        },
        onObjectFilamentSelected = { filament ->
            runCatching { sliceOptions.assignFilament(filament) }
                .onSuccess { assignment ->
                    applyOptions(assignment.options)
                    val current = projectTransferModel.state.value.history
                    val nextHistory = current.updateSelectedFilamentSlot(assignment.slot)
                    if (projectTransferModel.updateHistory(current, nextHistory)) {
                        clearCompletedSlice()
                        notice = null
                        error = null
                    }
                }
                .onFailure {
                    error = filamentSlotUnavailable
                    notice = null
                }
        },
        onUndo = {
            val current = projectTransferModel.state.value.history
            if (current.canUndo) {
                val next = current.undo()
                if (projectTransferModel.updateHistory(current, next)) {
                    clearCompletedSlice(next.current.selectedPlateId)
                }
            }
        },
        onRedo = {
            val current = projectTransferModel.state.value.history
            if (current.canRedo) {
                val next = current.redo()
                if (projectTransferModel.updateHistory(current, next)) {
                    clearCompletedSlice(next.current.selectedPlateId)
                }
            }
        },
        onDuplicate = { objectId ->
            val current = projectTransferModel.state.value.history
            if (current.current.allObjects.size < ProjectStore.MAX_PROJECT_OBJECTS) {
                val nextHistory = current.duplicate(objectId, UUID.randomUUID().toString())
                if (projectTransferModel.updateHistory(current, nextHistory)) {
                    clearCompletedSlice()
                }
            }
        },
        onRenameObject = { objectId, name ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.renameObject(objectId, name)
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onCopyObjectToPlate = { objectId, targetPlateId ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.copyObjectToPlate(
                objectId = objectId,
                targetPlateId = targetPlateId,
                newId = UUID.randomUUID().toString(),
            )
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                plateSliceResults = plateSliceResults.clear(targetPlateId)
                sliceOperationModel.clearCompleted()
                layerPreview = null
                stalePreviewResult = null
                remoteOperationModel.invalidateUpload()
                notice = null
                error = null
            }
        },
        onMoveObjectToPlate = { objectId, targetPlateId ->
            val current = projectTransferModel.state.value.history
            val sourcePlateId = current.current.selectedPlateId
            val nextHistory = current.moveObjectToPlate(objectId, targetPlateId)
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                clearCompletedSlice(sourcePlateId)
                plateSliceResults = plateSliceResults.clear(targetPlateId)
                notice = null
                error = null
            }
        },
        onArrange = ::arrangeProjectObjects,
        onAutoLay = ::autoLaySelectedModel,
        onLayOnFace = ::laySelectedFaceOnBed,
        onSplit = ::splitSelectedModel,
        onSplitParts = ::splitSelectedVolume,
        onCut = ::cutSelectedModel,
        onSimplify = ::simplifySelectedModel,
        onCancelProjectEdit = projectTransferModel::cancelActiveEdit,
        onCancelProjectImport = projectTransferModel::cancelProjectImport,
        onCancelProjectExport = projectTransferModel::cancelProjectExport,
        onSupportPaintPreview = facetPaintActions::previewSupport,
        onSupportPaintCommitted = facetPaintActions::commitSupport,
        onSeamPaintPreview = facetPaintActions::previewSeam,
        onSeamPaintCommitted = facetPaintActions::commitSeam,
        onBrimPointsChanged = { objectId, brimPoints ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.updateBrimPoints(objectId, brimPoints)
            if (projectTransferModel.updateHistory(current, nextHistory)) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onMultiColorPaintPreview = facetPaintActions::previewMultiColor,
        onMultiColorPaintCommitted = facetPaintActions::commitMultiColor,
        onVariableLayerHeightsChanged = { variableLayerHeights ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.updateSelectedVariableLayerHeights(
                variableLayerHeights,
            )
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onObjectProcessOverridesChanged = { processOverrides ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.updateSelectedProcessOverrides(processOverrides)
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onHeightRangeModifiersChanged = { modifiers ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.updateSelectedHeightRangeModifiers(modifiers)
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onRemoveAuxiliaryVolume = { volumeId ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.removeSelectedAuxiliaryVolume(volumeId)
            if (
                nextHistory != current &&
                projectTransferModel.updateHistory(current, nextHistory)
            ) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onRemoveModel = { objectId ->
            val current = projectTransferModel.state.value.history
            if (
                projectTransferModel.updateHistory(
                    current,
                    current.remove(objectId),
                )
            ) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onSlice = { allPlates ->
            if (allPlates) sliceStartControls.startAll() else sliceStartControls.startSelected()
        },
        onCancelSlice = sliceStartControls.cancel,
        onSave = saveGcode,
        onCancelGcodeExport = gcodeExportModel::cancelActiveExport,
        onSliceOptionsChanged = ::applyOptions,
        onSavePrinterProfile = profileActions::savePrinter,
        onSaveFilamentProfile = profileActions::saveFilament,
        onSaveSlicingProfile = profileActions::saveSlicing,
        onUpdatePrinterProfile = profileActions::updatePrinter,
        onUpdateFilamentProfile = profileActions::updateFilament,
        onUpdateSlicingProfile = profileActions::updateSlicing,
        onRenamePrinterProfile = profileActions::renamePrinter,
        onRenameFilamentProfile = profileActions::renameFilament,
        onRenameSlicingProfile = profileActions::renameSlicing,
        onDeletePrinterProfile = { profileId ->
            if (!profileLibraryModel.deletePrinter(profileId)) {
                error = profileDeleteError
                notice = null
            }
        },
        onDeleteFilamentProfile = { profileId ->
            if (!profileLibraryModel.deleteFilament(profileId)) {
                error = profileDeleteError
                notice = null
            }
        },
        onDeleteSlicingProfile = { profileId ->
            if (!profileLibraryModel.deleteSlicing(profileId)) {
                error = profileDeleteError
                notice = null
            }
        },
        onLayerRangeSelected = loadPreviewRange,
        onAddLayerPause = { _, printZMm ->
            val current = projectTransferModel.state.value.history
            val next = current.putLayerPause(
                LayerPauseEvent(
                    printZMm = printZMm,
                ),
            )
            if (next != current && projectTransferModel.updateHistory(current, next)) {
                invalidateSliceAfterPreviewEdit()
                notice = null
                error = null
            }
        },
        onRemoveLayerPause = { printZMm ->
            val current = projectTransferModel.state.value.history
            val next = current.removeLayerPause(printZMm)
            if (next != current && projectTransferModel.updateHistory(current, next)) {
                invalidateSliceAfterPreviewEdit()
                notice = null
                error = null
            }
        },
        onPutLayerFilamentChange = { _, printZMm, filamentSlot ->
            val current = projectTransferModel.state.value.history
            val next = current.putLayerFilamentChange(
                LayerFilamentChange(
                    printZMm = printZMm,
                    filamentSlot = filamentSlot,
                ),
            )
            if (next != current && projectTransferModel.updateHistory(current, next)) {
                invalidateSliceAfterPreviewEdit()
                notice = null
                error = null
            }
        },
        onRemoveLayerFilamentChange = { printZMm ->
            val current = projectTransferModel.state.value.history
            val next = current.removeLayerFilamentChange(printZMm)
            if (next != current && projectTransferModel.updateHistory(current, next)) {
                invalidateSliceAfterPreviewEdit()
                notice = null
                error = null
            }
        },
        onPutLayerCustomGCode = { _, printZMm, gcode ->
            val current = projectTransferModel.state.value.history
            val next = current.putLayerCustomGCode(
                LayerCustomGCodeEvent(printZMm = printZMm, gcode = gcode),
            )
            if (next != current && projectTransferModel.updateHistory(current, next)) {
                invalidateSliceAfterPreviewEdit()
                notice = null
                error = null
            }
        },
        onRemoveLayerCustomGCode = { printZMm ->
            val current = projectTransferModel.state.value.history
            val next = current.removeLayerCustomGCode(printZMm)
            if (next != current && projectTransferModel.updateHistory(current, next)) {
                invalidateSliceAfterPreviewEdit()
                notice = null
                error = null
            }
        },
        onAppSettingsChanged = { next ->
            appSettingsModel.updateSettings(next)
        },
        onSupportReportExport = { uri ->
            supportReportExportModel.export(uri, appSettings)
        },
        onCancelSupportReportExport = supportReportExportModel::cancel,
        onRemoteDeviceSelected = { id ->
            remoteOperationModel.selectionChanged(id)
        },
        onRemoteDeviceSaved = { draft ->
            remoteOperationModel.saveProfile(draft)
        },
        onRemoteDeviceDeleted = { id ->
            remoteOperationModel.deleteProfile(id)
        },
        onRemoteRefresh = {
            val profile = selectedRemoteDevice()
            if (profile != null && !remoteBusy) {
                remoteOperationModel.refresh(profile, appSettings.connectionTimeoutSeconds)
            }
        },
        onRemoteUpload = {
            val profile = selectedRemoteDevice()
            val outcome = plateSliceResults.resultFor(selectedPlateId)?.outcome
            if (profile != null && outcome != null && !remoteBusy) {
                remoteOperationModel.upload(
                    profile,
                    outcome.output,
                    outcome.suggestedName,
                    appSettings.connectionTimeoutSeconds,
                )
            }
        },
        onRemoteCancelRequest = {
            remoteOperationModel.cancelActiveRequest()
        },
        onRemoteStart = {
            val profile = selectedRemoteDevice()
            val upload = remoteUpload
            if (profile != null && upload != null && !remoteBusy) {
                remoteOperationModel.start(profile, upload, appSettings.connectionTimeoutSeconds)
            }
        },
        onRemotePause = {
            selectedRemoteDevice()?.let { profile ->
                remoteOperationModel.pause(profile, appSettings.connectionTimeoutSeconds)
            }
        },
        onRemoteResume = {
            selectedRemoteDevice()?.let { profile ->
                remoteOperationModel.resume(profile, appSettings.connectionTimeoutSeconds)
            }
        },
        onRemoteCancel = {
            selectedRemoteDevice()?.let { profile ->
                remoteOperationModel.cancel(profile, appSettings.connectionTimeoutSeconds)
            }
        },
    )
    externalProjectConfirmation?.let { request ->
        ProjectReplacementDialog(
            linkedProjectName = projectTransferState.linkedDocument?.displayName,
            linkedProjectDirty = projectTransferState.linkedDocumentDirty,
            onConfirm = {
                externalProjectConfirmation = null
                startExternalProjectImport(
                    request,
                    projectTransferModel,
                    ::importProject,
                    onExternalProjectRequestStarted,
                )
            },
            onDismiss = {
                externalProjectConfirmation = null
                onExternalProjectRequestDiscarded(request.id)
            },
        )
    }
}

internal fun startExternalProjectImport(
    request: ExternalProjectRequest,
    model: ProjectTransferViewModel,
    importProject: (Uri) -> Boolean,
    onStarted: (Long, Long) -> Boolean,
): Boolean {
    if (!importProject(request.uri)) return false
    val state = model.state.value
    val operationId = state.activeTransferId
    val claimed = operationId != null &&
        state.activeTransferDirection == ProjectTransferDirection.IMPORT &&
        onStarted(request.id, operationId)
    if (!claimed) model.cancelProjectImport()
    return claimed
}

private class ProfileLibraryActions(
    private val model: ProfileLibraryViewModel,
    private val sessionRevision: () -> Long,
    private val accept: (Boolean) -> Unit,
) {
    fun savePrinter(name: String, options: SliceOptions) =
        accept(model.savePrinter(name, options, sessionRevision()))

    fun saveFilament(name: String, options: SliceOptions, slot: Int) =
        accept(model.saveFilament(name, options, slot, sessionRevision()))

    fun saveSlicing(name: String, options: SliceOptions) =
        accept(model.saveSlicing(name, options, sessionRevision()))

    fun updatePrinter(profileId: String, options: SliceOptions) =
        accept(model.updatePrinter(profileId, options, sessionRevision()))

    fun updateFilament(profileId: String, options: SliceOptions, slot: Int) =
        accept(model.updateFilament(profileId, options, slot, sessionRevision()))

    fun updateSlicing(profileId: String, options: SliceOptions) =
        accept(model.updateSlicing(profileId, options, sessionRevision()))

    fun renamePrinter(profileId: String, name: String, options: SliceOptions) =
        accept(model.renamePrinter(profileId, name, options, sessionRevision()))

    fun renameFilament(profileId: String, name: String, options: SliceOptions) =
        accept(model.renameFilament(profileId, name, options, sessionRevision()))

    fun renameSlicing(profileId: String, name: String, options: SliceOptions) =
        accept(model.renameSlicing(profileId, name, options, sessionRevision()))
}

internal fun profileTransferSuccessNotice(
    resources: Resources,
    completion: ProfileTransferCompletion,
    profilesUnchangedNotice: String,
    profilesExportedNotice: String,
): String {
    if (completion.direction == ProfileTransferDirection.EXPORT) return profilesExportedNotice
    val result = completion.importResult
    val imported = result?.importedTotal ?: 0
    if (imported == 0) return profilesUnchangedNotice
    val renamed = result?.renamedConflicts ?: 0
    return if (renamed > 0) {
        resources.getString(
            R.string.profiles_imported_with_renamed_conflicts,
            imported,
            renamed,
        )
    } else {
        resources.getString(R.string.profiles_imported, imported)
    }
}

@Composable
private fun ProjectTransferCompletionEffect(
    completion: ProjectTransferCompletion?,
    externalRequest: ExternalProjectRequest?,
    supportEvents: SupportEventJournal,
    onExternalConsumed: (Long, Long) -> Boolean,
    onConsumeCompletion: (Long) -> Unit,
    onImported: () -> Unit,
    onDismissExternalConfirmation: () -> Unit,
    onPresentation: (notice: String?, error: String?) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(completion?.id) {
        val current = completion ?: return@LaunchedEffect
        when (current) {
            is ProjectTransferCompletion.Imported -> {
                onImported()
                onDismissExternalConfirmation()
                onPresentation(resources.getString(R.string.project_opened), null)
            }
            is ProjectTransferCompletion.Exported -> {
                val notice = resources.getString(
                    if (current.format != ProjectExportFormat.PROJECT_ARCHIVE) {
                        R.string.model_exported
                    } else {
                        R.string.project_saved
                    },
                )
                onPresentation(notice, null)
            }
            is ProjectTransferCompletion.Canceled -> {
                val notice = if (current.direction == ProjectTransferDirection.IMPORT) {
                    onDismissExternalConfirmation()
                    resources.getString(R.string.project_import_canceled)
                } else {
                    resources.getString(
                        if (current.format != ProjectExportFormat.PROJECT_ARCHIVE) {
                            R.string.model_export_canceled
                        } else {
                            R.string.project_export_canceled
                        },
                    )
                }
                onPresentation(notice, null)
            }
            is ProjectTransferCompletion.Failed -> {
                val error = if (current.direction == ProjectTransferDirection.IMPORT) {
                    supportEvents.record(SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED)
                    resources.getString(R.string.project_open_error)
                } else {
                    resources.getString(
                        if (current.format != ProjectExportFormat.PROJECT_ARCHIVE) {
                            R.string.model_export_error
                        } else {
                            R.string.project_export_error
                        },
                    )
                }
                onDismissExternalConfirmation()
                onPresentation(null, error)
            }
        }
        externalRequest
            ?.takeIf { request -> request.startedOperationId == current.id }
            ?.let { request -> onExternalConsumed(request.id, current.id) }
        onConsumeCompletion(current.id)
    }
}

@Composable
private fun ProfileTransferCompletionEffect(
    completion: ProfileTransferCompletion?,
    externalRequest: ExternalProfileRequest?,
    onExternalConsumed: (Long, Long) -> Boolean,
    onConsumeCompletion: (Long) -> Unit,
    onPresentation: (String?, String?) -> Unit,
) {
    val resources = LocalResources.current
    val profilesUnchangedNotice = stringResource(R.string.profiles_unchanged)
    val profilesExportedNotice = stringResource(R.string.profiles_exported)
    val profileImportCanceledNotice = stringResource(R.string.profile_import_canceled)
    val profileExportCanceledNotice = stringResource(R.string.profile_export_canceled)
    val profileImportError = stringResource(R.string.profile_import_error)
    val profileExportError = stringResource(R.string.profile_export_error)
    LaunchedEffect(completion?.id) {
        val completed = completion ?: return@LaunchedEffect
        val presentation = when (completed.outcome) {
            ProfileTransferOutcome.SUCCEEDED -> profileTransferSuccessNotice(
                resources,
                completed,
                profilesUnchangedNotice,
                profilesExportedNotice,
            ) to null
            ProfileTransferOutcome.CANCELED -> {
                val notice = if (completed.direction == ProfileTransferDirection.IMPORT) {
                    profileImportCanceledNotice
                } else {
                    profileExportCanceledNotice
                }
                notice to null
            }
            ProfileTransferOutcome.FAILED -> {
                val error = if (completed.direction == ProfileTransferDirection.IMPORT) {
                    profileImportError
                } else {
                    profileExportError
                }
                null to error
            }
        }
        onPresentation(presentation.first, presentation.second)
        externalRequest
            ?.takeIf { request -> request.startedOperationId == completed.id }
            ?.let { request -> onExternalConsumed(request.id, completed.id) }
        onConsumeCompletion(completed.id)
    }
}

internal fun initialWorkspaceReady(
    projectRestored: Boolean,
    profileCatalogLoaded: Boolean,
    profileRecentsLoaded: Boolean,
): Boolean = projectRestored && profileCatalogLoaded && profileRecentsLoaded

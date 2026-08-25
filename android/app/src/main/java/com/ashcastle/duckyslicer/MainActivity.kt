package com.ashcastle.duckyslicer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

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
private const val SLICE_NOTIFICATION_PREFERENCES = "slice_notifications"
private const val SLICE_NOTIFICATION_PERMISSION_ASKED = "permission_asked"
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
                    onExternalProjectRequestConsumed = externalProjectModel::consume,
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

@Composable
private fun DuckySlicerScreen(
    sliceOperationModel: SliceOperationViewModel,
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
    onExternalProjectRequestConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val modelReadError = stringResource(R.string.model_read_error)
    val modelTooLargeError = stringResource(R.string.model_too_large_error)
    val shapeError = stringResource(R.string.shape_error)
    val regionUpdateError = stringResource(R.string.region_update_error)
    val autoLayDone = stringResource(R.string.auto_lay_done)
    val autoLayUnchanged = stringResource(R.string.auto_lay_unchanged)
    val autoLayError = stringResource(R.string.auto_lay_error)
    val layOnFaceDone = stringResource(R.string.lay_on_face_done)
    val layOnFaceError = stringResource(R.string.lay_on_face_error)
    val arrangeDone = stringResource(R.string.arrange_done)
    val arrangeError = stringResource(R.string.arrange_error)
    val splitNotPossible = stringResource(R.string.split_not_possible)
    val splitError = stringResource(R.string.split_error)
    val splitPartsNotPossible = stringResource(R.string.split_parts_not_possible)
    val splitPartsError = stringResource(R.string.split_parts_error)
    val cutNotPossible = stringResource(R.string.cut_not_possible)
    val cutError = stringResource(R.string.cut_error)
    val simplifyError = stringResource(R.string.simplify_error)
    val sliceError = stringResource(R.string.slice_error)
    val sliceCanceledNotice = stringResource(R.string.slice_canceled)
    val modelEditCanceledNotice = stringResource(R.string.model_edit_canceled)
    val saveError = stringResource(R.string.save_error)
    val savedNotice = stringResource(R.string.gcode_saved)
    val gcodeExportCanceledNotice = stringResource(R.string.gcode_export_canceled)
    val profileSavedNotice = stringResource(R.string.profile_saved)
    val profileSaveError = stringResource(R.string.profile_save_error)
    val profilesUnchangedNotice = stringResource(R.string.profiles_unchanged)
    val profilesExportedNotice = stringResource(R.string.profiles_exported)
    val profileImportCanceledNotice = stringResource(R.string.profile_import_canceled)
    val profileExportCanceledNotice = stringResource(R.string.profile_export_canceled)
    val profileImportError = stringResource(R.string.profile_import_error)
    val profileExportError = stringResource(R.string.profile_export_error)
    val filamentSlotUnavailable = stringResource(R.string.filament_slot_unavailable)
    val projectSaveError = stringResource(R.string.project_save_error)
    val newProjectStartedNotice = stringResource(R.string.new_project_started)
    val projectOpenedNotice = stringResource(R.string.project_opened)
    val projectSavedNotice = stringResource(R.string.project_saved)
    val projectOpenError = stringResource(R.string.project_open_error)
    val projectExportError = stringResource(R.string.project_export_error)
    val projectImportCanceledNotice = stringResource(R.string.project_import_canceled)
    val projectExportCanceledNotice = stringResource(R.string.project_export_canceled)
    val modelExportedNotice = stringResource(R.string.model_exported)
    val modelExportError = stringResource(R.string.model_export_error)
    val modelExportCanceledNotice = stringResource(R.string.model_export_canceled)
    val savedDataUnavailable = stringResource(R.string.saved_data_unavailable)
    val previewError = stringResource(R.string.preview_error)
    val remoteSavedNotice = stringResource(R.string.device_saved)
    val remoteDeletedNotice = stringResource(R.string.device_deleted)
    val remoteConnectedNotice = stringResource(R.string.device_connected)
    val remoteUploadNotice = stringResource(R.string.gcode_sent)
    val remoteStartedNotice = stringResource(R.string.print_started)
    val remotePausedNotice = stringResource(R.string.print_paused)
    val remoteResumedNotice = stringResource(R.string.print_resumed)
    val remoteCanceledNotice = stringResource(R.string.print_canceled)
    val remoteUploadCanceledNotice = stringResource(R.string.upload_canceled)
    val remoteRequestCanceledNotice = stringResource(R.string.remote_request_canceled)
    val remoteConnectionError = stringResource(R.string.device_connection_error)
    val remoteUnauthorizedError = stringResource(R.string.device_access_denied)
    val remoteCommandError = stringResource(R.string.device_command_error)
    val remoteSaveError = stringResource(R.string.device_save_error)

    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var externalProjectConfirmation by remember { mutableStateOf<ExternalProjectRequest?>(null) }
    var plateSliceResults by rememberSaveable { mutableStateOf(PlateSliceResults()) }
    var pendingGcodeExport by rememberSaveable { mutableStateOf<PlateSliceResult?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.SLICE) }
    var layerPreview by remember { mutableStateOf<GcodeLayerPreview?>(null) }
    val sliceOperationState by sliceOperationModel.state.collectAsStateWithLifecycle()
    val slicing = sliceOperationState.slicing
    val sliceCancellationRequested = sliceOperationState.cancellationRequested
    val sliceProgress = sliceOperationState.progress
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
    val modelTransform = selectedProjectObject?.transform ?: ModelTransform()
    val profileCatalog = profileLibraryState.catalog
    val profileRecents = profileLibraryState.recents
    val profileRecentsLoaded = profileLibraryState.recentsLoaded
    val profileBusy = profileLibraryState.busy || profileLibraryState.completion != null ||
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
        remoteOperationModel.invalidateUpload()
    }

    fun clearAllCompletedSlices() {
        sliceOperationModel.clearCompleted()
        plateSliceResults = PlateSliceResults()
        pendingGcodeExport = null
        layerPreview = null
        remoteOperationModel.invalidateUpload()
    }

    LaunchedEffect(projectTransferState.completion?.id) {
        val completion = projectTransferState.completion ?: return@LaunchedEffect
        when (completion) {
            is ProjectTransferCompletion.Imported -> {
                clearAllCompletedSlices()
                externalProjectConfirmation = null
                notice = projectOpenedNotice
                error = null
                if (externalProjectRequest?.uri == completion.uri) {
                    onExternalProjectRequestConsumed(externalProjectRequest.id)
                }
            }
            is ProjectTransferCompletion.Exported -> {
                notice = if (completion.format == ProjectExportFormat.THREE_MF) {
                    modelExportedNotice
                } else {
                    projectSavedNotice
                }
                error = null
            }
            is ProjectTransferCompletion.Canceled -> {
                if (completion.direction == ProjectTransferDirection.IMPORT) {
                    notice = projectImportCanceledNotice
                    externalProjectConfirmation = null
                    if (externalProjectRequest?.uri == completion.uri) {
                        onExternalProjectRequestConsumed(externalProjectRequest.id)
                    }
                } else {
                    notice = if (completion.format == ProjectExportFormat.THREE_MF) {
                        modelExportCanceledNotice
                    } else {
                        projectExportCanceledNotice
                    }
                }
                error = null
            }
            is ProjectTransferCompletion.Failed -> {
                if (completion.direction == ProjectTransferDirection.IMPORT) {
                    supportEvents.record(SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED)
                    error = projectOpenError
                    if (externalProjectRequest?.uri == completion.uri) {
                        onExternalProjectRequestConsumed(externalProjectRequest.id)
                    }
                } else {
                    error = if (completion.format == ProjectExportFormat.THREE_MF) {
                        modelExportError
                    } else {
                        projectExportError
                    }
                }
                externalProjectConfirmation = null
                notice = null
            }
        }
        projectTransferModel.consumeCompletion(completion.id)
    }

    LaunchedEffect(projectTransferState.editCompletion?.id) {
        val completion = projectTransferState.editCompletion ?: return@LaunchedEffect
        if (completion.failure == null) {
            if (completion.sessionChanged) clearCompletedSlice()
            error = null
            notice = when (completion.kind) {
                ProjectEditKind.MODEL_IMPORT -> null
                ProjectEditKind.PRIMITIVE -> resources.getString(
                    R.string.shape_added,
                    completion.displayName.orEmpty(),
                )
                ProjectEditKind.AUXILIARY_VOLUME -> resources.getString(
                    R.string.region_updated,
                    completion.displayName.orEmpty(),
                )
                ProjectEditKind.AUTO_LAY -> if (completion.sessionChanged) {
                    autoLayDone
                } else {
                    autoLayUnchanged
                }
                ProjectEditKind.ARRANGE -> arrangeDone
                ProjectEditKind.SPLIT -> resources.getString(
                    if (completion.clearedObjectSettings) {
                        R.string.split_done_painting_cleared
                    } else {
                        R.string.split_done
                    },
                    completion.objectCount,
                )
                ProjectEditKind.SPLIT_PARTS -> resources.getString(
                    if (completion.clearedObjectSettings) {
                        R.string.split_parts_done_painting_cleared
                    } else {
                        R.string.split_parts_done
                    },
                    completion.objectCount,
                )
                ProjectEditKind.CUT -> resources.getString(
                    if (completion.clearedObjectSettings) {
                        R.string.cut_done_painting_cleared
                    } else {
                        R.string.cut_done
                    },
                )
                ProjectEditKind.SIMPLIFY -> resources.getString(
                    if (completion.clearedObjectSettings) {
                        R.string.simplify_done_painting_cleared
                    } else {
                        R.string.simplify_done
                    },
                    completion.triangleCount,
                )
            }
            selectedTab = WorkspaceTab.SLICE
        } else {
            notice = null
            error = when (completion.failure) {
                ProjectEditFailure.CANCELED -> null
                ProjectEditFailure.MODEL_TOO_LARGE -> modelTooLargeError
                ProjectEditFailure.NOT_SPLITTABLE -> if (
                    completion.kind == ProjectEditKind.SPLIT_PARTS
                ) {
                    splitPartsNotPossible
                } else {
                    splitNotPossible
                }
                ProjectEditFailure.NOT_CUTTABLE -> cutNotPossible
                ProjectEditFailure.GENERIC -> when (completion.kind) {
                    ProjectEditKind.MODEL_IMPORT -> modelReadError
                    ProjectEditKind.PRIMITIVE -> shapeError
                    ProjectEditKind.AUXILIARY_VOLUME -> regionUpdateError
                    ProjectEditKind.AUTO_LAY -> autoLayError
                    ProjectEditKind.ARRANGE -> arrangeError
                    ProjectEditKind.SPLIT -> splitError
                    ProjectEditKind.SPLIT_PARTS -> splitPartsError
                    ProjectEditKind.CUT -> cutError
                    ProjectEditKind.SIMPLIFY -> simplifyError
                }
            }
            if (completion.failure == ProjectEditFailure.CANCELED) {
                notice = modelEditCanceledNotice
            }
        }
        externalModelRequest
            ?.takeIf { request ->
                completion.kind == ProjectEditKind.MODEL_IMPORT &&
                    request.startedOperationId == completion.id
            }
            ?.let { request ->
                onExternalModelRequestConsumed(request.id, completion.id)
            }
        projectTransferModel.consumeEditCompletion(completion.id)
    }

    LaunchedEffect(gcodeExportState.completion?.id) {
        val completion = gcodeExportState.completion ?: return@LaunchedEffect
        when (completion.result) {
            GcodeExportResult.SAVED -> {
                notice = savedNotice
                error = null
            }
            GcodeExportResult.CANCELED -> {
                notice = gcodeExportCanceledNotice
                error = null
            }
            GcodeExportResult.FAILED -> {
                notice = null
                error = saveError
            }
        }
        gcodeExportModel.consumeCompletion(completion.id)
    }

    LaunchedEffect(sliceOutcome?.output?.absolutePath, selectedPlateId) {
        val restored = sliceOutcome ?: return@LaunchedEffect
        if (!restored.isRestorableFrom(context.filesDir)) {
            clearCompletedSlice()
            if (selectedTab == WorkspaceTab.PREVIEW) selectedTab = WorkspaceTab.SLICE
        }
    }
    LaunchedEffect(
        sliceOperationState.plateId,
        sliceOperationState.outcome,
        sliceOperationState.preview,
    ) {
        val completed = sliceOperationState.outcome ?: return@LaunchedEffect
        val ownerPlateId = sliceOperationState.plateId ?: run {
            sliceOperationModel.clearCompleted()
            return@LaunchedEffect
        }
        if (projectPlates.none { it.id == ownerPlateId }) {
            sliceOperationModel.clearCompleted()
            return@LaunchedEffect
        }
        plateSliceResults = plateSliceResults.put(ownerPlateId, completed)
        if (ownerPlateId == selectedPlateId) {
            sliceOperationState.preview?.let { layerPreview = it }
            selectedTab = WorkspaceTab.PREVIEW
        }
        remoteOperationModel.invalidateUpload()
    }
    LaunchedEffect(sliceOperationState.terminalStatus) {
        when (sliceOperationState.terminalStatus) {
            SliceTerminalStatus.CANCELED -> {
                notice = sliceCanceledNotice
                error = null
            }
            SliceTerminalStatus.SLICE_FAILED -> {
                supportEvents.record(SupportEvent.SLICE_FAILED)
                error = sliceError
                notice = null
            }
            SliceTerminalStatus.PREVIEW_FAILED -> {
                supportEvents.record(SupportEvent.PREVIEW_FAILED)
                error = previewError
                notice = null
            }
            SliceTerminalStatus.NONE -> Unit
        }
    }
    LaunchedEffect(projectPlates.map(ProjectPlate::id)) {
        plateSliceResults = plateSliceResults.retain(projectPlates.mapTo(HashSet(), ProjectPlate::id))
    }
    LaunchedEffect(projectTransferState.persistenceMessage) {
        when (projectTransferState.persistenceMessage) {
            ProjectPersistenceMessage.STORAGE_UNAVAILABLE -> error = savedDataUnavailable
            ProjectPersistenceMessage.SAVE_FAILED -> error = projectSaveError
            null -> Unit
        }
        if (projectTransferState.persistenceMessage != null) notice = null
    }
    LaunchedEffect(projectRestored, profileRecentsLoaded) {
        if (projectRestored && profileRecentsLoaded) {
            profileLibraryModel.recordSelection(sliceOptions)
        }
    }
    LaunchedEffect(profileLibraryState.message) {
        val message = profileLibraryState.message ?: return@LaunchedEffect
        error = when (message) {
            ProfileLibraryMessage.STORAGE_UNAVAILABLE -> savedDataUnavailable
            ProfileLibraryMessage.SAVE_FAILED -> profileSaveError
        }
        notice = null
        profileLibraryModel.consumeMessage(message)
    }
    LaunchedEffect(remoteOperationState.profilesLoaded, remoteOperationState.storageUnavailable) {
        if (remoteOperationState.profilesLoaded && remoteOperationState.storageUnavailable) {
            error = savedDataUnavailable
        }
    }
    val keepScreenAwake = appSettings.keepScreenAwakeWhileWorking &&
        (importing || projectTransferBusy || autoLaying || arranging || splitting || cutting || slicing ||
            previewLoading || exportingGcode || remoteBusy || profileBusy)
    DisposableEffect(keepScreenAwake) {
        val window = (context as? MainActivity)?.window
        if (keepScreenAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
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

    LaunchedEffect(profileLibraryState.transferCompletion?.id) {
        val completion = profileLibraryState.transferCompletion ?: return@LaunchedEffect
        when (completion.outcome) {
            ProfileTransferOutcome.SUCCEEDED -> {
                error = null
                notice = if (completion.direction == ProfileTransferDirection.IMPORT) {
                    val imported = completion.importResult?.importedTotal ?: 0
                    if (imported == 0) {
                        profilesUnchangedNotice
                    } else {
                        resources.getString(R.string.profiles_imported, imported)
                    }
                } else {
                    profilesExportedNotice
                }
            }
            ProfileTransferOutcome.CANCELED -> {
                error = null
                notice = if (completion.direction == ProfileTransferDirection.IMPORT) {
                    profileImportCanceledNotice
                } else {
                    profileExportCanceledNotice
                }
            }
            ProfileTransferOutcome.FAILED -> {
                notice = null
                error = if (completion.direction == ProfileTransferDirection.IMPORT) {
                    profileImportError
                } else {
                    profileExportError
                }
            }
        }
        externalProfileRequest
            ?.takeIf { request -> request.startedOperationId == completion.id }
            ?.let { request ->
                onExternalProfileRequestConsumed(request.id, completion.id)
            }
        profileLibraryModel.consumeTransferCompletion(completion.id)
    }

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

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (
            uri != null && projectRestored && !projectTransferBusy && !slicing &&
            !previewLoading && projectTransferModel.importModels(uri)
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

    val projectOpenPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importProject)
    }

    LaunchedEffect(
        externalProjectRequest?.id,
        projectRestored,
        projectHistory.current.allObjects.isNotEmpty(),
        projectTransferBusy,
        projectTransferState.completion?.id,
        importing,
        autoLaying,
        arranging,
        splitting,
        cutting,
        slicing,
        previewLoading,
    ) {
        val request = externalProjectRequest ?: return@LaunchedEffect
        if (
            !projectRestored || projectTransferBusy || importing || autoLaying ||
            arranging || splitting || cutting || slicing || previewLoading || projectTransferState.completion != null
        ) return@LaunchedEffect
        if (projectHistory.current.allObjects.isEmpty()) {
            importProject(request.uri)
        } else {
            externalProjectConfirmation = request
        }
    }

    val projectSavePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE),
    ) { uri ->
        if (
            uri != null && projectRestored && !projectTransferBusy && !importing &&
            !autoLaying && !arranging && !splitting && !cutting && !slicing && !previewLoading
        ) {
            if (
                projectTransferModel.exportProject(
                    uri,
                    projectHistory.current,
                    projectTransferState.plateOptions,
                )
            ) {
                error = null
                notice = null
            }
        }
    }

    val modelExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(THREE_MF_DOCUMENT_MIME_TYPE),
    ) { uri ->
        if (
            uri != null && projectRestored && !projectTransferBusy && !importing &&
            !autoLaying && !arranging && !splitting && !cutting && !slicing && !previewLoading
        ) {
            val snapshot = projectTransferModel.state.value.history.current
            val options = projectTransferModel.state.value.plateOptions[snapshot.selectedPlateId]
                ?: projectTransferModel.state.value.sliceOptions
            if (projectTransferModel.exportThreeMf(uri, snapshot, options)) {
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

    fun beginSlice() {
        val session = projectTransferModel.state.value
        val input = session.history.current.sliceInput(session.plateOptions) ?: return
        if (
            !slicing && !importing && !projectTransferBusy && !autoLaying && !arranging &&
            !splitting && !cutting && !previewLoading &&
            sliceOperationModel.start(input.plateId, input.objects, input.options)
        ) {
            plateSliceResults = plateSliceResults.clear(input.plateId)
            layerPreview = null
            remoteOperationModel.invalidateUpload()
            error = null
            notice = null
        }
    }
    val sliceNotificationPreferences = remember(context) {
        context.getSharedPreferences(SLICE_NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        sliceNotificationPreferences.edit()
            .putBoolean(SLICE_NOTIFICATION_PERMISSION_ASKED, true)
            .apply()
        beginSlice()
    }
    val startSlice = {
        val shouldRequestNotification =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
                !sliceNotificationPreferences.getBoolean(
                    SLICE_NOTIFICATION_PERMISSION_ASKED,
                    false,
                )
        if (shouldRequestNotification) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginSlice()
        }
    }

    val cancelSlice = {
        sliceOperationModel.cancel()
    }

    val saveGcode = {
        val requested = plateSliceResults.resultFor(selectedPlateId)
        if (requested != null && projectObjects.isNotEmpty() && !exportingGcode) {
            pendingGcodeExport = requested
            savePicker.launch(requested.outcome.suggestedName)
        }
    }

    fun selectedRemoteDevice(): RemoteDeviceProfile? = remoteOperationState.selectedProfile()

    WorkspaceScreen(
        selectedTab = selectedTab,
        projectPlates = projectPlates,
        selectedPlateId = selectedPlateId,
        projectObjects = projectObjects,
        selectedObjectId = projectHistory.current.selectedObjectId,
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
        layerPreview = layerPreview,
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
        slicing = slicing,
        sliceCancellationRequested = sliceCancellationRequested,
        sliceProgress = sliceProgress,
        previewLoading = previewLoading,
        exportingGcode = exportingGcode,
        gcodeExportCancellationRequested = gcodeExportCancellationRequested,
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
        onSaveProject = { projectSavePicker.launch(DEFAULT_PROJECT_ARCHIVE_NAME) },
        onExportModel = {
            val sourceName = projectHistory.current.selectedObject
                ?.primaryModelPart?.model?.fileName
                ?: projectObjects.firstOrNull()?.primaryModelPart?.model?.fileName
            val suggestedName = sourceName?.let {
                "${threeMfDisplayName(it, "DuckySlicer-model")}.3mf"
            } ?: DEFAULT_THREE_MF_NAME
            modelExportPicker.launch(suggestedName)
        },
        onPlateSelected = { plateId ->
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy && plateId != selectedPlateId
            ) {
                val current = projectTransferModel.state.value.history
                val next = current.selectPlate(plateId)
                if (projectTransferModel.updateHistory(current, next)) {
                    sliceOperationModel.clearCompleted()
                    layerPreview = null
                    remoteOperationModel.invalidateUpload()
                    notice = null
                    error = null
                }
            }
        },
        onAddPlate = {
            if (
                !projectTransferBusy && !slicing && !previewLoading && !exportingGcode &&
                !remoteBusy && projectPlates.size < MAX_PROJECT_PLATES
            ) {
                val current = projectTransferModel.state.value.history
                val next = current.addPlate(UUID.randomUUID().toString())
                if (projectTransferModel.updateHistory(current, next)) {
                    sliceOperationModel.clearCompleted()
                    layerPreview = null
                    remoteOperationModel.invalidateUpload()
                    selectedTab = WorkspaceTab.SLICE
                    notice = null
                    error = null
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
        onDuplicate = {
            val current = projectTransferModel.state.value.history
            val nextHistory = current.duplicateSelected(UUID.randomUUID().toString())
            if (projectTransferModel.updateHistory(current, nextHistory)) {
                clearCompletedSlice()
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
        onSupportPaintPreview = { objectId, volumeId, targets, state ->
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
                    clearCompletedSlice()
                    notice = null
                }
            }
        },
        onSupportPaintCommitted = { objectId, volumeId, previousPaint, previousAnnotation ->
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
        },
        onSeamPaintPreview = { objectId, volumeId, targets, state ->
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
                    clearCompletedSlice()
                    notice = null
                }
            }
        },
        onSeamPaintCommitted = { objectId, volumeId, previousPaint, previousAnnotation ->
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
        },
        onBrimPointsChanged = { objectId, brimPoints ->
            val current = projectTransferModel.state.value.history
            val nextHistory = current.updateBrimPoints(objectId, brimPoints)
            if (projectTransferModel.updateHistory(current, nextHistory)) {
                clearCompletedSlice()
                notice = null
                error = null
            }
        },
        onMultiColorPaintPreview = { objectId, volumeId, targets, slot ->
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
                    clearCompletedSlice()
                    notice = null
                }
            }
        },
        onMultiColorPaintCommitted = { objectId, volumeId, previousPaint, previousAnnotation ->
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
        },
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
        onRemoveModel = {
            val current = projectTransferModel.state.value.history
            if (
                projectTransferModel.updateHistory(
                    current,
                    current.removeSelected(),
                )
            ) {
                clearCompletedSlice()
                notice = null
                error = null
                selectedTab = WorkspaceTab.SLICE
            }
        },
        onSlice = startSlice,
        onCancelSlice = cancelSlice,
        onSave = saveGcode,
        onCancelGcodeExport = gcodeExportModel::cancelActiveExport,
        onSliceOptionsChanged = ::applyOptions,
        onSavePrinterProfile = { name, options ->
            if (
                !profileLibraryModel.savePrinter(
                    name,
                    options,
                    projectTransferModel.state.value.sessionRevision,
                )
            ) {
                error = profileSaveError
                notice = null
            }
        },
        onSaveFilamentProfile = { name, options, slot ->
            if (
                !profileLibraryModel.saveFilament(
                    name,
                    options,
                    slot,
                    projectTransferModel.state.value.sessionRevision,
                )
            ) {
                error = profileSaveError
                notice = null
            }
        },
        onSaveSlicingProfile = { name, options ->
            if (
                !profileLibraryModel.saveSlicing(
                    name,
                    options,
                    projectTransferModel.state.value.sessionRevision,
                )
            ) {
                error = profileSaveError
                notice = null
            }
        },
        onLayerRangeSelected = loadPreviewRange,
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
            onConfirm = {
                externalProjectConfirmation = null
                importProject(request.uri)
            },
            onDismiss = {
                externalProjectConfirmation = null
                onExternalProjectRequestConsumed(request.id)
            },
        )
    }
}

internal fun initialWorkspaceReady(
    projectRestored: Boolean,
    profileCatalogLoaded: Boolean,
    profileRecentsLoaded: Boolean,
): Boolean = projectRestored && profileCatalogLoaded && profileRecentsLoaded

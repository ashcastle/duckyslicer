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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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
private const val SLICE_NOTIFICATION_PREFERENCES = "slice_notifications"
private const val SLICE_NOTIFICATION_PERMISSION_ASKED = "permission_asked"
private const val DEFAULT_PROJECT_ARCHIVE_NAME = "DuckySlicer-project$PROJECT_ARCHIVE_FILE_EXTENSION"

data class ModelInfo(
    val fileName: String,
    val triangles: Int,
    val dimensions: List<Double>,
    val localPath: String,
    val minMm: List<Double>,
    val maxMm: List<Double>,
    val previewTriangles: FloatArray,
    val previewTriangleIndices: IntArray = IntArray(previewTriangles.size / 9) { it },
) {
    companion object {
        fun fromJson(raw: String, localPath: String): ModelInfo {
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { "model_invalid" }
            val values = json.getJSONArray("dimensionsMm")
            val minValues = json.getJSONArray("minMm")
            val maxValues = json.getJSONArray("maxMm")
            val triangleValues = json.getJSONArray("previewTriangles")
            val triangleIndices = json.getJSONArray("previewTriangleIndices")
            check(triangleIndices.length() == triangleValues.length()) { "model_invalid" }
            val previewTriangles = FloatArray(triangleValues.length() * 9)
            repeat(triangleValues.length()) { triangleIndex ->
                val triangle = triangleValues.getJSONArray(triangleIndex)
                repeat(9) { valueIndex ->
                    previewTriangles[triangleIndex * 9 + valueIndex] = triangle.getDouble(valueIndex).toFloat()
                }
            }
            return ModelInfo(
                fileName = json.getString("fileName"),
                triangles = json.getInt("triangles"),
                dimensions = List(3) { index -> values.getDouble(index) },
                localPath = localPath,
                minMm = List(3) { index -> minValues.getDouble(index) },
                maxMm = List(3) { index -> maxValues.getDouble(index) },
                previewTriangles = previewTriangles,
                previewTriangleIndices = IntArray(triangleIndices.length()) { index ->
                    triangleIndices.getInt(index).also { sourceIndex ->
                        check(sourceIndex in 0 until json.getInt("triangles")) { "model_invalid" }
                    }
                },
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var externalProjectModel: ExternalProjectRequestViewModel
    private lateinit var projectTransferModel: ProjectTransferViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sliceOperationModel = ViewModelProvider(this)[SliceOperationViewModel::class.java]
        val remoteOperationModel = ViewModelProvider(this)[RemoteOperationViewModel::class.java]
        val profileLibraryModel = ViewModelProvider(this)[ProfileLibraryViewModel::class.java]
        val appSettingsModel = ViewModelProvider(this)[AppSettingsViewModel::class.java]
        externalProjectModel = ViewModelProvider(this)[ExternalProjectRequestViewModel::class.java]
        projectTransferModel = ViewModelProvider(this)[ProjectTransferViewModel::class.java]
        if (savedInstanceState == null) externalProjectModel.enqueue(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = DuckyColors) {
                val externalProjectRequest by
                    externalProjectModel.request.collectAsStateWithLifecycle()
                DuckySlicerScreen(
                    sliceOperationModel = sliceOperationModel,
                    remoteOperationModel = remoteOperationModel,
                    profileLibraryModel = profileLibraryModel,
                    appSettingsModel = appSettingsModel,
                    projectTransferModel = projectTransferModel,
                    externalProjectRequest = externalProjectRequest,
                    onExternalProjectRequestConsumed = externalProjectModel::consume,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalProjectModel.enqueue(intent)
    }
}

@Composable
private fun DuckySlicerScreen(
    sliceOperationModel: SliceOperationViewModel,
    remoteOperationModel: RemoteOperationViewModel,
    profileLibraryModel: ProfileLibraryViewModel,
    appSettingsModel: AppSettingsViewModel,
    projectTransferModel: ProjectTransferViewModel,
    externalProjectRequest: ExternalProjectRequest?,
    onExternalProjectRequestConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val modelReadError = stringResource(R.string.model_read_error)
    val modelTooLargeError = stringResource(R.string.model_too_large_error)
    val shapeError = stringResource(R.string.shape_error)
    val autoLayDone = stringResource(R.string.auto_lay_done)
    val autoLayError = stringResource(R.string.auto_lay_error)
    val layOnFaceDone = stringResource(R.string.lay_on_face_done)
    val layOnFaceError = stringResource(R.string.lay_on_face_error)
    val arrangeDone = stringResource(R.string.arrange_done)
    val arrangeError = stringResource(R.string.arrange_error)
    val splitNotPossible = stringResource(R.string.split_not_possible)
    val splitError = stringResource(R.string.split_error)
    val cutNotPossible = stringResource(R.string.cut_not_possible)
    val cutError = stringResource(R.string.cut_error)
    val sliceError = stringResource(R.string.slice_error)
    val sliceCanceledNotice = stringResource(R.string.slice_canceled)
    val saveError = stringResource(R.string.save_error)
    val savedNotice = stringResource(R.string.gcode_saved)
    val profileSavedNotice = stringResource(R.string.profile_saved)
    val profileSaveError = stringResource(R.string.profile_save_error)
    val filamentSlotUnavailable = stringResource(R.string.filament_slot_unavailable)
    val projectSaveError = stringResource(R.string.project_save_error)
    val projectOpenedNotice = stringResource(R.string.project_opened)
    val projectSavedNotice = stringResource(R.string.project_saved)
    val projectOpenError = stringResource(R.string.project_open_error)
    val projectExportError = stringResource(R.string.project_export_error)
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
    val remoteConnectionError = stringResource(R.string.device_connection_error)
    val remoteUnauthorizedError = stringResource(R.string.device_access_denied)
    val remoteCommandError = stringResource(R.string.device_command_error)
    val remoteSaveError = stringResource(R.string.device_save_error)

    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var externalProjectConfirmation by remember { mutableStateOf<ExternalProjectRequest?>(null) }
    var autoLaying by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    var splitting by remember { mutableStateOf(false) }
    var cutting by remember { mutableStateOf(false) }
    var sliceOutcome by rememberSaveable { mutableStateOf<SliceOutcome?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.SLICE) }
    var layerPreview by remember { mutableStateOf<GcodeLayerPreview?>(null) }
    val sliceOperationState by sliceOperationModel.state.collectAsStateWithLifecycle()
    val slicing = sliceOperationState.slicing
    val sliceCancellationRequested = sliceOperationState.cancellationRequested
    val sliceProgress = sliceOperationState.progress
    val previewLoading = sliceOperationState.previewLoading
    val projectTransferState by projectTransferModel.state.collectAsStateWithLifecycle()
    val projectTransferBusy = projectTransferState.busy
    val projectHistory = projectTransferState.history
    val projectRestored = projectTransferState.restored
    val remoteOperationState by remoteOperationModel.state.collectAsStateWithLifecycle()
    val profileLibraryState by profileLibraryModel.state.collectAsStateWithLifecycle()
    val appSettingsState by appSettingsModel.state.collectAsStateWithLifecycle()
    val sliceOptions = projectTransferState.sliceOptions
    val projectObjects = projectHistory.current.objects
    val selectedProjectObject = projectHistory.current.selectedObject
    val model = selectedProjectObject?.model ?: projectObjects.firstOrNull()?.model
    val modelTransform = selectedProjectObject?.transform ?: ModelTransform()
    val projectStore = remember(context.applicationContext) { ProjectStore(context.applicationContext) }
    val profileCatalog = profileLibraryState.catalog
    val profileRecents = profileLibraryState.recents
    val profileRecentsLoaded = profileLibraryState.recentsLoaded
    val profileBusy = profileLibraryState.busy || profileLibraryState.completion != null
    val supportEvents = remember(context.applicationContext) {
        SupportEventJournal(context.applicationContext)
    }
    val appSettings = appSettingsState.settings
    val remoteDevices = remoteOperationState.profiles
    val selectedRemoteDeviceId = remoteOperationState.selectedProfileId
    val remoteStatus = remoteOperationState.statusFor(selectedRemoteDeviceId)
    val remoteUpload = remoteOperationState.uploadFor(selectedRemoteDeviceId)
    val remoteUploadProgress = remoteOperationState.progressFor(selectedRemoteDeviceId)
    val remoteOperationMessage = remoteOperationState.messageFor(selectedRemoteDeviceId)
    val remoteMessage = when (remoteOperationMessage) {
        RemoteOperationMessage.CONNECTED -> remoteConnectedNotice
        RemoteOperationMessage.UPLOADED -> remoteUploadNotice
        RemoteOperationMessage.STARTED -> remoteStartedNotice
        RemoteOperationMessage.PAUSED -> remotePausedNotice
        RemoteOperationMessage.RESUMED -> remoteResumedNotice
        RemoteOperationMessage.CANCELED -> remoteCanceledNotice
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

    fun clearCompletedSlice() {
        sliceOperationModel.clearCompleted()
        sliceOutcome = null
        layerPreview = null
        remoteOperationModel.invalidateUpload()
    }

    LaunchedEffect(projectTransferState.completion?.id) {
        val completion = projectTransferState.completion ?: return@LaunchedEffect
        when (completion) {
            is ProjectTransferCompletion.Imported -> {
                clearCompletedSlice()
                externalProjectConfirmation = null
                notice = projectOpenedNotice
                error = null
                if (externalProjectRequest?.uri == completion.uri) {
                    onExternalProjectRequestConsumed(externalProjectRequest.id)
                }
            }
            is ProjectTransferCompletion.Exported -> {
                notice = projectSavedNotice
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
                    supportEvents.record(SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED)
                    error = projectExportError
                }
                externalProjectConfirmation = null
                notice = null
            }
        }
        projectTransferModel.consumeCompletion(completion.id)
    }

    LaunchedEffect(sliceOutcome?.output?.absolutePath) {
        val restored = sliceOutcome ?: return@LaunchedEffect
        if (!restored.isRestorableFrom(context.filesDir)) {
            clearCompletedSlice()
            if (selectedTab == WorkspaceTab.PREVIEW) selectedTab = WorkspaceTab.SLICE
        }
    }
    LaunchedEffect(sliceOperationState.outcome, sliceOperationState.preview) {
        val completed = sliceOperationState.outcome ?: return@LaunchedEffect
        sliceOutcome = completed
        sliceOperationState.preview?.let { layerPreview = it }
        remoteOperationModel.invalidateUpload()
        selectedTab = WorkspaceTab.PREVIEW
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
            previewLoading || remoteBusy || profileBusy)
    DisposableEffect(keepScreenAwake) {
        val window = (context as? MainActivity)?.window
        if (keepScreenAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(sliceOperationModel) {
        onDispose {
            if (!sliceOperationModel.state.value.busy) {
                SlicerProcessClient.cancelActiveSliceAsync()
            }
        }
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

    fun applyModelTransform(transform: ModelTransform, recordHistory: Boolean = true) {
        val current = projectTransferModel.state.value.history
        val nextHistory = current.updateSelectedTransform(transform, recordHistory)
        if (nextHistory != current && projectTransferModel.updateHistory(current, nextHistory)) {
            clearCompletedSlice()
            notice = null
        }
    }

    fun autoLaySelectedModel() {
        val target = projectHistory.current.selectedObject ?: return
        if (autoLaying || arranging || splitting || cutting || importing || slicing || previewLoading) return
        autoLaying = true
        error = null
        notice = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    SlicerProcessClient.autoOrient(File(target.model.localPath))
                }
            }.onSuccess { orientation ->
                val current = projectTransferModel.state.value.history
                val currentTarget = current.current.objects.firstOrNull { it.id == target.id }
                if (currentTarget != null) {
                    val nextHistory = current.updateTransform(
                        target.id,
                        currentTarget.transform.withOrcaOrientation(orientation),
                    )
                    if (
                        nextHistory != current &&
                        projectTransferModel.updateHistory(current, nextHistory)
                    ) {
                        clearCompletedSlice()
                    }
                    notice = autoLayDone
                    error = null
                }
            }.onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Automatic lay failed", failure)
                supportEvents.record(SupportEvent.AUTO_LAY_FAILED)
                error = autoLayError
                notice = null
            }
            autoLaying = false
        }
    }

    fun laySelectedFaceOnBed(objectId: String, triangle: FloatArray) {
        if (autoLaying || arranging || splitting || cutting || importing || slicing || previewLoading) return
        val target = projectHistory.current.objects.firstOrNull { it.id == objectId } ?: return
        runCatching { target.transform.withFaceOnBed(triangle) }
            .onSuccess { transform ->
                val current = projectTransferModel.state.value.history
                val nextHistory = current.updateTransform(objectId, transform)
                if (
                    nextHistory != current &&
                    projectTransferModel.updateHistory(current, nextHistory)
                ) {
                    clearCompletedSlice()
                }
                notice = layOnFaceDone
                error = null
            }
            .onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Place on face failed", failure)
                supportEvents.record(SupportEvent.LAY_ON_FACE_FAILED)
                error = layOnFaceError
                notice = null
            }
    }

    fun arrangeProjectObjects() {
        val targets = projectHistory.current.objects
        if (targets.size < 2 || arranging || autoLaying || splitting || cutting || importing || slicing || previewLoading) return
        arranging = true
        error = null
        notice = null
        clearCompletedSlice()
        val targetOptions = sliceOptions
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { OnDeviceSlicer.arrange(targets, targetOptions) }
            }.onSuccess { arrangement ->
                val current = projectTransferModel.state.value.history
                val currentObjects = current.current.objects
                if (currentObjects.map(ProjectObject::id) == targets.map(ProjectObject::id) &&
                    currentObjects.map(ProjectObject::transform) == targets.map(ProjectObject::transform)
                ) {
                    val nextHistory = current.applyOrcaArrangement(
                        arrangement,
                        targetOptions.bedSizeX,
                        targetOptions.bedSizeY,
                    )
                    if (projectTransferModel.updateHistory(current, nextHistory)) {
                        notice = arrangeDone
                        error = null
                    }
                }
            }.onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Automatic arrangement failed", failure)
                supportEvents.record(SupportEvent.ARRANGE_FAILED)
                error = arrangeError
                notice = null
            }
            arranging = false
        }
    }

    fun splitSelectedModel() {
        val target = projectHistory.current.selectedObject ?: return
        if (splitting || cutting || arranging || autoLaying || importing || slicing || previewLoading) return
        val maximumObjects = ProjectStore.MAX_PROJECT_OBJECTS - projectObjects.size + 1
        if (maximumObjects < 2) {
            error = splitError
            notice = null
            return
        }
        splitting = true
        error = null
        notice = null
        clearCompletedSlice()
        val targetOptions = sliceOptions
        scope.launch {
            runCatching {
                splitProjectObject(target, projectStore, targetOptions, maximumObjects)
            }.onSuccess { result ->
                val current = projectTransferModel.state.value.history
                val currentTarget = current.current.selectedObject
                if (
                    currentTarget?.id == target.id &&
                    currentTarget.model.localPath == target.model.localPath &&
                    currentTarget.transform == target.transform &&
                    currentTarget.supportPaint == target.supportPaint &&
                    currentTarget.seamPaint == target.seamPaint &&
                    currentTarget.multiColorPaint == target.multiColorPaint &&
                    currentTarget.variableLayerHeights == target.variableLayerHeights &&
                    currentTarget.processOverrides == target.processOverrides &&
                    currentTarget.filamentSlot == target.filamentSlot
                ) {
                    val nextHistory = current.replaceSelected(result.objects)
                    if (projectTransferModel.updateHistory(current, nextHistory)) {
                        notice = resources.getString(
                            if (result.clearedObjectSettings) {
                                R.string.split_done_painting_cleared
                            } else {
                                R.string.split_done
                            },
                            result.objects.size,
                        )
                        error = null
                        selectedTab = WorkspaceTab.SLICE
                    } else {
                        result.objects.forEach { File(it.model.localPath).delete() }
                    }
                } else {
                    result.objects.forEach { File(it.model.localPath).delete() }
                }
            }.onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Model split failed", failure)
                error = if (failure is ModelNotSplittableException) splitNotPossible else splitError
                notice = null
            }
            splitting = false
        }
    }

    fun cutSelectedModel(heightRatio: Float, placeOnCut: Boolean) {
        val target = projectHistory.current.selectedObject ?: return
        if (cutting || splitting || arranging || autoLaying || importing || slicing || previewLoading) return
        val maximumObjects = ProjectStore.MAX_PROJECT_OBJECTS - projectObjects.size + 1
        if (maximumObjects < 2) {
            error = cutError
            notice = null
            return
        }
        cutting = true
        error = null
        notice = null
        clearCompletedSlice()
        val targetOptions = sliceOptions
        scope.launch {
            runCatching {
                cutProjectObject(
                    target,
                    projectStore,
                    targetOptions,
                    heightRatio,
                    placeOnCut,
                    maximumObjects,
                )
            }.onSuccess { result ->
                val current = projectTransferModel.state.value.history
                val currentTarget = current.current.selectedObject
                if (
                    currentTarget?.id == target.id &&
                    currentTarget.model.localPath == target.model.localPath &&
                    currentTarget.transform == target.transform &&
                    currentTarget.supportPaint == target.supportPaint &&
                    currentTarget.seamPaint == target.seamPaint &&
                    currentTarget.multiColorPaint == target.multiColorPaint &&
                    currentTarget.variableLayerHeights == target.variableLayerHeights &&
                    currentTarget.processOverrides == target.processOverrides &&
                    currentTarget.filamentSlot == target.filamentSlot
                ) {
                    val nextHistory = current.replaceSelected(result.objects)
                    if (projectTransferModel.updateHistory(current, nextHistory)) {
                        notice = resources.getString(
                            if (result.clearedObjectSettings) {
                                R.string.cut_done_painting_cleared
                            } else {
                                R.string.cut_done
                            },
                        )
                        error = null
                        selectedTab = WorkspaceTab.SLICE
                    } else {
                        result.objects.forEach { File(it.model.localPath).delete() }
                    }
                } else {
                    result.objects.forEach { File(it.model.localPath).delete() }
                }
            }.onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Model cut failed", failure)
                error = if (failure is ModelNotCuttableException) cutNotPossible else cutError
                notice = null
            }
            cutting = false
        }
    }

    fun addPrimitive(primitive: OrcaPrimitive, sizeMm: Float) {
        if (
            importing || projectTransferBusy || !projectRestored || autoLaying || arranging ||
            splitting || cutting || slicing || previewLoading
        ) return
        if (projectObjects.size >= ProjectStore.MAX_PROJECT_OBJECTS) {
            error = shapeError
            notice = null
            return
        }
        importing = true
        error = null
        notice = null
        val displayName = resources.getString(primitive.label)
        val objectIndex = projectObjects.size
        scope.launch {
            runCatching {
                createOrcaPrimitive(primitive, sizeMm, displayName, projectStore)
            }.onSuccess { created ->
                val distance = ((objectIndex + 1) / 2) * 24f
                val offset = when {
                    objectIndex == 0 -> 0f
                    objectIndex % 2 == 1 -> distance
                    else -> -distance
                }
                val current = projectTransferModel.state.value.history
                val nextHistory = current.addAll(
                    listOf(
                        created.copy(
                            transform = created.transform.copy(offsetXmm = offset),
                        ),
                    ),
                )
                if (projectTransferModel.updateHistory(current, nextHistory)) {
                    clearCompletedSlice()
                    selectedTab = WorkspaceTab.SLICE
                    notice = resources.getString(R.string.shape_added, displayName)
                } else {
                    File(created.model.localPath).delete()
                }
            }.onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Shape creation failed", failure)
                error = shapeError
                notice = null
            }
            importing = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && projectRestored && !autoLaying && !arranging && !splitting && !cutting && !slicing && !previewLoading) {
            importing = true
            error = null
            notice = null
            scope.launch {
                runCatching { importOrcaModels(context, uri, projectStore, sliceOptions) }
                    .onSuccess { importedObjects ->
                        val current = projectTransferModel.state.value.history
                        val objectIndex = current.current.objects.size
                        val distance = ((objectIndex + 1) / 2) * 24f
                        val offset = when {
                            objectIndex == 0 -> 0f
                            objectIndex % 2 == 1 -> distance
                            else -> -distance
                        }
                        val placedObjects = importedObjects.map { imported ->
                            imported.copy(
                                transform = imported.transform.copy(
                                    offsetXmm = imported.transform.offsetXmm + offset,
                                ),
                            )
                        }
                        val nextHistory = current.addAll(placedObjects)
                        if (projectTransferModel.updateHistory(current, nextHistory)) {
                            clearCompletedSlice()
                            selectedTab = WorkspaceTab.SLICE
                        } else {
                            importedObjects.forEach { File(it.model.localPath).delete() }
                        }
                    }
                    .onFailure { failure ->
                        if (failure is ModelTooLargeException) {
                            supportEvents.record(SupportEvent.MODEL_TOO_LARGE)
                            error = modelTooLargeError
                        } else {
                            supportEvents.record(SupportEvent.MODEL_IMPORT_FAILED)
                            error = modelReadError
                        }
                    }
                importing = false
            }
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GCODE_DOCUMENT_MIME_TYPE),
    ) { uri ->
        val completed = sliceOutcome
        if (uri != null && completed != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri).use { output ->
                            requireNotNull(output) { "output_unavailable" }
                            SliceArtifactLease.acquire(completed.output).use {
                                completed.output.inputStream().use { input -> input.copyTo(output) }
                            }
                        }
                    }
                }.onSuccess {
                    notice = savedNotice
                    error = null
                }.onFailure {
                    supportEvents.record(SupportEvent.GCODE_EXPORT_FAILED)
                    error = saveError
                    notice = null
                }
            }
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
        projectObjects.isNotEmpty(),
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
        if (projectObjects.isEmpty()) {
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
            if (projectTransferModel.exportProject(uri, projectHistory.current, sliceOptions)) {
                error = null
                notice = null
            }
        }
    }

    val loadPreviewRange: (Int, Int) -> Unit = { startLayer, endLayer ->
        val completed = sliceOutcome
        if (completed != null && !slicing && !autoLaying && !arranging && !splitting && !cutting) {
            sliceOperationModel.loadPreview(completed, startLayer, endLayer)
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
        val objects = projectObjects
        if (
            objects.isNotEmpty() &&
            !slicing && !importing && !projectTransferBusy && !autoLaying && !arranging &&
            !splitting && !cutting && !previewLoading &&
            sliceOperationModel.start(objects, sliceOptions)
        ) {
            sliceOutcome = null
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
        val completed = sliceOutcome
        val selected = selectedProjectObject?.model ?: projectObjects.firstOrNull()?.model
        if (completed != null && selected != null) {
            val baseName = if (projectObjects.size > 1) {
                "project"
            } else {
                selected.fileName.substringBeforeLast('.').ifBlank { "model" }
            }
            savePicker.launch("$baseName.gcode")
        }
    }

    fun selectedRemoteDevice(): RemoteDeviceProfile? = remoteOperationState.selectedProfile()

    WorkspaceScreen(
        selectedTab = selectedTab,
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
        remoteMessage = remoteMessage,
        remoteMessageIsError = remoteMessageIsError,
        profileBusy = profileBusy,
        appSettingsSaveFailed = appSettingsState.message == AppSettingsMessage.SAVE_FAILED,
        sliceOutcome = sliceOutcome,
        layerPreview = layerPreview,
        importing = importing || projectTransferBusy || !projectRestored,
        autoLaying = autoLaying,
        arranging = arranging,
        splitting = splitting,
        cutting = cutting,
        slicing = slicing,
        sliceCancellationRequested = sliceCancellationRequested,
        sliceProgress = sliceProgress,
        previewLoading = previewLoading,
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
        onCreatePrimitive = ::addPrimitive,
        onOpenProject = {
            projectOpenPicker.launch(
                arrayOf(PROJECT_ARCHIVE_MIME_TYPE, "application/zip"),
            )
        },
        onSaveProject = { projectSavePicker.launch(DEFAULT_PROJECT_ARCHIVE_NAME) },
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
                if (projectTransferModel.updateHistory(current, current.undo())) {
                    clearCompletedSlice()
                }
            }
        },
        onRedo = {
            val current = projectTransferModel.state.value.history
            if (current.canRedo) {
                if (projectTransferModel.updateHistory(current, current.redo())) {
                    clearCompletedSlice()
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
        onCut = ::cutSelectedModel,
        onSupportPaintPreview = { objectId, facetIndex, state ->
            val current = projectTransferModel.state.value.history
            val projectObject = current.current.objects.firstOrNull { it.id == objectId }
            if (projectObject != null && facetIndex in 0 until projectObject.model.triangles) {
                val nextPaint = projectObject.supportPaint.paint(facetIndex, state)
                val nextHistory = current.updateSupportPaint(
                    objectId,
                    nextPaint,
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
        onSupportPaintCommitted = { objectId, previous ->
            val current = projectTransferModel.state.value.history
            projectTransferModel.updateHistory(
                current,
                current.commitSupportPaint(objectId, previous),
            )
        },
        onSeamPaintPreview = { objectId, facetIndex, state ->
            val current = projectTransferModel.state.value.history
            val projectObject = current.current.objects.firstOrNull { it.id == objectId }
            if (projectObject != null && facetIndex in 0 until projectObject.model.triangles) {
                val nextPaint = projectObject.seamPaint.paint(facetIndex, state)
                val nextHistory = current.updateSeamPaint(
                    objectId,
                    nextPaint,
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
        onSeamPaintCommitted = { objectId, previous ->
            val current = projectTransferModel.state.value.history
            projectTransferModel.updateHistory(
                current,
                current.commitSeamPaint(objectId, previous),
            )
        },
        onMultiColorPaintPreview = { objectId, facetIndex, slot ->
            val session = projectTransferModel.state.value
            val current = session.history
            val projectObject = current.current.objects.firstOrNull { it.id == objectId }
            val availableSlots = session.sliceOptions.resolvedFilamentSlots().indices
            if (
                projectObject != null &&
                facetIndex in 0 until projectObject.model.triangles &&
                (slot == null || slot in availableSlots)
            ) {
                val nextPaint = projectObject.multiColorPaint.paint(facetIndex, slot)
                val nextHistory = current.updateMultiColorPaint(
                    objectId,
                    nextPaint,
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
        onMultiColorPaintCommitted = { objectId, previous ->
            val current = projectTransferModel.state.value.history
            projectTransferModel.updateHistory(
                current,
                current.commitMultiColorPaint(objectId, previous),
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
            val output = sliceOutcome?.output
            if (profile != null && output != null && !remoteBusy) {
                remoteOperationModel.upload(
                    profile,
                    output,
                    appSettings.connectionTimeoutSeconds,
                )
            }
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

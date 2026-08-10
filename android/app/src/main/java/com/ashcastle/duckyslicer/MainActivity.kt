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
import kotlinx.coroutines.delay
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
    projectTransferModel: ProjectTransferViewModel,
    externalProjectRequest: ExternalProjectRequest?,
    onExternalProjectRequestConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val modelReadError = stringResource(R.string.model_read_error)
    val modelTooLargeError = stringResource(R.string.model_too_large_error)
    val autoLayDone = stringResource(R.string.auto_lay_done)
    val autoLayError = stringResource(R.string.auto_lay_error)
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

    var projectHistory by remember { mutableStateOf(ProjectHistoryState()) }
    var projectRestored by remember { mutableStateOf(false) }
    var projectPersistenceBlocked by remember { mutableStateOf(false) }
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
    var sliceOptions by remember { mutableStateOf(SliceOptions()) }
    val projectObjects = projectHistory.current.objects
    val selectedProjectObject = projectHistory.current.selectedObject
    val model = selectedProjectObject?.model ?: projectObjects.firstOrNull()?.model
    val modelTransform = selectedProjectObject?.transform ?: ModelTransform()
    val profileStore = remember(context.applicationContext) { ProfileStore(context.applicationContext) }
    val profileRecentStore = remember(context.applicationContext) {
        ProfileRecentStore(context.applicationContext)
    }
    val projectStore = remember(context.applicationContext) { ProjectStore(context.applicationContext) }
    var profileCatalog by remember { mutableStateOf(ProfileCatalog()) }
    var profileRecents by remember { mutableStateOf(ProfileRecents()) }
    var profileRecentsLoaded by remember { mutableStateOf(false) }
    val appSettingsStore = remember(context.applicationContext) {
        AppSettingsStore(context.applicationContext)
    }
    val supportEvents = remember(context.applicationContext) {
        SupportEventJournal(context.applicationContext)
    }
    var appSettings by remember { mutableStateOf(appSettingsStore.load()) }
    val remoteDeviceStore = remember(context.applicationContext) {
        RemoteDeviceStore(context.applicationContext)
    }
    var remoteDevices by remember { mutableStateOf<List<RemoteDeviceProfile>>(emptyList()) }
    var selectedRemoteDeviceId by remember { mutableStateOf<String?>(null) }
    var remoteStatus by remember { mutableStateOf<RemoteDeviceStatus?>(null) }
    var remoteUpload by remember { mutableStateOf<RemoteUpload?>(null) }
    var remoteBusy by remember { mutableStateOf(false) }
    var remoteUploadProgress by remember { mutableStateOf<Int?>(null) }
    var remoteMessage by remember { mutableStateOf<String?>(null) }
    var remoteMessageIsError by remember { mutableStateOf(false) }

    fun clearCompletedSlice() {
        sliceOperationModel.clearCompleted()
        sliceOutcome = null
        layerPreview = null
    }

    LaunchedEffect(projectTransferState.completion?.id) {
        val completion = projectTransferState.completion ?: return@LaunchedEffect
        when (completion) {
            is ProjectTransferCompletion.Imported -> {
                projectHistory = ProjectHistoryState(current = completion.document.snapshot)
                completion.document.sliceOptions?.let { sliceOptions = it }
                projectPersistenceBlocked = false
                clearCompletedSlice()
                remoteUpload = null
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

    LaunchedEffect(profileStore) {
        profileCatalog = withContext(Dispatchers.IO) { profileStore.load() }
        if (profileStore.storageUnavailable) {
            supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
            error = savedDataUnavailable
        }
    }
    LaunchedEffect(profileRecentStore) {
        profileRecents = withContext(Dispatchers.IO) { profileRecentStore.load() }
        profileRecentsLoaded = true
        if (profileRecentStore.storageUnavailable) {
            supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
            error = savedDataUnavailable
        }
    }
    LaunchedEffect(projectStore) {
        val restored = withContext(Dispatchers.IO) { projectStore.loadProject() }
        projectHistory = ProjectHistoryState(current = restored.snapshot)
        restored.sliceOptions?.let { sliceOptions = it }
        projectPersistenceBlocked = restored.storageUnavailable
        if (restored.storageUnavailable) {
            supportEvents.record(SupportEvent.PROJECT_STORAGE_UNAVAILABLE)
            error = savedDataUnavailable
        }
        projectRestored = true
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
        remoteUpload = null
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
    LaunchedEffect(projectHistory.current, sliceOptions, projectRestored, projectTransferBusy) {
        if (!projectRestored || projectPersistenceBlocked || projectTransferBusy) return@LaunchedEffect
        delay(400)
        runCatching {
            withContext(Dispatchers.IO) {
                projectStore.save(projectHistory.current, sliceOptions)
            }
        }.onFailure {
            supportEvents.record(SupportEvent.PROJECT_SAVE_FAILED)
            error = projectSaveError
            notice = null
        }
    }
    LaunchedEffect(projectRestored, profileRecentsLoaded) {
        if (projectRestored && profileRecentsLoaded) {
            profileRecents = profileRecents.record(sliceOptions)
        }
    }
    LaunchedEffect(profileRecents, profileRecentsLoaded) {
        if (!profileRecentsLoaded || profileRecentStore.storageUnavailable) return@LaunchedEffect
        delay(350)
        runCatching {
            withContext(Dispatchers.IO) { profileRecentStore.save(profileRecents) }
        }.onFailure {
            supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
            error = savedDataUnavailable
            notice = null
        }
    }
    LaunchedEffect(remoteDeviceStore) {
        remoteDevices = withContext(Dispatchers.IO) { remoteDeviceStore.load() }
        if (remoteDeviceStore.storageUnavailable) {
            supportEvents.record(SupportEvent.REMOTE_STORAGE_UNAVAILABLE)
            error = savedDataUnavailable
        }
        selectedRemoteDeviceId = selectedRemoteDeviceId
            ?.takeIf { selected -> remoteDevices.any { it.id == selected } }
            ?: remoteDevices.firstOrNull()?.id
    }
    LaunchedEffect(appSettings) {
        delay(350)
        withContext(Dispatchers.IO) { appSettingsStore.save(appSettings) }
    }

    val keepScreenAwake = appSettings.keepScreenAwakeWhileWorking &&
        (importing || projectTransferBusy || autoLaying || arranging || splitting || cutting || slicing ||
            previewLoading || remoteBusy)
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
        if (options != sliceOptions) {
            val previous = sliceOptions
            var nextRecents = profileRecents
            if (options.printerProfile.id != previous.printerProfile.id) {
                nextRecents = nextRecents.recordPrinter(options.printerProfile.id)
            }
            val previousFilamentIds = previous.resolvedFilamentSlots().mapTo(mutableSetOf()) { it.id }
            options.resolvedFilamentSlots().forEach { filament ->
                if (filament.id !in previousFilamentIds) {
                    nextRecents = nextRecents.recordFilament(filament.id)
                }
            }
            if (options.quality.id != previous.quality.id) {
                nextRecents = nextRecents.recordSlicing(options.quality.id)
            }
            profileRecents = nextRecents
            projectHistory = projectHistory.constrainFilamentSlots(options.resolvedFilamentSlots().size)
            sliceOptions = options
            clearCompletedSlice()
            remoteUpload = null
            notice = null
        }
    }

    fun applyModelTransform(transform: ModelTransform, recordHistory: Boolean = true) {
        val nextHistory = projectHistory.updateSelectedTransform(transform, recordHistory)
        if (nextHistory != projectHistory) {
            projectHistory = nextHistory
            clearCompletedSlice()
            notice = null
            remoteUpload = null
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
                val currentTarget = projectHistory.current.objects.firstOrNull { it.id == target.id }
                if (currentTarget != null) {
                    val nextHistory = projectHistory.updateTransform(
                        target.id,
                        currentTarget.transform.withOrcaOrientation(orientation),
                    )
                    if (nextHistory != projectHistory) {
                        projectHistory = nextHistory
                        clearCompletedSlice()
                        remoteUpload = null
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

    fun arrangeProjectObjects() {
        val targets = projectHistory.current.objects
        if (targets.size < 2 || arranging || autoLaying || splitting || cutting || importing || slicing || previewLoading) return
        arranging = true
        error = null
        notice = null
        clearCompletedSlice()
        remoteUpload = null
        val targetOptions = sliceOptions
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { OnDeviceSlicer.arrange(targets, targetOptions) }
            }.onSuccess { arrangement ->
                val currentObjects = projectHistory.current.objects
                if (currentObjects.map(ProjectObject::id) == targets.map(ProjectObject::id) &&
                    currentObjects.map(ProjectObject::transform) == targets.map(ProjectObject::transform)
                ) {
                    projectHistory = projectHistory.applyOrcaArrangement(
                        arrangement,
                        targetOptions.bedSizeX,
                        targetOptions.bedSizeY,
                    )
                    notice = arrangeDone
                    error = null
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
        remoteUpload = null
        val targetOptions = sliceOptions
        scope.launch {
            runCatching {
                splitProjectObject(target, projectStore, targetOptions, maximumObjects)
            }.onSuccess { result ->
                val currentTarget = projectHistory.current.selectedObject
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
                    projectHistory = projectHistory.replaceSelected(result.objects)
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
        remoteUpload = null
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
                val currentTarget = projectHistory.current.selectedObject
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
                    projectHistory = projectHistory.replaceSelected(result.objects)
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
            }.onFailure { failure ->
                if (BuildConfig.DEBUG) Log.e("DuckySlicer", "Model cut failed", failure)
                error = if (failure is ModelNotCuttableException) cutNotPossible else cutError
                notice = null
            }
            cutting = false
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
                        val objectIndex = projectObjects.size
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
                        projectHistory = projectHistory.addAll(placedObjects)
                        clearCompletedSlice()
                        remoteUpload = null
                        selectedTab = WorkspaceTab.SLICE
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
            remoteUpload = null
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
            sliceNotificationPreferences.edit()
                .putBoolean(SLICE_NOTIFICATION_PERMISSION_ASKED, true)
                .apply()
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

    fun selectedRemoteDevice(): RemoteDeviceProfile? =
        remoteDevices.firstOrNull { it.id == selectedRemoteDeviceId }

    fun runRemoteCommand(
        successMessage: String,
        resultingState: String,
        operation: (RemoteDeviceClient, RemoteDeviceProfile, String) -> Unit,
    ) {
        val profile = selectedRemoteDevice() ?: return
        if (remoteBusy) return
        val settingsSnapshot = appSettings
        remoteBusy = true
        remoteUploadProgress = null
        remoteMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val credential = remoteDeviceStore.credential(profile.id)
                    operation(
                        RemoteDeviceClient(settingsSnapshot.connectionTimeoutSeconds * 1_000),
                        profile,
                        credential,
                    )
                }
            }.onSuccess {
                remoteStatus = (remoteStatus ?: RemoteDeviceStatus(resultingState)).copy(
                    state = resultingState,
                    fileName = remoteStatus?.fileName ?: remoteUpload?.displayName,
                )
                remoteMessage = successMessage
                remoteMessageIsError = false
            }.onFailure { failure ->
                supportEvents.record(
                    if (
                        failure is RemoteDeviceException &&
                        failure.statusCode in setOf(401, 403)
                    ) {
                        SupportEvent.REMOTE_AUTH_FAILED
                    } else {
                        SupportEvent.REMOTE_COMMAND_FAILED
                    },
                )
                remoteMessage = if (failure is RemoteDeviceException && failure.statusCode in setOf(401, 403)) {
                    remoteUnauthorizedError
                } else if (failure is RemoteDeviceException) {
                    remoteCommandError
                } else {
                    remoteConnectionError
                }
                remoteMessageIsError = true
            }
            remoteBusy = false
        }
    }

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
        onOpenProject = {
            projectOpenPicker.launch(
                arrayOf(PROJECT_ARCHIVE_MIME_TYPE, "application/zip"),
            )
        },
        onSaveProject = { projectSavePicker.launch(DEFAULT_PROJECT_ARCHIVE_NAME) },
        canUndo = projectHistory.canUndo,
        canRedo = projectHistory.canRedo,
        onObjectSelected = { objectId -> projectHistory = projectHistory.select(objectId) },
        onModelTransformChanged = { transform -> applyModelTransform(transform) },
        onModelTransformPreview = { transform -> applyModelTransform(transform, recordHistory = false) },
        onModelTransformCommitted = { previous ->
            projectHistory = projectHistory.commitSelectedTransform(previous)
        },
        onObjectFilamentSelected = { filament ->
            runCatching { sliceOptions.assignFilament(filament) }
                .onSuccess { assignment ->
                    applyOptions(assignment.options)
                    projectHistory = projectHistory.updateSelectedFilamentSlot(assignment.slot)
                    clearCompletedSlice()
                    remoteUpload = null
                    notice = null
                    error = null
                }
                .onFailure {
                    error = filamentSlotUnavailable
                    notice = null
                }
        },
        onUndo = {
            if (projectHistory.canUndo) {
                projectHistory = projectHistory.undo()
                clearCompletedSlice()
                remoteUpload = null
            }
        },
        onRedo = {
            if (projectHistory.canRedo) {
                projectHistory = projectHistory.redo()
                clearCompletedSlice()
                remoteUpload = null
            }
        },
        onDuplicate = {
            projectHistory = projectHistory.duplicateSelected(UUID.randomUUID().toString())
            clearCompletedSlice()
            remoteUpload = null
        },
        onArrange = ::arrangeProjectObjects,
        onAutoLay = ::autoLaySelectedModel,
        onSplit = ::splitSelectedModel,
        onCut = ::cutSelectedModel,
        onSupportPaintPreview = { objectId, facetIndex, state ->
            val projectObject = projectHistory.current.objects.firstOrNull { it.id == objectId }
            if (projectObject != null && facetIndex in 0 until projectObject.model.triangles) {
                val nextPaint = projectObject.supportPaint.paint(facetIndex, state)
                val nextHistory = projectHistory.updateSupportPaint(
                    objectId,
                    nextPaint,
                    recordHistory = false,
                )
                if (nextHistory != projectHistory) {
                    projectHistory = nextHistory
                    clearCompletedSlice()
                    remoteUpload = null
                    notice = null
                }
            }
        },
        onSupportPaintCommitted = { objectId, previous ->
            projectHistory = projectHistory.commitSupportPaint(objectId, previous)
        },
        onSeamPaintPreview = { objectId, facetIndex, state ->
            val projectObject = projectHistory.current.objects.firstOrNull { it.id == objectId }
            if (projectObject != null && facetIndex in 0 until projectObject.model.triangles) {
                val nextPaint = projectObject.seamPaint.paint(facetIndex, state)
                val nextHistory = projectHistory.updateSeamPaint(
                    objectId,
                    nextPaint,
                    recordHistory = false,
                )
                if (nextHistory != projectHistory) {
                    projectHistory = nextHistory
                    clearCompletedSlice()
                    remoteUpload = null
                    notice = null
                }
            }
        },
        onSeamPaintCommitted = { objectId, previous ->
            projectHistory = projectHistory.commitSeamPaint(objectId, previous)
        },
        onMultiColorPaintPreview = { objectId, facetIndex, slot ->
            val projectObject = projectHistory.current.objects.firstOrNull { it.id == objectId }
            val availableSlots = sliceOptions.resolvedFilamentSlots().indices
            if (
                projectObject != null &&
                facetIndex in 0 until projectObject.model.triangles &&
                (slot == null || slot in availableSlots)
            ) {
                val nextPaint = projectObject.multiColorPaint.paint(facetIndex, slot)
                val nextHistory = projectHistory.updateMultiColorPaint(
                    objectId,
                    nextPaint,
                    recordHistory = false,
                )
                if (nextHistory != projectHistory) {
                    projectHistory = nextHistory
                    clearCompletedSlice()
                    remoteUpload = null
                    notice = null
                }
            }
        },
        onMultiColorPaintCommitted = { objectId, previous ->
            projectHistory = projectHistory.commitMultiColorPaint(objectId, previous)
        },
        onVariableLayerHeightsChanged = { variableLayerHeights ->
            val nextHistory = projectHistory.updateSelectedVariableLayerHeights(
                variableLayerHeights,
            )
            if (nextHistory != projectHistory) {
                projectHistory = nextHistory
                clearCompletedSlice()
                remoteUpload = null
                notice = null
                error = null
            }
        },
        onObjectProcessOverridesChanged = { processOverrides ->
            val nextHistory = projectHistory.updateSelectedProcessOverrides(processOverrides)
            if (nextHistory != projectHistory) {
                projectHistory = nextHistory
                clearCompletedSlice()
                remoteUpload = null
                notice = null
                error = null
            }
        },
        onRemoveModel = {
            projectHistory = projectHistory.removeSelected()
            clearCompletedSlice()
            remoteUpload = null
            notice = null
            error = null
            selectedTab = WorkspaceTab.SLICE
        },
        onSlice = startSlice,
        onCancelSlice = cancelSlice,
        onSave = saveGcode,
        onSliceOptionsChanged = ::applyOptions,
        onSavePrinterProfile = { name, options ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        profileStore.savePrinter(name, options) to profileStore.load()
                    }
                }.onSuccess { (saved, catalog) ->
                    profileCatalog = catalog
                    applyOptions(options.selectPrinter(saved))
                    notice = profileSavedNotice
                    error = null
                }
                    .onFailure {
                        supportEvents.record(SupportEvent.PRINTER_PROFILE_SAVE_FAILED)
                        error = profileSaveError
                        notice = null
                    }
            }
        },
        onSaveFilamentProfile = { name, options, slot ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        profileStore.saveFilament(name, options, slot) to profileStore.load()
                    }
                }.onSuccess { (saved, catalog) ->
                    profileCatalog = catalog
                    applyOptions(options.updateFilamentSlot(slot, saved))
                    notice = profileSavedNotice
                    error = null
                }
                    .onFailure {
                        supportEvents.record(SupportEvent.FILAMENT_PROFILE_SAVE_FAILED)
                        error = profileSaveError
                        notice = null
                    }
            }
        },
        onSaveSlicingProfile = { name, options ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        profileStore.saveSlicing(name, options) to profileStore.load()
                    }
                }.onSuccess { (saved, catalog) ->
                    profileCatalog = catalog
                    applyOptions(options.selectQuality(saved))
                    notice = profileSavedNotice
                    error = null
                }
                    .onFailure {
                        supportEvents.record(SupportEvent.SLICING_PROFILE_SAVE_FAILED)
                        error = profileSaveError
                        notice = null
                    }
            }
        },
        onLayerRangeSelected = loadPreviewRange,
        onAppSettingsChanged = { next ->
            appSettings = next
        },
        onRemoteDeviceSelected = { id ->
            selectedRemoteDeviceId = id
            remoteStatus = null
            remoteUpload = remoteUpload?.takeIf { it.profileId == id }
            remoteMessage = null
        },
        onRemoteDeviceSaved = { draft ->
            if (!remoteBusy) {
                remoteBusy = true
                remoteMessage = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val saved = remoteDeviceStore.save(draft)
                            saved to remoteDeviceStore.load()
                        }
                    }.onSuccess { (saved, profiles) ->
                        remoteDevices = profiles
                        selectedRemoteDeviceId = saved.id
                        remoteStatus = null
                        remoteUpload = null
                        remoteMessage = remoteSavedNotice
                        remoteMessageIsError = false
                    }.onFailure {
                        supportEvents.record(SupportEvent.REMOTE_PROFILE_SAVE_FAILED)
                        remoteMessage = remoteSaveError
                        remoteMessageIsError = true
                    }
                    remoteBusy = false
                }
            }
        },
        onRemoteDeviceDeleted = { id ->
            if (!remoteBusy) {
                remoteBusy = true
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            remoteDeviceStore.delete(id)
                            remoteDeviceStore.load()
                        }
                    }.onSuccess { profiles ->
                        remoteDevices = profiles
                        if (selectedRemoteDeviceId == id) {
                            selectedRemoteDeviceId = profiles.firstOrNull()?.id
                            remoteStatus = null
                            remoteUpload = null
                        }
                        remoteMessage = remoteDeletedNotice
                        remoteMessageIsError = false
                    }.onFailure {
                        supportEvents.record(SupportEvent.REMOTE_PROFILE_SAVE_FAILED)
                        remoteMessage = remoteSaveError
                        remoteMessageIsError = true
                    }
                    remoteBusy = false
                }
            }
        },
        onRemoteRefresh = {
            val profile = selectedRemoteDevice()
            if (profile != null && !remoteBusy) {
                remoteBusy = true
                remoteUploadProgress = null
                remoteMessage = null
                val settingsSnapshot = appSettings
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            RemoteDeviceClient(settingsSnapshot.connectionTimeoutSeconds * 1_000).status(
                                profile,
                                remoteDeviceStore.credential(profile.id),
                            )
                        }
                    }.onSuccess { status ->
                        remoteStatus = status
                        remoteMessage = remoteConnectedNotice
                        remoteMessageIsError = false
                    }.onFailure { failure ->
                        remoteStatus = null
                        supportEvents.record(
                            if (
                                failure is RemoteDeviceException &&
                                failure.statusCode in setOf(401, 403)
                            ) {
                                SupportEvent.REMOTE_AUTH_FAILED
                            } else {
                                SupportEvent.REMOTE_CONNECTION_FAILED
                            },
                        )
                        remoteMessage = if (
                            failure is RemoteDeviceException && failure.statusCode in setOf(401, 403)
                        ) remoteUnauthorizedError else remoteConnectionError
                        remoteMessageIsError = true
                    }
                    remoteBusy = false
                }
            }
        },
        onRemoteUpload = {
            val profile = selectedRemoteDevice()
            val output = sliceOutcome?.output
            if (profile != null && output != null && !remoteBusy) {
                remoteBusy = true
                remoteUploadProgress = 0
                remoteMessage = null
                val settingsSnapshot = appSettings
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            RemoteDeviceClient(settingsSnapshot.connectionTimeoutSeconds * 1_000).upload(
                                profile,
                                remoteDeviceStore.credential(profile.id),
                                output,
                            ) { progress -> scope.launch { remoteUploadProgress = progress } }
                        }
                    }.onSuccess { uploaded ->
                        remoteUpload = uploaded
                        remoteMessage = remoteUploadNotice
                        remoteMessageIsError = false
                    }.onFailure { failure ->
                        supportEvents.record(
                            if (
                                failure is RemoteDeviceException &&
                                failure.statusCode in setOf(401, 403)
                            ) {
                                SupportEvent.REMOTE_AUTH_FAILED
                            } else {
                                SupportEvent.REMOTE_CONNECTION_FAILED
                            },
                        )
                        remoteMessage = if (
                            failure is RemoteDeviceException && failure.statusCode in setOf(401, 403)
                        ) remoteUnauthorizedError else remoteConnectionError
                        remoteMessageIsError = true
                    }
                    remoteUploadProgress = null
                    remoteBusy = false
                }
            }
        },
        onRemoteStart = {
            val upload = remoteUpload
            if (upload != null) {
                runRemoteCommand(remoteStartedNotice, "printing") { client, profile, credential ->
                    client.start(profile, credential, upload)
                }
            }
        },
        onRemotePause = {
            runRemoteCommand(remotePausedNotice, "paused") { client, profile, credential ->
                client.pause(profile, credential)
            }
        },
        onRemoteResume = {
            runRemoteCommand(remoteResumedNotice, "printing") { client, profile, credential ->
                client.resume(profile, credential)
            }
        },
        onRemoteCancel = {
            runRemoteCommand(remoteCanceledNotice, "canceled") { client, profile, credential ->
                client.cancel(profile, credential)
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

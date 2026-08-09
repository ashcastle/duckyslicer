package com.ashcastle.duckyslicer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sliceOperationModel = ViewModelProvider(this)[SliceOperationViewModel::class.java]
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = DuckyColors) {
                DuckySlicerScreen(sliceOperationModel)
            }
        }
    }
}

@Composable
private fun DuckySlicerScreen(sliceOperationModel: SliceOperationViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelReadError = stringResource(R.string.model_read_error)
    val modelTooLargeError = stringResource(R.string.model_too_large_error)
    val autoLayDone = stringResource(R.string.auto_lay_done)
    val autoLayError = stringResource(R.string.auto_lay_error)
    val arrangeDone = stringResource(R.string.arrange_done)
    val arrangeError = stringResource(R.string.arrange_error)
    val sliceError = stringResource(R.string.slice_error)
    val sliceCanceledNotice = stringResource(R.string.slice_canceled)
    val saveError = stringResource(R.string.save_error)
    val savedNotice = stringResource(R.string.gcode_saved)
    val profileSavedNotice = stringResource(R.string.profile_saved)
    val profileSaveError = stringResource(R.string.profile_save_error)
    val projectSaveError = stringResource(R.string.project_save_error)
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
    var autoLaying by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    var sliceOutcome by rememberSaveable { mutableStateOf<SliceOutcome?>(null) }
    var selectedTab by rememberSaveable { mutableStateOf(WorkspaceTab.SLICE) }
    var layerPreview by remember { mutableStateOf<GcodeLayerPreview?>(null) }
    val sliceOperationState by sliceOperationModel.state.collectAsStateWithLifecycle()
    val slicing = sliceOperationState.slicing
    val sliceCancellationRequested = sliceOperationState.cancellationRequested
    val sliceProgress = sliceOperationState.progress
    val previewLoading = sliceOperationState.previewLoading
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

    LaunchedEffect(profileStore) {
        profileCatalog = withContext(Dispatchers.IO) { profileStore.load() }
        if (profileStore.storageUnavailable) error = savedDataUnavailable
    }
    LaunchedEffect(profileRecentStore) {
        profileRecents = withContext(Dispatchers.IO) { profileRecentStore.load() }
        profileRecentsLoaded = true
        if (profileRecentStore.storageUnavailable) error = savedDataUnavailable
    }
    LaunchedEffect(projectStore) {
        val restored = withContext(Dispatchers.IO) { projectStore.loadProject() }
        projectHistory = ProjectHistoryState(current = restored.snapshot)
        restored.sliceOptions?.let { sliceOptions = it }
        projectPersistenceBlocked = restored.storageUnavailable
        if (restored.storageUnavailable) error = savedDataUnavailable
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
                error = sliceError
                notice = null
            }
            SliceTerminalStatus.PREVIEW_FAILED -> {
                error = previewError
                notice = null
            }
            SliceTerminalStatus.NONE -> Unit
        }
    }
    LaunchedEffect(projectHistory.current, sliceOptions, projectRestored) {
        if (!projectRestored || projectPersistenceBlocked) return@LaunchedEffect
        delay(400)
        runCatching {
            withContext(Dispatchers.IO) {
                projectStore.save(projectHistory.current, sliceOptions)
            }
        }.onFailure {
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
            error = savedDataUnavailable
            notice = null
        }
    }
    LaunchedEffect(remoteDeviceStore) {
        remoteDevices = withContext(Dispatchers.IO) { remoteDeviceStore.load() }
        if (remoteDeviceStore.storageUnavailable) error = savedDataUnavailable
        selectedRemoteDeviceId = selectedRemoteDeviceId
            ?.takeIf { selected -> remoteDevices.any { it.id == selected } }
            ?: remoteDevices.firstOrNull()?.id
    }
    LaunchedEffect(appSettings) {
        delay(350)
        withContext(Dispatchers.IO) { appSettingsStore.save(appSettings) }
    }

    val keepScreenAwake = appSettings.keepScreenAwakeWhileWorking &&
        (importing || autoLaying || arranging || slicing || previewLoading || remoteBusy)
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
            if (options.filamentProfile.id != previous.filamentProfile.id) {
                nextRecents = nextRecents.recordFilament(options.filamentProfile.id)
            }
            if (options.quality.id != previous.quality.id) {
                nextRecents = nextRecents.recordSlicing(options.quality.id)
            }
            profileRecents = nextRecents
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
        if (autoLaying || arranging || importing || slicing || previewLoading) return
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
                error = autoLayError
                notice = null
            }
            autoLaying = false
        }
    }

    fun arrangeProjectObjects() {
        val targets = projectHistory.current.objects
        if (targets.size < 2 || arranging || autoLaying || importing || slicing || previewLoading) return
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
                error = arrangeError
                notice = null
            }
            arranging = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && projectRestored && !autoLaying && !arranging && !slicing && !previewLoading) {
            importing = true
            error = null
            notice = null
            scope.launch {
                runCatching { importAndInspect(context, uri, projectStore) }
                    .onSuccess {
                        val objectIndex = projectObjects.size
                        val distance = ((objectIndex + 1) / 2) * 24f
                        val initialTransform = ModelTransform(
                            offsetXmm = when {
                                objectIndex == 0 -> 0f
                                objectIndex % 2 == 1 -> distance
                                else -> -distance
                            },
                        )
                        projectHistory = projectHistory.add(
                            ProjectObject(
                                id = UUID.randomUUID().toString(),
                                model = it,
                                transform = initialTransform,
                            ),
                        )
                        clearCompletedSlice()
                        remoteUpload = null
                        selectedTab = WorkspaceTab.SLICE
                    }
                    .onFailure { failure ->
                        error = if (failure is ModelTooLargeException) modelTooLargeError else modelReadError
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
                    error = saveError
                    notice = null
                }
            }
        }
    }

    val loadPreviewRange: (Int, Int) -> Unit = { startLayer, endLayer ->
        val completed = sliceOutcome
        if (completed != null && !slicing && !autoLaying && !arranging) {
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
            !slicing && !importing && !autoLaying && !arranging && !previewLoading &&
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
        importing = importing || !projectRestored,
        autoLaying = autoLaying,
        arranging = arranging,
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
        onChoose = { filePicker.launch(arrayOf("model/stl", "application/sla", "*/*")) },
        canUndo = projectHistory.canUndo,
        canRedo = projectHistory.canRedo,
        onObjectSelected = { objectId -> projectHistory = projectHistory.select(objectId) },
        onModelTransformChanged = { transform -> applyModelTransform(transform) },
        onModelTransformPreview = { transform -> applyModelTransform(transform, recordHistory = false) },
        onModelTransformCommitted = { previous ->
            projectHistory = projectHistory.commitSelectedTransform(previous)
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
                        error = profileSaveError
                        notice = null
                    }
            }
        },
        onSaveFilamentProfile = { name, options ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        profileStore.saveFilament(name, options) to profileStore.load()
                    }
                }.onSuccess { (saved, catalog) ->
                    profileCatalog = catalog
                    applyOptions(options.selectFilament(saved))
                    notice = profileSavedNotice
                    error = null
                }
                    .onFailure {
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
}

private suspend fun importAndInspect(
    context: Context,
    uri: Uri,
    projectStore: ProjectStore,
): ModelInfo = withContext(Dispatchers.IO) {
    val metadata = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = nameIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
            val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            name to size
        }
    }.getOrNull()
    val displayName = metadata?.first
        ?.take(200)
        ?.takeIf { it.endsWith(".stl", ignoreCase = true) }
        ?: "model.stl"
    val reportedSize = metadata?.second?.takeIf { it >= 0 }
    if (reportedSize != null && reportedSize > MAX_MODEL_IMPORT_BYTES) {
        throw ModelTooLargeException()
    }
    val destination = projectStore.createModelDestination(displayName)

    try {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "model_unreadable" }
            destination.outputStream().use { output -> copyModelWithLimit(input, output) }
        }
        ModelInfo.fromJson(NativeEngine.inspectStl(destination.absolutePath), destination.absolutePath)
            .copy(fileName = displayName)
    } catch (failure: Throwable) {
        destination.delete()
        throw failure
    }
}

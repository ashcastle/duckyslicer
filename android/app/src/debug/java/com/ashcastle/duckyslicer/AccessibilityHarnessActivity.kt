package com.ashcastle.duckyslicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.io.File

/** Debug-only host for deterministic device accessibility regressions. */
class AccessibilityHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gcodePreviewImportModel =
            ViewModelProvider(this)[GcodePreviewImportViewModel::class.java]
        setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalGcodePreviewImportModel provides gcodePreviewImportModel,
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        when (intent.getStringExtra(EXTRA_SCREEN)) {
                        SCREEN_PROFILE -> ProfileAccessibilityHarness()
                        SCREEN_DEVICE -> DeviceAccessibilityHarness()
                        SCREEN_DEVICE_TELEMETRY -> DeviceAccessibilityHarness(telemetry = true)
                        SCREEN_DEVICE_PRINTING -> DeviceAccessibilityHarness(printing = true)
                        SCREEN_REMOTE_REQUEST -> DeviceAccessibilityHarness(requestActive = true)
                        SCREEN_SETTINGS -> SettingsAccessibilityHarness()
                        SCREEN_SUPPORT_EXPORT -> SettingsAccessibilityHarness(supportExporting = true)
                        SCREEN_PROJECT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectObjects = listOf(accessibilityProjectObject()),
                        )
                        SCREEN_PROJECT_DIRTY_EMPTY -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                        )
                        SCREEN_PROJECT_RECENT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectObjects = listOf(accessibilityProjectObject()),
                            recentProjectDocuments = listOf(
                                LinkedProjectDocument(
                                    "content://accessibility/projects/recent",
                                    "Recent duck.duckyproject",
                                ),
                            ),
                        )
                        SCREEN_PROJECT_PLATES -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectObjects = listOf(accessibilityProjectObject()),
                            plateCount = 2,
                        )
                        SCREEN_PLATES -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityProjectObject()),
                            plateCount = 2,
                        )
                        SCREEN_SLICE_ALL -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityProjectObject()),
                            plateCount = 2,
                            allPlatesHaveObjects = true,
                        )
                        SCREEN_SLICE_ALL_PROGRESS -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityProjectObject()),
                            plateCount = 2,
                            allPlatesHaveObjects = true,
                            slicing = true,
                            sliceProgress = SliceProgress(
                                percent = 37,
                                batch = PlateSliceBatchProgress(current = 2, total = 3),
                            ),
                        )
                        SCREEN_OBJECT_SETTINGS -> ObjectSettingsAccessibilityHarness()
                        SCREEN_HEIGHT_RANGE_MODIFIERS -> HeightRangeModifiersAccessibilityHarness()
                        SCREEN_SHAPES -> BasicShapeSheet(
                            bedSizeX = 220f,
                            bedSizeY = 220f,
                            onAdd = { _, _ -> },
                            onDismiss = {},
                        )
                        SCREEN_AUXILIARY_SHAPE -> AuxiliaryShapeSheet(
                            projectObject = accessibilityLayOnFaceProjectObject(),
                            onAdd = {},
                            onDismiss = {},
                        )
                        SCREEN_AUXILIARY_VOLUMES -> AuxiliaryVolumesSheet(
                            projectObject = accessibilityAuxiliaryVolumeProjectObject(),
                            canAdd = true,
                            onAdd = {},
                            onEdit = {},
                            onRemove = {},
                            onDismiss = {},
                        )
                        SCREEN_AUXILIARY_VOLUME_EDIT -> {
                            val projectObject = accessibilityAuxiliaryVolumeProjectObject()
                            AuxiliaryVolumeEditSheet(
                                projectObject = projectObject,
                                volume = projectObject.volumes.last(),
                                onApply = {},
                                onDismiss = {},
                            )
                        }
                        SCREEN_SIMPLIFY -> SimplifyModelSheet(
                            originalTriangleCount = 100_000,
                            hasSurfacePaint = true,
                            onApply = {},
                            onDismiss = {},
                        )
                        SCREEN_SPLIT_PARTS -> SplitPartsSheet(
                            projectObject = accessibilityProjectObject(),
                            onApply = {},
                            onDismiss = {},
                        )
                        SCREEN_MODEL_TRANSFORM -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityProjectObject()),
                        )
                        SCREEN_LAY_ON_FACE -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityLayOnFaceProjectObject()),
                        )
                        SCREEN_LAY_ON_FACE_FAILURE -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityLayOnFaceProjectObject()),
                            layOnFaceForcedFailure = true,
                        )
                        SCREEN_GCODE_EXPORT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PREVIEW,
                            sliceOutcome = SliceOutcome(
                                File(cacheDir, "accessibility-export.gcode"),
                                10,
                                60f,
                                1_000f,
                                3f,
                            ),
                            exportingGcode = true,
                        )
                        SCREEN_GCODE_EXPORT_ALL -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PREVIEW,
                            projectObjects = listOf(accessibilityProjectObject()),
                            sliceOutcome = SliceOutcome(
                                File(cacheDir, "accessibility-export-all.gcode"),
                                10,
                                60f,
                                1_000f,
                                3f,
                            ),
                            plateCount = 2,
                            allPlatesHaveObjects = true,
                        )
                        SCREEN_GCODE_EXPORT_ALL_PROGRESS -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PREVIEW,
                            projectObjects = listOf(accessibilityProjectObject()),
                            sliceOutcome = SliceOutcome(
                                File(cacheDir, "accessibility-export-all-progress.gcode"),
                                10,
                                60f,
                                1_000f,
                                3f,
                            ),
                            plateCount = 3,
                            allPlatesHaveObjects = true,
                            gcodeExportState = GcodeExportState(
                                activeId = 1L,
                                completedFiles = 1,
                                totalFiles = 3,
                            ),
                        )
                        SCREEN_PROJECT_EXPORT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectExporting = true,
                        )
                        SCREEN_PROJECT_IMPORT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectImporting = true,
                        )
                        SCREEN_PROFILE_IMPORT -> WorkspaceAccessibilityHarness(
                            profileTransferDirection = ProfileTransferDirection.IMPORT,
                        )
                        SCREEN_PROFILE_EXPORT -> WorkspaceAccessibilityHarness(
                            profileTransferDirection = ProfileTransferDirection.EXPORT,
                        )
                        SCREEN_WORKSPACE -> {
                            val density = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(density.density, fontScale = 2f),
                            ) {
                                WorkspaceAccessibilityHarness()
                            }
                        }
                        SCREEN_WORKSPACE_PROFILES -> ProfileSettingsAccessibilityHarness()
                            else -> PreviewAccessibilityHarness()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_PREVIEW = "preview"
        const val SCREEN_PROFILE = "profile"
        const val SCREEN_DEVICE = "device"
        const val SCREEN_DEVICE_TELEMETRY = "device-telemetry"
        const val SCREEN_DEVICE_PRINTING = "device-printing"
        const val SCREEN_REMOTE_REQUEST = "remote-request"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_SUPPORT_EXPORT = "support-export"
        const val SCREEN_PROJECT = "project"
        const val SCREEN_PROJECT_DIRTY_EMPTY = "project-dirty-empty"
        const val SCREEN_PROJECT_RECENT = "project-recent"
        const val SCREEN_PROJECT_PLATES = "project-plates"
        const val SCREEN_PLATES = "plates"
        const val SCREEN_SLICE_ALL = "slice-all"
        const val SCREEN_SLICE_ALL_PROGRESS = "slice-all-progress"
        const val SCREEN_WORKSPACE = "workspace"
        const val SCREEN_WORKSPACE_PROFILES = "workspace-profiles"
        const val SCREEN_OBJECT_SETTINGS = "object-settings"
        const val SCREEN_HEIGHT_RANGE_MODIFIERS = "height-range-modifiers"
        const val SCREEN_SHAPES = "shapes"
        const val SCREEN_AUXILIARY_SHAPE = "auxiliary-shape"
        const val SCREEN_AUXILIARY_VOLUMES = "auxiliary-volumes"
        const val SCREEN_AUXILIARY_VOLUME_EDIT = "auxiliary-volume-edit"
        const val SCREEN_SIMPLIFY = "simplify"
        const val SCREEN_SPLIT_PARTS = "split-parts"
        const val SCREEN_MODEL_TRANSFORM = "model-transform"
        const val SCREEN_LAY_ON_FACE = "lay-on-face"
        const val SCREEN_LAY_ON_FACE_FAILURE = "lay-on-face-failure"
        const val SCREEN_GCODE_EXPORT = "gcode-export"
        const val SCREEN_GCODE_EXPORT_ALL = "gcode-export-all"
        const val SCREEN_GCODE_EXPORT_ALL_PROGRESS = "gcode-export-all-progress"
        const val SCREEN_PROJECT_EXPORT = "project-export"
        const val SCREEN_PROJECT_IMPORT = "project-import"
        const val SCREEN_PROFILE_IMPORT = "profile-import"
        const val SCREEN_PROFILE_EXPORT = "profile-export"
    }
}

@Composable
private fun ProfileSettingsAccessibilityHarness() {
    val userPrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
        id = "user-accessibility-printer",
        name = "My accessibility printer",
        builtIn = false,
    )
    val userFilament = FilamentProfile.GENERIC_PLA.copy(
        id = "user-accessibility-filament",
        name = "My accessibility filament",
        builtIn = false,
    )
    val userSlicing = QualityProfile.STANDARD.copy(
        id = "user-accessibility-slicing",
        name = "My accessibility slicing",
        builtIn = false,
    )
    var catalog by remember {
        val builtIns = ProfileCatalog()
        mutableStateOf(
            builtIns.copy(
                printers = builtIns.printers + userPrinter,
                filaments = builtIns.filaments + userFilament,
                slicing = builtIns.slicing + userSlicing,
            ),
        )
    }
    var options by remember {
        mutableStateOf(
            SliceOptions().copy(
                filamentSlots = listOf(FilamentProfile.PLA, FilamentProfile.PETG),
                supportEnabled = true,
                supportInterfaceTopLayers = 3,
            ),
        )
    }
    Column(Modifier.padding(16.dp)) {
        ProfileSettings(
            options = options,
            catalog = catalog,
            recents = ProfileRecents(),
            enabled = true,
            onOptionsChanged = { options = it },
            onSavePrinter = { _, _ -> },
            onSaveFilament = { _, _, _ -> },
            onSaveSlicing = { _, _ -> },
            onUpdatePrinter = { _, _ -> },
            onUpdateFilament = { _, _, _ -> },
            onUpdateSlicing = { _, _ -> },
            onRenamePrinter = { id, name, _ ->
                val renamed = catalog.printers.single { it.id == id }.copy(name = name)
                catalog = catalog.copy(
                    printers = catalog.printers.map { if (it.id == id) renamed else it },
                )
                if (options.printerProfile.id == id) options = options.selectPrinter(renamed)
            },
            onRenameFilament = { _, _, _ -> },
            onRenameSlicing = { _, _, _ -> },
            onDeletePrinter = { id ->
                catalog = catalog.copy(printers = catalog.printers.filterNot { it.id == id })
            },
            onDeleteFilament = { id ->
                catalog = catalog.copy(filaments = catalog.filaments.filterNot { it.id == id })
            },
            onDeleteSlicing = { id ->
                catalog = catalog.copy(slicing = catalog.slicing.filterNot { it.id == id })
            },
        )
    }
}

@Composable
private fun ObjectSettingsAccessibilityHarness() {
    var overrides by remember { mutableStateOf(ObjectProcessOverrides()) }
    ObjectProcessSettingsSheet(
        current = overrides,
        options = SliceOptions(),
        onApply = { overrides = it },
        onDismiss = {},
    )
}

@Composable
private fun HeightRangeModifiersAccessibilityHarness() {
    var modifiers by remember { mutableStateOf(HeightRangeModifiers()) }
    HeightRangeModifiersSheet(
        current = modifiers,
        objectOverrides = ObjectProcessOverrides(),
        objectHeightMm = 20f,
        options = SliceOptions(),
        onApply = { modifiers = it },
        onDismiss = {},
    )
}

@Composable
private fun PreviewAccessibilityHarness() {
    var opacity by remember { mutableFloatStateOf(0.92f) }
    var depthContrast by remember { mutableFloatStateOf(0.75f) }
    var visibleRoles by remember { mutableStateOf((0 until 10).toSet()) }
    var colorMode by remember { mutableStateOf(PreviewColorMode.FEATURE) }
    var pauseEvents by remember { mutableStateOf(LayerPauseEvents()) }
    var filamentChanges by remember { mutableStateOf(LayerFilamentChanges()) }
    var customGCodeEvents by remember { mutableStateOf(LayerCustomGCodeEvents()) }
    PreviewControls(
        preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 299,
            layerCount = 300,
            minZMm = 0.25f,
            maxZMm = 60.05f,
            segments = FloatArray(300 * GcodeLayerPreview.SEGMENT_STRIDE).apply {
                repeat(300) { layer ->
                    val offset = layer * GcodeLayerPreview.SEGMENT_STRIDE
                    this[offset] = layer.toFloat()
                    this[offset + 2] = layer + 1f
                    this[offset + 4] = 0.25f + layer * 0.2f
                }
            },
            roleSegmentCounts = intArrayOf(300, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        ),
        layerPauseEvents = pauseEvents,
        layerFilamentChanges = filamentChanges,
        layerCustomGCodeEvents = customGCodeEvents,
        layerFilamentChangesAvailable = true,
        filamentColors = listOf(0xFFFFCF40.toInt(), 0xFF44D7FF.toInt()),
        layerFilamentColors = listOf(0xFFFFCF40.toInt(), 0xFF44D7FF.toInt()),
        toolpathOpacity = opacity,
        onToolpathOpacityChanged = { opacity = it },
        toolpathDepthContrast = depthContrast,
        onToolpathDepthContrastChanged = { depthContrast = it },
        visibleToolpathRoles = visibleRoles,
        previewColorMode = colorMode,
        onPreviewColorModeChanged = { colorMode = it },
        onToolpathRoleVisibilityChanged = { role, visible ->
            visibleRoles = if (visible) visibleRoles + role else visibleRoles - role
        },
        onLayerRangeSelected = { _, _ -> },
        onAddLayerPause = { _, printZMm ->
            pauseEvents = pauseEvents.put(LayerPauseEvent(printZMm))
        },
        onRemoveLayerPause = { printZMm -> pauseEvents = pauseEvents.remove(printZMm) },
        onPutLayerFilamentChange = { _, printZMm, filamentSlot ->
            filamentChanges = filamentChanges.put(
                LayerFilamentChange(printZMm, filamentSlot),
            )
        },
        onRemoveLayerFilamentChange = { printZMm ->
            filamentChanges = filamentChanges.remove(printZMm)
        },
        onPutLayerCustomGCode = { _, printZMm, gcode ->
            customGCodeEvents = customGCodeEvents.put(LayerCustomGCodeEvent(printZMm, gcode))
        },
        onRemoveLayerCustomGCode = { printZMm ->
            customGCodeEvents = customGCodeEvents.remove(printZMm)
        },
    )
}

@Composable
private fun ProfileAccessibilityHarness() {
    var value by remember { mutableFloatStateOf(50f) }
    var enabled by remember { mutableStateOf(true) }
    Column(Modifier.padding(24.dp)) {
        SettingSlider(
            label = TEST_SETTING_LABEL,
            valueText = "50%",
            value = value,
            range = 0f..100f,
            steps = 99,
            onValueChange = { value = it },
        )
        SettingsSwitch(
            label = TEST_SWITCH_LABEL,
            checked = enabled,
            onCheckedChange = { enabled = it },
        )
    }
}

@Composable
private fun DeviceAccessibilityHarness(
    requestActive: Boolean = false,
    telemetry: Boolean = false,
    printing: Boolean = false,
) {
    var destructiveAction by remember { mutableStateOf<String?>(null) }
    var credentialRemovalSaved by remember { mutableStateOf(false) }
    Box {
        DeviceSheet(
            profiles = listOf(
                RemoteDeviceProfile(
                    id = "accessibility-device",
                    name = TEST_DEVICE_LABEL,
                    kind = RemoteDeviceKind.OCTOPRINT,
                    baseUrl = "http://127.0.0.1",
                    hasCredential = true,
                    credentialKey = "credential-accessibility",
                ),
            ),
            selectedProfileId = "accessibility-device".takeIf {
                requestActive || telemetry || printing
            },
            status = when {
                telemetry -> RemoteDeviceStatus(
                    state = "Printing",
                    fileName = "accessibility.gcode",
                    progressPercent = 40,
                    nozzleTemperatureC = 205.4,
                    nozzleTargetC = 210.0,
                    bedTemperatureC = 59.8,
                    bedTargetC = 60.0,
                    elapsedSeconds = 3_660,
                    remainingSeconds = 1_200,
                )
                printing -> RemoteDeviceStatus(state = "Printing")
                else -> null
            },
            upload = null,
            gcodeAvailable = false,
            busy = requestActive,
            uploadProgress = null,
            requestActive = requestActive,
            uploadActive = false,
            requestCancellationRequested = false,
            message = null,
            isError = false,
            confirmBeforePrint = true,
            onSelect = {},
            onSave = { credentialRemovalSaved = it.removeSavedCredential },
            onDelete = { destructiveAction = TEST_REMOTE_DELETE_DISPATCHED },
            onRefresh = {},
            onUpload = {},
            onCancelRequest = {},
            onStart = {},
            onPause = {},
            onResume = {},
            onCancel = { destructiveAction = TEST_REMOTE_CANCEL_DISPATCHED },
        )
        destructiveAction?.let { result ->
            Text(
                result,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (credentialRemovalSaved) {
            Text(
                TEST_REMOTE_CREDENTIAL_REMOVAL_SAVED,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun SettingsAccessibilityHarness(supportExporting: Boolean = false) {
    var settings by remember { mutableStateOf(AppSettings()) }
    AppSettingsSheet(
        settings = settings,
        saveFailed = false,
        supportReportExportState = SupportReportExportState(
            activeId = 1L.takeIf { supportExporting },
        ),
        onSettingsChanged = { settings = it },
        onSupportReportExport = {},
        onCancelSupportReportExport = {},
    )
}

@Composable
private fun WorkspaceAccessibilityHarness(
    selectedTab: WorkspaceTab = WorkspaceTab.SLICE,
    projectObjects: List<ProjectObject> = emptyList(),
    sliceOutcome: SliceOutcome? = null,
    exportingGcode: Boolean = false,
    gcodeExportState: GcodeExportState = if (exportingGcode) {
        GcodeExportState(activeId = 1L, totalFiles = 1)
    } else {
        GcodeExportState()
    },
    projectImporting: Boolean = false,
    projectExporting: Boolean = false,
    profileTransferDirection: ProfileTransferDirection? = null,
    profileTransferCancellationRequested: Boolean = false,
    plateCount: Int = 1,
    allPlatesHaveObjects: Boolean = false,
    recentProjectDocuments: List<LinkedProjectDocument> = emptyList(),
    slicing: Boolean = false,
    sliceProgress: SliceProgress = SliceProgress(0),
    layOnFaceForcedFailure: Boolean = false,
) {
    var harnessNotice by remember { mutableStateOf<String?>(null) }
    val recentProjectRemoved = stringResource(R.string.recent_project_removed)
    var layOnFaceUndoTransform by remember { mutableStateOf<ModelTransform?>(null) }
    var projectPlates by remember(plateCount) {
        mutableStateOf(
            List(plateCount) { index ->
                val plateObjects = if (index == 0 || allPlatesHaveObjects) {
                    projectObjects.map { projectObject ->
                        if (index == 0) projectObject else projectObject.copy(
                            id = "${projectObject.id}-$index",
                        )
                    }
                } else {
                    emptyList()
                }
                ProjectPlate(
                    id = "accessibility-plate-$index",
                    objects = plateObjects,
                    selectedObjectId = plateObjects.firstOrNull()?.id,
                )
            },
        )
    }
    var selectedPlateId by remember(plateCount) {
        mutableStateOf(projectPlates.first().id)
    }
    val activePlate = projectPlates.first { it.id == selectedPlateId }
    Box {
    WorkspaceScreen(
        selectedTab = selectedTab,
        projectPlates = projectPlates,
        selectedPlateId = selectedPlateId,
        projectObjects = activePlate.objects,
        selectedObjectId = activePlate.selectedObjectId,
        layerPauseEvents = activePlate.layerPauseEvents,
        layerFilamentChanges = activePlate.layerFilamentChanges,
        layerCustomGCodeEvents = activePlate.layerCustomGCodeEvents,
        sliceOptions = SliceOptions(),
        profileCatalog = ProfileCatalog(),
        profileRecents = ProfileRecents(),
        appSettings = AppSettings(),
        remoteDevices = emptyList(),
        selectedRemoteDeviceId = null,
        remoteStatus = null,
        remoteUpload = null,
        remoteBusy = false,
        remoteUploadProgress = null,
        remoteRequestActive = false,
        remoteUploadActive = false,
        remoteRequestCancellationRequested = false,
        remoteMessage = null,
        remoteMessageIsError = false,
        profileBusy = profileTransferDirection != null,
        profileTransferDirection = profileTransferDirection,
        profileTransferCancellationRequested = profileTransferCancellationRequested,
        appSettingsSaveFailed = false,
        supportReportExportState = SupportReportExportState(),
        sliceOutcome = sliceOutcome,
        previewOutcome = sliceOutcome,
        layerPreview = null,
        previewStale = false,
        importing = false,
        autoLaying = false,
        arranging = false,
        splitting = false,
        cutting = false,
        simplifying = false,
        projectEditActive = false,
        projectEditCancellationRequested = false,
        projectImporting = projectImporting,
        projectExporting = projectExporting,
        projectTransferCancellationRequested = false,
        linkedProjectName = "Linked-project.duckyproject".takeIf {
            selectedTab == WorkspaceTab.PROJECT
        },
        linkedProjectDirty = selectedTab == WorkspaceTab.PROJECT,
        recentProjectDocuments = recentProjectDocuments,
        slicing = slicing,
        sliceCancellationRequested = false,
        sliceProgress = sliceProgress,
        previewLoading = false,
        gcodeExportState = gcodeExportState,
        canExportAllGcode = plateCount >= 2 && allPlatesHaveObjects && sliceOutcome != null,
        error = null,
        notice = harnessNotice,
        canUndo = layOnFaceUndoTransform != null,
        canRedo = false,
        onTabSelected = {},
        onChoose = {},
        onImportProfiles = {},
        onExportProfiles = {},
        onShareProfiles = { harnessNotice = TEST_PROFILE_SHARE_REQUESTED_LABEL },
        onCancelProfileTransfer = {},
        onCreatePrimitive = { _, _ -> },
        onCreateAuxiliaryPrimitive = {},
        onEditAuxiliaryVolume = {},
        onNewProject = {
            projectPlates = listOf(ProjectPlate("accessibility-plate-new"))
            selectedPlateId = projectPlates.single().id
        },
        onOpenProject = {},
        onOpenRecentProject = { harnessNotice = it.displayName },
        onForgetRecentProject = {
            harnessNotice = recentProjectRemoved
        },
        onSaveProject = {},
        onExportModel = {},
        onExportSelectedStl = {},
        onPlateSelected = { selectedPlateId = it },
        onAddPlate = {
            if (projectPlates.size < MAX_PROJECT_PLATES) {
                val added = ProjectPlate("accessibility-plate-${projectPlates.size}")
                projectPlates = projectPlates + added
                selectedPlateId = added.id
            }
        },
        onDuplicatePlate = {
            val source = projectPlates.first { it.id == selectedPlateId }
            val snapshot = ProjectSnapshot(selectedPlateId, projectPlates)
            val duplicated = ProjectHistoryState(snapshot).duplicateSelectedPlate(
                newPlateId = "accessibility-plate-copy-${projectPlates.size}",
                newObjectIds = source.objects.indices.map { index ->
                    "accessibility-object-copy-${projectPlates.size}-$index"
                },
            ).current
            projectPlates = duplicated.plates
            selectedPlateId = duplicated.selectedPlateId
        },
        onRenamePlate = { name ->
            projectPlates = projectPlates.map { plate ->
                if (plate.id == selectedPlateId) plate.copy(name = name) else plate
            }
        },
        onMovePlate = { targetIndex ->
            val sourceIndex = projectPlates.indexOfFirst { it.id == selectedPlateId }
            if (sourceIndex >= 0 && targetIndex in projectPlates.indices) {
                projectPlates = projectPlates.toMutableList().apply {
                    add(targetIndex, removeAt(sourceIndex))
                }
            }
        },
        onRemovePlate = {
            if (projectPlates.size > 1) {
                val selectedIndex = projectPlates.indexOfFirst { it.id == selectedPlateId }
                projectPlates = projectPlates.filterNot { it.id == selectedPlateId }
                selectedPlateId = projectPlates[minOf(selectedIndex, projectPlates.lastIndex)].id
            }
        },
        onObjectSelected = {},
        onModelTransformChanged = {},
        onModelTransformPreview = { nextTransform ->
            projectPlates = projectPlates.map { plate ->
                if (plate.id != selectedPlateId) {
                    plate
                } else {
                    plate.copy(
                        objects = plate.objects.map { projectObject ->
                            if (projectObject.id == plate.selectedObjectId) {
                                projectObject.copy(transform = nextTransform)
                            } else {
                                projectObject
                            }
                        },
                    )
                }
            }
        },
        onModelTransformCommitted = { previousTransform ->
            layOnFaceUndoTransform = previousTransform
            harnessNotice = TEST_TRANSFORM_COMMITTED_LABEL
        },
        onObjectFilamentSelected = {},
        onUndo = {
            val previous = layOnFaceUndoTransform ?: return@WorkspaceScreen
            projectPlates = projectPlates.map { plate ->
                if (plate.id != selectedPlateId) {
                    plate
                } else {
                    plate.copy(
                        objects = plate.objects.map { projectObject ->
                            if (projectObject.id == plate.selectedObjectId) {
                                projectObject.copy(transform = previous)
                            } else {
                                projectObject
                            }
                        },
                    )
                }
            }
            layOnFaceUndoTransform = null
            harnessNotice = TEST_LAY_ON_FACE_UNDONE_LABEL
        },
        onRedo = {},
        onDuplicate = {},
        onRenameObject = { _, _ -> },
        onCopyObjectToPlate = { _, _ -> },
        onMoveObjectToPlate = { _, _ -> },
        onArrange = {},
        onAutoLay = {},
        onLayOnFace = { objectId, triangle ->
            if (layOnFaceForcedFailure) {
                harnessNotice = TEST_LAY_ON_FACE_FAILED_LABEL
                false
            } else {
                val plate = projectPlates.first { it.id == selectedPlateId }
                val projectObject = plate.objects.first { it.id == objectId }
                runCatching { projectObject.withFaceOnBed(triangle) }.fold(
                    onSuccess = { transform ->
                        layOnFaceUndoTransform = projectObject.transform
                        projectPlates = projectPlates.map { candidatePlate ->
                            if (candidatePlate.id != selectedPlateId) {
                                candidatePlate
                            } else {
                                candidatePlate.copy(
                                    objects = candidatePlate.objects.map { candidateObject ->
                                        if (candidateObject.id == objectId) {
                                            candidateObject.copy(transform = transform)
                                        } else {
                                            candidateObject
                                        }
                                    },
                                )
                            }
                        }
                        harnessNotice = TEST_LAY_ON_FACE_SELECTED_LABEL
                        true
                    },
                    onFailure = {
                        harnessNotice = TEST_LAY_ON_FACE_FAILED_LABEL
                        false
                    },
                )
            }
        },
        onSplit = {},
        onSplitParts = {},
        onCut = { _, _ -> },
        onSimplify = {},
        onCancelProjectEdit = {},
        onCancelProjectImport = {},
        onCancelProjectExport = {},
        onSupportPaintPreview = { _, _, _, _ ->
            harnessNotice = TEST_SUPPORT_PAINTED_LABEL
        },
        onSupportPaintCommitted = { _, _, _, _ -> },
        onSeamPaintPreview = { _, _, _, _ -> },
        onSeamPaintCommitted = { _, _, _, _ -> },
        onBrimPointsChanged = { _, _ -> },
        onMultiColorPaintPreview = { _, _, _, _ -> },
        onMultiColorPaintCommitted = { _, _, _, _ -> },
        onVariableLayerHeightsChanged = {},
        onObjectProcessOverridesChanged = {},
        onHeightRangeModifiersChanged = {},
        onRemoveAuxiliaryVolume = {},
        onRemoveModel = {},
        onSlice = { allPlates ->
            if (allPlates) harnessNotice = TEST_SLICE_ALL_REQUESTED_LABEL
        },
        onCancelSlice = {},
        onSave = { allPlates ->
            if (allPlates) harnessNotice = TEST_EXPORT_ALL_REQUESTED_LABEL
        },
        onShareGcode = { harnessNotice = TEST_GCODE_SHARE_REQUESTED_LABEL },
        onCancelGcodeExport = {},
        onSliceOptionsChanged = {},
        onSavePrinterProfile = { _, _ -> },
        onSaveFilamentProfile = { _, _, _ -> },
        onSaveSlicingProfile = { _, _ -> },
        onUpdatePrinterProfile = { _, _ -> },
        onUpdateFilamentProfile = { _, _, _ -> },
        onUpdateSlicingProfile = { _, _ -> },
        onRenamePrinterProfile = { _, _, _ -> },
        onRenameFilamentProfile = { _, _, _ -> },
        onRenameSlicingProfile = { _, _, _ -> },
        onDeletePrinterProfile = {},
        onDeleteFilamentProfile = {},
        onDeleteSlicingProfile = {},
        onLayerRangeSelected = { _, _ -> },
        onAddLayerPause = { _, _ -> },
        onRemoveLayerPause = {},
        onPutLayerFilamentChange = { _, _, _ -> },
        onRemoveLayerFilamentChange = {},
        onPutLayerCustomGCode = { _, _, _ -> },
        onRemoveLayerCustomGCode = {},
        onAppSettingsChanged = {},
        onSupportReportExport = {},
        onCancelSupportReportExport = {},
        onRemoteDeviceSelected = {},
        onRemoteDeviceSaved = {},
        onRemoteDeviceDeleted = {},
        onRemoteRefresh = {},
        onRemoteUpload = {},
        onRemoteCancelRequest = {},
        onRemoteStart = {},
        onRemotePause = {},
        onRemoteResume = {},
        onRemoteCancel = {},
    )
        harnessNotice?.let { message ->
            Text(
                text = message,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun accessibilityProjectObject() = ProjectObject(
    id = "accessibility-project-object",
    model = ModelInfo(
        fileName = "accessibility.stl",
        triangles = 2,
        dimensions = listOf(40.0, 40.0, 0.0),
        localPath = "",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(40.0, 40.0, 0.0),
        previewTriangles = floatArrayOf(
            0f, 0f, 0f, 40f, 0f, 0f, 40f, 40f, 0f,
            0f, 0f, 0f, 40f, 40f, 0f, 0f, 40f, 0f,
        ),
    ),
    supportPaint = SupportPaint(mapOf(0 to SupportPaintState.ENFORCE)),
    multiColorPaint = MultiColorPaint(mapOf(1 to 2)),
)

private fun accessibilityLayOnFaceProjectObject() = ProjectObject(
    id = "accessibility-lay-on-face-object",
    model = ModelInfo(
        fileName = "accessibility-box.stl",
        triangles = 12,
        dimensions = listOf(40.0, 40.0, 20.0),
        localPath = "",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(40.0, 40.0, 20.0),
        previewTriangles = floatArrayOf(
            // Bottom and top.
            0f, 0f, 0f, 40f, 0f, 0f, 40f, 40f, 0f,
            0f, 0f, 0f, 40f, 40f, 0f, 0f, 40f, 0f,
            0f, 0f, 20f, 40f, 40f, 20f, 40f, 0f, 20f,
            0f, 0f, 20f, 0f, 40f, 20f, 40f, 40f, 20f,
            // Front and back.
            0f, 0f, 0f, 40f, 0f, 20f, 40f, 0f, 0f,
            0f, 0f, 0f, 0f, 0f, 20f, 40f, 0f, 20f,
            0f, 40f, 0f, 40f, 40f, 0f, 40f, 40f, 20f,
            0f, 40f, 0f, 40f, 40f, 20f, 0f, 40f, 20f,
            // Left and right.
            0f, 0f, 0f, 0f, 40f, 0f, 0f, 40f, 20f,
            0f, 0f, 0f, 0f, 40f, 20f, 0f, 0f, 20f,
            40f, 0f, 0f, 40f, 0f, 20f, 40f, 40f, 20f,
            40f, 0f, 0f, 40f, 40f, 20f, 40f, 40f, 0f,
        ),
    ),
)

private fun accessibilityAuxiliaryVolumeProjectObject(): ProjectObject {
    val base = accessibilityLayOnFaceProjectObject()
    return base.copy(
        volumes = listOf(
            base.singleVolume,
            base.singleVolume.copy(
                id = "accessibility-cutout",
                role = ProjectVolumeRole.NEGATIVE_VOLUME,
            ),
            base.singleVolume.copy(
                id = "accessibility-settings-region",
                role = ProjectVolumeRole.PARAMETER_MODIFIER,
                config = ProjectVolumeConfig(
                    mapOf("sparse_infill_density" to "80%"),
                ),
            ),
        ),
    )
}

internal const val TEST_SETTING_LABEL = "Accessibility setting"
internal const val TEST_SWITCH_LABEL = "Accessibility switch"
internal const val TEST_DEVICE_LABEL = "Accessibility test printer"
internal const val TEST_REMOTE_DELETE_DISPATCHED = "Accessibility device deletion dispatched"
internal const val TEST_REMOTE_CANCEL_DISPATCHED = "Accessibility print cancellation dispatched"
internal const val TEST_REMOTE_CREDENTIAL_REMOVAL_SAVED =
    "Accessibility credential removal saved"
internal const val TEST_LAY_ON_FACE_SELECTED_LABEL = "Accessibility face selected"
internal const val TEST_LAY_ON_FACE_FAILED_LABEL = "Accessibility face placement failed"
internal const val TEST_LAY_ON_FACE_UNDONE_LABEL = "Accessibility face placement undone"
internal const val TEST_TRANSFORM_COMMITTED_LABEL = "Accessibility transform committed"
internal const val TEST_SUPPORT_PAINTED_LABEL = "Accessibility support painted"
internal const val TEST_SLICE_ALL_REQUESTED_LABEL = "Accessibility all plates requested"
internal const val TEST_EXPORT_ALL_REQUESTED_LABEL = "Accessibility all G-code requested"
internal const val TEST_GCODE_SHARE_REQUESTED_LABEL = "Accessibility G-code share requested"
internal const val TEST_PROFILE_SHARE_REQUESTED_LABEL = "Accessibility profile share requested"

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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File

/** Debug-only host for deterministic device accessibility regressions. */
class AccessibilityHarnessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    when (intent.getStringExtra(EXTRA_SCREEN)) {
                        SCREEN_PROFILE -> ProfileAccessibilityHarness()
                        SCREEN_DEVICE -> DeviceAccessibilityHarness()
                        SCREEN_REMOTE_REQUEST -> DeviceAccessibilityHarness(requestActive = true)
                        SCREEN_SETTINGS -> SettingsAccessibilityHarness()
                        SCREEN_SUPPORT_EXPORT -> SettingsAccessibilityHarness(supportExporting = true)
                        SCREEN_PROJECT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectObjects = listOf(accessibilityProjectObject()),
                        )
                        SCREEN_PLATES -> WorkspaceAccessibilityHarness(
                            projectObjects = listOf(accessibilityProjectObject()),
                            plateCount = 2,
                        )
                        SCREEN_OBJECT_SETTINGS -> ObjectSettingsAccessibilityHarness()
                        SCREEN_SHAPES -> BasicShapeSheet(
                            bedSizeX = 220f,
                            bedSizeY = 220f,
                            onAdd = { _, _ -> },
                            onDismiss = {},
                        )
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

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_PREVIEW = "preview"
        const val SCREEN_PROFILE = "profile"
        const val SCREEN_DEVICE = "device"
        const val SCREEN_REMOTE_REQUEST = "remote-request"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_SUPPORT_EXPORT = "support-export"
        const val SCREEN_PROJECT = "project"
        const val SCREEN_PLATES = "plates"
        const val SCREEN_WORKSPACE = "workspace"
        const val SCREEN_WORKSPACE_PROFILES = "workspace-profiles"
        const val SCREEN_OBJECT_SETTINGS = "object-settings"
        const val SCREEN_SHAPES = "shapes"
        const val SCREEN_SIMPLIFY = "simplify"
        const val SCREEN_SPLIT_PARTS = "split-parts"
        const val SCREEN_MODEL_TRANSFORM = "model-transform"
        const val SCREEN_GCODE_EXPORT = "gcode-export"
        const val SCREEN_PROJECT_EXPORT = "project-export"
        const val SCREEN_PROJECT_IMPORT = "project-import"
        const val SCREEN_PROFILE_IMPORT = "profile-import"
        const val SCREEN_PROFILE_EXPORT = "profile-export"
    }
}

@Composable
private fun ProfileSettingsAccessibilityHarness() {
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
            catalog = ProfileCatalog(),
            recents = ProfileRecents(),
            enabled = true,
            onOptionsChanged = { options = it },
            onSavePrinter = { _, _ -> },
            onSaveFilament = { _, _, _ -> },
            onSaveSlicing = { _, _ -> },
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
private fun PreviewAccessibilityHarness() {
    var opacity by remember { mutableFloatStateOf(0.92f) }
    var depthContrast by remember { mutableFloatStateOf(0.75f) }
    var visibleRoles by remember { mutableStateOf((0 until 10).toSet()) }
    PreviewControls(
        preview = GcodeLayerPreview(
            startLayer = 0,
            endLayer = 299,
            layerCount = 300,
            minZMm = 0.25f,
            maxZMm = 60.05f,
            segments = FloatArray(0),
            roleSegmentCounts = IntArray(10),
        ),
        toolpathOpacity = opacity,
        onToolpathOpacityChanged = { opacity = it },
        toolpathDepthContrast = depthContrast,
        onToolpathDepthContrastChanged = { depthContrast = it },
        visibleToolpathRoles = visibleRoles,
        onToolpathRoleVisibilityChanged = { role, visible ->
            visibleRoles = if (visible) visibleRoles + role else visibleRoles - role
        },
        onLayerRangeSelected = { _, _ -> },
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
private fun DeviceAccessibilityHarness(requestActive: Boolean = false) {
    DeviceSheet(
        profiles = listOf(
            RemoteDeviceProfile(
                id = "accessibility-device",
                name = TEST_DEVICE_LABEL,
                kind = RemoteDeviceKind.OCTOPRINT,
                baseUrl = "http://127.0.0.1",
            ),
        ),
        selectedProfileId = "accessibility-device".takeIf { requestActive },
        status = null,
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
        onSave = {},
        onDelete = {},
        onRefresh = {},
        onUpload = {},
        onCancelRequest = {},
        onStart = {},
        onPause = {},
        onResume = {},
        onCancel = {},
    )
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
    projectImporting: Boolean = false,
    projectExporting: Boolean = false,
    profileTransferDirection: ProfileTransferDirection? = null,
    profileTransferCancellationRequested: Boolean = false,
    plateCount: Int = 1,
) {
    var harnessNotice by remember { mutableStateOf<String?>(null) }
    var projectPlates by remember(plateCount) {
        mutableStateOf(
            List(plateCount) { index ->
                ProjectPlate(
                    id = "accessibility-plate-$index",
                    objects = projectObjects.takeIf { index == 0 }.orEmpty(),
                    selectedObjectId = projectObjects.firstOrNull()?.id.takeIf { index == 0 },
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
        layerPreview = null,
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
        slicing = false,
        sliceCancellationRequested = false,
        sliceProgress = 0,
        previewLoading = false,
        exportingGcode = exportingGcode,
        gcodeExportCancellationRequested = false,
        error = null,
        notice = harnessNotice,
        canUndo = false,
        canRedo = false,
        onTabSelected = {},
        onChoose = {},
        onImportProfiles = {},
        onExportProfiles = {},
        onCancelProfileTransfer = {},
        onCreatePrimitive = { _, _ -> },
        onOpenProject = {},
        onSaveProject = {},
        onPlateSelected = { selectedPlateId = it },
        onAddPlate = {
            if (projectPlates.size < MAX_PROJECT_PLATES) {
                val added = ProjectPlate("accessibility-plate-${projectPlates.size}")
                projectPlates = projectPlates + added
                selectedPlateId = added.id
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
        onModelTransformPreview = {},
        onModelTransformCommitted = {},
        onObjectFilamentSelected = {},
        onUndo = {},
        onRedo = {},
        onDuplicate = {},
        onArrange = {},
        onAutoLay = {},
        onLayOnFace = { _, _ -> harnessNotice = TEST_LAY_ON_FACE_SELECTED_LABEL },
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
        onRemoveModel = {},
        onSlice = {},
        onCancelSlice = {},
        onSave = {},
        onCancelGcodeExport = {},
        onSliceOptionsChanged = {},
        onSavePrinterProfile = { _, _ -> },
        onSaveFilamentProfile = { _, _, _ -> },
        onSaveSlicingProfile = { _, _ -> },
        onLayerRangeSelected = { _, _ -> },
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

internal const val TEST_SETTING_LABEL = "Accessibility setting"
internal const val TEST_SWITCH_LABEL = "Accessibility switch"
internal const val TEST_DEVICE_LABEL = "Accessibility test printer"
internal const val TEST_LAY_ON_FACE_SELECTED_LABEL = "Accessibility face selected"
internal const val TEST_SUPPORT_PAINTED_LABEL = "Accessibility support painted"

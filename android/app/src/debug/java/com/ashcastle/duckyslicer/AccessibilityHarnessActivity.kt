package com.ashcastle.duckyslicer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

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
                        SCREEN_SETTINGS -> SettingsAccessibilityHarness()
                        SCREEN_PROJECT -> WorkspaceAccessibilityHarness(
                            selectedTab = WorkspaceTab.PROJECT,
                            projectObjects = listOf(accessibilityProjectObject()),
                        )
                        SCREEN_OBJECT_SETTINGS -> ObjectSettingsAccessibilityHarness()
                        SCREEN_WORKSPACE -> {
                            val density = LocalDensity.current
                            CompositionLocalProvider(
                                LocalDensity provides Density(density.density, fontScale = 2f),
                            ) {
                                WorkspaceAccessibilityHarness()
                            }
                        }
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
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_PROJECT = "project"
        const val SCREEN_WORKSPACE = "workspace"
        const val SCREEN_OBJECT_SETTINGS = "object-settings"
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
private fun DeviceAccessibilityHarness() {
    DeviceSheet(
        profiles = listOf(
            RemoteDeviceProfile(
                id = "accessibility-device",
                name = TEST_DEVICE_LABEL,
                kind = RemoteDeviceKind.OCTOPRINT,
                baseUrl = "http://127.0.0.1",
            ),
        ),
        selectedProfileId = null,
        status = null,
        upload = null,
        gcodeAvailable = false,
        busy = false,
        uploadProgress = null,
        message = null,
        isError = false,
        confirmBeforePrint = true,
        onSelect = {},
        onSave = {},
        onDelete = {},
        onRefresh = {},
        onUpload = {},
        onStart = {},
        onPause = {},
        onResume = {},
        onCancel = {},
    )
}

@Composable
private fun SettingsAccessibilityHarness() {
    var settings by remember { mutableStateOf(AppSettings()) }
    AppSettingsSheet(
        settings = settings,
        onSettingsChanged = { settings = it },
    )
}

@Composable
private fun WorkspaceAccessibilityHarness(
    selectedTab: WorkspaceTab = WorkspaceTab.SLICE,
    projectObjects: List<ProjectObject> = emptyList(),
) {
    WorkspaceScreen(
        selectedTab = selectedTab,
        projectObjects = projectObjects,
        selectedObjectId = projectObjects.firstOrNull()?.id,
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
        remoteMessage = null,
        remoteMessageIsError = false,
        sliceOutcome = null,
        layerPreview = null,
        importing = false,
        autoLaying = false,
        arranging = false,
        splitting = false,
        cutting = false,
        slicing = false,
        sliceCancellationRequested = false,
        sliceProgress = 0,
        previewLoading = false,
        error = null,
        notice = null,
        canUndo = false,
        canRedo = false,
        onTabSelected = {},
        onChoose = {},
        onOpenProject = {},
        onSaveProject = {},
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
        onSplit = {},
        onCut = { _, _ -> },
        onSupportPaintPreview = { _, _, _ -> },
        onSupportPaintCommitted = { _, _ -> },
        onSeamPaintPreview = { _, _, _ -> },
        onSeamPaintCommitted = { _, _ -> },
        onMultiColorPaintPreview = { _, _, _ -> },
        onMultiColorPaintCommitted = { _, _ -> },
        onVariableLayerHeightsChanged = {},
        onObjectProcessOverridesChanged = {},
        onRemoveModel = {},
        onSlice = {},
        onCancelSlice = {},
        onSave = {},
        onSliceOptionsChanged = {},
        onSavePrinterProfile = { _, _ -> },
        onSaveFilamentProfile = { _, _, _ -> },
        onSaveSlicingProfile = { _, _ -> },
        onLayerRangeSelected = { _, _ -> },
        onAppSettingsChanged = {},
        onRemoteDeviceSelected = {},
        onRemoteDeviceSaved = {},
        onRemoteDeviceDeleted = {},
        onRemoteRefresh = {},
        onRemoteUpload = {},
        onRemoteStart = {},
        onRemotePause = {},
        onRemoteResume = {},
        onRemoteCancel = {},
    )
}

private fun accessibilityProjectObject() = ProjectObject(
    id = "accessibility-project-object",
    model = ModelInfo(
        fileName = "accessibility.stl",
        triangles = 1,
        dimensions = listOf(1.0, 1.0, 1.0),
        localPath = "",
        minMm = listOf(0.0, 0.0, 0.0),
        maxMm = listOf(1.0, 1.0, 1.0),
        previewTriangles = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
    ),
)

internal const val TEST_SETTING_LABEL = "Accessibility setting"
internal const val TEST_SWITCH_LABEL = "Accessibility switch"
internal const val TEST_DEVICE_LABEL = "Accessibility test printer"

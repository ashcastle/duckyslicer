package com.ashcastle.duckyslicer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale
import kotlinx.coroutines.delay

private val WorkspaceYellow = Color(0xFFF6C945)
private val WorkspaceBlack = Color(0xFF202124)
private val WorkspacePanel = Color(0xEE2A2A27)
private const val PreviewDepthBands = 12

private enum class SupportPaintTool(val state: SupportPaintState?, val label: Int) {
    ENFORCE(SupportPaintState.ENFORCE, R.string.support_enforce),
    BLOCK(SupportPaintState.BLOCK, R.string.support_block),
    ERASE(null, R.string.support_erase),
}

internal data class ModelScreenTriangle(
    val sourceFacetIndex: Int,
    val a: Offset,
    val b: Offset,
    val c: Offset,
    val depth: Float,
)

private data class ToolpathStyle(
    val code: Int,
    val label: Int,
    val color: Color,
    val widthDp: Float,
)

private val ToolpathStyles = listOf(
    ToolpathStyle(0, R.string.toolpath_outer_wall, Color(0xFFFFCF40), 0.80f),
    ToolpathStyle(1, R.string.toolpath_inner_wall, Color(0xFF44D7FF), 0.72f),
    ToolpathStyle(2, R.string.toolpath_infill, Color(0xFF668BFF), 0.58f),
    ToolpathStyle(3, R.string.toolpath_top_surface, Color(0xFFFF62D0), 0.68f),
    ToolpathStyle(4, R.string.toolpath_internal_solid, Color(0xFFA78BFA), 0.62f),
    ToolpathStyle(5, R.string.toolpath_support, Color(0xFF5EE6A8), 0.60f),
    ToolpathStyle(6, R.string.toolpath_bridge, Color(0xFFFF6B6B), 0.78f),
    ToolpathStyle(7, R.string.toolpath_adhesion, Color(0xFFFF9F43), 0.70f),
    ToolpathStyle(8, R.string.toolpath_other, Color(0xFFE7E7E2), 0.55f),
    ToolpathStyle(9, R.string.toolpath_bottom_surface, Color(0xFF00D7BD), 0.68f),
)

private val ToolpathDrawOrder = listOf(8, 2, 4, 9, 3, 5, 7, 1, 0, 6)

enum class WorkspaceTab {
    SLICE,
    PREVIEW,
    DEVICE,
    PROJECT,
    SETTINGS,
}

@Composable
fun WorkspaceScreen(
    selectedTab: WorkspaceTab,
    projectObjects: List<ProjectObject>,
    selectedObjectId: String?,
    sliceOptions: SliceOptions,
    profileCatalog: ProfileCatalog,
    appSettings: AppSettings,
    remoteDevices: List<RemoteDeviceProfile>,
    selectedRemoteDeviceId: String?,
    remoteStatus: RemoteDeviceStatus?,
    remoteUpload: RemoteUpload?,
    remoteBusy: Boolean,
    remoteUploadProgress: Int?,
    remoteMessage: String?,
    remoteMessageIsError: Boolean,
    sliceOutcome: SliceOutcome?,
    layerPreview: GcodeLayerPreview?,
    importing: Boolean,
    autoLaying: Boolean,
    arranging: Boolean,
    slicing: Boolean,
    sliceCancellationRequested: Boolean,
    sliceProgress: Int,
    previewLoading: Boolean,
    error: String?,
    notice: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    onTabSelected: (WorkspaceTab) -> Unit,
    onChoose: () -> Unit,
    onObjectSelected: (String?) -> Unit,
    onModelTransformChanged: (ModelTransform) -> Unit,
    onModelTransformPreview: (ModelTransform) -> Unit,
    onModelTransformCommitted: (ModelTransform) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDuplicate: () -> Unit,
    onArrange: () -> Unit,
    onAutoLay: () -> Unit,
    onSupportPaintPreview: (String, Int, SupportPaintState?) -> Unit,
    onSupportPaintCommitted: (String, SupportPaint) -> Unit,
    onRemoveModel: () -> Unit,
    onSlice: () -> Unit,
    onCancelSlice: () -> Unit,
    onSave: () -> Unit,
    onSliceOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinterProfile: (String) -> Unit,
    onSaveFilamentProfile: (String) -> Unit,
    onSaveSlicingProfile: (String) -> Unit,
    onLayerRangeSelected: (Int, Int) -> Unit,
    onAppSettingsChanged: (AppSettings) -> Unit,
    onRemoteDeviceSelected: (String) -> Unit,
    onRemoteDeviceSaved: (RemoteDeviceDraft) -> Unit,
    onRemoteDeviceDeleted: (String) -> Unit,
    onRemoteRefresh: () -> Unit,
    onRemoteUpload: () -> Unit,
    onRemoteStart: () -> Unit,
    onRemotePause: () -> Unit,
    onRemoteResume: () -> Unit,
    onRemoteCancel: () -> Unit,
) = BoxWithConstraints {
    val selectedObject = projectObjects.firstOrNull { it.id == selectedObjectId }
    val model = selectedObject?.model ?: projectObjects.firstOrNull()?.model
    val modelTransform = selectedObject?.transform ?: ModelTransform()
    val editingBusy = autoLaying || arranging
    val tabletLayout = maxWidth >= 600.dp
    val panelAlignment = if (tabletLayout) Alignment.BottomEnd else Alignment.BottomCenter
    val panelMaxHeight = (maxHeight - if (tabletLayout) 24.dp else 94.dp).coerceAtLeast(320.dp)
    var showModelTools by remember { mutableStateOf(false) }
    var supportPainting by remember { mutableStateOf(false) }
    var supportPaintTool by remember { mutableStateOf(SupportPaintTool.ENFORCE) }
    var visibleToolpathRoles by remember { mutableStateOf(ToolpathStyles.indices.toSet()) }
    LaunchedEffect(selectedObjectId, selectedTab) {
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) supportPainting = false
    }
    Scaffold(
        containerColor = Color(0xFF191A18),
        bottomBar = {
            if (!tabletLayout) WorkspaceNavigation(selectedTab = selectedTab, onSelected = onTabSelected)
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (tabletLayout) WorkspaceNavigationRail(selectedTab = selectedTab, onSelected = onTabSelected)
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                BedScene(
                    projectObjects = projectObjects,
                    selectedObjectId = selectedObjectId,
                    preview = if (selectedTab == WorkspaceTab.PREVIEW) layerPreview else null,
                    bedSizeX = sliceOptions.bedSizeX,
                    bedSizeY = sliceOptions.bedSizeY,
                    bedOriginX = sliceOptions.bedOriginX,
                    bedOriginY = sliceOptions.bedOriginY,
                    bedPolygon = sliceOptions.bedPolygon,
                    toolpathOpacity = appSettings.toolpathOpacity,
                    toolpathDepthContrast = appSettings.toolpathDepthContrast,
                    visibleToolpathRoles = visibleToolpathRoles,
                    previewDetail = appSettings.previewDetail,
                    previewRenderingMode = appSettings.previewRenderingMode,
                    objectManipulationEnabled = selectedTab == WorkspaceTab.SLICE &&
                        !importing && !editingBusy && !slicing && !previewLoading,
                    supportPaintObjectId = selectedObjectId.takeIf { supportPainting },
                    supportPaintState = supportPaintTool.state,
                    onObjectSelected = onObjectSelected,
                    onModelTransformPreview = onModelTransformPreview,
                    onModelTransformCommitted = onModelTransformCommitted,
                    onSupportPaintPreview = onSupportPaintPreview,
                    onSupportPaintCommitted = onSupportPaintCommitted,
                    modifier = Modifier.fillMaxSize(),
                )

            WorkspaceMenu(
                importing = importing,
                editingBusy = editingBusy,
                slicing = slicing,
                previewLoading = previewLoading,
                canExport = sliceOutcome != null,
                onImport = onChoose,
                onExport = onSave,
                canArrange = projectObjects.size > 1,
                onArrange = onArrange,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && !supportPainting) {
                ObjectToolRail(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onDuplicate = onDuplicate,
                    onAutoLay = onAutoLay,
                    autoLaying = autoLaying,
                    editingBusy = editingBusy,
                    onSupportPaint = { supportPainting = true },
                    onMore = { showModelTools = true },
                    onRemove = {
                        onRemoveModel()
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && supportPainting) {
                SupportPaintPalette(
                    selectedTool = supportPaintTool,
                    onToolSelected = { supportPaintTool = it },
                    onDone = { supportPainting = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                )
            }

            if (selectedTab == WorkspaceTab.PREVIEW) {
                PreviewExportSplitButton(
                    canExport = sliceOutcome != null,
                    canSend = sliceOutcome != null && selectedRemoteDeviceId != null && !remoteBusy,
                    onExport = onSave,
                    onSend = onRemoteUpload,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp),
                )
            } else {
                Surface(
                    color = Color.Black.copy(alpha = 0.62f),
                    contentColor = Color(0xFFF4F4EE),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .widthIn(max = 280.dp),
                ) {
                    Text(
                        text = selectedObject?.model?.fileName
                            ?: if (projectObjects.isEmpty()) {
                                stringResource(R.string.no_model)
                            } else {
                                stringResource(R.string.object_count, projectObjects.size)
                            },
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            when (selectedTab) {
                WorkspaceTab.SLICE -> SliceSheet(
                    model = model,
                    options = sliceOptions,
                    catalog = profileCatalog,
                    importing = importing || editingBusy,
                    previewLoading = previewLoading,
                    slicing = slicing,
                    cancellationRequested = sliceCancellationRequested,
                    progress = sliceProgress,
                    error = error,
                    notice = notice,
                    onSlice = onSlice,
                    onCancelSlice = onCancelSlice,
                    onOptionsChanged = onSliceOptionsChanged,
                    onSavePrinter = onSavePrinterProfile,
                    onSaveFilament = onSaveFilamentProfile,
                    onSaveSlicing = onSaveSlicingProfile,
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )

                WorkspaceTab.PREVIEW -> PreviewSheet(
                    outcome = sliceOutcome,
                    preview = layerPreview,
                    loading = previewLoading,
                    error = error,
                    toolpathOpacity = appSettings.toolpathOpacity,
                    onToolpathOpacityChanged = {
                        onAppSettingsChanged(appSettings.copy(toolpathOpacity = it))
                    },
                    toolpathDepthContrast = appSettings.toolpathDepthContrast,
                    onToolpathDepthContrastChanged = {
                        onAppSettingsChanged(appSettings.copy(toolpathDepthContrast = it))
                    },
                    visibleToolpathRoles = visibleToolpathRoles,
                    onToolpathRoleVisibilityChanged = { role, visible ->
                        visibleToolpathRoles = if (visible) {
                            visibleToolpathRoles + role
                        } else {
                            visibleToolpathRoles - role
                        }
                    },
                    onLayerRangeSelected = onLayerRangeSelected,
                    onGoToSlice = { onTabSelected(WorkspaceTab.SLICE) },
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )

                WorkspaceTab.DEVICE -> DeviceSheet(
                    profiles = remoteDevices,
                    selectedProfileId = selectedRemoteDeviceId,
                    status = remoteStatus,
                    upload = remoteUpload,
                    gcodeAvailable = sliceOutcome != null,
                    busy = remoteBusy,
                    uploadProgress = remoteUploadProgress,
                    message = remoteMessage,
                    isError = remoteMessageIsError,
                    confirmBeforePrint = appSettings.confirmBeforeRemotePrint,
                    onSelect = onRemoteDeviceSelected,
                    onSave = onRemoteDeviceSaved,
                    onDelete = onRemoteDeviceDeleted,
                    onRefresh = onRemoteRefresh,
                    onUpload = onRemoteUpload,
                    onStart = onRemoteStart,
                    onPause = onRemotePause,
                    onResume = onRemoteResume,
                    onCancel = onRemoteCancel,
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )

                WorkspaceTab.PROJECT -> ProjectSheet(
                    objects = projectObjects,
                    selectedObjectId = selectedObjectId,
                    outcome = sliceOutcome,
                    onObjectSelected = onObjectSelected,
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )

                WorkspaceTab.SETTINGS -> AppSettingsSheet(
                    settings = appSettings,
                    onSettingsChanged = onAppSettingsChanged,
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )
            }
        }
    }
    }
    if (showModelTools && selectedObject != null) {
        ModelTransformSheet(
            transform = modelTransform,
            bedSizeX = sliceOptions.bedSizeX,
            bedSizeY = sliceOptions.bedSizeY,
            bedPolygon = sliceOptions.bedPolygon,
            autoLaying = autoLaying,
            onAutoLay = onAutoLay,
            onTransformChanged = onModelTransformChanged,
            onRemoveModel = {
                showModelTools = false
                onRemoveModel()
            },
            onDismiss = { showModelTools = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelTransformSheet(
    transform: ModelTransform,
    bedSizeX: Float,
    bedSizeY: Float,
    bedPolygon: List<Float>,
    autoLaying: Boolean,
    onAutoLay: () -> Unit,
    onTransformChanged: (ModelTransform) -> Unit,
    onRemoveModel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val effectiveBedPolygon = bedPolygon.takeIf { bedPolygonIsValid(it, bedSizeX, bedSizeY) }
        ?: rectangularBedPolygon(bedSizeX, bedSizeY)

    fun constrainedTransform(offsetX: Float, offsetY: Float): ModelTransform {
        val center = coercePointToBedPolygon(
            bedSizeX / 2f + offsetX,
            bedSizeY / 2f + offsetY,
            effectiveBedPolygon,
        )
        return transform.copy(
            offsetXmm = center.first - bedSizeX / 2f,
            offsetYmm = center.second - bedSizeY / 2f,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.model_placement), style = MaterialTheme.typography.titleLarge)
            TransformSlider(
                label = stringResource(R.string.move_x),
                valueText = stringResource(R.string.millimeters_value, transform.offsetXmm),
                value = transform.offsetXmm,
                range = -bedSizeX / 2f..bedSizeX / 2f,
                enabled = !autoLaying,
                onValueChange = { onTransformChanged(constrainedTransform(it, transform.offsetYmm)) },
            )
            TransformSlider(
                label = stringResource(R.string.move_y),
                valueText = stringResource(R.string.millimeters_value, transform.offsetYmm),
                value = transform.offsetYmm,
                range = -bedSizeY / 2f..bedSizeY / 2f,
                enabled = !autoLaying,
                onValueChange = { onTransformChanged(constrainedTransform(transform.offsetXmm, it)) },
            )
            TransformSlider(
                label = stringResource(R.string.rotate_x),
                valueText = stringResource(R.string.degrees_value, transform.rotationXdeg),
                value = transform.rotationXdeg,
                range = -180f..180f,
                enabled = !autoLaying,
                onValueChange = { onTransformChanged(transform.copy(rotationXdeg = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.rotate_y),
                valueText = stringResource(R.string.degrees_value, transform.rotationYdeg),
                value = transform.rotationYdeg,
                range = -180f..180f,
                enabled = !autoLaying,
                onValueChange = { onTransformChanged(transform.copy(rotationYdeg = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.rotate_z),
                valueText = stringResource(R.string.degrees_value, transform.rotationZdeg),
                value = transform.rotationZdeg,
                range = -180f..180f,
                enabled = !autoLaying,
                onValueChange = { onTransformChanged(transform.copy(rotationZdeg = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.scale),
                valueText = stringResource(R.string.percent_value, (transform.scale * 100).roundToInt()),
                value = transform.scale,
                range = 0.25f..3f,
                enabled = !autoLaying,
                onValueChange = { onTransformChanged(transform.copy(scale = it)) },
            )
            Button(
                onClick = onAutoLay,
                enabled = !autoLaying,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WorkspaceYellow,
                    contentColor = WorkspaceBlack,
                ),
            ) {
                if (autoLaying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = WorkspaceBlack,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_lay_working))
                } else {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_lay))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        onTransformChanged(transform.copy(offsetXmm = 0f, offsetYmm = 0f))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !autoLaying,
                ) {
                    Text(stringResource(R.string.center_model))
                }
                TextButton(
                    onClick = {
                        val nextRotation = ((transform.rotationZdeg + 90f + 180f) % 360f) - 180f
                        onTransformChanged(transform.copy(rotationZdeg = nextRotation))
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !autoLaying,
                ) {
                    Text(stringResource(R.string.rotate_90))
                }
                TextButton(
                    onClick = { onTransformChanged(ModelTransform()) },
                    modifier = Modifier.weight(1f),
                    enabled = !autoLaying,
                ) {
                    Text(stringResource(R.string.reset))
                }
            }
            TextButton(
                onClick = onRemoveModel,
                enabled = !autoLaying,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.remove_model), color = Color(0xFFFF8A80))
            }
        }
    }
}

@Composable
private fun TransformSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(valueText, color = Color(0xFFC8C9C2))
    }
    Slider(
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        valueRange = range,
        colors = duckySliderColors(),
    )
}

@Composable
private fun WorkspaceMenu(
    importing: Boolean,
    editingBusy: Boolean,
    slicing: Boolean,
    previewLoading: Boolean,
    canExport: Boolean,
    canArrange: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onArrange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            color = Color.Black.copy(alpha = 0.68f),
            contentColor = Color(0xFFF4F4EE),
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(50.dp),
        ) {
            IconButton(onClick = { expanded = true }) {
                if (importing || editingBusy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = WorkspaceYellow, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_model)) },
                leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                enabled = !importing && !editingBusy && !slicing && !previewLoading,
                onClick = {
                    expanded = false
                    onImport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.arrange_objects)) },
                leadingIcon = { Icon(Icons.Default.GridView, null) },
                enabled = canArrange && !editingBusy && !slicing && !previewLoading,
                onClick = {
                    expanded = false
                    onArrange()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_gcode)) },
                leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                enabled = canExport,
                onClick = {
                    expanded = false
                    onExport()
                },
            )
        }
    }
}

@Composable
private fun PreviewExportSplitButton(
    canExport: Boolean,
    canSend: Boolean,
    onExport: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = Color.Black.copy(alpha = 0.68f),
                contentColor = Color(0xFFF4F4EE),
                shape = RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp),
                modifier = Modifier.width(34.dp).height(50.dp),
            ) {
                IconButton(
                    enabled = canExport,
                    onClick = { expanded = true },
                ) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.export_options))
                }
            }
            Spacer(Modifier.width(2.dp))
            Surface(
                color = WorkspaceYellow,
                contentColor = WorkspaceBlack,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(50.dp),
            ) {
                IconButton(enabled = canExport, onClick = onExport) {
                    Icon(Icons.Default.SaveAlt, contentDescription = stringResource(R.string.export_gcode))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_gcode)) },
                leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                enabled = canExport,
                onClick = {
                    expanded = false
                    onExport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.send_gcode)) },
                leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                enabled = canSend,
                onClick = {
                    expanded = false
                    onSend()
                },
            )
        }
    }
}

@Composable
private fun WorkspaceNavigation(
    selectedTab: WorkspaceTab,
    onSelected: (WorkspaceTab) -> Unit,
) {
    NavigationBar(containerColor = Color(0xFF242522)) {
        workspaceNavigationItems().forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WorkspaceBlack,
                    selectedTextColor = WorkspaceYellow,
                    indicatorColor = WorkspaceYellow,
                    unselectedIconColor = Color(0xFFD0D1CB),
                    unselectedTextColor = Color(0xFFD0D1CB),
                ),
            )
        }
    }
}

@Composable
private fun WorkspaceNavigationRail(
    selectedTab: WorkspaceTab,
    onSelected: (WorkspaceTab) -> Unit,
) {
    NavigationRail(containerColor = Color(0xFF242522)) {
        Spacer(Modifier.height(72.dp))
        workspaceNavigationItems().forEach { (tab, icon, label) ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(stringResource(label), maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = WorkspaceBlack,
                    selectedTextColor = WorkspaceYellow,
                    indicatorColor = WorkspaceYellow,
                    unselectedIconColor = Color(0xFFD0D1CB),
                    unselectedTextColor = Color(0xFFD0D1CB),
                ),
            )
        }
    }
}

private fun workspaceNavigationItems() = listOf(
    Triple(WorkspaceTab.SLICE, Icons.Default.Tune, R.string.tab_slice),
    Triple(WorkspaceTab.PREVIEW, Icons.Default.Visibility, R.string.tab_preview),
    Triple(WorkspaceTab.DEVICE, Icons.Default.Devices, R.string.tab_device),
    Triple(WorkspaceTab.PROJECT, Icons.Default.Folder, R.string.tab_project),
    Triple(WorkspaceTab.SETTINGS, Icons.Default.Settings, R.string.settings),
)

@Composable
private fun BedScene(
    projectObjects: List<ProjectObject>,
    selectedObjectId: String?,
    preview: GcodeLayerPreview?,
    bedSizeX: Float,
    bedSizeY: Float,
    bedOriginX: Float,
    bedOriginY: Float,
    bedPolygon: List<Float>,
    toolpathOpacity: Float,
    toolpathDepthContrast: Float,
    visibleToolpathRoles: Set<Int>,
    previewDetail: PreviewDetail,
    previewRenderingMode: PreviewRenderingMode,
    objectManipulationEnabled: Boolean,
    supportPaintObjectId: String?,
    supportPaintState: SupportPaintState?,
    onObjectSelected: (String?) -> Unit,
    onModelTransformPreview: (ModelTransform) -> Unit,
    onModelTransformCommitted: (ModelTransform) -> Unit,
    onSupportPaintPreview: (String, Int, SupportPaintState?) -> Unit,
    onSupportPaintCommitted: (String, SupportPaint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val depthPreviewSupported = remember(context) { supportsDepthTestedPreview(context) }
    val previewCapabilities = remember(context) { previewDeviceCapabilities(context) }
    val effectivePreviewDetail = remember(previewDetail, previewCapabilities) {
        resolvePreviewDetail(previewDetail, previewCapabilities)
    }
    if (
        preview != null &&
        previewRenderingMode == PreviewRenderingMode.DEPTH_TESTED &&
        depthPreviewSupported
    ) {
        DepthTestedToolpathScene(
            preview = preview,
            bedSizeX = bedSizeX,
            bedSizeY = bedSizeY,
            bedOriginX = bedOriginX,
            bedOriginY = bedOriginY,
            bedPolygon = bedPolygon,
            opacity = toolpathOpacity,
            depthContrast = toolpathDepthContrast,
            visibleRoles = visibleToolpathRoles,
            detail = effectivePreviewDetail,
            modifier = modifier,
        )
        return
    }
    var yaw by remember { mutableFloatStateOf(-45f) }
    var pitch by remember { mutableFloatStateOf(55f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var interactionActive by remember { mutableStateOf(false) }
    var refinedPreview by remember { mutableStateOf(true) }
    val objectIds = projectObjects.map(ProjectObject::id)
    var modelScreenBounds by remember(objectIds) { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    var modelScreenTriangles by remember(objectIds) {
        mutableStateOf<Map<String, List<ModelScreenTriangle>>>(emptyMap())
    }
    val currentObjects by rememberUpdatedState(projectObjects)
    val currentSelectionCallback by rememberUpdatedState(onObjectSelected)
    val currentTransformCallback by rememberUpdatedState(onModelTransformPreview)
    val currentTransformCommitCallback by rememberUpdatedState(onModelTransformCommitted)
    val currentSupportPaintPreviewCallback by rememberUpdatedState(onSupportPaintPreview)
    val currentSupportPaintCommitCallback by rememberUpdatedState(onSupportPaintCommitted)
    val effectiveBedPolygon = remember(bedPolygon, bedSizeX, bedSizeY) {
        bedPolygon.takeIf { bedPolygonIsValid(it, bedSizeX, bedSizeY) }
            ?: rectangularBedPolygon(bedSizeX, bedSizeY)
    }
    val previewPaths = remember(preview) {
        Array(PreviewDepthBands) { Array(ToolpathStyles.size) { Path() } }
    }
    val movingPreviewPlan = remember(preview, effectivePreviewDetail) {
        preview?.buildRenderPlan(
            segmentBudget = compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = false),
        )
    }
    val refinedPreviewPlan = remember(preview, effectivePreviewDetail) {
        preview?.buildRenderPlan(
            segmentBudget = compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = true),
        )
    }

    LaunchedEffect(interactionActive) {
        if (interactionActive) {
            refinedPreview = false
        } else {
            delay(650)
            refinedPreview = true
        }
    }

    Canvas(
        modifier.pointerInput(objectIds, preview, objectManipulationEnabled, supportPaintObjectId, supportPaintState) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val paintingObject = supportPaintObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val hitObjectId = if (objectManipulationEnabled && paintingObject == null) {
                    modelScreenBounds.entries.toList().asReversed().firstOrNull { (_, bounds) ->
                        bounds.inflate(14.dp.toPx()).contains(down.position)
                    }?.key
                } else {
                    null
                }
                val dragStartTransform = currentObjects
                    .firstOrNull { it.id == hitObjectId }
                    ?.transform
                val paintStart = paintingObject?.supportPaint
                val paintedFacets = HashSet<Int>()
                fun paintAt(position: Offset) {
                    val objectId = paintingObject?.id ?: return
                    val hit = closestPaintFacet(
                        modelScreenTriangles[objectId].orEmpty(),
                        position,
                        18.dp.toPx(),
                    ) ?: return
                    if (paintedFacets.add(hit)) {
                        currentSupportPaintPreviewCallback(objectId, hit, supportPaintState)
                    }
                }
                if (hitObjectId != null) currentSelectionCallback(hitObjectId)
                if (paintingObject != null) paintAt(down.position)
                var movement = 0f
                interactionActive = true
                try {
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size == 1 -> {
                                val change = pressed.first()
                                val delta = change.position - change.previousPosition
                                movement += abs(delta.x) + abs(delta.y)
                                if (paintingObject != null) {
                                    paintAt(change.position)
                                } else if (hitObjectId != null) {
                                    val currentSceneScale = min(
                                        size.width * 0.64f,
                                        size.height * 0.72f,
                                    ) / max(bedSizeX, bedSizeY) * zoom
                                    val pitchRadians = pitch / 180f * PI.toFloat()
                                    val yawRadians = yaw / 180f * PI.toFloat()
                                    val projectedX = delta.x / currentSceneScale.coerceAtLeast(0.001f)
                                    val projectedY = delta.y /
                                        (currentSceneScale * sin(pitchRadians)).coerceAtLeast(0.001f)
                                    val bedDeltaX = projectedX * cos(yawRadians) + projectedY * sin(yawRadians)
                                    val bedDeltaY = -projectedX * sin(yawRadians) + projectedY * cos(yawRadians)
                                    val transform = currentObjects
                                        .firstOrNull { it.id == hitObjectId }
                                        ?.transform
                                        ?: dragStartTransform
                                        ?: ModelTransform()
                                    val coerced = coercePointToBedPolygon(
                                        bedSizeX / 2f + transform.offsetXmm + bedDeltaX,
                                        bedSizeY / 2f + transform.offsetYmm + bedDeltaY,
                                        effectiveBedPolygon,
                                    )
                                    currentTransformCallback(
                                        transform.copy(
                                            offsetXmm = coerced.first - bedSizeX / 2f,
                                            offsetYmm = coerced.second - bedSizeY / 2f,
                                        ),
                                    )
                                } else {
                                    yaw += delta.x * 0.32f
                                    pitch = (pitch - delta.y * 0.26f).coerceIn(22f, 88f)
                                }
                            }

                            pressed.size >= 2 -> {
                                pan += event.calculatePan()
                                zoom = (zoom * event.calculateZoom()).coerceIn(0.45f, 4.5f)
                            }
                        }
                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    interactionActive = false
                    if (paintingObject != null && paintStart != null && paintedFacets.isNotEmpty()) {
                        currentSupportPaintCommitCallback(paintingObject.id, paintStart)
                    } else if (hitObjectId != null && dragStartTransform != null && movement >= 1f) {
                        currentTransformCommitCallback(dragStartTransform)
                    } else if (hitObjectId == null && paintingObject == null && movement < 12f) {
                        currentSelectionCallback(null)
                    }
                }
            }
        },
    ) {
        val yawRadians = yaw / 180f * PI.toFloat()
        val pitchRadians = pitch / 180f * PI.toFloat()
        val sceneScale = min(size.width * 0.64f, size.height * 0.72f) / max(bedSizeX, bedSizeY) * zoom
        val sceneCenter = Offset(size.width / 2f + pan.x, size.height * 0.48f + pan.y)

        fun project(x: Float, y: Float, z: Float = 0f): Offset {
            val dx = x - bedSizeX / 2f
            val dy = y - bedSizeY / 2f
            val rotatedX = dx * cos(yawRadians) - dy * sin(yawRadians)
            val rotatedY = dx * sin(yawRadians) + dy * cos(yawRadians)
            val screenY = rotatedY * sin(pitchRadians) - z * cos(pitchRadians)
            return Offset(
                x = sceneCenter.x + rotatedX * sceneScale,
                y = sceneCenter.y + screenY * sceneScale,
            )
        }

        fun cameraDepth(x: Float, y: Float, z: Float): Float {
            val dx = x - bedSizeX / 2f
            val dy = y - bedSizeY / 2f
            val rotatedY = dx * sin(yawRadians) + dy * cos(yawRadians)
            return rotatedY * cos(pitchRadians) + z * sin(pitchRadians)
        }

        val bed = Path().apply {
            val first = project(effectiveBedPolygon[0], effectiveBedPolygon[1])
            moveTo(first.x, first.y)
            for (index in 2 until effectiveBedPolygon.size step 2) {
                val point = project(effectiveBedPolygon[index], effectiveBedPolygon[index + 1])
                lineTo(point.x, point.y)
            }
            close()
        }
        drawPath(
            bed,
            color = if (preview == null) Color(0xFF343732) else Color(0xFF2D302D).copy(alpha = 0.7f),
        )

        val gridStep = if (max(bedSizeX, bedSizeY) <= 230f) 20f else 30f
        var gridX = 0f
        while (gridX <= bedSizeX) {
            verticalBedSegments(gridX, effectiveBedPolygon).forEach { (start, end) ->
                drawLine(
                    if (preview == null) Color(0xFF555950) else Color(0xFF70746B).copy(alpha = 0.45f),
                    project(gridX, start),
                    project(gridX, end),
                    1.dp.toPx(),
                )
            }
            gridX += gridStep
        }
        var gridY = 0f
        while (gridY <= bedSizeY) {
            horizontalBedSegments(gridY, effectiveBedPolygon).forEach { (start, end) ->
                drawLine(
                    if (preview == null) Color(0xFF555950) else Color(0xFF70746B).copy(alpha = 0.45f),
                    project(start, gridY),
                    project(end, gridY),
                    1.dp.toPx(),
                )
            }
            gridY += gridStep
        }
        drawPath(
            bed,
            color = if (preview == null) WorkspaceYellow.copy(alpha = 0.75f) else Color(0xFF9A9D94),
            style = Stroke(2.dp.toPx()),
        )

        if (preview != null) {
            previewPaths.forEach { bandPaths -> bandPaths.forEach(Path::reset) }
            val renderPlan = if (interactionActive || !refinedPreview) {
                movingPreviewPlan
            } else {
                refinedPreviewPlan
            }
            val zSpan = (preview.maxZMm - preview.minZMm).coerceAtLeast(0.001f)
            renderPlan?.segmentOffsets?.forEachIndexed { selectedIndex, segmentIndex ->
                val role = preview.segments[segmentIndex + 5].roundToInt()
                    .coerceIn(0, ToolpathStyles.lastIndex)
                if (role !in visibleToolpathRoles) return@forEachIndexed
                val startX = preview.segments[segmentIndex] - bedOriginX
                val startY = preview.segments[segmentIndex + 1] - bedOriginY
                val z = preview.segments[segmentIndex + 4]
                val normalizedHeight = ((z - preview.minZMm) / zSpan).coerceIn(0f, 1f)
                val depthBand = (normalizedHeight * (PreviewDepthBands - 1))
                    .roundToInt()
                    .coerceIn(0, PreviewDepthBands - 1)
                val rolePath = previewPaths[depthBand][role]
                val end = project(
                    preview.segments[segmentIndex + 2] - bedOriginX,
                    preview.segments[segmentIndex + 3] - bedOriginY,
                    z,
                )
                if (renderPlan.connectsToPrevious[selectedIndex]) {
                    rolePath.lineTo(end.x, end.y)
                } else {
                    val start = project(startX, startY, z)
                    rolePath.moveTo(start.x, start.y)
                    rolePath.lineTo(end.x, end.y)
                }
            }
            previewPaths.forEachIndexed { depthBand, bandPaths ->
                val normalizedHeight = depthBand.toFloat() / (PreviewDepthBands - 1)
                ToolpathDrawOrder.forEach { role ->
                    val style = ToolpathStyles[role]
                    val coreWidth = style.widthDp.dp.toPx()
                    val shadeAmount = toolpathDepthContrast * (1f - normalizedHeight) * 0.72f
                    val highlightAmount = toolpathDepthContrast * normalizedHeight * 0.08f
                    val shadedColor = lerp(style.color, Color(0xFF10120F), shadeAmount)
                    val visibleColor = lerp(shadedColor, Color.White, highlightAmount)
                    val heightAlpha = 1f - toolpathDepthContrast * (1f - normalizedHeight) * 0.36f
                    drawPath(
                        path = bandPaths[role],
                        color = Color.Black.copy(alpha = toolpathOpacity * 0.82f),
                        style = Stroke(width = coreWidth + 0.5.dp.toPx()),
                    )
                    drawPath(
                        path = bandPaths[role],
                        color = visibleColor.copy(alpha = toolpathOpacity * heightAlpha),
                        style = Stroke(width = coreWidth),
                    )
                }
            }
        } else if (projectObjects.isNotEmpty()) {
            val nextBounds = mutableMapOf<String, Rect>()
            val nextScreenTriangles = mutableMapOf<String, List<ModelScreenTriangle>>()
            projectObjects.forEach { projectObject ->
                val model = projectObject.model
                val modelTransform = projectObject.transform
                val objectSelected = projectObject.id == selectedObjectId
                val minimumRotatedZ = modelTransform.minimumRotatedZ(model)
                val meshPath = Path()
                val enforcePaintPath = Path()
                val blockPaintPath = Path()
                val screenTriangles = ArrayList<ModelScreenTriangle>(model.previewTriangleIndices.size)
                var triangleIndex = 0
                var minimumScreenX = Float.POSITIVE_INFINITY
                var minimumScreenY = Float.POSITIVE_INFINITY
                var maximumScreenX = Float.NEGATIVE_INFINITY
                var maximumScreenY = Float.NEGATIVE_INFINITY
                while (triangleIndex + 8 < model.previewTriangles.size) {
                    val aPosition = modelTransform.placeVertex(
                        model.previewTriangles[triangleIndex],
                        model.previewTriangles[triangleIndex + 1],
                        model.previewTriangles[triangleIndex + 2],
                        model,
                        bedSizeX,
                        bedSizeY,
                        minimumRotatedZ,
                    )
                    val bPosition = modelTransform.placeVertex(
                        model.previewTriangles[triangleIndex + 3],
                        model.previewTriangles[triangleIndex + 4],
                        model.previewTriangles[triangleIndex + 5],
                        model,
                        bedSizeX,
                        bedSizeY,
                        minimumRotatedZ,
                    )
                    val cPosition = modelTransform.placeVertex(
                        model.previewTriangles[triangleIndex + 6],
                        model.previewTriangles[triangleIndex + 7],
                        model.previewTriangles[triangleIndex + 8],
                        model,
                        bedSizeX,
                        bedSizeY,
                        minimumRotatedZ,
                    )
                    val a = project(aPosition[0], aPosition[1], aPosition[2])
                    val b = project(bPosition[0], bPosition[1], bPosition[2])
                    val c = project(cPosition[0], cPosition[1], cPosition[2])
                    val sourceFacetIndex = model.previewTriangleIndices
                        .getOrElse(triangleIndex / 9) { triangleIndex / 9 }
                    screenTriangles += ModelScreenTriangle(
                        sourceFacetIndex = sourceFacetIndex,
                        a = a,
                        b = b,
                        c = c,
                        depth = (
                            cameraDepth(aPosition[0], aPosition[1], aPosition[2]) +
                                cameraDepth(bPosition[0], bPosition[1], bPosition[2]) +
                                cameraDepth(cPosition[0], cPosition[1], cPosition[2])
                            ) / 3f,
                    )
                    listOf(a, b, c).forEach { point ->
                        minimumScreenX = min(minimumScreenX, point.x)
                        minimumScreenY = min(minimumScreenY, point.y)
                        maximumScreenX = max(maximumScreenX, point.x)
                        maximumScreenY = max(maximumScreenY, point.y)
                    }
                    meshPath.moveTo(a.x, a.y)
                    meshPath.lineTo(b.x, b.y)
                    meshPath.lineTo(c.x, c.y)
                    meshPath.close()
                    when (projectObject.supportPaint.facets[sourceFacetIndex]) {
                        SupportPaintState.ENFORCE -> enforcePaintPath.addTriangle(a, b, c)
                        SupportPaintState.BLOCK -> blockPaintPath.addTriangle(a, b, c)
                        null -> Unit
                    }
                    triangleIndex += 9
                }
                if (minimumScreenX.isFinite()) {
                    nextBounds[projectObject.id] = Rect(
                        minimumScreenX,
                        minimumScreenY,
                        maximumScreenX,
                        maximumScreenY,
                    )
                }
                nextScreenTriangles[projectObject.id] = screenTriangles
                drawPath(meshPath, WorkspaceYellow.copy(alpha = if (objectSelected) 0.24f else 0.14f))
                drawPath(
                    meshPath,
                    if (objectSelected) Color.White.copy(alpha = 0.92f)
                    else WorkspaceYellow.copy(alpha = 0.52f),
                    style = Stroke(if (objectSelected) 1.5.dp.toPx() else 0.7.dp.toPx()),
                )
                drawPath(enforcePaintPath, Color(0xFF5EE6A8).copy(alpha = 0.9f))
                drawPath(
                    enforcePaintPath,
                    Color(0xFF163C2E),
                    style = Stroke(1.2.dp.toPx()),
                )
                drawPath(blockPaintPath, Color(0xFFFF6B6B).copy(alpha = 0.9f))
                drawPath(
                    blockPaintPath,
                    Color(0xFF541F1F),
                    style = Stroke(1.2.dp.toPx()),
                )
            }
            if (modelScreenBounds != nextBounds) modelScreenBounds = nextBounds
            if (modelScreenTriangles != nextScreenTriangles) modelScreenTriangles = nextScreenTriangles
        }
    }
}

private fun Path.addTriangle(a: Offset, b: Offset, c: Offset) {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    close()
}

internal fun closestPaintFacet(
    triangles: List<ModelScreenTriangle>,
    point: Offset,
    brushRadius: Float,
): Int? {
    val inside = triangles.filter { triangle -> pointInsideTriangle(point, triangle) }
    if (inside.isNotEmpty()) return inside.maxByOrNull(ModelScreenTriangle::depth)?.sourceFacetIndex
    return triangles
        .asSequence()
        .map { triangle ->
            val distance = minOf(
                pointToSegmentDistance(point, triangle.a, triangle.b),
                pointToSegmentDistance(point, triangle.b, triangle.c),
                pointToSegmentDistance(point, triangle.c, triangle.a),
            )
            triangle to distance
        }
        .filter { (_, distance) -> distance <= brushRadius }
        .minWithOrNull(
            compareBy<Pair<ModelScreenTriangle, Float>> { it.second }
                .thenByDescending { it.first.depth },
        )
        ?.first
        ?.sourceFacetIndex
}

private fun pointInsideTriangle(point: Offset, triangle: ModelScreenTriangle): Boolean {
    fun sign(first: Offset, second: Offset, third: Offset): Float =
        (first.x - third.x) * (second.y - third.y) -
            (second.x - third.x) * (first.y - third.y)
    val first = sign(point, triangle.a, triangle.b)
    val second = sign(point, triangle.b, triangle.c)
    val third = sign(point, triangle.c, triangle.a)
    return !(first < 0f || second < 0f || third < 0f) ||
        !(first > 0f || second > 0f || third > 0f)
}

private fun pointToSegmentDistance(point: Offset, start: Offset, end: Offset): Float {
    val segment = end - start
    val lengthSquared = segment.x * segment.x + segment.y * segment.y
    if (lengthSquared <= 0.0001f) return (point - start).getDistance()
    val offset = point - start
    val position = ((offset.x * segment.x + offset.y * segment.y) / lengthSquared).coerceIn(0f, 1f)
    return (point - (start + segment * position)).getDistance()
}

@Composable
private fun SupportPaintPalette(
    selectedTool: SupportPaintTool,
    onToolSelected: (SupportPaintTool) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.88f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SupportPaintTool.entries.forEach { tool ->
                    TextButton(
                        onClick = { onToolSelected(tool) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selectedTool == tool) {
                                when (tool) {
                                    SupportPaintTool.ENFORCE -> Color(0xFF296A50)
                                    SupportPaintTool.BLOCK -> Color(0xFF793D3D)
                                    SupportPaintTool.ERASE -> Color(0xFF555752)
                                }
                            } else {
                                Color.Transparent
                            },
                            contentColor = Color(0xFFF4F4EE),
                        ),
                    ) {
                        Text(stringResource(tool.label), maxLines = 1)
                    }
                }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.done), color = WorkspaceYellow)
                }
            }
            Text(
                stringResource(R.string.support_paint_hint),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ObjectToolRail(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDuplicate: () -> Unit,
    onAutoLay: () -> Unit,
    autoLaying: Boolean,
    editingBusy: Boolean,
    onSupportPaint: () -> Unit,
    onMore: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUndo, enabled = canUndo && !editingBusy) {
                Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
            }
            IconButton(onClick = onRedo, enabled = canRedo && !editingBusy) {
                Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
            }
            IconButton(onClick = onDuplicate, enabled = !editingBusy) {
                Icon(Icons.Default.ContentCopy, stringResource(R.string.duplicate_object))
            }
            IconButton(onClick = onAutoLay, enabled = !editingBusy) {
                if (autoLaying) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoFixHigh, stringResource(R.string.auto_lay))
                }
            }
            IconButton(onClick = onSupportPaint, enabled = !editingBusy) {
                Icon(Icons.Default.Brush, stringResource(R.string.paint_support))
            }
            IconButton(onClick = onMore, enabled = !editingBusy) {
                Icon(Icons.Default.Tune, stringResource(R.string.more_settings))
            }
            IconButton(onClick = onRemove, enabled = !editingBusy) {
                Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_model), tint = Color(0xFFFF8A80))
            }
        }
    }
}

@Composable
private fun SliceSheet(
    model: ModelInfo?,
    options: SliceOptions,
    catalog: ProfileCatalog,
    importing: Boolean,
    previewLoading: Boolean,
    slicing: Boolean,
    cancellationRequested: Boolean,
    progress: Int,
    error: String?,
    notice: String?,
    onSlice: () -> Unit,
    onCancelSlice: () -> Unit,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinter: (String) -> Unit,
    onSaveFilament: (String) -> Unit,
    onSaveSlicing: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(modifier) {
        ProfileSettings(
            options = options,
            catalog = catalog,
            enabled = !slicing && !importing && !previewLoading,
            onOptionsChanged = onOptionsChanged,
            onSavePrinter = onSavePrinter,
            onSaveFilament = onSaveFilament,
            onSaveSlicing = onSaveSlicing,
        )
        if (model != null) {
            Text(
                model.dimensions.joinToString(" × ") { String.format(Locale.getDefault(), "%.1f", it) } + " mm",
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(stringResource(R.string.import_from_menu), color = Color(0xFFC8C9C2))
        }
        if (error != null) Text(error, color = Color(0xFFFF8A80))
        if (notice != null) Text(notice, color = WorkspaceYellow)
        if (slicing) {
            Text(stringResource(R.string.slicing_progress, progress), fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = WorkspaceYellow,
            )
            TextButton(
                onClick = onCancelSlice,
                enabled = !cancellationRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (cancellationRequested) R.string.canceling_slice else R.string.cancel,
                    ),
                )
            }
        }
        if (model != null) {
            Button(
                onClick = onSlice,
                enabled = !slicing && !importing && !previewLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = primaryButtonColors(),
            ) {
                Icon(Icons.Default.Layers, null)
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.slice_model), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PreviewSheet(
    outcome: SliceOutcome?,
    preview: GcodeLayerPreview?,
    loading: Boolean,
    error: String?,
    toolpathOpacity: Float,
    onToolpathOpacityChanged: (Float) -> Unit,
    toolpathDepthContrast: Float,
    onToolpathDepthContrastChanged: (Float) -> Unit,
    visibleToolpathRoles: Set<Int>,
    onToolpathRoleVisibilityChanged: (Int, Boolean) -> Unit,
    onLayerRangeSelected: (Int, Int) -> Unit,
    onGoToSlice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(modifier) {
        if (outcome == null) {
            Text(stringResource(R.string.preview_requires_slice), fontWeight = FontWeight.SemiBold)
            Button(onClick = onGoToSlice, colors = primaryButtonColors()) {
                Text(stringResource(R.string.tab_slice))
            }
            return@WorkspaceCard
        }
        if (loading || preview == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), color = WorkspaceYellow, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.loading_preview))
            }
        }
        if (error != null) Text(error, color = Color(0xFFFF8A80))
        if (preview != null) {
            val lastLayerIndex = (preview.layerCount - 1).coerceAtLeast(0)
            val safeStartLayer = preview.startLayer.coerceIn(0, lastLayerIndex)
            val safeEndLayer = preview.endLayer.coerceIn(safeStartLayer, lastLayerIndex)
            var selectedRange by remember(safeStartLayer, safeEndLayer, preview.layerCount) {
                mutableStateOf(safeStartLayer.toFloat()..safeEndLayer.toFloat())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(
                        R.string.layer_range,
                        safeStartLayer + 1,
                        safeEndLayer + 1,
                        preview.layerCount,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.z_range, preview.minZMm, preview.maxZMm),
                    color = Color(0xFFC8C9C2),
                )
            }
            if (preview.layerCount > 1) {
                RangeSlider(
                    value = selectedRange,
                    onValueChange = { selectedRange = it },
                    onValueChangeFinished = {
                        onLayerRangeSelected(
                            selectedRange.start.roundToInt(),
                            selectedRange.endInclusive.roundToInt(),
                        )
                    },
                    valueRange = 0f..lastLayerIndex.toFloat(),
                    steps = 0,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.toolpath_opacity, (toolpathOpacity * 100).roundToInt()),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = toolpathOpacity,
                        onValueChange = onToolpathOpacityChanged,
                        valueRange = 0.3f..1f,
                        colors = duckySliderColors(),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.toolpath_depth_contrast,
                            (toolpathDepthContrast * 100).roundToInt(),
                        ),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = toolpathDepthContrast,
                        onValueChange = onToolpathDepthContrastChanged,
                        valueRange = 0f..1f,
                        colors = duckySliderColors(),
                    )
                }
            }
            ToolpathStyles.chunked(2).forEach { rowStyles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowStyles.forEach { style ->
                        val visible = style.code in visibleToolpathRoles
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .toggleable(
                                    value = visible,
                                    role = Role.Checkbox,
                                    onValueChange = { nextVisible ->
                                        onToolpathRoleVisibilityChanged(style.code, nextVisible)
                                    },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(width = 18.dp, height = 6.dp),
                                color = if (visible) style.color else Color(0xFF62635F),
                                shape = RoundedCornerShape(50),
                            ) {}
                            Spacer(Modifier.width(7.dp))
                            Text(
                                stringResource(style.label),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (visible) Color(0xFFE2E3DD) else Color(0xFF858681),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectSheet(
    objects: List<ProjectObject>,
    selectedObjectId: String?,
    outcome: SliceOutcome?,
    onObjectSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(modifier) {
        Text(stringResource(R.string.tab_project), fontWeight = FontWeight.Bold)
        Text(
            if (objects.isEmpty()) stringResource(R.string.no_model)
            else stringResource(R.string.object_count, objects.size),
            color = Color(0xFFC8C9C2),
        )
        objects.forEach { projectObject ->
            val selected = projectObject.id == selectedObjectId
            Surface(
                onClick = { onObjectSelected(projectObject.id) },
                color = if (selected) WorkspaceYellow.copy(alpha = 0.18f) else Color.Transparent,
                contentColor = if (selected) WorkspaceYellow else Color(0xFFE2E3DD),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    projectObject.model.fileName,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            if (outcome == null) stringResource(R.string.no_gcode) else stringResource(R.string.gcode_ready),
            color = if (outcome == null) Color(0xFFC8C9C2) else WorkspaceYellow,
        )
    }
}

@Composable
private fun WorkspaceCard(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .padding(12.dp)
            .fillMaxWidth()
            .widthIn(max = 620.dp),
        colors = CardDefaults.cardColors(
            containerColor = WorkspacePanel,
            contentColor = Color(0xFFF4F4EE),
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = WorkspaceYellow,
    contentColor = WorkspaceBlack,
)

@Composable
internal fun duckySliderColors() = SliderDefaults.colors(
    thumbColor = WorkspaceYellow,
    activeTrackColor = WorkspaceYellow,
    inactiveTrackColor = Color(0xFF555950),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

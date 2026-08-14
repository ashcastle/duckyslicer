package com.ashcastle.duckyslicer

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val WorkspaceYellow = Color(0xFFF6C945)
private val WorkspaceBlack = Color(0xFF202124)
private val WorkspacePanel = Color(0xEE2A2A27)
private val FilamentSlotColors = listOf(
    WorkspaceYellow,
    Color(0xFF44D7FF),
    Color(0xFFFF62D0),
    Color(0xFF5EE6A8),
    Color(0xFFFF6B6B),
    Color(0xFFA78BFA),
    Color(0xFFFF9F43),
    Color(0xFFE7E7E2),
)
private const val PreviewDepthBands = 12
private const val TabletShortestSideDp = 600f
private const val CompactNavigationLabelFontScale = 1.5f
private const val WorkspaceTopOverlayClearanceDp = 142f
private const val ModelFaceDepthBands = 32
private const val ModelFaceShadeBands = 8
// Keep broad curves smooth in the mobile preview while retaining structural creases.
private const val ModelSharpEdgeCosine = 0.422618f
private const val PREPARE_PICKING_PREWARM_DELAY_MS = 180L

internal fun useWorkspaceNavigationRail(widthDp: Float, heightDp: Float): Boolean =
    minOf(widthDp, heightDp) >= TabletShortestSideDp

internal fun showWorkspaceNavigationLabels(fontScale: Float): Boolean =
    fontScale < CompactNavigationLabelFontScale

internal fun workspacePanelMaxHeightDp(availableHeightDp: Float): Float =
    (availableHeightDp - WorkspaceTopOverlayClearanceDp).coerceAtLeast(1f)

internal fun workspaceEditingBusy(
    autoLaying: Boolean,
    arranging: Boolean,
    slicing: Boolean,
    previewLoading: Boolean,
): Boolean = autoLaying || arranging || slicing || previewLoading

internal data class WorkspaceCameraGesture(
    val pan: Offset,
    val zoom: Float,
)

internal fun anchoredWorkspacePanZoom(
    pan: Offset,
    zoom: Float,
    viewportAnchor: Offset,
    previousCentroid: Offset,
    currentCentroid: Offset,
    zoomChange: Float,
    zoomRange: ClosedFloatingPointRange<Float> = 0.45f..4.5f,
): WorkspaceCameraGesture {
    if (
        !pan.x.isFinite() || !pan.y.isFinite() || !zoom.isFinite() || zoom <= 0f ||
        !previousCentroid.x.isFinite() || !previousCentroid.y.isFinite() ||
        !currentCentroid.x.isFinite() || !currentCentroid.y.isFinite() ||
        !zoomChange.isFinite() || zoomChange <= 0f
    ) {
        return WorkspaceCameraGesture(pan, zoom)
    }
    val nextZoom = (zoom * zoomChange.coerceIn(0.75f, 1.3333f)).coerceIn(zoomRange)
    val appliedScale = nextZoom / zoom
    val previousSceneCenter = viewportAnchor + pan
    val nextSceneCenter = currentCentroid -
        (previousCentroid - previousSceneCenter) * appliedScale
    return WorkspaceCameraGesture(
        pan = nextSceneCenter - viewportAnchor,
        zoom = nextZoom,
    )
}

internal fun workspaceOrbitDelta(
    pointerDelta: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
): Offset {
    if (
        !pointerDelta.x.isFinite() || !pointerDelta.y.isFinite() ||
        viewportWidth <= 0f || viewportHeight <= 0f
    ) {
        return Offset.Zero
    }
    return Offset(
        x = pointerDelta.x / viewportWidth * 150f,
        y = pointerDelta.y / viewportHeight * 110f,
    )
}

internal fun shouldUseDepthTestedPreview(
    renderingMode: PreviewRenderingMode,
    deviceSupported: Boolean,
    runtimeAvailable: Boolean,
): Boolean = renderingMode == PreviewRenderingMode.DEPTH_TESTED &&
    deviceSupported && runtimeAvailable

internal fun filamentSlotColor(slot: Int): Color =
    FilamentSlotColors[Math.floorMod(slot, FilamentSlotColors.size)]

internal fun projectVolumeColor(role: ProjectVolumeRole, filamentSlot: Int): Color = when (role) {
    ProjectVolumeRole.MODEL_PART -> filamentSlotColor(filamentSlot)
    ProjectVolumeRole.NEGATIVE_VOLUME -> Color(0xFFFF7043)
    ProjectVolumeRole.PARAMETER_MODIFIER -> Color(0xFF42C6D7)
    ProjectVolumeRole.SUPPORT_BLOCKER -> Color(0xFFEF5350)
    ProjectVolumeRole.SUPPORT_ENFORCER -> Color(0xFF66BB6A)
}

private enum class SupportPaintTool(val state: SupportPaintState?, val label: Int) {
    ENFORCE(SupportPaintState.ENFORCE, R.string.support_enforce),
    BLOCK(SupportPaintState.BLOCK, R.string.support_block),
    ERASE(null, R.string.support_erase),
}

private enum class SeamPaintTool(val state: SeamPaintState?, val label: Int) {
    ENFORCE(SeamPaintState.ENFORCE, R.string.seam_enforce),
    BLOCK(SeamPaintState.BLOCK, R.string.seam_block),
    ERASE(null, R.string.seam_erase),
}

internal data class ModelScreenTriangle(
    val sourceFacetIndex: Int,
    val a: Offset,
    val b: Offset,
    val c: Offset,
    val depth: Float,
    val previewTriangleIndex: Int = sourceFacetIndex,
    val surfaceShade: Float = 1f,
    val volumeId: String = "",
    val filamentSlot: Int = 0,
    val volumeRole: ProjectVolumeRole = ProjectVolumeRole.MODEL_PART,
)

private data class ModelFaceBucket(
    val depthBand: Int,
    val filamentSlot: Int,
    val shadeBand: Int,
    val volumeRole: ProjectVolumeRole,
)

internal data class ModelMeshEdge(
    val triangleIndex: Int,
    val startVertex: Int,
    val endVertex: Int,
    val adjacentTriangleIndex: Int?,
    val sharp: Boolean,
)

private data class MeshVertexKey(val x: Int, val y: Int, val z: Int)

private data class MeshEdgeKey(val first: MeshVertexKey, val second: MeshVertexKey)

private data class MeshEdgeAccumulator(
    val triangleIndex: Int,
    val startVertex: Int,
    val endVertex: Int,
    val normal: FloatArray,
    var adjacentTriangleIndex: Int? = null,
    var sharp: Boolean = false,
    var faceCount: Int = 1,
)

internal fun buildModelMeshEdges(previewTriangles: FloatArray): List<ModelMeshEdge> {
    require(previewTriangles.size % 9 == 0)
    val edgeMap = LinkedHashMap<MeshEdgeKey, MeshEdgeAccumulator>()
    repeat(previewTriangles.size / 9) { triangleIndex ->
        val triangleOffset = triangleIndex * 9
        val normal = previewTriangleNormal(previewTriangles, triangleOffset)
        arrayOf(0 to 1, 1 to 2, 2 to 0).forEach { (startVertex, endVertex) ->
            val startKey = previewVertexKey(previewTriangles, triangleOffset + startVertex * 3)
            val endKey = previewVertexKey(previewTriangles, triangleOffset + endVertex * 3)
            val key = if (startKey.isBefore(endKey)) {
                MeshEdgeKey(startKey, endKey)
            } else {
                MeshEdgeKey(endKey, startKey)
            }
            val existing = edgeMap[key]
            if (existing == null) {
                edgeMap[key] = MeshEdgeAccumulator(
                    triangleIndex = triangleIndex,
                    startVertex = startVertex,
                    endVertex = endVertex,
                    normal = normal,
                )
            } else {
                existing.faceCount += 1
                if (existing.adjacentTriangleIndex == null) {
                    existing.adjacentTriangleIndex = triangleIndex
                }
                val normalDot = abs(
                    existing.normal[0] * normal[0] +
                        existing.normal[1] * normal[1] +
                        existing.normal[2] * normal[2],
                )
                if (!normalDot.isFinite() || normalDot < ModelSharpEdgeCosine || existing.faceCount > 2) {
                    existing.sharp = true
                }
            }
        }
    }
    return edgeMap.values.map { edge ->
        ModelMeshEdge(
            triangleIndex = edge.triangleIndex,
            startVertex = edge.startVertex,
            endVertex = edge.endVertex,
            adjacentTriangleIndex = edge.adjacentTriangleIndex,
            sharp = edge.sharp,
        )
    }
}

private fun previewVertexKey(values: FloatArray, offset: Int): MeshVertexKey = MeshVertexKey(
    x = values[offset].normalizedFloatBits(),
    y = values[offset + 1].normalizedFloatBits(),
    z = values[offset + 2].normalizedFloatBits(),
)

private fun Float.normalizedFloatBits(): Int = if (this == 0f) 0 else toRawBits()

private fun MeshVertexKey.isBefore(other: MeshVertexKey): Boolean =
    x < other.x || x == other.x && (y < other.y || y == other.y && z <= other.z)

private fun previewTriangleNormal(values: FloatArray, offset: Int): FloatArray {
    val ux = values[offset + 3] - values[offset]
    val uy = values[offset + 4] - values[offset + 1]
    val uz = values[offset + 5] - values[offset + 2]
    val vx = values[offset + 6] - values[offset]
    val vy = values[offset + 7] - values[offset + 1]
    val vz = values[offset + 8] - values[offset + 2]
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    if (!length.isFinite() || length <= 0.000001f) return floatArrayOf(0f, 0f, 0f)
    return floatArrayOf(nx / length, ny / length, nz / length)
}

internal data class ModelPoint3(
    val x: Float,
    val y: Float,
    val z: Float,
)

internal data class ModelMeasurement(
    val distanceMm: Float,
    val deltaXmm: Float,
    val deltaYmm: Float,
    val deltaZmm: Float,
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
internal fun WorkspaceScreen(
    selectedTab: WorkspaceTab,
    projectPlates: List<ProjectPlate>,
    selectedPlateId: String,
    projectObjects: List<ProjectObject>,
    selectedObjectId: String?,
    sliceOptions: SliceOptions,
    profileCatalog: ProfileCatalog,
    profileRecents: ProfileRecents,
    appSettings: AppSettings,
    remoteDevices: List<RemoteDeviceProfile>,
    selectedRemoteDeviceId: String?,
    remoteStatus: RemoteDeviceStatus?,
    remoteUpload: RemoteUpload?,
    remoteBusy: Boolean,
    remoteUploadProgress: Int?,
    remoteRequestActive: Boolean,
    remoteUploadActive: Boolean,
    remoteRequestCancellationRequested: Boolean,
    remoteMessage: String?,
    remoteMessageIsError: Boolean,
    profileBusy: Boolean,
    profileTransferDirection: ProfileTransferDirection?,
    profileTransferCancellationRequested: Boolean,
    appSettingsSaveFailed: Boolean,
    supportReportExportState: SupportReportExportState,
    sliceOutcome: SliceOutcome?,
    layerPreview: GcodeLayerPreview?,
    importing: Boolean,
    autoLaying: Boolean,
    arranging: Boolean,
    splitting: Boolean,
    cutting: Boolean,
    simplifying: Boolean,
    projectEditActive: Boolean,
    projectEditCancellationRequested: Boolean,
    projectImporting: Boolean,
    projectExporting: Boolean,
    projectTransferCancellationRequested: Boolean,
    slicing: Boolean,
    sliceCancellationRequested: Boolean,
    sliceProgress: Int,
    previewLoading: Boolean,
    exportingGcode: Boolean,
    gcodeExportCancellationRequested: Boolean,
    error: String?,
    notice: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    onTabSelected: (WorkspaceTab) -> Unit,
    onChoose: () -> Unit,
    onImportProfiles: () -> Unit,
    onExportProfiles: () -> Unit,
    onCancelProfileTransfer: () -> Unit,
    onCreatePrimitive: (OrcaPrimitive, Float) -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onPlateSelected: (String) -> Unit,
    onAddPlate: () -> Unit,
    onRemovePlate: () -> Unit,
    onObjectSelected: (String?) -> Unit,
    onModelTransformChanged: (ModelTransform) -> Unit,
    onModelTransformPreview: (ModelTransform) -> Unit,
    onModelTransformCommitted: (ModelTransform) -> Unit,
    onObjectFilamentSelected: (FilamentProfile) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDuplicate: () -> Unit,
    onArrange: () -> Unit,
    onAutoLay: () -> Unit,
    onLayOnFace: (String, FloatArray) -> Unit,
    onSplit: () -> Unit,
    onSplitParts: (String) -> Unit,
    onCut: (Float, Boolean) -> Unit,
    onSimplify: (Int) -> Unit,
    onCancelProjectEdit: () -> Unit,
    onCancelProjectImport: () -> Unit,
    onCancelProjectExport: () -> Unit,
    onSupportPaintPreview: (String, String, Int, SupportPaintState?) -> Unit,
    onSupportPaintCommitted: (String, String, SupportPaint) -> Unit,
    onSeamPaintPreview: (String, String, Int, SeamPaintState?) -> Unit,
    onSeamPaintCommitted: (String, String, SeamPaint) -> Unit,
    onBrimPointsChanged: (String, BrimPoints) -> Unit,
    onMultiColorPaintPreview: (String, String, Int, Int?) -> Unit,
    onMultiColorPaintCommitted: (String, String, MultiColorPaint) -> Unit,
    onVariableLayerHeightsChanged: (VariableLayerHeights) -> Unit,
    onObjectProcessOverridesChanged: (ObjectProcessOverrides) -> Unit,
    onRemoveModel: () -> Unit,
    onSlice: () -> Unit,
    onCancelSlice: () -> Unit,
    onSave: () -> Unit,
    onCancelGcodeExport: () -> Unit,
    onSliceOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinterProfile: (String, SliceOptions) -> Unit,
    onSaveFilamentProfile: (String, SliceOptions, Int) -> Unit,
    onSaveSlicingProfile: (String, SliceOptions) -> Unit,
    onLayerRangeSelected: (Int, Int) -> Unit,
    onAppSettingsChanged: (AppSettings) -> Unit,
    onSupportReportExport: (Uri) -> Unit,
    onCancelSupportReportExport: () -> Unit,
    onRemoteDeviceSelected: (String) -> Unit,
    onRemoteDeviceSaved: (RemoteDeviceDraft) -> Unit,
    onRemoteDeviceDeleted: (String) -> Unit,
    onRemoteRefresh: () -> Unit,
    onRemoteUpload: () -> Unit,
    onRemoteCancelRequest: () -> Unit,
    onRemoteStart: () -> Unit,
    onRemotePause: () -> Unit,
    onRemoteResume: () -> Unit,
    onRemoteCancel: () -> Unit,
) = BoxWithConstraints {
    val selectedObject = projectObjects.firstOrNull { it.id == selectedObjectId }
    val stringResourceBrimPlacementHint = stringResource(R.string.brim_point_invalid)
    val selectedSingleVolume = selectedObject?.singleVolumeOrNull
    val availableFilaments = sliceOptions.resolvedFilamentSlots()
    val modelDimensions = (selectedObject ?: projectObjects.firstOrNull())?.geometry()?.let {
        listOf(it.maxX - it.minX, it.maxY - it.minY, it.maxZ - it.minZ)
    }
    val modelTransform = selectedObject?.transform ?: ModelTransform()
    val editingBusy = workspaceEditingBusy(autoLaying, arranging, slicing, previewLoading) ||
        splitting || cutting || simplifying
    val tabletLayout = useWorkspaceNavigationRail(maxWidth.value, maxHeight.value)
    val panelAlignment = if (tabletLayout) Alignment.BottomEnd else Alignment.BottomCenter
    var showModelTools by remember { mutableStateOf(false) }
    var showFilamentPicker by remember { mutableStateOf(false) }
    var showCutTool by remember { mutableStateOf(false) }
    var showSimplifyTool by remember { mutableStateOf(false) }
    var showSplitPartsTool by remember { mutableStateOf(false) }
    var showVariableLayerHeightTool by remember { mutableStateOf(false) }
    var showObjectProcessSettings by remember { mutableStateOf(false) }
    var showPrimitivePicker by remember { mutableStateOf(false) }
    var layingOnFace by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var measurementPoints by remember { mutableStateOf<List<ModelPoint3>>(emptyList()) }
    var supportPainting by remember { mutableStateOf(false) }
    var supportPaintTool by remember { mutableStateOf(SupportPaintTool.ENFORCE) }
    var seamPainting by remember { mutableStateOf(false) }
    var seamPaintTool by remember { mutableStateOf(SeamPaintTool.ENFORCE) }
    var multiColorPainting by remember { mutableStateOf(false) }
    var multiColorPaintSlot by remember { mutableStateOf<Int?>(1) }
    var brimEditing by remember { mutableStateOf(false) }
    var brimDraft by remember { mutableStateOf(BrimPoints()) }
    var selectedBrimPointIndex by remember { mutableStateOf<Int?>(null) }
    var brimAddMode by remember { mutableStateOf(true) }
    var brimEditMessage by remember { mutableStateOf<String?>(null) }
    var visibleToolpathRoles by remember { mutableStateOf(ToolpathStyles.indices.toSet()) }
    var previewControlsExpanded by rememberSaveable { mutableStateOf(false) }
    var plateRemovalRequested by remember { mutableStateOf(false) }
    val statusHostState = remember { SnackbarHostState() }
    val plateActionsEnabled = !importing && !editingBusy && !projectImporting &&
        !projectExporting && !slicing && !previewLoading && !exportingGcode && !remoteBusy

    LaunchedEffect(error, notice) {
        val message = error ?: notice ?: return@LaunchedEffect
        statusHostState.showSnackbar(
            message = message,
            duration = if (error != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
    }

    fun beginBrimEditing(projectObject: ProjectObject) {
        showModelTools = false
        layingOnFace = false
        measuring = false
        measurementPoints = emptyList()
        supportPainting = false
        seamPainting = false
        multiColorPainting = false
        brimDraft = projectObject.brimPoints
        selectedBrimPointIndex = projectObject.brimPoints.points.lastIndex.takeIf { it >= 0 }
        brimAddMode = projectObject.brimPoints.points.isEmpty()
        brimEditMessage = null
        brimEditing = true
    }

    LaunchedEffect(selectedPlateId, selectedObjectId, selectedTab) {
        plateRemovalRequested = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) layingOnFace = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) {
            measuring = false
            measurementPoints = emptyList()
        }
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) supportPainting = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) seamPainting = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) multiColorPainting = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) brimEditing = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) showFilamentPicker = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) showCutTool = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) showSimplifyTool = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) showSplitPartsTool = false
        if (selectedObjectId == null || selectedTab != WorkspaceTab.SLICE) {
            showVariableLayerHeightTool = false
            showObjectProcessSettings = false
        }
    }
    LaunchedEffect(selectedTab) {
        if (selectedTab != WorkspaceTab.SLICE) showPrimitivePicker = false
    }
    LaunchedEffect(availableFilaments.size) {
        if (availableFilaments.size < 2) {
            multiColorPainting = false
        }
        if (multiColorPaintSlot != null && multiColorPaintSlot !in availableFilaments.indices) {
            multiColorPaintSlot = availableFilaments.indices.lastOrNull()
        }
    }
    Scaffold(
        containerColor = Color(0xFF191A18),
        snackbarHost = { SnackbarHost(statusHostState) },
        bottomBar = {
            if (!tabletLayout) WorkspaceNavigation(selectedTab = selectedTab, onSelected = onTabSelected)
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (tabletLayout) WorkspaceNavigationRail(selectedTab = selectedTab, onSelected = onTabSelected)
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) {
                // Measure below Scaffold insets and navigation. This keeps the menu and
                // export actions reachable when large text makes a bottom sheet fill its height.
                val panelMaxHeight = workspacePanelMaxHeightDp(maxHeight.value).dp
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
                        !importing && !editingBusy && !slicing && !previewLoading &&
                        !layingOnFace && !measuring && !brimEditing,
                    layOnFaceObjectId = selectedObjectId.takeIf { layingOnFace },
                    measureObjectId = selectedObjectId.takeIf { measuring },
                    measurementPoints = measurementPoints,
                    supportPaintObjectId = selectedObjectId.takeIf { supportPainting },
                    supportPaintState = supportPaintTool.state,
                    seamPaintObjectId = selectedObjectId.takeIf { seamPainting },
                    seamPaintState = seamPaintTool.state,
                    multiColorPaintObjectId = selectedObjectId.takeIf { multiColorPainting },
                    multiColorPaintSlot = multiColorPaintSlot,
                    brimEditObjectId = selectedObjectId.takeIf { brimEditing },
                    brimPoints = brimDraft,
                    selectedBrimPointIndex = selectedBrimPointIndex,
                    brimAddMode = brimAddMode,
                    onObjectSelected = onObjectSelected,
                    onModelTransformPreview = onModelTransformPreview,
                    onModelTransformCommitted = onModelTransformCommitted,
                    onLayOnFace = { objectId, triangle ->
                        layingOnFace = false
                        onLayOnFace(objectId, triangle)
                    },
                    onMeasurePoint = { point ->
                        measurementPoints = nextMeasurementPoints(measurementPoints, point)
                    },
                    onSupportPaintPreview = onSupportPaintPreview,
                    onSupportPaintCommitted = onSupportPaintCommitted,
                    onSeamPaintPreview = onSeamPaintPreview,
                    onSeamPaintCommitted = onSeamPaintCommitted,
                    onBrimPointSelected = { selectedBrimPointIndex = it },
                    onBrimPointAdded = { point ->
                        if (brimDraft.points.size < BrimPoints.MAX_POINTS) {
                            brimDraft = BrimPoints(brimDraft.points + point)
                            selectedBrimPointIndex = brimDraft.points.lastIndex
                            brimAddMode = false
                            brimEditMessage = null
                        }
                    },
                    onBrimPointMoved = { index, point ->
                        if (index in brimDraft.points.indices) {
                            brimDraft = BrimPoints(
                                brimDraft.points.toMutableList().apply { this[index] = point },
                            )
                            brimEditMessage = null
                        }
                    },
                    onBrimPointInvalid = {
                        brimEditMessage = stringResourceBrimPlacementHint
                    },
                    onMultiColorPaintPreview = onMultiColorPaintPreview,
                    onMultiColorPaintCommitted = onMultiColorPaintCommitted,
                    modifier = Modifier.fillMaxSize(),
                )

            WorkspaceMenu(
                importing = importing,
                editingBusy = editingBusy,
                profileBusy = profileBusy,
                profileTransferDirection = profileTransferDirection,
                profileTransferCancellationRequested = profileTransferCancellationRequested,
                projectEditActive = projectEditActive,
                cancellationRequested = projectEditCancellationRequested,
                projectImporting = projectImporting,
                projectExporting = projectExporting,
                projectTransferCancellationRequested = projectTransferCancellationRequested,
                slicing = slicing,
                previewLoading = previewLoading,
                canExport = sliceOutcome != null && !exportingGcode,
                exportingGcode = exportingGcode,
                gcodeExportCancellationRequested = gcodeExportCancellationRequested,
                onImport = onChoose,
                onImportProfiles = onImportProfiles,
                onExportProfiles = onExportProfiles,
                onCancelProfileTransfer = onCancelProfileTransfer,
                onAddShape = { showPrimitivePicker = true },
                onExport = onSave,
                onCancelGcodeExport = onCancelGcodeExport,
                canArrange = projectObjects.size > 1 &&
                    projectObjects.sumOf { it.volumes.size } <= ProjectStore.MAX_PROJECT_VOLUMES,
                onArrange = onArrange,
                onCancelProjectEdit = onCancelProjectEdit,
                onCancelProjectImport = onCancelProjectImport,
                onCancelProjectExport = onCancelProjectExport,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )

            if (
                selectedTab == WorkspaceTab.SLICE || selectedTab == WorkspaceTab.PREVIEW ||
                selectedTab == WorkspaceTab.PROJECT
            ) {
                PlateSwitcher(
                    plates = projectPlates,
                    selectedPlateId = selectedPlateId,
                    enabled = plateActionsEnabled,
                    onSelected = onPlateSelected,
                    onAdd = onAddPlate,
                    onRemove = { plateRemovalRequested = true },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                )
            }

            if (
                selectedObject != null && selectedTab == WorkspaceTab.SLICE &&
                !layingOnFace && !measuring && !supportPainting && !seamPainting &&
                !multiColorPainting && !brimEditing
            ) {
                ObjectToolRail(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = onUndo,
                    onRedo = onRedo,
                    onDuplicate = onDuplicate,
                    onAutoLay = onAutoLay,
                    canAutoLay = true,
                    onLayOnFace = {
                        measuring = false
                        measurementPoints = emptyList()
                        supportPainting = false
                        seamPainting = false
                        multiColorPainting = false
                        brimEditing = false
                        layingOnFace = true
                    },
                    onMeasure = {
                        layingOnFace = false
                        supportPainting = false
                        seamPainting = false
                        multiColorPainting = false
                        brimEditing = false
                        measurementPoints = emptyList()
                        measuring = true
                    },
                    onBrimEars = { beginBrimEditing(selectedObject) },
                    autoLaying = autoLaying,
                    editingBusy = editingBusy,
                    canPaintColor = availableFilaments.size > 1,
                    onMultiColorPaint = {
                        supportPainting = false
                        seamPainting = false
                        multiColorPaintSlot = multiColorPaintSlot
                            ?.takeIf { it in availableFilaments.indices }
                            ?: availableFilaments.indices.last()
                        multiColorPainting = true
                        brimEditing = false
                    },
                    onSupportPaint = {
                        seamPainting = false
                        multiColorPainting = false
                        brimEditing = false
                        supportPainting = true
                    },
                    onMore = { showModelTools = true },
                    onRemove = {
                        onRemoveModel()
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && layingOnFace) {
                LayOnFacePalette(
                    onDone = { layingOnFace = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && measuring) {
                MeasurePalette(
                    points = measurementPoints,
                    onClear = { measurementPoints = emptyList() },
                    onDone = {
                        measuring = false
                        measurementPoints = emptyList()
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && supportPainting) {
                SupportPaintPalette(
                    selectedTool = supportPaintTool,
                    onToolSelected = { supportPaintTool = it },
                    onDone = { supportPainting = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && seamPainting) {
                SeamPaintPalette(
                    selectedTool = seamPaintTool,
                    onToolSelected = { seamPaintTool = it },
                    onDone = { seamPainting = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && multiColorPainting) {
                MultiColorPaintPalette(
                    filaments = availableFilaments,
                    selectedSlot = multiColorPaintSlot,
                    onSlotSelected = { multiColorPaintSlot = it },
                    onDone = { multiColorPainting = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedObject != null && selectedTab == WorkspaceTab.SLICE && brimEditing) {
                BrimEarPalette(
                    projectObject = selectedObject,
                    points = brimDraft,
                    selectedIndex = selectedBrimPointIndex,
                    addMode = brimAddMode,
                    message = brimEditMessage,
                    bedSizeX = sliceOptions.bedSizeX,
                    bedSizeY = sliceOptions.bedSizeY,
                    bedPolygon = sliceOptions.bedPolygon,
                    onAddModeChanged = {
                        brimAddMode = it
                        brimEditMessage = null
                    },
                    onPointSelected = { selectedBrimPointIndex = it },
                    onPointsChanged = { points, selected ->
                        brimDraft = points
                        selectedBrimPointIndex = selected
                        brimEditMessage = null
                    },
                    onInvalid = { brimEditMessage = stringResourceBrimPlacementHint },
                    onCancel = {
                        brimEditing = false
                        brimEditMessage = null
                    },
                    onApply = {
                        onBrimPointsChanged(selectedObject.id, brimDraft)
                        brimEditing = false
                        brimEditMessage = null
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 132.dp),
                )
            }

            if (selectedTab == WorkspaceTab.PREVIEW) {
                PreviewExportSplitButton(
                    canExport = sliceOutcome != null && !exportingGcode,
                    exporting = exportingGcode,
                    cancellationRequested = gcodeExportCancellationRequested,
                    canSend = sliceOutcome != null && selectedRemoteDeviceId != null && !remoteBusy,
                    onExport = onSave,
                    onCancelExport = onCancelGcodeExport,
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
                        text = selectedObject?.volumes?.firstOrNull()?.model?.fileName
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
                    modelDimensions = modelDimensions,
                    options = sliceOptions,
                    catalog = profileCatalog,
                    recents = profileRecents,
                    profileBusy = profileBusy,
                    importing = importing || editingBusy,
                    projectEditActive = projectEditActive,
                    projectEditCancellationRequested = projectEditCancellationRequested,
                    previewLoading = previewLoading,
                    slicing = slicing,
                    cancellationRequested = sliceCancellationRequested,
                    progress = sliceProgress,
                    error = error,
                    notice = notice,
                    onSlice = onSlice,
                    onCancelSlice = onCancelSlice,
                    onCancelProjectEdit = onCancelProjectEdit,
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
                    expanded = previewControlsExpanded,
                    onExpandedChanged = { previewControlsExpanded = it },
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
                    requestActive = remoteRequestActive,
                    uploadActive = remoteUploadActive,
                    requestCancellationRequested = remoteRequestCancellationRequested,
                    message = remoteMessage,
                    isError = remoteMessageIsError,
                    confirmBeforePrint = appSettings.confirmBeforeRemotePrint,
                    onSelect = onRemoteDeviceSelected,
                    onSave = onRemoteDeviceSaved,
                    onDelete = onRemoteDeviceDeleted,
                    onRefresh = onRemoteRefresh,
                    onUpload = onRemoteUpload,
                    onCancelRequest = onRemoteCancelRequest,
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
                    busy = importing || editingBusy,
                    importing = projectImporting,
                    exporting = projectExporting,
                    cancellationRequested = projectTransferCancellationRequested,
                    onObjectSelected = onObjectSelected,
                    onOpenProject = onOpenProject,
                    onSaveProject = onSaveProject,
                    onCancelProjectImport = onCancelProjectImport,
                    onCancelProjectExport = onCancelProjectExport,
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )

                WorkspaceTab.SETTINGS -> AppSettingsSheet(
                    settings = appSettings,
                    saveFailed = appSettingsSaveFailed,
                    supportReportExportState = supportReportExportState,
                    onSettingsChanged = onAppSettingsChanged,
                    onSupportReportExport = onSupportReportExport,
                    onCancelSupportReportExport = onCancelSupportReportExport,
                    modifier = Modifier.align(panelAlignment).heightIn(max = panelMaxHeight),
                )
            }
        }
    }
    if (plateRemovalRequested) {
        AlertDialog(
            onDismissRequest = { plateRemovalRequested = false },
            title = { Text(stringResource(R.string.remove_plate_title)) },
            text = { Text(stringResource(R.string.remove_plate_message)) },
            dismissButton = {
                TextButton(onClick = { plateRemovalRequested = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        plateRemovalRequested = false
                        onRemovePlate()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD9534F),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(stringResource(R.string.remove_plate))
                }
            },
        )
    }
    }
    if (showModelTools && selectedObject != null) {
        val filamentSlots = sliceOptions.resolvedFilamentSlots()
        val selectedFilamentSlot = selectedObject.primaryModelPart.filamentSlot
        ModelTransformSheet(
            transform = modelTransform,
            filamentSlot = selectedFilamentSlot,
            filamentProfile = filamentSlots.getOrElse(selectedFilamentSlot) {
                filamentSlots.first()
            },
            bedSizeX = sliceOptions.bedSizeX,
            bedSizeY = sliceOptions.bedSizeY,
            maxPrintHeight = sliceOptions.maxPrintHeight,
            bedPolygon = sliceOptions.bedPolygon,
            autoLaying = autoLaying,
            splitting = splitting,
            cutting = cutting,
            simplifying = simplifying,
            triangleCount = selectedObject.modelPartVolumes.sumOf { it.model.triangles },
            canAutoLay = true,
            canEditSingleVolumeModel = selectedSingleVolume != null,
            onAutoLay = onAutoLay,
            onLayOnFace = {
                showModelTools = false
                measuring = false
                measurementPoints = emptyList()
                supportPainting = false
                seamPainting = false
                multiColorPainting = false
                layingOnFace = true
            },
            onMeasure = {
                showModelTools = false
                layingOnFace = false
                supportPainting = false
                seamPainting = false
                multiColorPainting = false
                measurementPoints = emptyList()
                measuring = true
            },
            onSplit = {
                showModelTools = false
                onSplit()
            },
            onSplitParts = {
                showModelTools = false
                showSplitPartsTool = true
            },
            onCut = {
                showModelTools = false
                showCutTool = true
            },
            onSimplify = {
                showModelTools = false
                showSimplifyTool = true
            },
            onSeamPaint = {
                showModelTools = false
                supportPainting = false
                multiColorPainting = false
                seamPainting = true
            },
            onBrimEars = {
                beginBrimEditing(selectedObject)
            },
            onVariableLayerHeight = {
                showModelTools = false
                showVariableLayerHeightTool = true
            },
            onObjectSettings = {
                showModelTools = false
                showObjectProcessSettings = true
            },
            onChooseFilament = {
                showModelTools = false
                showFilamentPicker = true
            },
            onTransformChanged = onModelTransformChanged,
            onTransformPreview = onModelTransformPreview,
            onTransformCommitted = onModelTransformCommitted,
            onRemoveModel = {
                showModelTools = false
                onRemoveModel()
            },
            onDismiss = { showModelTools = false },
        )
    }
    if (showFilamentPicker && selectedObject != null) {
        FilamentAssignmentSheet(
            selectedSlot = selectedObject.primaryModelPart.filamentSlot,
            options = sliceOptions,
            catalog = profileCatalog,
            recentIds = profileRecents.filamentIds,
            onSelected = {
                showFilamentPicker = false
                onObjectFilamentSelected(it)
            },
            onDismiss = { showFilamentPicker = false },
        )
    }
    if (showCutTool && selectedSingleVolume != null) {
        CutObjectSheet(
            modelHeightMm = selectedSingleVolume.model.dimensions[2].toFloat(),
            onCut = { heightRatio, placeOnCut ->
                showCutTool = false
                onCut(heightRatio, placeOnCut)
            },
            onDismiss = { showCutTool = false },
        )
    }
    if (showSimplifyTool && selectedSingleVolume != null) {
        SimplifyModelSheet(
            originalTriangleCount = selectedSingleVolume.model.triangles,
            hasSurfacePaint = selectedSingleVolume.supportPaint.facets.isNotEmpty() ||
                selectedSingleVolume.seamPaint.facets.isNotEmpty() ||
                selectedSingleVolume.multiColorPaint.facets.isNotEmpty(),
            onApply = { keepPercent ->
                showSimplifyTool = false
                onSimplify(keepPercent)
            },
            onDismiss = { showSimplifyTool = false },
        )
    }
    if (showSplitPartsTool && selectedObject != null) {
        SplitPartsSheet(
            projectObject = selectedObject,
            onApply = { volumeId ->
                showSplitPartsTool = false
                onSplitParts(volumeId)
            },
            onDismiss = { showSplitPartsTool = false },
        )
    }
    if (showVariableLayerHeightTool && selectedObject != null) {
        VariableLayerHeightSheet(
            current = selectedObject.variableLayerHeights,
            baseLayerHeightMm = sliceOptions.layerHeight,
            nozzleDiameterMm = sliceOptions.nozzleDiameter,
            onApply = {
                showVariableLayerHeightTool = false
                onVariableLayerHeightsChanged(it)
            },
            onDismiss = { showVariableLayerHeightTool = false },
        )
    }
    if (showObjectProcessSettings && selectedObject != null) {
        ObjectProcessSettingsSheet(
            current = selectedObject.processOverrides,
            options = sliceOptions,
            onApply = {
                showObjectProcessSettings = false
                onObjectProcessOverridesChanged(it)
            },
            onDismiss = { showObjectProcessSettings = false },
        )
    }
    if (showPrimitivePicker) {
        BasicShapeSheet(
            bedSizeX = sliceOptions.bedSizeX,
            bedSizeY = sliceOptions.bedSizeY,
            onAdd = { primitive, sizeMm ->
                showPrimitivePicker = false
                onCreatePrimitive(primitive, sizeMm)
            },
            onDismiss = { showPrimitivePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BasicShapeSheet(
    bedSizeX: Float,
    bedSizeY: Float,
    onAdd: (OrcaPrimitive, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(OrcaPrimitive.CUBE) }
    val initialSize = (minOf(bedSizeX, bedSizeY) * 0.1f).coerceIn(10f, 40f)
    var sizeMm by rememberSaveable(bedSizeX, bedSizeY) { mutableFloatStateOf(initialSize) }
    val shapeSizeLabel = stringResource(R.string.shape_size)
    val shapeSizeValue = stringResource(R.string.millimeters_value, sizeMm)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.add_shape),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.add_shape_hint),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodyMedium,
            )
            OrcaPrimitive.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { primitive ->
                        Button(
                            onClick = { selected = primitive },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { this.selected = selected == primitive },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected == primitive) {
                                    WorkspaceYellow
                                } else {
                                    Color(0xFF3A3B37)
                                },
                                contentColor = if (selected == primitive) {
                                    WorkspaceBlack
                                } else {
                                    Color(0xFFF4F4EE)
                                },
                            ),
                        ) {
                            Text(
                                stringResource(primitive.label),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(shapeSizeLabel, fontWeight = FontWeight.SemiBold)
                Text(shapeSizeValue, color = WorkspaceYellow)
            }
            Slider(
                value = sizeMm,
                onValueChange = { sizeMm = it },
                valueRange = MIN_PRIMITIVE_SIZE_MM..MAX_PRIMITIVE_SIZE_MM,
                steps = 38,
                modifier = Modifier.semantics {
                    contentDescription = "$shapeSizeLabel $shapeSizeValue"
                },
                colors = duckySliderColors(),
            )
            Button(
                onClick = { onAdd(selected, sizeMm) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WorkspaceYellow,
                    contentColor = WorkspaceBlack,
                ),
            ) {
                Text(stringResource(R.string.add_shape))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimplifyModelSheet(
    originalTriangleCount: Int,
    hasSurfacePaint: Boolean,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    require(originalTriangleCount >= MINIMUM_SIMPLIFIABLE_TRIANGLES)
    var keepPercent by rememberSaveable(originalTriangleCount) {
        mutableFloatStateOf(DEFAULT_SIMPLIFY_KEEP_PERCENT.toFloat())
    }
    val keepPercentInt = keepPercent.roundToInt().coerceIn(
        MINIMUM_SIMPLIFY_KEEP_PERCENT,
        MAXIMUM_SIMPLIFY_KEEP_PERCENT,
    )
    val targetTriangleCount = simplificationTargetTriangleCount(
        originalTriangleCount,
        keepPercentInt,
    )
    val detailLabel = stringResource(R.string.simplify_detail_to_keep)
    val detailValue = stringResource(R.string.percent_value, keepPercentInt)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.simplify_model),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.simplify_model_hint),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(detailLabel, fontWeight = FontWeight.SemiBold)
                Text(detailValue, color = WorkspaceYellow, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = keepPercent,
                onValueChange = { keepPercent = it.roundToInt().toFloat() },
                valueRange = MINIMUM_SIMPLIFY_KEEP_PERCENT.toFloat()..
                    MAXIMUM_SIMPLIFY_KEEP_PERCENT.toFloat(),
                steps = 79,
                modifier = Modifier.semantics {
                    contentDescription = "$detailLabel $detailValue"
                },
                colors = duckySliderColors(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.simplify_current_faces, originalTriangleCount),
                    color = Color(0xFFC8C9C2),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.simplify_expected_faces, targetTriangleCount),
                    color = WorkspaceYellow,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (hasSurfacePaint) {
                Text(
                    stringResource(R.string.simplify_paint_warning),
                    color = Color(0xFFFFC66D),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.3f).heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onApply(keepPercentInt) },
                    modifier = Modifier.weight(0.7f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WorkspaceYellow,
                        contentColor = WorkspaceBlack,
                    ),
                ) {
                    Text(stringResource(R.string.simplify_model))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SplitPartsSheet(
    projectObject: ProjectObject,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val splittableVolumes = projectObject.modelPartVolumes
    var selectedVolumeId by rememberSaveable(
        projectObject.id,
        splittableVolumes.map(ProjectVolume::id),
    ) {
        mutableStateOf(splittableVolumes.first().id)
    }
    val selectedVolume = splittableVolumes.first { it.id == selectedVolumeId }
    val clearsPaint = selectedVolume.supportPaint.facets.isNotEmpty() ||
        selectedVolume.seamPaint.facets.isNotEmpty() ||
        selectedVolume.multiColorPaint.facets.isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.split_parts_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.split_parts_hint),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodyMedium,
            )
            splittableVolumes.forEachIndexed { index, volume ->
                val isSelected = volume.id == selectedVolumeId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { selectedVolumeId = volume.id },
                        ),
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) Color(0xFF4A4430) else Color(0xFF343530),
                    contentColor = if (isSelected) WorkspaceYellow else Color(0xFFF4F4EE),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            volume.model.fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(
                                R.string.split_part_summary,
                                index + 1,
                                volume.model.triangles,
                            ),
                            color = if (isSelected) {
                                WorkspaceYellow.copy(alpha = 0.82f)
                            } else {
                                Color(0xFFB8BAB3)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (clearsPaint) {
                Text(
                    stringResource(R.string.split_parts_paint_warning),
                    color = Color(0xFFFFC66D),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(0.3f).heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onApply(selectedVolumeId) },
                    modifier = Modifier.weight(0.7f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WorkspaceYellow,
                        contentColor = WorkspaceBlack,
                    ),
                ) {
                    Text(stringResource(R.string.split_to_parts))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelTransformSheet(
    transform: ModelTransform,
    filamentSlot: Int,
    filamentProfile: FilamentProfile,
    bedSizeX: Float,
    bedSizeY: Float,
    maxPrintHeight: Float,
    bedPolygon: List<Float>,
    autoLaying: Boolean,
    splitting: Boolean,
    cutting: Boolean,
    simplifying: Boolean,
    triangleCount: Int,
    canAutoLay: Boolean,
    canEditSingleVolumeModel: Boolean,
    onAutoLay: () -> Unit,
    onLayOnFace: () -> Unit,
    onMeasure: () -> Unit,
    onSplit: () -> Unit,
    onSplitParts: () -> Unit,
    onCut: () -> Unit,
    onSimplify: () -> Unit,
    onSeamPaint: () -> Unit,
    onBrimEars: () -> Unit,
    onVariableLayerHeight: () -> Unit,
    onObjectSettings: () -> Unit,
    onChooseFilament: () -> Unit,
    onTransformChanged: (ModelTransform) -> Unit,
    onTransformPreview: (ModelTransform) -> Unit,
    onTransformCommitted: (ModelTransform) -> Unit,
    onRemoveModel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val modelEditBusy = autoLaying || splitting || cutting || simplifying
    val effectiveBedPolygon = bedPolygon.takeIf { bedPolygonIsValid(it, bedSizeX, bedSizeY) }
        ?: rectangularBedPolygon(bedSizeX, bedSizeY)
    val scaleRange = ProjectStore.MIN_SCALE..ProjectStore.MAX_SCALE
    var keepProportions by rememberSaveable { mutableStateOf(transform.hasUniformScale()) }
    var transformGestureStart by remember { mutableStateOf<ModelTransform?>(null) }

    fun previewTransform(next: ModelTransform) {
        if (transformGestureStart == null) transformGestureStart = transform
        onTransformPreview(next)
    }

    fun commitTransformGesture() {
        transformGestureStart?.let(onTransformCommitted)
        transformGestureStart = null
    }

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
        onDismissRequest = {
            commitTransformGesture()
            onDismiss()
        },
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
            Text(
                stringResource(R.string.model_placement),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            TransformSlider(
                label = stringResource(R.string.move_x),
                valueText = stringResource(R.string.millimeters_value, transform.offsetXmm),
                value = transform.offsetXmm,
                range = -bedSizeX / 2f..bedSizeX / 2f,
                enabled = !autoLaying,
                onValueChange = { previewTransform(constrainedTransform(it, transform.offsetYmm)) },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.move_y),
                valueText = stringResource(R.string.millimeters_value, transform.offsetYmm),
                value = transform.offsetYmm,
                range = -bedSizeY / 2f..bedSizeY / 2f,
                enabled = !autoLaying,
                onValueChange = { previewTransform(constrainedTransform(transform.offsetXmm, it)) },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.move_z),
                valueText = stringResource(R.string.millimeters_value, transform.offsetZmm),
                value = transform.offsetZmm,
                range = -maxPrintHeight..maxPrintHeight,
                enabled = !autoLaying,
                onValueChange = { previewTransform(transform.copy(offsetZmm = it)) },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.rotate_x),
                valueText = stringResource(R.string.degrees_value, transform.rotationXdeg),
                value = transform.rotationXdeg,
                range = -180f..180f,
                enabled = !autoLaying,
                onValueChange = { previewTransform(transform.copy(rotationXdeg = it)) },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.rotate_y),
                valueText = stringResource(R.string.degrees_value, transform.rotationYdeg),
                value = transform.rotationYdeg,
                range = -180f..180f,
                enabled = !autoLaying,
                onValueChange = { previewTransform(transform.copy(rotationYdeg = it)) },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.rotate_z),
                valueText = stringResource(R.string.degrees_value, transform.rotationZdeg),
                value = transform.rotationZdeg,
                range = -180f..180f,
                enabled = !autoLaying,
                onValueChange = { previewTransform(transform.copy(rotationZdeg = it)) },
                onValueChangeFinished = ::commitTransformGesture,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = keepProportions,
                        enabled = !autoLaying,
                        role = Role.Switch,
                        onValueChange = { keepProportions = it },
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.keep_proportions), fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = keepProportions,
                    enabled = !autoLaying,
                    onCheckedChange = null,
                )
            }
            TransformSlider(
                label = stringResource(R.string.scale_x),
                valueText = stringResource(R.string.percent_value, (transform.scale * 100).roundToInt()),
                value = transform.scale,
                range = scaleRange,
                steps = 198,
                enabled = !autoLaying,
                onValueChange = {
                    previewTransform(
                        transform.withAxisScale(
                            ModelScaleAxis.X,
                            it,
                            keepProportions,
                            scaleRange,
                        ),
                    )
                },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.scale_y),
                valueText = stringResource(R.string.percent_value, (transform.scaleY * 100).roundToInt()),
                value = transform.scaleY,
                range = scaleRange,
                steps = 198,
                enabled = !autoLaying,
                onValueChange = {
                    previewTransform(
                        transform.withAxisScale(
                            ModelScaleAxis.Y,
                            it,
                            keepProportions,
                            scaleRange,
                        ),
                    )
                },
                onValueChangeFinished = ::commitTransformGesture,
            )
            TransformSlider(
                label = stringResource(R.string.scale_z),
                valueText = stringResource(R.string.percent_value, (transform.scaleZ * 100).roundToInt()),
                value = transform.scaleZ,
                range = scaleRange,
                steps = 198,
                enabled = !autoLaying,
                onValueChange = {
                    previewTransform(
                        transform.withAxisScale(
                            ModelScaleAxis.Z,
                            it,
                            keepProportions,
                            scaleRange,
                        ),
                    )
                },
                onValueChangeFinished = ::commitTransformGesture,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MirrorAxisButton(
                    label = stringResource(R.string.mirror_x),
                    mirrored = transform.mirrorX,
                    enabled = !autoLaying,
                    onClick = { onTransformChanged(transform.copy(mirrorX = !transform.mirrorX)) },
                    modifier = Modifier.weight(1f),
                )
                MirrorAxisButton(
                    label = stringResource(R.string.mirror_y),
                    mirrored = transform.mirrorY,
                    enabled = !autoLaying,
                    onClick = { onTransformChanged(transform.copy(mirrorY = !transform.mirrorY)) },
                    modifier = Modifier.weight(1f),
                )
                MirrorAxisButton(
                    label = stringResource(R.string.mirror_z),
                    mirrored = transform.mirrorZ,
                    enabled = !autoLaying,
                    onClick = { onTransformChanged(transform.copy(mirrorZ = !transform.mirrorZ)) },
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = onObjectSettings,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.object_process_settings))
            }
            Button(
                onClick = onChooseFilament,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Surface(
                    modifier = Modifier.size(18.dp),
                    shape = RoundedCornerShape(50),
                    color = filamentSlotColor(filamentSlot),
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        R.string.filament_tool_summary,
                        filamentSlot + 1,
                        profileLabel(filamentProfile),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onAutoLay,
                enabled = canAutoLay && !modelEditBusy,
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
            Button(
                onClick = onLayOnFace,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.VerticalAlignBottom, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lay_on_face))
            }
            Button(
                onClick = onMeasure,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.Straighten, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.measure_model))
            }
            Button(
                onClick = onBrimEars,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.AddBox, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.manual_brim_ears))
            }
            Button(
                onClick = onSimplify,
                enabled = canEditSingleVolumeModel && !modelEditBusy &&
                    triangleCount >= MINIMUM_SIMPLIFIABLE_TRIANGLES,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.GridView, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.simplify_model))
            }
            Button(
                onClick = onSplit,
                enabled = canEditSingleVolumeModel && !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.Layers, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.split_to_objects))
            }
            Button(
                onClick = onSplitParts,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.Layers, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.split_to_parts))
            }
            Button(
                onClick = onCut,
                enabled = canEditSingleVolumeModel && !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cut_model))
            }
            Button(
                onClick = onSeamPaint,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.Brush, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.paint_seam))
            }
            Button(
                onClick = onVariableLayerHeight,
                enabled = !modelEditBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3B37),
                    contentColor = Color(0xFFF4F4EE),
                ),
            ) {
                Icon(Icons.Default.Layers, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.variable_layer_height))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CutObjectSheet(
    modelHeightMm: Float,
    onCut: (Float, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var heightRatio by rememberSaveable { mutableFloatStateOf(0.5f) }
    var placeOnCut by rememberSaveable { mutableStateOf(true) }
    val safeHeight = modelHeightMm.takeIf { it.isFinite() && it > 0f } ?: 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.cut_model_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.cut_height),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(
                    R.string.cut_height_value,
                    safeHeight * heightRatio,
                    (heightRatio * 100f).roundToInt(),
                ),
                color = WorkspaceYellow,
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = heightRatio,
                onValueChange = { heightRatio = it },
                valueRange = 0.05f..0.95f,
                steps = 17,
                colors = SliderDefaults.colors(
                    thumbColor = WorkspaceYellow,
                    activeTrackColor = WorkspaceYellow,
                    inactiveTrackColor = Color(0xFF555650),
                ),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = placeOnCut,
                        role = Role.Switch,
                        onValueChange = { placeOnCut = it },
                    ),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF343530),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.place_on_cut),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = placeOnCut,
                        onCheckedChange = null,
                    )
                }
            }
            Button(
                onClick = { onCut(heightRatio, placeOnCut) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = WorkspaceYellow,
                    contentColor = WorkspaceBlack,
                ),
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.cut_model))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariableLayerHeightSheet(
    current: VariableLayerHeights,
    baseLayerHeightMm: Float,
    nozzleDiameterMm: Float,
    onApply: (VariableLayerHeights) -> Unit,
    onDismiss: () -> Unit,
) {
    val maximumLayerHeight = (nozzleDiameterMm * 0.7f).coerceAtLeast(0.04f)
    val initialLayerHeight = (baseLayerHeightMm * 0.5f).coerceIn(0.04f, maximumLayerHeight)
    var staged by remember(current) { mutableStateOf(current) }
    var selectedIndex by remember(current) { mutableStateOf(-1) }
    var selectedRange by remember(current) { mutableStateOf(0.25f..0.75f) }
    var selectedLayerHeight by remember(current) { mutableFloatStateOf(initialLayerHeight) }
    var rangeError by remember(current) { mutableStateOf(false) }

    fun stageRange() {
        val minimumGap = 0.01f
        val safeStart = selectedRange.start.coerceIn(0f, 1f - minimumGap)
        val safeEnd = selectedRange.endInclusive
            .coerceAtLeast(safeStart + minimumGap)
            .coerceAtMost(1f)
        val candidate = VariableLayerRange(
            startRatio = safeStart,
            endRatio = safeEnd,
            layerHeightMm = selectedLayerHeight,
        )
        val next = staged.ranges.toMutableList().apply {
            if (selectedIndex in indices) this[selectedIndex] = candidate else add(candidate)
            sortBy(VariableLayerRange::startRatio)
        }
        runCatching { VariableLayerHeights(next) }
            .onSuccess {
                staged = it
                selectedIndex = -1
                rangeError = false
            }
            .onFailure { rangeError = true }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.variable_layer_height),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    stringResource(R.string.variable_layer_height_hint),
                    color = Color(0xFFB8BAB3),
                    style = MaterialTheme.typography.bodyMedium,
                )
                staged.ranges.forEachIndexed { index, range ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIndex = index
                                selectedRange = range.startRatio..range.endRatio
                                selectedLayerHeight = range.layerHeightMm
                                rangeError = false
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedIndex == index) {
                                Color(0xFF4A4430)
                            } else {
                                Color(0xFF343530)
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    R.string.variable_layer_range_summary,
                                    (range.startRatio * 100).roundToInt(),
                                    (range.endRatio * 100).roundToInt(),
                                    range.layerHeightMm,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    staged = VariableLayerHeights(
                                        staged.ranges.filterIndexed { itemIndex, _ ->
                                            itemIndex != index
                                        },
                                    )
                                    selectedIndex = -1
                                    rangeError = false
                                },
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    stringResource(R.string.remove_layer_range),
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(
                        R.string.variable_layer_range_percent,
                        (selectedRange.start * 100).roundToInt(),
                        (selectedRange.endInclusive * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                RangeSlider(
                    value = selectedRange,
                    onValueChange = {
                        selectedRange = it
                        rangeError = false
                    },
                    valueRange = 0f..1f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = WorkspaceYellow,
                        activeTrackColor = WorkspaceYellow,
                        inactiveTrackColor = Color(0xFF555650),
                    ),
                )
                TransformSlider(
                    label = stringResource(R.string.layer_height),
                    valueText = stringResource(
                        R.string.millimeters_value_precise,
                        selectedLayerHeight,
                    ),
                    value = selectedLayerHeight,
                    range = 0.04f..maximumLayerHeight,
                    steps = ((maximumLayerHeight - 0.04f) / 0.01f)
                        .roundToInt().coerceAtLeast(1) - 1,
                    enabled = true,
                    onValueChange = {
                        selectedLayerHeight = it
                        rangeError = false
                    },
                )
                if (rangeError) {
                    Text(
                        stringResource(R.string.layer_ranges_overlap),
                        color = Color(0xFFFF8A80),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = ::stageRange,
                    enabled = selectedIndex >= 0 ||
                        staged.ranges.size < VariableLayerHeights.MAX_RANGES,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3A3B37),
                        contentColor = Color(0xFFF4F4EE),
                    ),
                ) {
                    Text(
                        stringResource(
                            if (selectedIndex >= 0) R.string.update_layer_range
                            else R.string.add_layer_range,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        staged = current
                        selectedIndex = -1
                        selectedRange = 0.25f..0.75f
                        selectedLayerHeight = initialLayerHeight
                        rangeError = false
                    },
                    modifier = Modifier.weight(0.3f),
                ) {
                    Text(stringResource(R.string.revert_changes))
                }
                Button(
                    onClick = { onApply(staged) },
                    modifier = Modifier.weight(0.7f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WorkspaceYellow,
                        contentColor = WorkspaceBlack,
                    ),
                ) {
                    Text(stringResource(R.string.apply_changes))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilamentAssignmentSheet(
    selectedSlot: Int,
    options: SliceOptions,
    catalog: ProfileCatalog,
    recentIds: List<String>,
    onSelected: (FilamentProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val slots = options.resolvedFilamentSlots()
    val selected = slots.getOrElse(selectedSlot) { slots.first() }
    val slotLimit = options.printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS)
    val profiles = catalog.filaments
        .asSequence()
        .filter { it.compatiblePrinters.matchesPrinter(options.printerProfile) }
        .filter { candidate ->
            slots.any { it.id == candidate.id } || slots.size < slotLimit
        }
        .plus(selected)
        .distinctBy(FilamentProfile::id)
        .toList()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.object_filament),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            SearchableGroupedProfileChoices(
                entries = profiles,
                selected = selected,
                recentIds = recentIds,
                id = FilamentProfile::id,
                name = FilamentProfile::name,
                label = { profileLabel(it) },
                brand = FilamentProfile::brand,
                builtIn = FilamentProfile::builtIn,
                searchTerms = { listOf(it.name, it.nativeName, it.brand.orEmpty()) },
                onSelected = onSelected,
            )
        }
    }
}

@Composable
private fun MirrorAxisButton(
    label: String,
    mirrored: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { selected = mirrored },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (mirrored) WorkspaceYellow else Color(0xFF3A3B37),
            contentColor = if (mirrored) WorkspaceBlack else Color(0xFFF4F4EE),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, maxLines = 1)
    }
}

@Composable
private fun TransformSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(valueText, color = Color(0xFFC8C9C2))
    }
    Slider(
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = Modifier.semantics {
            contentDescription = label
            stateDescription = valueText
        },
        valueRange = range,
        steps = steps,
        colors = duckySliderColors(),
    )
}

@Composable
private fun PlateSwitcher(
    plates: List<ProjectPlate>,
    selectedPlateId: String,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(0.92f).widthIn(max = 620.dp),
        color = Color.Black.copy(alpha = 0.76f),
        contentColor = Color(0xFFF4F4EE),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            plates.forEachIndexed { index, plate ->
                val selected = plate.id == selectedPlateId
                TextButton(
                    onClick = { onSelected(plate.id) },
                    enabled = enabled,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            this.selected = selected
                            stateDescription = if (selected) {
                                "${index + 1}/${plates.size}"
                            } else {
                                "${index + 1}"
                            }
                        },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selected) WorkspaceYellow else Color.Transparent,
                        contentColor = if (selected) WorkspaceBlack else Color(0xFFF4F4EE),
                    ),
                ) {
                    Text(
                        stringResource(R.string.plate_number, index + 1),
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                onClick = onAdd,
                enabled = enabled && plates.size < MAX_PROJECT_PLATES,
            ) {
                Icon(Icons.Default.AddBox, stringResource(R.string.add_plate))
            }
            IconButton(
                onClick = onRemove,
                enabled = enabled && plates.size > 1,
            ) {
                Icon(
                    Icons.Default.DeleteOutline,
                    stringResource(R.string.remove_plate),
                    tint = if (enabled && plates.size > 1) Color(0xFFFF8A80) else Color.Unspecified,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceMenu(
    importing: Boolean,
    editingBusy: Boolean,
    profileBusy: Boolean,
    profileTransferDirection: ProfileTransferDirection?,
    profileTransferCancellationRequested: Boolean,
    projectEditActive: Boolean,
    cancellationRequested: Boolean,
    projectImporting: Boolean,
    projectExporting: Boolean,
    projectTransferCancellationRequested: Boolean,
    slicing: Boolean,
    previewLoading: Boolean,
    canExport: Boolean,
    exportingGcode: Boolean,
    gcodeExportCancellationRequested: Boolean,
    canArrange: Boolean,
    onImport: () -> Unit,
    onImportProfiles: () -> Unit,
    onExportProfiles: () -> Unit,
    onCancelProfileTransfer: () -> Unit,
    onAddShape: () -> Unit,
    onExport: () -> Unit,
    onCancelGcodeExport: () -> Unit,
    onArrange: () -> Unit,
    onCancelProjectEdit: () -> Unit,
    onCancelProjectImport: () -> Unit,
    onCancelProjectExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuDescription = stringResource(R.string.menu)
    Box(modifier) {
        Surface(
            color = Color.Black.copy(alpha = 0.68f),
            contentColor = Color(0xFFF4F4EE),
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(50.dp),
        ) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.semantics { contentDescription = menuDescription },
            ) {
                if (importing || editingBusy || profileTransferDirection != null) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = WorkspaceYellow, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Menu, contentDescription = null)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (projectEditActive) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (cancellationRequested) {
                                    R.string.canceling_model_edit
                                } else {
                                    R.string.cancel_model_edit
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                    enabled = !cancellationRequested,
                    onClick = {
                        expanded = false
                        onCancelProjectEdit()
                    },
                )
            }
            if (projectImporting || projectExporting) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when {
                                    projectTransferCancellationRequested && projectImporting ->
                                        R.string.canceling_project_import
                                    projectTransferCancellationRequested ->
                                        R.string.canceling_project_export
                                    projectImporting -> R.string.cancel_project_import
                                    else -> R.string.cancel_project_export
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                    enabled = !projectTransferCancellationRequested,
                    onClick = {
                        expanded = false
                        if (projectImporting) onCancelProjectImport() else onCancelProjectExport()
                    },
                )
            }
            if (profileTransferDirection != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                when {
                                    profileTransferCancellationRequested ->
                                        R.string.canceling_profile_transfer
                                    profileTransferDirection == ProfileTransferDirection.IMPORT ->
                                        R.string.cancel_profile_import
                                    else -> R.string.cancel_profile_export
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Close, null) },
                    enabled = !profileTransferCancellationRequested,
                    onClick = {
                        expanded = false
                        onCancelProfileTransfer()
                    },
                )
            }
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
                text = { Text(stringResource(R.string.add_shape)) },
                leadingIcon = { Icon(Icons.Default.AddBox, null) },
                enabled = !importing && !editingBusy && !slicing && !previewLoading,
                onClick = {
                    expanded = false
                    onAddShape()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_profiles)) },
                leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                enabled = !profileBusy && !importing && !editingBusy && !slicing && !previewLoading,
                onClick = {
                    expanded = false
                    onImportProfiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_profiles)) },
                leadingIcon = { Icon(Icons.Default.SaveAlt, null) },
                enabled = !profileBusy && !importing && !editingBusy && !slicing && !previewLoading,
                onClick = {
                    expanded = false
                    onExportProfiles()
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
                text = {
                    Text(
                        stringResource(
                            when {
                                gcodeExportCancellationRequested -> R.string.canceling_gcode_export
                                exportingGcode -> R.string.cancel_gcode_export
                                else -> R.string.export_gcode
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(if (exportingGcode) Icons.Default.Close else Icons.Default.SaveAlt, null)
                },
                enabled = if (exportingGcode) {
                    !gcodeExportCancellationRequested
                } else {
                    canExport
                },
                onClick = {
                    expanded = false
                    if (exportingGcode) onCancelGcodeExport() else onExport()
                },
            )
        }
    }
}

@Composable
private fun PreviewExportSplitButton(
    canExport: Boolean,
    exporting: Boolean,
    cancellationRequested: Boolean,
    canSend: Boolean,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val exportOptionsLabel = stringResource(R.string.export_options)
    Box(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(50.dp)
                    .clickable(
                        enabled = canExport,
                        onClickLabel = exportOptionsLabel,
                        role = Role.Button,
                        onClick = { expanded = true },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.68f),
                    contentColor = Color(0xFFF4F4EE),
                    shape = RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp),
                    modifier = Modifier.width(34.dp).height(50.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = exportOptionsLabel,
                        )
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
            Surface(
                color = WorkspaceYellow,
                contentColor = WorkspaceBlack,
                shape = RoundedCornerShape(50),
                modifier = Modifier.size(50.dp),
            ) {
                IconButton(
                    enabled = if (exporting) !cancellationRequested else canExport,
                    onClick = { if (exporting) onCancelExport() else onExport() },
                ) {
                    if (exporting) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = WorkspaceBlack,
                                strokeWidth = 2.dp,
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(
                                    if (cancellationRequested) {
                                        R.string.canceling_gcode_export
                                    } else {
                                        R.string.cancel_gcode_export
                                    },
                                ),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.SaveAlt,
                            contentDescription = stringResource(R.string.export_gcode),
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            when {
                                cancellationRequested -> R.string.canceling_gcode_export
                                exporting -> R.string.cancel_gcode_export
                                else -> R.string.export_gcode
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(if (exporting) Icons.Default.Close else Icons.Default.SaveAlt, null)
                },
                enabled = if (exporting) !cancellationRequested else canExport,
                onClick = {
                    expanded = false
                    if (exporting) onCancelExport() else onExport()
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
    val showLabels = showWorkspaceNavigationLabels(LocalDensity.current.fontScale)
    NavigationBar(containerColor = Color(0xFF242522)) {
        workspaceNavigationItems().forEach { (tab, icon, label) ->
            val labelText = stringResource(label)
            NavigationBarItem(
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                icon = {
                    Icon(
                        icon,
                        contentDescription = if (showLabels) null else labelText,
                    )
                },
                label = if (showLabels) {
                    { Text(labelText, maxLines = 1) }
                } else {
                    null
                },
                alwaysShowLabel = showLabels,
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
    layOnFaceObjectId: String?,
    measureObjectId: String?,
    measurementPoints: List<ModelPoint3>,
    supportPaintObjectId: String?,
    supportPaintState: SupportPaintState?,
    seamPaintObjectId: String?,
    seamPaintState: SeamPaintState?,
    multiColorPaintObjectId: String?,
    multiColorPaintSlot: Int?,
    brimEditObjectId: String?,
    brimPoints: BrimPoints,
    selectedBrimPointIndex: Int?,
    brimAddMode: Boolean,
    onObjectSelected: (String?) -> Unit,
    onModelTransformPreview: (ModelTransform) -> Unit,
    onModelTransformCommitted: (ModelTransform) -> Unit,
    onLayOnFace: (String, FloatArray) -> Unit,
    onMeasurePoint: (ModelPoint3) -> Unit,
    onSupportPaintPreview: (String, String, Int, SupportPaintState?) -> Unit,
    onSupportPaintCommitted: (String, String, SupportPaint) -> Unit,
    onSeamPaintPreview: (String, String, Int, SeamPaintState?) -> Unit,
    onSeamPaintCommitted: (String, String, SeamPaint) -> Unit,
    onBrimPointSelected: (Int?) -> Unit,
    onBrimPointAdded: (BrimPoint) -> Unit,
    onBrimPointMoved: (Int, BrimPoint) -> Unit,
    onBrimPointInvalid: () -> Unit,
    onMultiColorPaintPreview: (String, String, Int, Int?) -> Unit,
    onMultiColorPaintCommitted: (String, String, MultiColorPaint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val depthPreviewSupported = remember(context) { supportsDepthTestedPreview(context) }
    var depthPreviewRuntimeAvailable by remember(previewRenderingMode) { mutableStateOf(true) }
    var prepareRendererRuntimeAvailable by remember { mutableStateOf(true) }
    val previewCapabilities = remember(context) { previewDeviceCapabilities(context) }
    val effectivePreviewDetail = remember(previewDetail, previewCapabilities) {
        resolvePreviewDetail(previewDetail, previewCapabilities)
    }
    if (preview != null && shouldUseDepthTestedPreview(
            renderingMode = previewRenderingMode,
            deviceSupported = depthPreviewSupported,
            runtimeAvailable = depthPreviewRuntimeAvailable,
        )
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
            // Keep the Automatic request intact so the GLES renderer can promote its
            // settled geometry from Performance to Balanced/Detail using measured work.
            detail = previewDetail,
            onUnavailable = { depthPreviewRuntimeAvailable = false },
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
    val useDepthTestedPrepare = preview == null && projectObjects.isNotEmpty() &&
        depthPreviewSupported && prepareRendererRuntimeAvailable
    val objectIds = projectObjects.map(ProjectObject::id)
    val modelTopology = projectObjects.flatMap { projectObject ->
        projectObject.volumes.map { volume -> (projectObject.id to volume.id) to volume.model }
    }
    val modelMeshEdges = remember(modelTopology, useDepthTestedPrepare) {
        if (useDepthTestedPrepare) {
            emptyMap()
        } else {
            projectObjects.flatMap { projectObject ->
                projectObject.volumes.map { volume ->
                    (projectObject.id to volume.id) to
                        buildModelMeshEdges(volume.model.previewTriangles)
                }
            }.toMap()
        }
    }
    val modelPlacementOrientations = projectObjects.map { projectObject ->
        projectObject.id to projectObject.transform.placementOrientation()
    }
    val modelPlacements = remember(modelTopology, modelPlacementOrientations) {
        projectObjects.associate { projectObject ->
            projectObject.id to PrepareObjectPlacement(
                geometry = projectObject.geometry(),
                minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
            )
        }
    }
    var modelPickingIndices by remember(modelTopology) {
        mutableStateOf<Map<PreparePickingIndexKey, PrepareVolumePickingIndex>>(emptyMap())
    }
    LaunchedEffect(modelTopology, interactionActive, layOnFaceObjectId) {
        if (
            interactionActive || layOnFaceObjectId != null ||
            modelPickingIndices.size == modelTopology.size
        ) {
            return@LaunchedEffect
        }
        delay(PREPARE_PICKING_PREWARM_DELAY_MS)
        val snapshot = projectObjects
        modelPickingIndices = withModelPreparationContext {
            buildPreparePickingIndices(snapshot) { ensureActive() }
        }
    }
    LaunchedEffect(modelTopology, layOnFaceObjectId) {
        val selected = projectObjects.firstOrNull { it.id == layOnFaceObjectId }
            ?: return@LaunchedEffect
        val missingIndex = selected.volumes.any { volume ->
            PreparePickingIndexKey(selected.id, volume.id) !in modelPickingIndices
        }
        if (!missingIndex) return@LaunchedEffect
        val selectedIndices = withModelPreparationContext {
            buildPreparePickingIndices(listOf(selected)) { ensureActive() }
        }
        modelPickingIndices = modelPickingIndices + selectedIndices
    }
    var layOnFaceCandidates by remember(layOnFaceObjectId, modelTopology) {
        mutableStateOf<Map<String, List<LayOnFaceCandidate>>>(emptyMap())
    }
    LaunchedEffect(layOnFaceObjectId, modelTopology) {
        val selected = projectObjects.firstOrNull { it.id == layOnFaceObjectId }
        layOnFaceCandidates = if (selected == null) {
            emptyMap()
        } else {
            withModelPreparationContext {
                selected.modelPartVolumes.associate { volume ->
                    volume.id to detectLayOnFaceCandidates(
                        volume.model.previewTriangles,
                        checkCancellation = { ensureActive() },
                    )
                }
            }
        }
    }
    val layOnFaceCandidateFacets = remember(layOnFaceCandidates, modelTopology) {
        projectObjects.firstOrNull { it.id == layOnFaceObjectId }
            ?.modelPartVolumes
            ?.associate { volume ->
                val selectable = BooleanArray(volume.model.previewTriangles.size / 9)
                layOnFaceCandidates[volume.id].orEmpty().forEach { candidate ->
                    candidate.previewTriangleIndices.forEach { triangleIndex ->
                        selectable[triangleIndex] = true
                    }
                }
                volume.id to selectable
            }
            .orEmpty()
    }
    var modelScreenBounds by remember(objectIds) { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    var modelScreenTriangles by remember(objectIds) {
        mutableStateOf<Map<String, List<ModelScreenTriangle>>>(emptyMap())
    }
    var brimPointScreenPositions by remember(brimEditObjectId) {
        mutableStateOf<Map<Int, Offset>>(emptyMap())
    }
    val currentObjects by rememberUpdatedState(projectObjects)
    val currentModelPlacements by rememberUpdatedState(modelPlacements)
    val currentModelPickingIndices by rememberUpdatedState(modelPickingIndices)
    val currentSelectionCallback by rememberUpdatedState(onObjectSelected)
    val currentTransformCallback by rememberUpdatedState(onModelTransformPreview)
    val currentTransformCommitCallback by rememberUpdatedState(onModelTransformCommitted)
    val currentLayOnFaceCallback by rememberUpdatedState(onLayOnFace)
    val currentMeasurePointCallback by rememberUpdatedState(onMeasurePoint)
    val currentSupportPaintPreviewCallback by rememberUpdatedState(onSupportPaintPreview)
    val currentSupportPaintCommitCallback by rememberUpdatedState(onSupportPaintCommitted)
    val currentSeamPaintPreviewCallback by rememberUpdatedState(onSeamPaintPreview)
    val currentSeamPaintCommitCallback by rememberUpdatedState(onSeamPaintCommitted)
    val currentBrimPoints by rememberUpdatedState(brimPoints)
    val currentBrimPointSelectionCallback by rememberUpdatedState(onBrimPointSelected)
    val currentBrimPointAddedCallback by rememberUpdatedState(onBrimPointAdded)
    val currentBrimPointMovedCallback by rememberUpdatedState(onBrimPointMoved)
    val currentBrimPointInvalidCallback by rememberUpdatedState(onBrimPointInvalid)
    val currentMultiColorPaintPreviewCallback by rememberUpdatedState(onMultiColorPaintPreview)
    val currentMultiColorPaintCommitCallback by rememberUpdatedState(onMultiColorPaintCommitted)
    val effectiveBedPolygon = remember(bedPolygon, bedSizeX, bedSizeY) {
        bedPolygon.takeIf { bedPolygonIsValid(it, bedSizeX, bedSizeY) }
            ?: rectangularBedPolygon(bedSizeX, bedSizeY)
    }
    val brimEditFootprint = remember(
        brimEditObjectId,
        modelTopology,
        projectObjects.firstOrNull { it.id == brimEditObjectId }?.transform,
        bedSizeX,
        bedSizeY,
    ) {
        projectObjects.firstOrNull { it.id == brimEditObjectId }
            ?.placedModelFootprint(bedSizeX, bedSizeY)
    }
    val previewPaths = remember(preview) {
        Array(PreviewDepthBands) { Array(ToolpathStyles.size) { Path() } }
    }
    val movingPreviewPlan = remember(preview, effectivePreviewDetail, visibleToolpathRoles) {
        preview?.buildRenderPlan(
            segmentBudget = compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = false),
            visibleRoles = visibleToolpathRoles,
        )
    }
    val refinedPreviewPlan = remember(preview, effectivePreviewDetail, visibleToolpathRoles) {
        preview?.buildRenderPlan(
            segmentBudget = compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = true),
            visibleRoles = visibleToolpathRoles,
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

    fun orbitBy(delta: Offset, viewportWidth: Int, viewportHeight: Int) {
        val orbit = workspaceOrbitDelta(
            pointerDelta = delta,
            viewportWidth = viewportWidth.toFloat(),
            viewportHeight = viewportHeight.toFloat(),
        )
        yaw += orbit.x
        pitch = (pitch - orbit.y).coerceIn(18f, 86f)
    }

    fun panAndZoomBy(event: PointerEvent, viewportWidth: Int, viewportHeight: Int) {
        val gesture = anchoredWorkspacePanZoom(
            pan = pan,
            zoom = zoom,
            viewportAnchor = Offset(viewportWidth / 2f, viewportHeight * 0.48f),
            previousCentroid = event.calculateCentroid(useCurrent = false),
            currentCentroid = event.calculateCentroid(useCurrent = true),
            zoomChange = event.calculateZoom(),
        )
        pan = gesture.pan
        zoom = gesture.zoom
    }

    Box(modifier) {
        if (useDepthTestedPrepare) {
            DepthTestedPrepareModelScene(
                projectObjects = projectObjects,
                placements = modelPlacements,
                selectedObjectId = selectedObjectId,
                bedSizeX = bedSizeX,
                bedSizeY = bedSizeY,
                bedPolygon = effectiveBedPolygon,
                yawDegrees = yaw,
                pitchDegrees = pitch,
                zoom = zoom,
                panX = pan.x,
                panY = pan.y,
                interactionActive = interactionActive,
                layOnFaceObjectId = layOnFaceObjectId,
                layOnFaceCandidateFacets = layOnFaceCandidateFacets,
                onUnavailable = { prepareRendererRuntimeAvailable = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(
            objectIds,
            preview,
            objectManipulationEnabled,
            layOnFaceObjectId,
            measureObjectId,
            supportPaintObjectId,
            supportPaintState,
            seamPaintObjectId,
            seamPaintState,
            multiColorPaintObjectId,
            multiColorPaintSlot,
            brimEditObjectId,
            brimAddMode,
            useDepthTestedPrepare,
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val brimEditingObject = brimEditObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val layOnFaceObject = layOnFaceObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val measuringObject = measureObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val supportPaintingObject = supportPaintObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val seamPaintingObject = seamPaintObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val multiColorPaintingObject = multiColorPaintObjectId?.let { objectId ->
                    currentObjects.firstOrNull { it.id == objectId }
                }
                val paintingObject = supportPaintingObject ?: seamPaintingObject ?:
                    multiColorPaintingObject
                val paintableVolumeIds = paintingObject?.modelPartVolumes
                    ?.mapTo(HashSet()) { it.id }
                    .orEmpty()
                if (brimEditingObject != null) {
                    val markerTouchRadius = 24.dp.toPx()
                    val touchedIndex = brimPointScreenPositions
                        .mapValues { (_, position) -> (position - down.position).getDistance() }
                        .filterValues { it <= markerTouchRadius }
                        .minByOrNull { it.value }
                        ?.key
                    if (touchedIndex != null) currentBrimPointSelectionCallback(touchedIndex)
                    val draggedRadius = touchedIndex
                        ?.let { currentBrimPoints.points.getOrNull(it)?.radiusMm }

                    fun pointAtScreen(position: Offset, radiusMm: Float): BrimPoint? {
                        val currentSceneScale = min(
                            size.width * 0.64f,
                            size.height * 0.72f,
                        ) / max(bedSizeX, bedSizeY) * zoom
                        if (!currentSceneScale.isFinite() || currentSceneScale <= 0.0001f) return null
                        val currentSceneCenter = Offset(
                            size.width / 2f + pan.x,
                            size.height * 0.48f + pan.y,
                        )
                        val pitchRadians = pitch / 180f * PI.toFloat()
                        val yawRadians = yaw / 180f * PI.toFloat()
                        val sinPitch = sin(pitchRadians)
                        if (abs(sinPitch) <= 0.0001f) return null
                        val rotatedX = (position.x - currentSceneCenter.x) / currentSceneScale
                        val rotatedY = (position.y - currentSceneCenter.y) /
                            (currentSceneScale * sinPitch)
                        val worldX = bedSizeX / 2f +
                            rotatedX * cos(yawRadians) + rotatedY * sin(yawRadians)
                        val worldY = bedSizeY / 2f -
                            rotatedX * sin(yawRadians) + rotatedY * cos(yawRadians)
                        if (!pointInsideBedPolygon(worldX, worldY, effectiveBedPolygon)) return null
                        return brimEditingObject.transform.manualBrimPointAtBed(
                            brimEditingObject,
                            worldX,
                            worldY,
                            bedSizeX,
                            bedSizeY,
                            radiusMm,
                            brimEditFootprint ?: return null,
                        )
                    }

                    var movement = 0f
                    var usedMultiplePointers = false
                    interactionActive = true
                    try {
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size == 1) {
                                val change = pressed.first()
                                val delta = change.position - change.previousPosition
                                movement += abs(delta.x) + abs(delta.y)
                                if (touchedIndex != null && draggedRadius != null) {
                                    val candidate = pointAtScreen(change.position, draggedRadius)
                                    if (candidate != null) {
                                        currentBrimPointMovedCallback(touchedIndex, candidate)
                                    } else {
                                        currentBrimPointInvalidCallback()
                                    }
                                } else {
                                    orbitBy(delta, size.width, size.height)
                                }
                            } else if (pressed.size >= 2) {
                                usedMultiplePointers = true
                                panAndZoomBy(event, size.width, size.height)
                            }
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        interactionActive = false
                    }
                    if (movement < 12f && !usedMultiplePointers && touchedIndex == null) {
                        if (brimAddMode) {
                            val candidate = pointAtScreen(
                                down.position,
                                BrimPoint.DEFAULT_RADIUS_MM,
                            )
                            if (candidate != null) {
                                currentBrimPointAddedCallback(candidate)
                            } else {
                                currentBrimPointInvalidCallback()
                            }
                        } else {
                            currentBrimPointSelectionCallback(null)
                        }
                    }
                    return@awaitEachGesture
                }
                if (layOnFaceObject != null || measuringObject != null) {
                    var movement = 0f
                    var usedMultiplePointers = false
                    interactionActive = true
                    try {
                        var event: PointerEvent
                        do {
                            event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size == 1) {
                                val change = pressed.first()
                                val delta = change.position - change.previousPosition
                                movement += abs(delta.x) + abs(delta.y)
                                orbitBy(delta, size.width, size.height)
                            } else if (pressed.size >= 2) {
                                usedMultiplePointers = true
                                panAndZoomBy(event, size.width, size.height)
                            }
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        interactionActive = false
                    }
                    if (movement < 12f && !usedMultiplePointers) {
                        val activeObject = layOnFaceObject ?: checkNotNull(measuringObject)
                        val hit = if (useDepthTestedPrepare) {
                            currentModelPlacements[activeObject.id]?.let { placement ->
                                val hitTestViewport = PrepareHitTestViewport(
                                    widthPx = size.width.toFloat(),
                                    heightPx = size.height.toFloat(),
                                    bedSizeX = bedSizeX,
                                    bedSizeY = bedSizeY,
                                    yawDegrees = yaw,
                                    pitchDegrees = pitch,
                                    zoom = zoom,
                                    panX = pan.x,
                                    panY = pan.y,
                                )
                                if (layOnFaceObject != null) {
                                    findLayOnFaceFacetAtScreen(
                                        projectObject = activeObject,
                                        placement = placement,
                                        viewport = hitTestViewport,
                                        screenX = down.position.x,
                                        screenY = down.position.y,
                                        touchRadiusPx = 18.dp.toPx(),
                                        pickingIndices = currentModelPickingIndices,
                                    )
                                } else {
                                    findPrepareFacetAtScreen(
                                        projectObject = activeObject,
                                        placement = placement,
                                        viewport = hitTestViewport,
                                        screenX = down.position.x,
                                        screenY = down.position.y,
                                        touchRadiusPx = 18.dp.toPx(),
                                        pickingIndices = currentModelPickingIndices,
                                    )
                                }
                            }
                        } else {
                            closestModelTriangle(
                                modelScreenTriangles[activeObject.id].orEmpty(),
                                down.position,
                                18.dp.toPx(),
                            )
                        }
                        if (hit != null) {
                            val activeVolume = activeObject.volumes.firstOrNull {
                                it.id == hit.volumeId
                            }
                            if (activeVolume == null) return@awaitEachGesture
                            val model = activeVolume.model
                            val start = hit.previewTriangleIndex * 9
                            if (start >= 0 && start + 9 <= model.previewTriangles.size) {
                                if (layOnFaceObject != null) {
                                    currentLayOnFaceCallback(
                                        layOnFaceObject.id,
                                        model.previewTriangles.copyOfRange(start, start + 9),
                                    )
                                } else {
                                    val transform = activeObject.transform
                                    val placement = checkNotNull(
                                        currentModelPlacements[activeObject.id],
                                    )
                                    val geometry = placement.geometry
                                    val minimumRotatedZ = placement.minimumRotatedZ
                                    val worldA = transform.placeVertex(
                                        model.previewTriangles[start],
                                        model.previewTriangles[start + 1],
                                        model.previewTriangles[start + 2],
                                        geometry,
                                        bedSizeX,
                                        bedSizeY,
                                        minimumRotatedZ,
                                    )
                                    val worldB = transform.placeVertex(
                                        model.previewTriangles[start + 3],
                                        model.previewTriangles[start + 4],
                                        model.previewTriangles[start + 5],
                                        geometry,
                                        bedSizeX,
                                        bedSizeY,
                                        minimumRotatedZ,
                                    )
                                    val worldC = transform.placeVertex(
                                        model.previewTriangles[start + 6],
                                        model.previewTriangles[start + 7],
                                        model.previewTriangles[start + 8],
                                        geometry,
                                        bedSizeX,
                                        bedSizeY,
                                        minimumRotatedZ,
                                    )
                                    modelSurfacePoint(hit, down.position, worldA, worldB, worldC)
                                        ?.let(currentMeasurePointCallback)
                                }
                            }
                        }
                    }
                    return@awaitEachGesture
                }
                val hitObjectId = if (objectManipulationEnabled && paintingObject == null) {
                    val touchRadius = 14.dp.toPx()
                    val candidates = modelScreenBounds.filterValues { bounds ->
                        bounds.inflate(touchRadius).contains(down.position)
                    }.keys
                    if (useDepthTestedPrepare && candidates.isNotEmpty()) {
                        findPrepareObjectAtScreen(
                            projectObjects = currentObjects.filter { it.id in candidates },
                            placements = currentModelPlacements,
                            viewport = PrepareHitTestViewport(
                                widthPx = size.width.toFloat(),
                                heightPx = size.height.toFloat(),
                                bedSizeX = bedSizeX,
                                bedSizeY = bedSizeY,
                                yawDegrees = yaw,
                                pitchDegrees = pitch,
                                zoom = zoom,
                                panX = pan.x,
                                panY = pan.y,
                            ),
                            screenX = down.position.x,
                            screenY = down.position.y,
                            touchRadiusPx = touchRadius,
                            pickingIndices = currentModelPickingIndices,
                        )
                    } else {
                        modelScreenBounds.entries.toList().asReversed().firstOrNull { (_, bounds) ->
                            bounds.inflate(touchRadius).contains(down.position)
                        }?.key
                    }
                } else {
                    null
                }
                val dragStartTransform = currentObjects
                    .firstOrNull { it.id == hitObjectId }
                    ?.transform
                var paintedVolumeId: String? = null
                var supportPaintStart: SupportPaint? = null
                var seamPaintStart: SeamPaint? = null
                var multiColorPaintStart: MultiColorPaint? = null
                val paintedFacets = HashSet<Pair<String, Int>>()
                fun paintAt(position: Offset) {
                    val objectId = paintingObject?.id ?: return
                    val hit = if (useDepthTestedPrepare) {
                        currentModelPlacements[objectId]?.let { placement ->
                            findPrepareFacetAtScreen(
                                projectObject = paintingObject,
                                placement = placement,
                                viewport = PrepareHitTestViewport(
                                    widthPx = size.width.toFloat(),
                                    heightPx = size.height.toFloat(),
                                    bedSizeX = bedSizeX,
                                    bedSizeY = bedSizeY,
                                    yawDegrees = yaw,
                                    pitchDegrees = pitch,
                                    zoom = zoom,
                                    panX = pan.x,
                                    panY = pan.y,
                                ),
                                screenX = position.x,
                                screenY = position.y,
                                touchRadiusPx = 18.dp.toPx(),
                                selectableVolumeIds = paintableVolumeIds,
                                pickingIndices = currentModelPickingIndices,
                            )
                        }
                    } else {
                        closestModelTriangle(
                            modelScreenTriangles[objectId].orEmpty().filter {
                                it.volumeId in paintableVolumeIds
                            },
                            position,
                            18.dp.toPx(),
                        )
                    } ?: return
                    if (paintedVolumeId == null) {
                        val volume = paintingObject.volumes.firstOrNull { it.id == hit.volumeId }
                            ?: return
                        if (!volume.role.acceptsFacetPaint) return
                        paintedVolumeId = volume.id
                        supportPaintStart = volume.supportPaint.takeIf { supportPaintingObject != null }
                        seamPaintStart = volume.seamPaint.takeIf { seamPaintingObject != null }
                        multiColorPaintStart = volume.multiColorPaint.takeIf {
                            multiColorPaintingObject != null
                        }
                    }
                    if (paintedVolumeId != hit.volumeId) return
                    val paintedFacet = hit.volumeId to hit.sourceFacetIndex
                    if (paintedFacets.add(paintedFacet)) {
                        if (supportPaintingObject != null) {
                            currentSupportPaintPreviewCallback(
                                objectId,
                                hit.volumeId,
                                hit.sourceFacetIndex,
                                supportPaintState,
                            )
                        } else if (seamPaintingObject != null) {
                            currentSeamPaintPreviewCallback(
                                objectId,
                                hit.volumeId,
                                hit.sourceFacetIndex,
                                seamPaintState,
                            )
                        } else if (multiColorPaintingObject != null) {
                            currentMultiColorPaintPreviewCallback(
                                objectId,
                                hit.volumeId,
                                hit.sourceFacetIndex,
                                multiColorPaintSlot,
                            )
                        }
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
                                    orbitBy(delta, size.width, size.height)
                                }
                            }

                            pressed.size >= 2 -> {
                                panAndZoomBy(event, size.width, size.height)
                            }
                        }
                        event.changes.forEach { change ->
                            if (change.positionChanged()) change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    interactionActive = false
                    if (
                        supportPaintingObject != null && supportPaintStart != null &&
                        paintedFacets.isNotEmpty() && paintedVolumeId != null
                    ) {
                        currentSupportPaintCommitCallback(
                            supportPaintingObject.id,
                            checkNotNull(paintedVolumeId),
                            checkNotNull(supportPaintStart),
                        )
                    } else if (
                        seamPaintingObject != null && seamPaintStart != null &&
                        paintedFacets.isNotEmpty() && paintedVolumeId != null
                    ) {
                        currentSeamPaintCommitCallback(
                            seamPaintingObject.id,
                            checkNotNull(paintedVolumeId),
                            checkNotNull(seamPaintStart),
                        )
                    } else if (
                        multiColorPaintingObject != null && multiColorPaintStart != null &&
                        paintedFacets.isNotEmpty() && paintedVolumeId != null
                    ) {
                        currentMultiColorPaintCommitCallback(
                            multiColorPaintingObject.id,
                            checkNotNull(paintedVolumeId),
                            checkNotNull(multiColorPaintStart),
                        )
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

        if (useDepthTestedPrepare) {
            val nextBounds = mutableMapOf<String, Rect>()
            projectObjects.forEach { projectObject ->
                val placement = checkNotNull(modelPlacements[projectObject.id])
                val geometry = placement.geometry
                val minimumRotatedZ = placement.minimumRotatedZ
                var left = Float.POSITIVE_INFINITY
                var top = Float.POSITIVE_INFINITY
                var right = Float.NEGATIVE_INFINITY
                var bottom = Float.NEGATIVE_INFINITY
                floatArrayOf(geometry.minX, geometry.maxX).forEach { x ->
                    floatArrayOf(geometry.minY, geometry.maxY).forEach { y ->
                        floatArrayOf(geometry.minZ, geometry.maxZ).forEach { z ->
                            val world = projectObject.transform.placeVertex(
                                x, y, z, geometry, bedSizeX, bedSizeY, minimumRotatedZ,
                            )
                            val point = project(world[0], world[1], world[2])
                            left = min(left, point.x)
                            top = min(top, point.y)
                            right = max(right, point.x)
                            bottom = max(bottom, point.y)
                        }
                    }
                }
                if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
                    val bounds = Rect(left, top, right, bottom)
                    nextBounds[projectObject.id] = bounds
                    if (projectObject.id == selectedObjectId) {
                        val color = WorkspaceYellow.copy(alpha = 0.82f)
                        val width = 1.5.dp.toPx()
                        drawLine(color, bounds.topLeft, bounds.topRight, width)
                        drawLine(color, bounds.topRight, bounds.bottomRight, width)
                        drawLine(color, bounds.bottomRight, bounds.bottomLeft, width)
                        drawLine(color, bounds.bottomLeft, bounds.topLeft, width)
                    }
                }
            }
            if (modelScreenBounds != nextBounds) modelScreenBounds = nextBounds
            if (modelScreenTriangles.isNotEmpty()) modelScreenTriangles = emptyMap()
            if (brimPointScreenPositions.isNotEmpty()) brimPointScreenPositions = emptyMap()
            if (brimEditObjectId != null) {
                projectObjects.firstOrNull { it.id == brimEditObjectId }?.let { projectObject ->
                    val nextBrimPositions = mutableMapOf<Int, Offset>()
                    brimPoints.points.forEachIndexed { index, brimPoint ->
                        val world = projectObject.transform.placeBrimPoint(
                            brimPoint,
                            projectObject,
                            bedSizeX,
                            bedSizeY,
                        )
                        val center = project(world[0], world[1], world[2])
                        nextBrimPositions[index] = center
                        val selected = index == selectedBrimPointIndex
                        val radius = max(7.dp.toPx(), brimPoint.radiusMm * sceneScale)
                        drawCircle(
                            color = WorkspaceYellow.copy(alpha = if (selected) 0.28f else 0.16f),
                            radius = radius,
                            center = center,
                        )
                        drawCircle(
                            color = if (selected) WorkspaceYellow else Color(0xFFF4F4EE),
                            radius = radius,
                            center = center,
                            style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()),
                        )
                        drawCircle(Color.Black.copy(alpha = 0.9f), 6.dp.toPx(), center)
                        drawCircle(
                            if (world[2] <= 0.05f) WorkspaceYellow else Color(0xFFFF6B6B),
                            3.5.dp.toPx(),
                            center,
                        )
                    }
                    if (brimPointScreenPositions != nextBrimPositions) {
                        brimPointScreenPositions = nextBrimPositions
                    }
                }
            }
            if (measureObjectId != null && measurementPoints.isNotEmpty()) {
                val projectedPoints = measurementPoints.take(2).map { point ->
                    project(point.x, point.y, point.z)
                }
                if (projectedPoints.size == 2) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.86f),
                        start = projectedPoints[0],
                        end = projectedPoints[1],
                        strokeWidth = 4.5.dp.toPx(),
                    )
                    drawLine(
                        color = WorkspaceYellow,
                        start = projectedPoints[0],
                        end = projectedPoints[1],
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                projectedPoints.forEachIndexed { index, point ->
                    drawCircle(Color.Black.copy(alpha = 0.88f), 7.dp.toPx(), point)
                    drawCircle(
                        if (index == 0) WorkspaceYellow else Color(0xFFF4F4EE),
                        4.dp.toPx(),
                        point,
                    )
                }
            }
            return@Canvas
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
            val nextBrimPositions = mutableMapOf<Int, Offset>()
            projectObjects.forEach { projectObject ->
                val modelTransform = projectObject.transform
                val objectSelected = projectObject.id == selectedObjectId
                val placement = checkNotNull(modelPlacements[projectObject.id])
                val objectGeometry = placement.geometry
                val minimumRotatedZ = placement.minimumRotatedZ
                val enforcePaintPath = Path()
                val blockPaintPath = Path()
                val seamEnforcePaintPath = Path()
                val seamBlockPaintPath = Path()
                val multiColorPaintPaths = mutableMapOf<Int, Path>()
                val screenTriangles = ArrayList<ModelScreenTriangle>(
                    projectObject.volumes.sumOf { it.model.previewTriangleIndices.size },
                )
                val screenTrianglesByVolume = mutableMapOf<String, List<ModelScreenTriangle>>()
                var minimumScreenX = Float.POSITIVE_INFINITY
                var minimumScreenY = Float.POSITIVE_INFINITY
                var maximumScreenX = Float.NEGATIVE_INFINITY
                var maximumScreenY = Float.NEGATIVE_INFINITY
                var minimumModelDepth = Float.POSITIVE_INFINITY
                var maximumModelDepth = Float.NEGATIVE_INFINITY
                projectObject.volumes.forEach { volume ->
                    val model = volume.model
                    val volumeScreenTriangles = ArrayList<ModelScreenTriangle>(
                        model.previewTriangleIndices.size,
                    )
                    var triangleIndex = 0
                    while (triangleIndex + 8 < model.previewTriangles.size) {
                    val aPosition = modelTransform.placeVertex(
                        model.previewTriangles[triangleIndex],
                        model.previewTriangles[triangleIndex + 1],
                        model.previewTriangles[triangleIndex + 2],
                        objectGeometry,
                        bedSizeX,
                        bedSizeY,
                        minimumRotatedZ,
                    )
                    val bPosition = modelTransform.placeVertex(
                        model.previewTriangles[triangleIndex + 3],
                        model.previewTriangles[triangleIndex + 4],
                        model.previewTriangles[triangleIndex + 5],
                        objectGeometry,
                        bedSizeX,
                        bedSizeY,
                        minimumRotatedZ,
                    )
                    val cPosition = modelTransform.placeVertex(
                        model.previewTriangles[triangleIndex + 6],
                        model.previewTriangles[triangleIndex + 7],
                        model.previewTriangles[triangleIndex + 8],
                        objectGeometry,
                        bedSizeX,
                        bedSizeY,
                        minimumRotatedZ,
                    )
                    val a = project(aPosition[0], aPosition[1], aPosition[2])
                    val b = project(bPosition[0], bPosition[1], bPosition[2])
                    val c = project(cPosition[0], cPosition[1], cPosition[2])
                    val sourceFacetIndex = model.previewTriangleIndices
                        .getOrElse(triangleIndex / 9) { triangleIndex / 9 }
                    val screenTriangle = ModelScreenTriangle(
                        sourceFacetIndex = sourceFacetIndex,
                        previewTriangleIndex = triangleIndex / 9,
                        a = a,
                        b = b,
                        c = c,
                        depth = (
                            cameraDepth(aPosition[0], aPosition[1], aPosition[2]) +
                                cameraDepth(bPosition[0], bPosition[1], bPosition[2]) +
                                cameraDepth(cPosition[0], cPosition[1], cPosition[2])
                            ) / 3f,
                        surfaceShade = modelSurfaceShade(aPosition, bPosition, cPosition),
                        volumeId = volume.id,
                        filamentSlot = volume.filamentSlot,
                        volumeRole = volume.role,
                    )
                    screenTriangles += screenTriangle
                    volumeScreenTriangles += screenTriangle
                    minimumModelDepth = min(minimumModelDepth, screenTriangle.depth)
                    maximumModelDepth = max(maximumModelDepth, screenTriangle.depth)
                    minimumScreenX = min(minimumScreenX, min(a.x, min(b.x, c.x)))
                    minimumScreenY = min(minimumScreenY, min(a.y, min(b.y, c.y)))
                    maximumScreenX = max(maximumScreenX, max(a.x, max(b.x, c.x)))
                    maximumScreenY = max(maximumScreenY, max(a.y, max(b.y, c.y)))
                    when (volume.supportPaint.facets[sourceFacetIndex]) {
                        SupportPaintState.ENFORCE -> enforcePaintPath.addTriangle(a, b, c)
                        SupportPaintState.BLOCK -> blockPaintPath.addTriangle(a, b, c)
                        null -> Unit
                    }
                    when (volume.seamPaint.facets[sourceFacetIndex]) {
                        SeamPaintState.ENFORCE -> seamEnforcePaintPath.addTriangle(a, b, c)
                        SeamPaintState.BLOCK -> seamBlockPaintPath.addTriangle(a, b, c)
                        null -> Unit
                    }
                    volume.multiColorPaint.facets[sourceFacetIndex]?.let { filamentSlot ->
                        multiColorPaintPaths.getOrPut(filamentSlot, ::Path).addTriangle(a, b, c)
                    }
                    triangleIndex += 9
                    }
                    screenTrianglesByVolume[volume.id] = volumeScreenTriangles
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
                val minimumDepth = minimumModelDepth.takeIf(Float::isFinite) ?: 0f
                val maximumDepth = maximumModelDepth.takeIf(Float::isFinite) ?: 0f
                val depthSpan = (maximumDepth - minimumDepth).coerceAtLeast(0.0001f)
                val facePaths = HashMap<ModelFaceBucket, Path>()
                screenTriangles.forEach { triangle ->
                    val depthBand = (
                        (triangle.depth - minimumDepth) / depthSpan * (ModelFaceDepthBands - 1)
                        ).roundToInt().coerceIn(0, ModelFaceDepthBands - 1)
                    val shadeBand = (
                        triangle.surfaceShade * (ModelFaceShadeBands - 1)
                        ).roundToInt().coerceIn(0, ModelFaceShadeBands - 1)
                    val bucket = ModelFaceBucket(
                        depthBand,
                        triangle.filamentSlot,
                        shadeBand,
                        triangle.volumeRole,
                    )
                    facePaths.getOrPut(bucket, ::Path).addTriangle(
                        triangle.a,
                        triangle.b,
                        triangle.c,
                    )
                }
                facePaths.entries
                    .sortedWith(
                        compareBy<Map.Entry<ModelFaceBucket, Path>>(
                            { it.key.depthBand },
                            { it.key.filamentSlot },
                            { it.key.volumeRole.nativeValue },
                            { it.key.shadeBand },
                        ),
                    )
                    .forEach { (bucket, path) ->
                    val surfaceShade = bucket.shadeBand.toFloat() / (ModelFaceShadeBands - 1)
                    val light = if (objectSelected) {
                        0.78f + surfaceShade * 0.16f
                    } else {
                        0.70f + surfaceShade * 0.18f
                    }
                    drawPath(
                        path,
                        lerp(
                            Color(0xFF11130F),
                            projectVolumeColor(bucket.volumeRole, bucket.filamentSlot),
                            light.coerceIn(0f, 1f),
                        )
                            .copy(
                                alpha = if (bucket.volumeRole == ProjectVolumeRole.MODEL_PART) {
                                    0.98f
                                } else {
                                    0.52f
                                },
                            ),
                    )
                    }
                if (projectObject.id == layOnFaceObjectId) {
                    projectObject.modelPartVolumes.forEach { volume ->
                        val volumeTriangles = screenTrianglesByVolume[volume.id].orEmpty()
                        layOnFaceCandidates[volume.id].orEmpty().forEach { candidate ->
                            val candidatePath = Path()
                            candidate.previewTriangleIndices.forEach { triangleIndex ->
                                volumeTriangles.getOrNull(triangleIndex)?.let { triangle ->
                                    candidatePath.addTriangle(triangle.a, triangle.b, triangle.c)
                                }
                            }
                            drawPath(candidatePath, WorkspaceYellow.copy(alpha = 0.16f))
                            drawPath(
                                candidatePath,
                                WorkspaceYellow.copy(alpha = 0.86f),
                                style = Stroke(1.1.dp.toPx()),
                            )
                        }
                    }
                }
                val featureEdgePath = Path()
                projectObject.volumes.forEach { volume ->
                    val volumeTriangles = screenTrianglesByVolume[volume.id].orEmpty()
                    modelMeshEdges[projectObject.id to volume.id].orEmpty().forEach { edge ->
                        val triangle = volumeTriangles.getOrNull(edge.triangleIndex)
                            ?: return@forEach
                        val adjacent = edge.adjacentTriangleIndex?.let(volumeTriangles::getOrNull)
                        val silhouette = adjacent == null ||
                            triangle.screenOrientation() * adjacent.screenOrientation() < 0f
                        if (!edge.sharp && !silhouette) return@forEach
                        val start = triangle.vertex(edge.startVertex)
                        val end = triangle.vertex(edge.endVertex)
                        featureEdgePath.moveTo(start.x, start.y)
                        featureEdgePath.lineTo(end.x, end.y)
                    }
                }
                drawPath(
                    featureEdgePath,
                    color = Color(0xFF12130F).copy(alpha = if (objectSelected) 0.34f else 0.22f),
                    style = Stroke(if (objectSelected) 0.7.dp.toPx() else 0.55.dp.toPx()),
                )
                multiColorPaintPaths.toSortedMap().forEach { (filamentSlot, path) ->
                    drawPath(path, filamentSlotColor(filamentSlot).copy(alpha = 0.94f))
                    drawPath(
                        path,
                        Color.Black.copy(alpha = 0.62f),
                        style = Stroke(0.9.dp.toPx()),
                    )
                }
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
                drawPath(seamEnforcePaintPath, Color(0xFF4CC9F0).copy(alpha = 0.9f))
                drawPath(
                    seamEnforcePaintPath,
                    Color(0xFF153B4A),
                    style = Stroke(1.2.dp.toPx()),
                )
                drawPath(seamBlockPaintPath, Color(0xFFFF9F43).copy(alpha = 0.9f))
                drawPath(
                    seamBlockPaintPath,
                    Color(0xFF563217),
                    style = Stroke(1.2.dp.toPx()),
                )
                if (projectObject.id == brimEditObjectId) {
                    brimPoints.points.forEachIndexed { index, brimPoint ->
                        val world = modelTransform.placeBrimPoint(
                            brimPoint,
                            projectObject,
                            bedSizeX,
                            bedSizeY,
                        )
                        val center = project(world[0], world[1], world[2])
                        nextBrimPositions[index] = center
                        val selected = index == selectedBrimPointIndex
                        val radius = max(7.dp.toPx(), brimPoint.radiusMm * sceneScale)
                        drawCircle(
                            color = WorkspaceYellow.copy(alpha = if (selected) 0.28f else 0.16f),
                            radius = radius,
                            center = center,
                        )
                        drawCircle(
                            color = if (selected) WorkspaceYellow else Color(0xFFF4F4EE),
                            radius = radius,
                            center = center,
                            style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx()),
                        )
                        drawCircle(Color.Black.copy(alpha = 0.9f), 6.dp.toPx(), center)
                        drawCircle(
                            if (world[2] <= 0.05f) WorkspaceYellow else Color(0xFFFF6B6B),
                            3.5.dp.toPx(),
                            center,
                        )
                    }
                }
            }
            if (measureObjectId != null && measurementPoints.isNotEmpty()) {
                val projectedPoints = measurementPoints.take(2).map { point ->
                    project(point.x, point.y, point.z)
                }
                if (projectedPoints.size == 2) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.86f),
                        start = projectedPoints[0],
                        end = projectedPoints[1],
                        strokeWidth = 4.5.dp.toPx(),
                    )
                    drawLine(
                        color = WorkspaceYellow,
                        start = projectedPoints[0],
                        end = projectedPoints[1],
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                projectedPoints.forEachIndexed { index, point ->
                    drawCircle(Color.Black.copy(alpha = 0.88f), 7.dp.toPx(), point)
                    drawCircle(
                        if (index == 0) WorkspaceYellow else Color(0xFFF4F4EE),
                        4.dp.toPx(),
                        point,
                    )
                }
            }
            if (modelScreenBounds != nextBounds) modelScreenBounds = nextBounds
            if (modelScreenTriangles != nextScreenTriangles) modelScreenTriangles = nextScreenTriangles
            if (brimPointScreenPositions != nextBrimPositions) {
                brimPointScreenPositions = nextBrimPositions
            }
        }
        }
    }
}

private fun Path.addTriangle(a: Offset, b: Offset, c: Offset) {
    moveTo(a.x, a.y)
    lineTo(b.x, b.y)
    lineTo(c.x, c.y)
    close()
}

private fun ModelScreenTriangle.vertex(index: Int): Offset = when (index) {
    0 -> a
    1 -> b
    2 -> c
    else -> error("model_vertex_invalid")
}

private fun ModelScreenTriangle.screenOrientation(): Float =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

internal fun modelSurfaceShade(a: FloatArray, b: FloatArray, c: FloatArray): Float {
    require(a.size >= 3 && b.size >= 3 && c.size >= 3)
    val ux = b[0] - a[0]
    val uy = b[1] - a[1]
    val uz = b[2] - a[2]
    val vx = c[0] - a[0]
    val vy = c[1] - a[1]
    val vz = c[2] - a[2]
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx
    val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
    if (!length.isFinite() || length <= 0.000001f) return 0.45f
    val diffuse = abs(nx * 0.36f + ny * -0.48f + nz * 0.80f) / length
    return (0.55f + diffuse.coerceIn(0f, 1f) * 0.45f).coerceIn(0f, 1f)
}

internal fun closestPaintFacet(
    triangles: List<ModelScreenTriangle>,
    point: Offset,
    brushRadius: Float,
): Int? = closestModelTriangle(triangles, point, brushRadius)?.sourceFacetIndex

internal fun closestModelTriangle(
    triangles: List<ModelScreenTriangle>,
    point: Offset,
    touchRadius: Float,
): ModelScreenTriangle? {
    val inside = triangles.filter { triangle -> pointInsideTriangle(point, triangle) }
    if (inside.isNotEmpty()) return inside.maxByOrNull(ModelScreenTriangle::depth)
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
        .filter { (_, distance) -> distance <= touchRadius }
        .minWithOrNull(
            compareBy<Pair<ModelScreenTriangle, Float>> { it.second }
                .thenByDescending { it.first.depth },
        )
        ?.first
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

private data class TriangleWeights(
    val a: Float,
    val b: Float,
    val c: Float,
)

internal fun modelSurfacePoint(
    triangle: ModelScreenTriangle,
    point: Offset,
    worldA: FloatArray,
    worldB: FloatArray,
    worldC: FloatArray,
): ModelPoint3? {
    if (worldA.size < 3 || worldB.size < 3 || worldC.size < 3) return null
    if (
        worldA.take(3).any { !it.isFinite() } ||
        worldB.take(3).any { !it.isFinite() } ||
        worldC.take(3).any { !it.isFinite() }
    ) {
        return null
    }
    val weights = triangleWeights(triangle, point)
    val result = ModelPoint3(
        x = worldA[0] * weights.a + worldB[0] * weights.b + worldC[0] * weights.c,
        y = worldA[1] * weights.a + worldB[1] * weights.b + worldC[1] * weights.c,
        z = worldA[2] * weights.a + worldB[2] * weights.b + worldC[2] * weights.c,
    )
    return result.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
}

private fun triangleWeights(triangle: ModelScreenTriangle, point: Offset): TriangleWeights {
    val denominator =
        (triangle.b.y - triangle.c.y) * (triangle.a.x - triangle.c.x) +
            (triangle.c.x - triangle.b.x) * (triangle.a.y - triangle.c.y)
    if (abs(denominator) > 0.000001f && pointInsideTriangle(point, triangle)) {
        val a = (
            (triangle.b.y - triangle.c.y) * (point.x - triangle.c.x) +
                (triangle.c.x - triangle.b.x) * (point.y - triangle.c.y)
            ) / denominator
        val b = (
            (triangle.c.y - triangle.a.y) * (point.x - triangle.c.x) +
                (triangle.a.x - triangle.c.x) * (point.y - triangle.c.y)
            ) / denominator
        val c = 1f - a - b
        if (a.isFinite() && b.isFinite() && c.isFinite()) {
            return TriangleWeights(a, b, c)
        }
    }

    data class EdgeCandidate(val distance: Float, val weights: TriangleWeights)

    fun edge(start: Offset, end: Offset, startIndex: Int, endIndex: Int): EdgeCandidate {
        val segment = end - start
        val lengthSquared = segment.x * segment.x + segment.y * segment.y
        val position = if (lengthSquared <= 0.000001f) {
            0f
        } else {
            val offset = point - start
            ((offset.x * segment.x + offset.y * segment.y) / lengthSquared).coerceIn(0f, 1f)
        }
        val closest = start + segment * position
        val values = FloatArray(3)
        values[startIndex] = 1f - position
        values[endIndex] = position
        return EdgeCandidate(
            distance = (point - closest).getDistance(),
            weights = TriangleWeights(values[0], values[1], values[2]),
        )
    }

    return listOf(
        edge(triangle.a, triangle.b, 0, 1),
        edge(triangle.b, triangle.c, 1, 2),
        edge(triangle.c, triangle.a, 2, 0),
    ).minBy(EdgeCandidate::distance).weights
}

internal fun measurementBetween(first: ModelPoint3, second: ModelPoint3): ModelMeasurement? {
    val deltaX = abs(second.x - first.x)
    val deltaY = abs(second.y - first.y)
    val deltaZ = abs(second.z - first.z)
    val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
    return ModelMeasurement(distance, deltaX, deltaY, deltaZ).takeIf {
        it.distanceMm.isFinite() && it.deltaXmm.isFinite() &&
            it.deltaYmm.isFinite() && it.deltaZmm.isFinite()
    }
}

internal fun nextMeasurementPoints(
    current: List<ModelPoint3>,
    point: ModelPoint3,
): List<ModelPoint3> {
    if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return current.take(2)
    return if (current.size >= 2) listOf(point) else (current + point).take(2)
}

@Composable
private fun BrimEarPalette(
    projectObject: ProjectObject,
    points: BrimPoints,
    selectedIndex: Int?,
    addMode: Boolean,
    message: String?,
    bedSizeX: Float,
    bedSizeY: Float,
    bedPolygon: List<Float>,
    onAddModeChanged: (Boolean) -> Unit,
    onPointSelected: (Int?) -> Unit,
    onPointsChanged: (BrimPoints, Int?) -> Unit,
    onInvalid: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveBedPolygon = remember(bedPolygon, bedSizeX, bedSizeY) {
        bedPolygon.takeIf { bedPolygonIsValid(it, bedSizeX, bedSizeY) }
            ?: rectangularBedPolygon(bedSizeX, bedSizeY)
    }
    val footprint = remember(projectObject, bedSizeX, bedSizeY) {
        projectObject.placedModelFootprint(bedSizeX, bedSizeY)
    }
    val selectedPoint = selectedIndex?.let(points.points::getOrNull)
    val stringResourceBrimRadius = stringResource(R.string.brim_radius)
    val stringResourceBrimRadiusValue = stringResource(
        R.string.millimeters_value_precise,
        selectedPoint?.radiusMm ?: BrimPoint.DEFAULT_RADIUS_MM,
    )
    val stringResourceAddModeState = stringResource(
        if (addMode) R.string.object_setting_on else R.string.object_setting_off,
    )

    fun addDefaultPoint() {
        if (points.points.size >= BrimPoints.MAX_POINTS) {
            onInvalid()
            return
        }
        val candidate = projectObject.defaultManualBrimPoint(bedSizeX, bedSizeY)
        if (candidate == null) {
            onInvalid()
            return
        }
        val placed = projectObject.transform.placeBrimPoint(
            candidate,
            projectObject,
            bedSizeX,
            bedSizeY,
        )
        if (!pointInsideBedPolygon(placed[0], placed[1], effectiveBedPolygon)) {
            onInvalid()
            return
        }
        val existing = points.points.indexOfFirst { point ->
            abs(point.xMm - candidate.xMm) < 0.01f &&
                abs(point.yMm - candidate.yMm) < 0.01f &&
                abs(point.zMm - candidate.zMm) < 0.01f
        }
        if (existing >= 0) {
            onPointSelected(existing)
        } else {
            val next = BrimPoints(points.points + candidate)
            onPointsChanged(next, next.points.lastIndex)
        }
        onAddModeChanged(false)
    }

    fun moveSelected(worldDeltaX: Float, worldDeltaY: Float) {
        val index = selectedIndex ?: return
        val current = points.points.getOrNull(index) ?: return
        val placed = projectObject.transform.placeBrimPoint(
            current,
            projectObject,
            bedSizeX,
            bedSizeY,
        )
        val targetX = placed[0] + worldDeltaX
        val targetY = placed[1] + worldDeltaY
        if (!pointInsideBedPolygon(targetX, targetY, effectiveBedPolygon)) {
            onInvalid()
            return
        }
        val candidate = projectObject.transform.manualBrimPointAtBed(
            projectObject,
            targetX,
            targetY,
            bedSizeX,
            bedSizeY,
            current.radiusMm,
            footprint,
        )
        if (candidate == null) {
            onInvalid()
            return
        }
        onPointsChanged(
            BrimPoints(points.points.toMutableList().apply { this[index] = candidate }),
            index,
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(0.96f).widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.92f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.manual_brim_ears),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.brim_point_count, points.points.size),
                    color = Color(0xFFC8C9C2),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                message ?: stringResource(R.string.brim_point_hint),
                color = if (message == null) Color(0xFFC8C9C2) else Color(0xFFFFC66D),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick = { onAddModeChanged(!addMode) },
                    enabled = points.points.size < BrimPoints.MAX_POINTS,
                    modifier = Modifier.semantics {
                        selected = addMode
                        stateDescription = stringResourceAddModeState
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (addMode) WorkspaceYellow else Color(0xFF3A3B37),
                        contentColor = if (addMode) WorkspaceBlack else Color(0xFFF4F4EE),
                    ),
                ) {
                    Icon(Icons.Default.AddBox, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.brim_add_mode))
                }
                TextButton(
                    onClick = ::addDefaultPoint,
                    enabled = points.points.size < BrimPoints.MAX_POINTS,
                ) {
                    Text(stringResource(R.string.brim_add_automatic))
                }
                IconButton(
                    onClick = {
                        if (points.points.isNotEmpty()) {
                            onPointSelected(
                                ((selectedIndex ?: 0) - 1 + points.points.size) % points.points.size,
                            )
                        }
                    },
                    enabled = points.points.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.brim_previous_point))
                }
                IconButton(
                    onClick = {
                        if (points.points.isNotEmpty()) {
                            onPointSelected(((selectedIndex ?: -1) + 1) % points.points.size)
                        }
                    },
                    enabled = points.points.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.brim_next_point))
                }
                IconButton(
                    onClick = {
                        val index = selectedIndex ?: return@IconButton
                        val next = points.points.toMutableList().apply { removeAt(index) }
                        onPointsChanged(
                            BrimPoints(next),
                            next.indices.lastOrNull()?.coerceAtMost(index),
                        )
                    },
                    enabled = selectedPoint != null,
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        stringResource(R.string.brim_remove_point),
                        tint = Color(0xFFFF8A80),
                    )
                }
            }
            if (selectedPoint != null) {
                Slider(
                    value = selectedPoint.radiusMm,
                    onValueChange = { value ->
                        val index = requireNotNull(selectedIndex)
                        val radius = (value * 2f).roundToInt() / 2f
                        onPointsChanged(
                            BrimPoints(
                                points.points.toMutableList().apply {
                                    this[index] = selectedPoint.copy(radiusMm = radius)
                                },
                            ),
                            index,
                        )
                    },
                    valueRange = BrimPoint.MIN_RADIUS_MM..BrimPoint.MAX_RADIUS_MM,
                    steps = 14,
                    modifier = Modifier.semantics {
                        contentDescription = stringResourceBrimRadius
                        stateDescription = stringResourceBrimRadiusValue
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = WorkspaceYellow,
                        activeTrackColor = WorkspaceYellow,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { moveSelected(-0.5f, 0f) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.brim_move_left))
                    }
                    IconButton(onClick = { moveSelected(0f, 0.5f) }) {
                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.brim_move_up))
                    }
                    IconButton(onClick = { moveSelected(0f, -0.5f) }) {
                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.brim_move_down))
                    }
                    IconButton(onClick = { moveSelected(0.5f, 0f) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.brim_move_right))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(0.3f).heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(0.7f).heightIn(min = 52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WorkspaceYellow,
                        contentColor = WorkspaceBlack,
                    ),
                ) {
                    Text(stringResource(R.string.apply_changes))
                }
            }
        }
    }
}

@Composable
private fun MeasurePalette(
    points: List<ModelPoint3>,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurement = points.takeIf { it.size == 2 }
        ?.let { measurementBetween(it[0], it[1]) }
    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.9f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Straighten, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.measure_model),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onClear, enabled = points.isNotEmpty()) {
                    Text(stringResource(R.string.measure_clear))
                }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.done), color = WorkspaceYellow)
                }
            }
            when {
                measurement != null -> {
                    Text(
                        stringResource(R.string.measure_distance, measurement.distanceMm),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = WorkspaceYellow,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(
                            R.string.measure_axes,
                            measurement.deltaXmm,
                            measurement.deltaYmm,
                            measurement.deltaZmm,
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = Color(0xFFC8C9C2),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                points.size == 1 -> Text(
                    stringResource(R.string.measure_second_point),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFFC8C9C2),
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Text(
                    stringResource(R.string.measure_hint),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFFC8C9C2),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LayOnFacePalette(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.9f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.VerticalAlignBottom, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.lay_on_face),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.cancel), color = WorkspaceYellow)
                }
            }
            Text(
                stringResource(R.string.lay_on_face_hint),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
private fun SeamPaintPalette(
    selectedTool: SeamPaintTool,
    onToolSelected: (SeamPaintTool) -> Unit,
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
                SeamPaintTool.entries.forEach { tool ->
                    TextButton(
                        onClick = { onToolSelected(tool) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selectedTool == tool) {
                                when (tool) {
                                    SeamPaintTool.ENFORCE -> Color(0xFF205B70)
                                    SeamPaintTool.BLOCK -> Color(0xFF75451D)
                                    SeamPaintTool.ERASE -> Color(0xFF555752)
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
                stringResource(R.string.seam_paint_hint),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MultiColorPaintPalette(
    filaments: List<FilamentProfile>,
    selectedSlot: Int?,
    onSlotSelected: (Int?) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.9f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.paint_color),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.done), color = WorkspaceYellow)
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                filaments.forEachIndexed { index, filament ->
                    val filamentLabel = profileLabel(filament)
                    TextButton(
                        onClick = { onSlotSelected(index) },
                        modifier = Modifier.widthIn(min = 72.dp).semantics {
                            contentDescription = "T${index + 1} · $filamentLabel"
                            selected = selectedSlot == index
                        },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selectedSlot == index) {
                                filamentSlotColor(index).copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            },
                            contentColor = Color(0xFFF4F4EE),
                        ),
                    ) {
                        Surface(
                            modifier = Modifier.size(14.dp),
                            color = filamentSlotColor(index),
                            shape = RoundedCornerShape(50),
                        ) {}
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                R.string.filament_tool_summary,
                                index + 1,
                                filamentLabel,
                            ),
                        )
                    }
                }
                TextButton(
                    onClick = { onSlotSelected(null) },
                    modifier = Modifier.semantics { selected = selectedSlot == null },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selectedSlot == null) Color(0xFF555752)
                        else Color.Transparent,
                        contentColor = Color(0xFFF4F4EE),
                    ),
                ) {
                    Text(stringResource(R.string.color_paint_erase))
                }
            }
            Text(
                stringResource(R.string.color_paint_hint),
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
    canAutoLay: Boolean,
    onLayOnFace: () -> Unit,
    onMeasure: () -> Unit,
    onBrimEars: () -> Unit,
    autoLaying: Boolean,
    editingBusy: Boolean,
    canPaintColor: Boolean,
    onMultiColorPaint: () -> Unit,
    onSupportPaint: () -> Unit,
    onMore: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(0.96f).widthIn(max = 560.dp),
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
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
            IconButton(onClick = onMore, enabled = !editingBusy) {
                Icon(Icons.Default.Tune, stringResource(R.string.more_settings))
            }
            IconButton(onClick = onSupportPaint, enabled = !editingBusy) {
                Icon(Icons.Default.Brush, stringResource(R.string.paint_support))
            }
            IconButton(onClick = onAutoLay, enabled = canAutoLay && !editingBusy) {
                if (autoLaying) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AutoFixHigh, stringResource(R.string.auto_lay))
                }
            }
            IconButton(onClick = onLayOnFace, enabled = !editingBusy) {
                Icon(Icons.Default.VerticalAlignBottom, stringResource(R.string.lay_on_face))
            }
            IconButton(onClick = onMeasure, enabled = !editingBusy) {
                Icon(Icons.Default.Straighten, stringResource(R.string.measure_model))
            }
            IconButton(onClick = onBrimEars, enabled = !editingBusy) {
                Icon(Icons.Default.AddBox, stringResource(R.string.manual_brim_ears))
            }
            IconButton(
                onClick = onMultiColorPaint,
                enabled = canPaintColor && !editingBusy,
            ) {
                Icon(Icons.Default.Palette, stringResource(R.string.paint_color))
            }
            IconButton(onClick = onRemove, enabled = !editingBusy) {
                Icon(Icons.Default.DeleteOutline, stringResource(R.string.remove_model), tint = Color(0xFFFF8A80))
            }
        }
    }
}

@Composable
private fun SliceSheet(
    modelDimensions: List<Float>?,
    options: SliceOptions,
    catalog: ProfileCatalog,
    recents: ProfileRecents,
    profileBusy: Boolean,
    importing: Boolean,
    projectEditActive: Boolean,
    projectEditCancellationRequested: Boolean,
    previewLoading: Boolean,
    slicing: Boolean,
    cancellationRequested: Boolean,
    progress: Int,
    error: String?,
    notice: String?,
    onSlice: () -> Unit,
    onCancelSlice: () -> Unit,
    onCancelProjectEdit: () -> Unit,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinter: (String, SliceOptions) -> Unit,
    onSaveFilament: (String, SliceOptions, Int) -> Unit,
    onSaveSlicing: (String, SliceOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(modifier) {
        ProfileSettings(
            options = options,
            catalog = catalog,
            recents = recents,
            enabled = !profileBusy && !slicing && !importing && !previewLoading,
            onOptionsChanged = onOptionsChanged,
            onSavePrinter = onSavePrinter,
            onSaveFilament = onSaveFilament,
            onSaveSlicing = onSaveSlicing,
        )
        if (modelDimensions != null) {
            Text(
                modelDimensions.joinToString(" × ") {
                    String.format(Locale.getDefault(), "%.1f", it)
                } + " mm",
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(stringResource(R.string.import_from_menu), color = Color(0xFFC8C9C2))
        }
        if (error != null) Text(error, color = Color(0xFFFF8A80))
        if (notice != null) Text(notice, color = WorkspaceYellow)
        if (projectEditActive) {
            Text(stringResource(R.string.editing_model), fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = WorkspaceYellow,
            )
            TextButton(
                onClick = onCancelProjectEdit,
                enabled = !projectEditCancellationRequested,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (projectEditCancellationRequested) {
                            R.string.canceling_model_edit
                        } else {
                            R.string.cancel_model_edit
                        },
                    ),
                )
            }
        }
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
        if (modelDimensions != null) {
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
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (outcome == null) {
                Text(stringResource(R.string.preview_requires_slice), fontWeight = FontWeight.SemiBold)
                Button(onClick = onGoToSlice, colors = primaryButtonColors()) {
                    Text(stringResource(R.string.tab_slice))
                }
                return@Column
            }
            PreviewSummaryHeader(
                summary = outcome.previewSummary(),
                expanded = expanded,
                loading = loading,
                onToggle = { onExpandedChanged(!expanded) },
            )
            if (error != null) Text(error, color = Color(0xFFFF8A80))
            if (expanded) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (loading || preview == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(22.dp),
                                color = WorkspaceYellow,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.loading_preview))
                        }
                    }
                    if (preview != null) {
                        PreviewControls(
                            preview = preview,
                            toolpathOpacity = toolpathOpacity,
                            onToolpathOpacityChanged = onToolpathOpacityChanged,
                            toolpathDepthContrast = toolpathDepthContrast,
                            onToolpathDepthContrastChanged = onToolpathDepthContrastChanged,
                            visibleToolpathRoles = visibleToolpathRoles,
                            onToolpathRoleVisibilityChanged = onToolpathRoleVisibilityChanged,
                            onLayerRangeSelected = onLayerRangeSelected,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSummaryHeader(
    summary: PreviewSummary,
    expanded: Boolean,
    loading: Boolean,
    onToggle: () -> Unit,
) {
    val durationText = when {
        summary.duration.underOneMinute -> stringResource(R.string.duration_under_one_minute)
        summary.duration.hours > 0 -> stringResource(
            R.string.duration_hours_minutes,
            summary.duration.hours,
            summary.duration.minutes,
        )
        else -> stringResource(R.string.duration_minutes, summary.duration.minutes)
    }
    val toggleDescription = stringResource(
        if (expanded) R.string.collapse_preview_controls else R.string.expand_preview_controls,
    )
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = Color(0xFFF4F4EE),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = stringResource(R.string.estimated_print_time),
                modifier = Modifier.size(18.dp),
                tint = WorkspaceYellow,
            )
            Text(
                durationText,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Straighten,
                contentDescription = stringResource(R.string.filament_usage),
                modifier = Modifier.size(18.dp),
                tint = WorkspaceYellow,
            )
            Text(
                stringResource(
                    R.string.filament_usage_compact,
                    summary.filamentGrams,
                    summary.filamentMeters,
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFFE2E3DD),
                style = MaterialTheme.typography.labelLarge,
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = WorkspaceYellow,
                    strokeWidth = 2.dp,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = toggleDescription,
                tint = Color(0xFFE2E3DD),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PreviewControls(
    preview: GcodeLayerPreview,
    toolpathOpacity: Float,
    onToolpathOpacityChanged: (Float) -> Unit,
    toolpathDepthContrast: Float,
    onToolpathDepthContrastChanged: (Float) -> Unit,
    visibleToolpathRoles: Set<Int>,
    onToolpathRoleVisibilityChanged: (Int, Boolean) -> Unit,
    onLayerRangeSelected: (Int, Int) -> Unit,
) {
    val lastLayerIndex = (preview.layerCount - 1).coerceAtLeast(0)
    val safeStartLayer = preview.startLayer.coerceIn(0, lastLayerIndex)
    val safeEndLayer = preview.endLayer.coerceIn(safeStartLayer, lastLayerIndex)
    var selectedRange by remember(safeStartLayer, safeEndLayer, preview.layerCount) {
        mutableStateOf(safeStartLayer.toFloat()..safeEndLayer.toFloat())
    }
    val rangeColors = duckySliderColors()
    val toolpathVisibilityLabel = stringResource(R.string.toolpath_visibility_control)
    val toolpathVisibilityState = stringResource(
        R.string.percent_value,
        (toolpathOpacity * 100).roundToInt(),
    )
    val toolpathDepthLabel = stringResource(R.string.toolpath_depth_contrast_control)
    val toolpathDepthState = stringResource(
        R.string.percent_value,
        (toolpathDepthContrast * 100).roundToInt(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            val startLayerLabel = stringResource(R.string.first_visible_layer)
            val endLayerLabel = stringResource(R.string.last_visible_layer)
            val startLayerState = stringResource(
                R.string.layer_position,
                selectedRange.start.roundToInt() + 1,
                preview.layerCount,
            )
            val endLayerState = stringResource(
                R.string.layer_position,
                selectedRange.endInclusive.roundToInt() + 1,
                preview.layerCount,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                RangeSlider(
                    value = selectedRange,
                    onValueChange = { selectedRange = it },
                    onValueChangeFinished = {
                        onLayerRangeSelected(
                            selectedRange.start.roundToInt(),
                            selectedRange.endInclusive.roundToInt(),
                        )
                    },
                    modifier = Modifier.clearAndSetSemantics { },
                    valueRange = 0f..lastLayerIndex.toFloat(),
                    colors = rangeColors,
                    steps = 0,
                )

                val accessibilityThumbSize = 48.dp
                val accessibilityTrackWidth = (maxWidth - accessibilityThumbSize).coerceAtLeast(0.dp)
                val rangeMaximum = lastLayerIndex.toFloat()
                val startFraction = (selectedRange.start / rangeMaximum).coerceIn(0f, 1f)
                val endFraction = (selectedRange.endInclusive / rangeMaximum).coerceIn(0f, 1f)

                Box(
                    Modifier
                        .offset(x = accessibilityTrackWidth * startFraction)
                        .size(accessibilityThumbSize)
                        .semantics {
                            contentDescription = startLayerLabel
                            stateDescription = startLayerState
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = selectedRange.start,
                                range = 0f..rangeMaximum,
                            )
                            setProgress { requestedValue ->
                                val nextStart = requestedValue
                                    .roundToInt()
                                    .coerceIn(0, selectedRange.endInclusive.roundToInt())
                                    .toFloat()
                                selectedRange = nextStart..selectedRange.endInclusive
                                onLayerRangeSelected(
                                    nextStart.roundToInt(),
                                    selectedRange.endInclusive.roundToInt(),
                                )
                                true
                            }
                        }
                        .focusable(),
                )
                Box(
                    Modifier
                        .offset(x = accessibilityTrackWidth * endFraction)
                        .size(accessibilityThumbSize)
                        .semantics {
                            contentDescription = endLayerLabel
                            stateDescription = endLayerState
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = selectedRange.endInclusive,
                                range = 0f..rangeMaximum,
                            )
                            setProgress { requestedValue ->
                                val nextEnd = requestedValue
                                    .roundToInt()
                                    .coerceIn(selectedRange.start.roundToInt(), lastLayerIndex)
                                    .toFloat()
                                selectedRange = selectedRange.start..nextEnd
                                onLayerRangeSelected(
                                    selectedRange.start.roundToInt(),
                                    nextEnd.roundToInt(),
                                )
                                true
                            }
                        }
                        .focusable(),
                )
            }
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
                    modifier = Modifier.semantics {
                        contentDescription = toolpathVisibilityLabel
                        stateDescription = toolpathVisibilityState
                    },
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
                    modifier = Modifier.semantics {
                        contentDescription = toolpathDepthLabel
                        stateDescription = toolpathDepthState
                    },
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
                            .heightIn(min = 48.dp)
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

@Composable
private fun ProjectSheet(
    objects: List<ProjectObject>,
    selectedObjectId: String?,
    outcome: SliceOutcome?,
    busy: Boolean,
    importing: Boolean,
    exporting: Boolean,
    cancellationRequested: Boolean,
    onObjectSelected: (String) -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onCancelProjectImport: () -> Unit,
    onCancelProjectExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReplacement by remember { mutableStateOf(false) }
    WorkspaceCard(modifier) {
        Text(
            stringResource(R.string.tab_project),
            modifier = Modifier.semantics { heading() },
            fontWeight = FontWeight.Bold,
        )
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
                    projectObject.primaryModelPart.model.fileName,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    when {
                        importing -> onCancelProjectImport()
                        objects.isEmpty() -> onOpenProject()
                        else -> confirmReplacement = true
                    }
                },
                enabled = if (importing) !cancellationRequested else !busy && !exporting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF454640),
                    contentColor = Color(0xFFF4F4EE),
                ),
                modifier = Modifier.weight(1f),
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFFF4F4EE),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            cancellationRequested && importing -> R.string.canceling_project_import
                            importing -> R.string.cancel_project_import
                            else -> R.string.open_project
                        },
                    ),
                )
            }
            Button(
                onClick = {
                    if (exporting) onCancelProjectExport() else onSaveProject()
                },
                enabled = if (exporting) !cancellationRequested else !busy && !importing,
                colors = primaryButtonColors(),
                modifier = Modifier.weight(1f),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = WorkspaceBlack,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.SaveAlt, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            cancellationRequested -> R.string.canceling_project_export
                            exporting -> R.string.cancel_project_export
                            else -> R.string.save_project
                        },
                    ),
                )
            }
        }
    }
    if (confirmReplacement) {
        ProjectReplacementDialog(
            onConfirm = {
                confirmReplacement = false
                onOpenProject()
            },
            onDismiss = { confirmReplacement = false },
        )
    }
}

@Composable
internal fun ProjectReplacementDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.replace_project_title)) },
        text = { Text(stringResource(R.string.replace_project_body)) },
        confirmButton = {
            Button(onClick = onConfirm, colors = primaryButtonColors()) {
                Text(stringResource(R.string.open_project))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
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
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
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

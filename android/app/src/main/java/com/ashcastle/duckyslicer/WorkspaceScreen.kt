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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.PI
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

private data class ToolpathStyle(
    val code: Int,
    val label: Int,
    val color: Color,
    val widthDp: Float,
)

private val ToolpathStyles = listOf(
    ToolpathStyle(0, R.string.toolpath_outer_wall, Color(0xFFFFCF40), 2.3f),
    ToolpathStyle(1, R.string.toolpath_inner_wall, Color(0xFF44D7FF), 1.9f),
    ToolpathStyle(2, R.string.toolpath_infill, Color(0xFF668BFF), 1.35f),
    ToolpathStyle(3, R.string.toolpath_solid, Color(0xFFE879F9), 1.65f),
    ToolpathStyle(4, R.string.toolpath_support, Color(0xFF5EE6A8), 1.45f),
    ToolpathStyle(5, R.string.toolpath_bridge, Color(0xFFFF6B6B), 2.1f),
    ToolpathStyle(6, R.string.toolpath_adhesion, Color(0xFFFF9F43), 1.8f),
    ToolpathStyle(7, R.string.toolpath_other, Color(0xFFE7E7E2), 1.2f),
)

private val ToolpathDrawOrder = listOf(7, 2, 3, 4, 6, 1, 0, 5)

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
    model: ModelInfo?,
    modelTransform: ModelTransform,
    sliceOptions: SliceOptions,
    profileCatalog: ProfileCatalog,
    sliceOutcome: SliceOutcome?,
    layerPreview: GcodeLayerPreview?,
    importing: Boolean,
    slicing: Boolean,
    sliceProgress: Int,
    previewLoading: Boolean,
    error: String?,
    notice: String?,
    onTabSelected: (WorkspaceTab) -> Unit,
    onChoose: () -> Unit,
    onModelTransformChanged: (ModelTransform) -> Unit,
    onRemoveModel: () -> Unit,
    onSlice: () -> Unit,
    onSave: () -> Unit,
    onSliceOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinterProfile: (String) -> Unit,
    onSaveFilamentProfile: (String) -> Unit,
    onSaveSlicingProfile: (String) -> Unit,
    onLayerRangeSelected: (Int, Int) -> Unit,
) = BoxWithConstraints {
    val tabletLayout = maxWidth >= 600.dp
    val panelAlignment = if (tabletLayout) Alignment.BottomEnd else Alignment.BottomCenter
    var toolpathOpacity by remember { mutableFloatStateOf(0.92f) }
    var showModelTools by remember { mutableStateOf(false) }
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
                    model = model,
                    modelTransform = modelTransform,
                    preview = if (selectedTab == WorkspaceTab.PREVIEW) layerPreview else null,
                    bedSizeX = sliceOptions.bedSizeX,
                    bedSizeY = sliceOptions.bedSizeY,
                    toolpathOpacity = toolpathOpacity,
                    modifier = Modifier.fillMaxSize(),
                )

            WorkspaceMenu(
                importing = importing,
                slicing = slicing,
                previewLoading = previewLoading,
                canExport = sliceOutcome != null,
                onImport = onChoose,
                onExport = onSave,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )

            if (model != null && selectedTab == WorkspaceTab.SLICE) {
                Button(
                    onClick = { showModelTools = true },
                    enabled = !importing && !slicing && !previewLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black.copy(alpha = 0.68f),
                        contentColor = Color(0xFFF4F4EE),
                    ),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.edit_model))
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                contentColor = Color(0xFFF4F4EE),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = if (model != null && selectedTab == WorkspaceTab.SLICE) 72.dp else 16.dp,
                        end = 16.dp,
                    )
                    .widthIn(max = 280.dp),
            ) {
                Text(
                    text = model?.fileName ?: stringResource(R.string.no_model),
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            when (selectedTab) {
                WorkspaceTab.SLICE -> SliceSheet(
                    model = model,
                    options = sliceOptions,
                    catalog = profileCatalog,
                    importing = importing,
                    previewLoading = previewLoading,
                    slicing = slicing,
                    progress = sliceProgress,
                    error = error,
                    notice = notice,
                    onSlice = onSlice,
                    onOptionsChanged = onSliceOptionsChanged,
                    onSavePrinter = onSavePrinterProfile,
                    onSaveFilament = onSaveFilamentProfile,
                    onSaveSlicing = onSaveSlicingProfile,
                    modifier = Modifier.align(panelAlignment),
                )

                WorkspaceTab.PREVIEW -> PreviewSheet(
                    outcome = sliceOutcome,
                    preview = layerPreview,
                    loading = previewLoading,
                    error = error,
                    toolpathOpacity = toolpathOpacity,
                    onToolpathOpacityChanged = { toolpathOpacity = it },
                    onLayerRangeSelected = onLayerRangeSelected,
                    onGoToSlice = { onTabSelected(WorkspaceTab.SLICE) },
                    modifier = Modifier.align(panelAlignment),
                )

                WorkspaceTab.DEVICE -> SimpleSheet(
                    title = stringResource(R.string.device_profiles),
                    body = stringResource(R.string.device_message),
                    modifier = Modifier.align(panelAlignment),
                )

                WorkspaceTab.PROJECT -> ProjectSheet(
                    model = model,
                    outcome = sliceOutcome,
                    modifier = Modifier.align(panelAlignment),
                )

                WorkspaceTab.SETTINGS -> SettingsSheet(modifier = Modifier.align(panelAlignment))
            }
        }
    }
    }
    if (showModelTools && model != null) {
        ModelTransformSheet(
            transform = modelTransform,
            bedSizeX = sliceOptions.bedSizeX,
            bedSizeY = sliceOptions.bedSizeY,
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
    onTransformChanged: (ModelTransform) -> Unit,
    onRemoveModel: () -> Unit,
    onDismiss: () -> Unit,
) {
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
                onValueChange = { onTransformChanged(transform.copy(offsetXmm = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.move_y),
                valueText = stringResource(R.string.millimeters_value, transform.offsetYmm),
                value = transform.offsetYmm,
                range = -bedSizeY / 2f..bedSizeY / 2f,
                onValueChange = { onTransformChanged(transform.copy(offsetYmm = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.rotate_x),
                valueText = stringResource(R.string.degrees_value, transform.rotationXdeg),
                value = transform.rotationXdeg,
                range = -180f..180f,
                onValueChange = { onTransformChanged(transform.copy(rotationXdeg = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.rotate_y),
                valueText = stringResource(R.string.degrees_value, transform.rotationYdeg),
                value = transform.rotationYdeg,
                range = -180f..180f,
                onValueChange = { onTransformChanged(transform.copy(rotationYdeg = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.rotate_z),
                valueText = stringResource(R.string.degrees_value, transform.rotationZdeg),
                value = transform.rotationZdeg,
                range = -180f..180f,
                onValueChange = { onTransformChanged(transform.copy(rotationZdeg = it)) },
            )
            TransformSlider(
                label = stringResource(R.string.scale),
                valueText = stringResource(R.string.percent_value, (transform.scale * 100).roundToInt()),
                value = transform.scale,
                range = 0.25f..3f,
                onValueChange = { onTransformChanged(transform.copy(scale = it)) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        onTransformChanged(transform.copy(offsetXmm = 0f, offsetYmm = 0f))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.center_model))
                }
                TextButton(
                    onClick = {
                        val nextRotation = ((transform.rotationZdeg + 90f + 180f) % 360f) - 180f
                        onTransformChanged(transform.copy(rotationZdeg = nextRotation))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.rotate_90))
                }
                TextButton(
                    onClick = { onTransformChanged(ModelTransform()) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.reset))
                }
            }
            TextButton(onClick = onRemoveModel, modifier = Modifier.fillMaxWidth()) {
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
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(valueText, color = Color(0xFFC8C9C2))
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        colors = duckySliderColors(),
    )
}

@Composable
private fun WorkspaceMenu(
    importing: Boolean,
    slicing: Boolean,
    previewLoading: Boolean,
    canExport: Boolean,
    onImport: () -> Unit,
    onExport: () -> Unit,
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
                if (importing) {
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
                enabled = !importing && !slicing && !previewLoading,
                onClick = {
                    expanded = false
                    onImport()
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
    model: ModelInfo?,
    modelTransform: ModelTransform,
    preview: GcodeLayerPreview?,
    bedSizeX: Float,
    bedSizeY: Float,
    toolpathOpacity: Float,
    modifier: Modifier = Modifier,
) {
    var yaw by remember { mutableFloatStateOf(-45f) }
    var pitch by remember { mutableFloatStateOf(55f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var interactionActive by remember { mutableStateOf(false) }
    var refinedPreview by remember { mutableStateOf(true) }
    val previewPaths = remember(preview) { Array(ToolpathStyles.size) { Path() } }
    val meshPath = remember(model) { Path() }
    val movingPreviewPlan = remember(preview) { preview?.buildRenderPlan(segmentBudget = 450) }
    val refinedPreviewPlan = remember(preview) { preview?.buildRenderPlan(segmentBudget = 4_000) }

    LaunchedEffect(interactionActive) {
        if (interactionActive) {
            refinedPreview = false
        } else {
            delay(650)
            refinedPreview = true
        }
    }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
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
                                yaw += delta.x * 0.32f
                                pitch = (pitch - delta.y * 0.26f).coerceIn(22f, 88f)
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

        val bed = Path().apply {
            moveTo(project(0f, 0f).x, project(0f, 0f).y)
            lineTo(project(bedSizeX, 0f).x, project(bedSizeX, 0f).y)
            lineTo(project(bedSizeX, bedSizeY).x, project(bedSizeX, bedSizeY).y)
            lineTo(project(0f, bedSizeY).x, project(0f, bedSizeY).y)
            close()
        }
        drawPath(
            bed,
            color = if (preview == null) Color(0xFF343732) else Color(0xFF2D302D).copy(alpha = 0.7f),
        )

        val gridStep = if (max(bedSizeX, bedSizeY) <= 230f) 20f else 30f
        var gridX = 0f
        while (gridX <= bedSizeX) {
            drawLine(
                if (preview == null) Color(0xFF555950) else Color(0xFF70746B).copy(alpha = 0.45f),
                project(gridX, 0f),
                project(gridX, bedSizeY),
                1.dp.toPx(),
            )
            gridX += gridStep
        }
        var gridY = 0f
        while (gridY <= bedSizeY) {
            drawLine(
                if (preview == null) Color(0xFF555950) else Color(0xFF70746B).copy(alpha = 0.45f),
                project(0f, gridY),
                project(bedSizeX, gridY),
                1.dp.toPx(),
            )
            gridY += gridStep
        }
        drawPath(
            bed,
            color = if (preview == null) WorkspaceYellow.copy(alpha = 0.75f) else Color(0xFF9A9D94),
            style = Stroke(2.dp.toPx()),
        )

        if (preview != null) {
            previewPaths.forEach(Path::reset)
            val renderPlan = if (interactionActive || !refinedPreview) {
                movingPreviewPlan
            } else {
                refinedPreviewPlan
            }
            renderPlan?.segmentOffsets?.forEachIndexed { selectedIndex, segmentIndex ->
                val role = preview.segments[segmentIndex + 5].roundToInt()
                    .coerceIn(0, ToolpathStyles.lastIndex)
                val startX = preview.segments[segmentIndex]
                val startY = preview.segments[segmentIndex + 1]
                val z = preview.segments[segmentIndex + 4]
                val end = project(
                    preview.segments[segmentIndex + 2],
                    preview.segments[segmentIndex + 3],
                    z,
                )
                if (renderPlan.connectsToPrevious[selectedIndex]) {
                    previewPaths[role].lineTo(end.x, end.y)
                } else {
                    val start = project(startX, startY, z)
                    previewPaths[role].moveTo(start.x, start.y)
                    previewPaths[role].lineTo(end.x, end.y)
                }
            }
            ToolpathDrawOrder.forEach { role ->
                val style = ToolpathStyles[role]
                drawPath(
                    path = previewPaths[role],
                    color = style.color.copy(alpha = toolpathOpacity),
                    style = Stroke(width = style.widthDp.dp.toPx()),
                )
            }
        } else if (model != null) {
            val minimumRotatedZ = modelTransform.minimumRotatedZ(model)
            meshPath.reset()
            var triangleIndex = 0
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
                meshPath.moveTo(a.x, a.y)
                meshPath.lineTo(b.x, b.y)
                meshPath.lineTo(c.x, c.y)
                meshPath.close()
                triangleIndex += 9
            }
            drawPath(meshPath, WorkspaceYellow.copy(alpha = 0.14f))
            drawPath(meshPath, WorkspaceYellow.copy(alpha = 0.52f), style = Stroke(0.7.dp.toPx()))
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
    progress: Int,
    error: String?,
    notice: String?,
    onSlice: () -> Unit,
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
            Text(
                stringResource(R.string.toolpath_opacity, (toolpathOpacity * 100).roundToInt()),
                fontWeight = FontWeight.SemiBold,
            )
            Slider(
                value = toolpathOpacity,
                onValueChange = onToolpathOpacityChanged,
                valueRange = 0.3f..1f,
                colors = duckySliderColors(),
            )
            ToolpathStyles.chunked(2).forEach { rowStyles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowStyles.forEach { style ->
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                modifier = Modifier.size(width = 18.dp, height = 6.dp),
                                color = style.color,
                                shape = RoundedCornerShape(50),
                            ) {}
                            Spacer(Modifier.width(7.dp))
                            Text(
                                stringResource(style.label),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFE2E3DD),
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
    model: ModelInfo?,
    outcome: SliceOutcome?,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(modifier) {
        Text(stringResource(R.string.tab_project), fontWeight = FontWeight.Bold)
        Text(model?.fileName ?: stringResource(R.string.no_model), color = Color(0xFFC8C9C2))
        Text(
            if (outcome == null) stringResource(R.string.no_gcode) else stringResource(R.string.gcode_ready),
            color = if (outcome == null) Color(0xFFC8C9C2) else WorkspaceYellow,
        )
    }
}

@Composable
private fun SimpleSheet(title: String, body: String, modifier: Modifier = Modifier) {
    WorkspaceCard(modifier) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, color = Color(0xFFC8C9C2))
    }
}

@Composable
private fun SettingsSheet(modifier: Modifier = Modifier) {
    WorkspaceCard(modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_ducky),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.offline_app_summary),
            color = Color(0xFFC8C9C2),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Text(stringResource(R.string.settings_message), color = Color(0xFFC8C9C2))
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

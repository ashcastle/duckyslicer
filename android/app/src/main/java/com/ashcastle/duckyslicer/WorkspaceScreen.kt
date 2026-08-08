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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

private val WorkspaceYellow = Color(0xFFF6C945)
private val WorkspaceBlack = Color(0xFF202124)
private val WorkspacePanel = Color(0xEE2A2A27)

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
                    preview = if (selectedTab == WorkspaceTab.PREVIEW) layerPreview else null,
                    bedSizeX = sliceOptions.bedSizeX,
                    bedSizeY = sliceOptions.bedSizeY,
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

            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                contentColor = Color(0xFFF4F4EE),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
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

                WorkspaceTab.SETTINGS -> SimpleSheet(
                    title = stringResource(R.string.settings),
                    body = stringResource(R.string.settings_message),
                    modifier = Modifier.align(panelAlignment),
                )
            }
        }
    }
}
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
    preview: GcodeLayerPreview?,
    bedSizeX: Float,
    bedSizeY: Float,
    modifier: Modifier = Modifier,
) {
    var yaw by remember { mutableFloatStateOf(-45f) }
    var pitch by remember { mutableFloatStateOf(55f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
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
        drawPath(bed, color = Color(0xFF343732))

        val gridStep = if (max(bedSizeX, bedSizeY) <= 230f) 20f else 30f
        var gridX = 0f
        while (gridX <= bedSizeX) {
            drawLine(Color(0xFF555950), project(gridX, 0f), project(gridX, bedSizeY), 1.dp.toPx())
            gridX += gridStep
        }
        var gridY = 0f
        while (gridY <= bedSizeY) {
            drawLine(Color(0xFF555950), project(0f, gridY), project(bedSizeX, gridY), 1.dp.toPx())
            gridY += gridStep
        }
        drawPath(bed, color = WorkspaceYellow.copy(alpha = 0.75f), style = Stroke(2.dp.toPx()))

        if (preview != null) {
            val path = Path()
            var segmentIndex = 0
            while (segmentIndex + 4 < preview.segments.size) {
                val start = project(
                    preview.segments[segmentIndex],
                    preview.segments[segmentIndex + 1],
                    preview.segments[segmentIndex + 4],
                )
                val end = project(
                    preview.segments[segmentIndex + 2],
                    preview.segments[segmentIndex + 3],
                    preview.segments[segmentIndex + 4],
                )
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
                segmentIndex += 5
            }
            drawPath(
                path = path,
                color = WorkspaceYellow,
                style = Stroke(width = 1.8.dp.toPx()),
            )
        } else if (model != null) {
            val minX = model.minMm[0].toFloat()
            val minY = model.minMm[1].toFloat()
            val minZ = model.minMm[2].toFloat()
            val maxX = model.maxMm[0].toFloat()
            val maxY = model.maxMm[1].toFloat()
            val outsideBed = minX < -2f || minY < -2f || maxX > bedSizeX + 2f || maxY > bedSizeY + 2f
            val offsetX = if (outsideBed) bedSizeX / 2f - (minX + maxX) / 2f else 0f
            val offsetY = if (outsideBed) bedSizeY / 2f - (minY + maxY) / 2f else 0f
            val meshPath = Path()
            var triangleIndex = 0
            while (triangleIndex + 8 < model.previewTriangles.size) {
                val a = project(
                    model.previewTriangles[triangleIndex] + offsetX,
                    model.previewTriangles[triangleIndex + 1] + offsetY,
                    model.previewTriangles[triangleIndex + 2] - minZ,
                )
                val b = project(
                    model.previewTriangles[triangleIndex + 3] + offsetX,
                    model.previewTriangles[triangleIndex + 4] + offsetY,
                    model.previewTriangles[triangleIndex + 5] - minZ,
                )
                val c = project(
                    model.previewTriangles[triangleIndex + 6] + offsetX,
                    model.previewTriangles[triangleIndex + 7] + offsetY,
                    model.previewTriangles[triangleIndex + 8] - minZ,
                )
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
            var selectedRange by remember(preview.startLayer, preview.endLayer, preview.layerCount) {
                mutableStateOf(preview.startLayer.toFloat()..preview.endLayer.toFloat())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(
                        R.string.layer_range,
                        preview.startLayer + 1,
                        preview.endLayer + 1,
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
                    valueRange = 0f..(preview.layerCount - 1).toFloat(),
                    steps = 0,
                )
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

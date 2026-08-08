package com.ashcastle.duckyslicer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

private val WorkspaceYellow = Color(0xFFF6C945)
private val WorkspaceBlack = Color(0xFF202124)
private val WorkspacePanel = Color(0xEE2A2A27)
private const val BED_SIZE_MM = 270f

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
    onLayerSelected: (Int) -> Unit,
) {
    Scaffold(
        containerColor = Color(0xFF191A18),
        bottomBar = {
            WorkspaceNavigation(selectedTab = selectedTab, onSelected = onTabSelected)
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BedScene(
                model = model,
                preview = if (selectedTab == WorkspaceTab.PREVIEW) layerPreview else null,
                modifier = Modifier.fillMaxSize(),
            )

            WorkspaceMenu(
                importing = importing,
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
                    slicing = slicing,
                    progress = sliceProgress,
                    error = error,
                    notice = notice,
                    onSlice = onSlice,
                    onOptionsChanged = onSliceOptionsChanged,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                WorkspaceTab.PREVIEW -> PreviewSheet(
                    outcome = sliceOutcome,
                    preview = layerPreview,
                    loading = previewLoading,
                    error = error,
                    onLayerSelected = onLayerSelected,
                    onGoToSlice = { onTabSelected(WorkspaceTab.SLICE) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                WorkspaceTab.DEVICE -> SimpleSheet(
                    title = stringResource(R.string.device_profiles),
                    body = stringResource(R.string.device_message),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                WorkspaceTab.PROJECT -> ProjectSheet(
                    model = model,
                    outcome = sliceOutcome,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                WorkspaceTab.SETTINGS -> SimpleSheet(
                    title = stringResource(R.string.settings),
                    body = stringResource(R.string.settings_message),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceMenu(
    importing: Boolean,
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
                enabled = !importing,
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
    val items = listOf(
        Triple(WorkspaceTab.SLICE, Icons.Default.Tune, R.string.tab_slice),
        Triple(WorkspaceTab.PREVIEW, Icons.Default.Visibility, R.string.tab_preview),
        Triple(WorkspaceTab.DEVICE, Icons.Default.Devices, R.string.tab_device),
        Triple(WorkspaceTab.PROJECT, Icons.Default.Folder, R.string.tab_project),
        Triple(WorkspaceTab.SETTINGS, Icons.Default.Settings, R.string.settings),
    )
    NavigationBar(containerColor = Color(0xFF242522)) {
        items.forEach { (tab, icon, label) ->
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
private fun BedScene(
    model: ModelInfo?,
    preview: GcodeLayerPreview?,
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
        val sceneScale = min(size.width * 0.64f, size.height * 0.72f) / BED_SIZE_MM * zoom
        val sceneCenter = Offset(size.width / 2f + pan.x, size.height * 0.48f + pan.y)

        fun project(x: Float, y: Float, z: Float = 0f): Offset {
            val dx = x - BED_SIZE_MM / 2f
            val dy = y - BED_SIZE_MM / 2f
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
            lineTo(project(BED_SIZE_MM, 0f).x, project(BED_SIZE_MM, 0f).y)
            lineTo(project(BED_SIZE_MM, BED_SIZE_MM).x, project(BED_SIZE_MM, BED_SIZE_MM).y)
            lineTo(project(0f, BED_SIZE_MM).x, project(0f, BED_SIZE_MM).y)
            close()
        }
        drawPath(bed, color = Color(0xFF343732))

        for (mm in 0..270 step 30) {
            val value = mm.toFloat()
            drawLine(Color(0xFF555950), project(value, 0f), project(value, BED_SIZE_MM), 1.dp.toPx())
            drawLine(Color(0xFF555950), project(0f, value), project(BED_SIZE_MM, value), 1.dp.toPx())
        }
        drawPath(bed, color = WorkspaceYellow.copy(alpha = 0.75f), style = Stroke(2.dp.toPx()))

        if (preview != null) {
            val path = Path()
            var segmentIndex = 0
            while (segmentIndex + 3 < preview.segments.size) {
                val start = project(
                    preview.segments[segmentIndex],
                    preview.segments[segmentIndex + 1],
                    preview.zMm,
                )
                val end = project(
                    preview.segments[segmentIndex + 2],
                    preview.segments[segmentIndex + 3],
                    preview.zMm,
                )
                path.moveTo(start.x, start.y)
                path.lineTo(end.x, end.y)
                segmentIndex += 4
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
            val outsideBed = minX < -2f || minY < -2f || maxX > BED_SIZE_MM + 2f || maxY > BED_SIZE_MM + 2f
            val offsetX = if (outsideBed) BED_SIZE_MM / 2f - (minX + maxX) / 2f else 0f
            val offsetY = if (outsideBed) BED_SIZE_MM / 2f - (minY + maxY) / 2f else 0f
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
    slicing: Boolean,
    progress: Int,
    error: String?,
    notice: String?,
    onSlice: () -> Unit,
    onOptionsChanged: (SliceOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    WorkspaceCard(modifier) {
        Text(stringResource(R.string.profile_management), fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.profile_summary),
            color = Color(0xFFC8C9C2),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QualityProfile.entries.forEach { profile ->
                val label = when (profile) {
                    QualityProfile.DRAFT -> R.string.profile_draft
                    QualityProfile.STANDARD -> R.string.profile_standard
                    QualityProfile.FINE -> R.string.profile_fine
                }
                FilterChip(
                    selected = options.quality == profile,
                    onClick = { onOptionsChanged(options.copy(quality = profile)) },
                    label = { Text(stringResource(label), maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
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
                enabled = !slicing,
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
    onLayerSelected: (Int) -> Unit,
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
            var selectedLayer by remember(preview.layer, preview.layerCount) {
                mutableFloatStateOf(preview.layer.toFloat())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.layer_position, preview.layer + 1, preview.layerCount),
                    fontWeight = FontWeight.Bold,
                )
                Text(String.format("Z %.2f mm", preview.zMm), color = Color(0xFFC8C9C2))
            }
            if (preview.layerCount > 1) {
                Slider(
                    value = selectedLayer,
                    onValueChange = { selectedLayer = it },
                    onValueChangeFinished = { onLayerSelected(selectedLayer.roundToInt()) },
                    valueRange = 0f..(preview.layerCount - 1).toFloat(),
                    steps = (preview.layerCount - 2).coerceAtLeast(0),
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

@Composable
private fun neutralButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xFF464842),
    contentColor = Color(0xFFF4F4EE),
)

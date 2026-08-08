package com.ashcastle.duckyslicer

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val DuckyColors = darkColorScheme(
    primary = Color(0xFFF6C945),
    onPrimary = Color(0xFF202124),
    secondary = Color(0xFFD99A00),
    background = Color(0xFF1D1D1B),
    onBackground = Color(0xFFF7F5EF),
    surface = Color(0xFF2A2A27),
    onSurface = Color(0xFFF7F5EF),
)

data class ModelInfo(
    val fileName: String,
    val triangles: Int,
    val dimensions: List<Double>,
    val localPath: String,
    val minMm: List<Double>,
    val maxMm: List<Double>,
    val previewTriangles: FloatArray,
) {
    companion object {
        fun fromJson(raw: String, localPath: String): ModelInfo {
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { "model_invalid" }
            val values = json.getJSONArray("dimensionsMm")
            val minValues = json.getJSONArray("minMm")
            val maxValues = json.getJSONArray("maxMm")
            val triangleValues = json.getJSONArray("previewTriangles")
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
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = DuckyColors) {
                DuckySlicerScreen()
            }
        }
    }
}

@Composable
private fun DuckySlicerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelReadError = stringResource(R.string.model_read_error)
    val sliceError = stringResource(R.string.slice_error)
    val saveError = stringResource(R.string.save_error)
    val savedNotice = stringResource(R.string.gcode_saved)
    val previewError = stringResource(R.string.preview_error)

    var model by remember { mutableStateOf<ModelInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var slicing by remember { mutableStateOf(false) }
    var sliceProgress by remember { mutableIntStateOf(0) }
    var sliceOutcome by remember { mutableStateOf<SliceOutcome?>(null) }
    var selectedTab by remember { mutableStateOf(WorkspaceTab.SLICE) }
    var layerPreview by remember { mutableStateOf<GcodeLayerPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var sliceOptions by remember { mutableStateOf(SliceOptions()) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !slicing && !previewLoading) {
            importing = true
            error = null
            notice = null
            scope.launch {
                runCatching { importAndInspect(context, uri) }
                    .onSuccess {
                        model = it
                        sliceOutcome = null
                        layerPreview = null
                        sliceProgress = 0
                        selectedTab = WorkspaceTab.SLICE
                    }
                    .onFailure { error = modelReadError }
                importing = false
            }
        }
    }

    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val completed = sliceOutcome
        if (uri != null && completed != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri).use { output ->
                            requireNotNull(output) { "output_unavailable" }
                            completed.output.inputStream().use { input -> input.copyTo(output) }
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
        val output = sliceOutcome?.output
        if (output != null && !previewLoading) {
            previewLoading = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        GcodeLayerPreview.fromJson(
                            NativeEngine.previewGcodeRange(output.absolutePath, startLayer, endLayer),
                        )
                    }
                }.onSuccess {
                    layerPreview = it
                    error = null
                }.onFailure {
                    error = previewError
                }
                previewLoading = false
            }
        }
    }

    val startSlice = {
        val selected = model
        if (selected != null && !slicing && !importing && !previewLoading) {
            slicing = true
            sliceProgress = 0
            sliceOutcome = null
            layerPreview = null
            error = null
            notice = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        OnDeviceSlicer.slice(File(selected.localPath), sliceOptions) { progress ->
                            scope.launch { sliceProgress = progress }
                        }
                    }
                }.onSuccess { outcome ->
                    sliceOutcome = outcome
                    sliceProgress = 100
                    selectedTab = WorkspaceTab.PREVIEW
                    previewLoading = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                GcodeLayerPreview.fromJson(
                                    NativeEngine.previewGcodeRange(
                                        outcome.output.absolutePath,
                                        0,
                                        Int.MAX_VALUE,
                                    ),
                                )
                            }
                        }.onSuccess { preview ->
                            layerPreview = preview
                            sliceOutcome = outcome.copy(layers = preview.layerCount)
                        }
                            .onFailure { error = previewError }
                        previewLoading = false
                    }
                }.onFailure {
                    error = sliceError
                }
                slicing = false
            }
        }
    }

    val saveGcode = {
        val completed = sliceOutcome
        val selected = model
        if (completed != null && selected != null) {
            val baseName = selected.fileName.substringBeforeLast('.').ifBlank { "model" }
            savePicker.launch("$baseName.gcode")
        }
    }

    WorkspaceScreen(
        selectedTab = selectedTab,
        model = model,
        sliceOptions = sliceOptions,
        sliceOutcome = sliceOutcome,
        layerPreview = layerPreview,
        importing = importing,
        slicing = slicing,
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
        onSlice = startSlice,
        onSave = saveGcode,
        onSliceOptionsChanged = {
            if (it != sliceOptions) {
                sliceOptions = it
                sliceOutcome = null
                layerPreview = null
                sliceProgress = 0
                notice = null
            }
        },
        onLayerRangeSelected = loadPreviewRange,
    )
}

private suspend fun importAndInspect(context: Context, uri: Uri): ModelInfo = withContext(Dispatchers.IO) {
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?.takeIf { it.endsWith(".stl", ignoreCase = true) }
        ?: "model.stl"
    val safeName = displayName.replace(Regex("[^A-Za-z0-9가-힣._-]"), "_")
    val destination = File(File(context.cacheDir, "models").apply { mkdirs() }, safeName)

    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "model_unreadable" }
        destination.outputStream().use { output -> input.copyTo(output) }
    }

    ModelInfo.fromJson(NativeEngine.inspectStl(destination.absolutePath), destination.absolutePath)
}

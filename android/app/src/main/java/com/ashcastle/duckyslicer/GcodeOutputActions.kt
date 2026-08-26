package com.ashcastle.duckyslicer

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

internal data class GcodeOutputActions(
    val save: (Boolean) -> Unit,
    val share: () -> Unit,
    val clearPending: () -> Unit,
)

/** Owns the Android document and Sharesheet edges outside the root workspace method. */
@Composable
internal fun rememberGcodeOutputActions(
    selectedResult: PlateSliceResult?,
    batch: GcodeExportBatch?,
    selectedPlateHasObjects: Boolean,
    exporting: Boolean,
    model: GcodeExportViewModel,
    onStarted: () -> Unit,
    onShareFailed: () -> Unit,
): GcodeOutputActions {
    val context = LocalContext.current
    val resources = LocalResources.current
    val chooserTitle = resources.getString(R.string.gcode_share_title)
    var pendingSingle by rememberSaveable { mutableStateOf<PlateSliceResult?>(null) }
    var pendingBatch by rememberSaveable { mutableStateOf<GcodeExportBatch?>(null) }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GCODE_DOCUMENT_MIME_TYPE),
    ) { uri ->
        val requested = pendingSingle
        pendingSingle = null
        if (uri != null && requested != null && model.export(uri, requested.outcome)) {
            onStarted()
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val requested = pendingBatch
        pendingBatch = null
        if (uri != null && requested != null && model.exportAll(uri, requested)) {
            onStarted()
        }
    }
    return GcodeOutputActions(
        save = { allPlates ->
            if (!exporting) {
                if (allPlates) {
                    batch?.let { requested ->
                        pendingBatch = requested
                        folderPicker.launch(null)
                    }
                } else if (selectedResult != null && selectedPlateHasObjects) {
                    pendingSingle = selectedResult
                    savePicker.launch(selectedResult.outcome.suggestedName)
                }
            }
        },
        share = {
            val share = selectedResult?.outcome?.let { outcome ->
                gcodeShareIntentOrNull(context, outcome)
            }
            if (share == null || runCatching {
                    context.startActivity(Intent.createChooser(share, chooserTitle))
                }.isFailure
            ) {
                onShareFailed()
            } else {
                onStarted()
            }
        },
        clearPending = {
            pendingSingle = null
            pendingBatch = null
        },
    )
}

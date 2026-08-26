package com.ashcastle.duckyslicer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val LocalGcodePreviewImportModel = staticCompositionLocalOf<GcodePreviewImportViewModel> {
    error("G-code preview model is unavailable")
}

internal data class GcodePreviewUiBinding(
    val state: GcodePreviewImportState,
    val openPicker: () -> Unit,
)

@Composable
internal fun rememberGcodePreviewUiBinding(
    model: GcodePreviewImportViewModel,
    onSucceeded: () -> Unit,
    onPresentation: (notice: String?, error: String?) -> Unit,
): GcodePreviewUiBinding {
    val state by model.state.collectAsStateWithLifecycle()
    val failed = stringResource(R.string.gcode_open_failed)
    val canceled = stringResource(R.string.gcode_open_canceled)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::open)
    }
    GcodePreviewCompletionEffect(
        state = state,
        onCompleted = { status ->
            when (status) {
                GcodePreviewImportStatus.SUCCEEDED -> {
                    onSucceeded()
                    onPresentation(null, null)
                }
                GcodePreviewImportStatus.CANCELED -> onPresentation(canceled, null)
                GcodePreviewImportStatus.FAILED -> onPresentation(null, failed)
                GcodePreviewImportStatus.NONE -> Unit
            }
        },
        onConsumeCompletion = model::consumeCompletion,
    )
    return GcodePreviewUiBinding(
        state = state,
        openPicker = {
            picker.launch(
                (GCODE_DOCUMENT_MIME_TYPES + GCODE_COMPATIBLE_MIME_TYPES).toTypedArray(),
            )
        },
    )
}

@Composable
internal fun ExternalGcodeRequestStartEffect(
    request: ExternalGcodeRequest?,
    state: GcodePreviewImportState,
    enabled: Boolean,
    onOpen: (android.net.Uri) -> Long?,
    onRequestStarted: (Long, Long) -> Boolean,
    onRequestConsumed: (Long, Long) -> Boolean,
    onCancel: () -> Unit,
) {
    LaunchedEffect(
        request?.id,
        request?.startedOperationId,
        enabled,
        state.busy,
    ) {
        val pending = request ?: return@LaunchedEffect
        if (pending.startedOperationId != null || !enabled || state.busy) {
            return@LaunchedEffect
        }
        val operationId = onOpen(pending.uri) ?: return@LaunchedEffect
        if (!onRequestStarted(pending.id, operationId)) {
            onCancel()
        } else {
            onRequestConsumed(pending.id, operationId)
        }
    }
}

@Composable
private fun GcodePreviewCompletionEffect(
    state: GcodePreviewImportState,
    onCompleted: (GcodePreviewImportStatus) -> Unit,
    onConsumeCompletion: (Long) -> Boolean,
) {
    LaunchedEffect(state.completionOperationId, state.status) {
        val operationId = state.completionOperationId ?: return@LaunchedEffect
        onCompleted(state.status)
        onConsumeCompletion(operationId)
    }
}

package com.ashcastle.duckyslicer

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

internal data class ProjectArchiveShareActions(
    val pendingOperationId: Long?,
    val start: () -> Unit,
)

/** Owns one retained project export until its Android Sharesheet handoff completes. */
@Composable
internal fun rememberProjectArchiveShareActions(
    model: ProjectTransferViewModel,
    completion: ProjectTransferCompletion?,
    snapshot: ProjectSnapshot,
    plateOptions: Map<String, SliceOptions>,
    busy: Boolean,
    enabled: Boolean,
    onPresentation: (notice: String?, error: String?) -> Unit,
): ProjectArchiveShareActions {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_project)
    val shareError = stringResource(R.string.project_share_error)
    var pendingOperationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(completion?.id, pendingOperationId) {
        val completed = completion ?: return@LaunchedEffect
        if (completed.id != pendingOperationId) return@LaunchedEffect
        val launched = if (
            completed is ProjectTransferCompletion.Exported &&
            completed.format == ProjectExportFormat.PROJECT_ARCHIVE
        ) {
            projectArchiveShareIntentOrNull(context, pendingUri?.let(Uri::parse))
                ?.let { shareIntent ->
                    runCatching {
                        context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                    }.isSuccess
                } ?: false
        } else {
            false
        }
        if (!launched) discardProjectArchiveShare(context, pendingPath)
        if (completed is ProjectTransferCompletion.Exported) {
            onPresentation(null, shareError.takeUnless { launched })
        }
        pendingOperationId = null
        pendingUri = null
        pendingPath = null
    }

    LaunchedEffect(pendingOperationId, completion, busy) {
        if (pendingOperationId != null && !busy && completion == null) {
            discardProjectArchiveShare(context, pendingPath)
            pendingOperationId = null
            pendingUri = null
            pendingPath = null
        }
    }

    return ProjectArchiveShareActions(
        pendingOperationId = pendingOperationId,
        start = {
            if (enabled) {
                val target = prepareProjectArchiveShare(context)
                if (
                    target != null && model.exportProject(
                        uri = target.uri,
                        snapshot = snapshot,
                        plateOptions = plateOptions,
                        updateLinkedDocument = false,
                    )
                ) {
                    pendingOperationId = model.state.value.activeTransferId
                    pendingUri = target.uri.toString()
                    pendingPath = target.file.absolutePath
                    onPresentation(null, null)
                } else {
                    target?.let { discardProjectArchiveShare(context, it.file.absolutePath) }
                    onPresentation(null, shareError)
                }
            }
        },
    )
}

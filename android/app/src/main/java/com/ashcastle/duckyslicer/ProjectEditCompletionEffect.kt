package com.ashcastle.duckyslicer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources

/** Presents and consumes exactly one retained project-edit completion. */
@Composable
internal fun ProjectEditCompletionEffect(
    completion: ProjectEditCompletion?,
    externalModelRequest: ExternalModelRequest?,
    onExternalModelRequestConsumed: (Long, Long) -> Boolean,
    onConsumeCompletion: (Long) -> Unit,
    onSessionChanged: () -> Unit,
    onTabSelected: (WorkspaceTab) -> Unit,
    onPresentation: (String?, String?) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(completion?.id) {
        val completed = completion ?: return@LaunchedEffect
        if (completed.failure == null) {
            if (completed.sessionChanged) onSessionChanged()
            val notice = when (completed.kind) {
                ProjectEditKind.MODEL_IMPORT -> null
                ProjectEditKind.PRIMITIVE -> resources.getString(
                    R.string.shape_added,
                    completed.displayName.orEmpty(),
                )
                ProjectEditKind.AUXILIARY_VOLUME -> resources.getString(
                    R.string.region_updated,
                    completed.displayName.orEmpty(),
                )
                ProjectEditKind.AUTO_LAY -> resources.getString(
                    if (completed.sessionChanged) R.string.auto_lay_done else R.string.auto_lay_unchanged,
                )
                ProjectEditKind.ARRANGE -> resources.getString(R.string.arrange_done)
                ProjectEditKind.SPLIT -> resources.getString(
                    if (completed.clearedObjectSettings) {
                        R.string.split_done_painting_cleared
                    } else {
                        R.string.split_done
                    },
                    completed.objectCount,
                )
                ProjectEditKind.SPLIT_PARTS -> resources.getString(
                    if (completed.clearedObjectSettings) {
                        R.string.split_parts_done_painting_cleared
                    } else {
                        R.string.split_parts_done
                    },
                    completed.objectCount,
                )
                ProjectEditKind.CUT -> resources.getString(
                    if (completed.clearedObjectSettings) {
                        R.string.cut_done_painting_cleared
                    } else {
                        R.string.cut_done
                    },
                )
                ProjectEditKind.SIMPLIFY -> resources.getString(
                    if (completed.clearedObjectSettings) {
                        R.string.simplify_done_painting_cleared
                    } else {
                        R.string.simplify_done
                    },
                    completed.triangleCount,
                )
            }
            onPresentation(notice, null)
            onTabSelected(WorkspaceTab.SLICE)
        } else {
            val error = when (completed.failure) {
                ProjectEditFailure.CANCELED -> null
                ProjectEditFailure.MODEL_TOO_LARGE -> resources.getString(R.string.model_too_large_error)
                ProjectEditFailure.NOT_SPLITTABLE -> resources.getString(
                    if (completed.kind == ProjectEditKind.SPLIT_PARTS) {
                        R.string.split_parts_not_possible
                    } else {
                        R.string.split_not_possible
                    },
                )
                ProjectEditFailure.NOT_CUTTABLE -> resources.getString(R.string.cut_not_possible)
                ProjectEditFailure.GENERIC -> resources.getString(
                    when (completed.kind) {
                        ProjectEditKind.MODEL_IMPORT -> R.string.model_read_error
                        ProjectEditKind.PRIMITIVE -> R.string.shape_error
                        ProjectEditKind.AUXILIARY_VOLUME -> R.string.region_update_error
                        ProjectEditKind.AUTO_LAY -> R.string.auto_lay_error
                        ProjectEditKind.ARRANGE -> R.string.arrange_error
                        ProjectEditKind.SPLIT -> R.string.split_error
                        ProjectEditKind.SPLIT_PARTS -> R.string.split_parts_error
                        ProjectEditKind.CUT -> R.string.cut_error
                        ProjectEditKind.SIMPLIFY -> R.string.simplify_error
                    },
                )
            }
            val notice = if (completed.failure == ProjectEditFailure.CANCELED) {
                resources.getString(R.string.model_edit_canceled)
            } else {
                null
            }
            onPresentation(notice, error)
        }
        externalModelRequest
            ?.takeIf { request ->
                completed.kind == ProjectEditKind.MODEL_IMPORT &&
                    request.startedOperationId == completed.id
            }
            ?.let { request -> onExternalModelRequestConsumed(request.id, completed.id) }
        onConsumeCompletion(completed.id)
    }
}

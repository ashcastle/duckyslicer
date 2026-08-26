package com.ashcastle.duckyslicer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

internal data class SliceLifecycleMessages(
    val canceled: String,
    val failed: String,
    val previewFailed: String,
    val allPlatesCompleted: String,
)

internal data class SliceStartControls(
    val startSelected: () -> Unit,
    val startAll: () -> Unit,
    val cancel: () -> Unit,
)

/** Owns notification permission gating and starts either one plate or the retained plate queue. */
@Composable
internal fun rememberSliceStartControls(
    ready: Boolean,
    blocked: Boolean,
    snapshot: ProjectSnapshot,
    plateOptions: Map<String, SliceOptions>,
    results: PlateSliceResults,
    operationState: SliceOperationState,
    batchState: PlateSliceBatchState,
    operationModel: SliceOperationViewModel,
    batchModel: PlateSliceBatchViewModel,
    onResultsChanged: (PlateSliceResults) -> Unit,
    onVisualResultsCleared: () -> Unit,
    onPresentationCleared: () -> Unit,
    onRemoteResultInvalidated: () -> Unit,
): SliceStartControls {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(SLICE_NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    }

    fun beginSelected() {
        val input = snapshot.sliceInput(plateOptions) ?: return
        if (
            !ready || blocked || !operationModel.start(
                input.plateId,
                input.objects,
                input.options,
                input.layerPauseEvents,
                input.layerFilamentChanges,
                input.layerCustomGCodeEvents,
            )
        ) {
            return
        }
        onResultsChanged(results.clear(input.plateId))
        onVisualResultsCleared()
        onRemoteResultInvalidated()
        onPresentationCleared()
    }

    fun beginAll() {
        val plateIds = snapshot.sliceablePlateIds(plateOptions)
        if (!ready || blocked || !batchModel.start(plateIds)) return
        operationModel.clearCompleted()
        onResultsChanged(plateIds.fold(results) { current, plateId -> current.clear(plateId) })
        onVisualResultsCleared()
        onRemoteResultInvalidated()
        onPresentationCleared()
    }

    var pendingAll by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        preferences.edit().putBoolean(SLICE_NOTIFICATION_PERMISSION_ASKED, true).apply()
        if (pendingAll) beginAll() else beginSelected()
        pendingAll = false
    }

    fun request(all: Boolean) {
        val shouldRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean(SLICE_NOTIFICATION_PERMISSION_ASKED, false)
        if (shouldRequest) {
            pendingAll = all
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (all) {
            beginAll()
        } else {
            beginSelected()
        }
    }

    PlateSliceBatchExecutionEffect(
        ready = ready,
        batchState = batchState,
        operationState = operationState,
        snapshot = snapshot,
        plateOptions = plateOptions,
        batchModel = batchModel,
        operationModel = operationModel,
    )

    return SliceStartControls(
        startSelected = { request(false) },
        startAll = { request(true) },
        cancel = {
            if (batchState.active) {
                if (batchModel.requestCancellation()) {
                    if (operationState.slicing) {
                        operationModel.cancel()
                    } else {
                        batchModel.cancel(batchState.currentPlateId)
                    }
                }
            } else {
                operationModel.cancel()
            }
        },
    )
}

/** Starts only the next queued plate after the preceding foreground operation is fully cleared. */
@Composable
internal fun PlateSliceBatchExecutionEffect(
    ready: Boolean,
    batchState: PlateSliceBatchState,
    operationState: SliceOperationState,
    snapshot: ProjectSnapshot,
    plateOptions: Map<String, SliceOptions>,
    batchModel: PlateSliceBatchViewModel,
    operationModel: SliceOperationViewModel,
) {
    LaunchedEffect(
        ready,
        batchState.plateIds,
        batchState.completedCount,
        batchState.currentPlateId,
        batchState.cancellationRequested,
        operationState.plateId,
        operationState.busy,
        operationState.outcome,
        operationState.terminalStatus,
        snapshot,
        plateOptions,
    ) {
        if (
            !ready || !batchState.active || batchState.cancellationRequested ||
            operationState.busy || operationState.outcome != null ||
            operationState.terminalStatus != SliceTerminalStatus.NONE
        ) {
            return@LaunchedEffect
        }
        val plateId = batchState.currentPlateId ?: batchModel.claimNext() ?: return@LaunchedEffect
        val input = snapshot.sliceInput(plateId, plateOptions)
        if (input == null) {
            batchModel.fail(plateId)
            return@LaunchedEffect
        }
        if (
            !operationModel.start(
                input.plateId,
                input.objects,
                input.options,
                input.layerPauseEvents,
                input.layerFilamentChanges,
                input.layerCustomGCodeEvents,
            )
        ) {
            batchModel.fail(plateId)
        }
    }
}

/** Applies completed foreground work to the owning plate and advances any batch exactly once. */
@Composable
internal fun SliceResultLifecycleEffects(
    filesDirectory: File,
    selectedOutcome: SliceOutcome?,
    selectedTab: WorkspaceTab,
    selectedPlateId: String,
    projectPlateIds: Set<String>,
    results: PlateSliceResults,
    operationState: SliceOperationState,
    batchState: PlateSliceBatchState,
    operationModel: SliceOperationViewModel,
    batchModel: PlateSliceBatchViewModel,
    supportEvents: SupportEventJournal,
    messages: SliceLifecycleMessages,
    onInvalidSelectedResult: () -> Unit,
    onResultsChanged: (PlateSliceResults) -> Unit,
    onPreviewChanged: (GcodeLayerPreview?) -> Unit,
    onClearStalePreview: () -> Unit,
    onTabSelected: (WorkspaceTab) -> Unit,
    onPresentation: (String?, String?) -> Unit,
    onRemoteResultInvalidated: () -> Unit,
) {
    LaunchedEffect(selectedOutcome?.output?.absolutePath, selectedPlateId) {
        val restored = selectedOutcome ?: return@LaunchedEffect
        if (!restored.isRestorableFrom(filesDirectory)) {
            onInvalidSelectedResult()
            if (selectedTab == WorkspaceTab.PREVIEW) onTabSelected(WorkspaceTab.SLICE)
        }
    }
    LaunchedEffect(
        operationState.plateId,
        operationState.outcome,
        operationState.preview,
    ) {
        val completed = operationState.outcome ?: return@LaunchedEffect
        val ownerPlateId = operationState.plateId ?: run {
            operationModel.clearCompleted()
            return@LaunchedEffect
        }
        if (ownerPlateId !in projectPlateIds) {
            operationModel.clearCompleted()
            return@LaunchedEffect
        }
        onResultsChanged(results.put(ownerPlateId, completed))
        val batchOwnsResult = batchState.active && batchState.currentPlateId == ownerPlateId
        if (batchOwnsResult) {
            if (batchModel.complete(ownerPlateId)) operationModel.clearCompleted()
            onRemoteResultInvalidated()
            return@LaunchedEffect
        }
        if (ownerPlateId == selectedPlateId) {
            onClearStalePreview()
            operationState.preview?.let(onPreviewChanged)
            onTabSelected(WorkspaceTab.PREVIEW)
        }
        onRemoteResultInvalidated()
    }
    LaunchedEffect(operationState.terminalStatus) {
        val batchOwnsTerminal = batchState.active &&
            batchState.currentPlateId == operationState.plateId
        when (operationState.terminalStatus) {
            SliceTerminalStatus.CANCELED -> if (batchOwnsTerminal) {
                batchModel.cancel(operationState.plateId)
            } else {
                onPresentation(messages.canceled, null)
            }
            SliceTerminalStatus.SLICE_FAILED -> if (batchOwnsTerminal) {
                batchModel.fail(operationState.plateId)
            } else {
                supportEvents.record(SupportEvent.SLICE_FAILED)
                onPresentation(null, messages.failed)
            }
            SliceTerminalStatus.PREVIEW_FAILED -> if (batchOwnsTerminal) {
                batchModel.fail(operationState.plateId)
            } else {
                supportEvents.record(SupportEvent.PREVIEW_FAILED)
                onPresentation(null, messages.previewFailed)
            }
            SliceTerminalStatus.NONE -> Unit
        }
    }
    LaunchedEffect(batchState.terminalStatus) {
        when (batchState.terminalStatus) {
            PlateSliceBatchTerminalStatus.COMPLETED -> {
                onPresentation(messages.allPlatesCompleted, null)
                onPreviewChanged(null)
                if (results.resultFor(selectedPlateId) != null) {
                    onTabSelected(WorkspaceTab.PREVIEW)
                }
            }
            PlateSliceBatchTerminalStatus.CANCELED -> {
                onPresentation(messages.canceled, null)
            }
            PlateSliceBatchTerminalStatus.FAILED -> {
                supportEvents.record(SupportEvent.SLICE_FAILED)
                onPresentation(null, messages.failed)
            }
            PlateSliceBatchTerminalStatus.NONE -> return@LaunchedEffect
        }
        batchModel.consumeTerminal(batchState.terminalStatus)
    }
    LaunchedEffect(projectPlateIds) {
        onResultsChanged(results.retain(projectPlateIds))
    }
}

private const val SLICE_NOTIFICATION_PREFERENCES = "slice_notifications"
private const val SLICE_NOTIFICATION_PERMISSION_ASKED = "permission_asked"

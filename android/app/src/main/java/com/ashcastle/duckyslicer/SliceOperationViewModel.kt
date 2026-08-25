package com.ashcastle.duckyslicer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SliceTerminalStatus {
    NONE,
    CANCELED,
    SLICE_FAILED,
    PREVIEW_FAILED,
}

internal data class SliceOperationState(
    val plateId: String? = null,
    val slicing: Boolean = false,
    val cancellationRequested: Boolean = false,
    val progress: Int = 0,
    val outcome: SliceOutcome? = null,
    val preview: GcodeLayerPreview? = null,
    val previewLoading: Boolean = false,
    val terminalStatus: SliceTerminalStatus = SliceTerminalStatus.NONE,
) {
    val busy: Boolean
        get() = slicing || previewLoading
}

/** Owns or reattaches a foreground slice across Activity and UI-process lifecycles. */
internal class SliceOperationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(SliceOperationState())
    val state: StateFlow<SliceOperationState> = mutableState.asStateFlow()

    private val operationJob = AtomicReference<Job?>(null)
    private val operationSession = AtomicReference<ForegroundSliceSession?>(null)
    private val operationCancellation = AtomicBoolean(false)

    init {
        recoverForegroundSlice()
    }

    fun start(
        plateId: String,
        objects: List<ProjectObject>,
        options: SliceOptions,
        layerPauseEvents: LayerPauseEvents = LayerPauseEvents(),
        layerFilamentChanges: LayerFilamentChanges = LayerFilamentChanges(),
    ): Boolean {
        if (objects.isEmpty() || mutableState.value.busy || operationJob.get()?.isActive == true) {
            return false
        }
        val foregroundSession = try {
            SlicerProcessClient.beginUserSlice(plateId)
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Foreground slice could not start", failure)
            mutableState.value = SliceOperationState(
                plateId = plateId,
                terminalStatus = SliceTerminalStatus.SLICE_FAILED,
            )
            return false
        }
        launchForegroundSlice(foregroundSession, recovering = false) { onProgress ->
            OnDeviceSlicer.slice(
                objects,
                options,
                layerPauseEvents = layerPauseEvents,
                layerFilamentChanges = layerFilamentChanges,
                foregroundSession = foregroundSession,
                cancellationRequested = { cancellationRequested(foregroundSession) },
                onProgress = onProgress,
            )
        }
        return true
    }

    private fun recoverForegroundSlice() {
        val foregroundSession = try {
            SlicerProcessClient.recoverUserSlice()
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Foreground slice could not be recovered", failure)
            mutableState.value = SliceOperationState(
                terminalStatus = SliceTerminalStatus.SLICE_FAILED,
            )
            return
        } ?: return
        if (BuildConfig.DEBUG) Log.i(LOG_TAG, "Reattaching foreground slice")
        launchForegroundSlice(foregroundSession, recovering = true) { onProgress ->
            SlicerProcessClient.awaitRecoveredSlice(foregroundSession, onProgress)
        }
    }

    private fun launchForegroundSlice(
        foregroundSession: ForegroundSliceSession,
        recovering: Boolean,
        slice: ((Int) -> Unit) -> SliceOutcome,
    ) {
        check(operationSession.compareAndSet(null, foregroundSession)) {
            "Another foreground slice session is already owned"
        }
        mutableState.value = SliceOperationState(
            plateId = foregroundSession.plateId,
            slicing = true,
        )
        operationCancellation.set(false)
        val job = viewModelScope.launch {
            var completedState: SliceOperationState? = null
            try {
                val outcome = withContext(Dispatchers.IO) {
                    slice { progress ->
                        mutableState.update { current ->
                            if (current.slicing) {
                                current.copy(progress = maxOf(current.progress, progress.coerceIn(0, 100)))
                            } else {
                                current
                            }
                        }
                    }
                }
                mutableState.value = SliceOperationState(
                    plateId = foregroundSession.plateId,
                    progress = 100,
                    outcome = outcome,
                    previewLoading = true,
                )
                try {
                    if (cancellationRequested(foregroundSession)) {
                        throw SlicingCancelledException()
                    }
                    val preview = withContext(Dispatchers.IO) { buildPreview(outcome, 0, Int.MAX_VALUE) }
                    if (cancellationRequested(foregroundSession)) {
                        throw SlicingCancelledException()
                    }
                    completedState = SliceOperationState(
                        plateId = foregroundSession.plateId,
                        progress = 100,
                        outcome = outcome.copy(layers = preview.layerCount),
                        preview = preview,
                    )
                    if (recovering && BuildConfig.DEBUG) {
                        Log.i(LOG_TAG, "Recovered foreground slice")
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (cancellation: SlicingCancelledException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Initial Preview failed", failure)
                    completedState = SliceOperationState(
                        plateId = foregroundSession.plateId,
                        progress = 100,
                        outcome = outcome,
                        terminalStatus = SliceTerminalStatus.PREVIEW_FAILED,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SlicingCancelledException) {
                completedState = SliceOperationState(
                    plateId = foregroundSession.plateId,
                    terminalStatus = SliceTerminalStatus.CANCELED,
                )
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Retained slice failed", failure)
                completedState = SliceOperationState(
                    plateId = foregroundSession.plateId,
                    terminalStatus = SliceTerminalStatus.SLICE_FAILED,
                )
            } finally {
                if (foregroundSession.cancellationRequested()) {
                    completedState = SliceOperationState(
                        plateId = foregroundSession.plateId,
                        terminalStatus = SliceTerminalStatus.CANCELED,
                    )
                }
                operationSession.compareAndSet(foregroundSession, null)
                foregroundSession.close()
                operationCancellation.set(false)
                operationJob.set(null)
                completedState?.let { mutableState.value = it }
            }
        }
        operationJob.set(job)
    }

    fun loadPreview(
        plateId: String,
        outcome: SliceOutcome,
        startLayer: Int,
        endLayer: Int,
    ): Boolean {
        if (
            mutableState.value.busy ||
            operationJob.get()?.isActive == true ||
            !outcome.isRestorableFrom(DuckySlicerApplication.context().filesDir)
        ) {
            return false
        }
        val previousPreview = mutableState.value.preview
        mutableState.value = SliceOperationState(
            plateId = plateId,
            progress = 100,
            outcome = outcome,
            preview = previousPreview,
            previewLoading = true,
        )
        val job = viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    buildPreview(outcome, startLayer, endLayer)
                }
                mutableState.value = SliceOperationState(
                    plateId = plateId,
                    progress = 100,
                    outcome = outcome,
                    preview = preview,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Preview range failed", failure)
                mutableState.value = SliceOperationState(
                    plateId = plateId,
                    progress = 100,
                    outcome = outcome,
                    preview = previousPreview,
                    terminalStatus = SliceTerminalStatus.PREVIEW_FAILED,
                )
            } finally {
                operationJob.set(null)
            }
        }
        operationJob.set(job)
        return true
    }

    fun cancel() {
        val current = mutableState.value
        if (!current.slicing || current.cancellationRequested) return
        val session = operationSession.get() ?: return
        if (!SlicerProcessClient.cancelUserSliceAsync(session)) return
        mutableState.value = current.copy(cancellationRequested = true)
        operationCancellation.set(true)
    }

    internal fun cancelFromNotificationForTest(): Boolean {
        check(BuildConfig.DEBUG) { "Notification cancellation is available only in debug builds" }
        val session = operationSession.get() ?: return false
        return SlicerProcessClient.cancelFromNotificationForTest(session)
    }

    fun clearCompleted() {
        if (!mutableState.value.busy) mutableState.value = SliceOperationState()
    }

    private fun buildPreview(
        outcome: SliceOutcome,
        startLayer: Int,
        endLayer: Int,
    ): GcodeLayerPreview =
        SliceArtifactLease.acquire(outcome.output).use {
            loadGcodePreview(
                outcome.output.absolutePath,
                startLayer,
                endLayer,
            )
        }

    private fun cancellationRequested(session: ForegroundSliceSession): Boolean =
        operationCancellation.get() || session.cancellationRequested()

    override fun onCleared() {
        operationCancellation.set(true)
        if (mutableState.value.slicing) {
            operationSession.get()?.let(SlicerProcessClient::cancelUserSliceAsync)
        }
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "DuckySliceOperation"
    }
}

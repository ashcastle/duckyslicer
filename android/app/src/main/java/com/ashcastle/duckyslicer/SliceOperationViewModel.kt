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
    private val operationCancellation = AtomicBoolean(false)

    init {
        recoverForegroundSlice()
    }

    fun start(objects: List<ProjectObject>, options: SliceOptions): Boolean {
        if (objects.isEmpty() || mutableState.value.busy || operationJob.get()?.isActive == true) {
            return false
        }
        val foregroundSession = try {
            SlicerProcessClient.beginUserSlice()
        } catch (failure: Exception) {
            if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Foreground slice could not start", failure)
            mutableState.value = SliceOperationState(
                terminalStatus = SliceTerminalStatus.SLICE_FAILED,
            )
            return false
        }
        launchForegroundSlice(foregroundSession, recovering = false) { onProgress ->
            OnDeviceSlicer.slice(
                objects,
                options,
                foregroundSession = foregroundSession,
                cancellationRequested = {
                    operationCancellation.get() || foregroundSession.cancellationRequested()
                },
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
        mutableState.value = SliceOperationState(slicing = true)
        operationCancellation.set(false)
        val job = viewModelScope.launch {
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
                    progress = 100,
                    outcome = outcome,
                    previewLoading = true,
                )
                try {
                    if (foregroundSession.cancellationRequested()) {
                        throw SlicingCancelledException()
                    }
                    val preview = withContext(Dispatchers.IO) { buildPreview(outcome, 0, Int.MAX_VALUE) }
                    if (foregroundSession.cancellationRequested()) {
                        throw SlicingCancelledException()
                    }
                    mutableState.value = SliceOperationState(
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
                    mutableState.value = SliceOperationState(
                        progress = 100,
                        outcome = outcome,
                        terminalStatus = SliceTerminalStatus.PREVIEW_FAILED,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SlicingCancelledException) {
                mutableState.value = SliceOperationState(
                    terminalStatus = SliceTerminalStatus.CANCELED,
                )
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Retained slice failed", failure)
                mutableState.value = SliceOperationState(
                    terminalStatus = SliceTerminalStatus.SLICE_FAILED,
                )
            } finally {
                if (foregroundSession.cancellationRequested()) {
                    mutableState.value = SliceOperationState(
                        terminalStatus = SliceTerminalStatus.CANCELED,
                    )
                }
                foregroundSession.close()
                operationCancellation.set(false)
                operationJob.set(null)
            }
        }
        operationJob.set(job)
    }

    fun loadPreview(outcome: SliceOutcome, startLayer: Int, endLayer: Int): Boolean {
        if (
            mutableState.value.busy ||
            operationJob.get()?.isActive == true ||
            !outcome.isRestorableFrom(DuckySlicerApplication.context().filesDir)
        ) {
            return false
        }
        val previousPreview = mutableState.value.preview
        mutableState.value = SliceOperationState(
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
                    progress = 100,
                    outcome = outcome,
                    preview = preview,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Preview range failed", failure)
                mutableState.value = SliceOperationState(
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
        mutableState.value = current.copy(cancellationRequested = true)
        operationCancellation.set(true)
        SlicerProcessClient.cancelActiveSliceAsync()
    }

    fun clearCompleted() {
        if (!mutableState.value.busy) mutableState.value = SliceOperationState()
    }

    private fun buildPreview(
        outcome: SliceOutcome,
        startLayer: Int,
        endLayer: Int,
    ): GcodeLayerPreview = GcodeLayerPreview.fromNative(
        SliceArtifactLease.acquire(outcome.output).use {
            NativeEngine.previewGcodeRange(
                outcome.output.absolutePath,
                startLayer,
                endLayer,
            )
        },
    )

    override fun onCleared() {
        operationCancellation.set(true)
        SlicerProcessClient.cancelActiveSliceAsync()
        super.onCleared()
    }

    private companion object {
        const val LOG_TAG = "DuckySliceOperation"
    }
}

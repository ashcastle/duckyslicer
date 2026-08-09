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

/** Owns a slice beyond one Activity instance so rotation cannot abandon the worker. */
internal class SliceOperationViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(SliceOperationState())
    val state: StateFlow<SliceOperationState> = mutableState.asStateFlow()

    private val operationJob = AtomicReference<Job?>(null)
    private val operationCancellation = AtomicBoolean(false)

    fun start(objects: List<ProjectObject>, options: SliceOptions): Boolean {
        if (objects.isEmpty() || mutableState.value.busy || operationJob.get()?.isActive == true) {
            return false
        }
        mutableState.value = SliceOperationState(slicing = true)
        operationCancellation.set(false)
        val job = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    OnDeviceSlicer.slice(
                        objects,
                        options,
                        cancellationRequested = operationCancellation::get,
                    ) { progress ->
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
                    val preview = withContext(Dispatchers.IO) { buildPreview(outcome, 0, Int.MAX_VALUE) }
                    mutableState.value = SliceOperationState(
                        progress = 100,
                        outcome = outcome.copy(layers = preview.layerCount),
                        preview = preview,
                    )
                } catch (cancellation: CancellationException) {
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
                operationCancellation.set(false)
                operationJob.set(null)
            }
        }
        operationJob.set(job)
        return true
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

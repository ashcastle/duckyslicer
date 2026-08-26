package com.ashcastle.duckyslicer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PlateSliceBatchTerminalStatus {
    NONE,
    COMPLETED,
    CANCELED,
    FAILED,
}

internal data class PlateSliceBatchState(
    val plateIds: List<String> = emptyList(),
    val completedCount: Int = 0,
    val currentPlateId: String? = null,
    val cancellationRequested: Boolean = false,
    val terminalStatus: PlateSliceBatchTerminalStatus = PlateSliceBatchTerminalStatus.NONE,
) {
    val active: Boolean
        get() = terminalStatus == PlateSliceBatchTerminalStatus.NONE &&
            plateIds.isNotEmpty() && completedCount < plateIds.size

    val currentNumber: Int?
        get() = if (active) completedCount + 1 else null

    init {
        require(plateIds.size <= MAX_PROJECT_PLATES) { "Too many queued plates" }
        require(plateIds.all { it.length in 1..ProjectStore.MAX_ID_LENGTH }) {
            "Invalid queued plate id"
        }
        require(plateIds.toSet().size == plateIds.size) { "Duplicate queued plate id" }
        require(completedCount in 0..plateIds.size) { "Invalid completed plate count" }
        require(currentPlateId == null || currentPlateId == plateIds.getOrNull(completedCount)) {
            "Current plate does not match the queue"
        }
        require(!cancellationRequested || active) { "Inactive batch cannot be canceling" }
        require(
            terminalStatus == PlateSliceBatchTerminalStatus.NONE ||
                (plateIds.isEmpty() && completedCount == 0 && currentPlateId == null),
        ) { "Terminal batch must not retain work" }
    }
}

internal data class PlateSliceBatchProgress(
    val current: Int,
    val total: Int,
) {
    init {
        require(total >= 2) { "Batch progress requires multiple plates" }
        require(current in 1..total) { "Invalid batch plate progress" }
    }
}

internal data class SliceProgress(
    val percent: Int,
    val batch: PlateSliceBatchProgress? = null,
) {
    init {
        require(percent in 0..100) { "Invalid slice progress" }
    }
}

/** Retains a bounded sequential plate queue without retaining model geometry in saved state. */
internal class PlateSliceBatchViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(restore())
    val state: StateFlow<PlateSliceBatchState> = mutableState.asStateFlow()

    fun start(plateIds: List<String>): Boolean {
        if (mutableState.value.active || plateIds.size < 2) return false
        val next = runCatching { PlateSliceBatchState(plateIds = plateIds.toList()) }
            .getOrNull() ?: return false
        set(next)
        return true
    }

    fun claimNext(): String? {
        val current = mutableState.value
        if (!current.active || current.cancellationRequested) return null
        current.currentPlateId?.let { return it }
        val plateId = current.plateIds.getOrNull(current.completedCount) ?: return null
        set(current.copy(currentPlateId = plateId))
        return plateId
    }

    fun complete(plateId: String): Boolean {
        val current = mutableState.value
        if (!current.active || current.currentPlateId != plateId) return false
        val completed = current.completedCount + 1
        if (completed == current.plateIds.size) {
            set(terminal(PlateSliceBatchTerminalStatus.COMPLETED))
        } else {
            set(current.copy(completedCount = completed, currentPlateId = null))
        }
        return true
    }

    fun requestCancellation(): Boolean {
        val current = mutableState.value
        if (!current.active || current.cancellationRequested) return false
        if (current.currentPlateId == null) {
            set(terminal(PlateSliceBatchTerminalStatus.CANCELED))
        } else {
            set(current.copy(cancellationRequested = true))
        }
        return true
    }

    fun fail(plateId: String?): Boolean = finishCurrent(
        plateId,
        PlateSliceBatchTerminalStatus.FAILED,
    )

    fun cancel(plateId: String?): Boolean = finishCurrent(
        plateId,
        PlateSliceBatchTerminalStatus.CANCELED,
    )

    fun consumeTerminal(status: PlateSliceBatchTerminalStatus): Boolean {
        val current = mutableState.value
        if (current.terminalStatus != status || status == PlateSliceBatchTerminalStatus.NONE) {
            return false
        }
        set(PlateSliceBatchState())
        return true
    }

    private fun finishCurrent(
        plateId: String?,
        status: PlateSliceBatchTerminalStatus,
    ): Boolean {
        val current = mutableState.value
        if (!current.active || current.currentPlateId != plateId) return false
        set(terminal(status))
        return true
    }

    private fun restore(): PlateSliceBatchState {
        val terminal = savedStateHandle.get<String>(KEY_TERMINAL)
            ?.let { runCatching { PlateSliceBatchTerminalStatus.valueOf(it) }.getOrNull() }
            ?: PlateSliceBatchTerminalStatus.NONE
        if (terminal != PlateSliceBatchTerminalStatus.NONE) return terminal(terminal)
        val ids = savedStateHandle.get<ArrayList<String>>(KEY_PLATE_IDS)?.toList().orEmpty()
        if (ids.isEmpty()) return PlateSliceBatchState()
        return runCatching {
            PlateSliceBatchState(
                plateIds = ids,
                completedCount = savedStateHandle[KEY_COMPLETED_COUNT] ?: 0,
                currentPlateId = savedStateHandle[KEY_CURRENT_PLATE_ID],
                cancellationRequested = savedStateHandle[KEY_CANCELLATION_REQUESTED] ?: false,
            )
        }.getOrElse {
            clearSavedState()
            PlateSliceBatchState()
        }
    }

    private fun set(next: PlateSliceBatchState) {
        mutableState.value = next
        savedStateHandle[KEY_PLATE_IDS] = ArrayList(next.plateIds)
        savedStateHandle[KEY_COMPLETED_COUNT] = next.completedCount
        savedStateHandle[KEY_CURRENT_PLATE_ID] = next.currentPlateId
        savedStateHandle[KEY_CANCELLATION_REQUESTED] = next.cancellationRequested
        savedStateHandle[KEY_TERMINAL] = next.terminalStatus.name
    }

    private fun clearSavedState() {
        savedStateHandle[KEY_PLATE_IDS] = ArrayList<String>()
        savedStateHandle[KEY_COMPLETED_COUNT] = 0
        savedStateHandle[KEY_CURRENT_PLATE_ID] = null
        savedStateHandle[KEY_CANCELLATION_REQUESTED] = false
        savedStateHandle[KEY_TERMINAL] = PlateSliceBatchTerminalStatus.NONE.name
    }

    private fun terminal(status: PlateSliceBatchTerminalStatus) = PlateSliceBatchState(
        terminalStatus = status,
    )

    private companion object {
        const val KEY_PLATE_IDS = "plate_slice_batch_ids"
        const val KEY_COMPLETED_COUNT = "plate_slice_batch_completed_count"
        const val KEY_CURRENT_PLATE_ID = "plate_slice_batch_current_id"
        const val KEY_CANCELLATION_REQUESTED = "plate_slice_batch_cancel_requested"
        const val KEY_TERMINAL = "plate_slice_batch_terminal"
    }
}

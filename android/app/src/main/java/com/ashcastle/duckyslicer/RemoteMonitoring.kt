package com.ashcastle.duckyslicer

internal const val ACTIVE_REMOTE_MONITORING_INTERVAL_MILLIS = 5_000L
internal const val INITIAL_REMOTE_MONITORING_INTERVAL_MILLIS = 10_000L
internal const val IDLE_REMOTE_MONITORING_INTERVAL_MILLIS = 30_000L

internal fun RemoteDeviceStatus.isPrintActive(): Boolean {
    val normalized = state.lowercase()
    return normalized.contains("print") || normalized.contains("pause")
}

internal fun remoteMonitoringIntervalMillis(
    status: RemoteDeviceStatus?,
    message: RemoteOperationMessage?,
): Long = when {
    message?.isError == true -> IDLE_REMOTE_MONITORING_INTERVAL_MILLIS
    status == null -> INITIAL_REMOTE_MONITORING_INTERVAL_MILLIS
    status.isPrintActive() -> ACTIVE_REMOTE_MONITORING_INTERVAL_MILLIS
    else -> IDLE_REMOTE_MONITORING_INTERVAL_MILLIS
}

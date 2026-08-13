package com.ashcastle.duckyslicer

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps optional model acceleration work behind rendering and touch handling.
 *
 * A large imported model can need a picking hierarchy and place-on-face candidates, but neither
 * result is required to draw the first useful frame. Running those CPU-heavy passes concurrently
 * on a small device can starve the UI and GL threads even though the renderer itself is fast.
 */
private val modelPreparationDispatcher = Dispatchers.Default.limitedParallelism(1)

internal suspend fun <T> withModelPreparationContext(
    block: suspend CoroutineScope.() -> T,
): T = withContext(modelPreparationDispatcher) {
    val threadId = Process.myTid()
    val previousPriority = Process.getThreadPriority(threadId)
    try {
        Process.setThreadPriority(threadId, Process.THREAD_PRIORITY_BACKGROUND)
        block()
    } finally {
        Process.setThreadPriority(threadId, previousPriority)
    }
}

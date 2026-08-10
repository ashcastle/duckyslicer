package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.RequiresApi

@RequiresApi(30)
internal object Api30ProcessExitHistory {
    fun read(context: Context): List<SupportProcessExit> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        return runCatching {
            manager.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                MAX_SUPPORT_PROCESS_EXITS,
            ).take(MAX_SUPPORT_PROCESS_EXITS).map { info ->
                SupportProcessExit(
                    timestampMillis = info.timestamp.coerceAtLeast(0L),
                    process = supportProcessKind(context.packageName, info.processName),
                    reason = SupportExitReason.fromPlatformCode(info.reason),
                )
            }
        }.getOrDefault(emptyList())
    }
}

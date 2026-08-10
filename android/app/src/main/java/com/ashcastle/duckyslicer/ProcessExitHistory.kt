package com.ashcastle.duckyslicer

import android.content.Context
import android.os.Build

internal enum class SupportProcessKind {
    APP,
    SLICER,
    OTHER,
}

internal enum class SupportExitReason(val platformCode: Int) {
    UNKNOWN(0),
    EXIT_SELF(1),
    SIGNALED(2),
    LOW_MEMORY(3),
    CRASH(4),
    CRASH_NATIVE(5),
    ANR(6),
    INITIALIZATION_FAILURE(7),
    PERMISSION_CHANGE(8),
    EXCESSIVE_RESOURCE_USAGE(9),
    USER_REQUESTED(10),
    USER_STOPPED(11),
    DEPENDENCY_DIED(12),
    OTHER(13),
    FREEZER(14),
    PACKAGE_STATE_CHANGE(15),
    PACKAGE_UPDATED(16),
    ;

    companion object {
        fun fromPlatformCode(code: Int): SupportExitReason =
            entries.firstOrNull { it.platformCode == code } ?: UNKNOWN
    }
}

internal data class SupportProcessExit(
    val timestampMillis: Long,
    val process: SupportProcessKind,
    val reason: SupportExitReason,
)

internal fun readRecentProcessExits(context: Context): List<SupportProcessExit> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Api30ProcessExitHistory.read(context.applicationContext)
    } else {
        emptyList()
    }

internal fun supportProcessKind(packageName: String, processName: String): SupportProcessKind =
    when (processName) {
        packageName -> SupportProcessKind.APP
        "$packageName:slicer" -> SupportProcessKind.SLICER
        else -> SupportProcessKind.OTHER
    }

internal const val MAX_SUPPORT_PROCESS_EXITS = 4

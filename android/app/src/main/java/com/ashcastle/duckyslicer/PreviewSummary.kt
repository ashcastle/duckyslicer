package com.ashcastle.duckyslicer

import kotlin.math.roundToInt

internal data class PreviewDuration(
    val hours: Int,
    val minutes: Int,
    val underOneMinute: Boolean,
)

internal data class PreviewSummary(
    val duration: PreviewDuration,
    val filamentGrams: Float,
    val filamentMeters: Float,
)

internal fun SliceOutcome.previewSummary(): PreviewSummary {
    require(estimatedSeconds.isFinite() && estimatedSeconds >= 0f) {
        "Invalid preview time estimate"
    }
    require(filamentMm.isFinite() && filamentMm >= 0f) {
        "Invalid preview filament length"
    }
    require(filamentGrams.isFinite() && filamentGrams >= 0f) {
        "Invalid preview filament mass"
    }
    val roundedMinutes = (estimatedSeconds / SECONDS_PER_MINUTE)
        .roundToInt()
        .coerceAtLeast(1)
    return PreviewSummary(
        duration = PreviewDuration(
            hours = roundedMinutes / MINUTES_PER_HOUR,
            minutes = roundedMinutes % MINUTES_PER_HOUR,
            underOneMinute = estimatedSeconds < SECONDS_PER_MINUTE,
        ),
        filamentGrams = filamentGrams,
        filamentMeters = filamentMm / MILLIMETERS_PER_METER,
    )
}

private const val SECONDS_PER_MINUTE = 60f
private const val MINUTES_PER_HOUR = 60
private const val MILLIMETERS_PER_METER = 1_000f

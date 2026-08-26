package com.ashcastle.duckyslicer

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

internal fun readGcodePreviewSummary(file: File): PreviewSummary {
    require(file.isFile) { "G-code document is unavailable" }
    RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        val headBytes = minOf(length, METADATA_HEAD_BYTES.toLong()).toInt()
        val head = ByteArray(headBytes)
        input.readFully(head)
        val tailStart = (length - METADATA_TAIL_BYTES).coerceAtLeast(headBytes.toLong())
        val tail = if (tailStart < length) {
            input.seek(tailStart)
            ByteArray((length - tailStart).toInt()).also(input::readFully)
        } else {
            ByteArray(0)
        }
        return parseGcodePreviewSummary(
            buildString(head.size + tail.size + 1) {
                append(String(head, StandardCharsets.UTF_8))
                append('\n')
                append(String(tail, StandardCharsets.UTF_8))
            },
        )
    }
}

internal fun parseGcodePreviewSummary(metadata: String): PreviewSummary {
    val normalDuration = metadata.lineSequence().mapNotNull { line ->
        ORCA_TIME.matchEntire(line.trim())?.groupValues?.get(1)
    }.lastOrNull()?.let(::parseDurationSeconds)
    val seconds = normalDuration ?: metadata.lineSequence().mapNotNull { line ->
        COMMON_TIME.matchEntire(line.trim())?.groupValues?.get(1)?.toDoubleOrNull()
    }.lastOrNull()?.validStatistic()

    val totalGrams = lastScalar(metadata, ORCA_TOTAL_GRAMS)
    val toolGrams = lastNumberList(metadata, ORCA_TOOL_GRAMS)?.sum()
    val millimeters = lastNumberList(metadata, ORCA_TOOL_MILLIMETERS)?.sum()
    val curaMeters = lastScalar(metadata, CURA_FILAMENT_METERS)

    return PreviewSummary(
        duration = seconds?.let(::previewDuration),
        filamentGrams = (totalGrams ?: toolGrams)?.toFloat(),
        filamentMeters = (millimeters?.div(1_000.0) ?: curaMeters)?.toFloat(),
    )
}

private fun lastScalar(metadata: String, pattern: Regex): Double? =
    metadata.lineSequence().mapNotNull { line ->
        pattern.matchEntire(line.trim())?.groupValues?.get(1)?.toDoubleOrNull()?.validStatistic()
    }.lastOrNull()

private fun lastNumberList(metadata: String, pattern: Regex): List<Double>? =
    metadata.lineSequence().mapNotNull { line ->
        val payload = pattern.matchEntire(line.trim())?.groupValues?.get(1) ?: return@mapNotNull null
        payload.split(',').map { value -> value.trim().toDoubleOrNull()?.validStatistic() ?: return@mapNotNull null }
    }.lastOrNull()

private fun parseDurationSeconds(value: String): Double? {
    var total = 0.0
    var matched = false
    DURATION_TOKEN.findAll(value.lowercase()).forEach { token ->
        val amount = token.groupValues[1].toDoubleOrNull()?.validStatistic() ?: return null
        total += amount * when (token.groupValues[2]) {
            "d" -> 86_400.0
            "h" -> 3_600.0
            "m" -> 60.0
            else -> 1.0
        }
        matched = true
    }
    return total.validStatistic().takeIf { matched }
}

private fun previewDuration(seconds: Double): PreviewDuration {
    val roundedMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
    return PreviewDuration(
        hours = roundedMinutes / 60,
        minutes = roundedMinutes % 60,
        underOneMinute = seconds < 60.0,
    )
}

private fun Double.validStatistic(): Double? = takeIf { it.isFinite() && it >= 0.0 }

private val ORCA_TIME = Regex(";\\s*estimated printing time \\(normal mode\\)\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
private val COMMON_TIME = Regex(";\\s*TIME\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
private val ORCA_TOTAL_GRAMS = Regex(";\\s*total filament used \\[g]\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
private val ORCA_TOOL_GRAMS = Regex(";\\s*filament used \\[g]\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
private val ORCA_TOOL_MILLIMETERS = Regex(";\\s*filament used \\[mm]\\s*=\\s*(.+)", RegexOption.IGNORE_CASE)
private val CURA_FILAMENT_METERS = Regex(";\\s*Filament used\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)m", RegexOption.IGNORE_CASE)
private val DURATION_TOKEN = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([dhms])")
private const val METADATA_HEAD_BYTES = 128 * 1_024
private const val METADATA_TAIL_BYTES = 512 * 1_024

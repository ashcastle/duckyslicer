package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.OutputStream
import java.time.Instant
import java.util.Locale

internal enum class SupportEvent {
    ARRANGE_FAILED,
    AUTO_LAY_FAILED,
    FILAMENT_PROFILE_SAVE_FAILED,
    GCODE_EXPORT_FAILED,
    MODEL_IMPORT_FAILED,
    MODEL_TOO_LARGE,
    PREVIEW_FAILED,
    PRINTER_PROFILE_SAVE_FAILED,
    PROFILE_STORAGE_UNAVAILABLE,
    PROJECT_SAVE_FAILED,
    PROJECT_STORAGE_UNAVAILABLE,
    REMOTE_AUTH_FAILED,
    REMOTE_COMMAND_FAILED,
    REMOTE_CONNECTION_FAILED,
    REMOTE_PROFILE_SAVE_FAILED,
    REMOTE_STORAGE_UNAVAILABLE,
    SLICE_FAILED,
    SLICING_PROFILE_SAVE_FAILED,
}

internal data class SupportEventRecord(
    val timestampMillis: Long,
    val event: SupportEvent,
)

internal data class SupportReportSnapshot(
    val generatedAtMillis: Long,
    val appVersion: String,
    val buildType: String,
    val androidRelease: String,
    val androidApi: Int,
    val deviceManufacturer: String,
    val deviceModel: String,
    val primaryAbi: String,
    val pageSizeBytes: Long,
    val appMemoryClassMb: Int,
    val lowRamDevice: Boolean,
    val maxHeapMb: Long,
    val availableStorageMb: Long,
    val localeTag: String,
    val requestedPreviewDetail: PreviewDetail,
    val effectivePreviewDetail: PreviewDetail,
    val previewRenderingMode: PreviewRenderingMode,
    val toolpathOpacityPercent: Int,
    val toolpathDepthContrastPercent: Int,
    val keepScreenAwake: Boolean,
    val confirmRemotePrint: Boolean,
    val connectionTimeoutSeconds: Int,
    val events: List<SupportEventRecord>,
    val processExits: List<SupportProcessExit>,
)

internal class SupportEventJournal(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences(
        SUPPORT_EVENT_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun record(event: SupportEvent) {
        val next = (
            decodeSupportEvents(preferences.getString(SUPPORT_EVENT_KEY, null).orEmpty()) +
                SupportEventRecord(nowMillis().coerceAtLeast(0L), event)
            ).takeLast(MAX_SUPPORT_EVENTS)
        preferences.edit().putString(SUPPORT_EVENT_KEY, encodeSupportEvents(next)).apply()
    }

    @Synchronized
    fun snapshot(): List<SupportEventRecord> =
        decodeSupportEvents(preferences.getString(SUPPORT_EVENT_KEY, null).orEmpty())
}

internal fun createSupportReport(context: Context, settings: AppSettings): String {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val capabilities = previewDeviceCapabilities(context)
    val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(0L)
    val availableStorage = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }
        .getOrDefault(0L)
    return renderSupportReport(
        SupportReportSnapshot(
            generatedAtMillis = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            androidApi = Build.VERSION.SDK_INT,
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            pageSizeBytes = pageSize,
            appMemoryClassMb = manager?.memoryClass ?: 0,
            lowRamDevice = manager?.isLowRamDevice ?: true,
            maxHeapMb = Runtime.getRuntime().maxMemory() / MEBIBYTE,
            availableStorageMb = availableStorage / MEBIBYTE,
            localeTag = Locale.getDefault().toLanguageTag(),
            requestedPreviewDetail = settings.previewDetail,
            effectivePreviewDetail = resolvePreviewDetail(settings.previewDetail, capabilities),
            previewRenderingMode = settings.previewRenderingMode,
            toolpathOpacityPercent = (settings.toolpathOpacity * 100f).toInt().coerceIn(0, 100),
            toolpathDepthContrastPercent =
                (settings.toolpathDepthContrast * 100f).toInt().coerceIn(0, 100),
            keepScreenAwake = settings.keepScreenAwakeWhileWorking,
            confirmRemotePrint = settings.confirmBeforeRemotePrint,
            connectionTimeoutSeconds = settings.connectionTimeoutSeconds,
            events = SupportEventJournal(context.applicationContext).snapshot(),
            processExits = readRecentProcessExits(context.applicationContext),
        ),
    )
}

internal fun renderSupportReport(snapshot: SupportReportSnapshot): String = buildString {
    appendLine("DuckySlicer support details")
    appendLine("schema=2")
    appendLine("generated_utc=${supportTimestamp(snapshot.generatedAtMillis)}")
    appendLine("app_version=${supportValue(snapshot.appVersion)}")
    appendLine("build_type=${supportValue(snapshot.buildType)}")
    appendLine("android_release=${supportValue(snapshot.androidRelease)}")
    appendLine("android_api=${snapshot.androidApi.coerceAtLeast(0)}")
    appendLine("device_manufacturer=${supportValue(snapshot.deviceManufacturer)}")
    appendLine("device_model=${supportValue(snapshot.deviceModel)}")
    appendLine("primary_abi=${supportValue(snapshot.primaryAbi)}")
    appendLine("page_size_bytes=${snapshot.pageSizeBytes.coerceAtLeast(0L)}")
    appendLine("app_memory_class_mb=${snapshot.appMemoryClassMb.coerceAtLeast(0)}")
    appendLine("low_ram_device=${snapshot.lowRamDevice}")
    appendLine("max_heap_mb=${snapshot.maxHeapMb.coerceAtLeast(0L)}")
    appendLine("available_storage_mb=${snapshot.availableStorageMb.coerceAtLeast(0L)}")
    appendLine("locale=${supportValue(snapshot.localeTag)}")
    appendLine("preview_detail_requested=${snapshot.requestedPreviewDetail.name}")
    appendLine("preview_detail_effective=${snapshot.effectivePreviewDetail.name}")
    appendLine("preview_display=${snapshot.previewRenderingMode.name}")
    appendLine("toolpath_visibility_percent=${snapshot.toolpathOpacityPercent.coerceIn(0, 100)}")
    appendLine(
        "toolpath_depth_contrast_percent=" +
            snapshot.toolpathDepthContrastPercent.coerceIn(0, 100),
    )
    appendLine("keep_screen_awake=${snapshot.keepScreenAwake}")
    appendLine("confirm_remote_print=${snapshot.confirmRemotePrint}")
    appendLine("connection_timeout_seconds=${snapshot.connectionTimeoutSeconds.coerceIn(5, 60)}")
    appendLine("recent_problem_count=${snapshot.events.takeLast(MAX_SUPPORT_EVENTS).size}")
    snapshot.events.takeLast(MAX_SUPPORT_EVENTS).forEachIndexed { index, record ->
        appendLine("recent_problem.$index.utc=${supportTimestamp(record.timestampMillis)}")
        appendLine("recent_problem.$index.code=${record.event.name}")
    }
    appendLine(
        "previous_exit_count=" +
            snapshot.processExits.take(MAX_SUPPORT_PROCESS_EXITS).size,
    )
    snapshot.processExits.take(MAX_SUPPORT_PROCESS_EXITS).forEachIndexed { index, record ->
        appendLine("previous_exit.$index.utc=${supportTimestamp(record.timestampMillis)}")
        appendLine("previous_exit.$index.process=${record.process.name}")
        appendLine("previous_exit.$index.reason=${record.reason.name}")
    }
    appendLine("private_content_included=false")
    appendLine("models_included=false")
    appendLine("gcode_included=false")
    appendLine("file_names_included=false")
    appendLine("printer_addresses_included=false")
    appendLine("access_keys_included=false")
    appendLine("raw_process_names_included=false")
    appendLine("exit_descriptions_included=false")
    appendLine("exit_traces_included=false")
    appendLine("exit_memory_samples_included=false")
}.also { report ->
    check(report.toByteArray(Charsets.UTF_8).size <= MAX_SUPPORT_REPORT_BYTES) {
        "support_report_too_large"
    }
}

internal fun writeSupportReport(output: OutputStream, report: String) {
    val bytes = report.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_SUPPORT_REPORT_BYTES) { "support_report_too_large" }
    output.write(bytes)
    output.flush()
}

internal fun encodeSupportEvents(events: List<SupportEventRecord>): String =
    events.takeLast(MAX_SUPPORT_EVENTS).joinToString(separator = "\n") { record ->
        "${record.timestampMillis.coerceAtLeast(0L)}|${record.event.name}"
    }

internal fun decodeSupportEvents(source: String): List<SupportEventRecord> {
    if (source.length > MAX_SUPPORT_EVENT_STORAGE_CHARS) return emptyList()
    return source.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('|')
        if (separator <= 0 || separator == line.lastIndex) return@mapNotNull null
        val timestamp = line.substring(0, separator).toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: return@mapNotNull null
        val event = runCatching { SupportEvent.valueOf(line.substring(separator + 1)) }
            .getOrNull()
            ?: return@mapNotNull null
        SupportEventRecord(timestamp, event)
    }.toList().takeLast(MAX_SUPPORT_EVENTS)
}

private fun supportTimestamp(timestampMillis: Long): String =
    runCatching { Instant.ofEpochMilli(timestampMillis.coerceAtLeast(0L)).toString() }
        .getOrDefault("1970-01-01T00:00:00Z")

private fun supportValue(source: String): String = source
    .map { character -> if (character.isISOControl()) ' ' else character }
    .joinToString(separator = "")
    .trim()
    .take(MAX_SUPPORT_VALUE_CHARS)

private const val SUPPORT_EVENT_PREFERENCES = "support_events"
private const val SUPPORT_EVENT_KEY = "recent_problem_categories"
internal const val MAX_SUPPORT_EVENTS = 32
internal const val MAX_SUPPORT_REPORT_BYTES = 16 * 1_024
private const val MAX_SUPPORT_EVENT_STORAGE_CHARS = 8 * 1_024
private const val MAX_SUPPORT_VALUE_CHARS = 120
private const val MEBIBYTE = 1_048_576L

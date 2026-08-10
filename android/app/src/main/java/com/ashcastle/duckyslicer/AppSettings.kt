package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.Context

enum class PreviewDetail {
    AUTOMATIC,
    PERFORMANCE,
    BALANCED,
    DETAIL,
}

internal data class PreviewDeviceCapabilities(
    val isLowRamDevice: Boolean,
    val appMemoryClassMb: Int,
)

internal fun previewDeviceCapabilities(context: Context): PreviewDeviceCapabilities {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return PreviewDeviceCapabilities(
        isLowRamDevice = manager?.isLowRamDevice ?: true,
        appMemoryClassMb = manager?.memoryClass ?: 0,
    )
}

internal fun resolvePreviewDetail(
    requested: PreviewDetail,
    capabilities: PreviewDeviceCapabilities,
): PreviewDetail = when (requested) {
    PreviewDetail.AUTOMATIC -> if (
        capabilities.isLowRamDevice || capabilities.appMemoryClassMb <= 0 ||
        capabilities.appMemoryClassMb <= 192
    ) {
        PreviewDetail.PERFORMANCE
    } else {
        PreviewDetail.BALANCED
    }
    else -> requested
}

internal fun previewDetailForInteraction(
    detail: PreviewDetail,
    interactionActive: Boolean,
): PreviewDetail {
    if (!interactionActive) return detail.concreteOrBalanced()
    return when (detail.concreteOrBalanced()) {
        PreviewDetail.PERFORMANCE -> PreviewDetail.PERFORMANCE
        PreviewDetail.BALANCED -> PreviewDetail.PERFORMANCE
        PreviewDetail.DETAIL -> PreviewDetail.BALANCED
        PreviewDetail.AUTOMATIC -> error("concreteOrBalanced must resolve automatic detail")
    }
}

internal fun depthPreviewSegmentBudget(detail: PreviewDetail): Int = when (detail.concreteOrBalanced()) {
    PreviewDetail.PERFORMANCE -> 24_000
    PreviewDetail.BALANCED -> 80_000
    PreviewDetail.DETAIL -> GcodeLayerPreview.MAX_SEGMENTS
    PreviewDetail.AUTOMATIC -> error("concreteOrBalanced must resolve automatic detail")
}

internal fun compatibilityPreviewSegmentBudget(
    detail: PreviewDetail,
    refined: Boolean,
): Int = when (detail.concreteOrBalanced()) {
    PreviewDetail.PERFORMANCE -> if (refined) 2_000 else 250
    PreviewDetail.BALANCED -> if (refined) 4_000 else 450
    PreviewDetail.DETAIL -> if (refined) 8_000 else 700
    PreviewDetail.AUTOMATIC -> error("concreteOrBalanced must resolve automatic detail")
}

private fun PreviewDetail.concreteOrBalanced(): PreviewDetail = when (this) {
    PreviewDetail.AUTOMATIC -> PreviewDetail.BALANCED
    else -> this
}

enum class PreviewRenderingMode {
    DEPTH_TESTED,
    COMPATIBILITY,
}

data class AppSettings(
    val previewDetail: PreviewDetail = PreviewDetail.AUTOMATIC,
    val previewRenderingMode: PreviewRenderingMode = PreviewRenderingMode.DEPTH_TESTED,
    val toolpathOpacity: Float = 0.92f,
    val toolpathDepthContrast: Float = 0.78f,
    val keepScreenAwakeWhileWorking: Boolean = true,
    val confirmBeforeRemotePrint: Boolean = true,
    val connectionTimeoutSeconds: Int = 15,
)

internal fun AppSettings.normalized(): AppSettings = copy(
    toolpathOpacity = toolpathOpacity.takeIf(Float::isFinite)?.coerceIn(0.3f, 1f) ?: 0.92f,
    toolpathDepthContrast = toolpathDepthContrast.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
        ?: 0.78f,
    connectionTimeoutSeconds = connectionTimeoutSeconds.coerceIn(5, 60),
)

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): AppSettings = AppSettings(
        previewDetail = runCatching {
            PreviewDetail.valueOf(
                preferences.getString("preview_detail", PreviewDetail.AUTOMATIC.name)
                    ?: PreviewDetail.AUTOMATIC.name,
            )
        }.getOrDefault(PreviewDetail.AUTOMATIC),
        previewRenderingMode = runCatching {
            PreviewRenderingMode.valueOf(
                preferences.getString("preview_rendering_mode", PreviewRenderingMode.DEPTH_TESTED.name)
                    ?: PreviewRenderingMode.DEPTH_TESTED.name,
            )
        }.getOrDefault(PreviewRenderingMode.DEPTH_TESTED),
        toolpathOpacity = runCatching { preferences.getFloat("toolpath_opacity", 0.92f) }
            .getOrDefault(0.92f),
        toolpathDepthContrast = runCatching {
            preferences.getFloat("toolpath_depth_contrast", 0.78f)
        }.getOrDefault(0.78f),
        keepScreenAwakeWhileWorking = runCatching {
            preferences.getBoolean("keep_screen_awake", true)
        }.getOrDefault(true),
        confirmBeforeRemotePrint = runCatching {
            preferences.getBoolean("confirm_remote_print", true)
        }.getOrDefault(true),
        connectionTimeoutSeconds = runCatching {
            preferences.getInt("connection_timeout_seconds", 15)
        }.getOrDefault(15),
    ).normalized()

    @Synchronized
    fun save(settings: AppSettings): Boolean {
        val normalized = settings.normalized()
        return preferences.edit()
            .putString("preview_detail", normalized.previewDetail.name)
            .putString("preview_rendering_mode", normalized.previewRenderingMode.name)
            .putFloat("toolpath_opacity", normalized.toolpathOpacity)
            .putFloat("toolpath_depth_contrast", normalized.toolpathDepthContrast)
            .putBoolean("keep_screen_awake", normalized.keepScreenAwakeWhileWorking)
            .putBoolean("confirm_remote_print", normalized.confirmBeforeRemotePrint)
            .putInt("connection_timeout_seconds", normalized.connectionTimeoutSeconds)
            .commit()
    }
}

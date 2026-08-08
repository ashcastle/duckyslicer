package com.ashcastle.duckyslicer

import android.content.Context

enum class PreviewDetail {
    PERFORMANCE,
    BALANCED,
    DETAIL,
}

data class AppSettings(
    val previewDetail: PreviewDetail = PreviewDetail.BALANCED,
    val toolpathOpacity: Float = 0.92f,
    val toolpathDepthContrast: Float = 0.78f,
    val keepScreenAwakeWhileWorking: Boolean = true,
    val confirmBeforeRemotePrint: Boolean = true,
    val connectionTimeoutSeconds: Int = 15,
)

class AppSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        previewDetail = runCatching {
            PreviewDetail.valueOf(
                preferences.getString("preview_detail", PreviewDetail.BALANCED.name)
                    ?: PreviewDetail.BALANCED.name,
            )
        }.getOrDefault(PreviewDetail.BALANCED),
        toolpathOpacity = preferences.getFloat("toolpath_opacity", 0.92f).coerceIn(0.3f, 1f),
        toolpathDepthContrast = preferences.getFloat("toolpath_depth_contrast", 0.78f).coerceIn(0f, 1f),
        keepScreenAwakeWhileWorking = preferences.getBoolean("keep_screen_awake", true),
        confirmBeforeRemotePrint = preferences.getBoolean("confirm_remote_print", true),
        connectionTimeoutSeconds = preferences.getInt("connection_timeout_seconds", 15).coerceIn(5, 60),
    )

    fun save(settings: AppSettings) {
        preferences.edit()
            .putString("preview_detail", settings.previewDetail.name)
            .putFloat("toolpath_opacity", settings.toolpathOpacity.coerceIn(0.3f, 1f))
            .putFloat("toolpath_depth_contrast", settings.toolpathDepthContrast.coerceIn(0f, 1f))
            .putBoolean("keep_screen_awake", settings.keepScreenAwakeWhileWorking)
            .putBoolean("confirm_remote_print", settings.confirmBeforeRemotePrint)
            .putInt("connection_timeout_seconds", settings.connectionTimeoutSeconds.coerceIn(5, 60))
            .apply()
    }
}

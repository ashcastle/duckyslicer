package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.Context
import kotlin.math.roundToInt

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
    @Suppress("UNUSED_PARAMETER")
    capabilities: PreviewDeviceCapabilities,
): PreviewDetail = when (requested) {
    // RAM capacity is not a useful proxy for mobile GPU throughput. Automatic mode must
    // start from the measured smooth tier; users can still explicitly request more detail.
    PreviewDetail.AUTOMATIC -> PreviewDetail.PERFORMANCE
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
    PreviewDetail.PERFORMANCE -> 10_000
    PreviewDetail.BALANCED -> 80_000
    PreviewDetail.DETAIL -> GcodeLayerPreview.MAX_SEGMENTS
    PreviewDetail.AUTOMATIC -> error("concreteOrBalanced must resolve automatic detail")
}

internal fun depthPreviewInteractionSegmentBudget(detail: PreviewDetail): Int =
    when (detail.concreteOrBalanced()) {
        PreviewDetail.PERFORMANCE, PreviewDetail.BALANCED -> 4_000
        PreviewDetail.DETAIL -> 10_000
        PreviewDetail.AUTOMATIC -> error("concreteOrBalanced must resolve automatic detail")
    }

internal data class PreviewSurfaceSize(
    val width: Int,
    val height: Int,
)

/**
 * Keeps every selected path and layer while reducing only the number of fragments a weak GPU
 * must shade. Automatic starts at Performance and restores the logical view resolution as each
 * completed-frame tier proves that the device has enough raster headroom.
 */
internal fun previewSurfaceSize(
    logicalWidth: Int,
    logicalHeight: Int,
    detail: PreviewDetail,
): PreviewSurfaceSize {
    val width = logicalWidth.coerceAtLeast(1)
    val height = logicalHeight.coerceAtLeast(1)
    val scale = when (detail) {
        PreviewDetail.AUTOMATIC, PreviewDetail.PERFORMANCE -> PERFORMANCE_PREVIEW_SURFACE_SCALE
        PreviewDetail.BALANCED -> BALANCED_PREVIEW_SURFACE_SCALE
        PreviewDetail.DETAIL -> 1f
    }
    return PreviewSurfaceSize(
        width = (width * scale).roundToInt().coerceIn(1, width),
        height = (height * scale).roundToInt().coerceIn(1, height),
    )
}

internal const val PERFORMANCE_PREVIEW_SURFACE_SCALE = 0.67f
internal const val BALANCED_PREVIEW_SURFACE_SCALE = 0.85f

/**
 * Prepare keeps the complete low-detail model during a gesture and reduces only transient raster
 * work. The logical viewport is unchanged, so camera framing and hit testing remain pixel-exact;
 * the full-resolution buffer is restored as soon as interaction ends.
 */
internal fun prepareSurfaceSize(
    logicalWidth: Int,
    logicalHeight: Int,
    interactionActive: Boolean,
): PreviewSurfaceSize {
    val width = logicalWidth.coerceAtLeast(1)
    val height = logicalHeight.coerceAtLeast(1)
    val scale = if (interactionActive) PREPARE_INTERACTION_SURFACE_SCALE else 1f
    return PreviewSurfaceSize(
        width = (width * scale).roundToInt().coerceIn(1, width),
        height = (height * scale).roundToInt().coerceIn(1, height),
    )
}

internal const val PREPARE_INTERACTION_SURFACE_SCALE = 0.72f

internal fun shouldDrawToolpathLines(
    detail: PreviewDetail,
    interactionActive: Boolean,
    initialPreview: Boolean,
    denseOverview: Boolean = false,
): Boolean = interactionActive || initialPreview || denseOverview ||
    detail.concreteOrBalanced() == PreviewDetail.PERFORMANCE

/**
 * At a bed-fit camera distance, an extrusion ribbon is approximately one screen pixel wide.
 * Rasterizing tens of thousands of overlapping quads adds fill work without exposing more shape
 * than a depth-tested line. Keep coherent layer and role coverage in that overview, then restore
 * shaded ribbons once zoom makes their physical width visible.
 */
internal fun shouldUseDenseOverviewLines(sourceSegmentCount: Int, zoom: Float): Boolean =
    sourceSegmentCount >= DENSE_PREVIEW_OVERVIEW_SEGMENTS &&
        zoom.isFinite() && zoom <= DENSE_PREVIEW_RIBBON_ZOOM

/**
 * At bed-fit zoom, paths hidden behind the visible shell cannot contribute independent screen
 * detail. Keep coherent exterior-first paths up to a screen-space budget and restore the full
 * requested tier as soon as the user zooms close enough to inspect extrusion widths.
 */
internal fun depthPreviewOverviewSegmentBudget(
    detail: PreviewDetail,
    viewportWidth: Int,
    viewportHeight: Int,
    zoom: Float,
): Int {
    val requested = depthPreviewSegmentBudget(detail)
    if (!zoom.isFinite() || zoom > DENSE_PREVIEW_RIBBON_ZOOM) return requested
    val pixelCount = viewportWidth.coerceAtLeast(1).toLong() * viewportHeight.coerceAtLeast(1)
    val screenSpaceBudget = (pixelCount / DENSE_PREVIEW_PIXELS_PER_SEGMENT)
        .coerceIn(DENSE_PREVIEW_MIN_SEGMENTS.toLong(), DENSE_PREVIEW_MAX_SEGMENTS.toLong())
        .toInt()
    return minOf(requested, screenSpaceBudget)
}

internal const val DENSE_PREVIEW_OVERVIEW_SEGMENTS = 40_000
internal const val DENSE_PREVIEW_RIBBON_ZOOM = 1.5f
internal const val DENSE_PREVIEW_MIN_SEGMENTS = 10_000
internal const val DENSE_PREVIEW_MAX_SEGMENTS = 32_000
private const val DENSE_PREVIEW_PIXELS_PER_SEGMENT = 48L

// Promotion is allowed only while completed GPU work leaves enough room for a responsive
// 60 Hz gesture frame. Emulator profiling showed that a 48 ms threshold promoted a one-vCPU
// SwiftShader workload all the way to Detail even while interaction frames exceeded 30 ms.
internal const val ADAPTIVE_PREVIEW_FAST_FRAME_MS = 24.0
internal const val ADAPTIVE_PREVIEW_FAST_SAMPLE_COUNT = 5

/**
 * Promotes Automatic Preview one bounded tier at a time after measuring completed GPU work.
 * A failed candidate falls back to the last proven tier and stays there for this workload,
 * preventing detail oscillation while the user is inspecting or manipulating the scene.
 */
internal class AdaptivePreviewDetailController(
    private val fastFrameMs: Double = ADAPTIVE_PREVIEW_FAST_FRAME_MS,
    private val requiredFastSamples: Int = ADAPTIVE_PREVIEW_FAST_SAMPLE_COUNT,
) {
    private var workload: Any? = null
    private var currentDetail = PreviewDetail.PERFORMANCE
    private var lastProvenDetail = PreviewDetail.PERFORMANCE
    private var fastSamples = 0
    private var settled = false

    init {
        require(fastFrameMs > 0.0 && fastFrameMs.isFinite())
        require(requiredFastSamples > 0)
    }

    fun detailFor(requested: PreviewDetail, workload: Any): PreviewDetail {
        if (requested != PreviewDetail.AUTOMATIC) {
            reset()
            return requested.concreteOrBalanced()
        }
        ensureWorkload(workload)
        return currentDetail
    }

    fun shouldMeasure(requested: PreviewDetail, workload: Any): Boolean {
        if (requested != PreviewDetail.AUTOMATIC) return false
        ensureWorkload(workload)
        return !settled
    }

    /** Returns true when another settled frame is required for calibration or promotion. */
    fun recordCompletedFrame(
        requested: PreviewDetail,
        workload: Any,
        measuredDetail: PreviewDetail,
        completionMs: Double,
    ): Boolean {
        if (requested != PreviewDetail.AUTOMATIC) {
            reset()
            return false
        }
        ensureWorkload(workload)
        if (settled || measuredDetail != currentDetail) return false

        if (!completionMs.isFinite() || completionMs < 0.0 || completionMs > fastFrameMs) {
            currentDetail = lastProvenDetail
            fastSamples = 0
            settled = true
            return false
        }

        fastSamples += 1
        if (fastSamples < requiredFastSamples) return true
        fastSamples = 0
        when (currentDetail) {
            PreviewDetail.PERFORMANCE -> {
                lastProvenDetail = PreviewDetail.PERFORMANCE
                currentDetail = PreviewDetail.BALANCED
            }

            PreviewDetail.BALANCED -> {
                lastProvenDetail = PreviewDetail.BALANCED
                currentDetail = PreviewDetail.DETAIL
            }

            PreviewDetail.DETAIL -> settled = true
            PreviewDetail.AUTOMATIC -> error("Automatic cannot be an effective detail tier")
        }
        return !settled
    }

    internal fun isSettledForTest(): Boolean = settled

    private fun ensureWorkload(next: Any) {
        if (workload == next) return
        workload = next
        currentDetail = PreviewDetail.PERFORMANCE
        lastProvenDetail = PreviewDetail.PERFORMANCE
        fastSamples = 0
        settled = false
    }

    private fun reset() {
        workload = null
        currentDetail = PreviewDetail.PERFORMANCE
        lastProvenDetail = PreviewDetail.PERFORMANCE
        fastSamples = 0
        settled = false
    }
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

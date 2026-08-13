package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Trace
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

@Composable
internal fun DepthTestedToolpathScene(
    preview: GcodeLayerPreview,
    bedSizeX: Float,
    bedSizeY: Float,
    bedOriginX: Float,
    bedOriginY: Float,
    bedPolygon: List<Float>,
    opacity: Float,
    depthContrast: Float,
    visibleRoles: Set<Int>,
    detail: PreviewDetail,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    AndroidView(
        factory = { context ->
            ToolpathSurfaceView(context) { currentOnUnavailable.value() }
        },
        update = { view ->
            view.submit(
                preview,
                bedSizeX,
                bedSizeY,
                bedOriginX,
                bedOriginY,
                bedPolygon,
                opacity,
                depthContrast,
                visibleRoles,
                detail,
            )
        },
        modifier = modifier,
    )
}

internal fun supportsDepthTestedPreview(context: Context): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return (manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x00030000
}

internal class ToolpathSurfaceView(
    context: Context,
    private val onUnavailable: () -> Unit,
) : GLSurfaceView(context) {
    private val applicationContext = context.applicationContext
    private var rendererReady = false
    private var unavailableReported = false
    private var sceneSubmitted = false
    private var latestSubmittedScene: ToolpathScene? = null
    private var startupWatchdogScheduled = false
    private val geometryGeneration = AtomicInteger()
    private val pendingGeometry = ConcurrentHashMap.newKeySet<PendingToolpathGeometry>()
    private val startupWatchdog = Runnable {
        startupWatchdogScheduled = false
        if (!rendererReady) reportUnavailableOnce()
    }
    private val toolpathRenderer = ToolpathRenderer(
        requestPrewarmFrame = { post { requestRender() } },
        requestGeometryBuild = ::requestGeometryBuild,
        reportRendererStarting = { post { markRendererStarting() } },
        reportFrameReady = { post(::markRendererReady) },
        reportUnavailable = { post(::reportUnavailableOnce) },
    )
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = releaseGpuMemory()

        override fun onTrimMemory(level: Int) {
            if (shouldReleaseToolpathGpuMemory(level)) releaseGpuMemory()
        }
    }
    private var memoryCallbacksRegistered = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastCenterX = 0f
    private var lastCenterY = 0f
    private val restoreDetail = Runnable {
        toolpathRenderer.setInteractionActive(false)
        requestRender()
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 8)
        setRenderer(toolpathRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true
    }

    fun submit(
        preview: GcodeLayerPreview,
        bedSizeX: Float,
        bedSizeY: Float,
        bedOriginX: Float,
        bedOriginY: Float,
        bedPolygon: List<Float>,
        opacity: Float,
        depthContrast: Float,
        visibleRoles: Set<Int>,
        detail: PreviewDetail,
    ) {
        val scene = ToolpathScene(
            preview = preview,
            bedSizeX = bedSizeX,
            bedSizeY = bedSizeY,
            bedOriginX = bedOriginX,
            bedOriginY = bedOriginY,
            bedPolygon = bedPolygon,
            opacity = opacity,
            depthContrast = depthContrast,
            detail = detail,
            visibleRoles = visibleRoles,
        )
        if (scene != latestSubmittedScene) {
            geometryGeneration.incrementAndGet()
            pendingGeometry.clear()
            latestSubmittedScene = scene
        }
        sceneSubmitted = true
        toolpathRenderer.submit(scene)
        scheduleStartupWatchdog()
        requestRender()
    }

    internal fun rendererReadyForTest(): Boolean = rendererReady

    internal fun geometryUploadCountForTest(): Int = toolpathRenderer.geometryUploadCountForTest()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(restoreDetail)
                toolpathRenderer.setInteractionActive(true)
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> captureTwoFingerState(event)
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) {
                val x0 = event.getX(0)
                val y0 = event.getY(0)
                val x1 = event.getX(1)
                val y1 = event.getY(1)
                val span = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
                val centerX = (x0 + x1) / 2f
                val centerY = (y0 + y1) / 2f
                if (lastSpan > 0f) {
                    toolpathRenderer.zoomBy(span / lastSpan)
                    toolpathRenderer.panBy(
                        centerX - lastCenterX,
                        centerY - lastCenterY,
                        width,
                        height,
                    )
                }
                lastSpan = span
                lastCenterX = centerX
                lastCenterY = centerY
            } else {
                val deltaX = event.x - lastX
                val deltaY = event.y - lastY
                toolpathRenderer.orbitBy(deltaX, deltaY)
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                lastSpan = 0f
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastSpan = 0f
                removeCallbacks(restoreDetail)
                postDelayed(restoreDetail, DETAIL_RESTORE_DELAY_MS)
            }
        }
        requestRender()
        return true
    }

    override fun onDetachedFromWindow() {
        geometryGeneration.incrementAndGet()
        pendingGeometry.clear()
        latestSubmittedScene = null
        removeCallbacks(restoreDetail)
        cancelStartupWatchdog()
        if (memoryCallbacksRegistered) {
            applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = false
        }
        releaseGpuMemory()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!memoryCallbacksRegistered) {
            applicationContext.registerComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = true
        }
        scheduleStartupWatchdog()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            scheduleStartupWatchdog()
            requestRender()
        } else {
            cancelStartupWatchdog()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        rendererReady = false
        toolpathRenderer.expectFirstFrame()
        super.surfaceCreated(holder)
        scheduleStartupWatchdog()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        rendererReady = false
        cancelStartupWatchdog()
        super.surfaceDestroyed(holder)
    }

    private fun releaseGpuMemory() {
        queueEvent { toolpathRenderer.releaseGpuGeometryForMemoryPressure() }
    }

    private fun requestGeometryBuild(scene: ToolpathScene) {
        val generation = geometryGeneration.get()
        val request = PendingToolpathGeometry(scene, generation)
        if (!pendingGeometry.add(request)) return
        ToolpathGeometryBuildExecutor.execute {
            val result = runCatching { ToolpathMeshBuilder.build(scene) }
            pendingGeometry.remove(request)
            if (generation != geometryGeneration.get() || !isAttachedToWindow) return@execute
            queueEvent {
                result.fold(
                    onSuccess = { payload -> toolpathRenderer.submitPreparedGeometry(scene, payload) },
                    onFailure = toolpathRenderer::reportGeometryBuildFailure,
                )
            }
            post { requestRender() }
        }
    }

    private fun captureTwoFingerState(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        lastSpan = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
        lastCenterX = (x0 + x1) / 2f
        lastCenterY = (y0 + y1) / 2f
    }

    private fun scheduleStartupWatchdog() {
        if (
            rendererReady || unavailableReported || !sceneSubmitted ||
            !isAttachedToWindow || windowVisibility != View.VISIBLE || startupWatchdogScheduled
        ) {
            return
        }
        startupWatchdogScheduled = true
        postDelayed(startupWatchdog, RENDERER_STARTUP_TIMEOUT_MS)
    }

    private fun cancelStartupWatchdog() {
        removeCallbacks(startupWatchdog)
        startupWatchdogScheduled = false
    }

    private fun markRendererReady() {
        if (unavailableReported) return
        rendererReady = true
        cancelStartupWatchdog()
    }

    private fun markRendererStarting() {
        if (unavailableReported) return
        rendererReady = false
        scheduleStartupWatchdog()
    }

    private fun reportUnavailableOnce() {
        if (unavailableReported) return
        unavailableReported = true
        cancelStartupWatchdog()
        onUnavailable()
    }

    private companion object {
        const val DETAIL_RESTORE_DELAY_MS = 220L
        const val RENDERER_STARTUP_TIMEOUT_MS = 5_000L
    }
}

private data class PendingToolpathGeometry(
    val scene: ToolpathScene,
    val generation: Int,
)

private object ToolpathGeometryBuildExecutor {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DuckyPreviewGeometry").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }

    fun execute(block: () -> Unit) {
        executor.execute(block)
    }
}

internal fun shouldReleaseToolpathGpuMemory(level: Int): Boolean =
    level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

internal data class ToolpathScene(
    val preview: GcodeLayerPreview,
    val bedSizeX: Float,
    val bedSizeY: Float,
    val opacity: Float,
    val depthContrast: Float,
    val detail: PreviewDetail,
    val visibleRoles: Set<Int> = (0 until GcodeLayerPreview.ROLE_COUNT).toSet(),
    val bedPolygon: List<Float> = rectangularBedPolygon(bedSizeX, bedSizeY),
    val bedOriginX: Float = 0f,
    val bedOriginY: Float = 0f,
    val segmentBudgetOverride: Int? = null,
    val renderAsLines: Boolean = false,
) {
    /**
     * Preview payloads are immutable renderer inputs. Cache identity must therefore follow the
     * payload object, not hash every coordinate in its large FloatArray on every camera frame.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolpathScene) return false
        return preview === other.preview &&
            bedSizeX.toBits() == other.bedSizeX.toBits() &&
            bedSizeY.toBits() == other.bedSizeY.toBits() &&
            opacity.toBits() == other.opacity.toBits() &&
            depthContrast.toBits() == other.depthContrast.toBits() &&
            detail == other.detail &&
            visibleRoles == other.visibleRoles &&
            bedPolygon == other.bedPolygon &&
            bedOriginX.toBits() == other.bedOriginX.toBits() &&
            bedOriginY.toBits() == other.bedOriginY.toBits() &&
            segmentBudgetOverride == other.segmentBudgetOverride &&
            renderAsLines == other.renderAsLines
    }

    override fun hashCode(): Int {
        var result = System.identityHashCode(preview)
        result = 31 * result + bedSizeX.hashCode()
        result = 31 * result + bedSizeY.hashCode()
        result = 31 * result + opacity.hashCode()
        result = 31 * result + depthContrast.hashCode()
        result = 31 * result + detail.hashCode()
        result = 31 * result + visibleRoles.hashCode()
        result = 31 * result + bedPolygon.hashCode()
        result = 31 * result + bedOriginX.hashCode()
        result = 31 * result + bedOriginY.hashCode()
        result = 31 * result + (segmentBudgetOverride ?: 0)
        result = 31 * result + renderAsLines.hashCode()
        return result
    }
}

internal data class ToolpathRendererTelemetry(
    val geometryBuildMs: Double,
    val renderPlanMs: Double,
    val geometryPackMs: Double,
    val geometryUploadMs: Double,
    val lastDrawSubmitMs: Double,
)

internal class ToolpathRenderer(
    private val requestPrewarmFrame: () -> Unit = {},
    private val requestGeometryBuild: ((ToolpathScene) -> Unit)? = null,
    private val reportRendererStarting: () -> Unit = {},
    private val reportFrameReady: () -> Unit = {},
    private val reportUnavailable: () -> Unit = {},
    private val programFactory: ((String, String) -> Int)? = null,
) : GLSurfaceView.Renderer {
    @Volatile
    private var latestScene: ToolpathScene? = null
    private val uploadState = ToolpathGeometryUploadState(capacity = GPU_GEOMETRY_CACHE_SIZE)
    private val gpuGeometry = HashMap<ToolpathScene, ToolpathGpuGeometry>()
    private val preparedGeometry = LinkedHashMap<ToolpathScene, ToolpathUploadPayload>()
    private val adaptivePreviewController = AdaptivePreviewDetailController()
    private var pendingPrewarmScene: ToolpathScene? = null
    private var refinementRequestedForScene: ToolpathScene? = null
    private var bedProgram = 0
    private var bedPositionLocation = 0
    private var bedColorLocation = 0
    private var bedAcrossLocation = 0
    private var bedMatrixLocation = 0
    private var toolpathProgram = 0
    private var toolpathStartLocation = 0
    private var toolpathEndLocation = 0
    private var toolpathHalfWidthLocation = 0
    private var toolpathColorLocation = 0
    private var toolpathMatrixLocation = 0
    private var lineProgram = 0
    private var linePositionLocation = 0
    private var lineColorLocation = 0
    private var lineMatrixLocation = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var yawDegrees = -45f
    private var elevationDegrees = 52f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var geometryUploadCount = 0
    private var geometryBuildNanos = 0L
    private var renderPlanNanos = 0L
    private var geometryPackNanos = 0L
    private var geometryUploadNanos = 0L
    private var lastDrawSubmitNanos = 0L
    private var rendererUnavailable = false
    private var lastEffectiveDetail: PreviewDetail? = null
    @Volatile
    private var frameReadyReported = false
    @Volatile
    private var interactionActive = false

    internal fun geometryUploadCountForTest(): Int = geometryUploadCount

    internal fun telemetryForTest(): ToolpathRendererTelemetry = ToolpathRendererTelemetry(
        geometryBuildMs = geometryBuildNanos / 1_000_000.0,
        renderPlanMs = renderPlanNanos / 1_000_000.0,
        geometryPackMs = geometryPackNanos / 1_000_000.0,
        geometryUploadMs = geometryUploadNanos / 1_000_000.0,
        lastDrawSubmitMs = lastDrawSubmitNanos / 1_000_000.0,
    )

    internal fun cachedGeometryCountForTest(): Int = gpuGeometry.size

    internal fun effectiveDetailForTest(): PreviewDetail? = lastEffectiveDetail

    internal fun automaticCalibrationSettledForTest(): Boolean =
        adaptivePreviewController.isSettledForTest()

    internal fun expectFirstFrame() {
        frameReadyReported = false
    }

    internal fun releaseGpuGeometryForMemoryPressure() {
        gpuGeometry.values.forEach(::deleteGeometry)
        gpuGeometry.clear()
        uploadState.invalidate()
        preparedGeometry.clear()
        pendingPrewarmScene = null
        refinementRequestedForScene = null
    }

    internal fun submitPreparedGeometry(scene: ToolpathScene, payload: ToolpathUploadPayload) {
        preparedGeometry[scene] = payload
        while (preparedGeometry.size > PREPARED_GEOMETRY_CACHE_SIZE) {
            val iterator = preparedGeometry.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    internal fun reportGeometryBuildFailure(failure: Throwable) {
        failRenderer("geometry_build", failure)
    }

    fun submit(scene: ToolpathScene) {
        latestScene = scene
    }

    fun setInteractionActive(active: Boolean) {
        interactionActive = active
    }

    fun orbitBy(deltaX: Float, deltaY: Float) {
        yawDegrees += deltaX * 0.28f
        elevationDegrees = (elevationDegrees - deltaY * 0.22f).coerceIn(18f, 86f)
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(0.45f, 5f)
    }

    fun panBy(deltaX: Float, deltaY: Float, width: Int, height: Int) {
        val scene = latestScene ?: return
        val scale = max(scene.bedSizeX, scene.bedSizeY) / max(width, height).coerceAtLeast(1)
        panX -= deltaX * scale / zoom
        panY += deltaY * scale / zoom
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        frameReadyReported = false
        reportRendererStarting()
        bedProgram = 0
        toolpathProgram = 0
        lineProgram = 0
        geometryUploadCount = 0
        geometryBuildNanos = 0L
        renderPlanNanos = 0L
        geometryPackNanos = 0L
        geometryUploadNanos = 0L
        lastDrawSubmitNanos = 0L
        pendingPrewarmScene = null
        refinementRequestedForScene = null
        gpuGeometry.clear()
        preparedGeometry.clear()
        uploadState.invalidate()
        if (rendererUnavailable) return
        GLES30.glClearColor(0.098f, 0.102f, 0.094f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        bedProgram = createProgramSafely(BED_VERTEX_SHADER, FRAGMENT_SHADER)
        toolpathProgram = createProgramSafely(TOOLPATH_VERTEX_SHADER, FRAGMENT_SHADER)
        lineProgram = createProgramSafely(LINE_VERTEX_SHADER, FRAGMENT_SHADER)
        if (bedProgram == 0 || toolpathProgram == 0 || lineProgram == 0) {
            failRenderer("program_creation")
            return
        }
        bedPositionLocation = GLES30.glGetAttribLocation(bedProgram, "aPosition")
        bedColorLocation = GLES30.glGetAttribLocation(bedProgram, "aColor")
        bedAcrossLocation = GLES30.glGetAttribLocation(bedProgram, "aAcross")
        bedMatrixLocation = GLES30.glGetUniformLocation(bedProgram, "uMvp")
        toolpathStartLocation = GLES30.glGetAttribLocation(toolpathProgram, "aStart")
        toolpathEndLocation = GLES30.glGetAttribLocation(toolpathProgram, "aEnd")
        toolpathHalfWidthLocation = GLES30.glGetAttribLocation(toolpathProgram, "aHalfWidth")
        toolpathColorLocation = GLES30.glGetAttribLocation(toolpathProgram, "aColor")
        toolpathMatrixLocation = GLES30.glGetUniformLocation(toolpathProgram, "uMvp")
        linePositionLocation = GLES30.glGetAttribLocation(lineProgram, "aPosition")
        lineColorLocation = GLES30.glGetAttribLocation(lineProgram, "aColor")
        lineMatrixLocation = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        val requiredLocations = intArrayOf(
            bedPositionLocation,
            bedColorLocation,
            bedAcrossLocation,
            bedMatrixLocation,
            toolpathStartLocation,
            toolpathEndLocation,
            toolpathHalfWidthLocation,
            toolpathColorLocation,
            toolpathMatrixLocation,
            linePositionLocation,
            lineColorLocation,
            lineMatrixLocation,
        )
        if (requiredLocations.any { it < 0 } || !glOperationSucceeded("program_locations")) {
            failRenderer("program_locations")
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        if (rendererUnavailable) return
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        glOperationSucceeded("viewport")
    }

    override fun onDrawFrame(unused: GL10?) {
        if (rendererUnavailable) return
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (bedProgram == 0 || toolpathProgram == 0 || lineProgram == 0) {
            failRenderer("missing_program")
            return
        }
        val requestedScene = latestScene ?: return
        val adaptiveWorkload = AdaptiveToolpathWorkload(
            scene = requestedScene.copy(
                detail = PreviewDetail.AUTOMATIC,
                segmentBudgetOverride = null,
            ),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            denseOverview = zoom <= DENSE_PREVIEW_RIBBON_ZOOM,
        )
        val effectiveDetail = adaptivePreviewController.detailFor(
            requestedScene.detail,
            adaptiveWorkload,
        )
        val resolvedScene = if (requestedScene.detail == PreviewDetail.AUTOMATIC) {
            requestedScene.copy(detail = effectiveDetail)
        } else {
            requestedScene
        }
        val sourceSegmentCount =
            requestedScene.preview.segments.size / GcodeLayerPreview.SEGMENT_STRIDE
        val overview = shouldUseDenseOverviewLines(sourceSegmentCount, zoom)
        val overviewBudget = depthPreviewOverviewSegmentBudget(
            resolvedScene.detail,
            viewportWidth,
            viewportHeight,
            zoom,
        )
        val sourceScene = if (overview) {
            resolvedScene.copy(
                segmentBudgetOverride = minOf(
                    resolvedScene.segmentBudgetOverride
                        ?: depthPreviewSegmentBudget(resolvedScene.detail),
                    overviewBudget,
                ),
            )
        } else {
            resolvedScene
        }
        lastEffectiveDetail = sourceScene.detail
        val prewarmAtFrameStart = pendingPrewarmScene
        pendingPrewarmScene = null
        val prewarmDetail = previewDetailForInteraction(sourceScene.detail, interactionActive = true)
        val interactionScene = if (prewarmDetail == sourceScene.detail) {
            sourceScene.copy(
                segmentBudgetOverride = depthPreviewInteractionSegmentBudget(sourceScene.detail),
            )
        } else {
            sourceScene.copy(
                detail = prewarmDetail,
                segmentBudgetOverride = depthPreviewInteractionSegmentBudget(sourceScene.detail),
            )
        }
        val sourceDrawsLines = shouldDrawToolpathLines(
            sourceScene.detail,
            interactionActive = false,
            initialPreview = false,
            denseOverview = overview,
        )
        val sourceGpuScene = sourceScene.copy(renderAsLines = sourceDrawsLines)
        val interactionGpuScene = interactionScene.copy(renderAsLines = true)
        releaseStaleGeometry(setOf(sourceGpuScene, interactionGpuScene))
        // A dense preview should become recognizable immediately. Draw the same coherent,
        // whole-path LOD used for gestures first, then replace it with requested detail on
        // the following dirty frame. This avoids blocking the first visible frame on tens of
        // thousands of software-rasterized extrusion ribbons without turning paths into dots.
        val initialPreview = !interactionActive && refinementRequestedForScene != sourceScene
        val scene = if (interactionActive || initialPreview) interactionGpuScene else sourceGpuScene
        val geometry = geometryFor(scene) ?: return
        val matrix = cameraMatrix(scene)
        val measureAutomaticFrame = !interactionActive && !initialPreview &&
            adaptivePreviewController.shouldMeasure(requestedScene.detail, adaptiveWorkload)
        val measurementStartedNanos = if (measureAutomaticFrame) System.nanoTime() else 0L
        val drawStartedNanos = System.nanoTime()
        drawBed(geometry, matrix)
        drawToolpaths(geometry, matrix, scene.renderAsLines)
        lastDrawSubmitNanos = System.nanoTime() - drawStartedNanos
        if (!glOperationSucceeded("frame_draw")) return
        if (measureAutomaticFrame) {
            // Calibration is limited to two settled frames per tier. glFinish gives a
            // portable GLES3 completion measurement that includes fragment work instead
            // of the tiny CPU submission time; explicit quality modes never pay this cost.
            GLES30.glFinish()
            if (!glOperationSucceeded("adaptive_gpu_completion")) return
            val completionMs = (System.nanoTime() - measurementStartedNanos) / 1_000_000.0
            if (
                adaptivePreviewController.recordCompletedFrame(
                    requested = requestedScene.detail,
                    workload = adaptiveWorkload,
                    measuredDetail = sourceScene.detail,
                    completionMs = completionMs,
                )
            ) {
                requestPrewarmFrame()
            }
        }
        if (!frameReadyReported) {
            frameReadyReported = true
            reportFrameReady()
        }

        if (initialPreview) {
            refinementRequestedForScene = sourceScene
            requestPrewarmFrame()
            return
        }

        if (!interactionActive && interactionGpuScene != sourceGpuScene) {
            if (uploadState.needsUpload(interactionGpuScene)) {
                if (prewarmAtFrameStart == interactionGpuScene) {
                    uploadGeometry(interactionGpuScene)
                } else {
                    pendingPrewarmScene = interactionGpuScene
                    requestPrewarmFrame()
                }
            }
        }
    }

    private fun drawBed(geometry: ToolpathGpuGeometry, matrix: FloatArray) {
        GLES30.glUseProgram(bedProgram)
        GLES30.glUniformMatrix4fv(bedMatrixLocation, 1, false, matrix, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.bedBufferId)
        GLES30.glVertexAttribPointer(
            bedPositionLocation,
            3,
            GLES30.GL_FLOAT,
            false,
            BED_STRIDE_BYTES,
            BED_POSITION_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(bedPositionLocation)
        GLES30.glVertexAttribDivisor(bedPositionLocation, 0)
        GLES30.glVertexAttribPointer(
            bedColorLocation,
            4,
            GLES30.GL_FLOAT,
            false,
            BED_STRIDE_BYTES,
            BED_COLOR_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(bedColorLocation)
        GLES30.glVertexAttribDivisor(bedColorLocation, 0)
        GLES30.glVertexAttribPointer(
            bedAcrossLocation,
            1,
            GLES30.GL_FLOAT,
            false,
            BED_STRIDE_BYTES,
            BED_ACROSS_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(bedAcrossLocation)
        GLES30.glVertexAttribDivisor(bedAcrossLocation, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, geometry.bedVertexCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun drawToolpaths(
        geometry: ToolpathGpuGeometry,
        matrix: FloatArray,
        drawInteractionLines: Boolean,
    ) {
        if (
            (drawInteractionLines && geometry.lineVertexCount == 0) ||
            (!drawInteractionLines && geometry.instanceCount == 0)
        ) return
        traced("DuckyPreview.drawToolpaths") {
            if (drawInteractionLines) {
                drawToolpathLines(geometry, matrix)
                return@traced
            }
            GLES30.glUseProgram(toolpathProgram)
            GLES30.glUniformMatrix4fv(toolpathMatrixLocation, 1, false, matrix, 0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.instanceBufferId)
            setInstanceAttribute(
                toolpathStartLocation,
                3,
                GLES30.GL_FLOAT,
                false,
                ToolpathMeshBuilder.INSTANCE_START_OFFSET_BYTES,
            )
            setInstanceAttribute(
                toolpathEndLocation,
                3,
                GLES30.GL_FLOAT,
                false,
                ToolpathMeshBuilder.INSTANCE_END_OFFSET_BYTES,
            )
            setInstanceAttribute(
                toolpathHalfWidthLocation,
                1,
                GLES30.GL_FLOAT,
                false,
                ToolpathMeshBuilder.INSTANCE_HALF_WIDTH_OFFSET_BYTES,
            )
            setInstanceAttribute(
                toolpathColorLocation,
                4,
                GLES30.GL_UNSIGNED_BYTE,
                true,
                ToolpathMeshBuilder.INSTANCE_COLOR_OFFSET_BYTES,
            )
            GLES30.glDrawArraysInstanced(
                GLES30.GL_TRIANGLE_STRIP,
                0,
                TOOLPATH_VERTICES_PER_INSTANCE,
                geometry.instanceCount,
            )
            val instanceLocations = intArrayOf(
                toolpathStartLocation,
                toolpathEndLocation,
                toolpathHalfWidthLocation,
                toolpathColorLocation,
            )
            for (location in instanceLocations) {
                GLES30.glVertexAttribDivisor(location, 0)
            }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
    }

    /**
     * Software GLES drivers pay a large per-instance cost even for two-vertex lines. The
     * interaction/overview path therefore uses one compact explicit vertex stream and one
     * non-instanced draw. Ribbons keep the smaller instanced representation for settled detail.
     */
    private fun drawToolpathLines(geometry: ToolpathGpuGeometry, matrix: FloatArray) {
        if (geometry.lineVertexCount == 0) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(lineMatrixLocation, 1, false, matrix, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.lineBufferId)
        GLES30.glVertexAttribPointer(
            linePositionLocation,
            3,
            GLES30.GL_FLOAT,
            false,
            ToolpathMeshBuilder.LINE_VERTEX_STRIDE_BYTES,
            ToolpathMeshBuilder.LINE_POSITION_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(linePositionLocation)
        GLES30.glVertexAttribDivisor(linePositionLocation, 0)
        GLES30.glVertexAttribPointer(
            lineColorLocation,
            4,
            GLES30.GL_UNSIGNED_BYTE,
            true,
            ToolpathMeshBuilder.LINE_VERTEX_STRIDE_BYTES,
            ToolpathMeshBuilder.LINE_COLOR_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(lineColorLocation)
        GLES30.glVertexAttribDivisor(lineColorLocation, 0)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, geometry.lineVertexCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun setInstanceAttribute(
        location: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        offsetBytes: Int,
    ) {
        GLES30.glVertexAttribPointer(
            location,
            size,
            type,
            normalized,
            ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES,
            offsetBytes,
        )
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribDivisor(location, 1)
    }

    private fun releaseStaleGeometry(retainedScenes: Set<ToolpathScene>) {
        gpuGeometry.keys.filterNot { it in retainedScenes }.forEach { staleScene ->
            gpuGeometry.remove(staleScene)?.let(::deleteGeometry)
            uploadState.remove(staleScene)
        }
        preparedGeometry.keys.removeAll { scene -> scene !in retainedScenes }
    }

    private fun geometryFor(scene: ToolpathScene): ToolpathGpuGeometry? {
        if (!uploadState.needsUpload(scene)) {
            uploadState.markUsed(scene)
            return gpuGeometry[scene]
        }
        preparedGeometry.remove(scene)?.let { payload ->
            return uploadGeometry(scene, payload)
        }
        requestGeometryBuild?.let { request ->
            request(scene)
            return null
        }
        return uploadGeometry(scene)
    }

    private fun uploadGeometry(scene: ToolpathScene): ToolpathGpuGeometry? {
        val payload = try {
            traced("DuckyPreview.buildGeometry") { ToolpathMeshBuilder.build(scene) }
        } catch (failure: RuntimeException) {
            failRenderer("geometry_build", failure)
            return null
        }
        return uploadGeometry(scene, payload)
    }

    private fun uploadGeometry(
        scene: ToolpathScene,
        payload: ToolpathUploadPayload,
    ): ToolpathGpuGeometry? {
        geometryBuildNanos += payload.geometryBuildNanos
        renderPlanNanos += payload.renderPlanNanos
        geometryPackNanos += payload.geometryPackNanos
        val buffers = IntArray(3)
        GLES30.glGenBuffers(buffers.size, buffers, 0)
        if (buffers.any { it == 0 } || GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteBuffers(buffers.size, buffers, 0)
            failRenderer("buffer_allocation")
            return null
        }
        val geometry = ToolpathGpuGeometry(
            bedBufferId = buffers[0],
            bedVertexCount = payload.bedVertices.remaining() / BED_FLOATS_PER_VERTEX,
            instanceBufferId = buffers[1],
            instanceCount = payload.instanceCount,
            lineBufferId = buffers[2],
            lineVertexCount = payload.lineVertexCount,
        )
        val uploadStartedNanos = System.nanoTime()
        traced("DuckyPreview.uploadGeometry") {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.bedBufferId)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                payload.bedVertices.remaining() * Float.SIZE_BYTES,
                payload.bedVertices,
                GLES30.GL_STATIC_DRAW,
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.instanceBufferId)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                payload.toolpathInstances.remaining(),
                payload.toolpathInstances,
                GLES30.GL_STATIC_DRAW,
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.lineBufferId)
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER,
                payload.lineVertices.remaining(),
                payload.lineVertices,
                GLES30.GL_STATIC_DRAW,
            )
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        }
        geometryUploadNanos += System.nanoTime() - uploadStartedNanos
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            deleteGeometry(geometry)
            failRenderer("buffer_upload")
            return null
        }
        uploadState.markUploaded(scene)?.let { evictedScene ->
            gpuGeometry.remove(evictedScene)?.let(::deleteGeometry)
        }
        gpuGeometry[scene] = geometry
        geometryUploadCount += 1
        return geometry
    }

    private fun deleteGeometry(geometry: ToolpathGpuGeometry) {
        GLES30.glDeleteBuffers(
            3,
            intArrayOf(geometry.bedBufferId, geometry.instanceBufferId, geometry.lineBufferId),
            0,
        )
    }

    private fun cameraMatrix(scene: ToolpathScene): FloatArray {
        val projection = FloatArray(16)
        val view = FloatArray(16)
        val result = FloatArray(16)
        Matrix.perspectiveM(
            projection,
            0,
            40f,
            viewportWidth.toFloat() / viewportHeight,
            1f,
            max(scene.bedSizeX, scene.bedSizeY) * 8f,
        )
        val yaw = yawDegrees / 180f * PI.toFloat()
        val elevation = elevationDegrees / 180f * PI.toFloat()
        val distance = max(scene.bedSizeX, scene.bedSizeY) * 1.55f / zoom
        val centerX = scene.bedSizeX / 2f + panX
        val centerY = scene.bedSizeY / 2f + panY
        val centerZ = (scene.preview.maxZMm * 0.34f).coerceAtLeast(1f)
        val horizontalDistance = distance * cos(elevation)
        Matrix.setLookAtM(
            view,
            0,
            centerX + horizontalDistance * cos(yaw),
            centerY + horizontalDistance * sin(yaw),
            centerZ + distance * sin(elevation),
            centerX,
            centerY,
            centerZ,
            0f,
            0f,
            1f,
        )
        Matrix.multiplyMM(result, 0, projection, 0, view, 0)
        return result
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        var vertex = 0
        var fragment = 0
        var created = 0
        try {
            vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
            fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
            created = GLES30.glCreateProgram()
            check(created != 0) { "Unable to create an OpenGL program" }
            GLES30.glAttachShader(created, vertex)
            GLES30.glAttachShader(created, fragment)
            GLES30.glLinkProgram(created)
            val status = IntArray(1)
            GLES30.glGetProgramiv(created, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(created) }
            return created
        } catch (failure: RuntimeException) {
            if (created != 0) GLES30.glDeleteProgram(created)
            throw failure
        } finally {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0) { "Unable to create an OpenGL shader" }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error(log)
        }
        return shader
    }

    private fun createProgramSafely(vertexSource: String, fragmentSource: String): Int = try {
        programFactory?.invoke(vertexSource, fragmentSource)
            ?: createProgram(vertexSource, fragmentSource)
    } catch (failure: RuntimeException) {
        Log.w(RENDERER_LOG_TAG, "Depth preview program creation failed", failure)
        0
    }

    private fun glOperationSucceeded(stage: String): Boolean {
        val error = GLES30.glGetError()
        if (error == GLES30.GL_NO_ERROR) return true
        failRenderer(stage, IllegalStateException("OpenGL error 0x${error.toString(16)}"))
        return false
    }

    private fun failRenderer(stage: String, failure: Throwable? = null) {
        if (rendererUnavailable) return
        rendererUnavailable = true
        if (failure == null) {
            Log.w(RENDERER_LOG_TAG, "Depth preview unavailable at $stage")
        } else {
            Log.w(RENDERER_LOG_TAG, "Depth preview unavailable at $stage", failure)
        }
        gpuGeometry.values.forEach(::deleteGeometry)
        gpuGeometry.clear()
        uploadState.invalidate()
        pendingPrewarmScene = null
        refinementRequestedForScene = null
        if (bedProgram != 0) GLES30.glDeleteProgram(bedProgram)
        if (toolpathProgram != 0) GLES30.glDeleteProgram(toolpathProgram)
        if (lineProgram != 0) GLES30.glDeleteProgram(lineProgram)
        bedProgram = 0
        toolpathProgram = 0
        lineProgram = 0
        reportUnavailable()
    }

    private companion object {
        const val RENDERER_LOG_TAG = "DuckyPreview"
        const val GPU_GEOMETRY_CACHE_SIZE = 2
        const val PREPARED_GEOMETRY_CACHE_SIZE = 3
        const val BED_FLOATS_PER_VERTEX = 8
        const val BED_STRIDE_BYTES = BED_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        const val BED_POSITION_OFFSET_BYTES = 0
        const val BED_COLOR_OFFSET_BYTES = 3 * Float.SIZE_BYTES
        const val BED_ACROSS_OFFSET_BYTES = 7 * Float.SIZE_BYTES
        const val TOOLPATH_VERTICES_PER_INSTANCE = 4
        const val BED_VERTEX_SHADER = """#version 300 es
            uniform mat4 uMvp;
            in vec3 aPosition;
            in vec4 aColor;
            in float aAcross;
            out vec4 vColor;
            out float vAcross;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vColor = aColor;
                vAcross = aAcross;
            }
        """
        const val TOOLPATH_VERTEX_SHADER = """#version 300 es
            uniform mat4 uMvp;
            in vec3 aStart;
            in vec3 aEnd;
            in float aHalfWidth;
            in vec4 aColor;
            out vec4 vColor;
            out float vAcross;
            const vec2 CORNERS[4] = vec2[4](
                vec2(0.0, -1.0),
                vec2(1.0, -1.0),
                vec2(0.0, 1.0),
                vec2(1.0, 1.0)
            );
            void main() {
                vec2 delta = aEnd.xy - aStart.xy;
                vec2 tangent = delta / max(length(delta), 0.0001);
                vec2 normal = vec2(-tangent.y, tangent.x);
                vec2 corner = CORNERS[gl_VertexID];
                vec3 cappedStart = vec3(aStart.xy - tangent * aHalfWidth, aStart.z);
                vec3 cappedEnd = vec3(aEnd.xy + tangent * aHalfWidth, aEnd.z);
                vec3 position = mix(cappedStart, cappedEnd, corner.x);
                position.xy += normal * aHalfWidth * corner.y;
                gl_Position = uMvp * vec4(position, 1.0);
                vColor = aColor;
                vAcross = corner.y;
            }
        """
        const val LINE_VERTEX_SHADER = """#version 300 es
            uniform mat4 uMvp;
            in vec3 aPosition;
            in vec4 aColor;
            out vec4 vColor;
            out float vAcross;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vColor = aColor;
                vAcross = 0.0;
            }
        """
        const val FRAGMENT_SHADER = """#version 300 es
            precision mediump float;
            in vec4 vColor;
            in float vAcross;
            out vec4 outColor;
            void main() {
                float edge = smoothstep(0.68, 0.98, abs(vAcross));
                float crown = (1.0 - abs(vAcross)) * 0.08;
                vec3 core = min(vColor.rgb * (1.0 + crown), vec3(1.0));
                outColor = vec4(mix(core, vColor.rgb * 0.14, edge), vColor.a);
            }
        """
    }
}

private data class AdaptiveToolpathWorkload(
    val scene: ToolpathScene,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val denseOverview: Boolean,
)

private data class ToolpathGpuGeometry(
    val bedBufferId: Int,
    val bedVertexCount: Int,
    val instanceBufferId: Int,
    val instanceCount: Int,
    val lineBufferId: Int,
    val lineVertexCount: Int,
)

internal class ToolpathGeometryUploadState(private val capacity: Int = 2) {
    private val uploadedScenes = ArrayList<ToolpathScene>(capacity)

    init {
        require(capacity > 0)
    }

    fun needsUpload(scene: ToolpathScene): Boolean = scene !in uploadedScenes

    fun markUploaded(scene: ToolpathScene): ToolpathScene? {
        uploadedScenes.remove(scene)
        uploadedScenes += scene
        return if (uploadedScenes.size > capacity) uploadedScenes.removeAt(0) else null
    }

    fun markUsed(scene: ToolpathScene) {
        if (uploadedScenes.remove(scene)) uploadedScenes += scene
    }

    fun remove(scene: ToolpathScene) {
        uploadedScenes.remove(scene)
    }

    fun invalidate() {
        uploadedScenes.clear()
    }
}

internal object ToolpathMeshBuilder {
    private val roleColors = arrayOf(
        floatArrayOf(1f, 0.812f, 0.251f),
        floatArrayOf(0.267f, 0.843f, 1f),
        floatArrayOf(0.4f, 0.545f, 1f),
        floatArrayOf(1f, 0.384f, 0.816f),
        floatArrayOf(0.655f, 0.545f, 0.98f),
        floatArrayOf(0.369f, 0.902f, 0.659f),
        floatArrayOf(1f, 0.42f, 0.42f),
        floatArrayOf(1f, 0.624f, 0.263f),
        floatArrayOf(0.906f, 0.906f, 0.886f),
        floatArrayOf(0f, 0.843f, 0.741f),
    )
    private val roleWidths =
        floatArrayOf(0.52f, 0.46f, 0.40f, 0.46f, 0.42f, 0.42f, 0.52f, 0.48f, 0.36f, 0.46f)

    internal const val INSTANCE_STRIDE_BYTES = 32
    internal const val INSTANCE_START_OFFSET_BYTES = 0
    internal const val INSTANCE_END_OFFSET_BYTES = 3 * Float.SIZE_BYTES
    internal const val INSTANCE_HALF_WIDTH_OFFSET_BYTES = 6 * Float.SIZE_BYTES
    internal const val INSTANCE_COLOR_OFFSET_BYTES = 7 * Float.SIZE_BYTES
    internal const val LINE_VERTEX_STRIDE_BYTES = 4 * Float.SIZE_BYTES
    internal const val LINE_POSITION_OFFSET_BYTES = 0
    internal const val LINE_COLOR_OFFSET_BYTES = 3 * Float.SIZE_BYTES
    internal const val EARLY_Z_OPACITY_THRESHOLD = 0.85f

    fun build(scene: ToolpathScene): ToolpathUploadPayload {
        val buildStartedNanos = System.nanoTime()
        val budget = scene.segmentBudgetOverride ?: depthPreviewSegmentBudget(scene.detail)
        val planStartedNanos = System.nanoTime()
        val plan = scene.preview.buildRenderPlan(budget, scene.visibleRoles)
        val renderPlanNanos = System.nanoTime() - planStartedNanos
        val packingStartedNanos = System.nanoTime()
        val bedBuilder = FloatBuilder(2_400)
        val instanceBuilder = if (scene.renderAsLines) null else {
            ToolpathInstanceBuilder(plan.segmentOffsets.size)
        }
        val lineBuilder = if (scene.renderAsLines) ToolpathLineBuilder(plan.segmentOffsets.size) else null
        addBed(bedBuilder, scene.bedSizeX, scene.bedSizeY, scene.bedPolygon)
        val zSpan = (scene.preview.maxZMm - scene.preview.minZMm).coerceAtLeast(0.001f)
        // Preview G-code is layer ordered. For the normal near-opaque view, upload high
        // layers first so depth testing rejects covered internal fragments before shading.
        // A deliberately translucent view keeps source order so inner paths remain visible.
        val segmentIndices = if (scene.opacity >= EARLY_Z_OPACITY_THRESHOLD) {
            plan.segmentOffsets.indices.reversed()
        } else {
            plan.segmentOffsets.indices
        }
        segmentIndices.forEach { index ->
            val offset = plan.segmentOffsets[index]
            val x1 = scene.preview.segments[offset] - scene.bedOriginX
            val y1 = scene.preview.segments[offset + 1] - scene.bedOriginY
            val x2 = scene.preview.segments[offset + 2] - scene.bedOriginX
            val y2 = scene.preview.segments[offset + 3] - scene.bedOriginY
            val z = scene.preview.segments[offset + 4]
            val role = scene.preview.segments[offset + 5].toInt().coerceIn(0, roleColors.lastIndex)
            val dx = x2 - x1
            val dy = y2 - y1
            if (dx * dx + dy * dy < 0.000001f) return@forEach
            val normalizedHeight = ((z - scene.preview.minZMm) / zSpan).coerceIn(0f, 1f)
            val base = roleColors[role]
            val shade = scene.depthContrast * (1f - normalizedHeight) * 0.56f
            val halfWidth = roleWidths[role] / 2f
            instanceBuilder?.segment(
                x1, y1, z + 0.024f,
                x2, y2, z + 0.024f,
                halfWidth,
                base[0] * (1f - shade),
                base[1] * (1f - shade),
                base[2] * (1f - shade),
                scene.opacity,
            )
            lineBuilder?.segment(
                x1, y1, z + 0.024f,
                x2, y2, z + 0.024f,
                base[0] * (1f - shade),
                base[1] * (1f - shade),
                base[2] * (1f - shade),
                scene.opacity,
            )
        }
        val bedVertices = bedBuilder.finish()
        val toolpathInstances = instanceBuilder?.finish() ?: ByteBuffer.allocateDirect(0)
        val lineVertices = lineBuilder?.finish() ?: ByteBuffer.allocateDirect(0)
        return ToolpathUploadPayload(
            bedVertices = bedVertices,
            toolpathInstances = toolpathInstances,
            instanceCount = instanceBuilder?.instanceCount ?: 0,
            lineVertices = lineVertices,
            lineVertexCount = lineBuilder?.vertexCount ?: 0,
            geometryBuildNanos = System.nanoTime() - buildStartedNanos,
            renderPlanNanos = renderPlanNanos,
            geometryPackNanos = System.nanoTime() - packingStartedNanos,
        )
    }

    private fun addBed(builder: FloatBuilder, width: Float, depth: Float, bedPolygon: List<Float>) {
        val polygon = bedPolygon.takeIf { bedPolygonIsValid(it, width, depth) }
            ?: rectangularBedPolygon(width, depth)
        val bedColor = floatArrayOf(0.15f, 0.16f, 0.145f)
        triangulateBedPolygon(polygon).chunked(3).forEach { triangle ->
            triangle.forEach { index ->
                builder.vertex(polygon[index * 2], polygon[index * 2 + 1], -0.08f, bedColor, 1f)
            }
        }
        val step = if (max(width, depth) <= 230f) 20f else 30f
        var x = 0f
        while (x <= width) {
            verticalBedSegments(x, polygon).forEach { (start, end) ->
                addRibbon(
                    builder, x, start, x, end, -0.06f, -1f, 0f, 0.12f,
                    floatArrayOf(0.33f, 0.35f, 0.31f), 0.72f,
                )
            }
            x += step
        }
        var y = 0f
        while (y <= depth) {
            horizontalBedSegments(y, polygon).forEach { (start, end) ->
                addRibbon(
                    builder, start, y, end, y, -0.06f, 0f, 1f, 0.12f,
                    floatArrayOf(0.33f, 0.35f, 0.31f), 0.72f,
                )
            }
            y += step
        }
        repeat(polygon.size / 2) { index ->
            val next = (index + 1) % (polygon.size / 2)
            val x1 = polygon[index * 2]
            val y1 = polygon[index * 2 + 1]
            val x2 = polygon[next * 2]
            val y2 = polygon[next * 2 + 1]
            val length = hypot(x2 - x1, y2 - y1).coerceAtLeast(0.001f)
            addRibbon(
                builder,
                x1,
                y1,
                x2,
                y2,
                -0.04f,
                -(y2 - y1) / length,
                (x2 - x1) / length,
                0.35f,
                floatArrayOf(0.96f, 0.75f, 0.18f),
                0.9f,
            )
        }
    }

    private fun addRibbon(
        builder: FloatBuilder,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        z: Float,
        nx: Float,
        ny: Float,
        halfWidth: Float,
        color: FloatArray,
        alpha: Float,
        outlined: Boolean = false,
    ) = addQuad(
        builder,
        x1 + nx * halfWidth, y1 + ny * halfWidth, z,
        x2 + nx * halfWidth, y2 + ny * halfWidth, z,
        x2 - nx * halfWidth, y2 - ny * halfWidth, z,
        x1 - nx * halfWidth, y1 - ny * halfWidth, z,
        color,
        alpha,
        across1 = if (outlined) -1f else 0f,
        across2 = if (outlined) -1f else 0f,
        across3 = if (outlined) 1f else 0f,
        across4 = if (outlined) 1f else 0f,
    )

    private fun addQuad(
        builder: FloatBuilder,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        color: FloatArray,
        alpha: Float,
        across1: Float = 0f,
        across2: Float = 0f,
        across3: Float = 0f,
        across4: Float = 0f,
    ) {
        builder.vertex(x1, y1, z1, color, alpha, across1)
        builder.vertex(x2, y2, z2, color, alpha, across2)
        builder.vertex(x3, y3, z3, color, alpha, across3)
        builder.vertex(x1, y1, z1, color, alpha, across1)
        builder.vertex(x3, y3, z3, color, alpha, across3)
        builder.vertex(x4, y4, z4, color, alpha, across4)
    }
}

internal data class ToolpathUploadPayload(
    val bedVertices: FloatBuffer,
    val toolpathInstances: ByteBuffer,
    val instanceCount: Int,
    val lineVertices: ByteBuffer,
    val lineVertexCount: Int,
    val geometryBuildNanos: Long = 0L,
    val renderPlanNanos: Long = 0L,
    val geometryPackNanos: Long = 0L,
) {
    val stagingByteCount: Int
        get() = bedVertices.remaining() * Float.SIZE_BYTES +
            toolpathInstances.remaining() + lineVertices.remaining()
}

private class ToolpathLineBuilder(initialSegmentCapacity: Int) {
    private var values = FloatArray(
        (initialSegmentCapacity * FLOATS_PER_SEGMENT).coerceAtLeast(FLOATS_PER_SEGMENT),
    )
    var vertexCount: Int = 0
        private set

    fun segment(
        startX: Float,
        startY: Float,
        startZ: Float,
        endX: Float,
        endY: Float,
        endZ: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        val offset = vertexCount * FLOATS_PER_VERTEX
        ensure(offset + FLOATS_PER_SEGMENT)
        val color = Float.fromBits(
            colorInt(alpha) shl 24 or
                (colorInt(blue) shl 16) or
                (colorInt(green) shl 8) or
                colorInt(red),
        )
        values[offset] = startX
        values[offset + 1] = startY
        values[offset + 2] = startZ
        values[offset + 3] = color
        values[offset + 4] = endX
        values[offset + 5] = endY
        values[offset + 6] = endZ
        values[offset + 7] = color
        vertexCount += 2
    }

    fun finish(): ByteBuffer {
        val usedFloats = vertexCount * FLOATS_PER_VERTEX
        return ByteBuffer.allocateDirect(usedFloats * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer -> buffer.asFloatBuffer().put(values, 0, usedFloats) }
    }

    private fun ensure(requiredFloats: Int) {
        if (requiredFloats <= values.size) return
        values = values.copyOf(max(values.size * 2, requiredFloats))
    }

    private fun colorInt(value: Float): Int =
        (value.coerceIn(0f, 1f) * 255f).roundToInt()

    private companion object {
        const val FLOATS_PER_VERTEX =
            ToolpathMeshBuilder.LINE_VERTEX_STRIDE_BYTES / Float.SIZE_BYTES
        const val FLOATS_PER_SEGMENT = 2 * FLOATS_PER_VERTEX
    }
}

private class ToolpathInstanceBuilder(initialInstanceCapacity: Int) {
    private var values = FloatArray(
        (initialInstanceCapacity * TOOLPATH_INSTANCE_FLOATS).coerceAtLeast(
            TOOLPATH_INSTANCE_FLOATS,
        ),
    )
    var instanceCount: Int = 0
        private set

    fun segment(
        startX: Float,
        startY: Float,
        startZ: Float,
        endX: Float,
        endY: Float,
        endZ: Float,
        halfWidth: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        val offset = instanceCount * TOOLPATH_INSTANCE_FLOATS
        ensure(offset + TOOLPATH_INSTANCE_FLOATS)
        values[offset] = startX
        values[offset + 1] = startY
        values[offset + 2] = startZ
        values[offset + 3] = endX
        values[offset + 4] = endY
        values[offset + 5] = endZ
        values[offset + 6] = halfWidth
        values[offset + 7] = Float.fromBits(
            colorInt(alpha) shl 24 or
                (colorInt(blue) shl 16) or
                (colorInt(green) shl 8) or
                colorInt(red),
        )
        instanceCount += 1
    }

    fun finish(): ByteBuffer {
        val usedFloats = instanceCount * TOOLPATH_INSTANCE_FLOATS
        return ByteBuffer.allocateDirect(usedFloats * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { buffer -> buffer.asFloatBuffer().put(values, 0, usedFloats) }
    }

    private fun ensure(requiredFloats: Int) {
        if (requiredFloats <= values.size) return
        values = values.copyOf(max(values.size * 2, requiredFloats))
    }

    private fun colorInt(value: Float): Int =
        (value.coerceIn(0f, 1f) * 255f).roundToInt()

    private companion object {
        const val TOOLPATH_INSTANCE_FLOATS =
            ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES / Float.SIZE_BYTES
    }
}

private class FloatBuilder(initialCapacity: Int) {
    private var values = allocate(initialCapacity.coerceAtLeast(64))

    fun vertex(
        x: Float,
        y: Float,
        z: Float,
        color: FloatArray,
        alpha: Float,
        across: Float = 0f,
    ) {
        ensure(8)
        values.put(x)
        values.put(y)
        values.put(z)
        values.put(color[0])
        values.put(color[1])
        values.put(color[2])
        values.put(alpha)
        values.put(across)
    }

    fun finish(): FloatBuffer = values.apply { flip() }

    private fun ensure(additional: Int) {
        if (values.remaining() >= additional) return
        val next = allocate(max(values.capacity() * 2, values.position() + additional))
        values.flip()
        next.put(values)
        values = next
    }

    private fun allocate(capacity: Int): FloatBuffer = ByteBuffer
        .allocateDirect(capacity * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
}

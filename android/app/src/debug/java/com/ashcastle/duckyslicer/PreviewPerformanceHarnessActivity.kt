package com.ashcastle.duckyslicer

import android.app.Activity
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil

internal data class PreviewPerformanceRequest(
    val preview: GcodeLayerPreview,
    val options: SliceOptions,
    val width: Int,
    val height: Int,
    val frameCount: Int,
)

internal data class PreviewTierMetrics(
    val firstFrameMs: Double,
    val settledFrameP50Ms: Double,
    val settledFrameP95Ms: Double,
    val interactionFrameP50Ms: Double,
    val interactionFrameP95Ms: Double,
    val settledCompletionP50Ms: Double,
    val settledCompletionP95Ms: Double,
    val interactionCompletionP50Ms: Double,
    val interactionCompletionP95Ms: Double,
    val settledDrawSubmitP50Ms: Double,
    val settledDrawSubmitP95Ms: Double,
    val interactionDrawSubmitP50Ms: Double,
    val interactionDrawSubmitP95Ms: Double,
    val geometryBuildMs: Double,
    val renderPlanMs: Double,
    val geometryPackMs: Double,
    val geometryUploadMs: Double,
    val geometryUploads: Int,
)

internal data class PreviewSurfaceMetrics(
    val framebufferWidth: Int,
    val framebufferHeight: Int,
    val frameCountPerPhase: Int,
    val automaticDetail: PreviewDetail,
    val gpuRenderer: String,
    val tiers: Map<PreviewDetail, PreviewTierMetrics>,
)

internal class PreviewPerformanceSession internal constructor(
    val request: PreviewPerformanceRequest,
) {
    private val completion = CountDownLatch(1)
    private val outcome = AtomicReference<PreviewPerformanceOutcome?>()

    fun complete(metrics: PreviewSurfaceMetrics) {
        if (outcome.compareAndSet(null, PreviewPerformanceOutcome.Success(metrics))) {
            completion.countDown()
        }
    }

    fun fail(failure: Throwable) {
        if (outcome.compareAndSet(null, PreviewPerformanceOutcome.Failure(failure))) {
            completion.countDown()
        }
    }

    fun await(timeoutSeconds: Long): PreviewSurfaceMetrics {
        check(completion.await(timeoutSeconds, TimeUnit.SECONDS)) {
            "Foreground Preview measurement exceeded $timeoutSeconds seconds"
        }
        return when (val completed = checkNotNull(outcome.get())) {
            is PreviewPerformanceOutcome.Success -> completed.metrics
            is PreviewPerformanceOutcome.Failure -> throw completed.failure
        }
    }
}

private sealed interface PreviewPerformanceOutcome {
    data class Success(val metrics: PreviewSurfaceMetrics) : PreviewPerformanceOutcome
    data class Failure(val failure: Throwable) : PreviewPerformanceOutcome
}

internal object PreviewPerformanceHarness {
    private val active = AtomicReference<PreviewPerformanceSession?>()

    fun begin(request: PreviewPerformanceRequest): PreviewPerformanceSession {
        val session = PreviewPerformanceSession(request)
        check(active.compareAndSet(null, session)) { "A Preview measurement is already active" }
        return session
    }

    fun current(): PreviewPerformanceSession? = active.get()

    fun finish(session: PreviewPerformanceSession) {
        active.compareAndSet(session, null)
    }
}

/** Debug-only visible surface used to measure real Preview frame cadence on physical hardware. */
class PreviewPerformanceHarnessActivity : Activity() {
    private var surface: GLSurfaceView? = null
    private var session: PreviewPerformanceSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val activeSession = PreviewPerformanceHarness.current()
        if (activeSession == null) {
            finish()
            return
        }
        session = activeSession
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val request = activeSession.request
        val view = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 8)
            setRenderer(
                ForegroundPreviewBenchmarkRenderer(
                    request = request,
                    onComplete = { metrics ->
                        activeSession.complete(metrics)
                        runOnUiThread { finish() }
                    },
                    onFailure = { failure ->
                        activeSession.fail(failure)
                        runOnUiThread { finish() }
                    },
                ),
            )
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }
        surface = view
        setContentView(
            view,
            ViewGroup.LayoutParams(request.width, request.height),
        )
    }

    override fun onDestroy() {
        surface?.onPause()
        session?.let { activeSession ->
            activeSession.fail(IllegalStateException("Foreground Preview activity stopped before completion"))
            PreviewPerformanceHarness.finish(activeSession)
        }
        super.onDestroy()
    }
}

private class ForegroundPreviewBenchmarkRenderer(
    private val request: PreviewPerformanceRequest,
    private val onComplete: (PreviewSurfaceMetrics) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : GLSurfaceView.Renderer {
    private val renderer = ToolpathRenderer()
    private val details = listOf(
        PreviewDetail.PERFORMANCE,
        PreviewDetail.BALANCED,
        PreviewDetail.DETAIL,
    )
    private val results = linkedMapOf<PreviewDetail, PreviewTierMetrics>()
    private var detailIndex = 0
    private var phase = Phase.FIRST
    private var phaseFrame = 0
    private var lastFrameStartedNanos = 0L
    private var firstFrameStartedNanos = 0L
    private var uploadsAtTierStart = 0
    private var telemetryAtTierStart = ToolpathRendererTelemetry(0.0, 0.0, 0.0, 0.0, 0.0)
    private var firstFrameMs = 0.0
    private val settledIntervals = mutableListOf<Double>()
    private val interactionIntervals = mutableListOf<Double>()
    private val settledCompletions = mutableListOf<Double>()
    private val interactionCompletions = mutableListOf<Double>()
    private val settledDrawSubmissions = mutableListOf<Double>()
    private val interactionDrawSubmissions = mutableListOf<Double>()
    private var width = 0
    private var height = 0
    private var gpuRenderer = ""
    private var automaticDetail = PreviewDetail.PERFORMANCE
    private var finished = false

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            renderer.onSurfaceCreated(unused, config)
            gpuRenderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
            startTier()
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        renderer.onSurfaceChanged(unused, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        if (finished) return
        try {
            check(width == request.width && height == request.height) {
                "Foreground Preview surface is ${width}x${height}, expected ${request.width}x${request.height}"
            }
            val started = SystemClock.elapsedRealtimeNanos()
            val interval = if (lastFrameStartedNanos == 0L) {
                null
            } else {
                (started - lastFrameStartedNanos) / 1_000_000.0
            }
            lastFrameStartedNanos = started
            when (phase) {
                Phase.SETTLED -> renderer.orbitBy(1.5f, -0.5f)
                Phase.INTERACTION -> renderer.orbitBy(-1.25f, 0.4f)
                else -> Unit
            }
            renderer.onDrawFrame(unused)
            val drawSubmitMs = renderer.telemetryForTest().lastDrawSubmitMs
            val completionMs = when (phase) {
                Phase.FIRST,
                Phase.SETTLED_WARMUP,
                Phase.SETTLED,
                Phase.INTERACTION_WARMUP,
                Phase.INTERACTION -> {
                    GLES30.glFinish()
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                }
                Phase.AUTOMATIC_CALIBRATION -> null
            }
            when (phase) {
                Phase.FIRST -> {
                    firstFrameMs = (SystemClock.elapsedRealtimeNanos() - firstFrameStartedNanos) / 1_000_000.0
                    transition(Phase.SETTLED_WARMUP)
                }
                Phase.SETTLED_WARMUP -> if (++phaseFrame >= WARMUP_FRAMES) {
                    transition(Phase.SETTLED)
                }
                Phase.SETTLED -> {
                    interval?.let(settledIntervals::add)
                    checkNotNull(completionMs).let(settledCompletions::add)
                    settledDrawSubmissions += drawSubmitMs
                    if (settledIntervals.size >= request.frameCount) {
                        renderer.setInteractionActive(true)
                        transition(Phase.INTERACTION_WARMUP)
                    }
                }
                Phase.INTERACTION_WARMUP -> if (++phaseFrame >= WARMUP_FRAMES) {
                    transition(Phase.INTERACTION)
                }
                Phase.INTERACTION -> {
                    interval?.let(interactionIntervals::add)
                    checkNotNull(completionMs).let(interactionCompletions::add)
                    interactionDrawSubmissions += drawSubmitMs
                    if (interactionIntervals.size >= request.frameCount) finishTier()
                }
                Phase.AUTOMATIC_CALIBRATION -> {
                    automaticDetail = checkNotNull(renderer.effectiveDetailForTest())
                    phaseFrame += 1
                    if (renderer.automaticCalibrationSettledForTest()) {
                        finishBenchmark()
                    } else {
                        check(phaseFrame < MAXIMUM_AUTOMATIC_CALIBRATION_FRAMES) {
                            "Automatic Preview calibration did not settle within " +
                                "$MAXIMUM_AUTOMATIC_CALIBRATION_FRAMES frames"
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            fail(failure)
        }
    }

    private fun startTier() {
        val detail = details[detailIndex]
        renderer.setInteractionActive(false)
        renderer.submit(
            ToolpathScene(
                preview = request.preview,
                bedSizeX = request.options.bedSizeX,
                bedSizeY = request.options.bedSizeY,
                opacity = 1f,
                depthContrast = 0.8f,
                detail = detail,
                bedPolygon = request.options.bedPolygon,
                bedOriginX = request.options.bedOriginX,
                bedOriginY = request.options.bedOriginY,
            ),
        )
        uploadsAtTierStart = renderer.geometryUploadCountForTest()
        telemetryAtTierStart = renderer.telemetryForTest()
        settledIntervals.clear()
        interactionIntervals.clear()
        settledCompletions.clear()
        interactionCompletions.clear()
        settledDrawSubmissions.clear()
        interactionDrawSubmissions.clear()
        firstFrameStartedNanos = SystemClock.elapsedRealtimeNanos()
        lastFrameStartedNanos = 0L
        transition(Phase.FIRST)
    }

    private fun finishTier() {
        val detail = details[detailIndex]
        val telemetry = renderer.telemetryForTest()
        results[detail] = PreviewTierMetrics(
            firstFrameMs = firstFrameMs,
            settledFrameP50Ms = percentile(settledIntervals, 0.50),
            settledFrameP95Ms = percentile(settledIntervals, 0.95),
            interactionFrameP50Ms = percentile(interactionIntervals, 0.50),
            interactionFrameP95Ms = percentile(interactionIntervals, 0.95),
            settledCompletionP50Ms = percentile(settledCompletions, 0.50),
            settledCompletionP95Ms = percentile(settledCompletions, 0.95),
            interactionCompletionP50Ms = percentile(interactionCompletions, 0.50),
            interactionCompletionP95Ms = percentile(interactionCompletions, 0.95),
            settledDrawSubmitP50Ms = percentile(settledDrawSubmissions, 0.50),
            settledDrawSubmitP95Ms = percentile(settledDrawSubmissions, 0.95),
            interactionDrawSubmitP50Ms = percentile(interactionDrawSubmissions, 0.50),
            interactionDrawSubmitP95Ms = percentile(interactionDrawSubmissions, 0.95),
            geometryBuildMs = telemetry.geometryBuildMs - telemetryAtTierStart.geometryBuildMs,
            renderPlanMs = telemetry.renderPlanMs - telemetryAtTierStart.renderPlanMs,
            geometryPackMs = telemetry.geometryPackMs - telemetryAtTierStart.geometryPackMs,
            geometryUploadMs = telemetry.geometryUploadMs - telemetryAtTierStart.geometryUploadMs,
            geometryUploads = renderer.geometryUploadCountForTest() - uploadsAtTierStart,
        )
        detailIndex += 1
        if (detailIndex < details.size) {
            startTier()
            return
        }
        startAutomaticCalibration()
    }

    private fun startAutomaticCalibration() {
        renderer.setInteractionActive(false)
        renderer.submit(
            ToolpathScene(
                preview = request.preview,
                bedSizeX = request.options.bedSizeX,
                bedSizeY = request.options.bedSizeY,
                opacity = 1f,
                depthContrast = 0.8f,
                detail = PreviewDetail.AUTOMATIC,
                bedPolygon = request.options.bedPolygon,
                bedOriginX = request.options.bedOriginX,
                bedOriginY = request.options.bedOriginY,
            ),
        )
        transition(Phase.AUTOMATIC_CALIBRATION)
    }

    private fun finishBenchmark() {
        finished = true
        renderer.releaseGpuGeometryForMemoryPressure()
        check(GLES30.glGetError() == GLES30.GL_NO_ERROR) {
            "Foreground Preview measurement ended with an OpenGL error"
        }
        onComplete(
            PreviewSurfaceMetrics(
                framebufferWidth = width,
                framebufferHeight = height,
                frameCountPerPhase = request.frameCount,
                automaticDetail = automaticDetail,
                gpuRenderer = gpuRenderer,
                tiers = results.toMap(),
            ),
        )
    }

    private fun transition(next: Phase) {
        phase = next
        phaseFrame = 0
    }

    private fun fail(failure: Throwable) {
        if (finished) return
        finished = true
        onFailure(failure)
    }

    private fun percentile(samples: List<Double>, fraction: Double): Double {
        check(samples.isNotEmpty()) { "Preview measurement returned no frame intervals" }
        val sorted = samples.sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private enum class Phase {
        FIRST,
        SETTLED_WARMUP,
        SETTLED,
        INTERACTION_WARMUP,
        INTERACTION,
        AUTOMATIC_CALIBRATION,
    }

    private companion object {
        const val WARMUP_FRAMES = 3
        const val MAXIMUM_AUTOMATIC_CALIBRATION_FRAMES = 12
    }
}

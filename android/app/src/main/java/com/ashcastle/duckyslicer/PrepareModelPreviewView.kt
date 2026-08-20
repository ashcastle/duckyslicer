package com.ashcastle.duckyslicer

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
internal fun DepthTestedPrepareModelScene(
    projectObjects: List<ProjectObject>,
    placements: Map<String, PrepareObjectPlacement>,
    selectedObjectId: String?,
    bedSizeX: Float,
    bedSizeY: Float,
    bedPolygon: List<Float>,
    bedExcludeArea: List<Float>,
    yawDegrees: Float,
    pitchDegrees: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    interactionActive: Boolean,
    overlays: List<PrepareModelOverlayData>,
    onUnavailable: () -> Unit,
    memoryPressureActive: Boolean = false,
    onMemoryPressure: () -> Unit = {},
    onMemoryPressureRecovered: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val topology = projectObjects.flatMap { projectObject ->
        projectObject.volumes.map { volume ->
            PrepareModelTopologyKey(
                objectId = projectObject.id,
                volumeId = volume.id,
                filamentSlot = volume.filamentSlot,
                role = volume.role,
                model = volume.model,
            )
        }
    }
    var sceneLoad by remember(topology, bedSizeX, bedSizeY, bedPolygon, bedExcludeArea) {
        mutableStateOf(
            PrepareModelSceneLoad(
                geometry = PrepareModelSceneBuilder.build(
                    emptyList(),
                    bedSizeX,
                    bedSizeY,
                    bedPolygon,
                    bedExcludeArea,
                ),
                complete = projectObjects.isEmpty(),
            ),
        )
    }
    LaunchedEffect(topology, bedSizeX, bedSizeY, bedPolygon, bedExcludeArea) {
        if (projectObjects.isNotEmpty()) {
            val geometry = withContext(Dispatchers.Default) {
                PrepareModelSceneBuilder.build(
                    projectObjects,
                    bedSizeX,
                    bedSizeY,
                    bedPolygon,
                    bedExcludeArea,
                )
            }
            sceneLoad = PrepareModelSceneLoad(geometry, complete = true)
        }
    }
    var detailRefinementReady by remember(topology) { mutableStateOf(false) }
    LaunchedEffect(topology, sceneLoad.complete, interactionActive, memoryPressureActive) {
        detailRefinementReady = false
        if (!sceneLoad.complete || interactionActive || memoryPressureActive) return@LaunchedEffect
        delay(PREPARE_DETAIL_REFINEMENT_DELAY_MS)
        detailRefinementReady = true
    }
    val objectDrawStates = remember(projectObjects, placements) {
        projectObjects.associate { projectObject ->
            projectObject.id to PrepareObjectDrawState(
                objectId = projectObject.id,
                transform = projectObject.transform,
                minimumRotatedZ = checkNotNull(placements[projectObject.id]).minimumRotatedZ,
            )
        }
    }
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    val currentOnMemoryPressure = rememberUpdatedState(onMemoryPressure)
    val currentOnMemoryPressureRecovered = rememberUpdatedState(onMemoryPressureRecovered)
    AndroidView(
        factory = { context ->
            PrepareModelSurfaceView(
                context = context,
                onUnavailable = { currentOnUnavailable.value() },
                onMemoryPressure = { currentOnMemoryPressure.value() },
                onMemoryPressureRecovered = { currentOnMemoryPressureRecovered.value() },
            )
        },
        update = { view ->
            view.setMemoryPressureActive(memoryPressureActive)
            view.submit(
                geometry = sceneLoad.geometry,
                objects = objectDrawStates,
                selectedObjectId = selectedObjectId,
                camera = PrepareModelCamera(
                    yawDegrees = yawDegrees,
                    pitchDegrees = pitchDegrees,
                    zoom = zoom,
                    panX = panX,
                    panY = panY,
                ),
                interactionActive = interactionActive,
                refinementReady = detailRefinementReady,
                overlays = overlays.takeIf { sceneLoad.complete }.orEmpty(),
            )
        },
        modifier = modifier,
    )
}

private data class PrepareModelTopologyKey(
    val objectId: String,
    val volumeId: String,
    val filamentSlot: Int,
    val role: ProjectVolumeRole,
    val model: ModelInfo,
)

private data class PrepareModelSceneLoad(
    val geometry: PrepareModelSceneGeometry,
    val complete: Boolean,
)

internal data class PrepareModelCamera(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val zoom: Float,
    val panX: Float,
    val panY: Float,
)

internal data class PrepareModelMeshData(
    val objectId: String,
    val volumeId: String,
    val filamentSlot: Int,
    val role: ProjectVolumeRole,
    val sourceCenter: FloatArray,
    val vertices: FloatArray,
    val coarseVertices: FloatArray = vertices,
    val detailVertices: FloatArray = vertices,
) {
    val vertexCount: Int get() = vertices.size / PREPARE_VERTEX_FLOATS
}

internal data class PrepareModelSceneGeometry(
    val bedSizeX: Float,
    val bedSizeY: Float,
    val bedFill: FloatArray,
    val bedGrid: FloatArray,
    val bedOutline: FloatArray,
    val bedExcludeOutline: FloatArray,
    val meshes: List<PrepareModelMeshData>,
) {
    /** Stable draw ordering avoids sorting every camera frame in high-volume projects. */
    val meshDrawOrder: IntArray = meshes.indices
        .sortedBy { index -> meshes[index].role != ProjectVolumeRole.MODEL_PART }
        .toIntArray()
}

internal fun uniquePrepareVertexArrays(meshes: List<PrepareModelMeshData>): List<FloatArray> {
    val seen = IdentityHashMap<FloatArray, Unit>()
    val unique = ArrayList<FloatArray>()
    meshes.forEach { mesh ->
        listOf(mesh.coarseVertices, mesh.vertices, mesh.detailVertices).forEach { vertices ->
            if (seen.put(vertices, Unit) == null) unique += vertices
        }
    }
    return unique
}

private fun boundedPrepareLowMeshes(
    meshes: List<PrepareModelMeshData>,
    lowDetailBudgetBytes: Long,
): List<PrepareModelMeshData> {
    require(lowDetailBudgetBytes >= 0L)
    val baselineArrays = IdentityHashMap<FloatArray, Unit>()
    var baselineBytes = 0L
    meshes.forEach { mesh ->
        if (baselineArrays.put(mesh.coarseVertices, Unit) == null) {
            baselineBytes += mesh.coarseVertices.size.toLong() * Float.SIZE_BYTES
        }
    }
    var retainedBytes = baselineBytes
    val visitedPreviewArrays = IdentityHashMap<FloatArray, Unit>()
    val retainedPreviewArrays = IdentityHashMap<FloatArray, Unit>()
    meshes
        .withIndex()
        .sortedBy { indexed -> indexed.value.role != ProjectVolumeRole.MODEL_PART }
        .forEach { indexed ->
            val preview = indexed.value.vertices
            if (
                preview !== indexed.value.coarseVertices &&
                !baselineArrays.containsKey(preview) &&
                visitedPreviewArrays.put(preview, Unit) == null
            ) {
                val previewBytes = preview.size.toLong() * Float.SIZE_BYTES
                if (previewBytes <= lowDetailBudgetBytes - retainedBytes) {
                    retainedPreviewArrays[preview] = Unit
                    retainedBytes += previewBytes
                }
            }
        }
    return meshes.map { mesh ->
        val preview = mesh.vertices
        if (
            preview === mesh.coarseVertices || baselineArrays.containsKey(preview) ||
            retainedPreviewArrays.containsKey(preview)
        ) mesh else mesh.copy(vertices = mesh.coarseVertices)
    }
}

private fun boundedPrepareDetailMeshes(
    meshes: List<PrepareModelMeshData>,
    additionalDetailBudgetBytes: Long,
): List<PrepareModelMeshData> {
    require(additionalDetailBudgetBytes >= 0L)
    val lowDetailArrays = IdentityHashMap<FloatArray, Unit>()
    meshes.forEach { mesh -> lowDetailArrays[mesh.vertices] = Unit }
    val visitedDetailArrays = IdentityHashMap<FloatArray, Unit>()
    val retainedDetailArrays = IdentityHashMap<FloatArray, Unit>()
    var retainedBytes = 0L
    meshes
        .withIndex()
        .sortedBy { indexed -> indexed.value.role != ProjectVolumeRole.MODEL_PART }
        .forEach { indexed ->
            val detail = indexed.value.detailVertices
            if (
                detail !== indexed.value.vertices &&
                !lowDetailArrays.containsKey(detail) &&
                visitedDetailArrays.put(detail, Unit) == null
            ) {
                val detailBytes = detail.size.toLong() * Float.SIZE_BYTES
                if (detailBytes <= additionalDetailBudgetBytes - retainedBytes) {
                    retainedDetailArrays[detail] = Unit
                    retainedBytes += detailBytes
                }
            }
        }
    return meshes.map { mesh ->
        val detail = mesh.detailVertices
        if (
            detail === mesh.vertices || lowDetailArrays.containsKey(detail) ||
            retainedDetailArrays.containsKey(detail)
        ) mesh else mesh.copy(detailVertices = mesh.vertices)
    }
}

private fun prepareVolumeColor(mesh: PrepareModelMeshData) =
    projectVolumeColor(mesh.role, mesh.filamentSlot)

internal object PrepareModelSceneBuilder {
    fun build(
        projectObjects: List<ProjectObject>,
        bedSizeX: Float,
        bedSizeY: Float,
        requestedBedPolygon: List<Float>,
        requestedBedExcludeArea: List<Float> = listOf(0f, 0f),
        additionalDetailBudgetBytes: Long = MAX_PREPARE_ADDITIONAL_DETAIL_GPU_BYTES,
        lowDetailBudgetBytes: Long = MAX_PREPARE_LOW_DETAIL_GPU_BYTES,
    ): PrepareModelSceneGeometry {
        require(bedSizeX.isFinite() && bedSizeX > 0f && bedSizeY.isFinite() && bedSizeY > 0f)
        val bedPolygon = requestedBedPolygon.takeIf {
            bedPolygonIsValid(it, bedSizeX, bedSizeY)
        } ?: rectangularBedPolygon(bedSizeX, bedSizeY)
        val bedFill = ArrayList<Float>()
        triangulateBedPolygon(bedPolygon).forEach { index ->
            addVertex(
                bedFill,
                bedPolygon[index * 2],
                bedPolygon[index * 2 + 1],
                PREPARE_BED_FILL_Z,
            )
        }
        val bedGrid = ArrayList<Float>()
        val gridStep = if (max(bedSizeX, bedSizeY) <= 230f) 20f else 30f
        var x = 0f
        while (x <= bedSizeX) {
            verticalBedSegments(x, bedPolygon).forEach { (start, end) ->
                addLine(bedGrid, x, start, x, end, PREPARE_BED_GRID_Z)
            }
            x += gridStep
        }
        var y = 0f
        while (y <= bedSizeY) {
            horizontalBedSegments(y, bedPolygon).forEach { (start, end) ->
                addLine(bedGrid, start, y, end, y, PREPARE_BED_GRID_Z)
            }
            y += gridStep
        }
        val bedOutline = ArrayList<Float>(bedPolygon.size / 2 * PREPARE_VERTEX_FLOATS)
        for (index in bedPolygon.indices step 2) {
            addVertex(
                bedOutline,
                bedPolygon[index],
                bedPolygon[index + 1],
                PREPARE_BED_OUTLINE_Z,
            )
        }
        val bedExcludeOutline = ArrayList<Float>()
        val bedExcludeArea = requestedBedExcludeArea.takeIf {
            bedExcludeAreaIsValid(it, bedSizeX, bedSizeY) && it.size >= 6
        }
        bedExcludeArea?.let { points ->
            repeat(points.size / 2) { index ->
                val next = (index + 1) % (points.size / 2)
                addLine(
                    bedExcludeOutline,
                    points[index * 2],
                    points[index * 2 + 1],
                    points[next * 2],
                    points[next * 2 + 1],
                    PREPARE_BED_EXCLUDE_Z,
                )
            }
        }
        val rawMeshes = projectObjects.flatMap { projectObject ->
            val center = projectObject.geometry().center
            projectObject.volumes.map { volume ->
                PrepareModelMeshData(
                    objectId = projectObject.id,
                    volumeId = volume.id,
                    filamentSlot = volume.filamentSlot,
                    role = volume.role,
                    sourceCenter = center,
                    // The imported preview already is a packed triangle-position stream.
                    // Keep it by reference instead of rebuilding and doubling it with three
                    // duplicated CPU normals per triangle. Flat normals are derived by the
                    // fragment shader from the transformed surface.
                    vertices = volume.model.previewTriangles,
                    coarseVertices = volume.model.coarsePreviewTriangles,
                    detailVertices = volume.model.detailPreviewTriangles,
                )
            }
        }
        val lowMeshes = boundedPrepareLowMeshes(rawMeshes, lowDetailBudgetBytes)
        val meshes = boundedPrepareDetailMeshes(lowMeshes, additionalDetailBudgetBytes)
        return PrepareModelSceneGeometry(
            bedSizeX = bedSizeX,
            bedSizeY = bedSizeY,
            bedFill = bedFill.toFloatArray(),
            bedGrid = bedGrid.toFloatArray(),
            bedOutline = bedOutline.toFloatArray(),
            bedExcludeOutline = bedExcludeOutline.toFloatArray(),
            meshes = meshes,
        )
    }

    private fun addLine(
        destination: MutableList<Float>,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        z: Float,
    ) {
        addVertex(destination, x1, y1, z)
        addVertex(destination, x2, y2, z)
    }

    private fun addVertex(
        destination: MutableList<Float>,
        x: Float,
        y: Float,
        z: Float,
    ) {
        destination += x
        destination += y
        destination += z
    }
}

internal class PrepareModelSurfaceView(
    context: Context,
    private val onUnavailable: () -> Unit,
    private val onMemoryPressure: () -> Unit = {},
    private val onMemoryPressureRecovered: () -> Unit = {},
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val applicationContext = context.applicationContext
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private val renderPending = AtomicBoolean(false)
    private var unavailableReported = false
    @Volatile
    private var rendererReady = false
    private var sceneSubmitted = false
    private var logicalSurfaceWidth = 1
    private var logicalSurfaceHeight = 1
    private var interactionActive = false
    private var memoryPressureActive = false
    private var memoryRecoveryPosted = false
    private var appliedBufferSize: PreviewSurfaceSize? = null
    @Volatile
    private var textureAvailable = false
    @Volatile
    private var renderedBufferSize = PreviewSurfaceSize(0, 0)
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: android.opengl.EGLConfig? = null
    private val renderer = PrepareModelRenderer(
        reportFrameReady = { post { rendererReady = true } },
        reportUnavailable = { post(::reportUnavailableOnce) },
    )
    private val startupWatchdog = Runnable {
        if (!rendererReady && sceneSubmitted) reportUnavailableOnce()
    }
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onLowMemory() = releasePrepareMemory()

        override fun onTrimMemory(level: Int) {
            if (shouldReleaseToolpathGpuMemory(level)) releasePrepareMemory()
        }
    }
    private var memoryCallbacksRegistered = false

    init {
        ensureRenderThread()
        surfaceTextureListener = this
        isOpaque = true
    }

    fun submit(
        geometry: PrepareModelSceneGeometry,
        objects: Map<String, PrepareObjectDrawState>,
        selectedObjectId: String?,
        camera: PrepareModelCamera,
        interactionActive: Boolean,
        refinementReady: Boolean = true,
        overlays: List<PrepareModelOverlayData>,
    ) {
        sceneSubmitted = true
        this.interactionActive = interactionActive
        renderer.submit(
            geometry,
            objects,
            selectedObjectId,
            camera,
            interactionActive,
            refinementReady,
            overlays,
        )
        applyRenderBufferSize()
        removeCallbacks(startupWatchdog)
        if (memoryPressureActive) return
        if (!rendererReady) postDelayed(startupWatchdog, PREPARE_RENDERER_STARTUP_TIMEOUT_MS)
        requestTextureRender()
    }

    fun setMemoryPressureActive(active: Boolean) {
        if (memoryPressureActive == active) return
        memoryPressureActive = active
        memoryRecoveryPosted = false
        if (!active) {
            applyRenderBufferSize()
            requestTextureRender()
        }
    }

    internal fun rendererReadyForTest(): Boolean = rendererReady

    internal fun renderBufferSizeForTest(): PreviewSurfaceSize = renderedBufferSize

    internal fun logicalSurfaceSizeForTest(): PreviewSurfaceSize = PreviewSurfaceSize(
        logicalSurfaceWidth,
        logicalSurfaceHeight,
    )

    internal fun lastMeshVertexCountForTest(): Int = renderer.lastMeshVertexCountForTest()

    internal fun geometryUploadCountForTest(): Int = renderer.geometryUploadCountForTest()

    internal fun retainedTopologyBufferCountForTest(): Int =
        renderer.retainedTopologyBufferCountForTest()

    internal fun releasePrepareMemoryForTest() = releasePrepareMemory()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureRenderThread()
        if (!memoryCallbacksRegistered) {
            applicationContext.registerComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = true
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) requestMemoryPressureRecovery()
    }

    internal fun requestMemoryPressureRecoveryForTest() = requestMemoryPressureRecovery()

    override fun onDetachedFromWindow() {
        removeCallbacks(startupWatchdog)
        textureAvailable = false
        rendererReady = false
        renderPending.set(false)
        appliedBufferSize = null
        if (memoryCallbacksRegistered) {
            applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = false
        }
        val released = CountDownLatch(1)
        val handler = renderHandler
        val thread = renderThread
        handler?.post {
            releaseEgl()
            released.countDown()
        }
        if (handler != null) {
            released.await(PREPARE_RENDERER_RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
        thread?.quitSafely()
        renderHandler = null
        renderThread = null
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        ensureRenderThread()
        textureAvailable = true
        rendererReady = false
        logicalSurfaceWidth = this.width.coerceAtLeast(width).coerceAtLeast(1)
        logicalSurfaceHeight = this.height.coerceAtLeast(height).coerceAtLeast(1)
        renderer.setLogicalViewportSize(logicalSurfaceWidth, logicalSurfaceHeight)
        val target = prepareSurfaceSize(
            logicalSurfaceWidth,
            logicalSurfaceHeight,
            interactionActive,
        )
        surface.setDefaultBufferSize(target.width, target.height)
        appliedBufferSize = target
        renderHandler?.post {
            if (!initializeEgl(surface, target.width, target.height)) return@post
            requestTextureRender()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        logicalSurfaceWidth = this.width.coerceAtLeast(1)
        logicalSurfaceHeight = this.height.coerceAtLeast(1)
        renderer.setLogicalViewportSize(logicalSurfaceWidth, logicalSurfaceHeight)
        appliedBufferSize = null
        applyRenderBufferSize()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        textureAvailable = false
        rendererReady = false
        renderPending.set(false)
        appliedBufferSize = null
        val releasePosted = renderHandler?.post {
            releaseEgl()
            surface.release()
        } == true
        if (!releasePosted) surface.release()
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        logicalSurfaceWidth = width.coerceAtLeast(1)
        logicalSurfaceHeight = height.coerceAtLeast(1)
        renderer.setLogicalViewportSize(logicalSurfaceWidth, logicalSurfaceHeight)
        appliedBufferSize = null
        applyRenderBufferSize()
    }

    private fun releasePrepareMemory() {
        if (!memoryPressureActive) {
            memoryPressureActive = true
            memoryRecoveryPosted = false
            post(onMemoryPressure)
        }
        removeCallbacks(startupWatchdog)
        renderHandler?.post {
            if (makeCurrent()) renderer.releaseGpuGeometryForMemoryPressure()
        }
    }

    private fun requestMemoryPressureRecovery() {
        if (!memoryPressureActive || memoryRecoveryPosted) return
        memoryRecoveryPosted = true
        post(onMemoryPressureRecovered)
    }

    private fun requestTextureRender() {
        if (!textureAvailable || !renderPending.compareAndSet(false, true)) return
        renderHandler?.post {
            renderPending.set(false)
            drawNow()
        }
    }

    private fun applyRenderBufferSize() {
        if (!textureAvailable) return
        val texture = surfaceTexture ?: return
        val target = prepareSurfaceSize(
            logicalSurfaceWidth,
            logicalSurfaceHeight,
            interactionActive,
        )
        if (target == appliedBufferSize) return
        appliedBufferSize = target
        texture.setDefaultBufferSize(target.width, target.height)
        renderHandler?.post {
            resizeEglSurface(texture, target)
        }
    }

    private fun drawNow() {
        if (!textureAvailable || !makeCurrent()) return
        val queriedWidth = IntArray(1)
        val queriedHeight = IntArray(1)
        if (
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, queriedWidth, 0) &&
            EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, queriedHeight, 0)
        ) {
            renderedBufferSize = PreviewSurfaceSize(queriedWidth[0], queriedHeight[0])
        }
        renderer.onDrawFrame(null)
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) reportUnavailableOnceOnUi()
    }

    private fun ensureRenderThread() {
        if (renderThread?.isAlive == true && renderHandler != null) return
        val created = HandlerThread("DuckyPrepareGL").apply { start() }
        renderThread = created
        renderHandler = Handler(created.looper)
    }

    private fun initializeEgl(texture: SurfaceTexture, width: Int, height: Int): Boolean {
        releaseEgl()
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return failEgl("display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return failEgl("initialize")
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        if (
            !EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    8,
                    EGL14.EGL_DEPTH_SIZE,
                    24,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                configs.size,
                count,
                0,
            ) || count[0] != 1
        ) {
            EGL14.eglTerminate(display)
            return failEgl("config")
        }
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(display)
            return failEgl("context")
        }
        val surface = EGL14.eglCreateWindowSurface(
            display,
            config,
            texture,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return failEgl("surface")
        }
        eglDisplay = display
        eglConfig = config
        eglContext = context
        eglSurface = surface
        if (!makeCurrent()) {
            releaseEgl()
            return failEgl("current")
        }
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, width, height)
        return true
    }

    private fun makeCurrent(): Boolean =
        eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT &&
            eglSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(
            eglDisplay,
            eglSurface,
            eglSurface,
            eglContext,
        )

    private fun resizeEglSurface(
        texture: SurfaceTexture,
        target: PreviewSurfaceSize,
    ): Boolean {
        val config = eglConfig ?: return false
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT) return false
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            texture,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE || !makeCurrent()) {
            reportUnavailableOnceOnUi()
            return false
        }
        renderer.onSurfaceChanged(null, target.width, target.height)
        return true
    }

    private fun releaseEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (makeCurrent()) renderer.releaseGpuGeometryForMemoryPressure()
        EGL14.eglMakeCurrent(
            eglDisplay,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglConfig = null
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun failEgl(stage: String): Boolean {
        Log.w(PREPARE_RENDERER_LOG_TAG, "Prepare texture renderer unavailable at $stage")
        reportUnavailableOnceOnUi()
        return false
    }

    private fun reportUnavailableOnceOnUi() {
        post(::reportUnavailableOnce)
    }

    private fun reportUnavailableOnce() {
        if (unavailableReported) return
        unavailableReported = true
        removeCallbacks(startupWatchdog)
        onUnavailable()
    }
}

internal data class PrepareObjectDrawState(
    val objectId: String,
    val transform: ModelTransform,
    val minimumRotatedZ: Float,
) {
    val rotationMatrix: FloatArray = transform.prepareRotationMatrix()
}

internal enum class PrepareModelRenderTier { COARSE, PREVIEW, DETAIL }

internal fun prepareModelRenderTier(
    interactionActive: Boolean,
    overlaysActive: Boolean,
    zoom: Float,
    refinementReady: Boolean = true,
): PrepareModelRenderTier = when {
    overlaysActive -> PrepareModelRenderTier.PREVIEW
    interactionActive && zoom.isFinite() && zoom <= PREPARE_COARSE_INTERACTION_MAX_ZOOM ->
        PrepareModelRenderTier.COARSE
    interactionActive -> PrepareModelRenderTier.PREVIEW
    !refinementReady -> PrepareModelRenderTier.PREVIEW
    else -> PrepareModelRenderTier.DETAIL
}

internal fun ModelTransform.prepareRotationMatrix(): FloatArray {
    val rx = Math.toRadians(rotationXdeg.toDouble()).toFloat()
    val ry = Math.toRadians(rotationYdeg.toDouble()).toFloat()
    val rz = Math.toRadians(rotationZdeg.toDouble()).toFloat()
    val sx = sin(rx)
    val cx = cos(rx)
    val sy = sin(ry)
    val cy = cos(ry)
    val sz = sin(rz)
    val cz = cos(rz)
    // OpenGL consumes column-major matrices. This is Rz * Ry * Rx, matching transformLocal().
    return floatArrayOf(
        cz * cy,
        sz * cy,
        -sy,
        cz * sy * sx - sz * cx,
        sz * sy * sx + cz * cx,
        cy * sx,
        cz * sy * cx + sz * sx,
        sz * sy * cx - cz * sx,
        cy * cx,
    )
}

internal data class PrepareModelFrame(
    val geometry: PrepareModelSceneGeometry,
    val objects: Map<String, PrepareObjectDrawState>,
    val selectedObjectId: String?,
    val camera: PrepareModelCamera,
    val interactionActive: Boolean,
    val refinementReady: Boolean,
    val overlays: List<PrepareModelOverlayData>,
)

internal class PrepareModelRenderer(
    private val reportFrameReady: () -> Unit = {},
    private val reportUnavailable: () -> Unit = {},
    private val programFactory: ((String, String) -> Int)? = null,
) : GLSurfaceView.Renderer {
    @Volatile
    private var latestFrame: PrepareModelFrame? = null
    private var uploadedGeometry: PrepareModelSceneGeometry? = null
    private var bedFillBuffer = 0
    private var bedGridBuffer = 0
    private var bedOutlineBuffer = 0
    private var bedExcludeOutlineBuffer = 0
    private val coarseMeshBuffers = ArrayList<Int>()
    private val meshBuffers = ArrayList<Int>()
    private val detailMeshBuffers = ArrayList<Int>()
    private val meshVertexBuffers = IdentityHashMap<FloatArray, Int>()
    private val overlayBuffers = ArrayList<PrepareOverlayGpuBuffers>()
    private var uploadedOverlays: List<PrepareModelOverlayData>? = null
    private var program = 0
    private var positionLocation = -1
    private var viewportLocation = -1
    private var sceneCenterLocation = -1
    private var bedSizeLocation = -1
    private var sceneScaleLocation = -1
    private var cameraRotationLocation = -1
    private var depthScaleLocation = -1
    private var objectModeLocation = -1
    private var sourceCenterLocation = -1
    private var signedScaleLocation = -1
    private var objectRotationLocation = -1
    private var translationLocation = -1
    private var baseColorLocation = -1
    private var opacityLocation = -1
    private var lightingLocation = -1
    private var selectedLocation = -1
    private var viewportWidth = 1
    private var viewportHeight = 1
    @Volatile
    private var logicalViewportWidth = 0
    @Volatile
    private var logicalViewportHeight = 0
    private var unavailable = false
    private var frameReadyReported = false
    @Volatile
    private var geometryUploadCount = 0
    @Volatile
    private var retainedTopologyBufferCount = 0
    @Volatile
    private var lastMeshVertexCount = 0

    internal fun geometryUploadCountForTest(): Int = geometryUploadCount

    internal fun retainedTopologyBufferCountForTest(): Int = retainedTopologyBufferCount

    internal fun lastMeshVertexCountForTest(): Int = lastMeshVertexCount

    internal fun setLogicalViewportSize(width: Int, height: Int) {
        logicalViewportWidth = width.coerceAtLeast(1)
        logicalViewportHeight = height.coerceAtLeast(1)
    }

    fun submit(
        geometry: PrepareModelSceneGeometry,
        objects: Map<String, PrepareObjectDrawState>,
        selectedObjectId: String?,
        camera: PrepareModelCamera,
        interactionActive: Boolean = false,
        refinementReady: Boolean = true,
        overlays: List<PrepareModelOverlayData> = emptyList(),
    ) {
        latestFrame = PrepareModelFrame(
            geometry = geometry,
            objects = objects,
            selectedObjectId = selectedObjectId,
            camera = camera,
            interactionActive = interactionActive,
            refinementReady = refinementReady,
            overlays = overlays,
        )
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        frameReadyReported = false
        unavailable = false
        releaseGpuGeometryForMemoryPressure()
        GLES30.glClearColor(0.098f, 0.102f, 0.094f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        program = createProgramSafely(PREPARE_VERTEX_SHADER, PREPARE_FRAGMENT_SHADER)
        if (program == 0) {
            failRenderer("program_creation")
            return
        }
        positionLocation = GLES30.glGetAttribLocation(program, "aPosition")
        viewportLocation = GLES30.glGetUniformLocation(program, "uViewport")
        sceneCenterLocation = GLES30.glGetUniformLocation(program, "uSceneCenter")
        bedSizeLocation = GLES30.glGetUniformLocation(program, "uBedSize")
        sceneScaleLocation = GLES30.glGetUniformLocation(program, "uSceneScale")
        cameraRotationLocation = GLES30.glGetUniformLocation(program, "uCameraRotation")
        depthScaleLocation = GLES30.glGetUniformLocation(program, "uDepthScale")
        objectModeLocation = GLES30.glGetUniformLocation(program, "uObjectMode")
        sourceCenterLocation = GLES30.glGetUniformLocation(program, "uSourceCenter")
        signedScaleLocation = GLES30.glGetUniformLocation(program, "uSignedScale")
        objectRotationLocation = GLES30.glGetUniformLocation(program, "uObjectRotation")
        translationLocation = GLES30.glGetUniformLocation(program, "uTranslation")
        baseColorLocation = GLES30.glGetUniformLocation(program, "uBaseColor")
        opacityLocation = GLES30.glGetUniformLocation(program, "uOpacity")
        lightingLocation = GLES30.glGetUniformLocation(program, "uLighting")
        selectedLocation = GLES30.glGetUniformLocation(program, "uSelected")
        if (
            intArrayOf(
                positionLocation,
                viewportLocation,
                sceneCenterLocation,
                bedSizeLocation,
                sceneScaleLocation,
                cameraRotationLocation,
                depthScaleLocation,
                objectModeLocation,
                sourceCenterLocation,
                signedScaleLocation,
                objectRotationLocation,
                translationLocation,
                baseColorLocation,
                opacityLocation,
                lightingLocation,
                selectedLocation,
            ).any { it < 0 }
        ) {
            failRenderer("program_locations")
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        if (logicalViewportWidth <= 0 || logicalViewportHeight <= 0) {
            logicalViewportWidth = viewportWidth
            logicalViewportHeight = viewportHeight
        }
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(unused: GL10?) {
        if (unavailable) return
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val frame = latestFrame ?: return
        val renderTier = prepareModelRenderTier(
            interactionActive = frame.interactionActive,
            overlaysActive = frame.overlays.isNotEmpty(),
            zoom = frame.camera.zoom,
            refinementReady = frame.refinementReady,
        )
        if (uploadedGeometry !== frame.geometry && !initializeGeometry(frame.geometry)) return
        if (!ensureMeshTierUploaded(frame.geometry, renderTier)) return
        if (uploadedOverlays !== frame.overlays && !uploadOverlays(frame)) return
        GLES30.glUseProgram(program)
        applyCamera(frame)
        drawBed(frame.geometry)
        drawMeshes(frame, renderTier)
        drawOverlays(frame)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            failRenderer("frame_draw")
            return
        }
        if (!frameReadyReported) {
            frameReadyReported = true
            reportFrameReady()
        }
    }

    internal fun releaseGpuGeometryForMemoryPressure() {
        val ids = buildList {
            if (bedFillBuffer != 0) add(bedFillBuffer)
            if (bedGridBuffer != 0) add(bedGridBuffer)
            if (bedOutlineBuffer != 0) add(bedOutlineBuffer)
            if (bedExcludeOutlineBuffer != 0) add(bedExcludeOutlineBuffer)
            addAll(meshVertexBuffers.values)
            overlayBuffers.forEach { buffers ->
                if (buffers.vertices != 0) add(buffers.vertices)
                if (buffers.fill != 0) add(buffers.fill)
                if (buffers.lines != 0) add(buffers.lines)
            }
        }.distinct().toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteBuffers(ids.size, ids, 0)
        bedFillBuffer = 0
        bedGridBuffer = 0
        bedOutlineBuffer = 0
        bedExcludeOutlineBuffer = 0
        coarseMeshBuffers.clear()
        meshBuffers.clear()
        detailMeshBuffers.clear()
        meshVertexBuffers.clear()
        overlayBuffers.clear()
        uploadedGeometry = null
        uploadedOverlays = null
        retainedTopologyBufferCount = 0
    }

    private fun initializeGeometry(geometry: PrepareModelSceneGeometry): Boolean {
        releaseGpuGeometryForMemoryPressure()
        val buffers = IntArray(PREPARE_BED_BUFFER_COUNT)
        GLES30.glGenBuffers(buffers.size, buffers, 0)
        if (buffers.any { it == 0 }) {
            GLES30.glDeleteBuffers(buffers.size, buffers, 0)
            failRenderer("buffer_allocation")
            return false
        }
        bedFillBuffer = buffers[0]
        bedGridBuffer = buffers[1]
        bedOutlineBuffer = buffers[2]
        bedExcludeOutlineBuffer = buffers[3]
        uploadBuffer(bedFillBuffer, geometry.bedFill)
        uploadBuffer(bedGridBuffer, geometry.bedGrid)
        uploadBuffer(bedOutlineBuffer, geometry.bedOutline)
        uploadBuffer(bedExcludeOutlineBuffer, geometry.bedExcludeOutline)
        repeat(geometry.meshes.size) {
            coarseMeshBuffers += 0
            meshBuffers += 0
            detailMeshBuffers += 0
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            releaseGpuGeometryForMemoryPressure()
            failRenderer("buffer_upload")
            return false
        }
        uploadedGeometry = geometry
        geometryUploadCount += 1
        retainedTopologyBufferCount = PREPARE_BED_BUFFER_COUNT
        return true
    }

    /** Uploads one coherent LOD on demand; camera-only frames reuse the identity-shared VBOs. */
    private fun ensureMeshTierUploaded(
        geometry: PrepareModelSceneGeometry,
        tier: PrepareModelRenderTier,
    ): Boolean {
        val requested = geometry.meshes.map { mesh ->
            when (tier) {
                PrepareModelRenderTier.COARSE -> mesh.coarseVertices
                PrepareModelRenderTier.PREVIEW -> mesh.vertices
                PrepareModelRenderTier.DETAIL -> mesh.detailVertices
            }
        }
        val seen = IdentityHashMap<FloatArray, Unit>()
        val missing = requested.filter { vertices ->
            !meshVertexBuffers.containsKey(vertices) && seen.put(vertices, Unit) == null
        }
        if (missing.isNotEmpty()) {
            val buffers = IntArray(missing.size)
            GLES30.glGenBuffers(buffers.size, buffers, 0)
            if (buffers.any { it == 0 }) {
                GLES30.glDeleteBuffers(buffers.size, buffers, 0)
                failRenderer("mesh_buffer_allocation")
                return false
            }
            missing.forEachIndexed { index, vertices -> uploadBuffer(buffers[index], vertices) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
                GLES30.glDeleteBuffers(buffers.size, buffers, 0)
                failRenderer("mesh_buffer_upload")
                return false
            }
            missing.forEachIndexed { index, vertices ->
                meshVertexBuffers[vertices] = buffers[index]
            }
            retainedTopologyBufferCount = PREPARE_BED_BUFFER_COUNT + meshVertexBuffers.size
        }
        val targets = when (tier) {
            PrepareModelRenderTier.COARSE -> coarseMeshBuffers
            PrepareModelRenderTier.PREVIEW -> meshBuffers
            PrepareModelRenderTier.DETAIL -> detailMeshBuffers
        }
        requested.forEachIndexed { index, vertices ->
            targets[index] = checkNotNull(meshVertexBuffers[vertices])
        }
        return true
    }

    private fun uploadBuffer(id: Int, values: FloatArray) {
        val buffer = values.toDirectFloatBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            buffer.remaining() * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
    }

    private fun uploadOverlays(frame: PrepareModelFrame): Boolean {
        val existing = overlayBuffers.flatMap { buffers ->
            listOf(buffers.vertices, buffers.fill, buffers.lines)
        }
            .filter { it != 0 }
            .toIntArray()
        if (existing.isNotEmpty()) GLES30.glDeleteBuffers(existing.size, existing, 0)
        overlayBuffers.clear()
        uploadedOverlays = null
        if (frame.overlays.isEmpty()) {
            uploadedOverlays = frame.overlays
            return true
        }
        val valid = frame.overlays.all { overlay ->
            val mesh = frame.geometry.meshes.getOrNull(overlay.meshIndex)
            val customVertexCount = overlay.customVertices?.size?.div(PREPARE_VERTEX_FLOATS)
            mesh != null && overlay.lineIndices.isNotEmpty() && if (customVertexCount != null) {
                overlay.customVertices.isNotEmpty() && overlay.customVertices.size % 9 == 0 &&
                    overlay.customVertices.all(Float::isFinite) && overlay.fillIndices.isEmpty() &&
                    overlay.lineIndices.all { it in 0 until customVertexCount }
            } else {
                overlay.fillIndices.isNotEmpty() &&
                    overlay.fillIndices.all { it in 0 until mesh.vertexCount } &&
                    overlay.lineIndices.all { it in 0 until mesh.vertexCount }
            }
        }
        if (!valid) {
            failRenderer("overlay_indices")
            return false
        }
        val ids = IntArray(frame.overlays.size * 3)
        GLES30.glGenBuffers(ids.size, ids, 0)
        if (ids.any { it == 0 }) {
            GLES30.glDeleteBuffers(ids.size, ids, 0)
            failRenderer("overlay_allocation")
            return false
        }
        frame.overlays.forEachIndexed { index, overlay ->
            val buffers = PrepareOverlayGpuBuffers(
                vertices = ids[index * 3],
                fill = ids[index * 3 + 1],
                lines = ids[index * 3 + 2],
            )
            overlayBuffers += buffers
            overlay.customVertices?.let { uploadBuffer(buffers.vertices, it) }
                ?: uploadIndexBuffer(buffers.fill, overlay.fillIndices)
            uploadIndexBuffer(buffers.lines, overlay.lineIndices)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            val generated = overlayBuffers.flatMap { listOf(it.vertices, it.fill, it.lines) }
                .toIntArray()
            GLES30.glDeleteBuffers(generated.size, generated, 0)
            overlayBuffers.clear()
            failRenderer("overlay_upload")
            return false
        }
        uploadedOverlays = frame.overlays
        return true
    }

    private fun uploadIndexBuffer(id: Int, values: IntArray) {
        val buffer = values.toDirectIntBuffer()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            buffer.remaining() * Int.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
    }

    private fun applyCamera(frame: PrepareModelFrame) {
        val camera = frame.camera
        val logicalWidth = logicalViewportWidth.coerceAtLeast(1)
        val logicalHeight = logicalViewportHeight.coerceAtLeast(1)
        val sceneScale = minOf(logicalWidth * 0.64f, logicalHeight * 0.72f) /
            max(frame.geometry.bedSizeX, frame.geometry.bedSizeY) * camera.zoom
        GLES30.glUniform2f(viewportLocation, logicalWidth.toFloat(), logicalHeight.toFloat())
        GLES30.glUniform2f(
            sceneCenterLocation,
            logicalWidth / 2f + camera.panX,
            logicalHeight * 0.48f + camera.panY,
        )
        GLES30.glUniform2f(bedSizeLocation, frame.geometry.bedSizeX, frame.geometry.bedSizeY)
        GLES30.glUniform1f(sceneScaleLocation, sceneScale)
        val yaw = Math.toRadians(camera.yawDegrees.toDouble()).toFloat()
        val pitch = Math.toRadians(camera.pitchDegrees.toDouble()).toFloat()
        GLES30.glUniform4f(
            cameraRotationLocation,
            cos(yaw),
            sin(yaw),
            sin(pitch),
            cos(pitch),
        )
        GLES30.glUniform1f(
            depthScaleLocation,
            max(frame.geometry.bedSizeX, frame.geometry.bedSizeY) * 8f,
        )
    }

    private fun drawBed(geometry: PrepareModelSceneGeometry) {
        setObjectMode(false)
        GLES30.glUniform1i(lightingLocation, 0)
        GLES30.glUniform1i(selectedLocation, 0)
        drawBuffer(
            bedFillBuffer,
            geometry.bedFill.size / PREPARE_VERTEX_FLOATS,
            GLES30.GL_TRIANGLES,
            0.204f,
            0.216f,
            0.196f,
        )
        drawBuffer(
            bedGridBuffer,
            geometry.bedGrid.size / PREPARE_VERTEX_FLOATS,
            GLES30.GL_LINES,
            0.333f,
            0.349f,
            0.314f,
        )
        drawBuffer(
            bedOutlineBuffer,
            geometry.bedOutline.size / PREPARE_VERTEX_FLOATS,
            GLES30.GL_LINE_LOOP,
            0.965f,
            0.788f,
            0.271f,
        )
        drawBuffer(
            bedExcludeOutlineBuffer,
            geometry.bedExcludeOutline.size / PREPARE_VERTEX_FLOATS,
            GLES30.GL_LINES,
            0.95f,
            0.25f,
            0.18f,
        )
    }

    private fun drawMeshes(frame: PrepareModelFrame, renderTier: PrepareModelRenderTier) {
        GLES30.glUniform1i(lightingLocation, 1)
        setObjectMode(true)
        lastMeshVertexCount = 0
        frame.geometry.meshDrawOrder.forEach { index ->
            val mesh = frame.geometry.meshes[index]
            val objectState = frame.objects[mesh.objectId] ?: return@forEach
            applyObject(objectState, mesh.sourceCenter, frame.geometry)
            val color = prepareVolumeColor(mesh)
            GLES30.glUniform1i(
                selectedLocation,
                if (mesh.objectId == frame.selectedObjectId) 1 else 0,
            )
            val vertices = when (renderTier) {
                PrepareModelRenderTier.COARSE -> mesh.coarseVertices
                PrepareModelRenderTier.PREVIEW -> mesh.vertices
                PrepareModelRenderTier.DETAIL -> mesh.detailVertices
            }
            val buffer = when (renderTier) {
                PrepareModelRenderTier.COARSE -> coarseMeshBuffers[index]
                PrepareModelRenderTier.PREVIEW -> meshBuffers[index]
                PrepareModelRenderTier.DETAIL -> detailMeshBuffers[index]
            }
            lastMeshVertexCount += vertices.size / PREPARE_VERTEX_FLOATS
            val auxiliary = mesh.role != ProjectVolumeRole.MODEL_PART
            if (auxiliary) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            }
            drawBuffer(
                buffer,
                vertices.size / PREPARE_VERTEX_FLOATS,
                GLES30.GL_TRIANGLES,
                color.red,
                color.green,
                color.blue,
                if (auxiliary) 0.48f else 1f,
            )
            if (auxiliary) {
                GLES30.glDepthMask(true)
                GLES30.glDisable(GLES30.GL_BLEND)
            }
        }
    }

    private fun drawOverlays(frame: PrepareModelFrame) {
        if (frame.overlays.isEmpty()) return
        GLES30.glUniform1i(lightingLocation, 0)
        GLES30.glUniform1i(selectedLocation, 0)
        setObjectMode(true)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        frame.overlays.forEachIndexed { index, overlay ->
            val mesh = frame.geometry.meshes[overlay.meshIndex]
            val objectState = frame.objects[mesh.objectId] ?: return@forEachIndexed
            val buffers = overlayBuffers.getOrNull(index) ?: return@forEachIndexed
            applyObject(objectState, mesh.sourceCenter, frame.geometry)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -1f)
            val customVertices = overlay.customVertices
            if (customVertices == null) {
                bindVertexBuffer(meshBuffers[overlay.meshIndex])
                drawElements(
                    buffers.fill,
                    overlay.fillIndices.size,
                    GLES30.GL_TRIANGLES,
                    overlay.fillColor,
                )
            } else {
                drawBuffer(
                    buffers.vertices,
                    customVertices.size / PREPARE_VERTEX_FLOATS,
                    GLES30.GL_TRIANGLES,
                    overlay.fillColor.red,
                    overlay.fillColor.green,
                    overlay.fillColor.blue,
                    overlay.fillColor.alpha,
                )
            }
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            bindVertexBuffer(
                if (customVertices == null) meshBuffers[overlay.meshIndex] else buffers.vertices,
            )
            drawElements(buffers.lines, overlay.lineIndices.size, GLES30.GL_LINES, overlay.lineColor)
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUniform1f(opacityLocation, 1f)
    }

    private fun drawElements(
        buffer: Int,
        indexCount: Int,
        mode: Int,
        color: PrepareOverlayColor,
    ) {
        GLES30.glUniform3f(baseColorLocation, color.red, color.green, color.blue)
        GLES30.glUniform1f(opacityLocation, color.alpha)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffer)
        GLES30.glDrawElements(mode, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    private fun applyObject(
        objectState: PrepareObjectDrawState,
        sourceCenter: FloatArray,
        geometry: PrepareModelSceneGeometry,
    ) {
        val transform = objectState.transform
        GLES30.glUniform3f(
            sourceCenterLocation,
            sourceCenter[0],
            sourceCenter[1],
            sourceCenter[2],
        )
        GLES30.glUniform3f(
            signedScaleLocation,
            transform.scale * if (transform.mirrorX) -1f else 1f,
            transform.scaleY * if (transform.mirrorY) -1f else 1f,
            transform.scaleZ * if (transform.mirrorZ) -1f else 1f,
        )
        GLES30.glUniformMatrix3fv(
            objectRotationLocation,
            1,
            false,
            objectState.rotationMatrix,
            0,
        )
        GLES30.glUniform3f(
            translationLocation,
            geometry.bedSizeX / 2f + transform.offsetXmm,
            geometry.bedSizeY / 2f + transform.offsetYmm,
            -objectState.minimumRotatedZ + transform.offsetZmm,
        )
    }

    private fun setObjectMode(enabled: Boolean) {
        GLES30.glUniform1i(objectModeLocation, if (enabled) 1 else 0)
        if (!enabled) {
            GLES30.glUniform3f(sourceCenterLocation, 0f, 0f, 0f)
            GLES30.glUniform3f(signedScaleLocation, 1f, 1f, 1f)
            GLES30.glUniformMatrix3fv(
                objectRotationLocation,
                1,
                false,
                PREPARE_IDENTITY_ROTATION_MATRIX,
                0,
            )
            GLES30.glUniform3f(translationLocation, 0f, 0f, 0f)
        }
    }

    private fun drawBuffer(
        id: Int,
        vertexCount: Int,
        mode: Int,
        red: Float,
        green: Float,
        blue: Float,
        opacity: Float = 1f,
    ) {
        if (id == 0 || vertexCount == 0) return
        GLES30.glUniform3f(baseColorLocation, red, green, blue)
        GLES30.glUniform1f(opacityLocation, opacity)
        bindVertexBuffer(id)
        GLES30.glDrawArrays(mode, 0, vertexCount)
    }

    private fun bindVertexBuffer(id: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        GLES30.glVertexAttribPointer(
            positionLocation,
            3,
            GLES30.GL_FLOAT,
            false,
            PREPARE_VERTEX_STRIDE_BYTES,
            0,
        )
        GLES30.glEnableVertexAttribArray(positionLocation)
    }

    private fun createProgramSafely(vertexSource: String, fragmentSource: String): Int = try {
        programFactory?.invoke(vertexSource, fragmentSource)
            ?: createProgram(vertexSource, fragmentSource)
    } catch (failure: RuntimeException) {
        Log.w(PREPARE_RENDERER_LOG_TAG, "Prepare model renderer creation failed", failure)
        0
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val created = GLES30.glCreateProgram()
        check(created != 0)
        try {
            GLES30.glAttachShader(created, vertex)
            GLES30.glAttachShader(created, fragment)
            GLES30.glLinkProgram(created)
            val status = IntArray(1)
            GLES30.glGetProgramiv(created, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(created) }
            return created
        } catch (failure: RuntimeException) {
            GLES30.glDeleteProgram(created)
            throw failure
        } finally {
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        check(shader != 0)
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

    private fun failRenderer(stage: String) {
        if (unavailable) return
        unavailable = true
        Log.w(PREPARE_RENDERER_LOG_TAG, "Prepare model renderer unavailable at $stage")
        releaseGpuGeometryForMemoryPressure()
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
        reportUnavailable()
    }
}

private fun FloatArray.toDirectFloatBuffer(): FloatBuffer = ByteBuffer
    .allocateDirect(size * Float.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asFloatBuffer()
    .apply {
        put(this@toDirectFloatBuffer)
        flip()
    }

private fun IntArray.toDirectIntBuffer(): IntBuffer = ByteBuffer
    .allocateDirect(size * Int.SIZE_BYTES)
    .order(ByteOrder.nativeOrder())
    .asIntBuffer()
    .apply {
        put(this@toDirectIntBuffer)
        flip()
    }

private data class PrepareOverlayGpuBuffers(
    val vertices: Int,
    val fill: Int,
    val lines: Int,
)

private const val PREPARE_VERTEX_FLOATS = 3
private const val PREPARE_VERTEX_STRIDE_BYTES = PREPARE_VERTEX_FLOATS * Float.SIZE_BYTES
private const val PREPARE_BED_FILL_Z = -0.08f
private const val PREPARE_BED_GRID_Z = -0.06f
private const val PREPARE_BED_OUTLINE_Z = -0.04f
private const val PREPARE_BED_EXCLUDE_Z = -0.02f
private const val PREPARE_RENDERER_STARTUP_TIMEOUT_MS = 5_000L
private const val PREPARE_RENDERER_RELEASE_TIMEOUT_MS = 1_000L
internal const val PREPARE_DETAIL_REFINEMENT_DELAY_MS = 240L
private const val PREPARE_BED_BUFFER_COUNT = 4
private const val PREPARE_RENDERER_LOG_TAG = "DuckyPrepareRenderer"
internal const val MAX_PREPARE_ADDITIONAL_DETAIL_GPU_BYTES = 16L * 1_024L * 1_024L
internal const val MAX_PREPARE_LOW_DETAIL_GPU_BYTES = 24L * 1_024L * 1_024L
internal const val PREPARE_COARSE_INTERACTION_MAX_ZOOM = 1.6f
private val PREPARE_IDENTITY_ROTATION_MATRIX = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f,
)

private const val PREPARE_VERTEX_SHADER = """#version 300 es
    uniform vec2 uViewport;
    uniform vec2 uSceneCenter;
    uniform vec2 uBedSize;
    uniform float uSceneScale;
    uniform vec4 uCameraRotation;
    uniform float uDepthScale;
    uniform int uObjectMode;
    uniform vec3 uSourceCenter;
    uniform vec3 uSignedScale;
    uniform mat3 uObjectRotation;
    uniform vec3 uTranslation;
    in vec3 aPosition;
    out vec3 vWorldPosition;

    void main() {
        vec3 world = aPosition;
        if (uObjectMode != 0) {
            world = uObjectRotation * ((aPosition - uSourceCenter) * uSignedScale) + uTranslation;
        }
        vec2 delta = world.xy - uBedSize * 0.5;
        float yawCos = uCameraRotation.x;
        float yawSin = uCameraRotation.y;
        float pitchSin = uCameraRotation.z;
        float pitchCos = uCameraRotation.w;
        float rotatedX = delta.x * yawCos - delta.y * yawSin;
        float rotatedY = delta.x * yawSin + delta.y * yawCos;
        vec2 screen = uSceneCenter + vec2(
            rotatedX,
            rotatedY * pitchSin - world.z * pitchCos
        ) * uSceneScale;
        float depth = rotatedY * pitchCos + world.z * pitchSin;
        vec2 ndc = vec2(screen.x / uViewport.x * 2.0 - 1.0, 1.0 - screen.y / uViewport.y * 2.0);
        gl_Position = vec4(ndc, clamp(-depth / uDepthScale, -0.98, 0.98), 1.0);
        vWorldPosition = world;
    }
"""

private const val PREPARE_FRAGMENT_SHADER = """#version 300 es
    precision highp float;
    uniform vec3 uBaseColor;
    uniform float uOpacity;
    uniform int uLighting;
    uniform int uSelected;
    in vec3 vWorldPosition;
    out vec4 outColor;
    void main() {
        float light = 1.0;
        if (uLighting != 0) {
            vec3 xGradient = dFdx(vWorldPosition);
            vec3 yGradient = dFdy(vWorldPosition);
            vec3 normal = normalize(cross(xGradient, yGradient));
            vec3 lightDirection = normalize(vec3(0.36, -0.48, 0.80));
            light = 0.55 + abs(dot(normal, lightDirection)) * 0.45;
        }
        if (uSelected != 0) light = min(1.0, light + 0.10);
        vec3 color = mix(vec3(0.067, 0.075, 0.059), uBaseColor, light);
        outColor = vec4(color, uOpacity);
    }
"""

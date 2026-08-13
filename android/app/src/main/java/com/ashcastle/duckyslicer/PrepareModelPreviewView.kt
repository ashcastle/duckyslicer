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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

@Composable
internal fun DepthTestedPrepareModelScene(
    projectObjects: List<ProjectObject>,
    placements: Map<String, PrepareObjectPlacement>,
    selectedObjectId: String?,
    bedSizeX: Float,
    bedSizeY: Float,
    bedPolygon: List<Float>,
    yawDegrees: Float,
    pitchDegrees: Float,
    zoom: Float,
    panX: Float,
    panY: Float,
    layOnFaceObjectId: String?,
    layOnFaceCandidateFacets: Map<String, BooleanArray>,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val topology = projectObjects.flatMap { projectObject ->
        projectObject.volumes.map { volume ->
            PrepareModelTopologyKey(
                objectId = projectObject.id,
                volumeId = volume.id,
                filamentSlot = volume.filamentSlot,
                model = volume.model,
            )
        }
    }
    var sceneLoad by remember(topology, bedSizeX, bedSizeY, bedPolygon) {
        mutableStateOf(
            PrepareModelSceneLoad(
                geometry = PrepareModelSceneBuilder.build(
                    emptyList(),
                    bedSizeX,
                    bedSizeY,
                    bedPolygon,
                ),
                complete = projectObjects.isEmpty(),
            ),
        )
    }
    LaunchedEffect(topology, bedSizeX, bedSizeY, bedPolygon) {
        if (projectObjects.isNotEmpty()) {
            val geometry = withContext(Dispatchers.Default) {
                PrepareModelSceneBuilder.build(projectObjects, bedSizeX, bedSizeY, bedPolygon)
            }
            sceneLoad = PrepareModelSceneLoad(geometry, complete = true)
        }
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
    val overlayTopology = projectObjects.flatMap { projectObject ->
        projectObject.volumes.map { volume ->
            PrepareModelOverlayKey(
                objectId = projectObject.id,
                volumeId = volume.id,
                model = volume.model,
                supportPaint = volume.supportPaint,
                seamPaint = volume.seamPaint,
                multiColorPaint = volume.multiColorPaint,
            )
        }
    }
    var overlays by remember(overlayTopology, layOnFaceObjectId, layOnFaceCandidateFacets) {
        mutableStateOf<List<PrepareModelOverlayData>>(emptyList())
    }
    LaunchedEffect(overlayTopology, layOnFaceObjectId, layOnFaceCandidateFacets) {
        val snapshot = projectObjects
        overlays = withContext(Dispatchers.Default) {
            PrepareModelOverlayBuilder.build(
                projectObjects = snapshot,
                layOnFaceObjectId = layOnFaceObjectId,
                layOnFaceCandidateFacets = layOnFaceCandidateFacets,
            )
        }
    }
    val currentOnUnavailable = rememberUpdatedState(onUnavailable)
    AndroidView(
        factory = { context ->
            PrepareModelSurfaceView(context) { currentOnUnavailable.value() }
        },
        update = { view ->
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
    val model: ModelInfo,
)

private data class PrepareModelOverlayKey(
    val objectId: String,
    val volumeId: String,
    val model: ModelInfo,
    val supportPaint: SupportPaint,
    val seamPaint: SeamPaint,
    val multiColorPaint: MultiColorPaint,
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
    val sourceCenter: FloatArray,
    val vertices: FloatArray,
) {
    val vertexCount: Int get() = vertices.size / PREPARE_VERTEX_FLOATS
}

internal data class PrepareModelSceneGeometry(
    val bedSizeX: Float,
    val bedSizeY: Float,
    val bedFill: FloatArray,
    val bedGrid: FloatArray,
    val bedOutline: FloatArray,
    val meshes: List<PrepareModelMeshData>,
)

internal object PrepareModelSceneBuilder {
    fun build(
        projectObjects: List<ProjectObject>,
        bedSizeX: Float,
        bedSizeY: Float,
        requestedBedPolygon: List<Float>,
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
                0f,
                0f,
                1f,
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
                0f,
                0f,
                1f,
            )
        }
        val meshes = projectObjects.flatMap { projectObject ->
            val center = projectObject.geometry().center
            projectObject.volumes.map { volume ->
                PrepareModelMeshData(
                    objectId = projectObject.id,
                    volumeId = volume.id,
                    filamentSlot = volume.filamentSlot,
                    sourceCenter = center,
                    vertices = buildMeshVertices(volume.model.previewTriangles),
                )
            }
        }
        return PrepareModelSceneGeometry(
            bedSizeX = bedSizeX,
            bedSizeY = bedSizeY,
            bedFill = bedFill.toFloatArray(),
            bedGrid = bedGrid.toFloatArray(),
            bedOutline = bedOutline.toFloatArray(),
            meshes = meshes,
        )
    }

    private fun buildMeshVertices(triangles: FloatArray): FloatArray {
        require(triangles.size % 9 == 0 && triangles.all(Float::isFinite))
        val result = FloatArray(triangles.size / 3 * PREPARE_VERTEX_FLOATS)
        var source = 0
        var target = 0
        while (source + 8 < triangles.size) {
            val ax = triangles[source]
            val ay = triangles[source + 1]
            val az = triangles[source + 2]
            val bx = triangles[source + 3]
            val by = triangles[source + 4]
            val bz = triangles[source + 5]
            val cx = triangles[source + 6]
            val cy = triangles[source + 7]
            val cz = triangles[source + 8]
            val ux = bx - ax
            val uy = by - ay
            val uz = bz - az
            val vx = cx - ax
            val vy = cy - ay
            val vz = cz - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length.isFinite() && length > 0.000001f) {
                nx /= length
                ny /= length
                nz /= length
            } else {
                nx = 0f
                ny = 0f
                nz = 1f
            }
            result[target++] = ax
            result[target++] = ay
            result[target++] = az
            result[target++] = nx
            result[target++] = ny
            result[target++] = nz
            result[target++] = bx
            result[target++] = by
            result[target++] = bz
            result[target++] = nx
            result[target++] = ny
            result[target++] = nz
            result[target++] = cx
            result[target++] = cy
            result[target++] = cz
            result[target++] = nx
            result[target++] = ny
            result[target++] = nz
            source += 9
        }
        return result
    }

    private fun addLine(
        destination: MutableList<Float>,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        z: Float,
    ) {
        addVertex(destination, x1, y1, z, 0f, 0f, 1f)
        addVertex(destination, x2, y2, z, 0f, 0f, 1f)
    }

    private fun addVertex(
        destination: MutableList<Float>,
        x: Float,
        y: Float,
        z: Float,
        nx: Float,
        ny: Float,
        nz: Float,
    ) {
        destination += x
        destination += y
        destination += z
        destination += nx
        destination += ny
        destination += nz
    }
}

private class PrepareModelSurfaceView(
    context: Context,
    private val onUnavailable: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {
    private val applicationContext = context.applicationContext
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    private val renderPending = AtomicBoolean(false)
    private var unavailableReported = false
    private var rendererReady = false
    private var sceneSubmitted = false
    @Volatile
    private var textureAvailable = false
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
        override fun onLowMemory() = releaseGpuMemory()

        override fun onTrimMemory(level: Int) {
            if (shouldReleaseToolpathGpuMemory(level)) releaseGpuMemory()
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
        overlays: List<PrepareModelOverlayData>,
    ) {
        sceneSubmitted = true
        renderer.submit(geometry, objects, selectedObjectId, camera, overlays)
        removeCallbacks(startupWatchdog)
        if (!rendererReady) postDelayed(startupWatchdog, PREPARE_RENDERER_STARTUP_TIMEOUT_MS)
        requestTextureRender()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureRenderThread()
        if (!memoryCallbacksRegistered) {
            applicationContext.registerComponentCallbacks(memoryCallbacks)
            memoryCallbacksRegistered = true
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(startupWatchdog)
        textureAvailable = false
        rendererReady = false
        renderPending.set(false)
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
        renderHandler?.post {
            if (!initializeEgl(surface, width, height)) return@post
            requestTextureRender()
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderHandler?.post {
            if (makeCurrent()) {
                renderer.onSurfaceChanged(null, width, height)
                drawNow()
            }
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        textureAvailable = false
        rendererReady = false
        renderPending.set(false)
        val releasePosted = renderHandler?.post {
            releaseEgl()
            surface.release()
        } == true
        if (!releasePosted) surface.release()
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun releaseGpuMemory() {
        renderHandler?.post {
            if (makeCurrent()) renderer.releaseGpuGeometryForMemoryPressure()
        }
    }

    private fun requestTextureRender() {
        if (!textureAvailable || !renderPending.compareAndSet(false, true)) return
        renderHandler?.post {
            renderPending.set(false)
            drawNow()
        }
    }

    private fun drawNow() {
        if (!textureAvailable || !makeCurrent()) return
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
)

internal data class PrepareModelFrame(
    val geometry: PrepareModelSceneGeometry,
    val objects: Map<String, PrepareObjectDrawState>,
    val selectedObjectId: String?,
    val camera: PrepareModelCamera,
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
    private val meshBuffers = ArrayList<Int>()
    private val overlayBuffers = ArrayList<PrepareOverlayGpuBuffers>()
    private var uploadedOverlays: List<PrepareModelOverlayData>? = null
    private var program = 0
    private var positionLocation = -1
    private var normalLocation = -1
    private var viewportLocation = -1
    private var sceneCenterLocation = -1
    private var bedSizeLocation = -1
    private var sceneScaleLocation = -1
    private var yawLocation = -1
    private var pitchLocation = -1
    private var depthScaleLocation = -1
    private var objectModeLocation = -1
    private var sourceCenterLocation = -1
    private var signedScaleLocation = -1
    private var rotationLocation = -1
    private var translationLocation = -1
    private var baseColorLocation = -1
    private var opacityLocation = -1
    private var lightingLocation = -1
    private var selectedLocation = -1
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var unavailable = false
    private var frameReadyReported = false
    private var geometryUploadCount = 0

    internal fun geometryUploadCountForTest(): Int = geometryUploadCount

    fun submit(
        geometry: PrepareModelSceneGeometry,
        objects: Map<String, PrepareObjectDrawState>,
        selectedObjectId: String?,
        camera: PrepareModelCamera,
        overlays: List<PrepareModelOverlayData> = emptyList(),
    ) {
        latestFrame = PrepareModelFrame(
            geometry = geometry,
            objects = objects,
            selectedObjectId = selectedObjectId,
            camera = camera,
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
        normalLocation = GLES30.glGetAttribLocation(program, "aNormal")
        viewportLocation = GLES30.glGetUniformLocation(program, "uViewport")
        sceneCenterLocation = GLES30.glGetUniformLocation(program, "uSceneCenter")
        bedSizeLocation = GLES30.glGetUniformLocation(program, "uBedSize")
        sceneScaleLocation = GLES30.glGetUniformLocation(program, "uSceneScale")
        yawLocation = GLES30.glGetUniformLocation(program, "uYaw")
        pitchLocation = GLES30.glGetUniformLocation(program, "uPitch")
        depthScaleLocation = GLES30.glGetUniformLocation(program, "uDepthScale")
        objectModeLocation = GLES30.glGetUniformLocation(program, "uObjectMode")
        sourceCenterLocation = GLES30.glGetUniformLocation(program, "uSourceCenter")
        signedScaleLocation = GLES30.glGetUniformLocation(program, "uSignedScale")
        rotationLocation = GLES30.glGetUniformLocation(program, "uRotation")
        translationLocation = GLES30.glGetUniformLocation(program, "uTranslation")
        baseColorLocation = GLES30.glGetUniformLocation(program, "uBaseColor")
        opacityLocation = GLES30.glGetUniformLocation(program, "uOpacity")
        lightingLocation = GLES30.glGetUniformLocation(program, "uLighting")
        selectedLocation = GLES30.glGetUniformLocation(program, "uSelected")
        if (
            intArrayOf(
                positionLocation,
                normalLocation,
                viewportLocation,
                sceneCenterLocation,
                bedSizeLocation,
                sceneScaleLocation,
                yawLocation,
                pitchLocation,
                depthScaleLocation,
                objectModeLocation,
                sourceCenterLocation,
                signedScaleLocation,
                rotationLocation,
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
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(unused: GL10?) {
        if (unavailable) return
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val frame = latestFrame ?: return
        if (uploadedGeometry !== frame.geometry && !upload(frame.geometry)) return
        if (uploadedOverlays !== frame.overlays && !uploadOverlays(frame)) return
        GLES30.glUseProgram(program)
        applyCamera(frame)
        drawBed(frame.geometry)
        drawMeshes(frame)
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
            addAll(meshBuffers)
            overlayBuffers.forEach { buffers ->
                if (buffers.fill != 0) add(buffers.fill)
                if (buffers.lines != 0) add(buffers.lines)
            }
        }.toIntArray()
        if (ids.isNotEmpty()) GLES30.glDeleteBuffers(ids.size, ids, 0)
        bedFillBuffer = 0
        bedGridBuffer = 0
        bedOutlineBuffer = 0
        meshBuffers.clear()
        overlayBuffers.clear()
        uploadedGeometry = null
        uploadedOverlays = null
    }

    private fun upload(geometry: PrepareModelSceneGeometry): Boolean {
        releaseGpuGeometryForMemoryPressure()
        val bufferCount = 3 + geometry.meshes.size
        val buffers = IntArray(bufferCount)
        GLES30.glGenBuffers(bufferCount, buffers, 0)
        if (buffers.any { it == 0 }) {
            GLES30.glDeleteBuffers(bufferCount, buffers, 0)
            failRenderer("buffer_allocation")
            return false
        }
        bedFillBuffer = buffers[0]
        bedGridBuffer = buffers[1]
        bedOutlineBuffer = buffers[2]
        meshBuffers += buffers.drop(3)
        uploadBuffer(bedFillBuffer, geometry.bedFill)
        uploadBuffer(bedGridBuffer, geometry.bedGrid)
        uploadBuffer(bedOutlineBuffer, geometry.bedOutline)
        geometry.meshes.forEachIndexed { index, mesh ->
            uploadBuffer(meshBuffers[index], mesh.vertices)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            releaseGpuGeometryForMemoryPressure()
            failRenderer("buffer_upload")
            return false
        }
        uploadedGeometry = geometry
        geometryUploadCount += 1
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
        val existing = overlayBuffers.flatMap { buffers -> listOf(buffers.fill, buffers.lines) }
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
            mesh != null && overlay.fillIndices.isNotEmpty() && overlay.lineIndices.isNotEmpty() &&
                overlay.fillIndices.all { it in 0 until mesh.vertexCount } &&
                overlay.lineIndices.all { it in 0 until mesh.vertexCount }
        }
        if (!valid) {
            failRenderer("overlay_indices")
            return false
        }
        val ids = IntArray(frame.overlays.size * 2)
        GLES30.glGenBuffers(ids.size, ids, 0)
        if (ids.any { it == 0 }) {
            GLES30.glDeleteBuffers(ids.size, ids, 0)
            failRenderer("overlay_allocation")
            return false
        }
        frame.overlays.forEachIndexed { index, overlay ->
            val buffers = PrepareOverlayGpuBuffers(ids[index * 2], ids[index * 2 + 1])
            overlayBuffers += buffers
            uploadIndexBuffer(buffers.fill, overlay.fillIndices)
            uploadIndexBuffer(buffers.lines, overlay.lineIndices)
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            val generated = overlayBuffers.flatMap { listOf(it.fill, it.lines) }.toIntArray()
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
        val sceneScale = minOf(viewportWidth * 0.64f, viewportHeight * 0.72f) /
            max(frame.geometry.bedSizeX, frame.geometry.bedSizeY) * camera.zoom
        GLES30.glUniform2f(viewportLocation, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform2f(
            sceneCenterLocation,
            viewportWidth / 2f + camera.panX,
            viewportHeight * 0.48f + camera.panY,
        )
        GLES30.glUniform2f(bedSizeLocation, frame.geometry.bedSizeX, frame.geometry.bedSizeY)
        GLES30.glUniform1f(sceneScaleLocation, sceneScale)
        GLES30.glUniform1f(yawLocation, Math.toRadians(camera.yawDegrees.toDouble()).toFloat())
        GLES30.glUniform1f(pitchLocation, Math.toRadians(camera.pitchDegrees.toDouble()).toFloat())
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
            floatArrayOf(0.204f, 0.216f, 0.196f),
        )
        drawBuffer(
            bedGridBuffer,
            geometry.bedGrid.size / PREPARE_VERTEX_FLOATS,
            GLES30.GL_LINES,
            floatArrayOf(0.333f, 0.349f, 0.314f),
        )
        drawBuffer(
            bedOutlineBuffer,
            geometry.bedOutline.size / PREPARE_VERTEX_FLOATS,
            GLES30.GL_LINE_LOOP,
            floatArrayOf(0.965f, 0.788f, 0.271f),
        )
    }

    private fun drawMeshes(frame: PrepareModelFrame) {
        GLES30.glUniform1i(lightingLocation, 1)
        setObjectMode(true)
        frame.geometry.meshes.forEachIndexed { index, mesh ->
            val objectState = frame.objects[mesh.objectId] ?: return@forEachIndexed
            applyObject(objectState, mesh.sourceCenter, frame.geometry)
            val color = filamentSlotColor(mesh.filamentSlot)
            GLES30.glUniform1i(
                selectedLocation,
                if (mesh.objectId == frame.selectedObjectId) 1 else 0,
            )
            drawBuffer(
                meshBuffers[index],
                mesh.vertexCount,
                GLES30.GL_TRIANGLES,
                floatArrayOf(color.red, color.green, color.blue),
            )
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
            bindVertexBuffer(meshBuffers[overlay.meshIndex])
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(-1f, -1f)
            drawElements(buffers.fill, overlay.fillIndices.size, GLES30.GL_TRIANGLES, overlay.fillColor)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
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
        GLES30.glUniform3f(
            rotationLocation,
            Math.toRadians(transform.rotationXdeg.toDouble()).toFloat(),
            Math.toRadians(transform.rotationYdeg.toDouble()).toFloat(),
            Math.toRadians(transform.rotationZdeg.toDouble()).toFloat(),
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
            GLES30.glUniform3f(rotationLocation, 0f, 0f, 0f)
            GLES30.glUniform3f(translationLocation, 0f, 0f, 0f)
        }
    }

    private fun drawBuffer(id: Int, vertexCount: Int, mode: Int, color: FloatArray) {
        if (id == 0 || vertexCount == 0) return
        GLES30.glUniform3f(baseColorLocation, color[0], color[1], color[2])
        GLES30.glUniform1f(opacityLocation, 1f)
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
        GLES30.glVertexAttribPointer(
            normalLocation,
            3,
            GLES30.GL_FLOAT,
            false,
            PREPARE_VERTEX_STRIDE_BYTES,
            3 * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(normalLocation)
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
    val fill: Int,
    val lines: Int,
)

private const val PREPARE_VERTEX_FLOATS = 6
private const val PREPARE_VERTEX_STRIDE_BYTES = PREPARE_VERTEX_FLOATS * Float.SIZE_BYTES
private const val PREPARE_BED_FILL_Z = -0.08f
private const val PREPARE_BED_GRID_Z = -0.06f
private const val PREPARE_BED_OUTLINE_Z = -0.04f
private const val PREPARE_RENDERER_STARTUP_TIMEOUT_MS = 5_000L
private const val PREPARE_RENDERER_RELEASE_TIMEOUT_MS = 1_000L
private const val PREPARE_RENDERER_LOG_TAG = "DuckyPrepareRenderer"

private const val PREPARE_VERTEX_SHADER = """#version 300 es
    uniform vec2 uViewport;
    uniform vec2 uSceneCenter;
    uniform vec2 uBedSize;
    uniform float uSceneScale;
    uniform float uYaw;
    uniform float uPitch;
    uniform float uDepthScale;
    uniform int uObjectMode;
    uniform vec3 uSourceCenter;
    uniform vec3 uSignedScale;
    uniform vec3 uRotation;
    uniform vec3 uTranslation;
    in vec3 aPosition;
    in vec3 aNormal;
    out float vDiffuse;

    vec3 rotatePoint(vec3 point) {
        float sx = sin(uRotation.x);
        float cx = cos(uRotation.x);
        float sy = sin(uRotation.y);
        float cy = cos(uRotation.y);
        float sz = sin(uRotation.z);
        float cz = cos(uRotation.z);
        vec3 afterX = vec3(point.x, point.y * cx - point.z * sx, point.y * sx + point.z * cx);
        vec3 afterY = vec3(
            afterX.x * cy + afterX.z * sy,
            afterX.y,
            -afterX.x * sy + afterX.z * cy
        );
        return vec3(afterY.x * cz - afterY.y * sz, afterY.x * sz + afterY.y * cz, afterY.z);
    }

    void main() {
        vec3 world = aPosition;
        vec3 normal = aNormal;
        if (uObjectMode != 0) {
            world = rotatePoint((aPosition - uSourceCenter) * uSignedScale) + uTranslation;
            vec3 safeScale = sign(uSignedScale) * max(abs(uSignedScale), vec3(0.000001));
            normal = normalize(rotatePoint(aNormal / safeScale));
        }
        vec2 delta = world.xy - uBedSize * 0.5;
        float yawCos = cos(uYaw);
        float yawSin = sin(uYaw);
        float pitchSin = sin(uPitch);
        float pitchCos = cos(uPitch);
        float rotatedX = delta.x * yawCos - delta.y * yawSin;
        float rotatedY = delta.x * yawSin + delta.y * yawCos;
        vec2 screen = uSceneCenter + vec2(
            rotatedX,
            rotatedY * pitchSin - world.z * pitchCos
        ) * uSceneScale;
        float depth = rotatedY * pitchCos + world.z * pitchSin;
        vec2 ndc = vec2(screen.x / uViewport.x * 2.0 - 1.0, 1.0 - screen.y / uViewport.y * 2.0);
        gl_Position = vec4(ndc, clamp(-depth / uDepthScale, -0.98, 0.98), 1.0);
        vec3 lightDirection = normalize(vec3(0.36, -0.48, 0.80));
        vDiffuse = 0.55 + abs(dot(normalize(normal), lightDirection)) * 0.45;
    }
"""

private const val PREPARE_FRAGMENT_SHADER = """#version 300 es
    precision mediump float;
    uniform vec3 uBaseColor;
    uniform float uOpacity;
    uniform int uLighting;
    uniform int uSelected;
    in float vDiffuse;
    out vec4 outColor;
    void main() {
        float light = uLighting != 0 ? vDiffuse : 1.0;
        if (uSelected != 0) light = min(1.0, light + 0.10);
        vec3 color = mix(vec3(0.067, 0.075, 0.059), uBaseColor, light);
        outColor = vec4(color, uOpacity);
    }
"""

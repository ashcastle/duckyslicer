package com.ashcastle.duckyslicer

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.View
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

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
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> ToolpathSurfaceView(context) },
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

private class ToolpathSurfaceView(context: Context) : GLSurfaceView(context) {
    private val applicationContext = context.applicationContext
    private val toolpathRenderer = ToolpathRenderer {
        post { requestRender() }
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
        toolpathRenderer.submit(
            ToolpathScene(
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
            ),
        )
        requestRender()
    }

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
        removeCallbacks(restoreDetail)
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
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) requestRender()
    }

    private fun releaseGpuMemory() {
        queueEvent { toolpathRenderer.releaseGpuGeometryForMemoryPressure() }
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

    private companion object {
        const val DETAIL_RESTORE_DELAY_MS = 220L
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
)

internal class ToolpathRenderer(
    private val requestPrewarmFrame: () -> Unit = {},
) : GLSurfaceView.Renderer {
    @Volatile
    private var latestScene: ToolpathScene? = null
    private val uploadState = ToolpathGeometryUploadState(capacity = GPU_GEOMETRY_CACHE_SIZE)
    private val gpuGeometry = HashMap<ToolpathScene, ToolpathGpuGeometry>()
    private var pendingPrewarmScene: ToolpathScene? = null
    private var program = 0
    private var positionLocation = 0
    private var colorLocation = 0
    private var acrossLocation = 0
    private var matrixLocation = 0
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var yawDegrees = -45f
    private var elevationDegrees = 52f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var geometryUploadCount = 0
    @Volatile
    private var interactionActive = false

    internal fun geometryUploadCountForTest(): Int = geometryUploadCount

    internal fun cachedGeometryCountForTest(): Int = gpuGeometry.size

    internal fun releaseGpuGeometryForMemoryPressure() {
        gpuGeometry.values.forEach { geometry ->
            GLES30.glDeleteBuffers(1, intArrayOf(geometry.bufferId), 0)
        }
        gpuGeometry.clear()
        uploadState.invalidate()
        pendingPrewarmScene = null
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
        program = 0
        geometryUploadCount = 0
        pendingPrewarmScene = null
        gpuGeometry.clear()
        uploadState.invalidate()
        GLES30.glClearColor(0.098f, 0.102f, 0.094f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        program = runCatching { createProgram(VERTEX_SHADER, FRAGMENT_SHADER) }.getOrDefault(0)
        if (program == 0) return
        positionLocation = GLES30.glGetAttribLocation(program, "aPosition")
        colorLocation = GLES30.glGetAttribLocation(program, "aColor")
        acrossLocation = GLES30.glGetAttribLocation(program, "aAcross")
        matrixLocation = GLES30.glGetUniformLocation(program, "uMvp")
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return
        val sourceScene = latestScene ?: return
        val prewarmAtFrameStart = pendingPrewarmScene
        pendingPrewarmScene = null
        val prewarmDetail = previewDetailForInteraction(sourceScene.detail, interactionActive = true)
        val interactionScene = if (prewarmDetail == sourceScene.detail) {
            sourceScene
        } else {
            sourceScene.copy(detail = prewarmDetail)
        }
        releaseStaleGeometry(setOf(sourceScene, interactionScene))
        val renderDetail = previewDetailForInteraction(sourceScene.detail, interactionActive)
        val scene = if (renderDetail == sourceScene.detail) {
            sourceScene
        } else {
            sourceScene.copy(detail = renderDetail)
        }
        val geometry = geometryFor(scene) ?: return
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(matrixLocation, 1, false, cameraMatrix(scene), 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, geometry.bufferId)
        GLES30.glVertexAttribPointer(
            positionLocation,
            3,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            POSITION_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(positionLocation)
        GLES30.glVertexAttribPointer(
            colorLocation,
            4,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            COLOR_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(colorLocation)
        GLES30.glVertexAttribPointer(
            acrossLocation,
            1,
            GLES30.GL_FLOAT,
            false,
            STRIDE_BYTES,
            ACROSS_OFFSET_BYTES,
        )
        GLES30.glEnableVertexAttribArray(acrossLocation)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, geometry.vertexCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        if (!interactionActive && interactionScene != sourceScene) {
            if (uploadState.needsUpload(interactionScene)) {
                if (prewarmAtFrameStart == interactionScene) {
                    uploadGeometry(interactionScene)
                } else {
                    pendingPrewarmScene = interactionScene
                    requestPrewarmFrame()
                }
            }
        }
    }

    private fun releaseStaleGeometry(retainedScenes: Set<ToolpathScene>) {
        gpuGeometry.keys.filterNot { it in retainedScenes }.forEach { staleScene ->
            gpuGeometry.remove(staleScene)?.let { stale ->
                GLES30.glDeleteBuffers(1, intArrayOf(stale.bufferId), 0)
            }
            uploadState.remove(staleScene)
        }
    }

    private fun geometryFor(scene: ToolpathScene): ToolpathGpuGeometry? {
        if (!uploadState.needsUpload(scene)) {
            uploadState.markUsed(scene)
            return gpuGeometry[scene]
        }
        return uploadGeometry(scene)
    }

    private fun uploadGeometry(scene: ToolpathScene): ToolpathGpuGeometry? {
        val buffer = ToolpathMeshBuilder.build(scene)
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        val bufferId = buffers[0]
        if (bufferId == 0) return null
        val geometry = ToolpathGpuGeometry(
            bufferId = bufferId,
            vertexCount = buffer.remaining() / FLOATS_PER_VERTEX,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            buffer.remaining() * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        uploadState.markUploaded(scene)?.let { evictedScene ->
            gpuGeometry.remove(evictedScene)?.let { evicted ->
                GLES30.glDeleteBuffers(1, intArrayOf(evicted.bufferId), 0)
            }
        }
        gpuGeometry[scene] = geometry
        geometryUploadCount += 1
        return geometry
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
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES30.glCreateProgram().also { created ->
            GLES30.glAttachShader(created, vertex)
            GLES30.glAttachShader(created, fragment)
            GLES30.glLinkProgram(created)
            val status = IntArray(1)
            GLES30.glGetProgramiv(created, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) { GLES30.glGetProgramInfoLog(created) }
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int = GLES30.glCreateShader(type).also { shader ->
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { GLES30.glGetShaderInfoLog(shader) }
    }

    private companion object {
        const val GPU_GEOMETRY_CACHE_SIZE = 2
        const val FLOATS_PER_VERTEX = 8
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4
        const val POSITION_OFFSET_BYTES = 0
        const val COLOR_OFFSET_BYTES = 3 * 4
        const val ACROSS_OFFSET_BYTES = 7 * 4
        const val VERTEX_SHADER = """#version 300 es
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

private data class ToolpathGpuGeometry(
    val bufferId: Int,
    val vertexCount: Int,
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

    fun build(scene: ToolpathScene): FloatBuffer {
        val budget = depthPreviewSegmentBudget(scene.detail)
        val plan = scene.preview.buildRenderPlan(budget, scene.visibleRoles)
        val builder = FloatBuilder(plan.segmentOffsets.size * 6 * 8 + 2_400)
        addBed(builder, scene.bedSizeX, scene.bedSizeY, scene.bedPolygon)
        val zSpan = (scene.preview.maxZMm - scene.preview.minZMm).coerceAtLeast(0.001f)
        plan.segmentOffsets.forEach { offset ->
            val x1 = scene.preview.segments[offset] - scene.bedOriginX
            val y1 = scene.preview.segments[offset + 1] - scene.bedOriginY
            val x2 = scene.preview.segments[offset + 2] - scene.bedOriginX
            val y2 = scene.preview.segments[offset + 3] - scene.bedOriginY
            val z = scene.preview.segments[offset + 4]
            val role = scene.preview.segments[offset + 5].toInt().coerceIn(0, roleColors.lastIndex)
            if (role !in scene.visibleRoles) return@forEach
            val dx = x2 - x1
            val dy = y2 - y1
            val length = hypot(dx, dy)
            if (length < 0.001f) return@forEach
            val nx = -dy / length
            val ny = dx / length
            val tx = dx / length
            val ty = dy / length
            val normalizedHeight = ((z - scene.preview.minZMm) / zSpan).coerceIn(0f, 1f)
            val base = roleColors[role]
            val shade = scene.depthContrast * (1f - normalizedHeight) * 0.56f
            val color = floatArrayOf(
                base[0] * (1f - shade),
                base[1] * (1f - shade),
                base[2] * (1f - shade),
            )
            val halfWidth = roleWidths[role] / 2f
            addRibbon(
                builder,
                x1 - tx * halfWidth,
                y1 - ty * halfWidth,
                x2 + tx * halfWidth,
                y2 + ty * halfWidth,
                z + 0.024f,
                nx,
                ny,
                halfWidth,
                color,
                scene.opacity,
                outlined = true,
            )
        }
        return builder.finish()
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

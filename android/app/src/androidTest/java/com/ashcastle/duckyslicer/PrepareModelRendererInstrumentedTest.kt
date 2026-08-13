package com.ashcastle.duckyslicer

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepareModelRendererInstrumentedTest {
    @Test
    fun denseLayOnFaceCandidatesBuildOffTheUiThreadPromptly() {
        val triangles = spatiallyScrambleTriangles(denseGridTriangles(columns = 100, rows = 60))
        val started = SystemClock.elapsedRealtimeNanos()
        val candidates = detectLayOnFaceCandidates(triangles)
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        println(
            "DuckyPrepare layOnFace triangles=${triangles.size / 9} " +
                "candidates=${candidates.size} elapsedMs=$elapsedMs",
        )
        assertTrue(
            "Face candidates must be produced within one second: $elapsedMs ms",
            elapsedMs <= 1_000.0,
        )
    }

    @Test
    fun denseDefaultPlacementStaysWithinLoadBudget() {
        val triangles = denseGridTriangles(columns = 100, rows = 60)
        val model = ModelInfo(
            fileName = "dense-default-placement.stl",
            triangles = triangles.size / 9,
            dimensions = listOf(200.0, 180.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(200.0, 180.0, 20.0),
            previewTriangles = triangles,
        )
        val projectObject = ProjectObject(id = "dense", model = model)
        val durations = ArrayList<Long>()
        repeat(14) {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(projectObject.transform.minimumRotatedZ(projectObject) in -10.1f..-9.9f)
            durations += SystemClock.elapsedRealtimeNanos() - started
        }
        val sorted = durations.drop(4).sorted()
        val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
        val p95Ms = sorted.last() / 1_000_000.0
        println(
            "DuckyPrepare defaultPlacement vertices=${triangles.size / 3} " +
                "p50Ms=$p50Ms p95Ms=$p95Ms",
        )
        assertTrue(
            "Default placement must leave headroom in a frame: p95=$p95Ms ms",
            p95Ms <= 1.0,
        )
    }

    @Test
    fun denseUnpaintedOverlayBuildStaysWithinLoadBudget() {
        val triangles = denseGridTriangles(columns = 100, rows = 60)
        val model = ModelInfo(
            fileName = "dense-unpainted-overlay.stl",
            triangles = triangles.size / 9,
            dimensions = listOf(200.0, 180.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(200.0, 180.0, 20.0),
            previewTriangles = triangles,
        )
        val projectObject = ProjectObject(id = "dense", model = model)
        val durations = ArrayList<Long>()
        repeat(14) {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(
                PrepareModelOverlayBuilder.build(
                    projectObjects = listOf(projectObject),
                    layOnFaceObjectId = null,
                    layOnFaceCandidateFacets = emptyMap(),
                ).isEmpty(),
            )
            durations += SystemClock.elapsedRealtimeNanos() - started
        }
        val sorted = durations.drop(4).sorted()
        val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
        val p95Ms = sorted.last() / 1_000_000.0
        println(
            "DuckyPrepare unpaintedOverlay triangles=${triangles.size / 9} " +
                "p50Ms=$p50Ms p95Ms=$p95Ms",
        )
        assertTrue(
            "Unpainted overlay detection must leave headroom in a frame: p95=$p95Ms ms",
            p95Ms <= 1.0,
        )
    }

    @Test
    fun denseMinimumRotatedZStaysWithinTransformBudget() {
        val triangles = denseGridTriangles(columns = 100, rows = 60)
        val model = ModelInfo(
            fileName = "dense-transform.stl",
            triangles = triangles.size / 9,
            dimensions = listOf(200.0, 180.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(200.0, 180.0, 20.0),
            previewTriangles = triangles,
        )
        val projectObject = ProjectObject(
            id = "dense",
            model = model,
            transform = ModelTransform(
                rotationXdeg = 37f,
                rotationYdeg = -23f,
                rotationZdeg = 81f,
                scale = 1.3f,
                scaleY = 0.8f,
                scaleZ = 1.6f,
                mirrorX = true,
            ),
        )
        val durations = ArrayList<Long>()
        repeat(14) {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(projectObject.transform.minimumRotatedZ(projectObject).isFinite())
            durations += SystemClock.elapsedRealtimeNanos() - started
        }
        val sorted = durations.drop(4).sorted()
        val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
        val p95Ms = sorted.last() / 1_000_000.0
        println(
            "DuckyPrepare minimumZ vertices=${triangles.size / 3} " +
                "p50Ms=$p50Ms p95Ms=$p95Ms",
        )
        assertTrue(
            "36k-vertex bed placement must stay inside one frame: p95=$p95Ms ms",
            p95Ms <= 16.0,
        )
    }

    @Test
    fun densePrepareSceneBuildStaysWithinLoadBudget() {
        val triangles = denseGridTriangles(columns = 100, rows = 60)
        val model = ModelInfo(
            fileName = "dense-build.stl",
            triangles = triangles.size / 9,
            dimensions = listOf(200.0, 180.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(200.0, 180.0, 20.0),
            previewTriangles = triangles,
        )
        val projectObject = ProjectObject(id = "dense", model = model)
        val durations = ArrayList<Long>()
        repeat(12) {
            val started = SystemClock.elapsedRealtimeNanos()
            val geometry = PrepareModelSceneBuilder.build(
                listOf(projectObject),
                220f,
                220f,
                rectangularBedPolygon(220f, 220f),
            )
            durations += SystemClock.elapsedRealtimeNanos() - started
            assertEquals(12_000 * 3, geometry.meshes.single().vertexCount)
        }
        val sorted = durations.drop(2).sorted()
        val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
        val p95Ms = sorted.last() / 1_000_000.0
        println(
            "DuckyPrepare build triangles=${triangles.size / 9} " +
                "p50Ms=$p50Ms p95Ms=$p95Ms",
        )
        assertTrue(
            "12k-triangle scene construction must not stall model loading: p95=$p95Ms ms",
            p95Ms <= 50.0,
        )
    }

    @Test
    fun densePreparePickingStaysWithinTapBudget() {
        // STL facet order is arbitrary. Deliberately interleave distant grid cells so a
        // contiguous-chunk index cannot appear fast merely because this fixture is row-major.
        val triangles = spatiallyScrambleTriangles(denseGridTriangles(columns = 100, rows = 60))
        val model = ModelInfo(
            fileName = "dense-picking.stl",
            triangles = triangles.size / 9,
            dimensions = listOf(200.0, 180.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(200.0, 180.0, 20.0),
            previewTriangles = triangles,
        )
        val projectObject = ProjectObject(id = "dense", model = model)
        val placement = PrepareObjectPlacement(
            geometry = projectObject.geometry(),
            minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
        )
        val indexStarted = SystemClock.elapsedRealtimeNanos()
        val pickingIndices = buildPreparePickingIndices(listOf(projectObject))
        val indexBuildMs = (SystemClock.elapsedRealtimeNanos() - indexStarted) / 1_000_000.0
        val viewport = PrepareHitTestViewport(
            widthPx = 720f,
            heightPx = 1_280f,
            bedSizeX = 220f,
            bedSizeY = 220f,
            yawDegrees = 0f,
            pitchDegrees = 90f,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
        )
        val objectDurations = ArrayList<Long>()
        val facetDurations = ArrayList<Long>()
        repeat(12) {
            val started = SystemClock.elapsedRealtimeNanos()
            assertEquals(
                "dense",
                findPrepareObjectAtScreen(
                    projectObjects = listOf(projectObject),
                    placements = mapOf(projectObject.id to placement),
                    viewport = viewport,
                    screenX = 360f,
                    screenY = 614.4f,
                    touchRadiusPx = 14f,
                    pickingIndices = pickingIndices,
                ),
            )
            objectDurations += SystemClock.elapsedRealtimeNanos() - started
            val facetStarted = SystemClock.elapsedRealtimeNanos()
            assertTrue(
                findPrepareFacetAtScreen(
                    projectObject = projectObject,
                    placement = placement,
                    viewport = viewport,
                    screenX = 360f,
                    screenY = 614.4f,
                    touchRadiusPx = 14f,
                    pickingIndices = pickingIndices,
                ) != null,
            )
            facetDurations += SystemClock.elapsedRealtimeNanos() - facetStarted
        }
        val sortedObjects = objectDurations.drop(2).sorted()
        val sortedFacets = facetDurations.drop(2).sorted()
        val objectP50Ms = sortedObjects[sortedObjects.size / 2] / 1_000_000.0
        val objectP95Ms = sortedObjects.last() / 1_000_000.0
        val facetP50Ms = sortedFacets[sortedFacets.size / 2] / 1_000_000.0
        val facetP95Ms = sortedFacets.last() / 1_000_000.0
        println(
            "DuckyPrepare picking triangles=${triangles.size / 9} " +
                "indexBuildMs=$indexBuildMs " +
                "objectP50Ms=$objectP50Ms objectP95Ms=$objectP95Ms " +
                "facetP50Ms=$facetP50Ms facetP95Ms=$facetP95Ms",
        )
        assertTrue(
            "12k-triangle spatial index and support set must finish promptly: $indexBuildMs ms",
            indexBuildMs <= 500.0,
        )
        assertTrue(
            "12k-triangle object selection must stay inside one frame: p95=$objectP95Ms ms",
            objectP95Ms <= 16.0,
        )
        assertTrue(
            "12k-triangle facet selection must stay inside one frame: p95=$facetP95Ms ms",
            facetP95Ms <= 16.0,
        )
    }

    @Test
    fun densePrepareCameraFramesReuseOneUploadedMesh() {
        withGles3Pbuffer(720, 1280) {
            val triangles = denseGridTriangles(columns = 100, rows = 60)
            val model = ModelInfo(
                fileName = "dense-grid.stl",
                triangles = triangles.size / 9,
                dimensions = listOf(200.0, 180.0, 20.0),
                localPath = "",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(200.0, 180.0, 20.0),
                previewTriangles = triangles,
            )
            val projectObject = ProjectObject(id = "dense", model = model)
            val geometry = PrepareModelSceneBuilder.build(
                listOf(projectObject),
                220f,
                220f,
                rectangularBedPolygon(220f, 220f),
            )
            val objectStates = mapOf(
                projectObject.id to PrepareObjectDrawState(
                    projectObject.id,
                    projectObject.transform,
                    projectObject.transform.minimumRotatedZ(projectObject),
                ),
            )
            val renderer = PrepareModelRenderer()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 720, 1280)
            val durations = ArrayList<Long>()
            repeat(12) { frame ->
                renderer.submit(
                    geometry,
                    objectStates,
                    projectObject.id,
                    PrepareModelCamera(-45f + frame * 2f, 55f, 1f, 0f, 0f),
                )
                val started = SystemClock.elapsedRealtimeNanos()
                renderer.onDrawFrame(null)
                GLES30.glFinish()
                durations += SystemClock.elapsedRealtimeNanos() - started
            }
            assertTrue(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            assertTrue("Camera changes must not re-upload topology", renderer.geometryUploadCountForTest() == 1)
            val sorted = durations.drop(2).sorted()
            val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
            val p95Ms = sorted.last() / 1_000_000.0
            println(
                "DuckyPrepare dense triangles=${triangles.size / 9} " +
                    "p50Ms=$p50Ms p95Ms=$p95Ms uploads=1",
            )
            assertTrue(
                "12k-triangle camera frames must remain interactive: p95=$p95Ms ms",
                p95Ms <= 50.0,
            )
            renderer.releaseGpuGeometryForMemoryPressure()
        }
    }

    @Test
    fun prepareRendererDrawsModelThroughRealGles3Context() {
        val framebufferSize = 256
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals(EGL14.EGL_NO_DISPLAY, display)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        assertTrue(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_DEPTH_SIZE,
                    24,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                configs.size,
                configCount,
                0,
            ) && configCount[0] == 1,
        )
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        assertNotEquals(EGL14.EGL_NO_CONTEXT, context)
        val surface = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(
                EGL14.EGL_WIDTH,
                framebufferSize,
                EGL14.EGL_HEIGHT,
                framebufferSize,
                EGL14.EGL_NONE,
            ),
            0,
        )
        assertNotEquals(EGL14.EGL_NO_SURFACE, surface)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            val renderer = PrepareModelRenderer()
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            val empty = PrepareModelSceneBuilder.build(
                projectObjects = emptyList(),
                bedSizeX = 100f,
                bedSizeY = 100f,
                requestedBedPolygon = rectangularBedPolygon(100f, 100f),
            )
            val camera = PrepareModelCamera(-45f, 55f, 1f, 0f, 0f)
            renderer.submit(empty, emptyMap(), null, camera)
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            val bedOnly = framebufferRgba(framebufferSize)

            val model = ModelInfo(
                fileName = "gpu-triangle.stl",
                triangles = 1,
                dimensions = listOf(50.0, 50.0, 10.0),
                localPath = "",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(50.0, 50.0, 10.0),
                previewTriangles = floatArrayOf(
                    0f, 0f, 0f,
                    50f, 0f, 0f,
                    25f, 50f, 10f,
                ),
            )
            val projectObject = ProjectObject(id = "gpu-object", model = model)
            val geometry = PrepareModelSceneBuilder.build(
                projectObjects = listOf(projectObject),
                bedSizeX = 100f,
                bedSizeY = 100f,
                requestedBedPolygon = rectangularBedPolygon(100f, 100f),
            )
            renderer.submit(
                geometry = geometry,
                objects = mapOf(
                    projectObject.id to PrepareObjectDrawState(
                        objectId = projectObject.id,
                        transform = projectObject.transform,
                        minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
                    ),
                ),
                selectedObjectId = projectObject.id,
                camera = camera,
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            val withModel = framebufferRgba(framebufferSize)
            val topologyUploadsBeforeOverlay = renderer.geometryUploadCountForTest()

            renderer.submit(
                geometry = geometry,
                objects = mapOf(
                    projectObject.id to PrepareObjectDrawState(
                        objectId = projectObject.id,
                        transform = projectObject.transform,
                        minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
                    ),
                ),
                selectedObjectId = projectObject.id,
                camera = camera,
                overlays = listOf(
                    PrepareModelOverlayData(
                        meshIndex = 0,
                        fillIndices = intArrayOf(0, 1, 2),
                        lineIndices = intArrayOf(0, 1, 1, 2, 2, 0),
                        fillColor = PrepareOverlayColor(0.36f, 0.90f, 0.66f, 0.90f),
                        lineColor = PrepareOverlayColor(0.08f, 0.24f, 0.18f, 1f),
                    ),
                ),
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            val withOverlay = framebufferRgba(framebufferSize)

            assertFalse("Model geometry must alter the rendered framebuffer", bedOnly.contentEquals(withModel))
            assertFalse(
                "Painted facet overlays must alter the rendered model",
                withModel.contentEquals(withOverlay),
            )
            assertEquals(
                "Changing paint overlays must not re-upload immutable model topology",
                topologyUploadsBeforeOverlay,
                renderer.geometryUploadCountForTest(),
            )
            assertTrue("Prepare rendering must complete without GL errors", GLES30.glGetError() == GLES30.GL_NO_ERROR)
            renderer.releaseGpuGeometryForMemoryPressure()
            assertTrue(GLES30.glGetError() == GLES30.GL_NO_ERROR)
        } finally {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun framebufferRgba(size: Int): ByteArray {
        val pixels = ByteBuffer.allocateDirect(size * size * 4)
        GLES30.glReadPixels(
            0,
            0,
            size,
            size,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels,
        )
        pixels.position(0)
        return ByteArray(pixels.remaining()).also(pixels::get)
    }

    private fun denseGridTriangles(columns: Int, rows: Int): FloatArray {
        val result = FloatArray(columns * rows * 2 * 9)
        var output = 0
        repeat(rows) { row ->
            repeat(columns) { column ->
                val x0 = column * 2f
                val x1 = x0 + 2f
                val y0 = row * 3f
                val y1 = y0 + 3f
                fun z(x: Float, y: Float) = (kotlin.math.sin(x * 0.04f) + kotlin.math.cos(y * 0.05f)) * 5f + 10f
                floatArrayOf(
                    x0, y0, z(x0, y0), x1, y0, z(x1, y0), x1, y1, z(x1, y1),
                    x0, y0, z(x0, y0), x1, y1, z(x1, y1), x0, y1, z(x0, y1),
                ).copyInto(result, output)
                output += 18
            }
        }
        return result
    }

    private fun spatiallyScrambleTriangles(source: FloatArray): FloatArray {
        val triangleCount = source.size / 9
        val result = FloatArray(source.size)
        repeat(triangleCount) { outputTriangle ->
            val sourceTriangle = if (outputTriangle % 2 == 0) {
                outputTriangle / 2
            } else {
                triangleCount - 1 - outputTriangle / 2
            }
            source.copyInto(
                result,
                destinationOffset = outputTriangle * 9,
                startIndex = sourceTriangle * 9,
                endIndex = sourceTriangle * 9 + 9,
            )
        }
        return result
    }

    private inline fun withGles3Pbuffer(width: Int, height: Int, block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_DEPTH_SIZE, 24,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            ),
        )
        val config = checkNotNull(configs[0])
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        val surface = EGL14.eglCreatePbufferSurface(
            display, config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0,
        )
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            block()
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}

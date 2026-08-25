package com.ashcastle.duckyslicer

import android.content.Intent
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrepareModelRendererInstrumentedTest {
    private data class DenseFrameMetrics(
        val p50Ms: Double,
        val p95Ms: Double,
        val vertexCount: Int,
        val geometryUploads: Int,
    )

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
        val brushDurations = ArrayList<Long>()
        val brushSamples = facetBrushSampleOffsets(24f).map { offset ->
            androidx.compose.ui.geometry.Offset(360f, 614.4f) + offset
        }
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
            val brushStarted = SystemClock.elapsedRealtimeNanos()
            assertEquals(
                FACET_BRUSH_SAMPLE_COUNT,
                findPrepareFacetsAtScreenSamples(
                    projectObject = projectObject,
                    placement = placement,
                    viewport = viewport,
                    centerX = 360f,
                    centerY = 614.4f,
                    samplePositions = brushSamples,
                    touchRadiusPx = 24f * 0.28f,
                    pickingIndices = pickingIndices,
                ).size,
            )
            brushDurations += SystemClock.elapsedRealtimeNanos() - brushStarted
        }
        val sortedObjects = objectDurations.drop(2).sorted()
        val sortedFacets = facetDurations.drop(2).sorted()
        val sortedBrushes = brushDurations.drop(2).sorted()
        val objectP50Ms = sortedObjects[sortedObjects.size / 2] / 1_000_000.0
        val objectP95Ms = sortedObjects.last() / 1_000_000.0
        val facetP50Ms = sortedFacets[sortedFacets.size / 2] / 1_000_000.0
        val facetP95Ms = sortedFacets.last() / 1_000_000.0
        val brushP50Ms = sortedBrushes[sortedBrushes.size / 2] / 1_000_000.0
        val brushP95Ms = sortedBrushes.last() / 1_000_000.0
        println(
            "DuckyPrepare picking triangles=${triangles.size / 9} " +
                "indexBuildMs=$indexBuildMs " +
                "objectP50Ms=$objectP50Ms objectP95Ms=$objectP95Ms " +
                "facetP50Ms=$facetP50Ms facetP95Ms=$facetP95Ms " +
                "brushP50Ms=$brushP50Ms brushP95Ms=$brushP95Ms",
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
        assertTrue(
            "12k-triangle 37-point brush selection must stay inside one frame: " +
                "p95=$brushP95Ms ms",
            brushP95Ms <= 16.0,
        )
    }

    @Test
    fun densePrepareCameraFramesReuseOneUploadedMesh() {
        withGles3Pbuffer(720, 1280) {
            val triangles = denseGridTriangles(columns = 100, rows = 60)
            val coarseTriangles = denseGridTriangles(
                columns = 40,
                rows = 25,
                width = 200f,
                height = 180f,
            )
            val model = ModelInfo(
                fileName = "dense-grid.stl",
                triangles = triangles.size / 9,
                dimensions = listOf(200.0, 180.0, 20.0),
                localPath = "",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(200.0, 180.0, 20.0),
                previewTriangles = triangles,
                coarsePreviewTriangles = coarseTriangles,
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
            val preparationPriority = AtomicInteger(Int.MIN_VALUE)
            val preparation = Thread {
                runBlocking {
                    withModelPreparationContext {
                        preparationPriority.set(
                            android.os.Process.getThreadPriority(android.os.Process.myTid()),
                        )
                        buildPreparePickingIndices(listOf(projectObject)) { ensureActive() }
                        detectLayOnFaceCandidates(
                            triangles,
                            checkCancellation = { ensureActive() },
                        )
                    }
                }
            }.apply {
                name = "DuckyPrepareBenchmark"
                start()
            }
            val durations = ArrayList<Long>()
            repeat(12) { frame ->
                renderer.submit(
                    geometry,
                    objectStates,
                    projectObject.id,
                    PrepareModelCamera(-45f + frame * 2f, 55f, 1f, 0f, 0f),
                    interactionActive = true,
                )
                val started = SystemClock.elapsedRealtimeNanos()
                renderer.onDrawFrame(null)
                GLES30.glFinish()
                durations += SystemClock.elapsedRealtimeNanos() - started
            }
            preparation.join(5_000)
            assertFalse("Background model preparation must complete", preparation.isAlive)
            assertTrue(
                "Optional model preparation must run below display priority",
                preparationPriority.get() >= android.os.Process.THREAD_PRIORITY_BACKGROUND,
            )
            assertTrue(GLES30.glGetError() == GLES30.GL_NO_ERROR)
            assertTrue("Camera changes must not re-upload topology", renderer.geometryUploadCountForTest() == 1)
            assertEquals(2_000 * 3, renderer.lastMeshVertexCountForTest())
            val sorted = durations.drop(2).sorted()
            val p50Ms = sorted[sorted.size / 2] / 1_000_000.0
            val p95Ms = sorted.last() / 1_000_000.0
            println(
                "DuckyPrepare dense triangles=${triangles.size / 9} " +
                    "p50Ms=$p50Ms p95Ms=$p95Ms uploads=1",
            )
            assertTrue(
                "Connected 2k-triangle gesture frames must remain interactive: p95=$p95Ms ms",
                p95Ms <= 50.0,
            )
            renderer.releaseGpuGeometryForMemoryPressure()
        }
    }

    @Test
    fun densePrepareInteractionReducesRasterWorkWithoutDroppingTheLowDetailShape() {
        val logical = PreviewSurfaceSize(720, 1_280)
        val reduced = prepareSurfaceSize(
            logical.width,
            logical.height,
            interactionActive = true,
        )
        val fullMetrics = measureDensePrepareInteraction(logical, logical)
        val reducedMetrics = measureDensePrepareInteraction(reduced, logical)
        val settledDetailMetrics = measureDensePrepareInteraction(
            logical,
            logical,
            interactionActive = false,
        )
        println(
            "DuckyPrepare raster full=${logical.width}x${logical.height} " +
                "reduced=${reduced.width}x${reduced.height} " +
                "fullP50Ms=${fullMetrics.p50Ms} fullP95Ms=${fullMetrics.p95Ms} " +
                "reducedP50Ms=${reducedMetrics.p50Ms} reducedP95Ms=${reducedMetrics.p95Ms} " +
                "detailP50Ms=${settledDetailMetrics.p50Ms} " +
                "detailP95Ms=${settledDetailMetrics.p95Ms} " +
                "gestureVertices=${reducedMetrics.vertexCount} " +
                "detailVertices=${settledDetailMetrics.vertexCount}",
        )
        assertTrue(reduced.width * reduced.height < logical.width * logical.height * 0.55f)
        assertEquals(2_000 * 3, fullMetrics.vertexCount)
        assertEquals(fullMetrics.vertexCount, reducedMetrics.vertexCount)
        assertEquals(1, fullMetrics.geometryUploads)
        assertEquals(1, reducedMetrics.geometryUploads)
        assertEquals(48_000 * 3, settledDetailMetrics.vertexCount)
        assertEquals(1, settledDetailMetrics.geometryUploads)
        assertTrue(
            "Reduced Prepare raster must not regress interaction completion: " +
                "full=${fullMetrics.p95Ms} reduced=${reducedMetrics.p95Ms}",
            reducedMetrics.p95Ms <= fullMetrics.p95Ms * 1.35 + 2.0,
        )
        assertTrue(
            "The restored 48k-triangle detail frame must remain responsive: " +
                "p95=${settledDetailMetrics.p95Ms}",
            settledDetailMetrics.p95Ms <= 50.0,
        )
    }

    @Test
    fun densePrepareProgressivelyUploadsPreviewThenDetailAndGestureTopology() =
        withGles3Pbuffer(256, 256) {
            val preview = denseGridTriangles(100, 60, 400f, 360f)
            val coarse = denseGridTriangles(40, 25, 400f, 360f)
            val detail = denseGridTriangles(200, 120, 400f, 360f)
            val model = ModelInfo(
                fileName = "progressive-dense.stl",
                triangles = 200_000,
                dimensions = listOf(400.0, 360.0, 20.0),
                localPath = "",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(400.0, 360.0, 20.0),
                previewTriangles = preview,
                coarsePreviewTriangles = coarse,
                detailPreviewTriangles = detail,
            )
            val projectObject = ProjectObject(id = "progressive-dense", model = model)
            val geometry = PrepareModelSceneBuilder.build(
                listOf(projectObject),
                440f,
                440f,
                rectangularBedPolygon(440f, 440f),
            ).withPrecomputedPrepareInteractionNormals()
            val objectStates = mapOf(
                projectObject.id to PrepareObjectDrawState(
                    projectObject.id,
                    projectObject.transform,
                    projectObject.transform.minimumRotatedZ(projectObject),
                ),
            )
            val renderer = PrepareModelRenderer()
            renderer.setLogicalViewportSize(256, 256)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 256, 256)

            renderer.submit(
                geometry,
                objectStates,
                projectObject.id,
                PrepareModelCamera(-45f, 55f, 1f, 0f, 0f),
                refinementReady = false,
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            assertEquals(preview.size / 3, renderer.lastMeshVertexCountForTest())
            assertEquals(1, geometry.normalUploadCache.pendingTopologyCountForTest())
            assertEquals(0, geometry.normalUploadCache.fallbackGenerationCountForTest())
            assertEquals(
                "The first useful frame retains four bed buffers and one position/normal pair",
                6,
                renderer.retainedTopologyBufferCountForTest(),
            )
            assertEquals(1, renderer.geometryUploadCountForTest())

            val distinctDetail = uniquePrepareDetailVertexArrays(geometry.meshes)
            assertEquals(1, distinctDetail.size)
            geometry.normalUploadCache.addPrecomputed(distinctDetail)
            assertEquals(2, geometry.normalUploadCache.pendingTopologyCountForTest())
            renderer.submit(
                geometry,
                objectStates,
                projectObject.id,
                PrepareModelCamera(-45f, 55f, 1f, 0f, 0f),
                refinementReady = true,
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            assertEquals(detail.size / 3, renderer.lastMeshVertexCountForTest())
            assertEquals(1, geometry.normalUploadCache.pendingTopologyCountForTest())
            assertEquals(0, geometry.normalUploadCache.fallbackGenerationCountForTest())
            assertEquals(
                "Idle refinement appends one detail position/normal pair without recreating the scene",
                8,
                renderer.retainedTopologyBufferCountForTest(),
            )
            assertEquals(1, renderer.geometryUploadCountForTest())

            renderer.submit(
                geometry,
                objectStates,
                projectObject.id,
                PrepareModelCamera(-40f, 55f, 1f, 0f, 0f),
                interactionActive = true,
                refinementReady = false,
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            assertEquals(coarse.size / 3, renderer.lastMeshVertexCountForTest())
            assertEquals(0, geometry.normalUploadCache.pendingTopologyCountForTest())
            assertEquals(0, geometry.normalUploadCache.fallbackGenerationCountForTest())
            assertEquals(
                "Gesture entry appends one connected coarse position/normal pair",
                10,
                renderer.retainedTopologyBufferCountForTest(),
            )
            assertEquals(1, renderer.geometryUploadCountForTest())
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            renderer.releaseGpuGeometryForMemoryPressure()
        }

    @Test
    fun productionPrepareSurfaceRestoresFullDetailAfterReducedRasterInteraction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val surfaceReference = AtomicReference<PrepareModelSurfaceView>()
        val unavailable = AtomicBoolean(false)
        val memoryPressureCalls = AtomicInteger()
        val memoryRecoveryCalls = AtomicInteger()
        val previewTriangles = denseGridTriangles(100, 60, 400f, 360f)
        val coarseTriangles = denseGridTriangles(40, 25, 400f, 360f)
        val detailTriangles = denseGridTriangles(200, 120, 400f, 360f)
        val model = ModelInfo(
            fileName = "production-dense-raster.stl",
            triangles = 200_000,
            dimensions = listOf(400.0, 360.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(400.0, 360.0, 20.0),
            previewTriangles = previewTriangles,
            coarsePreviewTriangles = coarseTriangles,
            detailPreviewTriangles = detailTriangles,
        )
        val projectObject = ProjectObject(id = "production-dense", model = model)
        val geometry = PrepareModelSceneBuilder.build(
            listOf(projectObject),
            440f,
            440f,
            rectangularBedPolygon(440f, 440f),
        )
        val objectStates = mapOf(
            projectObject.id to PrepareObjectDrawState(
                projectObject.id,
                projectObject.transform,
                projectObject.transform.minimumRotatedZ(projectObject),
            ),
        )
        val camera = PrepareModelCamera(-45f, 55f, 1f, 0f, 0f)
        ActivityScenario.launch<AccessibilityHarnessActivity>(
            Intent(context, AccessibilityHarnessActivity::class.java),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val surface = PrepareModelSurfaceView(
                    context = activity,
                    onUnavailable = { unavailable.set(true) },
                    onMemoryPressure = { memoryPressureCalls.incrementAndGet() },
                    onMemoryPressureRecovered = {
                        memoryRecoveryCalls.incrementAndGet()
                        surfaceReference.get()?.setMemoryPressureActive(false)
                    },
                )
                activity.setContentView(
                    surface,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                surface.submit(
                    geometry,
                    objectStates,
                    projectObject.id,
                    camera,
                    interactionActive = false,
                    overlays = emptyList(),
                )
                surfaceReference.set(surface)
            }
            val surface = checkNotNull(surfaceReference.get())
            waitForPrepareSurface(surface) { it.rendererReadyForTest() }
            assertFalse("The production Prepare renderer must remain available", unavailable.get())
            val logical = surface.logicalSurfaceSizeForTest()
            waitForPrepareSurface(surface) { it.renderBufferSizeForTest() == logical }
            assertEquals(48_000 * 3, surface.lastMeshVertexCountForTest())

            scenario.onActivity {
                surface.submit(
                    geometry,
                    objectStates,
                    projectObject.id,
                    camera.copy(yawDegrees = -40f),
                    interactionActive = true,
                    overlays = emptyList(),
                )
            }
            val interaction = prepareSurfaceSize(
                logical.width,
                logical.height,
                interactionActive = true,
            )
            waitForPrepareSurface(surface) {
                it.renderBufferSizeForTest() == interaction &&
                    it.lastMeshVertexCountForTest() == 2_000 * 3
            }
            assertEquals(interaction, surface.renderBufferSizeForTest())

            scenario.onActivity {
                surface.submit(
                    geometry,
                    objectStates,
                    projectObject.id,
                    camera.copy(yawDegrees = -37f, zoom = 2f),
                    interactionActive = true,
                    overlays = emptyList(),
                )
            }
            waitForPrepareSurface(surface) {
                it.lastMeshVertexCountForTest() == 12_000 * 3
            }

            scenario.onActivity {
                surface.submit(
                    geometry,
                    objectStates,
                    projectObject.id,
                    camera.copy(yawDegrees = -35f),
                    interactionActive = false,
                    overlays = emptyList(),
                )
            }
            waitForPrepareSurface(surface) {
                it.renderBufferSizeForTest() == logical &&
                    it.lastMeshVertexCountForTest() == 48_000 * 3
            }
            assertEquals(logical, surface.renderBufferSizeForTest())

            val uploadsBeforeMemoryPressure = surface.geometryUploadCountForTest()
            assertTrue(surface.retainedTopologyBufferCountForTest() > 0)
            scenario.onActivity {
                surface.releasePrepareMemoryForTest()
                surface.releasePrepareMemoryForTest()
            }
            waitForPrepareSurface(surface) {
                memoryPressureCalls.get() == 1 &&
                    it.retainedTopologyBufferCountForTest() == 0
            }
            assertEquals(
                "Repeated memory callbacks must be deduplicated until foreground recovery",
                1,
                memoryPressureCalls.get(),
            )

            scenario.onActivity { surface.requestMemoryPressureRecoveryForTest() }
            waitForPrepareSurface(surface) {
                memoryRecoveryCalls.get() == 1 &&
                    it.geometryUploadCountForTest() > uploadsBeforeMemoryPressure &&
                    it.retainedTopologyBufferCountForTest() > 0
            }
            assertFalse("Recovered Prepare rendering must remain available", unavailable.get())
        }
    }

    @Test
    fun repeatedPlacementsShareOneLazilyRequestedDetailTopology() =
        withGles3Pbuffer(128, 128) {
            val preview = denseGridTriangles(columns = 2, rows = 2)
            val detail = denseGridTriangles(columns = 4, rows = 4)
            val model = ModelInfo(
                fileName = "shared-topology.stl",
                triangles = detail.size / 9,
                dimensions = listOf(4.0, 4.0, 1.0),
                localPath = "",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(4.0, 4.0, 1.0),
                previewTriangles = preview,
                detailPreviewTriangles = detail,
            )
            val first = ProjectObject(id = "shared-first", model = model)
            val second = ProjectObject(
                id = "shared-second",
                model = model,
                transform = ModelTransform(offsetXmm = 8f),
            )
            val geometry = PrepareModelSceneBuilder.build(
                projectObjects = listOf(first, second),
                bedSizeX = 40f,
                bedSizeY = 40f,
                requestedBedPolygon = rectangularBedPolygon(40f, 40f),
            )
            val renderer = PrepareModelRenderer()
            renderer.setLogicalViewportSize(128, 128)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 128, 128)
            renderer.submit(
                geometry = geometry,
                objects = listOf(first, second).associate { projectObject ->
                    projectObject.id to PrepareObjectDrawState(
                        objectId = projectObject.id,
                        transform = projectObject.transform,
                        minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
                    )
                },
                selectedObjectId = first.id,
                camera = PrepareModelCamera(-45f, 55f, 1f, 0f, 0f),
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()

            assertEquals(
                "Four bed buffers plus one shared position/normal topology must be retained",
                6,
                renderer.retainedTopologyBufferCountForTest(),
            )
            assertEquals(detail.size / 3 * 2, renderer.lastMeshVertexCountForTest())
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            renderer.releaseGpuGeometryForMemoryPressure()
        }

    @Test
    fun distinctPlacementsRenderCompleteCoarseMeshesAfterTheInteractionBudget() =
        withGles3Pbuffer(128, 128) {
            val models = (0 until 3).map { index ->
                val preview = denseGridTriangles(columns = 2, rows = 2)
                val coarse = denseGridTriangles(columns = 1, rows = 1)
                ModelInfo(
                    fileName = "bounded-$index.stl",
                    triangles = preview.size / 9,
                    dimensions = listOf(4.0, 4.0, 1.0),
                    localPath = "",
                    minMm = listOf(0.0, 0.0, 0.0),
                    maxMm = listOf(4.0, 4.0, 1.0),
                    previewTriangles = preview,
                    coarsePreviewTriangles = coarse,
                    detailPreviewTriangles = denseGridTriangles(columns = 4, rows = 4),
                )
            }
            val objects = models.mapIndexed { index, model ->
                ProjectObject(
                    id = "bounded-$index",
                    model = model,
                    transform = ModelTransform(offsetXmm = index * 6f),
                )
            }
            val baselineBytes = models.sumOf { prepareMeshGpuBytes(it.coarsePreviewTriangles) }
            val geometry = PrepareModelSceneBuilder.build(
                projectObjects = objects,
                bedSizeX = 40f,
                bedSizeY = 40f,
                requestedBedPolygon = rectangularBedPolygon(40f, 40f),
                additionalDetailBudgetBytes = 0L,
                lowDetailBudgetBytes = baselineBytes +
                    prepareMeshGpuBytes(models.first().previewTriangles),
            )
            val renderer = PrepareModelRenderer()
            renderer.setLogicalViewportSize(128, 128)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, 128, 128)
            renderer.submit(
                geometry = geometry,
                objects = objects.associate { projectObject ->
                    projectObject.id to PrepareObjectDrawState(
                        objectId = projectObject.id,
                        transform = projectObject.transform,
                        minimumRotatedZ = projectObject.transform.minimumRotatedZ(projectObject),
                    )
                },
                selectedObjectId = objects.first().id,
                camera = PrepareModelCamera(-45f, 55f, 1f, 0f, 0f),
                interactionActive = true,
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()

            assertEquals(
                "Four bed buffers and three distinct position/normal pairs are retained lazily",
                10,
                renderer.retainedTopologyBufferCountForTest(),
            )
            assertEquals(
                models.sumOf { it.coarsePreviewTriangles.size / 3 },
                renderer.lastMeshVertexCountForTest(),
            )
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
            renderer.releaseGpuGeometryForMemoryPressure()
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
                triangles = 2,
                dimensions = listOf(50.0, 50.0, 10.0),
                localPath = "",
                minMm = listOf(0.0, 0.0, 0.0),
                maxMm = listOf(50.0, 50.0, 10.0),
                previewTriangles = floatArrayOf(
                    0f, 0f, 0f,
                    50f, 0f, 0f,
                    25f, 50f, 10f,
                ),
                detailPreviewTriangles = floatArrayOf(
                    0f, 0f, 0f,
                    50f, 0f, 0f,
                    50f, 50f, 10f,
                    0f, 0f, 0f,
                    50f, 50f, 10f,
                    0f, 50f, 10f,
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
            assertEquals(6, renderer.lastMeshVertexCountForTest())

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
                interactionActive = true,
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            assertEquals(3, renderer.lastMeshVertexCountForTest())
            assertEquals(topologyUploadsBeforeOverlay, renderer.geometryUploadCountForTest())

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
            assertEquals(3, renderer.lastMeshVertexCountForTest())

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
                        fillIndices = IntArray(0),
                        lineIndices = intArrayOf(0, 1, 1, 2, 2, 0),
                        fillColor = PrepareOverlayColor(1f, 0.42f, 0.42f, 0.90f),
                        lineColor = PrepareOverlayColor(0.33f, 0.12f, 0.12f, 1f),
                        customVertices = floatArrayOf(
                            0f, 0f, 0f,
                            25f, 0f, 0f,
                            12.5f, 25f, 5f,
                        ),
                    ),
                ),
            )
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            val withSplitOverlay = framebufferRgba(framebufferSize)

            assertFalse("Model geometry must alter the rendered framebuffer", bedOnly.contentEquals(withModel))
            assertFalse(
                "Painted facet overlays must alter the rendered model",
                withModel.contentEquals(withOverlay),
            )
            assertFalse(
                "Exact split-facet geometry must be rendered as a partial overlay",
                withOverlay.contentEquals(withSplitOverlay),
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

    private fun measureDensePrepareInteraction(
        bufferSize: PreviewSurfaceSize,
        logicalSize: PreviewSurfaceSize,
        interactionActive: Boolean = true,
    ): DenseFrameMetrics = withGles3Pbuffer(bufferSize.width, bufferSize.height) {
        val previewTriangles = denseGridTriangles(100, 60, 400f, 360f)
        val coarseTriangles = denseGridTriangles(40, 25, 400f, 360f)
        val detailTriangles = denseGridTriangles(200, 120, 400f, 360f)
        val model = ModelInfo(
            fileName = "dense-raster.stl",
            triangles = 200_000,
            dimensions = listOf(400.0, 360.0, 20.0),
            localPath = "",
            minMm = listOf(0.0, 0.0, 0.0),
            maxMm = listOf(400.0, 360.0, 20.0),
            previewTriangles = previewTriangles,
            coarsePreviewTriangles = coarseTriangles,
            detailPreviewTriangles = detailTriangles,
        )
        val projectObject = ProjectObject(id = "dense-raster", model = model)
        val geometry = PrepareModelSceneBuilder.build(
            listOf(projectObject),
            440f,
            440f,
            rectangularBedPolygon(440f, 440f),
        )
        val renderer = PrepareModelRenderer()
        renderer.setLogicalViewportSize(logicalSize.width, logicalSize.height)
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, bufferSize.width, bufferSize.height)
        val objectStates = mapOf(
            projectObject.id to PrepareObjectDrawState(
                projectObject.id,
                projectObject.transform,
                projectObject.transform.minimumRotatedZ(projectObject),
            ),
        )
        val durations = ArrayList<Long>()
        repeat(24) { frame ->
            renderer.submit(
                geometry,
                objectStates,
                projectObject.id,
                PrepareModelCamera(-45f + frame * 1.5f, 55f, 1f, 0f, 0f),
                interactionActive = interactionActive,
            )
            val started = SystemClock.elapsedRealtimeNanos()
            renderer.onDrawFrame(null)
            GLES30.glFinish()
            durations += SystemClock.elapsedRealtimeNanos() - started
        }
        val sorted = durations.drop(4).sorted()
        val metrics = DenseFrameMetrics(
            p50Ms = sorted[sorted.size / 2] / 1_000_000.0,
            p95Ms = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)] /
                1_000_000.0,
            vertexCount = renderer.lastMeshVertexCountForTest(),
            geometryUploads = renderer.geometryUploadCountForTest(),
        )
        renderer.releaseGpuGeometryForMemoryPressure()
        metrics
    }

    private fun waitForPrepareSurface(
        surface: PrepareModelSurfaceView,
        condition: (PrepareModelSurfaceView) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (!condition(surface) && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20L)
        }
        assertTrue("Prepare production Surface did not reach the expected state", condition(surface))
    }

    private fun denseGridTriangles(
        columns: Int,
        rows: Int,
        width: Float = columns * 2f,
        height: Float = rows * 3f,
    ): FloatArray {
        require(columns > 0 && rows > 0 && width > 0f && height > 0f)
        val result = FloatArray(columns * rows * 2 * 9)
        var output = 0
        val stepX = width / columns
        val stepY = height / rows
        repeat(rows) { row ->
            repeat(columns) { column ->
                val x0 = column * stepX
                val x1 = x0 + stepX
                val y0 = row * stepY
                val y1 = y0 + stepY
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

    private inline fun <T> withGles3Pbuffer(width: Int, height: Int, block: () -> T): T {
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
            return block()
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

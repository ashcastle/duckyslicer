package com.ashcastle.duckyslicer

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class NativeEngineInstrumentedTest {
    private data class ToolpathBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private data class TestVertex(val x: Float, val y: Float, val z: Float)

    private data class MeshCorpusEntry(
        val name: String,
        val model: File,
        val mustSlice: Boolean,
    )

    @Test
    fun automaticPreviewQualityResolvesToAConcreteDeviceTier() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val capabilities = previewDeviceCapabilities(context)
        val resolved = resolvePreviewDetail(PreviewDetail.AUTOMATIC, capabilities)

        assertTrue("Android must report a positive app memory class", capabilities.appMemoryClassMb > 0)
        assertEquals(
            "Automatic preview must use the measured smooth tier before building GPU geometry",
            PreviewDetail.PERFORMANCE,
            resolved,
        )
    }

    @Test
    fun persistentProjectModelSlicesIntoRetainedArtifact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projectStore = ProjectStore(context)
        val storedModel = projectStore.createModelDestination("persistent-slice.stl")
        val nativeOutput = File(requireNotNull(storedModel.parentFile), SliceArtifactStore.NATIVE_OUTPUT_NAME)
        try {
            fixtureModel().copyTo(storedModel, overwrite = true)

            val outcome = OnDeviceSlicer.slice(
                storedModel,
                SliceOptions().selectQuality(QualityProfile.DRAFT),
            )

            assertTrue("Persistent project models must produce retained G-code", outcome.output.length() > 1_000L)
            assertTrue("Slice outcome must retain Orca's print-time estimate", outcome.estimatedSeconds > 0f)
            assertTrue("Slice outcome must retain Orca's filament-length estimate", outcome.filamentMm > 0f)
            assertTrue("Slice outcome must retain Orca's filament-mass estimate", outcome.filamentGrams > 0f)
            assertEquals(
                "Completed G-code must move into bounded slice storage",
                File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY).canonicalFile,
                requireNotNull(outcome.output.parentFile).canonicalFile,
            )
            assertFalse("Native output must not remain beside the project model", nativeOutput.exists())
        } finally {
            storedModel.delete()
            nativeOutput.delete()
        }
    }

    @Test
    fun inheritedPerFeatureJerkChangesActualMarlinToolpathCommands() {
        val printer = PrinterProfile.U1_04.copy(
            gcodeFlavor = "marlin2",
            maxJerkX = 50f,
            maxJerkY = 50f,
        )
        val options = SliceOptions()
            .selectPrinter(printer)
            .copy(
                perimeters = 3,
                fillDensity = 0.2f,
                jerk = JerkSettings(
                    defaultJerk = 8.5f,
                    outerWallJerk = 7.5f,
                    innerWallJerk = 8f,
                    topSurfaceJerk = 6.5f,
                    infillJerk = 9.5f,
                    firstLayerJerk = 5.5f,
                    travelJerk = 12.5f,
                ),
            )

        val outcome = OnDeviceSlicer.slice(fixtureModel(), options)
        try {
            val gcode = outcome.output.readText()
            for (value in listOf("5.5", "6.5", "7.5", "8", "9.5", "12.5")) {
                assertTrue(
                    "Orca must emit the requested feature jerk into real Marlin motion commands: $value",
                    gcode.contains("M205 X$value Y$value"),
                )
            }
            assertTrue(gcode.contains("; default_jerk = 8.5"))
            assertTrue(gcode.contains("; outer_wall_jerk = 7.5"))
            assertTrue(gcode.contains("; inner_wall_jerk = 8"))
            assertTrue(gcode.contains("; top_surface_jerk = 6.5"))
            assertTrue(gcode.contains("; infill_jerk = 9.5"))
            assertTrue(gcode.contains("; initial_layer_jerk = 5.5"))
            assertTrue(gcode.contains("; travel_jerk = 12.5"))
        } finally {
            outcome.output.delete()
        }
    }

    @Test
    fun inheritedMotionOutputChangesFirstLayerTravelAndKlipperLimits() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04.copy(gcodeFlavor = "klipper"))
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                travelSpeed = 400f,
                defaultAcceleration = 2_000f,
            )
        val absolute = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                gcodeSettings = GcodeSettings(
                    initialLayerTravelSpeed = 40f,
                    initialLayerTravelSpeedPercent = false,
                    accelToDecelEnabled = true,
                    accelToDecelFactor = 25f,
                ),
            ),
        )
        val percentage = OnDeviceSlicer.slice(
            fixtureModel(),
            base.copy(
                gcodeSettings = GcodeSettings(
                    initialLayerTravelSpeed = 25f,
                    initialLayerTravelSpeedPercent = true,
                    accelToDecelEnabled = false,
                    accelToDecelFactor = 25f,
                ),
            ),
        )
        try {
            fun firstLayerTravelFeeds(gcode: String): Set<Float> {
                val lines = gcode.lineSequence().toList()
                val layerMarkers = lines.indices.filter { lines[it] == ";LAYER_CHANGE" }
                assertTrue("A real slice needs at least two layers", layerMarkers.size >= 2)
                return lines.subList(layerMarkers[0] + 1, layerMarkers[1])
                    .asSequence()
                    .filter { line ->
                        (line.startsWith("G0 ") || line.startsWith("G1 ")) &&
                            !line.contains(" E") &&
                            (line.contains(" X") || line.contains(" Y"))
                    }
                    .mapNotNull { line ->
                        Regex("(?:^| )F([0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
                    }
                    .toSet()
            }

            val absoluteGcode = absolute.output.readText()
            val percentageGcode = percentage.output.readText()
            assertTrue(
                "An absolute 40 mm/s first-layer travel must emit F2400",
                2_400f in firstLayerTravelFeeds(absoluteGcode),
            )
            assertTrue(
                "25% of a 400 mm/s travel speed must emit F6000",
                6_000f in firstLayerTravelFeeds(percentageGcode),
            )
            val velocityLimits = Regex(
                "SET_VELOCITY_LIMIT ACCEL=([0-9.]+) ACCEL_TO_DECEL=([0-9.]+)",
            ).findAll(absoluteGcode).toList()
            assertTrue("Enabled Klipper smoothing must emit acceleration limits", velocityLimits.isNotEmpty())
            assertTrue(
                "Every emitted accel-to-decel limit must retain the selected 25% factor",
                velocityLimits.all { match ->
                    val acceleration = match.groupValues[1].toFloat()
                    val deceleration = match.groupValues[2].toFloat()
                    abs(deceleration - acceleration * 0.25f) < 0.01f
                },
            )
            assertFalse(percentageGcode.contains("ACCEL_TO_DECEL="))
            assertTrue(absoluteGcode.contains("; initial_layer_travel_speed = 40"))
            assertTrue(absoluteGcode.contains("; accel_to_decel_enable = 1"))
            assertTrue(absoluteGcode.contains("; accel_to_decel_factor = 25%"))
        } finally {
            absolute.output.delete()
            percentage.output.delete()
        }
    }

    @Test
    fun depthPreviewPrewarmsGestureVboAndReusesItAcrossCameraFrames() {
        val framebufferSize = 256
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals("EGL display must be available", EGL14.EGL_NO_DISPLAY, display)
        val version = IntArray(2)
        assertTrue("EGL must initialize", EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val configCount = IntArray(1)
        val configAttributes = intArrayOf(
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
        )
        assertTrue(
            "An OpenGL ES 3 pbuffer config must be available",
            EGL14.eglChooseConfig(
                display,
                configAttributes,
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
        assertNotEquals("OpenGL ES 3 context creation must succeed", EGL14.EGL_NO_CONTEXT, context)
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
        assertNotEquals("EGL pbuffer creation must succeed", EGL14.EGL_NO_SURFACE, surface)
        try {
            assertTrue(
                "The pbuffer must become current",
                EGL14.eglMakeCurrent(display, surface, surface, context),
            )
            val preview = GcodeLayerPreview(
                startLayer = 0,
                endLayer = 0,
                layerCount = 1,
                minZMm = 0.2f,
                maxZMm = 0.2f,
                segments = floatArrayOf(10f, 10f, 20f, 10f, 0.2f, 0f),
                roleSegmentCounts = intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            )
            val scene = ToolpathScene(preview, 100f, 100f, 1f, 0.8f, PreviewDetail.BALANCED)
            val renderer = ToolpathRenderer()
            renderer.submit(scene)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            renderer.onDrawFrame(null)

            assertEquals(
                "The first frame must upload one coherent low-cost geometry set",
                1,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals("The instanced draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            renderer.onDrawFrame(null)
            assertEquals(
                "The next idle frame must upload the requested detail geometry set",
                2,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(
                "Only the requested and gesture geometry sets may stay resident",
                2,
                renderer.cachedGeometryCountForTest(),
            )

            renderer.orbitBy(12f, -7f)
            renderer.zoomBy(1.1f)
            renderer.onDrawFrame(null)
            assertEquals(
                "Camera-only frames must reuse the uploaded GPU buffers",
                2,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals("The reused VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            renderer.setInteractionActive(true)
            renderer.onDrawFrame(null)
            assertEquals(
                "Starting a gesture must reuse the prewarmed lower-detail geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )
            renderer.orbitBy(-8f, 5f)
            renderer.onDrawFrame(null)
            assertEquals(
                "Every subsequent gesture frame must reuse the lower-detail geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals("The gesture VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            renderer.setInteractionActive(false)
            renderer.onDrawFrame(null)
            assertEquals(
                "Settling after a gesture must reuse the requested geometry",
                2,
                renderer.geometryUploadCountForTest(),
            )

            renderer.submit(scene.copy(visibleRoles = setOf(1)))
            renderer.onDrawFrame(null)
            assertEquals(
                "A geometry change must replace the GPU buffers exactly once",
                3,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(
                "Old-scene GPU buffers must be released before the new gesture tier is prewarmed",
                1,
                renderer.cachedGeometryCountForTest(),
            )
            renderer.onDrawFrame(null)
            assertEquals(
                "Changed geometry must prewarm its gesture VBO without growing the cache",
                4,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(
                "The GPU cache must remain bounded to two geometry sets",
                2,
                renderer.cachedGeometryCountForTest(),
            )
            assertEquals("The replacement VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            val uploadsBeforeTrim = renderer.geometryUploadCountForTest()
            renderer.releaseGpuGeometryForMemoryPressure()
            assertEquals(
                "UI memory pressure must release every reconstructable preview buffer",
                0,
                renderer.cachedGeometryCountForTest(),
            )
            assertEquals("Releasing preview VBOs must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())
            renderer.onDrawFrame(null)
            assertEquals(
                "The first frame after memory pressure must rebuild the low-cost geometry once",
                uploadsBeforeTrim + 1,
                renderer.geometryUploadCountForTest(),
            )
            assertEquals(1, renderer.cachedGeometryCountForTest())
            assertEquals("The rebuilt VBO draw must be valid", GLES30.GL_NO_ERROR, GLES30.glGetError())

            val withoutToolpath = framebufferRgba(framebufferSize, framebufferSize)
            renderer.submit(scene.copy(visibleRoles = setOf(0)))
            renderer.onDrawFrame(null)
            val withToolpath = framebufferRgba(framebufferSize, framebufferSize)
            assertFalse(
                "Instanced toolpath must change the rendered framebuffer",
                withoutToolpath.contentEquals(withToolpath),
            )
            assertEquals(
                "Instanced toolpath framebuffer readback must be valid",
                GLES30.GL_NO_ERROR,
                GLES30.glGetError(),
            )

            val adaptiveRenderer = ToolpathRenderer()
            adaptiveRenderer.submit(scene.copy(detail = PreviewDetail.AUTOMATIC))
            adaptiveRenderer.onSurfaceCreated(null, null)
            adaptiveRenderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            val maximumAutomaticCalibrationFrames =
                ADAPTIVE_PREVIEW_FAST_SAMPLE_COUNT * PreviewDetail.entries.size + 10
            repeat(maximumAutomaticCalibrationFrames) { adaptiveRenderer.onDrawFrame(null) }
            assertEquals(
                "A trivial Preview workload must promote Automatic through measured tiers",
                PreviewDetail.DETAIL,
                adaptiveRenderer.effectiveDetailForTest(),
            )
            assertTrue(
                "Automatic calibration must settle after bounded completed-frame samples",
                adaptiveRenderer.automaticCalibrationSettledForTest(),
            )
            assertTrue(
                "Automatic promotion must retain at most settled and gesture VBOs",
                adaptiveRenderer.cachedGeometryCountForTest() <= 2,
            )
            assertEquals(
                "Automatic GPU completion calibration must leave GLES valid",
                GLES30.GL_NO_ERROR,
                GLES30.glGetError(),
            )
            adaptiveRenderer.releaseGpuGeometryForMemoryPressure()

            var requestedGeometry: ToolpathScene? = null
            val asynchronousRenderer = ToolpathRenderer(
                requestGeometryBuild = { requestedGeometry = it },
            )
            asynchronousRenderer.submit(scene)
            asynchronousRenderer.onSurfaceCreated(null, null)
            asynchronousRenderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            asynchronousRenderer.onDrawFrame(null)
            assertEquals(
                "An asynchronous renderer must not build geometry on its GL thread",
                0,
                asynchronousRenderer.geometryUploadCountForTest(),
            )
            val requested = checkNotNull(requestedGeometry)
            asynchronousRenderer.submitPreparedGeometry(
                requested,
                ToolpathMeshBuilder.build(requested),
            )
            asynchronousRenderer.onDrawFrame(null)
            assertEquals(
                "Prepared CPU geometry must upload exactly once on the GL thread",
                1,
                asynchronousRenderer.geometryUploadCountForTest(),
            )
            requestedGeometry = null
            val retainedFrame = framebufferRgba(framebufferSize, framebufferSize)
            asynchronousRenderer.onDrawFrame(null)
            assertTrue("Refined geometry must be requested in the background", requestedGeometry != null)
            assertEquals(
                "The last compatible GPU frame must remain visible during refinement",
                1,
                asynchronousRenderer.fallbackFrameCountForTest(),
            )
            assertTrue(
                "Background refinement must not clear the visible Preview",
                retainedFrame.contentEquals(framebufferRgba(framebufferSize, framebufferSize)),
            )
            assertEquals(
                "Asynchronous geometry upload must leave GLES valid",
                GLES30.GL_NO_ERROR,
                GLES30.glGetError(),
            )
            asynchronousRenderer.releaseGpuGeometryForMemoryPressure()

            var rendererFailures = 0
            val failingRenderer = ToolpathRenderer(
                reportUnavailable = { rendererFailures += 1 },
                programFactory = { _, _ -> 0 },
            )
            failingRenderer.submit(scene)
            failingRenderer.onSurfaceCreated(null, null)
            failingRenderer.onSurfaceChanged(null, framebufferSize, framebufferSize)
            failingRenderer.onDrawFrame(null)
            assertEquals(
                "A failed depth renderer must request compatibility fallback exactly once",
                1,
                rendererFailures,
            )
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

    private fun framebufferRgba(width: Int, height: Int): ByteArray {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        GLES30.glReadPixels(
            0,
            0,
            width,
            height,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels,
        )
        pixels.position(0)
        return ByteArray(pixels.remaining()).also(pixels::get)
    }

    private fun outerWallBounds(gcode: File): ToolpathBounds {
        val preview = loadGcodePreview(gcode.absolutePath, 0, Int.MAX_VALUE)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
            if (preview.segments[offset + 5].toInt() != 0) return@forEach
            minX = minOf(minX, preview.segments[offset], preview.segments[offset + 2])
            minY = minOf(minY, preview.segments[offset + 1], preview.segments[offset + 3])
            maxX = maxOf(maxX, preview.segments[offset], preview.segments[offset + 2])
            maxY = maxOf(maxY, preview.segments[offset + 1], preview.segments[offset + 3])
        }
        check(minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite()) {
            "No outer-wall extrusion coordinates"
        }
        return ToolpathBounds(minX, minY, maxX, maxY)
    }

    private fun fixtureModel(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val requestedName = InstrumentationRegistry.getArguments().getString("modelName")
        val modelName = requestedName ?: "20mmbox-LF.stl"
        val destination = File(context.filesDir, modelName)
        if (destination.isFile) return destination

        instrumentation.context.assets.open(modelName).use { input ->
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }

    private fun tiltedAutoOrientModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "tilted-auto-orient.stl")
        val facets = mutableListOf<List<TestVertex>>()
        val radians = Math.toRadians(37.0)
        val cosAngle = cos(radians).toFloat()
        val sinAngle = sin(radians).toFloat()

        fun vertex(x: Float, y: Float, z: Float): TestVertex {
            val rotatedY = y * cosAngle - z * sinAngle
            val rotatedZ = y * sinAngle + z * cosAngle
            return TestVertex(x, rotatedY, rotatedZ + 20f)
        }
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }

        val x0 = -30f
        val x1 = 30f
        val y0 = -15f
        val y1 = 15f
        val z0 = -5f
        val z1 = 5f
        quad(vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1), vertex(x0, y0, z1))
        quad(vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1), vertex(x1, y0, z1))
        quad(vertex(x1, y1, z0), vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1))
        quad(vertex(x0, y1, z0), vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1))
        quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
        quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid tilted_auto_orient")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid tilted_auto_orient")
        }
        return destination
    }

    private fun hollowTubeModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "hollow-tube-topology.stl")
        val facets = mutableListOf<List<TestVertex>>()

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }

        val z0 = 0f
        val z1 = 20f
        val low = 0f
        val high = 30f
        val holeLow = 10f
        val holeHigh = 20f

        // Outer walls, wound toward the air outside the part.
        quad(vertex(low, low, z0), vertex(high, low, z0), vertex(high, low, z1), vertex(low, low, z1))
        quad(vertex(high, low, z0), vertex(high, high, z0), vertex(high, high, z1), vertex(high, low, z1))
        quad(vertex(high, high, z0), vertex(low, high, z0), vertex(low, high, z1), vertex(high, high, z1))
        quad(vertex(low, high, z0), vertex(low, low, z0), vertex(low, low, z1), vertex(low, high, z1))

        // Cavity walls use the opposite winding, toward the empty center.
        quad(vertex(holeLow, holeLow, z0), vertex(holeLow, holeLow, z1), vertex(holeHigh, holeLow, z1), vertex(holeHigh, holeLow, z0))
        quad(vertex(holeHigh, holeLow, z0), vertex(holeHigh, holeLow, z1), vertex(holeHigh, holeHigh, z1), vertex(holeHigh, holeHigh, z0))
        quad(vertex(holeHigh, holeHigh, z0), vertex(holeHigh, holeHigh, z1), vertex(holeLow, holeHigh, z1), vertex(holeLow, holeHigh, z0))
        quad(vertex(holeLow, holeHigh, z0), vertex(holeLow, holeHigh, z1), vertex(holeLow, holeLow, z1), vertex(holeLow, holeLow, z0))

        // Close the annulus at the top and bottom without filling the cavity.
        val strips = listOf(
            arrayOf(low, high, low, holeLow),
            arrayOf(low, high, holeHigh, high),
            arrayOf(low, holeLow, holeLow, holeHigh),
            arrayOf(holeHigh, high, holeLow, holeHigh),
        )
        strips.forEach { (x0, x1, y0, y1) ->
            quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))
        }

        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid hollow_tube")
            facets.forEach { triangle ->
                writer.appendLine("  facet normal 0 0 0")
                writer.appendLine("    outer loop")
                triangle.forEach { point ->
                    writer.appendLine("      vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("    endloop")
                writer.appendLine("  endfacet")
            }
            writer.appendLine("endsolid hollow_tube")
        }
        return destination
    }

    private fun cylinderModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "scarf-seam-cylinder.stl")
        val facets = mutableListOf<List<TestVertex>>()
        val segments = 64
        val radius = 15f
        val height = 20f
        val bottomCenter = TestVertex(0f, 0f, 0f)
        val topCenter = TestVertex(0f, 0f, height)
        val bottom = Array(segments) { index ->
            val angle = 2.0 * Math.PI * index / segments
            TestVertex((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), 0f)
        }
        val top = Array(segments) { index -> bottom[index].copy(z = height) }

        repeat(segments) { index ->
            val next = (index + 1) % segments
            facets += listOf(bottom[index], bottom[next], top[next])
            facets += listOf(bottom[index], top[next], top[index])
            facets += listOf(bottomCenter, bottom[next], bottom[index])
            facets += listOf(topCenter, top[index], top[next])
        }
        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid scarf_seam_cylinder")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid scarf_seam_cylinder")
        }
        return destination
    }

    private fun supportPaintOverhangModel(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File(context.cacheDir, "support-paint-overhang.stl")
        val facets = mutableListOf<List<TestVertex>>()

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(a: TestVertex, b: TestVertex, c: TestVertex, d: TestVertex) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }
        fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float) {
            quad(vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y0, z1), vertex(x0, y0, z1))
            quad(vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x1, y1, z1), vertex(x1, y0, z1))
            quad(vertex(x1, y1, z0), vertex(x0, y1, z0), vertex(x0, y1, z1), vertex(x1, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x0, y0, z0), vertex(x0, y0, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1))
            quad(vertex(x0, y1, z0), vertex(x1, y1, z0), vertex(x1, y0, z0), vertex(x0, y0, z0))
        }

        box(8f, 12f, 8f, 12f, 0f, 18f)
        box(0f, 20f, 0f, 20f, 18f, 22f)
        destination.bufferedWriter().use { writer ->
            writer.appendLine("solid support_paint_overhang")
            facets.forEach { triangle ->
                writer.appendLine("facet normal 0 0 0")
                writer.appendLine("outer loop")
                triangle.forEach { point ->
                    writer.appendLine("vertex ${point.x} ${point.y} ${point.z}")
                }
                writer.appendLine("endloop")
                writer.appendLine("endfacet")
            }
            writer.appendLine("endsolid support_paint_overhang")
        }
        return destination
    }

    private fun meshCorpus(): List<MeshCorpusEntry> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        fun vertex(x: Float, y: Float, z: Float) = TestVertex(x, y, z)
        fun quad(
            facets: MutableList<List<TestVertex>>,
            a: TestVertex,
            b: TestVertex,
            c: TestVertex,
            d: TestVertex,
        ) {
            facets += listOf(a, b, c)
            facets += listOf(a, c, d)
        }
        fun cube(low: Float, high: Float): MutableList<List<TestVertex>> {
            val facets = mutableListOf<List<TestVertex>>()
            val z0 = 0f
            val z1 = high - low
            quad(facets, vertex(low, low, z0), vertex(high, low, z0), vertex(high, low, z1), vertex(low, low, z1))
            quad(facets, vertex(high, low, z0), vertex(high, high, z0), vertex(high, high, z1), vertex(high, low, z1))
            quad(facets, vertex(high, high, z0), vertex(low, high, z0), vertex(low, high, z1), vertex(high, high, z1))
            quad(facets, vertex(low, high, z0), vertex(low, low, z0), vertex(low, low, z1), vertex(low, high, z1))
            quad(facets, vertex(low, low, z1), vertex(high, low, z1), vertex(high, high, z1), vertex(low, high, z1))
            quad(facets, vertex(low, high, z0), vertex(high, high, z0), vertex(high, low, z0), vertex(low, low, z0))
            return facets
        }
        fun write(name: String, facets: List<List<TestVertex>>): File {
            val output = File(context.cacheDir, "mesh-corpus-$name.stl")
            output.bufferedWriter().use { writer ->
                writer.appendLine("solid $name")
                facets.forEach { triangle ->
                    writer.appendLine("  facet normal 0 0 0")
                    writer.appendLine("    outer loop")
                    triangle.forEach { point ->
                        writer.appendLine("      vertex ${point.x} ${point.y} ${point.z}")
                    }
                    writer.appendLine("    endloop")
                    writer.appendLine("  endfacet")
                }
                writer.appendLine("endsolid $name")
            }
            return output
        }

        val closedCube = cube(0f, 20f)
        val openTop = closedCube.filterIndexed { index, _ -> index !in 8..9 }
        val reversedFacet = closedCube.mapIndexed { index, triangle ->
            if (index == 3) triangle.asReversed() else triangle
        }
        val duplicateFacet = closedCube.toMutableList().apply { add(closedCube.first()) }
        val degenerateAttachment = closedCube + listOf(
            listOf(vertex(5f, 5f, 5f), vertex(5f, 5f, 5f), vertex(10f, 5f, 5f)),
        )
        val intersectingShells = cube(0f, 20f) + cube(10f, 30f)
        val degenerateOnly = listOf(
            listOf(vertex(0f, 0f, 0f), vertex(0f, 0f, 0f), vertex(0f, 0f, 0f)),
        )

        return listOf(
            MeshCorpusEntry("open-top", write("open-top", openTop), mustSlice = true),
            MeshCorpusEntry("reversed-facet", write("reversed-facet", reversedFacet), mustSlice = true),
            MeshCorpusEntry("duplicate-facet", write("duplicate-facet", duplicateFacet), mustSlice = true),
            MeshCorpusEntry(
                "degenerate-attachment",
                write("degenerate-attachment", degenerateAttachment),
                mustSlice = true,
            ),
            MeshCorpusEntry(
                "intersecting-shells",
                write("intersecting-shells", intersectingShells),
                mustSlice = true,
            ),
            MeshCorpusEntry("degenerate-only", write("degenerate-only", degenerateOnly), mustSlice = false),
        )
    }

    @Test
    fun projectSurvivesStoreRecreationAndNativeReinspection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "project-store-${System.nanoTime()}")
        val inspector: (File) -> ModelInfo = { model ->
            inspectModel(model.absolutePath)
        }
        try {
            val firstStore = ProjectStore(root, inspector)
            val destination = firstStore.createModelDestination("restored duck.stl")
            fixtureModel().copyTo(destination)
            val savedOptions = SliceOptions()
                .selectPrinter(PrinterProfile.U1_06)
                .selectFilament(FilamentProfile.PETG)
                .selectQuality(QualityProfile.FINE_06)
                .copy(
                    nozzleTemp = 247,
                    fillDensity = 0.23f,
                    outerWallLineWidth = 0.63f,
                    innerWallLineWidth = 0.69f,
                    topSurfaceLineWidth = 0.57f,
                    sparseInfillLineWidth = 0.72f,
                    internalSolidInfillLineWidth = 0.66f,
                    supportLineWidth = 0.58f,
                    innerWallSpeed = 175f,
                    sparseInfillSpeed = 205f,
                    internalSolidInfillSpeed = 165f,
                    topSurfaceSpeed = 95f,
                    supportSpeed = 80f,
                    bridgeSpeed = 42f,
                    gapInfillSpeed = 132f,
                    firstLayerInfillSpeed = 62f,
                    supportInterfaceSpeed = 53f,
                    printFlowRatio = 0.94f,
                    bridgeFlowRatio = 0.91f,
                    internalBridgeFlowRatio = 0.96f,
                    topSurfaceFlowRatio = 0.97f,
                    bottomSurfaceFlowRatio = 0.98f,
                    topShellThickness = 0.8f,
                    bottomShellThickness = 0.7f,
                    supportInterfaceTopLayers = 4,
                    supportInterfaceBottomLayers = 2,
                    supportInterfaceSpacing = 0.24f,
                    supportBottomInterfaceSpacing = 0.28f,
                    supportTopZDistance = 0.18f,
                    supportBottomZDistance = 0.22f,
                    supportObjectXYDistance = 0.4f,
                    initialLayerLineWidth = 0.74f,
                    defaultAcceleration = 4_200f,
                    outerWallAcceleration = 2_100f,
                    innerWallAcceleration = 3_800f,
                    topSurfaceAcceleration = 1_300f,
                    travelAcceleration = 4_700f,
                    firstLayerAcceleration = 650f,
                    wallGenerator = "classic",
                    wallSequence = "outer-inner",
                    gcodeFlavor = "klipper",
                    maxAccelerationTravel = 4_700f,
                )
            firstStore.save(
                ProjectSnapshot(
                    objects = listOf(
                        ProjectObject(
                            id = "restored-object",
                            model = inspector(destination).copy(fileName = "restored duck.stl"),
                            transform = ModelTransform(
                                offsetXmm = 18f,
                                offsetYmm = -11f,
                                rotationZdeg = 30f,
                                scale = 1.4f,
                                scaleY = 0.9f,
                                scaleZ = 1.8f,
                            ),
                        ),
                    ),
                    selectedObjectId = "restored-object",
                ),
                savedOptions,
            )

            val restoredDocument = ProjectStore(root, inspector).loadProject()
            val restored = restoredDocument.snapshot

            assertEquals("restored-object", restored.selectedObjectId)
            assertEquals("restored duck.stl", restored.selectedObject!!.model.fileName)
            assertEquals(18f, restored.selectedObject!!.transform.offsetXmm)
            assertEquals(-11f, restored.selectedObject!!.transform.offsetYmm)
            assertEquals(30f, restored.selectedObject!!.transform.rotationZdeg)
            assertEquals(1.4f, restored.selectedObject!!.transform.scale)
            assertEquals(0.9f, restored.selectedObject!!.transform.scaleY)
            assertEquals(1.8f, restored.selectedObject!!.transform.scaleZ)
            assertTrue(restored.selectedObject!!.model.previewTriangles.isNotEmpty())
            assertEquals(247, restoredDocument.sliceOptions?.nozzleTemp)
            assertEquals(0.23f, restoredDocument.sliceOptions?.fillDensity)
            assertEquals(0.63f, restoredDocument.sliceOptions?.outerWallLineWidth)
            assertEquals(0.69f, restoredDocument.sliceOptions?.innerWallLineWidth)
            assertEquals(0.57f, restoredDocument.sliceOptions?.topSurfaceLineWidth)
            assertEquals(0.72f, restoredDocument.sliceOptions?.sparseInfillLineWidth)
            assertEquals(0.66f, restoredDocument.sliceOptions?.internalSolidInfillLineWidth)
            assertEquals(0.58f, restoredDocument.sliceOptions?.supportLineWidth)
            assertEquals(175f, restoredDocument.sliceOptions?.innerWallSpeed)
            assertEquals(205f, restoredDocument.sliceOptions?.sparseInfillSpeed)
            assertEquals(165f, restoredDocument.sliceOptions?.internalSolidInfillSpeed)
            assertEquals(95f, restoredDocument.sliceOptions?.topSurfaceSpeed)
            assertEquals(80f, restoredDocument.sliceOptions?.supportSpeed)
            assertEquals(42f, restoredDocument.sliceOptions?.bridgeSpeed)
            assertEquals(132f, restoredDocument.sliceOptions?.gapInfillSpeed)
            assertEquals(62f, restoredDocument.sliceOptions?.firstLayerInfillSpeed)
            assertEquals(53f, restoredDocument.sliceOptions?.supportInterfaceSpeed)
            assertEquals(0.94f, restoredDocument.sliceOptions?.printFlowRatio)
            assertEquals(0.91f, restoredDocument.sliceOptions?.bridgeFlowRatio)
            assertEquals(0.8f, restoredDocument.sliceOptions?.topShellThickness)
            assertEquals(4, restoredDocument.sliceOptions?.supportInterfaceTopLayers)
            assertEquals(0.24f, restoredDocument.sliceOptions?.supportInterfaceSpacing)
            assertEquals(0.74f, restoredDocument.sliceOptions?.initialLayerLineWidth)
            assertEquals(4_200f, restoredDocument.sliceOptions?.defaultAcceleration)
            assertEquals(2_100f, restoredDocument.sliceOptions?.outerWallAcceleration)
            assertEquals(3_800f, restoredDocument.sliceOptions?.innerWallAcceleration)
            assertEquals(1_300f, restoredDocument.sliceOptions?.topSurfaceAcceleration)
            assertEquals(4_700f, restoredDocument.sliceOptions?.travelAcceleration)
            assertEquals(650f, restoredDocument.sliceOptions?.firstLayerAcceleration)
            assertEquals("classic", restoredDocument.sliceOptions?.wallGenerator)
            assertEquals("outer-inner", restoredDocument.sliceOptions?.wallSequence)
            assertEquals("klipper", restoredDocument.sliceOptions?.gcodeFlavor)
            assertEquals(4_700f, restoredDocument.sliceOptions?.maxAccelerationTravel)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectArchiveRoundTripReinspectsAndSlicesOnArm64() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceRoot = File(context.cacheDir, "archive-source-${System.nanoTime()}")
        val destinationRoot = File(context.filesDir, ProjectStore.PROJECT_DIRECTORY)
        val inspector: (File) -> ModelInfo = { model ->
            inspectModel(model.absolutePath)
        }
        var gcode: File? = null
        try {
            destinationRoot.deleteRecursively()
            val source = ProjectStore(sourceRoot, inspector)
            val storedModel = source.createModelDestination("archive-box.stl")
            fixtureModel().copyTo(storedModel)
            val inspected = inspector(storedModel).copy(fileName = "archive-box.stl")
            val transform = ModelTransform(offsetXmm = 8f, offsetYmm = -6f, rotationZdeg = 18f)
            val paint = SupportPaint().paint(0, SupportPaintState.BLOCK)
            val options = SliceOptions()
                .selectQuality(QualityProfile.DRAFT)
                .copy(fillDensity = 0.18f, supportEnabled = true)
            val snapshot = ProjectSnapshot(
                objects = listOf(ProjectObject("archive-object", inspected, transform, paint)),
                selectedObjectId = "archive-object",
            )
            val archive = ByteArrayOutputStream().also { output ->
                source.exportArchive(snapshot, options, output)
            }.toByteArray()

            val imported = ProjectStore(context).importArchive(
                ByteArrayInputStream(archive),
            )
            val restoredObject = imported.snapshot.selectedObject
            assertEquals("archive-object", restoredObject?.id)
            assertEquals(transform, restoredObject?.transform)
            assertEquals(paint, restoredObject?.supportPaint)
            assertEquals(0.18f, imported.sliceOptions?.fillDensity)
            assertTrue(restoredObject?.model?.previewTriangles?.isNotEmpty() == true)

            val outcome = OnDeviceSlicer.slice(
                imported.snapshot.objects,
                requireNotNull(imported.sliceOptions),
            )
            gcode = outcome.output
            assertTrue("A restored project must produce retained G-code", outcome.output.length() > 1_000L)
            assertTrue("A restored project must retain a finite print estimate", outcome.estimatedSeconds.isFinite())
            assertTrue("A restored project must retain a finite filament estimate", outcome.filamentMm.isFinite())
        } finally {
            gcode?.delete()
            sourceRoot.deleteRecursively()
            destinationRoot.deleteRecursively()
        }
    }

    @Test
    fun userProfilesRoundTripInPrivateStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "profile-store-test").apply { mkdirs() }
        val file = File(directory, "profiles.json").also { it.delete() }
        val store = ProfileStore(file)
        val edited = SliceOptions()
            .selectPrinter(PrinterProfile.U1_06)
            .selectFilament(FilamentProfile.PETG.copy(
                retractLength = 1.2f,
                retractSpeed = 41f,
                deretractSpeed = 36f,
                retractionMinimumTravel = 2.3f,
                retractWhenChangingLayer = true,
                wipeWhileRetracting = true,
                wipeDistance = 2.6f,
                retractBeforeWipe = 64f,
                retractRestartExtra = 0.07f,
                zHop = 0.65f,
                zHopType = "spiral",
            ))
            .copy(nozzleTemp = 248, firstLayerNozzleTemp = 253)
            .selectQuality(QualityProfile.FINE_06)
            .copy(fillDensity = 0.22f, supportEnabled = true)
            .copy(
                fillPattern = "crosshatch",
                topSurfacePattern = "monotonic",
                bottomSurfacePattern = "concentric",
                internalSolidInfillPattern = "rectilinear",
                topSolidLayers = 7,
                travelSpeed = 420f,
                fanMinSpeed = 40,
                pressureAdvanceEnabled = true,
                pressureAdvance = 0.035f,
                outerWallLineWidth = 0.64f,
                innerWallLineWidth = 0.68f,
                topSurfaceLineWidth = 0.56f,
                sparseInfillLineWidth = 0.72f,
                internalSolidInfillLineWidth = 0.66f,
                supportLineWidth = 0.58f,
                innerWallSpeed = 180f,
                sparseInfillSpeed = 220f,
                internalSolidInfillSpeed = 170f,
                topSurfaceSpeed = 100f,
                supportSpeed = 85f,
                bridgeSpeed = 44f,
                gapInfillSpeed = 134f,
                firstLayerInfillSpeed = 64f,
                supportInterfaceSpeed = 54f,
                internalBridgeSpeed = 164f,
                internalBridgeSpeedPercent = true,
                overhangSpeedEnabled = false,
                overhangSpeed1 = 76f,
                overhangSpeed1Percent = true,
                bridgeFlowRatio = 0.92f,
                internalBridgeFlowRatio = 0.95f,
                topSurfaceFlowRatio = 0.97f,
                bottomSurfaceFlowRatio = 0.98f,
                bridgeDensity = 88f,
                internalBridgeDensity = 74f,
                bridgeAngle = 18f,
                internalBridgeAngle = 104f,
                bridgeNoSupport = true,
                thickBridges = true,
                thickInternalBridges = false,
                extraBridgeLayer = "external_bridge_only",
                internalBridgeFilter = "limited",
                topShellThickness = 0.85f,
                bottomShellThickness = 0.75f,
                supportInterfaceTopLayers = 4,
                supportInterfaceBottomLayers = 2,
                supportInterfaceSpacing = 0.25f,
                supportBottomInterfaceSpacing = 0.3f,
                supportTopZDistance = 0.18f,
                supportBottomZDistance = 0.22f,
                supportObjectXYDistance = 0.4f,
                supportBasePattern = "rectilinear-grid",
                supportInterfacePattern = "rectilinear_interlaced",
                supportStyle = "snug",
                supportCoverage = SupportCoverageSettings(
                    onBuildPlateOnly = true,
                    criticalRegionsOnly = true,
                    removeSmallOverhangs = false,
                ),
                supportAdvanced = SupportAdvancedSettings(
                    patternAngle = 45f,
                    thresholdOverlap = 37f,
                    thresholdOverlapPercent = true,
                    objectFirstLayerGap = 0.36f,
                    avoidInterfaceFilamentForBase = false,
                    ironingEnabled = true,
                    ironingPattern = "concentric",
                    ironingFlow = 16f,
                    ironingSpacing = 0.19f,
                ),
                supportFilament = 1,
                supportInterfaceFilament = 1,
                wipeTowerEnabled = true,
                wipeTowerWidth = 46f,
                multiMaterial = MultiMaterialSettings(
                    primeVolume = 58f,
                    primeTowerBrimWidth = 5.5f,
                    wipeTowerNoSparseLayers = true,
                    wipeTowerRotationAngle = 73f,
                    wipeTowerBridging = 12.5f,
                    wipeTowerExtraSpacing = 145f,
                    wipeTowerExtraFlow = 118f,
                    wipeTowerMaxPurgeSpeed = 137f,
                    wipeTowerWallType = "rib",
                    wipeTowerConeAngle = 42f,
                    wipeTowerExtraRibLength = 9.5f,
                    wipeTowerRibWidth = 11f,
                    wipeTowerFilletWall = false,
                    singleExtruderMultiMaterialPriming = true,
                    flushIntoInfill = true,
                    flushIntoSupport = false,
                    flushIntoObjects = true,
                    oozePrevention = true,
                    standbyTemperatureDelta = -42,
                    interfaceShells = true,
                ),
                gcodeSettings = GcodeSettings(
                    arcFitting = true,
                    labelObjects = false,
                    excludeObjects = true,
                    initialLayerTravelSpeed = 35f,
                    initialLayerTravelSpeedPercent = true,
                    accelToDecelEnabled = false,
                    accelToDecelFactor = 27f,
                ),
                infillFirst = true,
                infillWallOverlap = 18f,
                topBottomInfillWallOverlap = 32f,
                infillCombination = true,
                infillCombinationMaxLayerHeight = 0.38f,
                infillCombinationMaxLayerHeightPercent = false,
                infillDirection = 36f,
                solidInfillDirection = 124f,
                alignInfillDirectionToModel = true,
                minimumSparseInfillArea = 41f,
                infillAnchor = 322f,
                infillAnchorPercent = true,
                infillAnchorMax = 18f,
                infillAnchorMaxPercent = false,
                gapFillTarget = "topbottom",
                filterOutGapFill = 0.8f,
                reduceCrossingWall = true,
                maxTravelDetourDistance = 154f,
                maxTravelDetourDistancePercent = true,
                reduceInfillRetraction = true,
                initialLayerLineWidth = 0.73f,
                smallPerimeterSpeed = 78f,
                smallPerimeterSpeedPercent = false,
                smallPerimeterThreshold = 6.25f,
                slowdownForCurledPerimeters = false,
                resolution = 0.024f,
                seamPosition = "nearest",
                staggeredInnerSeams = true,
                seamGap = 3.25f,
                seamGapPercent = true,
                wipeBeforeExternalLoop = true,
                wipeOnLoops = true,
                roleBasedWipeSpeed = false,
                wipeSpeed = 67f,
                wipeSpeedPercent = false,
                ironing = IroningSettings(
                    type = "top",
                    pattern = "concentric",
                    flow = 12f,
                    spacing = 0.16f,
                    inset = 0.36f,
                    speed = 26f,
                    angle = 122f,
                ),
                defaultAcceleration = 4_000f,
                outerWallAcceleration = 2_000f,
                innerWallAcceleration = 3_500f,
                topSurfaceAcceleration = 1_200f,
                travelAcceleration = 4_500f,
                firstLayerAcceleration = 600f,
                bridgeAcceleration = 48f,
                bridgeAccelerationPercent = true,
                sparseInfillAcceleration = 4_322f,
                sparseInfillAccelerationPercent = false,
                internalSolidInfillAcceleration = 84f,
                internalSolidInfillAccelerationPercent = true,
                wallGenerator = "classic",
                wallTransitionLength = 140f,
                wallTransitionFilterDeviation = 32f,
                wallTransitionAngle = 23f,
                wallDistributionCount = 3,
                minimumFeatureSize = 21f,
                precision = PrecisionSettings(
                    minimumWallWidth = 72f,
                    firstLayerMinimumWallWidth = 118f,
                ),
                minimumWallLengthFactor = 0.8f,
                wallSequence = "outer-inner",
                wallDirection = "cw",
                detectThinWalls = true,
                onlyOneWallOnTop = false,
                minWidthTopSurface = 290f,
                minWidthTopSurfacePercent = true,
                onlyOneWallFirstLayer = true,
                extraPerimetersOnOverhangs = true,
                overhangReverse = true,
                overhangReverseInternalOnly = true,
                overhangReverseThreshold = 0.9f,
                overhangReverseThresholdPercent = false,
                counterboreHoleBridging = "partiallybridge",
                alternateExtraWall = true,
                ensureVerticalShellThickness = "ensure_critical_only",
                detectNarrowInternalSolidInfill = false,
                xyHoleCompensation = 0.12f,
                xyContourCompensation = -0.08f,
                elephantFootCompensation = 0.24f,
                elephantFootCompensationLayers = 4,
                maxBridgeLength = 27f,
                preciseOuterWalls = true,
                skirtLoops = 3,
                skirtDistance = 7.5f,
                skirtHeight = 4,
                skirtSpeed = 58f,
                minimumSkirtLength = 13f,
                draftShield = "enabled",
                brimType = "outer_and_inner",
                brimWidth = 6.5f,
                brimObjectGap = 0.16f,
                raftLayers = 3,
                raftContactDistance = 0.14f,
                raftExpansion = 2.6f,
                raftFirstLayerDensity = 87f,
                raftFirstLayerExpansion = 3.6f,
                gcodeFlavor = "marlin2",
                maxSpeedX = 320f,
                maxAccelerationX = 4_200f,
                maxAccelerationTravel = 5_000f,
                maxJerkX = 7f,
            )

        val printer = store.savePrinter("Workshop U1", edited)
        val filament = store.saveFilament("My PETG", edited)
        val slicing = store.saveSlicing("Fine supports", edited)
        val restored = ProfileStore(file).load()

        assertEquals(printer, restored.printers.last())
        assertEquals(filament, restored.filaments.last())
        assertEquals(slicing, restored.slicing.last())
        assertEquals(248, restored.filaments.last().nozzleTemp)
        assertEquals(253, restored.filaments.last().firstLayerNozzleTemp)
        assertEquals(0.22f, restored.slicing.last().fillDensity)
        assertTrue(restored.slicing.last().supportEnabled)
        assertEquals("crosshatch", restored.slicing.last().fillPattern)
        assertEquals("monotonic", restored.slicing.last().topSurfacePattern)
        assertEquals("concentric", restored.slicing.last().bottomSurfacePattern)
        assertEquals("rectilinear", restored.slicing.last().internalSolidInfillPattern)
        assertEquals(false, restored.slicing.last().overhangSpeedEnabled)
        assertEquals(76f, restored.slicing.last().overhangSpeed1)
        assertTrue(restored.slicing.last().overhangSpeed1Percent)
        assertEquals("rectilinear-grid", restored.slicing.last().supportBasePattern)
        assertEquals("rectilinear_interlaced", restored.slicing.last().supportInterfacePattern)
        assertEquals("snug", restored.slicing.last().supportStyle)
        assertEquals(
            SupportCoverageSettings(
                onBuildPlateOnly = true,
                criticalRegionsOnly = true,
                removeSmallOverhangs = false,
            ),
            restored.slicing.last().supportCoverage,
        )
        assertEquals(45f, restored.slicing.last().supportAdvanced.patternAngle)
        assertEquals(37f, restored.slicing.last().supportAdvanced.thresholdOverlap)
        assertTrue(restored.slicing.last().supportAdvanced.thresholdOverlapPercent)
        assertTrue(restored.slicing.last().supportAdvanced.ironingEnabled)
        assertEquals("concentric", restored.slicing.last().supportAdvanced.ironingPattern)
        assertEquals(1, restored.slicing.last().supportFilament)
        assertEquals(1, restored.slicing.last().supportInterfaceFilament)
        assertTrue(restored.slicing.last().wipeTowerEnabled)
        assertEquals(46f, restored.slicing.last().wipeTowerWidth)
        assertEquals(58f, restored.slicing.last().multiMaterial.primeVolume)
        assertEquals(5.5f, restored.slicing.last().multiMaterial.primeTowerBrimWidth)
        assertTrue(restored.slicing.last().multiMaterial.wipeTowerNoSparseLayers)
        assertEquals(73f, restored.slicing.last().multiMaterial.wipeTowerRotationAngle)
        assertEquals(12.5f, restored.slicing.last().multiMaterial.wipeTowerBridging)
        assertEquals(145f, restored.slicing.last().multiMaterial.wipeTowerExtraSpacing)
        assertEquals(118f, restored.slicing.last().multiMaterial.wipeTowerExtraFlow)
        assertEquals(137f, restored.slicing.last().multiMaterial.wipeTowerMaxPurgeSpeed)
        assertEquals("rib", restored.slicing.last().multiMaterial.wipeTowerWallType)
        assertEquals(42f, restored.slicing.last().multiMaterial.wipeTowerConeAngle)
        assertEquals(9.5f, restored.slicing.last().multiMaterial.wipeTowerExtraRibLength)
        assertEquals(11f, restored.slicing.last().multiMaterial.wipeTowerRibWidth)
        assertFalse(restored.slicing.last().multiMaterial.wipeTowerFilletWall)
        assertTrue(restored.slicing.last().multiMaterial.singleExtruderMultiMaterialPriming)
        assertTrue(restored.slicing.last().multiMaterial.flushIntoInfill)
        assertFalse(restored.slicing.last().multiMaterial.flushIntoSupport)
        assertTrue(restored.slicing.last().multiMaterial.flushIntoObjects)
        assertTrue(restored.slicing.last().multiMaterial.oozePrevention)
        assertEquals(-42, restored.slicing.last().multiMaterial.standbyTemperatureDelta)
        assertTrue(restored.slicing.last().multiMaterial.interfaceShells)
        assertTrue(restored.slicing.last().gcodeSettings.arcFitting)
        assertFalse(restored.slicing.last().gcodeSettings.labelObjects)
        assertTrue(restored.slicing.last().gcodeSettings.excludeObjects)
        assertEquals(35f, restored.slicing.last().gcodeSettings.initialLayerTravelSpeed)
        assertTrue(restored.slicing.last().gcodeSettings.initialLayerTravelSpeedPercent)
        assertFalse(restored.slicing.last().gcodeSettings.accelToDecelEnabled)
        assertEquals(27f, restored.slicing.last().gcodeSettings.accelToDecelFactor)
        assertEquals("nearest", restored.slicing.last().seamPosition)
        assertEquals(
            IroningSettings(
                type = "top",
                pattern = "concentric",
                flow = 12f,
                spacing = 0.16f,
                inset = 0.36f,
                speed = 26f,
                angle = 122f,
            ),
            restored.slicing.last().ironing,
        )
        assertEquals(164f, restored.slicing.last().internalBridgeSpeed)
        assertTrue(restored.slicing.last().internalBridgeSpeedPercent)
        assertTrue(restored.slicing.last().infillFirst)
        assertEquals(18f, restored.slicing.last().infillWallOverlap)
        assertEquals(32f, restored.slicing.last().topBottomInfillWallOverlap)
        assertTrue(restored.slicing.last().infillCombination)
        assertEquals(0.38f, restored.slicing.last().infillCombinationMaxLayerHeight)
        assertEquals(false, restored.slicing.last().infillCombinationMaxLayerHeightPercent)
        assertEquals(36f, restored.slicing.last().infillDirection)
        assertEquals(124f, restored.slicing.last().solidInfillDirection)
        assertTrue(restored.slicing.last().alignInfillDirectionToModel)
        assertEquals(41f, restored.slicing.last().minimumSparseInfillArea)
        assertEquals(322f, restored.slicing.last().infillAnchor)
        assertTrue(restored.slicing.last().infillAnchorPercent)
        assertEquals(18f, restored.slicing.last().infillAnchorMax)
        assertEquals(false, restored.slicing.last().infillAnchorMaxPercent)
        assertEquals("topbottom", restored.slicing.last().gapFillTarget)
        assertEquals(0.8f, restored.slicing.last().filterOutGapFill)
        assertTrue(restored.slicing.last().reduceCrossingWall)
        assertEquals(154f, restored.slicing.last().maxTravelDetourDistance)
        assertTrue(restored.slicing.last().maxTravelDetourDistancePercent)
        assertTrue(restored.slicing.last().reduceInfillRetraction)
        assertEquals(88f, restored.slicing.last().bridgeDensity)
        assertEquals(74f, restored.slicing.last().internalBridgeDensity)
        assertEquals(18f, restored.slicing.last().bridgeAngle)
        assertEquals(104f, restored.slicing.last().internalBridgeAngle)
        assertTrue(restored.slicing.last().bridgeNoSupport)
        assertTrue(restored.slicing.last().thickBridges)
        assertEquals(false, restored.slicing.last().thickInternalBridges)
        assertEquals("external_bridge_only", restored.slicing.last().extraBridgeLayer)
        assertEquals("limited", restored.slicing.last().internalBridgeFilter)
        assertEquals(48f, restored.slicing.last().bridgeAcceleration)
        assertTrue(restored.slicing.last().bridgeAccelerationPercent)
        assertEquals(4_322f, restored.slicing.last().sparseInfillAcceleration)
        assertEquals(false, restored.slicing.last().sparseInfillAccelerationPercent)
        assertEquals(84f, restored.slicing.last().internalSolidInfillAcceleration)
        assertTrue(restored.slicing.last().internalSolidInfillAccelerationPercent)
        assertEquals(7, restored.slicing.last().topSolidLayers)
        assertEquals(420f, restored.slicing.last().travelSpeed)
        assertEquals(1.2f, restored.filaments.last().retractLength)
        assertEquals(41f, restored.filaments.last().retractSpeed)
        assertEquals(36f, restored.filaments.last().deretractSpeed)
        assertEquals(2.3f, restored.filaments.last().retractionMinimumTravel)
        assertEquals(true, restored.filaments.last().retractWhenChangingLayer)
        assertEquals(true, restored.filaments.last().wipeWhileRetracting)
        assertEquals(2.6f, restored.filaments.last().wipeDistance)
        assertEquals(64f, restored.filaments.last().retractBeforeWipe)
        assertEquals(0.07f, restored.filaments.last().retractRestartExtra)
        assertEquals(0.65f, restored.filaments.last().zHop)
        assertEquals("spiral", restored.filaments.last().zHopType)
        assertEquals(40, restored.filaments.last().fanMinSpeed)
        assertTrue(restored.filaments.last().pressureAdvanceEnabled)
        assertEquals(0.035f, restored.filaments.last().pressureAdvance)
        assertEquals(0.64f, restored.slicing.last().outerWallLineWidth)
        assertEquals(0.68f, restored.slicing.last().innerWallLineWidth)
        assertEquals(0.56f, restored.slicing.last().topSurfaceLineWidth)
        assertEquals(0.72f, restored.slicing.last().sparseInfillLineWidth)
        assertEquals(0.66f, restored.slicing.last().internalSolidInfillLineWidth)
        assertEquals(0.58f, restored.slicing.last().supportLineWidth)
        assertEquals(180f, restored.slicing.last().innerWallSpeed)
        assertEquals(220f, restored.slicing.last().sparseInfillSpeed)
        assertEquals(170f, restored.slicing.last().internalSolidInfillSpeed)
        assertEquals(100f, restored.slicing.last().topSurfaceSpeed)
        assertEquals(85f, restored.slicing.last().supportSpeed)
        assertEquals(44f, restored.slicing.last().bridgeSpeed)
        assertEquals(134f, restored.slicing.last().gapInfillSpeed)
        assertEquals(64f, restored.slicing.last().firstLayerInfillSpeed)
        assertEquals(54f, restored.slicing.last().supportInterfaceSpeed)
        assertEquals(0.92f, restored.slicing.last().bridgeFlowRatio)
        assertEquals(0.85f, restored.slicing.last().topShellThickness)
        assertEquals(4, restored.slicing.last().supportInterfaceTopLayers)
        assertEquals(0.25f, restored.slicing.last().supportInterfaceSpacing)
        assertEquals(0.73f, restored.slicing.last().initialLayerLineWidth)
        assertEquals(4_000f, restored.slicing.last().defaultAcceleration)
        assertEquals(2_000f, restored.slicing.last().outerWallAcceleration)
        assertEquals(3_500f, restored.slicing.last().innerWallAcceleration)
        assertEquals(1_200f, restored.slicing.last().topSurfaceAcceleration)
        assertEquals(4_500f, restored.slicing.last().travelAcceleration)
        assertEquals(600f, restored.slicing.last().firstLayerAcceleration)
        assertEquals("classic", restored.slicing.last().wallGenerator)
        assertEquals(140f, restored.slicing.last().wallTransitionLength)
        assertEquals(32f, restored.slicing.last().wallTransitionFilterDeviation)
        assertEquals(23f, restored.slicing.last().wallTransitionAngle)
        assertEquals(3, restored.slicing.last().wallDistributionCount)
        assertEquals(21f, restored.slicing.last().minimumFeatureSize)
        assertEquals(72f, restored.slicing.last().precision.minimumWallWidth)
        assertEquals(118f, restored.slicing.last().precision.firstLayerMinimumWallWidth)
        assertEquals(0.8f, restored.slicing.last().minimumWallLengthFactor)
        assertEquals("outer-inner", restored.slicing.last().wallSequence)
        assertEquals("cw", restored.slicing.last().wallDirection)
        assertEquals(78f, restored.slicing.last().smallPerimeterSpeed)
        assertEquals(false, restored.slicing.last().smallPerimeterSpeedPercent)
        assertEquals(6.25f, restored.slicing.last().smallPerimeterThreshold)
        assertEquals(false, restored.slicing.last().slowdownForCurledPerimeters)
        assertEquals(0.024f, restored.slicing.last().resolution)
        assertTrue(restored.slicing.last().staggeredInnerSeams)
        assertEquals(3.25f, restored.slicing.last().seamGap)
        assertTrue(restored.slicing.last().seamGapPercent)
        assertTrue(restored.slicing.last().wipeBeforeExternalLoop)
        assertTrue(restored.slicing.last().wipeOnLoops)
        assertEquals(false, restored.slicing.last().roleBasedWipeSpeed)
        assertEquals(67f, restored.slicing.last().wipeSpeed)
        assertEquals(false, restored.slicing.last().wipeSpeedPercent)
        assertTrue(restored.slicing.last().detectThinWalls)
        assertEquals(false, restored.slicing.last().onlyOneWallOnTop)
        assertTrue(restored.slicing.last().onlyOneWallFirstLayer)
        assertTrue(restored.slicing.last().extraPerimetersOnOverhangs)
        assertEquals(290f, restored.slicing.last().minWidthTopSurface)
        assertTrue(restored.slicing.last().minWidthTopSurfacePercent)
        assertTrue(restored.slicing.last().overhangReverse)
        assertTrue(restored.slicing.last().overhangReverseInternalOnly)
        assertEquals(0.9f, restored.slicing.last().overhangReverseThreshold)
        assertEquals(false, restored.slicing.last().overhangReverseThresholdPercent)
        assertEquals("partiallybridge", restored.slicing.last().counterboreHoleBridging)
        assertTrue(restored.slicing.last().alternateExtraWall)
        assertEquals("ensure_critical_only", restored.slicing.last().ensureVerticalShellThickness)
        assertEquals(false, restored.slicing.last().detectNarrowInternalSolidInfill)
        assertEquals(0.12f, restored.slicing.last().xyHoleCompensation)
        assertEquals(-0.08f, restored.slicing.last().xyContourCompensation)
        assertEquals(0.24f, restored.slicing.last().elephantFootCompensation)
        assertEquals(4, restored.slicing.last().elephantFootCompensationLayers)
        assertEquals(27f, restored.slicing.last().maxBridgeLength)
        assertTrue(restored.slicing.last().preciseOuterWalls)
        assertEquals(3, restored.slicing.last().skirtLoops)
        assertEquals(7.5f, restored.slicing.last().skirtDistance)
        assertEquals(4, restored.slicing.last().skirtHeight)
        assertEquals(58f, restored.slicing.last().skirtSpeed)
        assertEquals(13f, restored.slicing.last().minimumSkirtLength)
        assertEquals("enabled", restored.slicing.last().draftShield)
        assertEquals("outer_and_inner", restored.slicing.last().brimType)
        assertEquals(6.5f, restored.slicing.last().brimWidth)
        assertEquals(0.16f, restored.slicing.last().brimObjectGap)
        assertEquals(3, restored.slicing.last().raftLayers)
        assertEquals(0.14f, restored.slicing.last().raftContactDistance)
        assertEquals(2.6f, restored.slicing.last().raftExpansion)
        assertEquals(87f, restored.slicing.last().raftFirstLayerDensity)
        assertEquals(3.6f, restored.slicing.last().raftFirstLayerExpansion)
        assertEquals("marlin2", restored.printers.last().gcodeFlavor)
        assertEquals(320f, restored.printers.last().maxSpeedX)
        assertEquals(4_200f, restored.printers.last().maxAccelerationX)
        assertEquals(5_000f, restored.printers.last().maxAccelerationTravel)
        assertEquals(7f, restored.printers.last().maxJerkX)
        assertEquals(null, restored.printers.last().brand)
        assertEquals(null, restored.filaments.last().brand)
        assertEquals(USER_PROFILE_SCHEMA_VERSION, JSONObject(file.readText()).getInt("schemaVersion"))
        assertTrue("Saved profiles must stay in app-private storage", file.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        file.delete()
        directory.delete()
    }

    @Test
    fun builtInCatalogCoversAllU1NozzlesAndCommonMaterials() {
        assertEquals(
            listOf(0.2f, 0.4f, 0.6f, 0.8f),
            PrinterProfile.builtIns.filter { it.brand == "Snapmaker" }.map { it.nozzleDiameter },
        )
        assertTrue(FilamentProfile.builtIns.map { it.nativeName }.containsAll(listOf("PLA", "PETG", "ABS", "ASA", "PLA-CF", "PETG-CF", "TPU", "PA-CF")))
        assertEquals(setOf("Custom", "Snapmaker"), PrinterProfile.builtIns.mapNotNull { it.brand }.toSet())
        assertTrue(PrinterProfile.builtIns.contains(PrinterProfile.CUSTOM_CARTESIAN))
        assertTrue(
            FilamentProfile.builtIns.mapNotNull { it.brand }.containsAll(
                listOf("Generic", "Snapmaker", "Prusa", "Creality", "Anycubic", "Elegoo"),
            ),
        )
        assertEquals(QualityProfile.STANDARD_02, QualityProfile.standardFor(0.2f))
        assertEquals(QualityProfile.STANDARD_08, QualityProfile.standardFor(0.8f))
    }

    @Test
    fun bundledProfileCatalogIsVersionedValidatedAndBroad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loadStartedAt = SystemClock.elapsedRealtimeNanos()
        val catalog = OrcaProfileCatalog(context).load()
        val loadElapsedMs = (SystemClock.elapsedRealtimeNanos() - loadStartedAt) / 1_000_000
        Log.i("DuckyCatalogPerf", "loadMs=$loadElapsedMs")

        assertEquals(37, catalog.schemaVersion)
        assertTrue("Profile catalog loading took ${loadElapsedMs}ms", loadElapsedMs < 5_000)
        assertEquals("2c8a5385bc53cbc16211b4dd36ef9963ee185f4a", catalog.sourceRevision)
        assertTrue("The catalog must cover hundreds of printer variants", catalog.printers.size > 700)
        assertTrue("The catalog must include upstream filament presets", catalog.filaments.size > 3_000)
        assertTrue("The catalog must include upstream slicing presets", catalog.slicing.size > 2_000)
        val representativeBrands = setOf(
            "Prusa", "Creality", "Anycubic", "Elegoo", "Snapmaker", "Sovol", "Qidi",
        )
        representativeBrands.forEach { brand ->
            val printers = catalog.printers.filter { it.brand == brand }
            assertTrue("$brand printer profiles must be present", printers.isNotEmpty())
            assertTrue("$brand filament profiles must be present", catalog.filaments.any { it.brand == brand })
            assertTrue("$brand slicing profiles must be present", catalog.slicing.any { it.brand == brand })
            assertTrue(
                "$brand must cover common mobile-selectable nozzle sizes",
                printers.map { it.nozzleDiameter }.toSet().containsAll(setOf(0.2f, 0.4f, 0.6f, 0.8f)),
            )
        }
        assertTrue(catalog.printers.all(ProfileValidation::printer))
        assertTrue(
            "The catalog must retain non-rectangular build plates",
            catalog.printers.any { it.bedPolygon.size > 8 },
        )
        val delta = catalog.printers.single { it.name == "FLSun V400 0.4 nozzle" }
        assertEquals(-150f, delta.bedOriginX, 0.01f)
        assertEquals(-150f, delta.bedOriginY, 0.01f)
        assertTrue(
            "The catalog must retain printer-specific sequential-print clearance",
            catalog.printers.any { it.extruderClearanceRadius != 40f },
        )
        assertTrue(catalog.filaments.all(ProfileValidation::filament))
        assertTrue(
            "Prime-tower process values must survive catalog normalization",
            catalog.slicing.any { it.wipeTowerEnabled && it.wipeTowerWidth != 60f },
        )
        assertTrue(
            "Inherited multi-material process values must survive catalog normalization",
            catalog.slicing.any {
                it.multiMaterial.primeVolume != 45f ||
                    it.multiMaterial.primeTowerBrimWidth != 3f ||
                    it.multiMaterial.wipeTowerNoSparseLayers ||
                    it.multiMaterial.wipeTowerRotationAngle != 0f ||
                    it.multiMaterial.wipeTowerExtraSpacing != 100f ||
                    it.multiMaterial.wipeTowerWallType != "rectangle" ||
                    it.multiMaterial.wipeTowerConeAngle != 30f ||
                    it.multiMaterial.singleExtruderMultiMaterialPriming ||
                    !it.multiMaterial.flushIntoSupport ||
                    it.multiMaterial.oozePrevention ||
                    it.multiMaterial.interfaceShells
            },
        )
        assertTrue(catalog.slicing.all(ProfileValidation::slicing))
        assertTrue(
            "The catalog must retain process-wide flow calibration",
            catalog.slicing.any { it.printFlowRatio != 1f },
        )
        assertTrue(
            "The catalog must retain both arc-fitting policies",
            catalog.slicing.any { it.gcodeSettings.arcFitting } &&
                catalog.slicing.any { !it.gcodeSettings.arcFitting },
        )
        assertTrue(
            "The catalog must retain both object-label policies",
            catalog.slicing.any { it.gcodeSettings.labelObjects } &&
                catalog.slicing.any { !it.gcodeSettings.labelObjects },
        )
        assertTrue(
            "The catalog must retain both object-exclusion policies",
            catalog.slicing.any { it.gcodeSettings.excludeObjects } &&
                catalog.slicing.any { !it.gcodeSettings.excludeObjects },
        )
        assertTrue(
            "The catalog must retain absolute and percentage initial travel speeds",
            catalog.slicing.any { it.gcodeSettings.initialLayerTravelSpeedPercent } &&
                catalog.slicing.any { !it.gcodeSettings.initialLayerTravelSpeedPercent },
        )
        assertTrue(
            "The catalog must retain both acceleration-smoothing policies",
            catalog.slicing.any { it.gcodeSettings.accelToDecelEnabled } &&
                catalog.slicing.any { !it.gcodeSettings.accelToDecelEnabled },
        )
        assertTrue(catalog.slicing.any { it.outerWallLineWidth != it.innerWallLineWidth })
        assertTrue(catalog.slicing.any { it.topSurfaceLineWidth != it.internalSolidInfillLineWidth })
        assertTrue(catalog.slicing.any { it.printSpeed != it.innerWallSpeed })
        assertTrue(catalog.slicing.any { it.sparseInfillSpeed != it.internalSolidInfillSpeed })
        assertTrue(catalog.slicing.any { it.bridgeSpeed != 50f })
        assertTrue(catalog.slicing.any { it.firstLayerSpeed != it.firstLayerInfillSpeed })
        assertTrue(catalog.slicing.any { it.supportSpeed != it.supportInterfaceSpeed })
        assertTrue(catalog.slicing.any { it.bridgeFlowRatio != 1f })
        assertTrue(catalog.slicing.any { it.topShellThickness > 0f })
        assertTrue(catalog.slicing.any { it.supportInterfaceSpacing == 0f })
        assertTrue(catalog.slicing.any { it.fillPattern == "crosshatch" })
        assertTrue(catalog.slicing.any { it.overhangSpeed1Percent })
        assertTrue(catalog.slicing.any { it.seamPosition == "nearest" })
        assertTrue(catalog.slicing.any { it.ironing.type == "top" })
        assertTrue(catalog.slicing.any { it.ironing.inset != 0f })
        assertTrue(catalog.slicing.all { it.ironing.angle in -1f..359f })
        assertTrue(catalog.slicing.any { it.supportBasePattern == "rectilinear-grid" })
        assertTrue(catalog.slicing.any { it.supportCoverage.onBuildPlateOnly })
        assertTrue(catalog.slicing.any { it.supportCoverage.criticalRegionsOnly })
        assertTrue(catalog.slicing.any { it.supportCoverage.removeSmallOverhangs })
        assertTrue(catalog.slicing.any { it.supportAdvanced.patternAngle == 45f })
        assertTrue(catalog.slicing.any { it.supportBasePatternSpacing != 2.5f })
        assertTrue(catalog.slicing.any { it.supportExpansion != 0f })
        assertTrue(catalog.slicing.any { it.supportInterfaceLoopPattern })
        assertTrue(catalog.slicing.any { !it.independentSupportLayerHeight })
        assertTrue(catalog.slicing.any { it.supportType == "tree(auto)" })
        assertTrue(catalog.slicing.any { it.treeSupportBranchAngle != 40f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDistance != 5f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDiameter != 5f })
        assertTrue(catalog.slicing.any { it.treeSupportWallCount != 0 })
        assertTrue(catalog.slicing.any { it.treeSupportTipDiameter != 0.8f })
        assertTrue(catalog.slicing.any { it.treeSupportPreferredBranchAngle != 25f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDensity != 30f })
        assertTrue(catalog.slicing.any { it.treeSupportOrganicBranchAngle != 40f })
        assertTrue(catalog.slicing.any { it.treeSupportOrganicBranchDistance != 1f })
        assertTrue(catalog.slicing.any { it.treeSupportOrganicBranchDiameter != 2f })
        assertTrue(catalog.slicing.any { it.treeSupportBranchDiameterAngle != 5f })
        assertTrue(catalog.slicing.any { !it.treeSupportAdaptiveLayerHeight })
        assertTrue(catalog.slicing.any { !it.treeSupportAutoBrim })
        assertTrue(catalog.slicing.any { it.treeSupportBrimWidth != 3f })
        assertTrue(catalog.slicing.any { it.infillFirst })
        assertTrue(catalog.slicing.any { it.wallSequence == "outer-inner" })
        assertTrue(catalog.slicing.any { it.infillCombination })
        assertTrue(catalog.slicing.any { it.internalBridgeSpeedPercent })
        assertTrue(catalog.slicing.any { !it.bridgeAccelerationPercent })
        assertTrue("Inherited Orca jerk profiles must survive catalog normalization", catalog.slicing.any { it.defaultJerk > 0f })
        assertTrue(catalog.slicing.any { it.travelJerk != 12f })
        assertTrue(catalog.slicing.any { it.elephantFootCompensation > 0f })
        assertTrue(catalog.slicing.any { it.xyHoleCompensation != 0f })
        assertTrue(catalog.slicing.any { it.gapFillTarget == "everywhere" })
        assertTrue(catalog.slicing.any { it.gapFillTarget == "topbottom" })
        assertTrue(catalog.slicing.any { it.filterOutGapFill > 0f })
        assertTrue(catalog.slicing.any { it.minimumSparseInfillArea != 15f })
        assertTrue(catalog.slicing.any { it.maxBridgeLength != 10f })
        assertTrue("Vase process presets must retain spiral mode", catalog.slicing.any { it.spiralMode })
        assertTrue("Smooth vase presets must retain smoothing mode", catalog.slicing.any { it.spiralModeSmooth })
        assertTrue(catalog.slicing.any { it.reduceCrossingWall })
        assertTrue(catalog.slicing.any { it.reduceInfillRetraction })
        assertTrue(catalog.slicing.any { it.maxTravelDetourDistancePercent })
        assertTrue(catalog.slicing.any { !it.smallPerimeterSpeedPercent })
        assertTrue(catalog.slicing.any { it.seamGapPercent && it.seamGap != 10f })
        assertTrue("Scarf seam presets must survive catalog normalization", catalog.slicing.any { it.scarfSeam.type != "none" })
        assertTrue(catalog.slicing.any { it.scarfSeam.conditional })
        assertTrue(catalog.slicing.any { it.scarfSeam.speedPercent })
        assertTrue(catalog.slicing.any { it.wipeOnLoops })
        assertTrue(catalog.slicing.any { !it.roleBasedWipeSpeed })
        assertTrue(catalog.slicing.any { it.resolution == 0.012f })
        assertTrue(catalog.slicing.any { it.precision.minimumWallWidth != 85f })
        assertTrue(catalog.slicing.any { it.precision.firstLayerMinimumWallWidth != 85f })
        assertTrue(catalog.slicing.all { it.precision.mode in setOf("regular", "even_odd", "close_holes") })
        assertTrue(catalog.slicing.all { it.precision.closingRadius >= 0f })
        assertTrue(catalog.slicing.any { it.precision.preciseZHeight })
        assertTrue(catalog.slicing.any { it.overhangReverse })
        assertTrue(catalog.slicing.any { it.minWidthTopSurface != 300f })
        assertTrue(catalog.slicing.any { it.internalBridgeFilter == "limited" })
        assertTrue(catalog.slicing.any { it.brimType == "no_brim" })
        assertTrue(catalog.slicing.any { it.brimType == "outer_only" })
        assertTrue(catalog.slicing.any { it.brimObjectGap == 0.1f })
        assertTrue(catalog.slicing.any { it.skirtHeight > 1 })
        assertTrue(catalog.slicing.any { it.minimumSkirtLength > 0f })
        assertTrue(catalog.slicing.any { it.raftFirstLayerExpansion > 2f })
        val legacyDecimalComma = requireNotNull(
            catalog.slicing.find { it.name == "0.05mm Detail @MK3.5" },
        )
        assertEquals(2f, legacyDecimalComma.infillAnchor)
        assertEquals(false, legacyDecimalComma.infillAnchorPercent)
        val legacyInfillFirst = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-118b0a2ab38fdffaa3d4" },
        )
        assertTrue(legacyInfillFirst.infillFirst)
        assertEquals("inner-outer", legacyInfillFirst.wallSequence)
        assertEquals(30f, legacyInfillFirst.infillWallOverlap)
        assertEquals("limited", legacyInfillFirst.internalBridgeFilter)
        val reversedOverhang = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-c664cf6dd495de940c1b" },
        )
        assertTrue(reversedOverhang.overhangReverse)
        assertEquals(50f, reversedOverhang.overhangReverseThreshold)
        assertTrue(reversedOverhang.overhangReverseThresholdPercent)
        val narrowTopThreshold = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-24fb182645fb0995da8d" },
        )
        assertEquals(100f, narrowTopThreshold.minWidthTopSurface)
        assertTrue(narrowTopThreshold.minWidthTopSurfacePercent)
        val legacyOuterFirst = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-358f3384a4aa741baeb8" },
        )
        assertEquals("outer-inner", legacyOuterFirst.wallSequence)
        val distributedArachneWalls = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-ba41bc56a960ec4bf47d" },
        )
        assertEquals(2, distributedArachneWalls.wallDistributionCount)
        val wideArachneTransition = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-1ad5b7e0535fb26f335c" },
        )
        assertEquals(59f, wideArachneTransition.wallTransitionAngle)
        val narrowFeatureProfile = requireNotNull(
            catalog.slicing.find { it.id == "orca-process-118b0a2ab38fdffaa3d4" },
        )
        assertEquals(20f, narrowFeatureProfile.minimumFeatureSize)
        assertTrue(catalog.slicing.map { it.wallGenerator }.toSet().containsAll(listOf("arachne", "classic")))
        assertTrue(catalog.slicing.map { it.wallSequence }.toSet().containsAll(listOf("inner-outer", "outer-inner")))
        assertTrue(catalog.printers.mapNotNull { it.brand }.containsAll(listOf("Creality", "Prusa", "Anycubic")))
    }

    @Test
    fun previewRenderPlanKeepsEveryToolpathRoleWithinItsBudget() {
        val segmentCount = 8_000
        val segments = FloatArray(segmentCount * GcodeLayerPreview.SEGMENT_STRIDE)
        val roleCounts = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(segmentCount) { index ->
            val offset = index * GcodeLayerPreview.SEGMENT_STRIDE
            val role = index % GcodeLayerPreview.ROLE_COUNT
            segments[offset] = index.toFloat()
            segments[offset + 1] = role.toFloat()
            segments[offset + 2] = index + 1f
            segments[offset + 3] = role.toFloat()
            segments[offset + 4] = 0.2f
            segments[offset + 5] = role.toFloat()
            roleCounts[role] += 1
        }
        val preview = GcodeLayerPreview(0, 0, 1, 0.2f, 0.2f, segments, roleCounts)
        val plan = preview.buildRenderPlan(segmentBudget = 450)
        val selectedRoles = plan.segmentOffsets.map { segments[it + 5].toInt() }.toSet()

        assertEquals((0 until GcodeLayerPreview.ROLE_COUNT).toSet(), selectedRoles)
        assertTrue(plan.segmentOffsets.size <= 450 + GcodeLayerPreview.ROLE_COUNT * 26)
        assertEquals(plan.segmentOffsets.size, plan.connectsToPrevious.size)
    }

    @Test
    fun attachedStlLoadsThroughRustAndCppBridge() {
        val model = fixtureModel()

        assertTrue("Bundled model fixture must be available", model.isFile)
        assertTrue(NativeEngine.version().startsWith("DuckySlicer native bridge"))

        val result = inspectModel(model.absolutePath)
        assertTrue("STL must contain triangles", result.triangles > 0)
        assertTrue("STL preview must contain sampled mesh triangles", result.previewTriangles.isNotEmpty())
        assertTrue("STL X dimension must be positive", result.dimensions[0] > 0.0)
        assertTrue("STL Y dimension must be positive", result.dimensions[1] > 0.0)
        assertTrue("STL Z dimension must be positive", result.dimensions[2] > 0.0)
    }

    @Test
    fun malformedInputsFailClosedWithoutKillingJniProcess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "malformed-native-inputs-${System.nanoTime()}")
        assertTrue("Malformed-input fixture directory must be created", root.mkdirs())

        try {
            val oversizedGcode = File(root, "oversized-line.gcode")
            oversizedGcode.writeText("G1 X${"1".repeat(65_537)}\n;LAYER_CHANGE\n;Z:0.2\n")
            val previewOutput = ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)
                .order(ByteOrder.nativeOrder())
            val gcodeResult = NativeEngine.previewGcodeRangeInto(
                oversizedGcode.absolutePath,
                0,
                Int.MAX_VALUE,
                previewOutput,
            )
            assertTrue("Oversized G-code lines must be rejected", gcodeResult < 0)

            val validGcode = File(root, "valid-preview.gcode")
            validGcode.writeText(";LAYER_CHANGE\n;Z:0.2\nG1 X0 Y0 Z0.2\nG1 X10 Y0 E1\n")
            assertTrue(
                "Heap buffers must be rejected at the Preview JNI boundary",
                NativeEngine.previewGcodeRangeInto(
                    validGcode.absolutePath,
                    0,
                    Int.MAX_VALUE,
                    ByteBuffer.allocate(GcodeLayerPreview.MAX_PAYLOAD_BYTES),
                ) < 0,
            )
            assertTrue(
                "Undersized direct buffers must be rejected at the Preview JNI boundary",
                NativeEngine.previewGcodeRangeInto(
                    validGcode.absolutePath,
                    0,
                    Int.MAX_VALUE,
                    ByteBuffer.allocateDirect(Float.SIZE_BYTES).order(ByteOrder.nativeOrder()),
                ) < 0,
            )

            val extremeStl = File(root, "extreme-coordinate.stl")
            extremeStl.writeText(
                """
                solid extreme
                  facet normal 0 0 1
                    outer loop
                      vertex 3e38 0 0
                      vertex 0 1 0
                      vertex 0 0 1
                    endloop
                  endfacet
                endsolid extreme
                """.trimIndent(),
            )
            assertTrue(
                "Out-of-range STL coordinates must be rejected",
                NativeEngine.inspectStlPayload(extremeStl.absolutePath) == null,
            )

            val samePath = File(root, "same-path.stl")
            fixtureModel().copyTo(samePath)
            val originalBytes = samePath.readBytes()
            val transformResult = JSONObject(
                NativeEngine.transformStl(
                    samePath.absolutePath,
                    samePath.absolutePath,
                    """{"bedCenterMm":[128,128],"offsetMm":[0,0],"rotationDeg":[0,0,0],"scale":1}""",
                ),
            )
            assertTrue("In-place STL transforms must be rejected", !transformResult.optBoolean("ok"))
            assertTrue("Rejected transforms must preserve the source", originalBytes.contentEquals(samePath.readBytes()))

            assertTrue(
                "JNI must remain usable after malformed inputs",
                inspectModel(fixtureModel().absolutePath).triangles > 0,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun automaticLayUsesOrcaInTheIsolatedArm64WorkerAndProducesABedPlacedModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = tiltedAutoOrientModel()
        val sourceInspection = inspectModel(source.absolutePath)
        val output = File(context.cacheDir, "automatic-lay-${System.nanoTime()}.stl")
        try {
            val orientation = SlicerProcessClient.autoOrient(source)
            assertTrue(
                "Orca orientation must contain finite radians",
                orientation.rotationRadians.all { it.isFinite() },
            )
            assertTrue(
                "A tilted rectangular solid must receive a visible automatic orientation",
                orientation.rotationRadians.any { abs(it) > 0.1 },
            )
            assertTrue(
                "Automatic orientation must run outside the application process",
                SlicerProcessClient.lastWorkerPid() > 0 &&
                    SlicerProcessClient.lastWorkerPid() != android.os.Process.myPid(),
            )

            val transform = ModelTransform().withOrcaOrientation(orientation)
            val transformResult = JSONObject(
                NativeEngine.transformStl(
                    source.absolutePath,
                    output.absolutePath,
                    transform.toJson(256f, 256f),
                ),
            )
            assertTrue(
                "Automatic lay transform failed: ${transformResult.optString("error")}",
                transformResult.optBoolean("ok"),
            )
            val inspection = inspectModel(output.absolutePath)
            assertTrue(
                "Automatic lay must put the model on Z=0",
                abs(inspection.minMm[2]) < 0.001,
            )
            assertTrue(
                "Automatic lay must place the tilted solid on its broad stable face: " +
                    "before=${sourceInspection.dimensions[2]} after=${inspection.dimensions[2]}",
                inspection.dimensions[2] < sourceInspection.dimensions[2] * 0.7,
            )
        } finally {
            output.delete()
        }
    }

    @Test
    fun automaticLayAcceptsEveryVolumeOfOneProjectObject() {
        val source = fixtureModel()

        val orientation = SlicerProcessClient.autoOrient(listOf(source, source))

        assertTrue(
            "Multi-volume automatic orientation must return finite radians",
            orientation.rotationRadians.all { it.isFinite() },
        )
        assertTrue(
            "Multi-volume automatic orientation must run outside the application process",
            SlicerProcessClient.lastWorkerPid() > 0 &&
                SlicerProcessClient.lastWorkerPid() != android.os.Process.myPid(),
        )
    }

    @Test
    fun automaticLayReturnsCanonicalIdentityForAnAlreadyStableSolid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "automatic-lay-stable-${System.nanoTime()}.stl")
        try {
            instrumentation.context.assets.open("20mmbox-LF.stl").use { input ->
                source.outputStream().use(input::copyTo)
            }
            val orientation = SlicerProcessClient.autoOrient(source)
            assertTrue(
                "A stable solid must not return an Euler-equivalent 180/180/180 fake change",
                orientation.rotationRadians.all { abs(Math.toDegrees(it)) < 0.001 },
            )
            assertEquals(ModelTransform(), ModelTransform().withOrcaOrientation(orientation))
        } finally {
            source.delete()
        }
    }

    @Test
    fun selectedFaceUsesRustAlignmentAndStillProducesRealGcodeOnDevice() {
        val source = fixtureModel()
        val model = inspectModel(source.absolutePath)
        val triangle = model.previewTriangles.copyOfRange(0, 9)
        val transform = ModelTransform(
            rotationXdeg = 19f,
            rotationYdeg = -31f,
            rotationZdeg = 12f,
            scale = 1.2f,
            scaleY = 0.85f,
            scaleZ = 1.4f,
            mirrorX = true,
        ).withFaceOnBed(triangle)
        val center = FloatArray(3) { axis ->
            ((model.minMm[axis] + model.maxMm[axis]) / 2.0).toFloat()
        }
        val transformed = Array(3) { vertex ->
            transform.transformLocal(
                FloatArray(3) { axis -> triangle[vertex * 3 + axis] - center[axis] },
            )
        }
        val reversesWinding = listOf(transform.mirrorX, transform.mirrorY, transform.mirrorZ)
            .count { it } % 2 == 1
        val secondVertex = if (reversesWinding) transformed[2] else transformed[1]
        val thirdVertex = if (reversesWinding) transformed[1] else transformed[2]
        val first = FloatArray(3) { axis -> secondVertex[axis] - transformed[0][axis] }
        val second = FloatArray(3) { axis -> thirdVertex[axis] - transformed[0][axis] }
        val normal = floatArrayOf(
            first[1] * second[2] - first[2] * second[1],
            first[2] * second[0] - first[0] * second[2],
            first[0] * second[1] - first[1] * second[0],
        )
        val normalLength = sqrt(normal.sumOf { (it * it).toDouble() }).toFloat()
        assertTrue("Selected face normal must point into the bed", normal[2] / normalLength < -0.999f)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val transformedModel = File(context.cacheDir, "lay-on-face-${System.nanoTime()}.stl")
        var slicedOutput: File? = null
        try {
            val options = SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT)
            val result = JSONObject(
                NativeEngine.transformStl(
                    source.absolutePath,
                    transformedModel.absolutePath,
                    transform.toJson(
                        options.bedSizeX,
                        options.bedSizeY,
                        options.bedOriginX,
                        options.bedOriginY,
                    ),
                ),
            )
            assertTrue(result.optString("error"), result.optBoolean("ok"))
            val inspection = inspectModel(transformedModel.absolutePath)
            assertTrue(
                "Placed model must touch Z=0",
                abs(inspection.minMm[2]) < 0.001,
            )

            val outcome = OnDeviceSlicer.slice(transformedModel, options)
            slicedOutput = outcome.output
            assertTrue(
                "Placed model must produce extrusion G-code",
                outcome.output.readText().contains(";TYPE:Outer wall"),
            )
        } finally {
            transformedModel.delete()
            slicedOutput?.delete()
        }
    }

    @Test
    fun automaticArrangementUsesOrcaInTheIsolatedArm64WorkerAndRecoversAfterNoFit() {
        val source = fixtureModel()
        val model = inspectModel(source.absolutePath)
        val objects = listOf(
            ProjectObject("arrange-first", model, ModelTransform(offsetXmm = -25f)),
            ProjectObject("arrange-second", model, ModelTransform(offsetXmm = 25f)),
        )
        val diamondBed = listOf(50f, 0f, 100f, 50f, 50f, 100f, 0f, 50f)
        val options = SliceOptions().copy(
            bedSizeX = 100f,
            bedSizeY = 100f,
            bedOriginX = -50f,
            bedOriginY = -50f,
            bedPolygon = diamondBed,
        )

        val arrangement = OnDeviceSlicer.arrange(objects, options, minimumGap = 6f)
        assertEquals("Orca must return one placement per object", objects.size, arrangement.objectCount)
        assertTrue(
            "Automatic arrangement must run outside the application process",
            SlicerProcessClient.lastWorkerPid() > 0 &&
                SlicerProcessClient.lastWorkerPid() != android.os.Process.myPid(),
        )
        repeat(arrangement.objectCount) { index ->
            val x = arrangement.lowerLeftMm[index * 2]
            val y = arrangement.lowerLeftMm[index * 2 + 1]
            val width = arrangement.sizesMm[index * 3]
            val depth = arrangement.sizesMm[index * 3 + 1]
            assertTrue("Arranged object must remain inside the bed", x >= -0.05f && y >= -0.05f)
            assertTrue(
                "Arranged object must remain inside the bed",
                x + width <= options.bedSizeX + 0.05f &&
                    y + depth <= options.bedSizeY + 0.05f,
            )
            listOf(x to y, x + width to y, x + width to y + depth, x to y + depth).forEach { corner ->
                assertTrue(
                    "Orca arrangement must honor the non-rectangular printable area: $corner",
                    pointInsideBedPolygon(corner.first, corner.second, diamondBed),
                )
            }
        }
        val firstX = arrangement.lowerLeftMm[0]
        val firstY = arrangement.lowerLeftMm[1]
        val firstWidth = arrangement.sizesMm[0]
        val firstDepth = arrangement.sizesMm[1]
        val secondX = arrangement.lowerLeftMm[2]
        val secondY = arrangement.lowerLeftMm[3]
        val secondWidth = arrangement.sizesMm[3]
        val secondDepth = arrangement.sizesMm[4]
        val horizontalGap = maxOf(
            secondX - (firstX + firstWidth),
            firstX - (secondX + secondWidth),
        )
        val verticalGap = maxOf(
            secondY - (firstY + firstDepth),
            firstY - (secondY + secondDepth),
        )
        val measuredGap = sqrt(
            horizontalGap.coerceAtLeast(0f) * horizontalGap.coerceAtLeast(0f) +
                verticalGap.coerceAtLeast(0f) * verticalGap.coerceAtLeast(0f),
        )
        assertTrue(
            "Orca arrangement must keep the requested object clearance; measured=$measuredGap, " +
                "horizontal=$horizontalGap, vertical=$verticalGap",
            measuredGap >= 5.8f,
        )

        val noFit = runCatching {
            OnDeviceSlicer.arrange(
                objects,
                options.copy(
                    bedSizeX = 10f,
                    bedSizeY = 10f,
                    bedOriginX = -5f,
                    bedOriginY = -5f,
                    bedPolygon = listOf(5f, 0f, 10f, 5f, 5f, 10f, 0f, 5f),
                ),
                minimumGap = 6f,
            )
        }
        assertTrue("Orca must reject objects that cannot fit on the bed", noFit.isFailure)
        val healthyWorkerPid = SlicerProcessClient.workerHealthForTest(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        assertTrue("The isolated slicer worker must remain healthy after no-fit", healthyWorkerPid > 0)
        assertNotEquals("Orca must remain isolated from the app", android.os.Process.myPid(), healthyWorkerPid)
    }

    @Test
    fun supportPaintReachesOrcaAndCreatesSupportToolpaths() {
        val modelFile = supportPaintOverhangModel()
        val model = inspectModel(modelFile.absolutePath)
        assertEquals("Support fixture facet order must remain stable", 24, model.triangles)
        val options = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(supportEnabled = false)
        val baseline = OnDeviceSlicer.slice(
            listOf(ProjectObject("baseline", model)),
            options,
        )
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)

        val paintedFacets = SupportPaint()
            .paint(22, SupportPaintState.ENFORCE)
            .paint(23, SupportPaintState.ENFORCE)
        val painted = OnDeviceSlicer.slice(
            listOf(ProjectObject("painted", model, supportPaint = paintedFacets)),
            options,
        )
        val paintedPreview = loadGcodePreview(painted.output.absolutePath, 0, Int.MAX_VALUE)

        assertEquals("Support-disabled baseline must not create support", 0, baselinePreview.roleSegmentCounts[5])
        assertTrue(
            "Painted enforcer facets must create real Orca support toolpaths",
            paintedPreview.roleSegmentCounts[5] > 0,
        )
    }

    @Test
    fun automaticTreeSupportRetainsItsModeAndCreatesSupportToolpaths() {
        val model = inspectModel(supportPaintOverhangModel().absolutePath)
        val options = SliceOptions()
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                supportEnabled = true,
                supportType = "tree(auto)",
                treeSupportBranchAngle = 47f,
                treeSupportBranchDistance = 6.2f,
                treeSupportBranchDiameter = 2.4f,
                treeSupportWallCount = 2,
                treeSupportTipDiameter = 1.3f,
                treeSupportPreferredBranchAngle = 31f,
                treeSupportBranchDensity = 37f,
                treeSupportOrganicBranchAngle = 45f,
                treeSupportOrganicBranchDistance = 2.2f,
                treeSupportOrganicBranchDiameter = 3.1f,
                treeSupportBranchDiameterAngle = 10f,
                treeSupportAdaptiveLayerHeight = false,
                treeSupportAutoBrim = false,
                treeSupportBrimWidth = 4.6f,
            )

        val outcome = OnDeviceSlicer.slice(listOf(ProjectObject("tree-auto", model)), options)
        val preview = loadGcodePreview(outcome.output.absolutePath, 0, Int.MAX_VALUE)
        val gcode = outcome.output.readText()
        val baseline = OnDeviceSlicer.slice(
            listOf(ProjectObject("tree-auto-baseline", model)),
            options.copy(
                treeSupportOrganicBranchAngle = 40f,
                treeSupportOrganicBranchDistance = 1f,
                treeSupportOrganicBranchDiameter = 2f,
                treeSupportBranchDiameterAngle = 5f,
            ),
        )
        val baselinePreview = loadGcodePreview(baseline.output.absolutePath, 0, Int.MAX_VALUE)

        assertTrue("Automatic tree support must generate support paths", preview.roleSegmentCounts[5] > 0)
        assertTrue("Organic baseline must generate support paths", baselinePreview.roleSegmentCounts[5] > 0)
        assertFalse(
            "Organic geometry controls must change generated toolpaths, not only G-code metadata",
            baselinePreview.segments.contentEquals(preview.segments),
        )
        assertTrue(gcode.contains("; support_type = tree(auto)"))
        assertTrue(gcode.contains("; tree_support_branch_angle = 47"))
        assertTrue(gcode.contains("; tree_support_branch_distance = 6.2"))
        assertTrue(gcode.contains("; tree_support_branch_diameter = 2.4"))
        assertTrue(gcode.contains("; tree_support_wall_count = 2"))
        assertTrue(gcode.contains("; tree_support_tip_diameter = 1.3"))
        assertTrue(gcode.contains("; tree_support_angle_slow = 31"))
        assertTrue(gcode.contains("; tree_support_top_rate = 37%"))
        assertTrue(gcode.contains("; tree_support_branch_angle_organic = 45"))
        assertTrue(gcode.contains("; tree_support_branch_distance_organic = 2.2"))
        assertTrue(gcode.contains("; tree_support_branch_diameter_organic = 3.1"))
        assertTrue(gcode.contains("; tree_support_branch_diameter_angle = 10"))
        assertTrue(gcode.contains("; tree_support_adaptive_layer_height = 0"))
        assertTrue(gcode.contains("; tree_support_auto_brim = 0"))
        assertTrue(gcode.contains("; tree_support_brim_width = 4.6"))
    }

    @Test
    fun supportFilamentRoutingAndPrimeTowerReachOrca() {
        val model = inspectModel(fixtureModel().absolutePath)
        val primary = FilamentProfile.PLA
        val secondary = FilamentProfile.PETG
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(primary)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                filamentSlots = listOf(primary, secondary),
                supportEnabled = true,
                supportFilament = 1,
                supportInterfaceFilament = 2,
                wipeTowerEnabled = true,
                wipeTowerWidth = 42f,
                multiMaterial = MultiMaterialSettings(
                    primeVolume = 61.5f,
                    primeTowerBrimWidth = 4.5f,
                    wipeTowerNoSparseLayers = true,
                    wipeTowerRotationAngle = 73f,
                    wipeTowerBridging = 12.5f,
                    wipeTowerExtraSpacing = 145f,
                    wipeTowerExtraFlow = 118f,
                    wipeTowerMaxPurgeSpeed = 137f,
                    wipeTowerWallType = "rib",
                    wipeTowerConeAngle = 42f,
                    wipeTowerExtraRibLength = 9.5f,
                    wipeTowerRibWidth = 11f,
                    wipeTowerFilletWall = false,
                    singleExtruderMultiMaterialPriming = true,
                    flushIntoInfill = true,
                    flushIntoSupport = false,
                    flushIntoObjects = false,
                    oozePrevention = true,
                    standbyTemperatureDelta = -35,
                    interfaceShells = true,
                ),
            )
        val outcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "prime-primary",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "prime-secondary",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            ),
            options,
        )

        val gcode = outcome.output.readText()
        assertTrue("Support base filament must reach Orca", gcode.contains("; support_filament = 1"))
        assertTrue(
            "Support interface filament must reach Orca",
            gcode.contains("; support_interface_filament = 2"),
        )
        assertTrue("Prime tower must remain enabled for a two-tool plate", gcode.contains("; enable_prime_tower = 1"))
        assertTrue("Prime-tower width must reach Orca", gcode.contains("; prime_tower_width = 42"))
        assertTrue("Prime volume must reach Orca", gcode.contains("; prime_volume = 61.5"))
        assertTrue("Tower brim width must reach Orca", gcode.contains("; prime_tower_brim_width = 4.5"))
        assertTrue("Sparse tower layers must remain disabled", gcode.contains("; wipe_tower_no_sparse_layers = 1"))
        assertTrue("Tower rotation must reach Orca", gcode.contains("; wipe_tower_rotation_angle = 73"))
        assertTrue("Tower bridging must reach Orca", gcode.contains("; wipe_tower_bridging = 12.5"))
        assertTrue("Tower spacing must reach Orca", gcode.contains("; wipe_tower_extra_spacing = 145%"))
        assertTrue("Tower flow must reach Orca", gcode.contains("; wipe_tower_extra_flow = 118%"))
        assertTrue("Tower purge speed must reach Orca", gcode.contains("; wipe_tower_max_purge_speed = 137"))
        assertTrue("Tower wall type must reach Orca", gcode.contains("; wipe_tower_wall_type = rib"))
        assertTrue("Tower cone angle must reach Orca", gcode.contains("; wipe_tower_cone_angle = 42"))
        assertTrue("Tower rib length must reach Orca", gcode.contains("; wipe_tower_extra_rib_length = 9.5"))
        assertTrue("Tower rib width must reach Orca", gcode.contains("; wipe_tower_rib_width = 11"))
        assertTrue("Tower rib fillet must reach Orca", gcode.contains("; wipe_tower_fillet_wall = 0"))
        assertTrue(
            "All-extruder priming must reach Orca",
            gcode.contains("; single_extruder_multi_material_priming = 1"),
        )
        assertTrue("Infill flushing must reach Orca", gcode.contains("; flush_into_infill = 1"))
        assertTrue("Support flushing must reach Orca", gcode.contains("; flush_into_support = 0"))
        assertTrue("Object flushing must remain disabled", gcode.contains("; flush_into_objects = 0"))
        assertTrue("Ooze prevention must reach Orca", gcode.contains("; ooze_prevention = 1"))
        assertTrue("Standby temperature delta must reach Orca", gcode.contains("; standby_temperature_delta = -35"))
        assertTrue("Interface shells must reach Orca", gcode.contains("; interface_shells = 1"))
        assertTrue("The second object must produce a real tool change", gcode.lineSequence().any { it == "T1" })

        val objectFlushOutcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "object-flush-primary",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "object-flush-secondary",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            ),
            options.copy(
                multiMaterial = options.multiMaterial.copy(flushIntoObjects = true),
            ),
        )
        assertTrue(
            "Object flushing must reach Orca when explicitly enabled",
            objectFlushOutcome.output.readText().contains("; flush_into_objects = 1"),
        )

        val defaultsOutcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject(
                    id = "defaults-primary",
                    model = model,
                    transform = ModelTransform(offsetXmm = -20f),
                    filamentSlot = 0,
                ),
                ProjectObject(
                    id = "defaults-secondary",
                    model = model,
                    transform = ModelTransform(offsetXmm = 20f),
                    filamentSlot = 1,
                ),
            ),
            options.copy(
                wipeTowerEnabled = false,
                multiMaterial = MultiMaterialSettings(),
            ),
        )
        val defaultsGcode = defaultsOutcome.output.readText()
        assertTrue("Tower rotation must default to zero", defaultsGcode.contains("; wipe_tower_rotation_angle = 0"))
        assertTrue("Tower bridging must retain its default", defaultsGcode.contains("; wipe_tower_bridging = 10"))
        assertTrue("Tower spacing must retain its default", defaultsGcode.contains("; wipe_tower_extra_spacing = 100%"))
        assertTrue("Tower flow must retain its default", defaultsGcode.contains("; wipe_tower_extra_flow = 100%"))
        assertTrue("Tower purge speed must retain its default", defaultsGcode.contains("; wipe_tower_max_purge_speed = 90"))
        assertTrue("Tower wall type must default to rectangle", defaultsGcode.contains("; wipe_tower_wall_type = rectangle"))
        assertTrue("Tower cone angle must retain its default", defaultsGcode.contains("; wipe_tower_cone_angle = 30"))
        assertTrue("Tower rib length must default to zero", defaultsGcode.contains("; wipe_tower_extra_rib_length = 0"))
        assertTrue("Tower rib width must retain its default", defaultsGcode.contains("; wipe_tower_rib_width = 8"))
        assertTrue("Tower rib fillet must default on", defaultsGcode.contains("; wipe_tower_fillet_wall = 1"))
        assertTrue(
            "All-extruder priming must default off",
            defaultsGcode.contains("; single_extruder_multi_material_priming = 0"),
        )
        assertTrue("Infill flushing must default off", defaultsGcode.contains("; flush_into_infill = 0"))
        assertTrue("Support flushing must default on", defaultsGcode.contains("; flush_into_support = 1"))
        assertTrue("Object flushing must default off", defaultsGcode.contains("; flush_into_objects = 0"))
        assertTrue("Ooze prevention must not be forced on", defaultsGcode.contains("; ooze_prevention = 0"))
        assertTrue("The inherited standby delta must remain intact", defaultsGcode.contains("; standby_temperature_delta = -5"))
        assertTrue("Interface shells must not be forced on", defaultsGcode.contains("; interface_shells = 0"))
    }

    @Test
    fun nativeSlicerWorkerCrashLeavesAppAliveAndRestartsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appPid = android.os.Process.myPid()
        val firstOutcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        val firstWorkerPid = SlicerProcessClient.lastWorkerPid()
        assertNotEquals("Orca must run outside the application process", appPid, firstWorkerPid)

        val terminatedWorkerPid = SlicerProcessClient.terminateWorkerForTest(context)

        assertEquals("Application process must survive worker termination", appPid, android.os.Process.myPid())
        assertNotEquals("Only the worker process may be terminated", appPid, terminatedWorkerPid)

        val recoveredOutcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        val restartedWorkerPid = SlicerProcessClient.lastWorkerPid()
        assertTrue("Slicer worker must restart with a new process", restartedWorkerPid > 0)
        assertNotEquals("Restarted worker must not reuse the terminated process", terminatedWorkerPid, restartedWorkerPid)
        assertTrue("Worker restart must produce G-code", recoveredOutcome.output.length() > 1_000L)
        assertTrue("Previous G-code must survive another slice", firstOutcome.output.isFile)
        assertNotEquals(
            "Each slice must retain a distinct G-code artifact",
            firstOutcome.output.canonicalPath,
            recoveredOutcome.output.canonicalPath,
        )
    }

    @Test
    fun activeSliceCancellationKeepsServiceResponsiveAndRestartsCleanly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appPid = android.os.Process.myPid()
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val requestId = UUID.randomUUID().toString()
        val probe = Thread {
            runCatching {
                SlicerProcessClient.cancellationProbeForTest(started::countDown, requestId)
            }.onFailure(failure::set)
        }.apply { start() }

        assertTrue("Cancellation probe must start", started.await(10, TimeUnit.SECONDS))
        val busyWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertNotEquals("Orca work must not block the service main thread", appPid, busyWorkerPid)

        assertTrue(
            "The exact active request must accept cancellation",
            SlicerProcessClient.cancelRequestForTest(requestId),
        )
        probe.join(10_000)

        assertTrue("Cancellation must promptly release the waiting client", !probe.isAlive)
        assertTrue("Cancellation must have a distinct result", failure.get() is SlicingCancelledException)
        assertEquals("Cancellation must not terminate the app", appPid, android.os.Process.myPid())

        val restartedWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertTrue("The isolated worker must restart after cancellation", restartedWorkerPid > 0)
        assertNotEquals("Cancellation must replace the terminated worker", busyWorkerPid, restartedWorkerPid)
        val recovery = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        assertTrue("A new slice must succeed after cancellation", recovery.output.length() > 1_000L)
    }

    @Test
    fun nativeGcodeWriterHardLimitContainsDiskGrowthAndRecovers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appPid = android.os.Process.myPid()
        val maximumBytes = 32 * 1_024
        val transientOutputs = listOf(context.filesDir, context.cacheDir).map {
            File(it, SliceArtifactStore.NATIVE_OUTPUT_NAME)
        }
        val limitedWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertNotEquals("Orca must run outside the application process", appPid, limitedWorkerPid)

        val failure = runCatching {
            SlicerProcessClient.sliceWithOutputLimitForTest(
                transformedModels = listOf(fixtureModel()),
                options = SliceOptions().selectQuality(QualityProfile.DRAFT),
                maximumGcodeBytes = maximumBytes,
            )
        }

        assertTrue("The native writer must reject a truncated slice", failure.isFailure)
        assertEquals("The output limit must not terminate the app", appPid, android.os.Process.myPid())
        val limitedOutputs = transientOutputs.filter(File::exists)
        limitedOutputs.forEach { output ->
            assertTrue(
                "Native output must stop at the process file-size limit: ${output.length()}",
                output.length() in 1..maximumBytes.toLong(),
            )
        }

        val healthyWorkerPid = SlicerProcessClient.workerHealthForTest(context)
        assertTrue("A worker must be available after the native write failure", healthyWorkerPid > 0)
        assertNotEquals("Orca must remain isolated from the app", appPid, healthyWorkerPid)
        assertNotEquals(
            "The file-size signal must replace the limited worker",
            limitedWorkerPid,
            healthyWorkerPid,
        )

        val recovery = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )
        assertTrue(
            "A normal request must restore the production limit and complete",
            recovery.output.length() > maximumBytes,
        )
    }

    @Test
    fun sliceArtifactLeaseProtectsConcurrentReadersAcrossProcesses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputRoot = File(context.filesDir, SliceArtifactStore.OUTPUT_DIRECTORY).apply { mkdirs() }
        val probeFiles = ArrayList<File>()
        val leased = outputRoot.resolve("lease-probe-oldest.gcode").apply {
            writeText("G28\n")
            setLastModified(1L)
            probeFiles += this
        }
        repeat(SliceArtifactStore.MAXIMUM_RETAINED_OUTPUTS + 1) { index ->
            probeFiles += outputRoot.resolve("lease-probe-$index.gcode").apply {
                writeText("G1 X$index\n")
                setLastModified(10_000L + index)
            }
        }
        try {
            SliceArtifactLease.acquire(leased).use {
                OnDeviceSlicer.slice(
                    fixtureModel(),
                    SliceOptions().selectQuality(QualityProfile.DRAFT),
                )
                assertTrue("A cross-process reader lease must prevent pruning", leased.isFile)
            }

            OnDeviceSlicer.slice(
                fixtureModel(),
                SliceOptions().selectQuality(QualityProfile.DRAFT),
            )
            assertFalse("The released oldest output must become eligible for pruning", leased.exists())
        } finally {
            probeFiles.forEach(File::delete)
        }
    }

    @Test
    fun imperfectMeshCorpusIsRepairableOrFailsWithoutKillingTheApp() {
        val appPid = android.os.Process.myPid()
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(perimeters = 2, fillDensity = 0.10f)

        val corpus = meshCorpus()
        try {
            corpus.forEach { entry ->
                val result = runCatching { OnDeviceSlicer.slice(entry.model, options) }

                assertEquals("${entry.name} must not terminate the app process", appPid, android.os.Process.myPid())
                if (entry.mustSlice) {
                    val outcome = result.getOrElse { error ->
                        throw AssertionError("${entry.name} should be repaired and sliced", error)
                    }
                    assertTrue("${entry.name} must produce non-trivial G-code", outcome.output.length() > 1_000L)
                    val gcode = outcome.output.readText()
                    assertTrue("${entry.name} must contain outer-wall extrusion", gcode.contains(";TYPE:Outer wall"))
                    assertTrue("${entry.name} must not emit non-finite coordinates", !NON_FINITE_GCODE.containsMatchIn(gcode))
                } else {
                    assertTrue("${entry.name} must be rejected", result.isFailure)
                }

                val recovery = OnDeviceSlicer.slice(fixtureModel(), options)
                assertEquals("JNI recovery after ${entry.name} must keep the app process", appPid, android.os.Process.myPid())
                assertTrue("A valid model must slice after ${entry.name}", recovery.output.length() > 1_000L)
            }
        } finally {
            corpus.forEach { it.model.delete() }
        }
    }

    @Test
    fun attachedStlProducesGcodeOnDevice() {
        val model = fixtureModel()
        var highestProgress = 0

        assertTrue("Bundled model fixture must be available", model.isFile)

        val options = SliceOptions()
            .selectPrinter(PrinterProfile.U1_06)
            .selectFilament(FilamentProfile.PETG.copy(
                retractLength = 1.1f,
                retractSpeed = 38f,
                deretractSpeed = 33f,
                retractionMinimumTravel = 2.2f,
                retractWhenChangingLayer = true,
                wipeWhileRetracting = true,
                wipeDistance = 2.4f,
                retractBeforeWipe = 63f,
                retractRestartExtra = 0.09f,
                zHop = 0.6f,
                zHopType = "spiral",
            ))
            .selectQuality(QualityProfile.DRAFT_06)
            .copy(
                topSolidLayers = 6,
                bottomSolidLayers = 5,
                fillPattern = "crosshatch",
                topSurfacePattern = "monotonic",
                bottomSurfacePattern = "concentric",
                internalSolidInfillPattern = "rectilinear",
                travelSpeed = 420f,
                firstLayerSpeed = 35f,
                skirtLoops = 2,
                skirtDistance = 7f,
                skirtHeight = 3,
                skirtSpeed = 59f,
                minimumSkirtLength = 14f,
                draftShield = "enabled",
                brimType = "outer_and_inner",
                brimWidth = 6f,
                brimObjectGap = 0.17f,
                raftLayers = 2,
                raftContactDistance = 0.16f,
                raftExpansion = 2.7f,
                raftFirstLayerDensity = 88f,
                raftFirstLayerExpansion = 3.7f,
                perimeters = 3,
                outerWallLineWidth = 0.62f,
                innerWallLineWidth = 0.68f,
                topSurfaceLineWidth = 0.58f,
                sparseInfillLineWidth = 0.71f,
                internalSolidInfillLineWidth = 0.66f,
                supportLineWidth = 0.55f,
                innerWallSpeed = 177f,
                sparseInfillSpeed = 188f,
                internalSolidInfillSpeed = 166f,
                topSurfaceSpeed = 99f,
                supportSpeed = 77f,
                bridgeSpeed = 43f,
                gapInfillSpeed = 137f,
                firstLayerInfillSpeed = 63f,
                supportInterfaceSpeed = 57f,
                internalBridgeSpeed = 163f,
                internalBridgeSpeedPercent = true,
                overhangSpeedEnabled = true,
                overhangSpeed1 = 81f,
                overhangSpeed1Percent = true,
                overhangSpeed2 = 52f,
                overhangSpeed2Percent = false,
                overhangSpeed3 = 33f,
                overhangSpeed3Percent = true,
                overhangSpeed4 = 21f,
                overhangSpeed4Percent = false,
                printFlowRatio = 0.94f,
                bridgeFlowRatio = 0.91f,
                internalBridgeFlowRatio = 0.96f,
                topSurfaceFlowRatio = 0.97f,
                bottomSurfaceFlowRatio = 0.98f,
                bridgeDensity = 87f,
                internalBridgeDensity = 73f,
                bridgeAngle = 19f,
                internalBridgeAngle = 107f,
                bridgeNoSupport = true,
                thickBridges = true,
                thickInternalBridges = false,
                extraBridgeLayer = "apply_to_all",
                internalBridgeFilter = "nofilter",
                topShellThickness = 0.83f,
                bottomShellThickness = 0.74f,
                supportInterfaceTopLayers = 4,
                supportInterfaceBottomLayers = 2,
                supportInterfaceSpacing = 0.23f,
                supportBottomInterfaceSpacing = 0.27f,
                supportTopZDistance = 0.18f,
                supportBottomZDistance = 0.22f,
                supportObjectXYDistance = 0.41f,
                supportBasePattern = "rectilinear-grid",
                supportInterfacePattern = "rectilinear_interlaced",
                supportStyle = "snug",
                supportCoverage = SupportCoverageSettings(
                    onBuildPlateOnly = true,
                    criticalRegionsOnly = true,
                    removeSmallOverhangs = false,
                ),
                supportAdvanced = SupportAdvancedSettings(
                    patternAngle = 73f,
                    thresholdOverlap = 0.33f,
                    thresholdOverlapPercent = false,
                    objectFirstLayerGap = 0.42f,
                    avoidInterfaceFilamentForBase = false,
                    ironingEnabled = true,
                    ironingPattern = "concentric",
                    ironingFlow = 17f,
                    ironingSpacing = 0.18f,
                ),
                supportBasePatternSpacing = 3.2f,
                supportExpansion = -0.4f,
                supportInterfaceLoopPattern = true,
                independentSupportLayerHeight = false,
                supportType = "tree(auto)",
                treeSupportBranchAngle = 47f,
                treeSupportBranchDistance = 6.2f,
                treeSupportBranchDiameter = 2.4f,
                treeSupportWallCount = 2,
                treeSupportTipDiameter = 1.3f,
                treeSupportPreferredBranchAngle = 31f,
                treeSupportBranchDensity = 37f,
                treeSupportOrganicBranchAngle = 45f,
                treeSupportOrganicBranchDistance = 2.2f,
                treeSupportOrganicBranchDiameter = 3.1f,
                treeSupportBranchDiameterAngle = 10f,
                treeSupportAdaptiveLayerHeight = false,
                treeSupportAutoBrim = false,
                treeSupportBrimWidth = 4.6f,
                infillFirst = true,
                infillWallOverlap = 19f,
                topBottomInfillWallOverlap = 31f,
                infillCombination = true,
                infillCombinationMaxLayerHeight = 0.48f,
                infillCombinationMaxLayerHeightPercent = false,
                infillDirection = 37f,
                solidInfillDirection = 123f,
                alignInfillDirectionToModel = true,
                minimumSparseInfillArea = 42f,
                infillAnchor = 321f,
                infillAnchorPercent = true,
                infillAnchorMax = 17.5f,
                infillAnchorMaxPercent = false,
                gapFillTarget = "everywhere",
                filterOutGapFill = 0.9f,
                reduceCrossingWall = true,
                maxTravelDetourDistance = 123f,
                maxTravelDetourDistancePercent = true,
                reduceInfillRetraction = true,
                initialLayerLineWidth = 0.73f,
                smallPerimeterSpeed = 69f,
                smallPerimeterSpeedPercent = true,
                smallPerimeterThreshold = 7.5f,
                slowdownForCurledPerimeters = false,
                resolution = 0.021f,
                precision = PrecisionSettings(
                    mode = "even_odd",
                    closingRadius = 0.123f,
                    preciseZHeight = true,
                    minimumWallWidth = 71f,
                    firstLayerMinimumWallWidth = 119f,
                ),
                seamPosition = "nearest",
                staggeredInnerSeams = true,
                seamGap = 7f,
                seamGapPercent = true,
                wipeBeforeExternalLoop = true,
                wipeOnLoops = true,
                roleBasedWipeSpeed = false,
                wipeSpeed = 61f,
                wipeSpeedPercent = false,
                ironing = IroningSettings(
                    type = "top",
                    pattern = "concentric",
                    flow = 13f,
                    spacing = 0.17f,
                    inset = 0.38f,
                    speed = 27f,
                    angle = 124f,
                ),
                defaultAcceleration = 4_567f,
                outerWallAcceleration = 2_345f,
                innerWallAcceleration = 3_456f,
                topSurfaceAcceleration = 1_234f,
                travelAcceleration = 5_678f,
                firstLayerAcceleration = 678f,
                bridgeAcceleration = 47f,
                bridgeAccelerationPercent = true,
                sparseInfillAcceleration = 4_321f,
                sparseInfillAccelerationPercent = false,
                internalSolidInfillAcceleration = 83f,
                internalSolidInfillAccelerationPercent = true,
                wallGenerator = "arachne",
                wallTransitionLength = 137f,
                wallTransitionFilterDeviation = 33f,
                wallTransitionAngle = 26f,
                wallDistributionCount = 4,
                minimumFeatureSize = 19f,
                minimumWallLengthFactor = 0.85f,
                wallSequence = "outer-inner",
                wallDirection = "cw",
                detectThinWalls = true,
                detectOverhangWalls = false,
                onlyOneWallOnTop = false,
                minWidthTopSurface = 285f,
                minWidthTopSurfacePercent = true,
                onlyOneWallFirstLayer = true,
                extraPerimetersOnOverhangs = true,
                overhangReverse = true,
                overhangReverseInternalOnly = true,
                overhangReverseThreshold = 0.75f,
                overhangReverseThresholdPercent = false,
                counterboreHoleBridging = "sacrificiallayer",
                alternateExtraWall = true,
                ensureVerticalShellThickness = "ensure_moderate",
                detectNarrowInternalSolidInfill = false,
                xyHoleCompensation = 0.11f,
                xyContourCompensation = -0.07f,
                elephantFootCompensation = 0.23f,
                elephantFootCompensationLayers = 3,
                maxBridgeLength = 26f,
                preciseOuterWalls = true,
            )
        val outcome = OnDeviceSlicer.slice(model, options) { progress ->
            highestProgress = maxOf(highestProgress, progress)
        }

        assertTrue("Slicing must report progress", highestProgress > 0)
        assertTrue("Slicing must produce at least one layer", outcome.layers > 0)
        assertTrue("G-code must be a non-trivial file", outcome.output.length() > 1_000L)
        val gcode = outcome.output.readText()
        assertTrue("G-code must contain motion commands", gcode.lineSequence().any { it.startsWith("G1 ") })
        assertTrue("Orca must emit distinct inner-wall regions", gcode.contains(";TYPE:Inner wall"))
        assertTrue("Orca must emit distinct outer-wall regions", gcode.contains(";TYPE:Outer wall"))
        assertTrue("Printer nozzle must reach G-code", gcode.contains("; nozzle_diameter = 0.6"))
        assertTrue("Filament type must reach G-code", gcode.contains("; filament_type = PETG"))
        assertTrue("First layer nozzle temperature must reach G-code", gcode.contains("M104 S250"))
        assertTrue("Filament nozzle temperature must reach G-code", gcode.contains("M104 S245"))
        assertTrue("Filament bed temperature must reach G-code", gcode.contains("M190 S70"))
        assertTrue("Filament flow ratio must reach G-code", gcode.contains("; filament_flow_ratio = 0.95"))
        assertTrue("Maximum flow must reach G-code", gcode.contains("; filament_max_volumetric_speed = 10"))
        assertTrue("Layer height must reach G-code", gcode.contains("; layer_height = 0.4"))
        assertTrue("First layer height must reach G-code", gcode.contains("; first_layer_height = 0.350"))
        assertTrue("Top shell layers must reach G-code", gcode.contains("; top_shell_layers = 6"))
        assertTrue("Bottom shell layers must reach G-code", gcode.contains("; bottom_shell_layers = 5"))
        assertTrue("Top shell thickness must reach G-code", gcode.contains("; top_shell_thickness = 0.83"))
        assertTrue("Bottom shell thickness must reach G-code", gcode.contains("; bottom_shell_thickness = 0.74"))
        assertTrue("Sparse pattern must preserve Orca crosshatch", gcode.contains("; sparse_infill_pattern = crosshatch"))
        assertTrue("Top surface pattern must remain distinct", gcode.contains("; top_surface_pattern = monotonic"))
        assertTrue("Bottom surface pattern must remain distinct", gcode.contains("; bottom_surface_pattern = concentric"))
        assertTrue("Internal solid pattern must remain distinct", gcode.contains("; internal_solid_infill_pattern = rectilinear"))
        assertTrue("Travel speed must reach G-code", gcode.contains("; travel_speed = 420"))
        assertTrue("First layer speed must reach G-code", gcode.contains("; initial_layer_speed = 35"))
        assertTrue("Initial-layer solid speed must reach Orca", gcode.contains("; initial_layer_infill_speed = 63"))
        assertTrue("Bridge speed must reach Orca", gcode.contains("; bridge_speed = 43"))
        assertTrue("Internal bridge speed must preserve percent units", gcode.contains("; internal_bridge_speed = 163%"))
        assertTrue("Gap-infill speed must reach Orca", gcode.contains("; gap_infill_speed = 137"))
        val retractionHeader = gcode.lineSequence()
            .filter { line ->
                line.startsWith("; retraction_") || line.startsWith("; deretraction_") ||
                    line.startsWith("; retract_") || line.startsWith("; wipe") ||
                    line.startsWith("; z_hop")
            }
            .joinToString(" | ")
        assertTrue(
            "Retraction length must reach G-code; actual: $retractionHeader",
            gcode.contains("; retraction_length = 1.1"),
        )
        assertTrue("Retraction speed must reach G-code", gcode.contains("; retraction_speed = 38"))
        assertTrue("De-retraction speed must reach G-code", gcode.contains("; deretraction_speed = 33"))
        assertTrue("Retraction travel threshold must reach G-code", gcode.contains("; retraction_minimum_travel = 2.2"))
        assertTrue("Layer-change retraction must reach G-code", gcode.contains("; retract_when_changing_layer = 1"))
        assertTrue("Retraction wipe must reach G-code", gcode.contains("; wipe = 1"))
        assertTrue("Wipe distance must reach G-code", gcode.contains("; wipe_distance = 2.4"))
        assertTrue("Pre-wipe amount must reach G-code", gcode.contains("; retract_before_wipe = 63%"))
        assertTrue("Restart extra must reach G-code", gcode.contains("; retract_restart_extra = 0.09"))
        assertTrue("Z-hop height must reach G-code", gcode.contains("; z_hop = 0.6"))
        assertTrue("Z-hop type must reach G-code", gcode.contains("; z_hop_types = Spiral Lift"))
        assertTrue("Skirt loops must reach G-code", gcode.contains("; skirt_loops = 2"))
        assertTrue("Skirt distance must reach Orca", gcode.contains("; skirt_distance = 7"))
        assertTrue("Skirt height must reach Orca", gcode.contains("; skirt_height = 3"))
        assertTrue("Skirt speed must reach Orca", gcode.contains("; skirt_speed = 59"))
        assertTrue("Minimum skirt extrusion must reach Orca", gcode.contains("; min_skirt_length = 14"))
        assertTrue("Draft shield mode must reach Orca", gcode.contains("; draft_shield = enabled"))
        assertTrue("Brim topology must reach Orca", gcode.contains("; brim_type = outer_and_inner"))
        assertTrue("Brim width must reach Orca", gcode.contains("; brim_width = 6"))
        assertTrue("Brim gap must reach Orca", gcode.contains("; brim_object_gap = 0.17"))
        assertTrue("Raft layer count must reach Orca", gcode.contains("; raft_layers = 2"))
        assertTrue("Raft contact distance must reach Orca", gcode.contains("; raft_contact_distance = 0.16"))
        assertTrue("Raft expansion must reach Orca", gcode.contains("; raft_expansion = 2.7"))
        assertTrue("Raft density must reach Orca", gcode.contains("; raft_first_layer_density = 88%"))
        assertTrue("First raft expansion must reach Orca", gcode.contains("; raft_first_layer_expansion = 3.7"))
        assertTrue("Wall count must reach Orca", gcode.contains("; wall_loops = 3"))
        assertTrue("Outer-wall width must remain independent", gcode.contains("; outer_wall_line_width = 0.62"))
        assertTrue("Inner-wall width must remain independent", gcode.contains("; inner_wall_line_width = 0.68"))
        assertTrue("Top-surface width must remain independent", gcode.contains("; top_surface_line_width = 0.58"))
        assertTrue("Sparse-infill width must remain independent", gcode.contains("; sparse_infill_line_width = 0.71"))
        assertTrue("Internal-solid width must remain independent", gcode.contains("; internal_solid_infill_line_width = 0.66"))
        assertTrue("Support width must remain independent", gcode.contains("; support_line_width = 0.55"))
        assertTrue("Initial-layer width must remain independent", gcode.contains("; initial_layer_line_width = 0.73"))
        assertTrue("Inner-wall speed must reach Orca", gcode.contains("; inner_wall_speed = 177"))
        assertTrue("Sparse-infill speed must reach Orca", gcode.contains("; sparse_infill_speed = 188"))
        assertTrue("Internal-solid speed must reach Orca", gcode.contains("; internal_solid_infill_speed = 166"))
        assertTrue("Top-surface speed must reach Orca", gcode.contains("; top_surface_speed = 99"))
        assertTrue("Support speed must reach Orca", gcode.contains("; support_speed = 77"))
        assertTrue("Support-interface speed must reach Orca", gcode.contains("; support_interface_speed = 57"))
        assertTrue("Process flow ratio must reach Orca", gcode.contains("; print_flow_ratio = 0.94"))
        assertTrue("Bridge flow must reach Orca", gcode.contains("; bridge_flow = 0.91"))
        assertTrue("Internal bridge flow must reach Orca", gcode.contains("; internal_bridge_flow = 0.96"))
        assertTrue("Top surface flow must reach Orca", gcode.contains("; top_solid_infill_flow_ratio = 0.97"))
        assertTrue("Bottom surface flow must reach Orca", gcode.contains("; bottom_solid_infill_flow_ratio = 0.98"))
        assertTrue("External bridge density must reach Orca", gcode.contains("; bridge_density = 87%"))
        assertTrue("Internal bridge density must reach Orca", gcode.contains("; internal_bridge_density = 73%"))
        assertTrue("Bridge support policy must reach Orca", gcode.contains("; bridge_no_support = 1"))
        assertTrue("External bridge thickness must reach Orca", gcode.contains("; thick_bridges = 1"))
        assertTrue("Internal bridge thickness must reach Orca", gcode.contains("; thick_internal_bridges = 0"))
        assertTrue("External bridge direction must reach Orca", gcode.contains("; bridge_angle = 19"))
        assertTrue("Internal bridge direction must reach Orca", gcode.contains("; internal_bridge_angle = 107"))
        assertTrue("Extra bridge layers must reach Orca", gcode.contains("; enable_extra_bridge_layer = apply_to_all"))
        assertTrue("Internal bridge filtering must reach Orca", gcode.contains("; dont_filter_internal_bridges = nofilter"))
        assertTrue("Top support interface layers must reach Orca", gcode.contains("; support_interface_top_layers = 4"))
        assertTrue("Bottom support interface layers must reach Orca", gcode.contains("; support_interface_bottom_layers = 2"))
        assertTrue("Top support interface spacing must reach Orca", gcode.contains("; support_interface_spacing = 0.23"))
        assertTrue("Bottom support interface spacing must reach Orca", gcode.contains("; support_bottom_interface_spacing = 0.27"))
        assertTrue("Support top Z distance must reach Orca", gcode.contains("; support_top_z_distance = 0.18"))
        assertTrue("Support bottom Z distance must reach Orca", gcode.contains("; support_bottom_z_distance = 0.22"))
        assertTrue("Support XY distance must reach Orca", gcode.contains("; support_object_xy_distance = 0.41"))
        assertTrue("Support base pattern must reach Orca", gcode.contains("; support_base_pattern = rectilinear-grid"))
        assertTrue("Support interface pattern must reach Orca", gcode.contains("; support_interface_pattern = rectilinear_interlaced"))
        assertTrue("Support style must reach Orca", gcode.contains("; support_style = snug"))
        assertTrue("Build-plate-only support must reach Orca", gcode.contains("; support_on_build_plate_only = 1"))
        assertTrue("Critical-region support must reach Orca", gcode.contains("; support_critical_regions_only = 1"))
        assertTrue("Small-overhang filtering must reach Orca", gcode.contains("; support_remove_small_overhang = 0"))
        assertTrue("Support pattern angle must reach Orca", gcode.contains("; support_angle = 73"))
        assertTrue("Support threshold overlap must preserve millimeters", gcode.contains("; support_threshold_overlap = 0.33"))
        assertTrue("First-layer support gap must reach Orca", gcode.contains("; support_object_first_layer_gap = 0.42"))
        assertTrue("Support filament policy must reach Orca", gcode.contains("; support_interface_not_for_body = 0"))
        assertTrue("Support ironing must reach Orca", gcode.contains("; support_ironing = 1"))
        assertTrue("Support ironing pattern must reach Orca", gcode.contains("; support_ironing_pattern = concentric"))
        assertTrue("Support ironing flow must reach Orca", gcode.contains("; support_ironing_flow = 17%"))
        assertTrue("Support ironing spacing must reach Orca", gcode.contains("; support_ironing_spacing = 0.18"))
        assertTrue("Support base spacing must reach Orca", gcode.contains("; support_base_pattern_spacing = 3.2"))
        assertTrue("Support expansion must reach Orca", gcode.contains("; support_expansion = -0.4"))
        assertTrue("Support interface loops must reach Orca", gcode.contains("; support_interface_loop_pattern = 1"))
        assertTrue("Independent support layers must reach Orca", gcode.contains("; independent_support_layer_height = 0"))
        assertTrue("Automatic tree support mode must reach Orca", gcode.contains("; support_type = tree(auto)"))
        assertTrue("Tree branch angle must reach Orca", gcode.contains("; tree_support_branch_angle = 47"))
        assertTrue("Tree branch distance must reach Orca", gcode.contains("; tree_support_branch_distance = 6.2"))
        assertTrue("Tree branch diameter must reach Orca", gcode.contains("; tree_support_branch_diameter = 2.4"))
        assertTrue("Tree support wall loops must reach Orca", gcode.contains("; tree_support_wall_count = 2"))
        assertTrue("Tree tip diameter must reach Orca", gcode.contains("; tree_support_tip_diameter = 1.3"))
        assertTrue("Preferred tree branch angle must reach Orca", gcode.contains("; tree_support_angle_slow = 31"))
        assertTrue("Tree branch density must reach Orca", gcode.contains("; tree_support_top_rate = 37%"))
        assertTrue("Organic branch angle must reach Orca", gcode.contains("; tree_support_branch_angle_organic = 45"))
        assertTrue("Organic branch distance must reach Orca", gcode.contains("; tree_support_branch_distance_organic = 2.2"))
        assertTrue("Organic branch diameter must reach Orca", gcode.contains("; tree_support_branch_diameter_organic = 3.1"))
        assertTrue("Branch diameter angle must reach Orca", gcode.contains("; tree_support_branch_diameter_angle = 10"))
        assertTrue("Adaptive tree layers must reach Orca", gcode.contains("; tree_support_adaptive_layer_height = 0"))
        assertTrue("Automatic tree brim must reach Orca", gcode.contains("; tree_support_auto_brim = 0"))
        assertTrue("Tree brim width must reach Orca", gcode.contains("; tree_support_brim_width = 4.6"))
        assertTrue("Seam position must reach Orca", gcode.contains("; seam_position = nearest"))
        assertTrue("Ironing type must reach Orca", gcode.contains("; ironing_type = top"))
        assertTrue("Ironing pattern must reach Orca", gcode.contains("; ironing_pattern = concentric"))
        assertTrue("Ironing flow must reach Orca", gcode.contains("; ironing_flow = 13%"))
        assertTrue("Ironing spacing must reach Orca", gcode.contains("; ironing_spacing = 0.17"))
        assertTrue("Ironing inset must reach Orca", gcode.contains("; ironing_inset = 0.38"))
        assertTrue("Ironing speed must reach Orca", gcode.contains("; ironing_speed = 27"))
        assertTrue("Ironing angle must reach Orca", gcode.contains("; ironing_angle = 124"))
        assertTrue("Overhang stage 1 must preserve percent units", gcode.contains("; overhang_1_4_speed = 81%"))
        assertTrue("Overhang stage 2 must preserve absolute units", gcode.contains("; overhang_2_4_speed = 52"))
        assertTrue("Overhang stage 3 must preserve percent units", gcode.contains("; overhang_3_4_speed = 33%"))
        assertTrue("Overhang stage 4 must preserve absolute units", gcode.contains("; overhang_4_4_speed = 21"))
        assertTrue("Infill-first order must reach Orca", gcode.contains("; is_infill_first = 1"))
        assertTrue("Sparse infill overlap must reach Orca", gcode.contains("; infill_wall_overlap = 19%"))
        assertTrue("Solid surface overlap must reach Orca", gcode.contains("; top_bottom_infill_wall_overlap = 31%"))
        assertTrue("Combined infill must reach Orca", gcode.contains("; infill_combination = 1"))
        assertTrue("Combined infill height must preserve absolute units", gcode.contains("; infill_combination_max_layer_height = 0.48"))
        assertTrue("Sparse infill direction must reach Orca", gcode.contains("; infill_direction = 37"))
        assertTrue("Solid infill direction must reach Orca", gcode.contains("; solid_infill_direction = 123"))
        assertTrue("Model-relative infill must reach Orca", gcode.contains("; align_infill_direction_to_model = 1"))
        assertTrue("Sparse-area threshold must reach Orca", gcode.contains("; minimum_sparse_infill_area = 42"))
        assertTrue("Infill anchor must preserve percent units", gcode.contains("; infill_anchor = 321%"))
        assertTrue("Maximum infill anchor must preserve absolute units", gcode.contains("; infill_anchor_max = 17.5"))
        assertTrue("Gap-fill surface policy must reach Orca", gcode.contains("; gap_fill_target = everywhere"))
        assertTrue("Tiny-gap filter must reach Orca", gcode.contains("; filter_out_gap_fill = 0.9"))
        assertTrue("Wall-crossing avoidance must reach Orca", gcode.contains("; reduce_crossing_wall = 1"))
        assertTrue("Travel detour must preserve percent units", gcode.contains("; max_travel_detour_distance = 123%"))
        assertTrue("Infill retraction policy must reach Orca", gcode.contains("; reduce_infill_retraction = 1"))
        assertTrue("Default acceleration must reach Orca", gcode.contains("; default_acceleration = 4567"))
        assertTrue("Outer-wall acceleration must reach Orca", gcode.contains("; outer_wall_acceleration = 2345"))
        assertTrue("Inner-wall acceleration must reach Orca", gcode.contains("; inner_wall_acceleration = 3456"))
        assertTrue("Top-surface acceleration must reach Orca", gcode.contains("; top_surface_acceleration = 1234"))
        assertTrue("Travel acceleration must reach Orca", gcode.contains("; travel_acceleration = 5678"))
        assertTrue("First-layer acceleration must reach Orca", gcode.contains("; initial_layer_acceleration = 678"))
        assertTrue("Bridge acceleration must preserve percent units", gcode.contains("; bridge_acceleration = 47%"))
        assertTrue("Sparse infill acceleration must preserve absolute units", gcode.contains("; sparse_infill_acceleration = 4321"))
        assertTrue("Internal solid acceleration must preserve percent units", gcode.contains("; internal_solid_infill_acceleration = 83%"))
        assertTrue("Arachne selection must reach Orca", gcode.contains("; wall_generator = arachne"))
        assertTrue("Arachne transition length must reach Orca", gcode.contains("; wall_transition_length = 137%"))
        assertTrue("Arachne transition filter must reach Orca", gcode.contains("; wall_transition_filter_deviation = 33%"))
        assertTrue("Arachne transition angle must reach Orca", gcode.contains("; wall_transition_angle = 26"))
        assertTrue("Arachne wall distribution must reach Orca", gcode.contains("; wall_distribution_count = 4"))
        assertTrue("Arachne minimum feature size must reach Orca", gcode.contains("; min_feature_size = 19%"))
        assertTrue("Arachne minimum wall width must reach Orca", gcode.contains("; min_bead_width = 71%"))
        assertTrue(
            "Arachne first-layer minimum wall width must reach Orca",
            gcode.contains("; initial_layer_min_bead_width = 119%"),
        )
        assertTrue("Arachne minimum wall length must reach Orca", gcode.contains("; min_length_factor = 0.85"))
        assertTrue("Wall order must reach Orca", gcode.contains("; wall_sequence = outer wall/inner wall"))
        assertTrue("Wall direction must reach Orca", gcode.contains("; wall_direction = cw"))
        assertTrue("Small-perimeter speed must preserve percent units", gcode.contains("; small_perimeter_speed = 69%"))
        assertTrue("Small-perimeter threshold must reach Orca", gcode.contains("; small_perimeter_threshold = 7.5"))
        assertTrue("Curled-perimeter slowdown must reach Orca", gcode.contains("; slowdown_for_curled_perimeters = 0"))
        assertTrue("Toolpath resolution must reach Orca", gcode.contains("; resolution = 0.021"))
        assertTrue("Mesh winding mode must reach Orca", gcode.contains("; slicing_mode = even_odd"))
        assertTrue("Mesh gap closing radius must reach Orca", gcode.contains("; slice_closing_radius = 0.123"))
        assertTrue("Precise Z height must reach Orca", gcode.contains("; precise_z_height = 1"))
        assertTrue("Inner seam staggering must reach Orca", gcode.contains("; staggered_inner_seams = 1"))
        assertTrue("Seam gap must preserve percent units", gcode.contains("; seam_gap = 7%"))
        assertTrue("Outer-wall pre-wipe must reach Orca", gcode.contains("; wipe_before_external_loop = 1"))
        assertTrue("Loop wipe must reach Orca", gcode.contains("; wipe_on_loops = 1"))
        assertTrue("Role-based wipe policy must reach Orca", gcode.contains("; role_based_wipe_speed = 0"))
        assertTrue("Absolute wipe speed must reach Orca", gcode.contains("; wipe_speed = 61"))
        assertTrue("Thin-wall detection must reach Orca", gcode.contains("; detect_thin_wall = 1"))
        assertTrue("Overhang-wall detection must reach Orca", gcode.contains("; detect_overhang_wall = 0"))
        assertTrue("Top-surface wall rule must reach Orca", gcode.contains("; only_one_wall_top = 0"))
        assertTrue("Partial top-surface threshold must preserve percent units", gcode.contains("; min_width_top_surface = 285%"))
        assertTrue("First-layer wall rule must reach Orca", gcode.contains("; only_one_wall_first_layer = 1"))
        assertTrue("Overhang extra-wall policy must reach Orca", gcode.contains("; extra_perimeters_on_overhangs = 1"))
        assertTrue("Overhang direction reversal must reach Orca", gcode.contains("; overhang_reverse = 1"))
        assertTrue("Internal-only reversal must reach Orca", gcode.contains("; overhang_reverse_internal_only = 1"))
        assertTrue("Overhang reversal threshold must preserve absolute units", gcode.contains("; overhang_reverse_threshold = 0.75"))
        assertTrue("Counterbore bridge mode must reach Orca", gcode.contains("; counterbore_hole_bridging = sacrificiallayer"))
        assertTrue("Alternating extra wall must reach Orca", gcode.contains("; alternate_extra_wall = 1"))
        assertTrue("Vertical shell mode must reach Orca", gcode.contains("; ensure_vertical_shell_thickness = ensure_moderate"))
        assertTrue("Narrow internal-solid policy must reach Orca", gcode.contains("; detect_narrow_internal_solid_infill = 0"))
        assertTrue("Hole compensation must reach Orca", gcode.contains("; xy_hole_compensation = 0.11"))
        assertTrue("Contour compensation must reach Orca", gcode.contains("; xy_contour_compensation = -0.07"))
        assertTrue("Elephant-foot compensation must reach Orca", gcode.contains("; elefant_foot_compensation = 0.23"))
        assertTrue("Elephant-foot layer count must reach Orca", gcode.contains("; elefant_foot_compensation_layers = 3"))
        assertTrue("Unsupported bridge limit must reach Orca", gcode.contains("; max_bridge_length = 26"))
        assertTrue("Outer-wall precision must reach Orca", gcode.contains("; precise_outer_wall = 1"))

        val previewPayload = ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)
            .order(ByteOrder.nativeOrder())
        val usedFloats = NativeEngine.previewGcodeRangeInto(
            outcome.output.absolutePath,
            0,
            Int.MAX_VALUE,
            previewPayload,
        )
        assertTrue(
            "Direct preview payload must remain within the fixed memory budget",
            usedFloats in 1..GcodeLayerPreview.MAX_PAYLOAD_FLOATS,
        )
        val preview = GcodeLayerPreview.fromTrustedNative(previewPayload, usedFloats)
        assertTrue("Preview must report generated layers", preview.layerCount > 0)
        assertTrue("Preview must include the first layer", preview.startLayer == 0)
        assertTrue("Preview must include the final G-code layer", preview.endLayer == preview.layerCount - 1)
        assertTrue("Full preview must contain extrusion paths", preview.segments.isNotEmpty())
        assertEquals(0, preview.segments.size % GcodeLayerPreview.SEGMENT_STRIDE)
        assertTrue("Segment Z coordinates must be positive", preview.segments[4] > 0f)
        assertTrue("Outer-wall paths must be classified", preview.roleSegmentCounts[0] > 0)
        assertTrue("Inner-wall paths must be classified", preview.roleSegmentCounts[1] > 0)
        assertTrue("Visible top surfaces must be classified", preview.roleSegmentCounts[3] > 0)
        assertTrue("Internal solid infill must stay separate", preview.roleSegmentCounts[4] > 0)
        assertTrue("Visible bottom surfaces must stay separate", preview.roleSegmentCounts[9] > 0)
        assertTrue("Preview must report a positive first layer Z", preview.minZMm > 0f)
        assertTrue("Multi-layer preview must span upward in Z", preview.maxZMm > preview.minZMm)

        val gpuStaging = ToolpathMeshBuilder.build(
            ToolpathScene(
                preview = preview,
                bedSizeX = options.bedSizeX,
                bedSizeY = options.bedSizeY,
                opacity = 0.92f,
                depthContrast = 0.78f,
                detail = PreviewDetail.BALANCED,
            ),
        )
        assertTrue("ARM64 GPU bed staging must use direct memory", gpuStaging.bedVertices.isDirect)
        assertTrue(
            "ARM64 GPU instance staging must use direct memory",
            gpuStaging.toolpathInstances.isDirect,
        )
        assertEquals(
            "ARM64 toolpaths must use compact 32-byte instances",
            gpuStaging.instanceCount * ToolpathMeshBuilder.INSTANCE_STRIDE_BYTES,
            gpuStaging.toolpathInstances.remaining(),
        )
        assertTrue(
            "ARM64 compact preview instances must stay below four MiB",
            gpuStaging.toolpathInstances.remaining() < 4 * 1024 * 1024,
        )
    }

    private companion object {
        val NON_FINITE_GCODE = Regex("(?i)(?:^|[\\sXYZEF])(?:nan|[+-]?inf)(?:$|\\s)")
    }

    @Test
    fun classicWallGeneratorProducesDistinctOuterAndInnerWallsOnDevice() {
        val model = fixtureModel()
        val outcome = OnDeviceSlicer.slice(
            model,
            SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT)
                .copy(
                    wallGenerator = "classic",
                    perimeters = 3,
                    fillDensity = 0.12f,
                    outerWallLineWidth = 0.42f,
                    innerWallLineWidth = 0.45f,
                    detectThinWalls = true,
                    detectOverhangWalls = true,
                ),
        )

        val gcode = outcome.output.readText()
        assertTrue("Classic selection must reach Orca", gcode.contains("; wall_generator = classic"))
        assertTrue("Classic must generate outer walls", gcode.contains(";TYPE:Outer wall"))
        assertTrue("Classic must generate inner walls", gcode.contains(";TYPE:Inner wall"))
        assertTrue("Classic G-code must contain extrusion", gcode.lineSequence().any { it.startsWith("G1 ") && it.contains(" E") })
    }

    @Test
    fun scarfJointSeamProducesSlopedExtrusionOnCylinder() {
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.STANDARD)
            .copy(
                wallGenerator = "classic",
                perimeters = 2,
                fillDensity = 0.10f,
                scarfSeam = ScarfSeamSettings(
                    type = "external",
                    conditional = false,
                    speed = 65f,
                    speedPercent = true,
                    flowRatio = 0.92f,
                    startHeight = 15f,
                    startHeightPercent = true,
                    entireLoop = false,
                    length = 12f,
                    steps = 12,
                    innerWalls = false,
                ),
            )
        val enabled = OnDeviceSlicer.slice(cylinderModel(), base).output.readText()
        val disabled = OnDeviceSlicer.slice(
            cylinderModel(),
            base.copy(scarfSeam = base.scarfSeam.copy(type = "none")),
        ).output.readText()

        fun extrusionZValues(gcode: String): List<Float> = gcode.lineSequence()
            .filter { line ->
                line.startsWith("G1 ") && line.contains(" E") && line.contains(" Z") &&
                    (line.contains(" X") || line.contains(" Y"))
            }
            .mapNotNull { line ->
                Regex("(?:^| )Z(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull()
            }
            .distinct()
            .toList()

        assertTrue("Scarf seam mode must reach Orca", enabled.contains("; seam_slope_type = external"))
        assertTrue("Scarf speed must preserve percent units", enabled.contains("; scarf_joint_speed = 65%"))
        assertTrue("Scarf flow ratio must reach Orca", enabled.contains("; scarf_joint_flow_ratio = 0.92"))
        assertTrue("Scarf start height must preserve percent units", enabled.contains("; seam_slope_start_height = 15%"))
        assertTrue("Scarf step count must reach Orca", enabled.contains("; seam_slope_steps = 12"))
        assertTrue("Disabled control must remain disabled", disabled.contains("; seam_slope_type = none"))
        assertTrue(
            "A real cylindrical scarf joint must add multiple within-wall extrusion heights",
            extrusionZValues(enabled).size >= extrusionZValues(disabled).size + 4,
        )
    }

    @Test
    fun spiralVaseProducesContinuousZExtrusionOnDevice() {
        val outcome = OnDeviceSlicer.slice(
            fixtureModel(),
            SliceOptions()
                .selectPrinter(PrinterProfile.U1_04)
                .selectFilament(FilamentProfile.PLA)
                .selectQuality(QualityProfile.DRAFT)
                .withSpiralMode(true)
                .copy(
                    bottomSolidLayers = 3,
                    spiralModeSmooth = true,
                    spiralModeMaxXySmoothing = 250f,
                    spiralModeMaxXySmoothingPercent = true,
                    spiralStartingFlowRatio = 0.35f,
                    spiralFinishingFlowRatio = 0.2f,
                ),
        )

        val gcode = outcome.output.readText()
        val extrusionZValues = gcode.lineSequence()
            .filter { it.startsWith("G1 ") && it.contains(" E") && it.contains(" Z") }
            .mapNotNull { line ->
                line.split(' ').firstOrNull { it.startsWith("Z") }?.drop(1)?.toFloatOrNull()
            }
            .distinct()
            .take(32)
            .toList()

        assertTrue("Spiral mode must reach the slicing engine", gcode.contains("; spiral_mode = 1"))
        assertTrue("Smooth spiral mode must reach the slicing engine", gcode.contains("; spiral_mode_smooth = 1"))
        assertTrue(
            "Spiral XY smoothing must preserve percent units",
            gcode.contains("; spiral_mode_max_xy_smoothing = 250%"),
        )
        assertTrue("Spiral starting flow must reach the slicing engine", gcode.contains("; spiral_starting_flow_ratio = 0.35"))
        assertTrue("Spiral finishing flow must reach the slicing engine", gcode.contains("; spiral_finishing_flow_ratio = 0.2"))
        assertTrue(
            "Spiral vase must raise Z continuously during extrusion instead of stacking closed layer loops",
            extrusionZValues.size >= 16,
        )
    }

    @Test
    fun hollowSolidKeepsExteriorAndCavityContoursDistinctOnDevice() {
        val options = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.STANDARD)
            .copy(
                wallGenerator = "arachne",
                perimeters = 3,
                fillDensity = 0.18f,
                topSolidLayers = 4,
                bottomSolidLayers = 4,
            )
        val outcome = OnDeviceSlicer.slice(hollowTubeModel(), options)
        val middleLayer = (outcome.layers / 2).coerceAtLeast(1)
        val preview = loadGcodePreview(outcome.output.absolutePath, middleLayer, middleLayer)

        val centerX = options.bedSizeX / 2f
        val centerY = options.bedSizeY / 2f
        var exteriorOuterWalls = 0
        var cavityOuterWalls = 0
        var innerWalls = 0
        var cavityCrossings = 0
        preview.segments.indices.step(GcodeLayerPreview.SEGMENT_STRIDE).forEach { offset ->
            val x1 = preview.segments[offset]
            val y1 = preview.segments[offset + 1]
            val x2 = preview.segments[offset + 2]
            val y2 = preview.segments[offset + 3]
            val role = preview.segments[offset + 5].toInt()
            val midpointX = (x1 + x2) / 2f
            val midpointY = (y1 + y2) / 2f
            val centerDistance = maxOf(abs(midpointX - centerX), abs(midpointY - centerY))

            if (role == 0 && centerDistance > 12.5f) exteriorOuterWalls += 1
            if (role == 0 && centerDistance in 4f..7.5f) cavityOuterWalls += 1
            if (role == 1) innerWalls += 1
            if (abs(midpointX - centerX) < 3.5f && abs(midpointY - centerY) < 3.5f) {
                cavityCrossings += 1
            }
        }

        assertTrue("Orca must classify the exterior contour as an outer wall", exteriorOuterWalls > 0)
        assertTrue("Orca must classify the cavity contour as a surface-facing outer wall", cavityOuterWalls > 0)
        assertTrue("Orca must keep structural inner-wall shells separate", innerWalls > 0)
        assertEquals("No extrusion may cross the hollow cavity", 0, cavityCrossings)
    }

    @Test
    fun contourCompensationChangesGeneratedOuterWallGeometry() {
        val model = fixtureModel()
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.CUSTOM_CARTESIAN)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(
                skirtLoops = 0,
                brimWidth = 0f,
                elephantFootCompensation = 0f,
                xyContourCompensation = 0f,
            )
        val original = outerWallBounds(OnDeviceSlicer.slice(model, base).output)
        val expanded = outerWallBounds(
            OnDeviceSlicer.slice(model, base.copy(xyContourCompensation = 0.4f)).output,
        )

        assertTrue(
            "Positive contour compensation must expand generated X geometry",
            expanded.maxX - expanded.minX > original.maxX - original.minX + 0.4f,
        )
        assertTrue(
            "Positive contour compensation must expand generated Y geometry",
            expanded.maxY - expanded.minY > original.maxY - original.minY + 0.4f,
        )
    }

    @Test
    fun multipleObjectsReachTheOrcaProjectAndSliceTogether() {
        val modelFile = fixtureModel()
        val model = inspectModel(modelFile.absolutePath)
        val outcome = OnDeviceSlicer.slice(
            listOf(
                ProjectObject("left", model, ModelTransform(offsetXmm = -18f)),
                ProjectObject("right", model, ModelTransform(offsetXmm = 18f)),
            ),
            SliceOptions().selectQuality(QualityProfile.DRAFT),
        )

        assertTrue("A multi-object project must produce G-code", outcome.output.length() > 1_000L)
        assertTrue("Both objects must contribute layers", outcome.layers > 0)
        val gcode = outcome.output.readText()
        assertTrue(gcode.contains(";TYPE:Outer wall"))
        assertTrue(gcode.contains(";TYPE:Inner wall"))
    }

    @Test
    fun objectOutputControlsEmitFirmwareSpecificCancellationRanges() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject("left-object", model, ModelTransform(offsetXmm = -18f)),
            ProjectObject("right-object", model, ModelTransform(offsetXmm = 18f)),
        )
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(brimWidth = 0f, skirtLoops = 0)

        val klipper = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeFlavor = "klipper",
                gcodeSettings = GcodeSettings(labelObjects = true, excludeObjects = true),
            ),
        ).output.readText()
        val klipperStarts = klipper.lineSequence()
            .filter { it.startsWith("EXCLUDE_OBJECT_START NAME=") }
            .map { it.substringAfter('=') }
            .toSet()
        val klipperEnds = klipper.lineSequence()
            .filter { it.startsWith("EXCLUDE_OBJECT_END NAME=") }
            .map { it.substringAfter('=') }
            .toSet()
        assertEquals("Both Klipper objects need distinct cancellation ranges", 2, klipperStarts.size)
        assertEquals(klipperStarts, klipperEnds)
        assertTrue(klipper.contains("; printing object "))

        val marlin = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeFlavor = "marlin2",
                gcodeSettings = GcodeSettings(labelObjects = false, excludeObjects = true),
            ),
        ).output.readText()
        val marlinStarts = marlin.lineSequence()
            .mapNotNull { line -> Regex("^M486 S(\\d+)$").matchEntire(line)?.groupValues?.get(1) }
            .toSet()
        assertEquals("Both Marlin objects need distinct M486 ranges", 2, marlinStarts.size)
        assertTrue(marlin.lineSequence().any { it == "M486 S-1" })
        assertFalse(marlin.contains("; printing object "))

        val disabled = OnDeviceSlicer.slice(
            objects,
            base.copy(
                gcodeFlavor = "klipper",
                gcodeSettings = GcodeSettings(labelObjects = false, excludeObjects = false),
            ),
        ).output.readText()
        assertFalse(disabled.contains("; printing object "))
        assertFalse(disabled.contains("EXCLUDE_OBJECT_"))
    }

    @Test
    fun printSequenceChangesRealMultiObjectToolpathOrdering() {
        val model = inspectModel(fixtureModel().absolutePath)
        val objects = listOf(
            ProjectObject("first", model, ModelTransform(offsetXmm = -60f)),
            ProjectObject("second", model, ModelTransform(offsetXmm = 60f)),
        )
        val base = SliceOptions()
            .selectPrinter(PrinterProfile.U1_04)
            .selectFilament(FilamentProfile.PLA)
            .selectQuality(QualityProfile.DRAFT)
            .copy(brimWidth = 0f, skirtLoops = 0)
        val layered = OnDeviceSlicer.slice(
            objects,
            base.copy(printSequence = "by layer", printOrder = "as_obj_list"),
        ).output.readText()
        val sequential = OnDeviceSlicer.slice(
            objects,
            base.copy(printSequence = "by object", printOrder = "default"),
        ).output.readText()
        val layeredStarts = layered.lineSequence().filter { it.startsWith("; printing object ") }.toList()
        val sequentialStarts = sequential.lineSequence().filter { it.startsWith("; printing object ") }.toList()
        fun objectName(marker: String): String = marker
            .removePrefix("; printing object ")
            .substringBefore(" id:")
        fun objectTransitions(markers: List<String>): Int = markers
            .map(::objectName)
            .zipWithNext()
            .count { (left, right) -> left != right }

        assertTrue(layered.contains("; print_sequence = by layer"))
        assertTrue(layered.contains("; print_order = as_obj_list"))
        assertTrue(
            "Object-list order must begin with the first project object",
            objectName(layeredStarts.first()).contains("slicer-input-0-"),
        )
        assertTrue(sequential.contains("; print_sequence = by object"))
        assertTrue(sequential.contains("; print_order = default"))
        assertTrue(
            "By-object output must finish an object instead of alternating objects every layer",
            objectTransitions(sequentialStarts) < objectTransitions(layeredStarts),
        )
        assertEquals("Both sequential objects must reach G-code", 2, sequentialStarts.map(::objectName).toSet().size)

        val unsafeSequential = runCatching {
            OnDeviceSlicer.slice(
                listOf(
                    ProjectObject("overlap-first", model, ModelTransform(offsetXmm = -5f)),
                    ProjectObject("overlap-second", model, ModelTransform(offsetXmm = 5f)),
                ),
                base.copy(printSequence = "by object"),
            )
        }
        assertTrue(
            "By-object mode must retain Orca's print-head clearance rejection",
            unsafeSequential.isFailure,
        )
    }

    @Test
    fun customPrinterGeometryAndMotionReachOrca() {
        val model = fixtureModel()
        val customPrinter = PrinterProfile.CUSTOM_CARTESIAN.copy(
            bedSizeX = 180f,
            bedSizeY = 190f,
            bedOriginX = -90f,
            bedOriginY = -95f,
            bedPolygon = listOf(90f, 0f, 180f, 95f, 90f, 190f, 0f, 95f),
            maxPrintHeight = 180f,
            gcodeFlavor = "marlin2",
            maxSpeedX = 240f,
            maxSpeedY = 250f,
            maxAccelerationX = 4_200f,
            maxAccelerationY = 4_300f,
            maxAccelerationExtruding = 3_100f,
            maxAccelerationTravel = 4_000f,
            extruderClearanceRadius = 71f,
            extruderClearanceHeightToRod = 29f,
            extruderClearanceHeightToLid = 119f,
        )
        val options = SliceOptions()
            .selectPrinter(customPrinter)
            .selectFilament(FilamentProfile.GENERIC_PLA)
            .selectQuality(QualityProfile.STANDARD)
        val outcome = OnDeviceSlicer.slice(model, options)
        val gcode = outcome.output.readText()
        val printableArea = gcode.lineSequence().firstOrNull { it.startsWith("; printable_area =") }.orEmpty()

        assertTrue("The original negative X origin must reach Orca", printableArea.contains("-90x0"))
        assertTrue("The original negative Y origin must reach Orca", printableArea.contains("0x-95"))
        assertTrue("Custom bed width must reach Orca", printableArea.contains("90x0"))
        assertTrue("Custom bed depth must reach Orca", printableArea.contains("0x95"))
        assertTrue("Custom height must reach Orca", gcode.contains("; printable_height = 180"))
        assertTrue("Custom X speed must reach Orca", gcode.contains("; machine_max_speed_x = 240,240"))
        assertTrue("Custom Y speed must reach Orca", gcode.contains("; machine_max_speed_y = 250,250"))
        assertTrue("Custom X acceleration must reach Orca", gcode.contains("; machine_max_acceleration_x = 4200,4200"))
        assertTrue("Custom Y acceleration must reach Orca", gcode.contains("; machine_max_acceleration_y = 4300,4300"))
        assertTrue("Print-head radius must reach Orca", gcode.contains("; extruder_clearance_radius = 71"))
        assertTrue("Print-head rod clearance must reach Orca", gcode.contains("; extruder_clearance_height_to_rod = 29"))
        assertTrue("Print-head lid clearance must reach Orca", gcode.contains("; extruder_clearance_height_to_lid = 119"))
        assertTrue("Custom G-code flavor must reach Orca", gcode.contains("; gcode_flavor = marlin2"))
        val bounds = outerWallBounds(outcome.output)
        assertTrue("Centered-machine G-code must retain negative X coordinates", bounds.minX < -1f)
        assertTrue("Centered-machine G-code must retain positive X coordinates", bounds.maxX > 1f)
        assertTrue("Centered-machine G-code must retain negative Y coordinates", bounds.minY < -1f)
        assertTrue("Centered-machine G-code must retain positive Y coordinates", bounds.maxY > 1f)
    }

    @Test
    fun marlinAndKlipperFirmwareContractsReachOrca() {
        val model = fixtureModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = OrcaProfileCatalog(context).load()
        data class Contract(
            val flavor: String,
            val printerId: String,
            val processId: String,
            val expectedSettings: Map<String, String>,
        )
        val contracts = listOf(
            Contract(
                flavor = "marlin",
                printerId = "orca-printer-b0d4d6e7890c59e89228",
                processId = "orca-process-ce89c8ef371d6032980c",
                expectedSettings = mapOf(
                    "outer_wall_speed" to "40",
                    "inner_wall_speed" to "40",
                    "sparse_infill_speed" to "60",
                    "internal_solid_infill_speed" to "50",
                    "top_surface_speed" to "30",
                    "support_speed" to "40",
                    "bridge_speed" to "25",
                    "gap_infill_speed" to "30",
                    "initial_layer_infill_speed" to "35",
                    "support_interface_speed" to "80",
                    "bridge_flow" to "0.95",
                    "initial_layer_line_width" to "0.42",
                    "top_shell_thickness" to "0.8",
                    "support_interface_top_layers" to "3",
                    "support_interface_bottom_layers" to "-1",
                    "support_interface_spacing" to "0.2",
                    "support_top_z_distance" to "0.15",
                    "top_surface_line_width" to "0.4",
                    "internal_solid_infill_line_width" to "0.45",
                    "support_line_width" to "0.38",
                    "wall_generator" to "arachne",
                    "sparse_infill_pattern" to "crosshatch",
                    "top_surface_pattern" to "monotonicline",
                    "bottom_surface_pattern" to "monotonic",
                    "internal_solid_infill_pattern" to "monotonic",
                    "seam_position" to "aligned",
                    "ironing_type" to "no ironing",
                    "ironing_flow" to "15%",
                    "overhang_2_4_speed" to "20",
                    "support_base_pattern" to "rectilinear",
                    "support_style" to "grid",
                    "is_infill_first" to "0",
                    "infill_wall_overlap" to "25%",
                    "top_bottom_infill_wall_overlap" to "25%",
                    "internal_bridge_speed" to "150%",
                    "bridge_acceleration" to "50%",
                    "sparse_infill_acceleration" to "100%",
                    "internal_solid_infill_acceleration" to "100%",
                    "infill_combination" to "0",
                    "thick_internal_bridges" to "1",
                    "minimum_sparse_infill_area" to "10",
                    "infill_anchor" to "400%",
                    "gap_fill_target" to "nowhere",
                    "elefant_foot_compensation" to "0.1",
                    "ensure_vertical_shell_thickness" to "ensure_all",
                    "reduce_infill_retraction" to "1",
                    "small_perimeter_speed" to "50%",
                    "resolution" to "0.012",
                    "seam_gap" to "10%",
                    "wipe_speed" to "80%",
                ),
            ),
            Contract(
                flavor = "marlin2",
                printerId = "orca-printer-62803969e82d53d3720a",
                processId = "orca-process-f42a24b8fb07dff14515",
                expectedSettings = mapOf(
                    "outer_wall_speed" to "170",
                    "inner_wall_speed" to "170",
                    "sparse_infill_speed" to "200",
                    "internal_solid_infill_speed" to "200",
                    "top_surface_speed" to "100",
                    "support_speed" to "150",
                    "bridge_speed" to "25",
                    "gap_infill_speed" to "120",
                    "initial_layer_infill_speed" to "80",
                    "support_interface_speed" to "80",
                    "initial_layer_line_width" to "0.48",
                    "top_shell_thickness" to "0.8",
                    "support_interface_top_layers" to "2",
                    "support_interface_bottom_layers" to "2",
                    "support_top_z_distance" to "0.08",
                    "support_bottom_z_distance" to "0.08",
                    "default_acceleration" to "4000",
                    "outer_wall_acceleration" to "3000",
                    "inner_wall_acceleration" to "4000",
                    "top_surface_acceleration" to "1000",
                    "travel_acceleration" to "4000",
                    "initial_layer_acceleration" to "700",
                    "top_surface_line_width" to "0.375",
                    "internal_solid_infill_line_width" to "0.48",
                    "support_line_width" to "0.384",
                    "wall_generator" to "arachne",
                    "sparse_infill_pattern" to "crosshatch",
                    "top_surface_pattern" to "monotonicline",
                    "bottom_surface_pattern" to "monotonic",
                    "internal_solid_infill_pattern" to "monotonic",
                    "ironing_spacing" to "0.15",
                    "overhang_2_4_speed" to "50",
                    "support_interface_pattern" to "auto",
                    "is_infill_first" to "0",
                    "infill_wall_overlap" to "25%",
                    "internal_bridge_speed" to "50",
                    "bridge_density" to "100%",
                    "infill_combination_max_layer_height" to "100%",
                    "infill_direction" to "45",
                    "solid_infill_direction" to "45",
                    "infill_anchor_max" to "20",
                    "gap_fill_target" to "nowhere",
                    "detect_narrow_internal_solid_infill" to "1",
                    "reduce_infill_retraction" to "1",
                    "small_perimeter_speed" to "50%",
                    "resolution" to "0.012",
                    "seam_gap" to "10%",
                    "wall_direction" to "auto",
                ),
            ),
            Contract(
                flavor = "klipper",
                printerId = "orca-printer-8d5fc727726c00b46b13",
                processId = "orca-process-169e5f32752a1719ac3e",
                expectedSettings = mapOf(
                    "outer_wall_speed" to "120",
                    "inner_wall_speed" to "300",
                    "sparse_infill_speed" to "300",
                    "internal_solid_infill_speed" to "240",
                    "top_surface_speed" to "120",
                    "support_speed" to "150",
                    "bridge_speed" to "50",
                    "gap_infill_speed" to "200",
                    "initial_layer_infill_speed" to "60",
                    "support_interface_speed" to "80",
                    "bridge_flow" to "0.9",
                    "initial_layer_line_width" to "0.5",
                    "top_shell_thickness" to "1",
                    "bottom_shell_thickness" to "0.6",
                    "support_interface_top_layers" to "2",
                    "support_interface_bottom_layers" to "2",
                    "default_acceleration" to "10000",
                    "outer_wall_acceleration" to "5000",
                    "inner_wall_acceleration" to "10000",
                    "top_surface_acceleration" to "2000",
                    "travel_acceleration" to "10000",
                    "initial_layer_acceleration" to "5000",
                    "top_surface_line_width" to "0.42",
                    "internal_solid_infill_line_width" to "0.42",
                    "support_line_width" to "0.4",
                    "wall_generator" to "classic",
                    "sparse_infill_pattern" to "crosshatch",
                    "top_surface_pattern" to "monotonicline",
                    "bottom_surface_pattern" to "monotonic",
                    "internal_solid_infill_pattern" to "monotonic",
                    "ironing_speed" to "30",
                    "overhang_3_4_speed" to "40",
                    "support_style" to "default",
                    "is_infill_first" to "0",
                    "infill_wall_overlap" to "15%",
                    "internal_bridge_speed" to "150%",
                    "internal_bridge_density" to "100%",
                    "bridge_no_support" to "0",
                    "gap_fill_target" to "topbottom",
                    "ensure_vertical_shell_thickness" to "ensure_moderate",
                    "elefant_foot_compensation" to "0.15",
                    "max_bridge_length" to "10",
                    "reduce_infill_retraction" to "1",
                    "small_perimeter_speed" to "50%",
                    "slowdown_for_curled_perimeters" to "0",
                    "resolution" to "0.012",
                    "seam_gap" to "0",
                ),
            ),
        )

        for (contract in contracts) {
            val printer = requireNotNull(catalog.printers.find { it.id == contract.printerId })
            val process = requireNotNull(catalog.slicing.find { it.id == contract.processId })
            val outcome = OnDeviceSlicer.slice(
                model,
                SliceOptions()
                    .selectPrinter(printer)
                    .selectFilament(FilamentProfile.GENERIC_PLA)
                    .selectQuality(process),
            )
            val gcode = outcome.output.readText()
            val settings = gcode.lineSequence()
                .filter { it.startsWith("; ") && it.contains(" = ") }
                .associate { line -> line.removePrefix("; ").split(" = ", limit = 2).let { it[0] to it[1] } }

            assertEquals("${contract.flavor} metadata must reach Orca", contract.flavor, settings["gcode_flavor"])
            contract.expectedSettings.forEach { (key, value) ->
                assertEquals("${contract.flavor} must preserve $key", value, settings[key])
            }
            assertTrue("${contract.flavor} output must contain extrusion", gcode.contains(";TYPE:Outer wall"))
            if (contract.flavor == "klipper") {
                assertTrue(
                    "Klipper must use its native acceleration command",
                    gcode.contains("SET_VELOCITY_LIMIT ACCEL="),
                )
            } else {
                assertTrue("Marlin must use M204 acceleration", gcode.lineSequence().any { it.startsWith("M204 ") })
            }
        }
    }

    @Test
    fun representativeNozzleAndMaterialProfilesProduceRealGcode() {
        val model = fixtureModel()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = OrcaProfileCatalog(context).load()
        data class Contract(
            val printerName: String,
            val processName: String,
            val filamentName: String,
            val nozzleDiameter: Float,
            val material: String,
            val flavor: String,
        )
        val contracts = listOf(
            Contract(
                printerName = "Creality Ender-3 0.2 nozzle",
                processName = "0.12mm Fine @Creality Ender3 0.2",
                filamentName = "Creality Generic PLA",
                nozzleDiameter = 0.2f,
                material = "PLA",
                flavor = "marlin",
            ),
            Contract(
                printerName = "Elegoo Centauri 0.6 nozzle",
                processName = "0.18mm Fine @Elegoo C 0.6 nozzle",
                filamentName = "Elegoo PETG PRO @EC",
                nozzleDiameter = 0.6f,
                material = "PETG",
                flavor = "klipper",
            ),
            Contract(
                printerName = "Qidi Q1 Pro 0.8 nozzle",
                processName = "0.24mm Standard @Qidi Q1 Pro 0.8 nozzle",
                filamentName = "Generic ABS @System",
                nozzleDiameter = 0.8f,
                material = "ABS",
                flavor = "klipper",
            ),
        )

        contracts.forEach { contract ->
            val printer = catalog.printers.single {
                it.name == contract.printerName && it.id.startsWith("orca-printer-")
            }
            val process = catalog.slicing.single {
                it.name == contract.processName && it.id.startsWith("orca-process-")
            }
            val filament = catalog.filaments.single {
                it.name == contract.filamentName && it.id.startsWith("orca-filament-")
            }
            assertEquals(contract.nozzleDiameter, printer.nozzleDiameter, 0.001f)
            assertEquals(contract.nozzleDiameter, process.nozzleDiameter, 0.001f)
            assertTrue(process.compatiblePrinters.matchesPrinter(printer))
            assertTrue(filament.compatiblePrinters.matchesPrinter(printer))
            assertEquals(contract.material, filament.nativeName)

            val outcome = OnDeviceSlicer.slice(
                model,
                SliceOptions()
                    .selectPrinter(printer)
                    .selectFilament(filament)
                    .selectQuality(process),
            )
            try {
                assertTrue("${contract.printerName} must produce layers", outcome.layers > 0)
                assertTrue("${contract.printerName} must produce non-trivial G-code", outcome.output.length() > 1_000L)
                val gcode = outcome.output.readText()
                val settings = gcode.lineSequence()
                    .filter { it.startsWith("; ") && it.contains(" = ") }
                    .associate { line ->
                        line.removePrefix("; ").split(" = ", limit = 2).let { it[0] to it[1] }
                    }
                assertEquals(contract.flavor, settings["gcode_flavor"])
                assertEquals(contract.material, settings["filament_type"])
                assertEquals(contract.nozzleDiameter, settings.getValue("nozzle_diameter").toFloat(), 0.001f)
                assertEquals(process.layerHeightMm, settings.getValue("layer_height").toFloat(), 0.001f)
                assertEquals(filament.flowRatio, settings.getValue("filament_flow_ratio").toFloat(), 0.001f)
                assertEquals(
                    filament.maxVolumetricSpeed,
                    settings.getValue("filament_max_volumetric_speed").toFloat(),
                    0.001f,
                )
                assertTrue("${contract.printerName} must contain outer-wall extrusion", gcode.contains(";TYPE:Outer wall"))
            } finally {
                outcome.output.delete()
            }
        }
    }

}

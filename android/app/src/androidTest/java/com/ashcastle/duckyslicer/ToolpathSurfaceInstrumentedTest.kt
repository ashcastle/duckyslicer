package com.ashcastle.duckyslicer

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolpathSurfaceInstrumentedTest {
    @Test
    fun productionSurfaceBuildsDenseGeometryOffTheGlThread() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = AtomicReference<ToolpathSurfaceView>()
        val unavailable = AtomicBoolean(false)
        val preview = densePreview(segmentCount = GcodeLayerPreview.MAX_SEGMENTS)
        val intent = Intent(context, AccessibilityHarnessActivity::class.java)
        ActivityScenario.launch<AccessibilityHarnessActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val surface = ToolpathSurfaceView(activity) { unavailable.set(true) }
                activity.setContentView(
                    surface,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                surface.submit(
                    preview = preview,
                    bedSizeX = 220f,
                    bedSizeY = 220f,
                    bedOriginX = 0f,
                    bedOriginY = 0f,
                    bedPolygon = rectangularBedPolygon(220f, 220f),
                    opacity = 1f,
                    depthContrast = 0.8f,
                    visibleRoles = (0 until GcodeLayerPreview.ROLE_COUNT).toSet(),
                    // Exercise the worst render-plan reduction as well as the maximum
                    // accepted Preview payload on the actual production SurfaceView.
                    detail = PreviewDetail.PERFORMANCE,
                )
                view.set(surface)
            }

            val deadline = SystemClock.elapsedRealtime() + 20_000L
            while (!checkNotNull(view.get()).rendererReadyForTest() &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(20L)
            }
            assertFalse("The production depth renderer must remain available", unavailable.get())
            assertTrue("The first dense Preview frame must complete", view.get().rendererReadyForTest())
            assertTrue(
                "The background-built geometry must upload on the GL thread",
                view.get().geometryUploadCountForTest() in 1..2,
            )
        }
    }

    private fun densePreview(segmentCount: Int): GcodeLayerPreview {
        val segments = FloatArray(segmentCount * GcodeLayerPreview.SEGMENT_STRIDE)
        repeat(segmentCount) { index ->
            val offset = index * GcodeLayerPreview.SEGMENT_STRIDE
            val x = (index % 200).toFloat()
            val y = ((index / 200) % 200).toFloat()
            segments[offset] = x
            segments[offset + 1] = y
            segments[offset + 2] = x + 0.8f
            segments[offset + 3] = y
            segments[offset + 4] = 0.2f + index / 4_000 * 0.2f
            segments[offset + 5] = (index % GcodeLayerPreview.ROLE_COUNT).toFloat()
        }
        val roles = IntArray(GcodeLayerPreview.ROLE_COUNT)
        repeat(segmentCount) { index -> roles[index % roles.size] += 1 }
        return GcodeLayerPreview(
            startLayer = 0,
            endLayer = 4,
            layerCount = 5,
            minZMm = 0.2f,
            maxZMm = 1.0f,
            segments = segments,
            roleSegmentCounts = roles,
        )
    }
}

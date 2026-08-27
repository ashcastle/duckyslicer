package com.ashcastle.duckyslicer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelImportPerformanceInstrumentedTest {
    @Test
    fun denseBinaryStlUsesBoundedPrimitiveImportWithinBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = File(context.cacheDir, "dense-import-benchmark.stl")
        try {
            DenseBinaryStlFixture.writeTorus(modelFile, majorSegments = 400, minorSegments = 250)
            val nativeDurations = ArrayList<Long>()
            val decodeDurations = ArrayList<Long>()
            var decoded: ModelInfo? = null
            repeat(21) {
                val nativeStarted = SystemClock.elapsedRealtimeNanos()
                val raw = NativeEngine.inspectStlPayload(modelFile.absolutePath)
                nativeDurations += SystemClock.elapsedRealtimeNanos() - nativeStarted
                val decodeStarted = SystemClock.elapsedRealtimeNanos()
                decoded = ModelInfo.fromNative(raw, modelFile.absolutePath)
                decodeDurations += SystemClock.elapsedRealtimeNanos() - decodeStarted
            }
            val native = nativeDurations.drop(1).sorted()
            val decode = decodeDurations.drop(1).sorted()
            val nativeP95 = percentile95(native)
            val decodeP95 = percentile95(decode)
            val info = checkNotNull(decoded)
            println(
                "DuckyModelImport sourceTriangles=${info.triangles} " +
                    "previewTriangles=${info.previewTriangles.size / 9} " +
                    "detailPreviewTriangles=${info.detailPreviewTriangles.size / 9} " +
                    "nativeP50Ms=${native[native.size / 2] / 1_000_000.0} " +
                    "nativeP95Ms=${nativeP95 / 1_000_000.0} " +
                    "decodeP50Ms=${decode[decode.size / 2] / 1_000_000.0} " +
                    "decodeP95Ms=${decodeP95 / 1_000_000.0}",
            )
            assertEquals(200_000, info.triangles)
            assertTrue(info.previewTriangles.isNotEmpty())
            assertTrue(info.previewTriangles.size / 9 <= 12_000)
            assertTrue(info.detailPreviewTriangles.size / 9 in
                (info.previewTriangles.size / 9)..48_000)
            assertEquals(info.previewTriangles.size / 9, info.previewTriangleIndices.size)
            assertTrue(
                "200k-triangle native inspection must stay bounded: p95=${nativeP95 / 1_000_000.0} ms",
                nativeP95 / 1_000_000.0 <= 250.0,
            )
            assertTrue(
                "Primitive background model decoding must stay bounded: p95=${decodeP95 / 1_000_000.0} ms",
                decodeP95 / 1_000_000.0 <= 100.0,
            )
            assertTrue(
                "The complete native model boundary must stay responsive on one core",
                (nativeP95 + decodeP95) / 1_000_000.0 <= 300.0,
            )
        } finally {
            modelFile.delete()
        }
    }

    private fun percentile95(sortedDurations: List<Long>): Long {
        require(sortedDurations.size == 20) { "The benchmark requires 20 measured samples" }
        return sortedDurations[18]
    }

}

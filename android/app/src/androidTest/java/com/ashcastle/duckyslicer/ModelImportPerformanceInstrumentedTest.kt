package com.ashcastle.duckyslicer

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
        writeTorus(modelFile, majorSegments = 400, minorSegments = 250)
        try {
            val nativeDurations = ArrayList<Long>()
            val decodeDurations = ArrayList<Long>()
            var decoded: ModelInfo? = null
            repeat(4) {
                val nativeStarted = SystemClock.elapsedRealtimeNanos()
                val raw = NativeEngine.inspectStlPayload(modelFile.absolutePath)
                nativeDurations += SystemClock.elapsedRealtimeNanos() - nativeStarted
                val decodeStarted = SystemClock.elapsedRealtimeNanos()
                decoded = ModelInfo.fromNative(raw, modelFile.absolutePath)
                decodeDurations += SystemClock.elapsedRealtimeNanos() - decodeStarted
            }
            val native = nativeDurations.drop(1).sorted()
            val decode = decodeDurations.drop(1).sorted()
            val info = checkNotNull(decoded)
            println(
                "DuckyModelImport sourceTriangles=${info.triangles} " +
                    "previewTriangles=${info.previewTriangles.size / 9} " +
                    "nativeP50Ms=${native[native.size / 2] / 1_000_000.0} " +
                    "nativeP95Ms=${native.last() / 1_000_000.0} " +
                    "decodeP50Ms=${decode[decode.size / 2] / 1_000_000.0} " +
                    "decodeP95Ms=${decode.last() / 1_000_000.0}",
            )
            assertEquals(200_000, info.triangles)
            assertTrue(info.previewTriangles.isNotEmpty())
            assertTrue(info.previewTriangles.size / 9 <= 12_000)
            assertEquals(info.previewTriangles.size / 9, info.previewTriangleIndices.size)
            assertTrue(
                "200k-triangle native inspection must stay bounded: p95=${native.last() / 1_000_000.0} ms",
                native.last() / 1_000_000.0 <= 250.0,
            )
            assertTrue(
                "Primitive background model decoding must stay bounded: p95=${decode.last() / 1_000_000.0} ms",
                decode.last() / 1_000_000.0 <= 100.0,
            )
            assertTrue(
                "The complete native model boundary must stay responsive on one core",
                (native.last() + decode.last()) / 1_000_000.0 <= 300.0,
            )
        } finally {
            modelFile.delete()
        }
    }

    private fun writeTorus(file: File, majorSegments: Int, minorSegments: Int) {
        val triangleCount = majorSegments * minorSegments * 2
        BufferedOutputStream(file.outputStream(), 1024 * 1024).use { output ->
            output.write(ByteArray(80))
            output.write(littleEndianInt(triangleCount))
            val triangle = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
            repeat(majorSegments) { major ->
                repeat(minorSegments) { minor ->
                    val a = torusVertex(major, minor, majorSegments, minorSegments)
                    val b = torusVertex(major + 1, minor, majorSegments, minorSegments)
                    val c = torusVertex(major + 1, minor + 1, majorSegments, minorSegments)
                    val d = torusVertex(major, minor + 1, majorSegments, minorSegments)
                    writeTriangle(output, triangle, a, b, c)
                    writeTriangle(output, triangle, a, c, d)
                }
            }
        }
    }

    private fun torusVertex(
        majorIndex: Int,
        minorIndex: Int,
        majorSegments: Int,
        minorSegments: Int,
    ): FloatArray {
        val major = majorIndex.toDouble() / majorSegments * PI * 2.0
        val minor = minorIndex.toDouble() / minorSegments * PI * 2.0
        val radius = 35.0 + 12.0 * cos(minor)
        return floatArrayOf(
            (radius * cos(major) + 50.0).toFloat(),
            (radius * sin(major) + 50.0).toFloat(),
            (12.0 * sin(minor) + 15.0).toFloat(),
        )
    }

    private fun writeTriangle(
        output: BufferedOutputStream,
        buffer: ByteBuffer,
        a: FloatArray,
        b: FloatArray,
        c: FloatArray,
    ) {
        buffer.clear()
        repeat(3) { buffer.putFloat(0f) }
        arrayOf(a, b, c).forEach { vertex ->
            vertex.forEach(buffer::putFloat)
        }
        buffer.putShort(0)
        output.write(buffer.array())
    }

    private fun littleEndianInt(value: Int): ByteArray = ByteBuffer
        .allocate(Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()
}

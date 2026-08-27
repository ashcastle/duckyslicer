package com.ashcastle.duckyslicer

import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal object DenseBinaryStlFixture {
    fun writeTorus(file: File, majorSegments: Int, minorSegments: Int) {
        require(majorSegments >= 3)
        require(minorSegments >= 3)
        val triangleCount = Math.multiplyExact(Math.multiplyExact(majorSegments, minorSegments), 2)
        BufferedOutputStream(file.outputStream(), 1024 * 1024).use { output ->
            output.write(ByteArray(80))
            output.write(littleEndianInt(triangleCount))
            val triangle = ByteBuffer
                .allocate(BINARY_STL_TRIANGLE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
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
        arrayOf(a, b, c).forEach { vertex -> vertex.forEach(buffer::putFloat) }
        buffer.putShort(0)
        output.write(buffer.array())
    }

    private fun littleEndianInt(value: Int): ByteArray = ByteBuffer
        .allocate(Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private const val BINARY_STL_TRIANGLE_BYTES = 50
}

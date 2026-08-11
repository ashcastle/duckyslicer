package com.ashcastle.duckyslicer

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Orca manual Brim-ear point in the owning object's local model coordinates. */
data class BrimPoint(
    val xMm: Float,
    val yMm: Float,
    val zMm: Float,
    val radiusMm: Float,
) {
    init {
        require(listOf(xMm, yMm, zMm).all { it.isFinite() && abs(it) <= MAX_COORDINATE_MM }) {
            "Brim point position is invalid"
        }
        require(radiusMm.isFinite() && radiusMm in MIN_RADIUS_MM..MAX_RADIUS_MM) {
            "Brim point radius is invalid"
        }
    }

    companion object {
        const val MAX_COORDINATE_MM = 1_000_000f
        const val MIN_RADIUS_MM = 2.5f
        const val MAX_RADIUS_MM = 10f
        const val DEFAULT_RADIUS_MM = 4f
    }
}

data class BrimPoints(
    val points: List<BrimPoint> = emptyList(),
) {
    init {
        require(points.size <= MAX_POINTS) { "Too many Brim points" }
    }

    fun writeSidecar(output: File) {
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writer.writeInt(points.size)
                points.forEach { point ->
                    writer.writeFloat(point.xMm)
                    writer.writeFloat(point.yMm)
                    writer.writeFloat(point.zMm)
                    writer.writeFloat(point.radiusMm)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == HEADER_BYTES + points.size.toLong() * ENTRY_BYTES) {
            "Brim points could not be stored"
        }
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        const val MAX_POINTS = 256
        const val HEADER_BYTES = 8L
        const val ENTRY_BYTES = 16L
        const val MAX_SIDECAR_BYTES = HEADER_BYTES + MAX_POINTS.toLong() * ENTRY_BYTES
    }
}

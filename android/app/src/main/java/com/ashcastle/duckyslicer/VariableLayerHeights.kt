package com.ashcastle.duckyslicer

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

data class VariableLayerRange(
    val startRatio: Float,
    val endRatio: Float,
    val layerHeightMm: Float,
) {
    init {
        require(startRatio.isFinite() && startRatio in 0f..1f) {
            "Layer range start is invalid"
        }
        require(endRatio.isFinite() && endRatio in 0f..1f && endRatio > startRatio) {
            "Layer range end is invalid"
        }
        require(
            layerHeightMm.isFinite() &&
                layerHeightMm in MINIMUM_LAYER_HEIGHT_MM..MAXIMUM_LAYER_HEIGHT_MM,
        ) { "Layer height is invalid" }
    }

    companion object {
        const val MINIMUM_LAYER_HEIGHT_MM = 0.01f
        const val MAXIMUM_LAYER_HEIGHT_MM = 2f
    }
}

data class VariableLayerHeights(
    val ranges: List<VariableLayerRange> = emptyList(),
    val adaptiveQuality: Float? = null,
) {
    init {
        require(adaptiveQuality == null || adaptiveQuality.isFinite() && adaptiveQuality in 0f..1f) {
            "Adaptive layer quality is invalid"
        }
        require(adaptiveQuality == null || ranges.isEmpty()) {
            "Adaptive and manual layer heights cannot be combined"
        }
        require(ranges.size <= MAX_RANGES) { "Too many variable layer ranges" }
        require(ranges == ranges.sortedBy(VariableLayerRange::startRatio)) {
            "Variable layer ranges are not sorted"
        }
        ranges.zipWithNext().forEach { (previous, next) ->
            require(previous.endRatio <= next.startRatio) {
                "Variable layer ranges overlap"
            }
        }
    }

    val isConfigured: Boolean
        get() = adaptiveQuality != null || ranges.isNotEmpty()

    fun writeSidecar(output: File) {
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writer.writeInt(if (adaptiveQuality != null) MODE_ADAPTIVE else MODE_MANUAL)
                writer.writeFloat(adaptiveQuality ?: 0f)
                writer.writeInt(ranges.size)
                ranges.forEach { range ->
                    writer.writeFloat(range.startRatio)
                    writer.writeFloat(range.endRatio)
                    writer.writeFloat(range.layerHeightMm)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == HEADER_BYTES + ranges.size.toLong() * ENTRY_BYTES) {
            "Variable layer heights could not be stored"
        }
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), '2'.code.toByte())
        const val MODE_MANUAL = 0
        const val MODE_ADAPTIVE = 1
        const val MAX_RANGES = 32
        const val HEADER_BYTES = 16L
        const val ENTRY_BYTES = 12L
        const val MAX_SIDECAR_BYTES = HEADER_BYTES + MAX_RANGES.toLong() * ENTRY_BYTES
    }
}

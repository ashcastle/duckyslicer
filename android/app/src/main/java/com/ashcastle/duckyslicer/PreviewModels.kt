package com.ashcastle.duckyslicer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PreviewRenderPlan(
    val segmentOffsets: IntArray,
    val connectsToPrevious: BooleanArray,
)

data class GcodeLayerPreview(
    val startLayer: Int,
    val endLayer: Int,
    val layerCount: Int,
    val minZMm: Float,
    val maxZMm: Float,
    val segments: FloatArray,
    val roleSegmentCounts: IntArray,
) {
    internal fun buildRenderPlan(segmentBudget: Int): PreviewRenderPlan {
        val totalSegments = segments.size / SEGMENT_STRIDE
        if (totalSegments == 0) return PreviewRenderPlan(IntArray(0), BooleanArray(0))

        val safeBudget = segmentBudget.coerceAtLeast(1)
        if (totalSegments <= safeBudget) {
            return planForOffsets(IntArray(totalSegments) { it * SEGMENT_STRIDE })
        }

        val layers = mutableListOf<SegmentLayer>()
        var layerStart = 0
        var layerZ = segments[4]
        for (segmentIndex in 1 until totalSegments) {
            val z = segments[segmentIndex * SEGMENT_STRIDE + 4]
            if (abs(z - layerZ) > Z_EPSILON) {
                layers += SegmentLayer(layerStart, segmentIndex)
                layerStart = segmentIndex
                layerZ = z
            }
        }
        layers += SegmentLayer(layerStart, totalSegments)

        val averageSegmentsPerLayer = totalSegments.toFloat() / layers.size
        var selectedLayerCount = (safeBudget / averageSegmentsPerLayer)
            .toInt()
            .coerceIn(1, layers.size)
        val maximumBudget = max(safeBudget, safeBudget * 5 / 4)
        var selected = selectCompleteLayers(layers, selectedLayerCount)
        while (selected.size > maximumBudget && selectedLayerCount > 1) {
            selectedLayerCount -= 1
            selected = selectCompleteLayers(layers, selectedLayerCount)
        }
        if (selected.size > maximumBudget) selected = sampleEvenly(selected, maximumBudget)

        return planForOffsets(selected)
    }

    private fun selectCompleteLayers(layers: List<SegmentLayer>, targetCount: Int): IntArray {
        val layerIndices = if (targetCount == 1) {
            intArrayOf(layers.lastIndex)
        } else {
            IntArray(targetCount) { index ->
                (index * layers.lastIndex.toFloat() / (targetCount - 1)).roundToInt()
            }.distinct().toIntArray()
        }
        val selectedCount = layerIndices.sumOf { layers[it].endExclusive - layers[it].start }
        val selected = IntArray(selectedCount)
        var writeIndex = 0
        layerIndices.forEach { layerIndex ->
            val layer = layers[layerIndex]
            for (segmentIndex in layer.start until layer.endExclusive) {
                selected[writeIndex++] = segmentIndex * SEGMENT_STRIDE
            }
        }
        return selected
    }

    private fun sampleEvenly(source: IntArray, budget: Int): IntArray {
        if (source.size <= budget) return source
        if (budget == 1) return intArrayOf(source.last())
        return IntArray(budget) { index ->
            source[(index * (source.lastIndex.toFloat() / (budget - 1))).roundToInt()]
        }
    }

    private fun planForOffsets(offsets: IntArray): PreviewRenderPlan {
        val connections = BooleanArray(offsets.size)
        for (index in 1 until offsets.size) {
            val previous = offsets[index - 1]
            val current = offsets[index]
            if (current != previous + SEGMENT_STRIDE) continue
            val previousRole = segments[previous + 5].toInt()
            val currentRole = segments[current + 5].toInt()
            connections[index] = previousRole == currentRole &&
                abs(segments[previous + 2] - segments[current]) < Z_EPSILON &&
                abs(segments[previous + 3] - segments[current + 1]) < Z_EPSILON &&
                abs(segments[previous + 4] - segments[current + 4]) < Z_EPSILON
        }
        return PreviewRenderPlan(
            segmentOffsets = offsets,
            connectsToPrevious = connections,
        )
    }

    private data class SegmentLayer(val start: Int, val endExclusive: Int)

    companion object {
        const val SEGMENT_STRIDE = 6
        private const val Z_EPSILON = 0.001f

        fun fromNative(raw: FloatArray?): GcodeLayerPreview {
            checkNotNull(raw) { "preview_invalid" }
            check(raw.size >= HEADER_FLOATS) { "preview_header_invalid" }
            check(raw[0] == PAYLOAD_MAGIC && raw[1] == PAYLOAD_VERSION) {
                "preview_format_invalid"
            }
            val startLayer = raw.exactInt(2, MAX_LAYER_COUNT)
            val endLayer = raw.exactInt(3, MAX_LAYER_COUNT)
            val layerCount = raw.exactInt(4, MAX_LAYER_COUNT)
            check(startLayer <= endLayer) { "preview_range_invalid" }
            check(
                (layerCount == 0 && startLayer == 0 && endLayer == 0) ||
                    (layerCount > 0 && endLayer < layerCount),
            ) { "preview_layer_count_invalid" }
            val minZMm = raw[5]
            val maxZMm = raw[6]
            check(minZMm.isFinite() && maxZMm.isFinite() && minZMm <= maxZMm) {
                "preview_height_invalid"
            }
            val segmentFloatCount = raw.size - HEADER_FLOATS
            check(segmentFloatCount % SEGMENT_STRIDE == 0) { "preview_segment_invalid" }
            check(raw.size <= MAX_PAYLOAD_FLOATS && segmentFloatCount / SEGMENT_STRIDE <= MAX_SEGMENTS) {
                "preview_too_large"
            }
            val segments = raw.copyOfRange(HEADER_FLOATS, raw.size)
            val roleSegmentCounts = IntArray(ROLE_COUNT)
            segments.indices.step(SEGMENT_STRIDE).forEach { offset ->
                repeat(5) { axis ->
                    check(
                        segments[offset + axis].isFinite() &&
                            kotlin.math.abs(segments[offset + axis]) <= MAX_COORDINATE_ABS_MM,
                    ) { "preview_coordinate_invalid" }
                }
                val roleValue = segments[offset + 5]
                check(roleValue.isFinite() && roleValue % 1f == 0f) {
                    "preview_role_invalid"
                }
                val role = roleValue.toInt()
                check(role in 0 until ROLE_COUNT) { "preview_role_invalid" }
                roleSegmentCounts[role] += 1
            }
            return GcodeLayerPreview(
                startLayer = startLayer,
                endLayer = endLayer,
                layerCount = layerCount,
                minZMm = minZMm,
                maxZMm = maxZMm,
                segments = segments,
                roleSegmentCounts = roleSegmentCounts,
            )
        }

        internal const val ROLE_COUNT = 10
        internal const val MAX_SEGMENTS = 120_000
        internal const val MAX_PAYLOAD_FLOATS = 7 + MAX_SEGMENTS * SEGMENT_STRIDE
        private const val HEADER_FLOATS = 7
        private const val PAYLOAD_MAGIC = 17_491f
        private const val PAYLOAD_VERSION = 1f
        private const val MAX_LAYER_COUNT = 1_000_000
        private const val MAX_COORDINATE_ABS_MM = 1_000_000f
    }
}

private fun FloatArray.exactInt(index: Int, maximum: Int): Int {
    val value = this[index]
    check(value.isFinite() && value % 1f == 0f && value in 0f..maximum.toFloat()) {
        "preview_integer_invalid"
    }
    return value.toInt()
}

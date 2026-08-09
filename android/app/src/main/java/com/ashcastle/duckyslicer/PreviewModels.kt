package com.ashcastle.duckyslicer

import org.json.JSONObject
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

        fun fromJson(raw: String): GcodeLayerPreview {
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { "preview_invalid" }
            val source = json.getJSONArray("segments")
            val segments = FloatArray(source.length() * SEGMENT_STRIDE)
            val roleSegmentCounts = IntArray(ROLE_COUNT)
            repeat(source.length()) { index ->
                val segment = source.getJSONArray(index)
                check(segment.length() == SEGMENT_STRIDE) { "preview_segment_invalid" }
                repeat(SEGMENT_STRIDE) { axis ->
                    segments[index * SEGMENT_STRIDE + axis] = segment.getDouble(axis).toFloat()
                }
                val role = segments[index * SEGMENT_STRIDE + 5].toInt().coerceIn(0, ROLE_COUNT - 1)
                roleSegmentCounts[role] += 1
            }
            return GcodeLayerPreview(
                startLayer = json.getInt("startLayer"),
                endLayer = json.getInt("endLayer"),
                layerCount = json.getInt("layerCount"),
                minZMm = json.getDouble("minZMm").toFloat(),
                maxZMm = json.getDouble("maxZMm").toFloat(),
                segments = segments,
                roleSegmentCounts = roleSegmentCounts,
            )
        }

        internal const val ROLE_COUNT = 10
    }
}

package com.ashcastle.duckyslicer

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

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
        val roleStrides = IntArray(ROLE_COUNT) { role ->
            val roleCount = roleSegmentCounts.getOrElse(role) { 0 }
            val roleBudget = max(24, safeBudget * roleCount / totalSegments)
            ceil(roleCount.toFloat() / roleBudget.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        }
        val capacity = minOf(totalSegments, safeBudget + ROLE_COUNT * 26)
        val offsets = IntArray(capacity)
        val connections = BooleanArray(capacity)
        val roleSeen = IntArray(ROLE_COUNT)
        val roleHasDrawn = BooleanArray(ROLE_COUNT)
        val lastRawEnd = Array(ROLE_COUNT) { floatArrayOf(Float.NaN, Float.NaN, Float.NaN) }
        var previousRole = -1
        var selectedCount = 0
        var segmentOffset = 0

        while (segmentOffset + SEGMENT_STRIDE - 1 < segments.size) {
            val role = segments[segmentOffset + 5].toInt().coerceIn(0, ROLE_COUNT - 1)
            if (role != previousRole) {
                roleHasDrawn[role] = false
                previousRole = role
            }
            val seen = roleSeen[role]
            roleSeen[role] = seen + 1
            val startX = segments[segmentOffset]
            val startY = segments[segmentOffset + 1]
            val z = segments[segmentOffset + 4]
            val continuous = abs(lastRawEnd[role][0] - startX) < 0.001f &&
                abs(lastRawEnd[role][1] - startY) < 0.001f &&
                abs(lastRawEnd[role][2] - z) < 0.001f

            if (seen % roleStrides[role] == 0 && selectedCount < offsets.size) {
                offsets[selectedCount] = segmentOffset
                connections[selectedCount] = continuous && roleHasDrawn[role]
                roleHasDrawn[role] = true
                selectedCount += 1
            }
            lastRawEnd[role][0] = segments[segmentOffset + 2]
            lastRawEnd[role][1] = segments[segmentOffset + 3]
            lastRawEnd[role][2] = z
            segmentOffset += SEGMENT_STRIDE
        }

        return PreviewRenderPlan(
            segmentOffsets = offsets.copyOf(selectedCount),
            connectsToPrevious = connections.copyOf(selectedCount),
        )
    }

    companion object {
        const val SEGMENT_STRIDE = 6

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

        internal const val ROLE_COUNT = 8
    }
}

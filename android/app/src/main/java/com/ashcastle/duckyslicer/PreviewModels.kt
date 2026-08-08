package com.ashcastle.duckyslicer

import org.json.JSONObject

data class GcodeLayerPreview(
    val layer: Int,
    val layerCount: Int,
    val zMm: Float,
    val segments: FloatArray,
) {
    companion object {
        fun fromJson(raw: String): GcodeLayerPreview {
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { "preview_invalid" }
            val source = json.getJSONArray("segments")
            val segments = FloatArray(source.length() * 4)
            repeat(source.length()) { index ->
                val segment = source.getJSONArray(index)
                repeat(4) { axis -> segments[index * 4 + axis] = segment.getDouble(axis).toFloat() }
            }
            return GcodeLayerPreview(
                layer = json.getInt("layer"),
                layerCount = json.getInt("layerCount"),
                zMm = json.getDouble("zMm").toFloat(),
                segments = segments,
            )
        }
    }
}

package com.ashcastle.duckyslicer

import org.json.JSONObject

data class GcodeLayerPreview(
    val startLayer: Int,
    val endLayer: Int,
    val layerCount: Int,
    val minZMm: Float,
    val maxZMm: Float,
    val segments: FloatArray,
) {
    companion object {
        fun fromJson(raw: String): GcodeLayerPreview {
            val json = JSONObject(raw)
            check(json.optBoolean("ok")) { "preview_invalid" }
            val source = json.getJSONArray("segments")
            val segments = FloatArray(source.length() * 5)
            repeat(source.length()) { index ->
                val segment = source.getJSONArray(index)
                repeat(5) { axis -> segments[index * 5 + axis] = segment.getDouble(axis).toFloat() }
            }
            return GcodeLayerPreview(
                startLayer = json.getInt("startLayer"),
                endLayer = json.getInt("endLayer"),
                layerCount = json.getInt("layerCount"),
                minZMm = json.getDouble("minZMm").toFloat(),
                maxZMm = json.getDouble("maxZMm").toFloat(),
                segments = segments,
            )
        }
    }
}

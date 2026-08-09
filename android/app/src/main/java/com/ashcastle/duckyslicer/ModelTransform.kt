package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

data class ModelTransform(
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
    val rotationXdeg: Float = 0f,
    val rotationYdeg: Float = 0f,
    val rotationZdeg: Float = 0f,
    val scale: Float = 1f,
) {
    fun toJson(bedSizeX: Float, bedSizeY: Float): String = JSONObject()
        .put("bedCenterMm", JSONArray(listOf(bedSizeX / 2f, bedSizeY / 2f)))
        .put("offsetMm", JSONArray(listOf(offsetXmm, offsetYmm)))
        .put("rotationDeg", JSONArray(listOf(rotationXdeg, rotationYdeg, rotationZdeg)))
        .put("scale", scale)
        .toString()
}

internal fun ModelTransform.rotate(point: FloatArray): FloatArray {
    val rx = Math.toRadians(rotationXdeg.toDouble()).toFloat()
    val ry = Math.toRadians(rotationYdeg.toDouble()).toFloat()
    val rz = Math.toRadians(rotationZdeg.toDouble()).toFloat()
    val sinX = sin(rx)
    val cosX = cos(rx)
    val sinY = sin(ry)
    val cosY = cos(ry)
    val sinZ = sin(rz)
    val cosZ = cos(rz)

    val afterX = floatArrayOf(
        point[0],
        point[1] * cosX - point[2] * sinX,
        point[1] * sinX + point[2] * cosX,
    )
    val afterY = floatArrayOf(
        afterX[0] * cosY + afterX[2] * sinY,
        afterX[1],
        -afterX[0] * sinY + afterX[2] * cosY,
    )
    return floatArrayOf(
        afterY[0] * cosZ - afterY[1] * sinZ,
        afterY[0] * sinZ + afterY[1] * cosZ,
        afterY[2],
    )
}

internal fun ModelTransform.minimumRotatedZ(model: ModelInfo): Float {
    val center = FloatArray(3) { axis ->
        ((model.minMm[axis] + model.maxMm[axis]) / 2.0).toFloat()
    }
    var minimum = Float.POSITIVE_INFINITY
    var index = 0
    while (index + 2 < model.previewTriangles.size) {
        val rotated = rotate(
            floatArrayOf(
                (model.previewTriangles[index] - center[0]) * scale,
                (model.previewTriangles[index + 1] - center[1]) * scale,
                (model.previewTriangles[index + 2] - center[2]) * scale,
            ),
        )
        minimum = minOf(minimum, rotated[2])
        index += 3
    }
    return minimum.takeIf { it.isFinite() } ?: 0f
}

internal fun ModelTransform.placeVertex(
    x: Float,
    y: Float,
    z: Float,
    model: ModelInfo,
    bedSizeX: Float,
    bedSizeY: Float,
    minimumRotatedZ: Float,
): FloatArray {
    val center = FloatArray(3) { axis ->
        ((model.minMm[axis] + model.maxMm[axis]) / 2.0).toFloat()
    }
    val rotated = rotate(
        floatArrayOf(
            (x - center[0]) * scale,
            (y - center[1]) * scale,
            (z - center[2]) * scale,
        ),
    )
    return floatArrayOf(
        rotated[0] + bedSizeX / 2f + offsetXmm,
        rotated[1] + bedSizeY / 2f + offsetYmm,
        rotated[2] - minimumRotatedZ,
    )
}

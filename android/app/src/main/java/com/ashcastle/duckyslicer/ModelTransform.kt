package com.ashcastle.duckyslicer

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

data class ModelTransform(
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
    val offsetZmm: Float = 0f,
    val rotationXdeg: Float = 0f,
    val rotationYdeg: Float = 0f,
    val rotationZdeg: Float = 0f,
    val scale: Float = 1f,
    val scaleY: Float = scale,
    val scaleZ: Float = scale,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val mirrorZ: Boolean = false,
) {
    fun toJson(
        bedSizeX: Float,
        bedSizeY: Float,
        bedOriginX: Float = 0f,
        bedOriginY: Float = 0f,
    ): String = JSONObject()
        .put(
            "bedCenterMm",
            JSONArray(listOf(bedOriginX + bedSizeX / 2f, bedOriginY + bedSizeY / 2f)),
        )
        .put("offsetMm", JSONArray(listOf(offsetXmm, offsetYmm)))
        .put("offsetZMm", offsetZmm)
        .put("rotationDeg", JSONArray(listOf(rotationXdeg, rotationYdeg, rotationZdeg)))
        .put("scale", scale)
        .put("scaleAxes", JSONArray(listOf(scale, scaleY, scaleZ)))
        .put("mirror", JSONArray(listOf(mirrorX, mirrorY, mirrorZ)))
        .toString()

    internal fun withOrcaOrientation(orientation: OrcaOrientation): ModelTransform = copy(
        rotationXdeg = orientation.rotationRadians[0].toCanonicalDegreeFloat(),
        rotationYdeg = orientation.rotationRadians[1].toCanonicalDegreeFloat(),
        rotationZdeg = orientation.rotationRadians[2].toCanonicalDegreeFloat(),
    )
}

private fun Double.toCanonicalDegreeFloat(): Float =
    Math.toDegrees(this).toFloat().let { degrees -> if (degrees == 0f) 0f else degrees }

internal enum class ModelScaleAxis { X, Y, Z }

internal fun ModelTransform.hasUniformScale(tolerance: Float = 0.0001f): Boolean =
    abs(scale - scaleY) <= tolerance && abs(scale - scaleZ) <= tolerance

internal fun ModelTransform.withAxisScale(
    axis: ModelScaleAxis,
    requested: Float,
    keepProportions: Boolean,
    range: ClosedFloatingPointRange<Float>,
): ModelTransform {
    require(requested.isFinite() && range.start > 0f && range.endInclusive >= range.start) {
        "Axis scale is invalid"
    }
    val bounded = requested.coerceIn(range)
    if (!keepProportions) {
        return when (axis) {
            ModelScaleAxis.X -> copy(scale = bounded)
            ModelScaleAxis.Y -> copy(scaleY = bounded)
            ModelScaleAxis.Z -> copy(scaleZ = bounded)
        }
    }
    val scales = floatArrayOf(scale, scaleY, scaleZ)
    require(scales.all { it.isFinite() && it > 0f }) { "Current axis scale is invalid" }
    val current = scales[axis.ordinal]
    val minimumFactor = scales.maxOf { range.start / it }
    val maximumFactor = scales.minOf { range.endInclusive / it }
    val factor = (bounded / current).coerceIn(minimumFactor, maximumFactor)
    return copy(
        scale = scale * factor,
        scaleY = scaleY * factor,
        scaleZ = scaleZ * factor,
    )
}

internal fun ModelTransform.withFaceOnBed(triangle: FloatArray): ModelTransform {
    require(triangle.size == 9 && triangle.all(Float::isFinite)) {
        "Selected face is invalid"
    }
    val response = JSONObject(
        NativeEngine.layOnFace(
            JSONObject()
                .put("transform", JSONObject(toJson(0f, 0f)))
                .put("triangle", JSONArray(triangle.toList()))
                .toString(),
        ),
    )
    require(response.optBoolean("ok")) {
        response.optString("error").ifBlank { "Selected face could not be placed" }
    }
    val rotation = response.getJSONArray("rotationDeg")
    require(rotation.length() == 3) { "Selected face rotation is invalid" }
    val degrees = FloatArray(3) { index -> rotation.getDouble(index).toFloat() }
    require(degrees.all { it.isFinite() && it in -180f..180f }) {
        "Selected face rotation is invalid"
    }
    return copy(
        offsetZmm = 0f,
        rotationXdeg = degrees[0],
        rotationYdeg = degrees[1],
        rotationZdeg = degrees[2],
    )
}

internal data class OrcaOrientation(
    val rotationRadians: DoubleArray,
) {
    init {
        require(rotationRadians.size == 3 && rotationRadians.all { it.isFinite() }) {
            "Orca orientation is invalid"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is OrcaOrientation && rotationRadians.contentEquals(other.rotationRadians)

    override fun hashCode(): Int = rotationRadians.contentHashCode()
}

data class OrcaArrangement(
    val lowerLeftMm: FloatArray,
    val sizesMm: FloatArray,
    val centersMm: FloatArray,
) {
    init {
        require(lowerLeftMm.size >= 2 && lowerLeftMm.size % 2 == 0) {
            "Orca arrangement positions are invalid"
        }
        require(sizesMm.size == lowerLeftMm.size / 2 * 3) {
            "Orca arrangement sizes are invalid"
        }
        require(centersMm.size == lowerLeftMm.size) {
            "Orca arrangement centers are invalid"
        }
        require(
            lowerLeftMm.all { it.isFinite() } &&
                sizesMm.all { it.isFinite() && it > 0f } &&
                centersMm.all { it.isFinite() },
        ) {
            "Orca arrangement geometry is invalid"
        }
    }

    val objectCount: Int get() = lowerLeftMm.size / 2

    override fun equals(other: Any?): Boolean = other is OrcaArrangement &&
        lowerLeftMm.contentEquals(other.lowerLeftMm) &&
        sizesMm.contentEquals(other.sizesMm) &&
        centersMm.contentEquals(other.centersMm)

    override fun hashCode(): Int = 31 * (
        31 * lowerLeftMm.contentHashCode() + sizesMm.contentHashCode()
    ) + centersMm.contentHashCode()
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

internal fun ModelTransform.transformLocal(point: FloatArray): FloatArray = rotate(
    floatArrayOf(
        point[0] * scale * if (mirrorX) -1f else 1f,
        point[1] * scaleY * if (mirrorY) -1f else 1f,
        point[2] * scaleZ * if (mirrorZ) -1f else 1f,
    ),
)

internal data class ProjectObjectGeometry(
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
) {
    val center: FloatArray
        get() = floatArrayOf(
            (minX + maxX) / 2f,
            (minY + maxY) / 2f,
            (minZ + maxZ) / 2f,
        )
}

internal fun ProjectObject.geometry(): ProjectObjectGeometry = ProjectObjectGeometry(
    minX = volumes.minOf { it.model.minMm[0] }.toFloat(),
    minY = volumes.minOf { it.model.minMm[1] }.toFloat(),
    minZ = volumes.minOf { it.model.minMm[2] }.toFloat(),
    maxX = volumes.maxOf { it.model.maxMm[0] }.toFloat(),
    maxY = volumes.maxOf { it.model.maxMm[1] }.toFloat(),
    maxZ = volumes.maxOf { it.model.maxMm[2] }.toFloat(),
)

internal fun ModelTransform.minimumRotatedZ(projectObject: ProjectObject): Float {
    val center = projectObject.geometry().center
    var minimum = Float.POSITIVE_INFINITY
    projectObject.volumes.forEach { volume ->
        var index = 0
        while (index + 2 < volume.model.previewTriangles.size) {
            val rotated = transformLocal(
                floatArrayOf(
                    volume.model.previewTriangles[index] - center[0],
                    volume.model.previewTriangles[index + 1] - center[1],
                    volume.model.previewTriangles[index + 2] - center[2],
                ),
            )
            minimum = minOf(minimum, rotated[2])
            index += 3
        }
    }
    return minimum.takeIf { it.isFinite() } ?: 0f
}

internal fun ModelTransform.minimumRotatedZ(model: ModelInfo): Float {
    val center = FloatArray(3) { axis ->
        ((model.minMm[axis] + model.maxMm[axis]) / 2.0).toFloat()
    }
    var minimum = Float.POSITIVE_INFINITY
    var index = 0
    while (index + 2 < model.previewTriangles.size) {
        val rotated = transformLocal(
            floatArrayOf(
                model.previewTriangles[index] - center[0],
                model.previewTriangles[index + 1] - center[1],
                model.previewTriangles[index + 2] - center[2],
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
    geometry: ProjectObjectGeometry,
    bedSizeX: Float,
    bedSizeY: Float,
    minimumRotatedZ: Float,
): FloatArray {
    val center = geometry.center
    val rotated = transformLocal(
        floatArrayOf(
            x - center[0],
            y - center[1],
            z - center[2],
        ),
    )
    return floatArrayOf(
        rotated[0] + bedSizeX / 2f + offsetXmm,
        rotated[1] + bedSizeY / 2f + offsetYmm,
        rotated[2] - minimumRotatedZ + offsetZmm,
    )
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
    val rotated = transformLocal(
        floatArrayOf(
            x - center[0],
            y - center[1],
            z - center[2],
        ),
    )
    return floatArrayOf(
        rotated[0] + bedSizeX / 2f + offsetXmm,
        rotated[1] + bedSizeY / 2f + offsetYmm,
        rotated[2] - minimumRotatedZ + offsetZmm,
    )
}

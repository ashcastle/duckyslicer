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
        offsetZmm = 0f,
        rotationXdeg = orientation.rotationRadians[0].toCanonicalDegreeFloat(),
        rotationYdeg = orientation.rotationRadians[1].toCanonicalDegreeFloat(),
        rotationZdeg = orientation.rotationRadians[2].toCanonicalDegreeFloat(),
    )
}

internal data class ModelPlacementOrientation(
    val rotationXdeg: Float,
    val rotationYdeg: Float,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    val mirrorX: Boolean,
    val mirrorY: Boolean,
    val mirrorZ: Boolean,
)

internal fun ModelTransform.placementOrientation(): ModelPlacementOrientation =
    ModelPlacementOrientation(
        rotationXdeg = rotationXdeg,
        rotationYdeg = rotationYdeg,
        scaleX = scale,
        scaleY = scaleY,
        scaleZ = scaleZ,
        mirrorX = mirrorX,
        mirrorY = mirrorY,
        mirrorZ = mirrorZ,
    )

internal fun Double.toCanonicalDegreeFloat(): Float {
    val degrees = Math.toDegrees(this)
    val normalized = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    return if (abs(normalized) < ORIENTATION_ZERO_TOLERANCE_DEGREES) {
        0f
    } else {
        normalized.toFloat()
    }
}

private const val ORIENTATION_ZERO_TOLERANCE_DEGREES = 0.001

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

/**
 * Places a selected surface on the bed without trusting the source mesh's triangle winding.
 *
 * STL files in the wild frequently contain locally reversed facets. The native alignment uses the
 * facet normal, so evaluate both possible normals and keep only an orientation where the selected
 * plane is also the printable object's lowest plane. This prevents reporting success when some
 * unrelated part of the object is actually touching the bed.
 */
internal fun ProjectObject.withFaceOnBed(triangle: FloatArray): ModelTransform {
    require(triangle.size == 9 && triangle.all(Float::isFinite)) {
        "Selected face is invalid"
    }
    val reversed = triangle.copyOf().also { values ->
        repeat(3) { axis ->
            val second = values[3 + axis]
            values[3 + axis] = values[6 + axis]
            values[6 + axis] = second
        }
    }
    return selectSupportingFaceTransform(
        triangle = triangle,
        candidates = listOf(
            transform.withFaceOnBed(triangle),
            transform.withFaceOnBed(reversed),
        ),
    )
}

internal fun ProjectObject.selectSupportingFaceTransform(
    triangle: FloatArray,
    candidates: List<ModelTransform>,
    bedToleranceMm: Float = LAY_ON_FACE_BED_TOLERANCE_MM,
): ModelTransform {
    require(triangle.size == 9 && triangle.all(Float::isFinite)) {
        "Selected face is invalid"
    }
    require(candidates.isNotEmpty() && bedToleranceMm.isFinite() && bedToleranceMm >= 0f) {
        "Selected face candidates are invalid"
    }
    val center = geometry().center
    return candidates
        .asSequence()
        .mapNotNull { candidate ->
            if (!candidate.hasFinitePlacement()) return@mapNotNull null
            val minimumZ = candidate.minimumRotatedZ(this)
            if (!minimumZ.isFinite()) return@mapNotNull null
            val faceZ = FloatArray(3) { vertex ->
                candidate.transformLocal(
                    FloatArray(3) { axis -> triangle[vertex * 3 + axis] - center[axis] },
                )[2]
            }
            if (faceZ.any { !it.isFinite() }) return@mapNotNull null
            val planeError = faceZ.max() - faceZ.min()
            val bedGap = faceZ.average().toFloat() - minimumZ
            if (planeError > bedToleranceMm || bedGap < -bedToleranceMm) {
                return@mapNotNull null
            }
            SupportingFaceCandidate(candidate, bedGap.coerceAtLeast(0f), planeError)
        }
        .filter { candidate -> candidate.bedGapMm <= bedToleranceMm }
        .minWithOrNull(
            compareBy(
                SupportingFaceCandidate::bedGapMm,
                SupportingFaceCandidate::planeErrorMm,
            ),
        )
        ?.transform
        ?: throw IllegalArgumentException("Selected surface is not a supporting bed face")
}

private data class SupportingFaceCandidate(
    val transform: ModelTransform,
    val bedGapMm: Float,
    val planeErrorMm: Float,
)

private fun ModelTransform.hasFinitePlacement(): Boolean = listOf(
    offsetXmm,
    offsetYmm,
    offsetZmm,
    rotationXdeg,
    rotationYdeg,
    rotationZdeg,
    scale,
    scaleY,
    scaleZ,
).all(Float::isFinite) && scale > 0f && scaleY > 0f && scaleZ > 0f

private const val LAY_ON_FACE_BED_TOLERANCE_MM = 0.05f

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
    val rotationZRadians: FloatArray = FloatArray(lowerLeftMm.size / 2),
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
        require(rotationZRadians.size == lowerLeftMm.size / 2) {
            "Orca arrangement rotations are invalid"
        }
        require(
            lowerLeftMm.all { it.isFinite() } &&
                sizesMm.all { it.isFinite() && it > 0f } &&
                centersMm.all { it.isFinite() } &&
                rotationZRadians.all { it.isFinite() },
        ) {
            "Orca arrangement geometry is invalid"
        }
    }

    val objectCount: Int get() = lowerLeftMm.size / 2

    override fun equals(other: Any?): Boolean = other is OrcaArrangement &&
        lowerLeftMm.contentEquals(other.lowerLeftMm) &&
        sizesMm.contentEquals(other.sizesMm) &&
        centersMm.contentEquals(other.centersMm) &&
        rotationZRadians.contentEquals(other.rotationZRadians)

    override fun hashCode(): Int = 31 * (
        31 * (
            31 * lowerLeftMm.contentHashCode() + sizesMm.contentHashCode()
        ) + centersMm.contentHashCode()
    ) + rotationZRadians.contentHashCode()
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

internal fun ModelTransform.transformBrimPointsForSlicing(
    brimPoints: BrimPoints,
    sourceCenterMm: FloatArray,
    transformedMinZ: Float,
    bedCenterXmm: Float,
    bedCenterYmm: Float,
): BrimPoints {
    require(sourceCenterMm.size == 3 && sourceCenterMm.all(Float::isFinite)) {
        "Model transform frame is invalid"
    }
    require(transformedMinZ.isFinite()) { "Model transform height is invalid" }
    return BrimPoints(
        brimPoints.points.map { point ->
            val transformed = transformLocal(
                floatArrayOf(
                    point.xMm - sourceCenterMm[0],
                    point.yMm - sourceCenterMm[1],
                    point.zMm - sourceCenterMm[2],
                ),
            )
            BrimPoint(
                xMm = transformed[0] + bedCenterXmm + offsetXmm,
                yMm = transformed[1] + bedCenterYmm + offsetYmm,
                zMm = transformed[2] - transformedMinZ + offsetZmm,
                radiusMm = point.radiusMm,
            )
        },
    )
}

internal fun ModelTransform.placeBrimPoint(
    point: BrimPoint,
    projectObject: ProjectObject,
    bedSizeX: Float,
    bedSizeY: Float,
): FloatArray = placeVertex(
    point.xMm,
    point.yMm,
    point.zMm,
    projectObject.geometry(),
    bedSizeX,
    bedSizeY,
    minimumRotatedZ(projectObject),
)

internal fun ModelTransform.manualBrimPointAtBed(
    projectObject: ProjectObject,
    worldXmm: Float,
    worldYmm: Float,
    bedSizeX: Float,
    bedSizeY: Float,
    radiusMm: Float = BrimPoint.DEFAULT_RADIUS_MM,
    footprint: PlacedModelFootprint = projectObject.placedModelFootprint(bedSizeX, bedSizeY),
): BrimPoint? {
    if (!worldXmm.isFinite() || !worldYmm.isFinite() || offsetZmm > 0.05f) return null
    if (!footprint.contains(worldXmm, worldYmm)) return null
    val local = placedWorldToLocal(
        projectObject = projectObject,
        worldXmm = worldXmm,
        worldYmm = worldYmm,
        worldZmm = 0f,
        bedSizeX = bedSizeX,
        bedSizeY = bedSizeY,
    ) ?: return null
    return runCatching {
        BrimPoint(local[0], local[1], local[2], radiusMm)
    }.getOrNull()
}

internal fun ProjectObject.defaultManualBrimPoint(
    bedSizeX: Float,
    bedSizeY: Float,
    radiusMm: Float = BrimPoint.DEFAULT_RADIUS_MM,
): BrimPoint? {
    if (transform.offsetZmm > 0.05f) return null
    val geometry = geometry()
    val minimumRotatedZ = transform.minimumRotatedZ(this)
    var bestLocal: FloatArray? = null
    var bestWorld: FloatArray? = null
    modelPartVolumes.forEach { volume ->
        val vertices = volume.model.previewTriangles
        var index = 0
        while (index + 2 < vertices.size) {
            val world = transform.placeVertex(
                vertices[index], vertices[index + 1], vertices[index + 2], geometry,
                bedSizeX, bedSizeY, minimumRotatedZ,
            )
            val previous = bestWorld
            if (
                previous == null || world[2] < previous[2] - 0.0001f ||
                (
                    abs(world[2] - previous[2]) <= 0.0001f &&
                        (world[0] < previous[0] ||
                            (world[0] == previous[0] && world[1] < previous[1]))
                    )
            ) {
                bestLocal = floatArrayOf(vertices[index], vertices[index + 1], vertices[index + 2])
                bestWorld = world
            }
            index += 3
        }
    }
    val lowestLocal = bestLocal ?: return null
    if (bestWorld?.get(2)?.let { it > 0.05f } != false) return null
    return runCatching {
        BrimPoint(lowestLocal[0], lowestLocal[1], lowestLocal[2], radiusMm)
    }.getOrNull()
}

internal data class PlacedModelFootprint(
    private val triangles: FloatArray,
) {
    init {
        require(triangles.size % 6 == 0 && triangles.all(Float::isFinite)) {
            "Model footprint is invalid"
        }
    }

    fun contains(worldXmm: Float, worldYmm: Float): Boolean {
        if (!worldXmm.isFinite() || !worldYmm.isFinite()) return false
        var index = 0
        while (index + 5 < triangles.size) {
            if (
                pointInWorldTriangle(
                    worldXmm,
                    worldYmm,
                    triangles[index],
                    triangles[index + 1],
                    triangles[index + 2],
                    triangles[index + 3],
                    triangles[index + 4],
                    triangles[index + 5],
                )
            ) {
                return true
            }
            index += 6
        }
        return false
    }
}

internal fun ProjectObject.placedModelFootprint(
    bedSizeX: Float,
    bedSizeY: Float,
): PlacedModelFootprint {
    val geometry = geometry()
    val minimumRotatedZ = transform.minimumRotatedZ(this)
    val triangleCount = modelPartVolumes.sumOf { it.model.previewTriangles.size / 9 }
    val footprint = FloatArray(triangleCount * 6)
    var output = 0
    modelPartVolumes.forEach { volume ->
        val triangles = volume.model.previewTriangles
        var index = 0
        while (index + 8 < triangles.size) {
            val a = transform.placeVertex(
                triangles[index], triangles[index + 1], triangles[index + 2],
                geometry, bedSizeX, bedSizeY, minimumRotatedZ,
            )
            val b = transform.placeVertex(
                triangles[index + 3], triangles[index + 4], triangles[index + 5],
                geometry, bedSizeX, bedSizeY, minimumRotatedZ,
            )
            val c = transform.placeVertex(
                triangles[index + 6], triangles[index + 7], triangles[index + 8],
                geometry, bedSizeX, bedSizeY, minimumRotatedZ,
            )
            footprint[output++] = a[0]
            footprint[output++] = a[1]
            footprint[output++] = b[0]
            footprint[output++] = b[1]
            footprint[output++] = c[0]
            footprint[output++] = c[1]
            index += 9
        }
    }
    return PlacedModelFootprint(if (output == footprint.size) footprint else footprint.copyOf(output))
}

private fun ModelTransform.placedWorldToLocal(
    projectObject: ProjectObject,
    worldXmm: Float,
    worldYmm: Float,
    worldZmm: Float,
    bedSizeX: Float,
    bedSizeY: Float,
): FloatArray? {
    val minimumRotatedZ = minimumRotatedZ(projectObject)
    val rotated = floatArrayOf(
        worldXmm - bedSizeX / 2f - offsetXmm,
        worldYmm - bedSizeY / 2f - offsetYmm,
        worldZmm + minimumRotatedZ - offsetZmm,
    )
    val unrotated = inverseRotate(rotated)
    val scales = floatArrayOf(
        scale * if (mirrorX) -1f else 1f,
        scaleY * if (mirrorY) -1f else 1f,
        scaleZ * if (mirrorZ) -1f else 1f,
    )
    if (scales.any { !it.isFinite() || abs(it) < 0.000001f }) return null
    val center = projectObject.geometry().center
    return FloatArray(3) { axis -> unrotated[axis] / scales[axis] + center[axis] }
        .takeIf { it.all(Float::isFinite) }
}

private fun ModelTransform.inverseRotate(point: FloatArray): FloatArray {
    val rx = Math.toRadians(rotationXdeg.toDouble()).toFloat()
    val ry = Math.toRadians(rotationYdeg.toDouble()).toFloat()
    val rz = Math.toRadians(rotationZdeg.toDouble()).toFloat()
    val sinX = sin(rx)
    val cosX = cos(rx)
    val sinY = sin(ry)
    val cosY = cos(ry)
    val sinZ = sin(rz)
    val cosZ = cos(rz)
    val afterZ = floatArrayOf(
        point[0] * cosZ + point[1] * sinZ,
        -point[0] * sinZ + point[1] * cosZ,
        point[2],
    )
    val afterY = floatArrayOf(
        afterZ[0] * cosY - afterZ[2] * sinY,
        afterZ[1],
        afterZ[0] * sinY + afterZ[2] * cosY,
    )
    return floatArrayOf(
        afterY[0],
        afterY[1] * cosX + afterY[2] * sinX,
        -afterY[1] * sinX + afterY[2] * cosX,
    )
}

private fun pointInWorldTriangle(
    x: Float,
    y: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
): Boolean {
    val denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if (abs(denominator) <= 0.000001f) return false
    val first = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator
    val second = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator
    val third = 1f - first - second
    return first >= -0.001f && second >= -0.001f && third >= -0.001f
}

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

internal fun ProjectObject.geometry(): ProjectObjectGeometry {
    val printable = modelPartVolumes
    return ProjectObjectGeometry(
        minX = printable.minOf { it.model.minMm[0] }.toFloat(),
        minY = printable.minOf { it.model.minMm[1] }.toFloat(),
        minZ = printable.minOf { it.model.minMm[2] }.toFloat(),
        maxX = printable.maxOf { it.model.maxMm[0] }.toFloat(),
        maxY = printable.maxOf { it.model.maxMm[1] }.toFloat(),
        maxZ = printable.maxOf { it.model.maxMm[2] }.toFloat(),
    )
}

internal fun ModelTransform.minimumRotatedZ(projectObject: ProjectObject): Float {
    val geometry = projectObject.geometry()
    val centerX = (geometry.minX + geometry.maxX) / 2f
    val centerY = (geometry.minY + geometry.maxY) / 2f
    val centerZ = (geometry.minZ + geometry.maxZ) / 2f
    minimumZWithoutTilt(geometry, centerZ)?.let { return it }
    val calculator = MinimumRotatedZCalculator(this)
    var minimum = Float.POSITIVE_INFINITY
    projectObject.modelPartVolumes.forEach { volume ->
        minimum = minOf(
            minimum,
            calculator.minimum(volume.model.placementVertices, centerX, centerY, centerZ),
        )
    }
    return minimum.takeIf { it.isFinite() } ?: 0f
}

internal fun ModelTransform.placedHeight(projectObject: ProjectObject): Float {
    val geometry = projectObject.geometry()
    val minimumRotatedZ = minimumRotatedZ(projectObject)
    var maximum = Float.NEGATIVE_INFINITY
    projectObject.modelPartVolumes.forEach { volume ->
        val vertices = volume.model.placementVertices
        var index = 0
        while (index + 2 < vertices.size) {
            val placed = placeVertex(
                vertices[index],
                vertices[index + 1],
                vertices[index + 2],
                geometry,
                bedSizeX = 0f,
                bedSizeY = 0f,
                minimumRotatedZ = minimumRotatedZ,
            )
            maximum = maxOf(maximum, placed[2])
            index += 3
        }
    }
    return (maximum - offsetZmm).takeIf { it.isFinite() && it > 0f }
        ?: ((geometry.maxZ - geometry.minZ) * scaleZ).coerceAtLeast(HeightRangeModifiers.MIN_RANGE_MM)
}

internal fun ModelTransform.minimumRotatedZ(model: ModelInfo): Float {
    val centerX = ((model.minMm[0] + model.maxMm[0]) / 2.0).toFloat()
    val centerY = ((model.minMm[1] + model.maxMm[1]) / 2.0).toFloat()
    val centerZ = ((model.minMm[2] + model.maxMm[2]) / 2.0).toFloat()
    val geometry = ProjectObjectGeometry(
        minX = model.minMm[0].toFloat(),
        minY = model.minMm[1].toFloat(),
        minZ = model.minMm[2].toFloat(),
        maxX = model.maxMm[0].toFloat(),
        maxY = model.maxMm[1].toFloat(),
        maxZ = model.maxMm[2].toFloat(),
    )
    minimumZWithoutTilt(geometry, centerZ)?.let { return it }
    val calculator = MinimumRotatedZCalculator(this)
    val minimum = calculator.minimum(model.placementVertices, centerX, centerY, centerZ)
    return minimum.takeIf { it.isFinite() } ?: 0f
}

/**
 * Z-axis rotation cannot change bed height. With no X/Y tilt, the exact minimum comes from the
 * model bounds, so dense meshes do not need a vertex scan during import, translation, or Z rotate.
 */
private fun ModelTransform.minimumZWithoutTilt(
    geometry: ProjectObjectGeometry,
    centerZ: Float,
): Float? {
    if (rotationXdeg != 0f || rotationYdeg != 0f) return null
    val signedScaleZ = scaleZ * if (mirrorZ) -1f else 1f
    val extremumZ = if (signedScaleZ >= 0f) geometry.minZ else geometry.maxZ
    return ((extremumZ - centerZ) * signedScaleZ).takeIf(Float::isFinite)
}

/**
 * Computes only the transformed Z component needed for bed placement. Z-axis rotation cannot
 * change that component, so this avoids the temporary arrays created by the general 3D transform
 * while preserving the exact X-then-Y rotation order used by [transformLocal].
 */
private class MinimumRotatedZCalculator(transform: ModelTransform) {
    private val scaleX = transform.scale * if (transform.mirrorX) -1f else 1f
    private val scaleY = transform.scaleY * if (transform.mirrorY) -1f else 1f
    private val scaleZ = transform.scaleZ * if (transform.mirrorZ) -1f else 1f
    private val sinX = sin(Math.toRadians(transform.rotationXdeg.toDouble()).toFloat())
    private val cosX = cos(Math.toRadians(transform.rotationXdeg.toDouble()).toFloat())
    private val sinY = sin(Math.toRadians(transform.rotationYdeg.toDouble()).toFloat())
    private val cosY = cos(Math.toRadians(transform.rotationYdeg.toDouble()).toFloat())

    fun minimum(vertices: FloatArray, centerX: Float, centerY: Float, centerZ: Float): Float {
        var result = Float.POSITIVE_INFINITY
        var index = 0
        while (index + 2 < vertices.size) {
            val scaledX = (vertices[index] - centerX) * scaleX
            val scaledY = (vertices[index + 1] - centerY) * scaleY
            val scaledZ = (vertices[index + 2] - centerZ) * scaleZ
            val afterXz = scaledY * sinX + scaledZ * cosX
            result = minOf(result, -scaledX * sinY + afterXz * cosY)
            index += 3
        }
        return result
    }
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

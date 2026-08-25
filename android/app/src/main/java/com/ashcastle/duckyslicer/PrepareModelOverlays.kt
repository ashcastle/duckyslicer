package com.ashcastle.duckyslicer

internal data class PrepareOverlayColor(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
)

internal data class PrepareModelOverlayData(
    val meshIndex: Int,
    val fillIndices: IntArray,
    val lineIndices: IntArray,
    val fillColor: PrepareOverlayColor,
    val lineColor: PrepareOverlayColor,
    val customVertices: FloatArray? = null,
)

internal data class PrepareModelOverlayKey(
    val objectId: String,
    val volumeId: String,
    val model: ModelInfo,
    val supportPaint: SupportPaint,
    val seamPaint: SeamPaint,
    val multiColorPaint: MultiColorPaint,
    val orcaFacetAnnotations: OrcaFacetAnnotations,
)

internal object PrepareModelOverlayBuilder {
    fun build(
        projectObjects: List<ProjectObject>,
        layOnFaceObjectId: String?,
        layOnFaceCandidateFacets: Map<String, BooleanArray>,
        filamentColors: List<Int> = DefaultFilamentColors,
    ): List<PrepareModelOverlayData> {
        val resolvedColors = filamentColors.previewFilamentColors()
        if (
            layOnFaceObjectId == null && projectObjects.none { projectObject ->
                projectObject.volumes.any { volume ->
                    volume.multiColorPaint.facets.isNotEmpty() ||
                        volume.supportPaint.facets.isNotEmpty() ||
                        volume.seamPaint.facets.isNotEmpty() ||
                        !volume.orcaFacetAnnotations.isEmpty
                }
            }
        ) {
            return emptyList()
        }
        val overlays = ArrayList<PrepareModelOverlayData>()
        var remainingSplitTriangles = MAX_EXACT_SPLIT_OVERLAY_TRIANGLES
        var meshIndex = 0
        projectObjects.forEach { projectObject ->
            projectObject.volumes.forEach { volume ->
                val trianglesByStyle = linkedMapOf<PrepareOverlayStyle, MutableList<Int>>()
                val splitTrianglesByStyle = linkedMapOf<PrepareOverlayStyle, MutableList<Float>>()
                val previewTriangleCount = volume.model.previewTriangles.size / 9
                repeat(previewTriangleCount) { previewTriangleIndex ->
                    val sourceFacetIndex = volume.model.previewTriangleIndices
                        .getOrElse(previewTriangleIndex) { previewTriangleIndex }
                    if (
                        projectObject.id == layOnFaceObjectId &&
                        layOnFaceCandidateFacets[volume.id]
                            ?.getOrNull(previewTriangleIndex) == true
                    ) {
                        trianglesByStyle.add(PrepareOverlayStyle.LayOnFace, previewTriangleIndex)
                    }
                    volume.multiColorPaint.facets[sourceFacetIndex]?.let { slot ->
                        trianglesByStyle.add(
                            PrepareOverlayStyle.MultiColor(slot, resolvedColors[slot]),
                            previewTriangleIndex,
                        )
                    }
                    if (sourceFacetIndex !in volume.multiColorPaint.facets) {
                        volume.orcaFacetAnnotations.multiColor.triangles[sourceFacetIndex]?.let { value ->
                            addExactAnnotation(
                                value = value,
                                styleForState = { state ->
                                    state.takeIf { it > 0 }
                                        ?.let { slot ->
                                            PrepareOverlayStyle.MultiColor(
                                                slot - 1,
                                                resolvedColors[slot - 1],
                                            )
                                        }
                                },
                                previewTriangles = volume.model.previewTriangles,
                                previewTriangleIndex = previewTriangleIndex,
                                trianglesByStyle = trianglesByStyle,
                                splitTrianglesByStyle = splitTrianglesByStyle,
                                remainingSplitTriangles = remainingSplitTriangles,
                            ).also { remainingSplitTriangles -= it }
                        }
                    }
                    when (volume.supportPaint.facets[sourceFacetIndex]) {
                        SupportPaintState.ENFORCE -> trianglesByStyle.add(
                            PrepareOverlayStyle.SupportEnforce,
                            previewTriangleIndex,
                        )
                        SupportPaintState.BLOCK -> trianglesByStyle.add(
                            PrepareOverlayStyle.SupportBlock,
                            previewTriangleIndex,
                        )
                        null -> Unit
                    }
                    if (sourceFacetIndex !in volume.supportPaint.facets) {
                        volume.orcaFacetAnnotations.support.triangles[sourceFacetIndex]?.let { value ->
                            addExactAnnotation(
                                value = value,
                                styleForState = { state ->
                                    when (SupportPaintState.fromCode(state)) {
                                        SupportPaintState.ENFORCE -> PrepareOverlayStyle.SupportEnforce
                                        SupportPaintState.BLOCK -> PrepareOverlayStyle.SupportBlock
                                        null -> null
                                    }
                                },
                                previewTriangles = volume.model.previewTriangles,
                                previewTriangleIndex = previewTriangleIndex,
                                trianglesByStyle = trianglesByStyle,
                                splitTrianglesByStyle = splitTrianglesByStyle,
                                remainingSplitTriangles = remainingSplitTriangles,
                            ).also { remainingSplitTriangles -= it }
                        }
                    }
                    when (volume.seamPaint.facets[sourceFacetIndex]) {
                        SeamPaintState.ENFORCE -> trianglesByStyle.add(
                            PrepareOverlayStyle.SeamEnforce,
                            previewTriangleIndex,
                        )
                        SeamPaintState.BLOCK -> trianglesByStyle.add(
                            PrepareOverlayStyle.SeamBlock,
                            previewTriangleIndex,
                        )
                        null -> Unit
                    }
                    if (sourceFacetIndex !in volume.seamPaint.facets) {
                        volume.orcaFacetAnnotations.seam.triangles[sourceFacetIndex]?.let { value ->
                            addExactAnnotation(
                                value = value,
                                styleForState = { state ->
                                    when (SeamPaintState.fromCode(state)) {
                                        SeamPaintState.ENFORCE -> PrepareOverlayStyle.SeamEnforce
                                        SeamPaintState.BLOCK -> PrepareOverlayStyle.SeamBlock
                                        null -> null
                                    }
                                },
                                previewTriangles = volume.model.previewTriangles,
                                previewTriangleIndex = previewTriangleIndex,
                                trianglesByStyle = trianglesByStyle,
                                splitTrianglesByStyle = splitTrianglesByStyle,
                                remainingSplitTriangles = remainingSplitTriangles,
                            ).also { remainingSplitTriangles -= it }
                        }
                    }
                }
                trianglesByStyle.forEach { (style, triangleIndices) ->
                    overlays += PrepareModelOverlayData(
                        meshIndex = meshIndex,
                        fillIndices = triangleIndices.toFillIndices(),
                        lineIndices = triangleIndices.toLineIndices(),
                        fillColor = style.fillColor,
                        lineColor = style.lineColor,
                    )
                }
                splitTrianglesByStyle.forEach { (style, vertices) ->
                    val packed = vertices.toFloatArray()
                    overlays += PrepareModelOverlayData(
                        meshIndex = meshIndex,
                        fillIndices = IntArray(0),
                        lineIndices = packed.toSplitLineIndices(),
                        fillColor = style.fillColor,
                        lineColor = style.lineColor,
                        customVertices = packed,
                    )
                }
                meshIndex += 1
            }
        }
        return overlays
    }

    private fun addExactAnnotation(
        value: String,
        styleForState: (Int) -> PrepareOverlayStyle?,
        previewTriangles: FloatArray,
        previewTriangleIndex: Int,
        trianglesByStyle: MutableMap<PrepareOverlayStyle, MutableList<Int>>,
        splitTrianglesByStyle: MutableMap<PrepareOverlayStyle, MutableList<Float>>,
        remainingSplitTriangles: Int,
    ): Int {
        val rootState = OrcaFacetPreviewTessellator.rootLeafState(value)
        if (rootState != null) {
            styleForState(rootState)?.let { style ->
                trianglesByStyle.add(style, previewTriangleIndex)
            }
            return 0
        }
        if (remainingSplitTriangles <= 0) return 0
        val leaves = OrcaFacetPreviewTessellator.tessellate(
            value = value,
            sourceVertices = previewTriangles,
            sourceOffset = previewTriangleIndex * 9,
            maximumTriangles = remainingSplitTriangles,
        )
        leaves.forEach { leaf ->
            val style = styleForState(leaf.state) ?: return@forEach
            val output = splitTrianglesByStyle.getOrPut(style, ::ArrayList)
            leaf.vertices.forEach(output::add)
        }
        return leaves.size
    }

    private fun MutableMap<PrepareOverlayStyle, MutableList<Int>>.add(
        style: PrepareOverlayStyle,
        triangleIndex: Int,
    ) {
        getOrPut(style, ::ArrayList) += triangleIndex
    }

    private fun List<Int>.toFillIndices(): IntArray {
        val result = IntArray(size * 3)
        forEachIndexed { outputTriangle, triangleIndex ->
            val vertex = triangleIndex * 3
            val output = outputTriangle * 3
            result[output] = vertex
            result[output + 1] = vertex + 1
            result[output + 2] = vertex + 2
        }
        return result
    }

    private fun List<Int>.toLineIndices(): IntArray {
        val result = IntArray(size * 6)
        forEachIndexed { outputTriangle, triangleIndex ->
            val vertex = triangleIndex * 3
            val output = outputTriangle * 6
            result[output] = vertex
            result[output + 1] = vertex + 1
            result[output + 2] = vertex + 1
            result[output + 3] = vertex + 2
            result[output + 4] = vertex + 2
            result[output + 5] = vertex
        }
        return result
    }

    private fun FloatArray.toSplitLineIndices(): IntArray {
        require(size % 9 == 0)
        val result = IntArray(size / 9 * 6)
        repeat(size / 9) { triangleIndex ->
            val vertex = triangleIndex * 3
            val output = triangleIndex * 6
            result[output] = vertex
            result[output + 1] = vertex + 1
            result[output + 2] = vertex + 1
            result[output + 3] = vertex + 2
            result[output + 4] = vertex + 2
            result[output + 5] = vertex
        }
        return result
    }
}

private sealed class PrepareOverlayStyle(
    val fillColor: PrepareOverlayColor,
    val lineColor: PrepareOverlayColor,
) {
    data object LayOnFace : PrepareOverlayStyle(
        fillColor = color(0xF6, 0xC9, 0x45, 0.16f),
        lineColor = color(0xF6, 0xC9, 0x45, 0.86f),
    )

    data class MultiColor(val slot: Int, val rgb: Int) : PrepareOverlayStyle(
        fillColor = filamentColor(rgb, 0.94f),
        lineColor = color(0x00, 0x00, 0x00, 0.62f),
    )

    data object SupportEnforce : PrepareOverlayStyle(
        fillColor = color(0x5E, 0xE6, 0xA8, 0.90f),
        lineColor = color(0x16, 0x3C, 0x2E, 1f),
    )

    data object SupportBlock : PrepareOverlayStyle(
        fillColor = color(0xFF, 0x6B, 0x6B, 0.90f),
        lineColor = color(0x54, 0x1F, 0x1F, 1f),
    )

    data object SeamEnforce : PrepareOverlayStyle(
        fillColor = color(0x4C, 0xC9, 0xF0, 0.90f),
        lineColor = color(0x15, 0x3B, 0x4A, 1f),
    )

    data object SeamBlock : PrepareOverlayStyle(
        fillColor = color(0xFF, 0x9F, 0x43, 0.90f),
        lineColor = color(0x56, 0x32, 0x17, 1f),
    )

    companion object {
        private fun filamentColor(rgb: Int, alpha: Float): PrepareOverlayColor =
            PrepareOverlayColor(
                red = ((rgb shr 16) and 0xFF) / 255f,
                green = ((rgb shr 8) and 0xFF) / 255f,
                blue = (rgb and 0xFF) / 255f,
                alpha = alpha,
            )

        private fun color(
            red: Int,
            green: Int,
            blue: Int,
            alpha: Float,
        ): PrepareOverlayColor = PrepareOverlayColor(
            red = red / 255f,
            green = green / 255f,
            blue = blue / 255f,
            alpha = alpha,
        )
    }
}

internal const val MAX_EXACT_SPLIT_OVERLAY_TRIANGLES = 48_000

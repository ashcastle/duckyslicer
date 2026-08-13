package com.ashcastle.duckyslicer

import kotlin.math.abs
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
    @Volatile
    private var cachedContinuousPaths: List<SegmentPath>? = null
    @Volatile
    private var cachedPathsByRole: Array<List<SegmentPath>>? = null
    private val cachedRenderPlans = LinkedHashMap<RenderPlanKey, PreviewRenderPlan>(
        MAX_RENDER_PLAN_CACHE_ENTRIES,
        0.75f,
        true,
    )

    internal fun prepareRenderIndex(): GcodeLayerPreview {
        buildContinuousPaths()
        return this
    }

    internal fun buildRenderPlan(
        segmentBudget: Int,
        visibleRoles: Set<Int>? = null,
    ): PreviewRenderPlan {
        val totalSegments = segments.size / SEGMENT_STRIDE
        if (totalSegments == 0) return PreviewRenderPlan(IntArray(0), BooleanArray(0))

        val safeBudget = segmentBudget.coerceAtLeast(1)
        val cacheKey = RenderPlanKey(safeBudget, visibleRoleMask(visibleRoles))
        synchronized(cachedRenderPlans) {
            cachedRenderPlans[cacheKey]?.let { return it }
        }
        val allPaths = buildContinuousPaths()
        val pathsByRole = buildPathsByRole(allPaths)
        val presentRoles = ROLE_PRIORITY.filter { role ->
            (visibleRoles == null || role in visibleRoles) && pathsByRole[role].isNotEmpty()
        }
        val visibleSegmentCount = presentRoles.sumOf { role ->
            pathsByRole[role].sumOf(SegmentPath::size)
        }
        if (visibleSegmentCount == 0) return PreviewRenderPlan(IntArray(0), BooleanArray(0))
        if (visibleSegmentCount <= safeBudget) {
            val visiblePaths = if (visibleRoles == null) {
                allPaths
            } else {
                allPaths.filter { path -> path.role in visibleRoles }
            }
            return cacheRenderPlan(cacheKey, planForPaths(visiblePaths))
        }

        val selected = ArrayList<SegmentPath>()
        val selectedStarts = HashSet<Int>()
        val reservedPerRole = (safeBudget / (presentRoles.size * 4)).coerceAtLeast(1)
        presentRoles.forEach { role ->
            val chosen = chooseWholePaths(pathsByRole[role], reservedPerRole)
            selected += chosen
            chosen.forEach { selectedStarts += it.start }
        }

        var remaining = (safeBudget - selected.sumOf(SegmentPath::size)).coerceAtLeast(0)
        presentRoles.forEach { role ->
            if (remaining <= 0) return@forEach
            val rolePaths = pathsByRole[role].filter { it.start !in selectedStarts }
            if (rolePaths.isEmpty()) return@forEach
            val chosen = chooseWholePaths(rolePaths, remaining)
            selected += chosen
            chosen.forEach { selectedStarts += it.start }
            remaining = (remaining - chosen.sumOf(SegmentPath::size)).coerceAtLeast(0)
        }
        return cacheRenderPlan(cacheKey, planForPaths(selected.sortedBy(SegmentPath::start)))
    }

    private fun cacheRenderPlan(key: RenderPlanKey, plan: PreviewRenderPlan): PreviewRenderPlan =
        synchronized(cachedRenderPlans) {
            cachedRenderPlans[key]?.let { return@synchronized it }
            cachedRenderPlans[key] = plan
            while (cachedRenderPlans.size > MAX_RENDER_PLAN_CACHE_ENTRIES) {
                val eldest = cachedRenderPlans.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
            plan
        }

    private fun visibleRoleMask(visibleRoles: Set<Int>?): Int {
        if (visibleRoles == null) return ALL_ROLES_MASK
        return visibleRoles.fold(0) { mask, role ->
            if (role in 0 until ROLE_COUNT) mask or (1 shl role) else mask
        }
    }

    private fun buildContinuousPaths(): List<SegmentPath> {
        cachedContinuousPaths?.let { return it }
        val built = computeContinuousPaths()
        return synchronized(this) {
            cachedContinuousPaths ?: built.also { cachedContinuousPaths = it }
        }
    }

    private fun buildPathsByRole(paths: List<SegmentPath>): Array<List<SegmentPath>> {
        cachedPathsByRole?.let { return it }
        val mutable = Array(ROLE_COUNT) { ArrayList<SegmentPath>() }
        paths.forEach { path ->
            if (path.role in 0 until ROLE_COUNT) mutable[path.role] += path
        }
        val built = Array<List<SegmentPath>>(ROLE_COUNT) { role -> mutable[role] }
        return synchronized(this) {
            cachedPathsByRole ?: built.also { cachedPathsByRole = it }
        }
    }

    private fun computeContinuousPaths(): List<SegmentPath> {
        val totalSegments = segments.size / SEGMENT_STRIDE
        if (totalSegments == 0) return emptyList()
        val paths = ArrayList<SegmentPath>()
        var pathStart = 0
        var pathRole = segments[5].toInt()
        for (segmentIndex in 1 until totalSegments) {
            val previousOffset = (segmentIndex - 1) * SEGMENT_STRIDE
            val currentOffset = segmentIndex * SEGMENT_STRIDE
            if (!segmentsConnect(previousOffset, currentOffset)) {
                paths += SegmentPath(pathStart, segmentIndex, pathRole)
                pathStart = segmentIndex
                pathRole = segments[currentOffset + 5].toInt()
            }
        }
        paths += SegmentPath(pathStart, totalSegments, pathRole)
        return paths
    }

    private fun chooseWholePaths(paths: List<SegmentPath>, budget: Int): List<SegmentPath> {
        val totalSize = paths.sumOf(SegmentPath::size)
        if (totalSize <= budget) return paths
        val averageSize = totalSize.toFloat() / paths.size
        val targetCount = (budget / averageSize).toInt().coerceIn(1, paths.size)
        val indices = if (targetCount == 1) {
            intArrayOf(paths.lastIndex)
        } else {
            IntArray(targetCount) { index ->
                (index * paths.lastIndex.toFloat() / (targetCount - 1)).roundToInt()
            }.distinct().toIntArray()
        }
        val selected = ArrayList<SegmentPath>(indices.size)
        val selectedIndices = indices.toHashSet()
        var used = 0
        indices.forEach { index ->
            val path = paths[index]
            if (selected.isEmpty() || used + path.size <= budget) {
                selected += path
                used += path.size
            }
        }
        paths.forEachIndexed { index, path ->
            if (index !in selectedIndices && used + path.size <= budget) {
                selected += path
                used += path.size
            }
        }
        return selected
    }

    private fun planForPaths(paths: List<SegmentPath>): PreviewRenderPlan {
        val offsets = IntArray(paths.sumOf(SegmentPath::size))
        var writeIndex = 0
        paths.forEach { path ->
            for (segmentIndex in path.start until path.endExclusive) {
                offsets[writeIndex++] = segmentIndex * SEGMENT_STRIDE
            }
        }
        return planForOffsets(offsets)
    }

    private fun planForOffsets(offsets: IntArray): PreviewRenderPlan {
        val connections = BooleanArray(offsets.size)
        for (index in 1 until offsets.size) {
            val previous = offsets[index - 1]
            val current = offsets[index]
            if (current != previous + SEGMENT_STRIDE) continue
            connections[index] = segmentsConnect(previous, current)
        }
        return PreviewRenderPlan(
            segmentOffsets = offsets,
            connectsToPrevious = connections,
        )
    }

    private fun segmentsConnect(previous: Int, current: Int): Boolean =
        segments[previous + 5].toInt() == segments[current + 5].toInt() &&
            abs(segments[previous + 2] - segments[current]) < Z_EPSILON &&
            abs(segments[previous + 3] - segments[current + 1]) < Z_EPSILON &&
            abs(segments[previous + 4] - segments[current + 4]) < Z_EPSILON

    private data class SegmentPath(
        val start: Int,
        val endExclusive: Int,
        val role: Int,
    ) {
        val size: Int get() = endExclusive - start
    }

    private data class RenderPlanKey(val segmentBudget: Int, val visibleRoleMask: Int)

    companion object {
        const val SEGMENT_STRIDE = 6
        internal const val ROLE_COUNT = 10
        private const val Z_EPSILON = 0.001f
        private const val MAX_RENDER_PLAN_CACHE_ENTRIES = 6
        private const val ALL_ROLES_MASK = (1 shl ROLE_COUNT) - 1
        private val ROLE_PRIORITY = intArrayOf(0, 3, 9, 1, 4, 6, 7, 5, 2, 8)

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
            val continuousPaths = ArrayList<SegmentPath>()
            var pathStart = 0
            var pathRole = 0
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
                val segmentIndex = offset / SEGMENT_STRIDE
                if (segmentIndex == 0) {
                    pathRole = role
                } else {
                    val previous = offset - SEGMENT_STRIDE
                    val connects = segments[previous + 5].toInt() == role &&
                        abs(segments[previous + 2] - segments[offset]) < Z_EPSILON &&
                        abs(segments[previous + 3] - segments[offset + 1]) < Z_EPSILON &&
                        abs(segments[previous + 4] - segments[offset + 4]) < Z_EPSILON
                    if (!connects) {
                        continuousPaths += SegmentPath(pathStart, segmentIndex, pathRole)
                        pathStart = segmentIndex
                        pathRole = role
                    }
                }
            }
            val totalSegments = segments.size / SEGMENT_STRIDE
            if (totalSegments > 0) {
                continuousPaths += SegmentPath(pathStart, totalSegments, pathRole)
            }
            return GcodeLayerPreview(
                startLayer = startLayer,
                endLayer = endLayer,
                layerCount = layerCount,
                minZMm = minZMm,
                maxZMm = maxZMm,
                segments = segments,
                roleSegmentCounts = roleSegmentCounts,
            ).also { preview -> preview.cachedContinuousPaths = continuousPaths }
        }

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

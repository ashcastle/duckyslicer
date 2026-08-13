package com.ashcastle.duckyslicer

import kotlin.math.abs
import kotlin.math.roundToInt

internal class PreviewRenderPlan(
    internal val pathStarts: IntArray,
    internal val pathEndsExclusive: IntArray,
    internal val segmentCount: Int,
) {
    init {
        require(pathStarts.size == pathEndsExclusive.size)
    }

    // GLES consumes the compact path ranges directly. The Canvas fallback and diagnostics
    // retain the former segment view, but only pay to materialize it when they actually use it.
    val segmentOffsets: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IntArray(segmentCount).also { offsets ->
            var writeIndex = 0
            pathStarts.indices.forEach { pathIndex ->
                for (segmentIndex in pathStarts[pathIndex] until pathEndsExclusive[pathIndex]) {
                    offsets[writeIndex++] = segmentIndex * GcodeLayerPreview.SEGMENT_STRIDE
                }
            }
            check(writeIndex == segmentCount) { "Preview path range size changed" }
        }
    }

    val connectsToPrevious: BooleanArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        BooleanArray(segmentCount).also { connections ->
            var writeIndex = 0
            pathStarts.indices.forEach { pathIndex ->
                val pathSize = pathEndsExclusive[pathIndex] - pathStarts[pathIndex]
                repeat(pathSize) { offsetInPath ->
                    connections[writeIndex++] = offsetInPath > 0
                }
            }
            check(writeIndex == segmentCount) { "Preview connection range size changed" }
        }
    }

    companion object {
        val NONE = PreviewRenderPlan(IntArray(0), IntArray(0), 0)
    }
}

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
    private var cachedPathsByRole: Array<RolePathIndex>? = null
    private val cachedRenderPlans = LinkedHashMap<RenderPlanKey, PreviewRenderPlan>(
        MAX_RENDER_PLAN_CACHE_ENTRIES,
        0.75f,
        true,
    )

    internal fun prepareRenderIndex(): GcodeLayerPreview {
        val paths = buildContinuousPaths()
        buildPathsByRole(paths)
        return this
    }

    internal fun buildRenderPlan(
        segmentBudget: Int,
        visibleRoles: Set<Int>? = null,
    ): PreviewRenderPlan {
        val totalSegments = segments.size / SEGMENT_STRIDE
        if (totalSegments == 0) return PreviewRenderPlan.NONE

        val safeBudget = segmentBudget.coerceAtLeast(1)
        val cacheKey = RenderPlanKey(safeBudget, visibleRoleMask(visibleRoles))
        synchronized(cachedRenderPlans) {
            cachedRenderPlans[cacheKey]?.let { return it }
        }
        val allPaths = buildContinuousPaths()
        val pathsByRole = buildPathsByRole(allPaths)
        val presentRoles = ROLE_PRIORITY.filter { role ->
            (visibleRoles == null || role in visibleRoles) && pathsByRole[role].paths.isNotEmpty()
        }
        val visibleSegmentCount = presentRoles.sumOf { role -> pathsByRole[role].segmentCount }
        if (visibleSegmentCount == 0) return PreviewRenderPlan.NONE
        if (visibleSegmentCount <= safeBudget) {
            val visiblePaths = if (visibleRoles == null) {
                allPaths
            } else {
                allPaths.filter { path -> path.role in visibleRoles }
            }
            return cacheRenderPlan(cacheKey, planForPaths(visiblePaths))
        }

        // Path starts are segment indices in a bounded 120k-segment payload. A primitive
        // bitmap avoids boxing and sorting tens of thousands of paths while the first Preview
        // frame reserves and fills each role's coherent paths.
        val selectedStarts = BooleanArray(totalSegments)
        val selectedPathCounts = IntArray(ROLE_COUNT)
        val selectedSegmentCounts = IntArray(ROLE_COUNT)
        val reservedPerRole = (safeBudget / (presentRoles.size * 4)).coerceAtLeast(1)
        var used = 0
        presentRoles.forEach { role ->
            val selection = chooseWholePaths(
                index = pathsByRole[role],
                budget = reservedPerRole,
                selectedStarts = selectedStarts,
                selectedPathCount = selectedPathCounts[role],
                selectedSegmentCount = selectedSegmentCounts[role],
            )
            selectedPathCounts[role] += selection.pathCount
            selectedSegmentCounts[role] += selection.segmentCount
            used += selection.segmentCount
        }

        var remaining = (safeBudget - used).coerceAtLeast(0)
        presentRoles.forEach { role ->
            if (remaining <= 0) return@forEach
            val selection = chooseWholePaths(
                index = pathsByRole[role],
                budget = remaining,
                selectedStarts = selectedStarts,
                selectedPathCount = selectedPathCounts[role],
                selectedSegmentCount = selectedSegmentCounts[role],
            )
            selectedPathCounts[role] += selection.pathCount
            selectedSegmentCounts[role] += selection.segmentCount
            used += selection.segmentCount
            remaining = (remaining - selection.segmentCount).coerceAtLeast(0)
        }
        return cacheRenderPlan(
            cacheKey,
            planForSelectedPaths(
                paths = allPaths,
                selectedStarts = selectedStarts,
                selectedSegmentCount = used,
                selectedPathCount = selectedPathCounts.sum(),
            ),
        )
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

    private fun buildPathsByRole(paths: List<SegmentPath>): Array<RolePathIndex> {
        cachedPathsByRole?.let { return it }
        val mutable = Array(ROLE_COUNT) { ArrayList<SegmentPath>() }
        val segmentCounts = IntArray(ROLE_COUNT)
        paths.forEach { path ->
            if (path.role in 0 until ROLE_COUNT) {
                mutable[path.role] += path
                segmentCounts[path.role] += path.size
            }
        }
        val built = Array(ROLE_COUNT) { role ->
            RolePathIndex(paths = mutable[role], segmentCount = segmentCounts[role])
        }
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

    private fun chooseWholePaths(
        index: RolePathIndex,
        budget: Int,
        selectedStarts: BooleanArray,
        selectedPathCount: Int,
        selectedSegmentCount: Int,
    ): PathSelection {
        val eligibleCount = index.paths.size - selectedPathCount
        val totalSize = index.segmentCount - selectedSegmentCount
        if (eligibleCount == 0) return PathSelection.NONE
        if (totalSize <= budget) {
            var chosen = 0
            index.paths.forEach { path ->
                if (!selectedStarts[path.start]) {
                    selectedStarts[path.start] = true
                    chosen += 1
                }
            }
            return PathSelection(totalSize, chosen)
        }

        val averageSize = totalSize.toFloat() / eligibleCount
        val targetCount = (budget / averageSize).toInt().coerceIn(1, eligibleCount)
        val targetOrdinals = IntArray(targetCount)
        var uniqueTargets = 0
        repeat(targetCount) { index ->
            val ordinal = if (targetCount == 1) {
                eligibleCount - 1
            } else {
                (index * (eligibleCount - 1).toFloat() / (targetCount - 1)).roundToInt()
            }
            if (uniqueTargets == 0 || targetOrdinals[uniqueTargets - 1] != ordinal) {
                targetOrdinals[uniqueTargets++] = ordinal
            }
        }
        var used = 0
        var chosen = 0
        var eligibleOrdinal = 0
        var targetIndex = 0
        index.paths.forEach { path ->
            if (selectedStarts[path.start]) return@forEach
            if (targetIndex < uniqueTargets && eligibleOrdinal == targetOrdinals[targetIndex]) {
                if (chosen == 0 || used + path.size <= budget) {
                    selectedStarts[path.start] = true
                    used += path.size
                    chosen += 1
                }
                targetIndex += 1
            }
            eligibleOrdinal += 1
        }
        index.paths.forEach { path ->
            if (!selectedStarts[path.start] && used + path.size <= budget) {
                selectedStarts[path.start] = true
                used += path.size
                chosen += 1
            }
        }
        return PathSelection(used, chosen)
    }

    private fun planForPaths(paths: List<SegmentPath>): PreviewRenderPlan {
        val starts = IntArray(paths.size)
        val ends = IntArray(paths.size)
        var segmentCount = 0
        paths.forEachIndexed { index, path ->
            starts[index] = path.start
            ends[index] = path.endExclusive
            segmentCount += path.size
        }
        return PreviewRenderPlan(starts, ends, segmentCount)
    }

    private fun planForSelectedPaths(
        paths: List<SegmentPath>,
        selectedStarts: BooleanArray,
        selectedSegmentCount: Int,
        selectedPathCount: Int,
    ): PreviewRenderPlan {
        val starts = IntArray(selectedPathCount)
        val ends = IntArray(selectedPathCount)
        var writeIndex = 0
        paths.forEach { path ->
            if (!selectedStarts[path.start]) return@forEach
            starts[writeIndex] = path.start
            ends[writeIndex] = path.endExclusive
            writeIndex += 1
        }
        check(writeIndex == selectedPathCount) { "Preview path selection count changed" }
        check(
            starts.indices.sumOf { index -> ends[index] - starts[index] } == selectedSegmentCount,
        ) { "Preview path selection size changed" }
        return PreviewRenderPlan(starts, ends, selectedSegmentCount)
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

    private data class RolePathIndex(
        val paths: List<SegmentPath>,
        val segmentCount: Int,
    )

    private data class PathSelection(val segmentCount: Int, val pathCount: Int) {
        companion object {
            val NONE = PathSelection(0, 0)
        }
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
            val pathsByRole = Array(ROLE_COUNT) { ArrayList<SegmentPath>() }
            var pathStart = 0
            var pathRole = 0
            fun completePath(endExclusive: Int) {
                continuousPaths += SegmentPath(pathStart, endExclusive, pathRole)
                pathsByRole[pathRole] += continuousPaths.last()
            }
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
                        completePath(segmentIndex)
                        pathStart = segmentIndex
                        pathRole = role
                    }
                }
            }
            val totalSegments = segments.size / SEGMENT_STRIDE
            if (totalSegments > 0) {
                completePath(totalSegments)
            }
            return GcodeLayerPreview(
                startLayer = startLayer,
                endLayer = endLayer,
                layerCount = layerCount,
                minZMm = minZMm,
                maxZMm = maxZMm,
                segments = segments,
                roleSegmentCounts = roleSegmentCounts,
            ).also { preview ->
                preview.cachedContinuousPaths = continuousPaths
                preview.cachedPathsByRole = Array(ROLE_COUNT) { role ->
                    RolePathIndex(
                        paths = pathsByRole[role],
                        segmentCount = roleSegmentCounts[role],
                    )
                }
            }
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

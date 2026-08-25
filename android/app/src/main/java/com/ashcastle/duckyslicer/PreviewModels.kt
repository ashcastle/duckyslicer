package com.ashcastle.duckyslicer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
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
    val toolSegmentCounts: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        IntArray(MAX_TOOL_COUNT).also { counts ->
            var offset = 0
            while (offset < segments.size) {
                counts[segments[offset + TOOL_OFFSET].toInt()] += 1
                offset += SEGMENT_STRIDE
            }
        }
    }

    @Volatile
    private var cachedPathIndex: ContinuousPathIndex? = null
    @Volatile
    private var derivedCacheGeneration = 0L
    private val cachedRenderPlans = LinkedHashMap<RenderPlanKey, PreviewRenderPlan>(
        MAX_RENDER_PLAN_CACHE_ENTRIES,
        0.75f,
        true,
    )

    internal fun prepareRenderIndex(): GcodeLayerPreview {
        buildPathIndex(derivedCacheGeneration)
        return this
    }

    /** Drops only data that can be rebuilt from [segments] after Android memory pressure. */
    internal fun releaseDerivedMemoryForMemoryPressure() {
        synchronized(this) {
            derivedCacheGeneration += 1L
            cachedPathIndex = null
        }
        synchronized(cachedRenderPlans) {
            cachedRenderPlans.clear()
        }
    }

    internal fun derivedCacheStateForTest(): PreviewDerivedCacheState {
        val indexedPathCount = synchronized(this) { cachedPathIndex?.pathCount ?: 0 }
        val renderPlanCount = synchronized(cachedRenderPlans) { cachedRenderPlans.size }
        return PreviewDerivedCacheState(indexedPathCount, renderPlanCount)
    }

    internal fun buildRenderPlan(
        segmentBudget: Int,
        visibleRoles: Set<Int>? = null,
    ): PreviewRenderPlan {
        val totalSegments = segments.size / SEGMENT_STRIDE
        if (totalSegments == 0) return PreviewRenderPlan.NONE

        val expectedCacheGeneration = derivedCacheGeneration
        val safeBudget = segmentBudget.coerceAtLeast(1)
        val cacheKey = RenderPlanKey(safeBudget, visibleRoleMask(visibleRoles))
        synchronized(cachedRenderPlans) {
            cachedRenderPlans[cacheKey]?.let { return it }
        }
        val allPaths = buildPathIndex(expectedCacheGeneration)
        val pathsByRole = allPaths.pathsByRole
        val presentRoles = ROLE_PRIORITY.filter { role ->
            (visibleRoles == null || role in visibleRoles) &&
                pathsByRole[role].pathOrdinals.isNotEmpty()
        }
        val visibleSegmentCount = presentRoles.sumOf { role -> pathsByRole[role].segmentCount }
        if (visibleSegmentCount == 0) return PreviewRenderPlan.NONE
        if (visibleSegmentCount <= safeBudget) {
            return cacheRenderPlan(
                cacheKey,
                planForPaths(allPaths, visibleRoles),
                expectedCacheGeneration,
            )
        }

        // Path identity is a bounded primitive ordinal. This avoids per-path objects, boxed
        // role lists, and a segment-sized bitmap while keeping source-order path ranges exact.
        val selectedPaths = BooleanArray(allPaths.pathCount)
        val selectedPathCounts = IntArray(ROLE_COUNT)
        val selectedSegmentCounts = IntArray(ROLE_COUNT)
        val reservedPerRole = (safeBudget / (presentRoles.size * 4)).coerceAtLeast(1)
        var used = 0
        presentRoles.forEach { role ->
            val selection = chooseWholePaths(
                index = pathsByRole[role],
                paths = allPaths,
                budget = reservedPerRole,
                selectedPaths = selectedPaths,
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
                paths = allPaths,
                budget = remaining,
                selectedPaths = selectedPaths,
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
                selectedPaths = selectedPaths,
                selectedSegmentCount = used,
                selectedPathCount = selectedPathCounts.sum(),
            ),
            expectedCacheGeneration,
        )
    }

    private fun cacheRenderPlan(
        key: RenderPlanKey,
        plan: PreviewRenderPlan,
        expectedCacheGeneration: Long,
    ): PreviewRenderPlan =
        synchronized(cachedRenderPlans) {
            if (derivedCacheGeneration != expectedCacheGeneration) return@synchronized plan
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

    private fun buildPathIndex(expectedCacheGeneration: Long): ContinuousPathIndex {
        cachedPathIndex?.let { return it }
        val built = computePathIndex()
        return synchronized(this) {
            if (derivedCacheGeneration != expectedCacheGeneration) {
                built
            } else {
                cachedPathIndex ?: built.also { cachedPathIndex = it }
            }
        }
    }

    private fun computePathIndex(): ContinuousPathIndex {
        val totalSegments = segments.size / SEGMENT_STRIDE
        if (totalSegments == 0) return ContinuousPathIndex.EMPTY
        val paths = PrimitivePathBuilder(totalSegments)
        var pathStart = 0
        var pathRole = segments[5].toInt()
        var segmentIndex = 1
        while (segmentIndex < totalSegments) {
            val previousOffset = (segmentIndex - 1) * SEGMENT_STRIDE
            val currentOffset = segmentIndex * SEGMENT_STRIDE
            if (!segmentsConnect(previousOffset, currentOffset)) {
                paths.add(pathStart, segmentIndex, pathRole)
                pathStart = segmentIndex
                pathRole = segments[currentOffset + 5].toInt()
            }
            segmentIndex += 1
        }
        paths.add(pathStart, totalSegments, pathRole)
        return paths.finish()
    }

    private fun chooseWholePaths(
        index: RolePathIndex,
        paths: ContinuousPathIndex,
        budget: Int,
        selectedPaths: BooleanArray,
        selectedPathCount: Int,
        selectedSegmentCount: Int,
    ): PathSelection {
        val eligibleCount = index.pathOrdinals.size - selectedPathCount
        val totalSize = index.segmentCount - selectedSegmentCount
        if (eligibleCount == 0) return PathSelection.NONE
        if (totalSize <= budget) {
            var chosen = 0
            index.pathOrdinals.forEach { pathOrdinal ->
                if (!selectedPaths[pathOrdinal]) {
                    selectedPaths[pathOrdinal] = true
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
        index.pathOrdinals.forEach { pathOrdinal ->
            if (selectedPaths[pathOrdinal]) return@forEach
            val pathSize = paths.size(pathOrdinal)
            if (targetIndex < uniqueTargets && eligibleOrdinal == targetOrdinals[targetIndex]) {
                if (chosen == 0 || used + pathSize <= budget) {
                    selectedPaths[pathOrdinal] = true
                    used += pathSize
                    chosen += 1
                }
                targetIndex += 1
            }
            eligibleOrdinal += 1
        }
        index.pathOrdinals.forEach { pathOrdinal ->
            val pathSize = paths.size(pathOrdinal)
            if (!selectedPaths[pathOrdinal] && used + pathSize <= budget) {
                selectedPaths[pathOrdinal] = true
                used += pathSize
                chosen += 1
            }
        }
        return PathSelection(used, chosen)
    }

    private fun planForPaths(
        paths: ContinuousPathIndex,
        visibleRoles: Set<Int>?,
    ): PreviewRenderPlan {
        val selectedPathCount = if (visibleRoles == null) {
            paths.pathCount
        } else {
            paths.roles.count { role -> role in visibleRoles }
        }
        val starts = IntArray(selectedPathCount)
        val ends = IntArray(selectedPathCount)
        var segmentCount = 0
        var writeIndex = 0
        var pathOrdinal = 0
        while (pathOrdinal < paths.pathCount) {
            if (visibleRoles == null || paths.roles[pathOrdinal] in visibleRoles) {
                starts[writeIndex] = paths.starts[pathOrdinal]
                ends[writeIndex] = paths.endsExclusive[pathOrdinal]
                segmentCount += paths.size(pathOrdinal)
                writeIndex += 1
            }
            pathOrdinal += 1
        }
        check(writeIndex == selectedPathCount) { "Preview visible path count changed" }
        return PreviewRenderPlan(starts, ends, segmentCount)
    }

    private fun planForSelectedPaths(
        paths: ContinuousPathIndex,
        selectedPaths: BooleanArray,
        selectedSegmentCount: Int,
        selectedPathCount: Int,
    ): PreviewRenderPlan {
        val starts = IntArray(selectedPathCount)
        val ends = IntArray(selectedPathCount)
        var writeIndex = 0
        var verifiedSegmentCount = 0
        var pathOrdinal = 0
        while (pathOrdinal < paths.pathCount) {
            if (selectedPaths[pathOrdinal]) {
                starts[writeIndex] = paths.starts[pathOrdinal]
                ends[writeIndex] = paths.endsExclusive[pathOrdinal]
                verifiedSegmentCount += paths.size(pathOrdinal)
                writeIndex += 1
            }
            pathOrdinal += 1
        }
        check(writeIndex == selectedPathCount) { "Preview path selection count changed" }
        check(verifiedSegmentCount == selectedSegmentCount) { "Preview path selection size changed" }
        return PreviewRenderPlan(starts, ends, selectedSegmentCount)
    }

    private fun segmentsConnect(previous: Int, current: Int): Boolean =
        segments[previous + 5].toInt() == segments[current + 5].toInt() &&
            segments[previous + TOOL_OFFSET].toInt() == segments[current + TOOL_OFFSET].toInt() &&
            abs(segments[previous + 2] - segments[current]) < Z_EPSILON &&
            abs(segments[previous + 3] - segments[current + 1]) < Z_EPSILON &&
            abs(segments[previous + 4] - segments[current + 4]) < Z_EPSILON

    private data class RolePathIndex(
        val pathOrdinals: IntArray,
        val segmentCount: Int,
    )

    private class ContinuousPathIndex(
        val starts: IntArray,
        val endsExclusive: IntArray,
        val roles: IntArray,
        val pathsByRole: Array<RolePathIndex>,
    ) {
        val pathCount: Int get() = starts.size

        fun size(pathOrdinal: Int): Int = endsExclusive[pathOrdinal] - starts[pathOrdinal]

        companion object {
            val EMPTY = ContinuousPathIndex(
                IntArray(0),
                IntArray(0),
                IntArray(0),
                Array(ROLE_COUNT) { RolePathIndex(IntArray(0), 0) },
            )
        }
    }

    private class PrimitivePathBuilder(private val maximumPathCount: Int) {
        private var starts = IntArray(minOf(maximumPathCount, INITIAL_PATH_CAPACITY).coerceAtLeast(1))
        private var endsExclusive = IntArray(starts.size)
        private var roles = IntArray(starts.size)
        private val rolePathCounts = IntArray(ROLE_COUNT)
        private val roleSegmentCounts = IntArray(ROLE_COUNT)
        private var pathCount = 0

        fun add(start: Int, endExclusive: Int, role: Int) {
            require(start in 0 until endExclusive && endExclusive <= maximumPathCount)
            ensureCapacity()
            starts[pathCount] = start
            endsExclusive[pathCount] = endExclusive
            roles[pathCount] = role
            if (role in 0 until ROLE_COUNT) {
                rolePathCounts[role] += 1
                roleSegmentCounts[role] += endExclusive - start
            }
            pathCount += 1
        }

        fun finish(): ContinuousPathIndex {
            val finalStarts = starts.copyOf(pathCount)
            val finalEnds = endsExclusive.copyOf(pathCount)
            val finalRoles = roles.copyOf(pathCount)
            val roleOrdinals = Array(ROLE_COUNT) { role -> IntArray(rolePathCounts[role]) }
            val writeIndices = IntArray(ROLE_COUNT)
            var pathOrdinal = 0
            while (pathOrdinal < pathCount) {
                val role = finalRoles[pathOrdinal]
                if (role in 0 until ROLE_COUNT) {
                    roleOrdinals[role][writeIndices[role]++] = pathOrdinal
                }
                pathOrdinal += 1
            }
            return ContinuousPathIndex(
                finalStarts,
                finalEnds,
                finalRoles,
                Array(ROLE_COUNT) { role ->
                    RolePathIndex(roleOrdinals[role], roleSegmentCounts[role])
                },
            )
        }

        private fun ensureCapacity() {
            if (pathCount < starts.size) return
            val nextCapacity = minOf(maximumPathCount, maxOf(pathCount + 1, starts.size * 2))
            check(nextCapacity > starts.size) { "Preview path index exceeded its segment bound" }
            starts = starts.copyOf(nextCapacity)
            endsExclusive = endsExclusive.copyOf(nextCapacity)
            roles = roles.copyOf(nextCapacity)
        }
    }

    private data class PathSelection(val segmentCount: Int, val pathCount: Int) {
        companion object {
            val NONE = PathSelection(0, 0)
        }
    }

    private data class RenderPlanKey(val segmentBudget: Int, val visibleRoleMask: Int)

    companion object {
        const val SEGMENT_STRIDE = 7
        internal const val ROLE_COUNT = 10
        internal const val MAX_TOOL_COUNT = 16
        internal const val TOOL_OFFSET = 6
        private const val Z_EPSILON = 0.001f
        private const val MAX_RENDER_PLAN_CACHE_ENTRIES = 6
        private const val ALL_ROLES_MASK = (1 shl ROLE_COUNT) - 1
        private const val INITIAL_PATH_CAPACITY = 1_024
        private val ROLE_PRIORITY = intArrayOf(0, 3, 9, 1, 4, 6, 7, 5, 2, 8)

        fun fromNative(raw: FloatArray?): GcodeLayerPreview = decodeNativePayload(
            raw = raw,
            validateCoordinates = true,
        )

        internal fun fromTrustedNative(raw: FloatArray?): GcodeLayerPreview = decodeNativePayload(
            raw = raw,
            validateCoordinates = false,
        )

        internal fun fromTrustedNative(
            raw: ByteBuffer?,
            usedFloats: Int,
        ): GcodeLayerPreview = decodeTrustedNativeBuffer(raw, usedFloats)

        private fun decodeTrustedNativeBuffer(
            raw: ByteBuffer?,
            usedFloats: Int,
        ): GcodeLayerPreview {
            checkNotNull(raw) { "preview_invalid" }
            check(raw.isDirect && raw.order() == ByteOrder.nativeOrder()) {
                "preview_buffer_invalid"
            }
            check(usedFloats in HEADER_FLOATS..MAX_PAYLOAD_FLOATS) {
                "preview_size_invalid"
            }
            val usedBytes = usedFloats.toLong() * Float.SIZE_BYTES
            check(usedBytes <= raw.capacity().toLong()) { "preview_size_invalid" }
            val floats = raw.duplicate()
                .order(ByteOrder.nativeOrder())
                .apply {
                    clear()
                    limit(usedBytes.toInt())
                }
                .asFloatBuffer()
            check(floats[0] == PAYLOAD_MAGIC && floats[1] == PAYLOAD_VERSION) {
                "preview_format_invalid"
            }
            val startLayer = floats.exactInt(2, MAX_LAYER_COUNT)
            val endLayer = floats.exactInt(3, MAX_LAYER_COUNT)
            val layerCount = floats.exactInt(4, MAX_LAYER_COUNT)
            check(startLayer <= endLayer) { "preview_range_invalid" }
            check(
                (layerCount == 0 && startLayer == 0 && endLayer == 0) ||
                    (layerCount > 0 && endLayer < layerCount),
            ) { "preview_layer_count_invalid" }
            val minZMm = floats[5]
            val maxZMm = floats[6]
            check(minZMm.isFinite() && maxZMm.isFinite() && minZMm <= maxZMm) {
                "preview_height_invalid"
            }
            val totalSegments = floats.exactInt(7, MAX_SEGMENTS)
            val pathCount = floats.exactInt(8, MAX_SEGMENTS)
            check(pathCount <= totalSegments) { "preview_path_count_invalid" }
            val roleSegmentCounts = IntArray(ROLE_COUNT) { role ->
                floats.exactInt(ROLE_COUNTS_OFFSET + role, MAX_SEGMENTS)
            }
            check(roleSegmentCounts.sum() == totalSegments) { "preview_role_count_invalid" }
            val segmentEnd = HEADER_FLOATS + totalSegments * SEGMENT_STRIDE
            val expectedPayloadFloats = segmentEnd + pathCount * PATH_STRIDE
            check(usedFloats == expectedPayloadFloats) { "preview_size_invalid" }
            val segments = FloatArray(totalSegments * SEGMENT_STRIDE)
            floats.position(HEADER_FLOATS)
            floats.get(segments)

            val pathBuilder = if (pathCount == 0) null else PrimitivePathBuilder(totalSegments)
            var previousEnd = 0
            var pathOffset = segmentEnd
            repeat(pathCount) {
                val pathStart = previousEnd
                val pathEnd = floats.exactInt(pathOffset, totalSegments)
                check(pathStart < pathEnd) { "preview_path_range_invalid" }
                val roleValue = segments[pathStart * SEGMENT_STRIDE + 5]
                check(roleValue.isFinite() && roleValue % 1f == 0f) {
                    "preview_path_role_invalid"
                }
                val pathRole = roleValue.toInt()
                check(pathRole in 0 until ROLE_COUNT) { "preview_path_role_invalid" }
                val pathTool = floats.exactInt(
                    HEADER_FLOATS + pathStart * SEGMENT_STRIDE + TOOL_OFFSET,
                    MAX_TOOL_COUNT - 1,
                )
                var segmentIndex = pathStart
                while (segmentIndex < pathEnd) {
                    val offset = segmentIndex * SEGMENT_STRIDE
                    check(segments[offset + 5].toInt() == pathRole) {
                        "preview_path_role_invalid"
                    }
                    check(segments[offset + TOOL_OFFSET].exactTool() == pathTool) {
                        "preview_path_tool_invalid"
                    }
                    segmentIndex += 1
                }
                checkNotNull(pathBuilder).add(pathStart, pathEnd, pathRole)
                previousEnd = pathEnd
                pathOffset += PATH_STRIDE
            }
            check(previousEnd == totalSegments) { "preview_path_coverage_invalid" }
            val pathIndex = pathBuilder?.finish() ?: ContinuousPathIndex.EMPTY
            pathIndex.pathsByRole.indices.forEach { role ->
                check(pathIndex.pathsByRole[role].segmentCount == roleSegmentCounts[role]) {
                    "preview_path_role_count_invalid"
                }
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
                preview.cachedPathIndex = pathIndex
            }
        }

        private fun decodeNativePayload(
            raw: FloatArray?,
            validateCoordinates: Boolean,
        ): GcodeLayerPreview {
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
            val totalSegments = raw.exactInt(7, MAX_SEGMENTS)
            val pathCount = raw.exactInt(8, MAX_SEGMENTS)
            check(pathCount <= totalSegments) { "preview_path_count_invalid" }
            val roleSegmentCounts = IntArray(ROLE_COUNT) { role ->
                raw.exactInt(ROLE_COUNTS_OFFSET + role, MAX_SEGMENTS)
            }
            check(roleSegmentCounts.sum() == totalSegments) { "preview_role_count_invalid" }
            val segmentEnd = HEADER_FLOATS + totalSegments * SEGMENT_STRIDE
            val expectedPayloadFloats = segmentEnd + pathCount * PATH_STRIDE
            check(raw.size <= MAX_PAYLOAD_FLOATS && raw.size == expectedPayloadFloats) {
                "preview_size_invalid"
            }
            val segments = raw.copyOfRange(HEADER_FLOATS, segmentEnd)
            if (validateCoordinates) {
                val verifiedRoleCounts = IntArray(ROLE_COUNT)
                var offset = 0
                while (offset < segments.size) {
                    var axis = 0
                    while (axis < 5) {
                        check(
                            segments[offset + axis].isFinite() &&
                                kotlin.math.abs(segments[offset + axis]) <= MAX_COORDINATE_ABS_MM,
                        ) { "preview_coordinate_invalid" }
                        axis += 1
                    }
                    val roleValue = segments[offset + 5]
                    check(roleValue.isFinite() && roleValue % 1f == 0f) {
                        "preview_role_invalid"
                    }
                    val role = roleValue.toInt()
                    check(role in 0 until ROLE_COUNT) { "preview_role_invalid" }
                    segments[offset + TOOL_OFFSET].exactTool()
                    verifiedRoleCounts[role] += 1
                    offset += SEGMENT_STRIDE
                }
                check(verifiedRoleCounts.contentEquals(roleSegmentCounts)) {
                    "preview_role_count_invalid"
                }
            }
            val pathBuilder = if (pathCount == 0) null else PrimitivePathBuilder(totalSegments)
            var previousEnd = 0
            var pathOffset = segmentEnd
            repeat(pathCount) {
                val pathStart = previousEnd
                val pathEnd = raw.exactInt(pathOffset, totalSegments)
                check(pathStart < pathEnd) {
                    "preview_path_range_invalid"
                }
                val roleValue = segments[pathStart * SEGMENT_STRIDE + 5]
                check(roleValue.isFinite() && roleValue % 1f == 0f) {
                    "preview_path_role_invalid"
                }
                val pathRole = roleValue.toInt()
                check(pathRole in 0 until ROLE_COUNT) { "preview_path_role_invalid" }
                val pathTool = segments[pathStart * SEGMENT_STRIDE + TOOL_OFFSET].exactTool()
                var segmentIndex = pathStart
                while (segmentIndex < pathEnd) {
                    val offset = segmentIndex * SEGMENT_STRIDE
                    check(segments[offset + 5].toInt() == pathRole) {
                        "preview_path_role_invalid"
                    }
                    check(segments[offset + TOOL_OFFSET].exactTool() == pathTool) {
                        "preview_path_tool_invalid"
                    }
                    segmentIndex += 1
                }
                checkNotNull(pathBuilder).add(pathStart, pathEnd, pathRole)
                previousEnd = pathEnd
                pathOffset += PATH_STRIDE
            }
            check(previousEnd == totalSegments) { "preview_path_coverage_invalid" }
            val pathIndex = pathBuilder?.finish() ?: ContinuousPathIndex.EMPTY
            pathIndex.pathsByRole.indices.forEach { role ->
                check(pathIndex.pathsByRole[role].segmentCount == roleSegmentCounts[role]) {
                    "preview_path_role_count_invalid"
                }
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
                preview.cachedPathIndex = pathIndex
            }
        }

        internal const val MAX_SEGMENTS = 120_000
        private const val HEADER_FLOATS = 9 + ROLE_COUNT
        private const val ROLE_COUNTS_OFFSET = 9
        private const val PATH_STRIDE = 1
        internal const val MAX_PAYLOAD_FLOATS = HEADER_FLOATS +
            MAX_SEGMENTS * (SEGMENT_STRIDE + PATH_STRIDE)
        internal const val MAX_PAYLOAD_BYTES = MAX_PAYLOAD_FLOATS * Float.SIZE_BYTES
        private const val PAYLOAD_MAGIC = 17_491f
        private const val PAYLOAD_VERSION = 3f
        private const val MAX_LAYER_COUNT = 1_000_000
        private const val MAX_COORDINATE_ABS_MM = 1_000_000f
    }
}

internal data class PreviewDerivedCacheState(
    val indexedPathCount: Int,
    val renderPlanCount: Int,
)

private fun FloatArray.exactInt(index: Int, maximum: Int): Int {
    val value = this[index]
    check(value.isFinite() && value % 1f == 0f && value in 0f..maximum.toFloat()) {
        "preview_integer_invalid"
    }
    return value.toInt()
}

private fun Float.exactTool(): Int {
    check(isFinite() && this % 1f == 0f) { "preview_tool_invalid" }
    val tool = toInt()
    check(tool in 0 until GcodeLayerPreview.MAX_TOOL_COUNT) { "preview_tool_invalid" }
    return tool
}

private fun FloatBuffer.exactInt(index: Int, maximum: Int): Int {
    val value = this[index]
    check(value.isFinite() && value % 1f == 0f && value in 0f..maximum.toFloat()) {
        "preview_integer_invalid"
    }
    return value.toInt()
}

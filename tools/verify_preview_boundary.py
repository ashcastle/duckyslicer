#!/usr/bin/env python3
"""Enforce the bounded primitive JNI contract for G-code preview data."""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


class VerificationError(ValueError):
    pass


def verify_preview_boundary(sources: dict[str, str]) -> None:
    required = {
        "NativeEngine.kt",
        "PreviewModels.kt",
        "PreviewSummary.kt",
        "AppSettings.kt",
        "AppSettingsSheet.kt",
        "ToolpathPreviewView.kt",
        "ModelTransform.kt",
        "TransformGizmo.kt",
        "LayOnFaceCandidates.kt",
        "PrepareModelPreviewView.kt",
        "PrepareModelOverlays.kt",
        "OrcaFacetPreview.kt",
        "OrcaFacetEditing.kt",
        "PrepareModelPicking.kt",
        "ModelPreparationScheduler.kt",
        "PreviewPerformanceHarnessActivity.kt",
        "WorkspaceScreen.kt",
        "MainActivity.kt",
        "ProjectState.kt",
        "ProjectStore.kt",
        "OrcaModelCut.kt",
        "SliceOperationViewModel.kt",
        "OnDeviceSlicer.kt",
        "SlicerProcessService.kt",
        "NativeEngineInstrumentedTest.kt",
        "OrcaModelImportInstrumentedTest.kt",
        "OrcaMultiColorPaintInstrumentedTest.kt",
        "OrcaSeamPaintInstrumentedTest.kt",
        "PrepareModelRendererInstrumentedTest.kt",
        "PrepareModelPickingTest.kt",
        "ModelInfoTest.kt",
        "TransformGizmoTest.kt",
        "LayOnFaceCandidatesTest.kt",
        "ModelImportPerformanceInstrumentedTest.kt",
        "ToolpathRendererPerformanceInstrumentedTest.kt",
        "ToolpathSurfaceInstrumentedTest.kt",
        "ToolpathNativePackingInstrumentedTest.kt",
        "AccessibilityInstrumentedTest.kt",
        "PreviewModelsTest.kt",
        "PreviewSummaryTest.kt",
        "SliceOutcomeRestorationTest.kt",
        "PreviewPerformancePolicyTest.kt",
        "ToolpathMeshBuilderTest.kt",
        "WorkspaceLayoutPolicyTest.kt",
        "OrcaFacetEditingTest.kt",
        "ProjectStateTest.kt",
        "SupportPaintHitTest.kt",
        "strings.xml",
        "strings-ko.xml",
        "lib.rs",
        "CONTRIBUTING.md",
    }
    missing = sorted(required - sources.keys())
    if missing:
        raise VerificationError(f"preview boundary sources are missing: {missing}")

    native = sources["NativeEngine.kt"]
    if "external fun previewGcodeRangeInto(" not in native or "output: ByteBuffer" not in native:
        raise VerificationError("Android preview JNI does not use bounded direct-buffer output")
    if "inspectStlPayload(path: String): FloatArray?" not in native:
        raise VerificationError("Android model inspection JNI does not return a nullable primitive float array")
    if "external fun packToolpathGeometry(" not in native or "output: ByteBuffer" not in native:
        raise VerificationError("Android Preview does not expose bounded Rust geometry packing")
    for marker in (
        "NativePreviewBufferPool.acquire()",
        "NativePreviewBufferPool.release(payload)",
        "MAX_RETAINED_BUFFERS = 2",
        "lease.generation == generation",
        "fun trimForMemoryPressure()",
        "generation += 1L",
        "available.clear()",
    ):
        if marker not in native:
            raise VerificationError(f"Android Preview direct-buffer pooling is missing: {marker}")

    model = sources["MainActivity.kt"]
    for marker in (
        "fun fromNative(raw: FloatArray?, localPath: String)",
        "MODEL_PREVIEW_PAYLOAD_MAGIC",
        "MODEL_PREVIEW_PAYLOAD_VERSION",
        "MODEL_PREVIEW_HEADER_FLOATS = 12",
        "MODEL_MAX_PREVIEW_TRIANGLES = 12_000",
        "MODEL_MAX_COARSE_PREVIEW_TRIANGLES = 2_000",
        "MODEL_MAX_DETAIL_PREVIEW_TRIANGLES = 48_000",
        "coarsePreviewTriangles",
        "detailPreviewTriangles",
        "raw.copyOfRange(vertexStart, vertexEnd)",
        "exactModelIntegerOrNull()",
        "MODEL_MAX_COORDINATE_ABS_MM",
    ):
        if marker not in model:
            raise VerificationError(f"Android model payload validation is missing: {marker}")
    for source_name in ("ProjectStore.kt", "OrcaModelCut.kt", "OnDeviceSlicer.kt"):
        source = sources[source_name]
        if "NativeEngine.inspectStl(" in source or "ModelInfo.fromJson(" in source:
            raise VerificationError(
                f"production model loading reverted to JSON decoding in {source_name}"
            )
        if "inspectModel(" not in source:
            raise VerificationError(f"primitive model loading is missing from {source_name}")

    preview = sources["PreviewModels.kt"]
    for marker in (
        "fun fromNative(raw: FloatArray?)",
        "PAYLOAD_MAGIC",
        "PAYLOAD_VERSION",
        "HEADER_FLOATS = 9 + ROLE_COUNT",
        "PATH_STRIDE = 1",
        "MAX_SEGMENTS = 120_000",
        "MAX_PAYLOAD_FLOATS",
        "preview_coordinate_invalid",
        "preview_role_invalid",
        "fun fromTrustedNative(raw: FloatArray?)",
        "raw: ByteBuffer?",
        "usedFloats: Int",
        "raw.isDirect && raw.order() == ByteOrder.nativeOrder()",
        "MAX_PAYLOAD_BYTES",
        "validateCoordinates = true",
        "validateCoordinates = false",
        "cachedPathIndex",
        "PrimitivePathBuilder(totalSegments)",
        "preview.cachedPathIndex = pathIndex",
        "RolePathIndex(",
        "val pathOrdinals: IntArray",
        "selectedPaths = BooleanArray(allPaths.pathCount)",
        "selectedPathCounts = IntArray(ROLE_COUNT)",
        "internal val pathStarts: IntArray",
        "internal val pathEndsExclusive: IntArray",
        "internal val segmentCount: Int",
        "val segmentOffsets: IntArray by lazy",
        "selectedPathCount = selectedPathCounts.sum()",
        "releaseDerivedMemoryForMemoryPressure()",
        "derivedCacheGeneration += 1L",
        "cachedRenderPlans.clear()",
        "derivedCacheGeneration != expectedCacheGeneration",
    ):
        if marker not in preview:
            raise VerificationError(f"Android preview payload validation is missing: {marker}")
    if "JSONObject" in preview or "fun fromJson" in preview:
        raise VerificationError("G-code preview reverted to object-heavy JSON decoding")
    if "selected.sortedBy(SegmentPath::start)" in preview:
        raise VerificationError("dense Preview planning reverted to boxed path sorting")

    settings = sources["AppSettings.kt"]
    for marker in (
        "PreviewDetail.AUTOMATIC",
        "val previewDetail: PreviewDetail = PreviewDetail.AUTOMATIC",
        "PreviewDeviceCapabilities",
        "manager?.isLowRamDevice",
        "PreviewDetail.AUTOMATIC -> PreviewDetail.PERFORMANCE",
        "resolvePreviewDetail(",
        "previewDetailForInteraction(",
        "depthPreviewSegmentBudget(",
        "shouldDrawToolpathLines(",
        "shouldUseDenseOverviewLines(",
        "depthPreviewOverviewSegmentBudget(",
        "DENSE_PREVIEW_OVERVIEW_SEGMENTS = 40_000",
        "DENSE_PREVIEW_RIBBON_ZOOM = 1.5f",
        "DENSE_PREVIEW_MIN_SEGMENTS = 10_000",
        "DENSE_PREVIEW_MAX_SEGMENTS = 32_000",
        "compatibilityPreviewSegmentBudget(",
        "AdaptivePreviewDetailController(",
        "ADAPTIVE_PREVIEW_FAST_FRAME_MS = 24.0",
        "ADAPTIVE_PREVIEW_FAST_SAMPLE_COUNT = 5",
        "recordCompletedFrame(",
        "currentDetail = lastProvenDetail",
        "prepareSurfaceSize(",
        "PREPARE_INTERACTION_SURFACE_SCALE = 0.72f",
    ):
        if marker not in settings:
            raise VerificationError(f"adaptive preview policy is missing: {marker}")

    renderer = sources["ToolpathPreviewView.kt"]
    for marker in (
        "renderMode = RENDERMODE_WHEN_DIRTY",
        "ToolpathGeometryUploadState",
        "System.identityHashCode(preview)",
        "uploadState.needsUpload(scene)",
        "ToolpathGeometryUploadState(capacity = GPU_GEOMETRY_CACHE_SIZE)",
        "const val GPU_GEOMETRY_CACHE_SIZE = 2",
        "pendingPrewarmScene",
        "requestPrewarmFrame()",
        "uploadState.markUsed(scene)",
        "releaseStaleGeometry(buildSet",
        "candidate.canReuseGeometryWhileBuilding(scene)",
        "fallbackFrameCount += 1",
        "uploadState.remove(staleScene)",
        "GLES30.glGenBuffers",
        "GLES30.glDeleteBuffers",
        "GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER",
        "GLES30.glBufferData(",
        "GLES30.GL_STATIC_DRAW",
        "GLES30.glDrawArraysInstanced(",
        "GLES30.GL_TRIANGLE_STRIP",
        "private fun drawToolpathLines(",
        "GLES30.glDrawArrays(GLES30.GL_LINES",
        "renderAsLines = true",
        "val lineVertices = when {",
        "lineBuilder?.finish() ?: ByteBuffer.allocateDirect(0)",
        "const val TOOLPATH_VERTICES_PER_INSTANCE = 4",
        "GLES30.glVertexAttribDivisor(",
        "GLES30.GL_UNSIGNED_BYTE",
        "geometryUploadCountForTest",
        "cachedGeometryCountForTest",
        "ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN",
        "registerComponentCallbacks(memoryCallbacks)",
        "unregisterComponentCallbacks(memoryCallbacks)",
        "queueEvent { toolpathRenderer.releaseGpuGeometryForMemoryPressure() }",
        "releaseGpuGeometryForMemoryPressure()",
        "latestSubmittedScene?.preview?.releaseDerivedMemoryForMemoryPressure()",
        "NativePreviewBufferPool.trimForMemoryPressure()",
        "geometryGeneration.incrementAndGet()",
        "generation == geometryGeneration.get()",
        "if (generation != geometryGeneration) return false",
        "ToolpathUploadPayload",
        "INSTANCE_STRIDE_BYTES = 32",
        "INSTANCE_START_OFFSET_BYTES",
        "INSTANCE_COLOR_OFFSET_BYTES",
        "val toolpathInstances = when {",
        "instanceBuilder?.finish() ?: ByteBuffer.allocateDirect(0)",
        "EARLY_Z_OPACITY_THRESHOLD = 0.85f",
        "val reverseForEarlyZ = scene.opacity >= EARLY_Z_OPACITY_THRESHOLD",
        "plan.pathStarts[pathIndex]",
        "plan.pathEndsExclusive[pathIndex]",
        ".allocateDirect(capacity * Float.SIZE_BYTES)",
        "ByteBuffer.allocateDirect(usedFloats * Float.SIZE_BYTES)",
        ".also { buffer -> buffer.asFloatBuffer().put(values, 0, usedFloats) }",
        "TOOLPATH_INSTANCE_FLOATS",
        "Float.fromBits(",
        "setInteractionActive(true)",
        "postDelayed(restoreDetail, DETAIL_RESTORE_DELAY_MS)",
        "previewDetailForInteraction(sourceScene.detail, interactionActive = true)",
        "depthPreviewSegmentBudget(scene.detail)",
        "val overview = shouldUseDenseOverviewLines(sourceSegmentCount, zoom)",
        "val overviewBudget = depthPreviewOverviewSegmentBudget(",
        "shouldDrawToolpathLines(",
        "overview,",
        "adaptivePreviewController.shouldMeasure(",
        "GLES30.glFinish()",
        'glOperationSucceeded("adaptive_gpu_completion")',
        "adaptivePreviewController.recordCompletedFrame(",
        "reportFrameReady",
        "reportRendererStarting",
        "reportUnavailable",
        "override fun surfaceDestroyed(holder: SurfaceHolder)",
        "RENDERER_STARTUP_TIMEOUT_MS = 5_000L",
        "GLES30.glGetError()",
        'failRenderer("program_creation")',
        "NativeToolpathPacker.pack(scene, plan, reverseForEarlyZ)",
        "val maximumBytes = plan.segmentCount * PACKED_TOOLPATH_FLOATS * Float.SIZE_BYTES",
        "ByteBuffer.allocateDirect(maximumBytes).order(ByteOrder.nativeOrder())",
        "if (segmentCount !in 0..plan.segmentCount) return null",
        "output.limit(usedBytes)",
        "nativePackingUsed = nativePacked != null",
    ):
        if marker not in renderer:
            raise VerificationError(f"GPU preview upload contract is missing: {marker}")
    if (
        "private var vertices: FloatBuffer?" in renderer
        or "private var vertexBufferId" in renderer
        or "builder.writeTo" in renderer
    ):
        raise VerificationError("GPU preview reverted to duplicated client-side vertex storage")
    if "plan.segmentOffsets.size * 6 * 8" in renderer:
        raise VerificationError("GPU preview reverted to expanded per-segment ribbon vertices")
    if "plan.segmentOffsets" in renderer:
        raise VerificationError("GPU preview reverted to materialized per-segment offsets")

    native_packing_test = sources["ToolpathNativePackingInstrumentedTest.kt"]
    for marker in (
        "rustPackingIsByteExactWithTheManagedFallback",
        "ToolpathMeshBuilder.build(scene, useNativePacking = true)",
        "ToolpathMeshBuilder.build(scene, useNativePacking = false)",
        "assertArrayEquals(managed.toolpathInstances.bytes(), native.toolpathInstances.bytes())",
        "assertArrayEquals(managed.lineVertices.bytes(), native.lineVertices.bytes())",
    ):
        if marker not in native_packing_test:
            raise VerificationError(f"native Preview packing regression is missing: {marker}")

    prepare_renderer = sources["PrepareModelPreviewView.kt"]
    for marker in (
        "PrepareModelTopologyKey(",
        "filamentSlot = volume.filamentSlot",
        "withModelPreparationContext",
        "PrepareModelSceneBuilder.build(\n                    projectObjects,",
        "PrepareModelSceneBuilder.build(\n                    emptyList()",
        "overlays.takeIf { sceneLoad.complete }.orEmpty()",
        "overlays: List<PrepareModelOverlayData>",
        "overlay.customVertices",
        "buffers.vertices",
        "customVertices.size / PREPARE_VERTEX_FLOATS",
        "detailVertices: FloatArray = vertices",
        "coarseVertices: FloatArray = vertices",
        "prepareModelRenderTier(",
        "PrepareModelRenderTier.COARSE -> mesh.coarseVertices",
        "PrepareModelRenderTier.PREVIEW -> mesh.vertices",
        "PrepareModelRenderTier.DETAIL -> mesh.detailVertices",
        "var detailRefinementReady by remember(topology) { mutableStateOf(false) }",
        "val detailWorkAllowed = prepareDetailNormalsAllowed(",
        "if (!detailWorkAllowed) return@LaunchedEffect",
        "delay(PREPARE_DETAIL_REFINEMENT_DELAY_MS)",
        "refinementReady = detailRefinementReady",
        "!refinementReady -> PrepareModelRenderTier.PREVIEW",
        "ensureMeshTierUploaded(frame.geometry, renderTier)",
        "private val meshVertexBuffers = IdentityHashMap<FloatArray, Int>()",
        "private val meshNormalBuffers = IdentityHashMap<FloatArray, Int>()",
        "meshVertexBuffers.size + meshNormalBuffers.size",
        "PrepareModelNormalUploadCache.precompute(",
        "positions.withPrecomputedPrepareInteractionNormals { ensureActive() }",
        "uniquePrepareDetailVertexArrays(geometry.meshes)",
        "geometry.normalUploadCache.addPrecomputed(",
        "prepareDetailNormalsAllowed(",
        "!sceneLoad.detailNormalsReady",
        "geometry.normalUploadCache.take(vertices)",
        "private fun uploadNormalBuffer(id: Int, packedNormals: ByteArray)",
        "buildPackedPrepareSmoothNormals(vertices)",
        "val normalPositionHeads = IntArray(prepareNormalHashCapacity(vertexCount))",
        "findPrepareNormalPositionSlot(",
        "PREPARE_NORMAL_CANCELLATION_MASK",
        "prepareMeshGpuBytes(mesh.coarseVertices)",
        "in vec3 aNormal;",
        "out vec3 vNormal;",
        "GLES30.GL_BYTE",
        "prepareSurfaceSize(",
        "texture.setDefaultBufferSize(target.width, target.height)",
        "resizeEglSurface(texture, target)",
        "EGL14.eglDestroySurface(eglDisplay, eglSurface)",
        "renderer.setLogicalViewportSize(logicalSurfaceWidth, logicalSurfaceHeight)",
        "GLES30.glUniform2f(viewportLocation, logicalWidth.toFloat(), logicalHeight.toFloat())",
        "memoryPressureActive: Boolean = false",
        "onMemoryPressure: () -> Unit = {}",
        "onMemoryPressureRecovered: () -> Unit = {}",
        "view.setMemoryPressureActive(memoryPressureActive)",
        "override fun onLowMemory() = releasePrepareMemory()",
        "if (memoryPressureActive) return",
        "private fun requestMemoryPressureRecovery()",
        "retainedTopologyBufferCountForTest()",
        "private fun initializeGeometry(geometry: PrepareModelSceneGeometry)",
        "private fun ensureMeshTierUploaded(",
        "additionalDetailBudgetBytes: Long = MAX_PREPARE_ADDITIONAL_DETAIL_GPU_BYTES",
        "lowDetailBudgetBytes: Long = MAX_PREPARE_LOW_DETAIL_GPU_BYTES",
        "boundedPrepareLowMeshes(rawMeshes, lowDetailBudgetBytes)",
        "boundedPrepareDetailMeshes(lowMeshes, additionalDetailBudgetBytes)",
        ".sortedBy { indexed -> indexed.value.role != ProjectVolumeRole.MODEL_PART }",
        "MAX_PREPARE_ADDITIONAL_DETAIL_GPU_BYTES = 16L * 1_024L * 1_024L",
        "MAX_PREPARE_LOW_DETAIL_GPU_BYTES = 24L * 1_024L * 1_024L",
    ):
        if marker not in prepare_renderer:
            raise VerificationError(f"Prepare model loading contract is missing: {marker}")
    if "dFdx(vWorldPosition)" in prepare_renderer:
        raise VerificationError("Prepare model lighting must not expose triangle facets")

    prepare_overlays = sources["PrepareModelOverlays.kt"]
    for marker in (
        "orcaFacetAnnotations: OrcaFacetAnnotations",
        "sourceFacetIndex !in volume.multiColorPaint.facets",
        "sourceFacetIndex !in volume.supportPaint.facets",
        "sourceFacetIndex !in volume.seamPaint.facets",
        "OrcaFacetPreviewTessellator.tessellate(",
        "MAX_EXACT_SPLIT_OVERLAY_TRIANGLES = 48_000",
        "customVertices = packed",
    ):
        if marker not in prepare_overlays:
            raise VerificationError(f"bounded exact Prepare overlay contract is missing: {marker}")

    facet_preview = sources["OrcaFacetPreview.kt"]
    for marker in (
        "maximumTriangles: Int",
        "paintedLeaves > maximumTriangles",
        "dominantState",
        "ArrayDeque<FacetTriangle>()",
        "children.forEach(pending::addLast)",
        "splitSides == 3 && specialSide == 0",
    ):
        if marker not in facet_preview:
            raise VerificationError(f"bounded exact facet tessellation is missing: {marker}")

    facet_editing = sources["OrcaFacetEditing.kt"]
    for marker in (
        "MAX_SUBDIVISION_DEPTH = 4",
        "fun OrcaFacetAnnotation.paintAt(",
        "fun OrcaFacetAnnotation.paintAll(",
        "internal fun exactPaintFacetsToClear(",
        "MAX_FACET_PAINT_BATCH_TARGETS = 256",
        "preserving every untouched recursive child",
        "children = List(4) { FacetNode.Leaf(this.state) }",
        ").compressed()",
        "encoded.length > OrcaFacetAnnotation.MAX_TRIANGLE_VALUE_BYTES",
        "next.size >= OrcaFacetAnnotation.MAX_ANNOTATED_TRIANGLES",
        "split(splitSides: Int, specialSide: Int)",
    ):
        if marker not in facet_editing:
            raise VerificationError(f"bounded exact facet editing is missing: {marker}")

    workspace = sources["WorkspaceScreen.kt"]
    for marker in (
        "internal fun facetPaintTarget(",
        "ceil(log2((longestEdge / targetDiameter).toDouble()))",
        "val paintedTargets = HashSet<FacetPaintTarget>()",
        "supportAnnotationStart",
        "multiColorAnnotationStart",
        "internal fun facetBrushSampleOffsets(",
        "internal fun facetBrushStrokeCenters(",
        "FACET_BRUSH_SAMPLE_COUNT = 37",
        "MAX_FACET_BRUSH_STROKE_CENTERS = 6",
        "FACET_BRUSH_SAMPLE_HIT_RADIUS_RATIO = 0.28f",
        "brushSampleHitRadiusPx",
        "findPrepareFacetsAtScreenSamples(",
        "internal fun closestModelTrianglesAtPoints(",
        "paintFootprintsAt(centers: List<Offset>)",
        "List<FacetPaintTarget>",
        "MAX_FACET_PAINT_BATCH_TARGETS",
        "fun drawFacetBrushCursor()",
        "private fun FacetBrushSizeControl(",
        "R.string.paint_brush_size",
        "range = FACET_BRUSH_MIN_RADIUS_DP..FACET_BRUSH_MAX_RADIUS_DP",
        "internal data class PrepareDerivedCacheLifecycle(",
        "prepareDerivedCacheLifecycle.suspended",
        "modelPickingIndices = emptyMap()",
        "layOnFaceCandidates = emptyMap()",
        "prepareOverlays = emptyList()",
        "onMemoryPressureRecovered = {",
        "currentResumePrepareDerivedCaches()",
    ):
        if marker not in workspace:
            raise VerificationError(f"partial-facet brush routing is missing: {marker}")
    for marker in (
        "previousAnnotation.paintAll(",
        "exactPaintFacetsToClear(",
    ):
        if marker not in model:
            raise VerificationError(f"batched facet state routing is missing: {marker}")

    project_state = sources["ProjectState.kt"]
    for marker in (
        "fun updateExactSupportPaint(",
        "fun commitExactSupportPaint(",
        "fun updateExactSeamPaint(",
        "fun commitExactSeamPaint(",
        "fun updateExactMultiColorPaint(",
        "fun commitExactMultiColorPaint(",
        "private fun commitExactFacetPaint(",
    ):
        if marker not in project_state:
            raise VerificationError(f"partial-facet history routing is missing: {marker}")

    for test_file, markers in {
        "OrcaFacetEditingTest.kt": (
            "partialEditsPaintAndEraseIndependentRegionsThenCompress",
            "importedIrregularChildrenRemainWhenOneRegionIsRefined",
            "batchedFacetPaintMatchesSequentialEditsAndResolvesFallbackOncePerFacet",
        ),
        "ProjectStateTest.kt": (
            "partialFacetPaintingCommitsOneUndoEntryAndPreservesOtherExactCategories",
            "seamAndMultiColorPartialPaintUseTheirOwnExactChannels",
        ),
        "SupportPaintHitTest.kt": (
            "facetBrushUsesViewAwareBoundedSubdivisionAndStableRegions",
            "facetBrushClampsNearbyEdgeHitsToValidBarycentricCoordinates",
            "facetBrushFootprintUsesABoundedCircularSamplePattern",
            "facetBrushStrokeFillsNormalPointerGapsAndCapsExtremeJumps",
            "compatibilityBrushBatchKeepsEverySampleFrontmostWithoutTemporarySorting",
        ),
        "OrcaMultiColorPaintInstrumentedTest.kt": (
            "fourColorFacetPaintProducesObjectAndPrimeTowerExtrusionOnEveryTool",
            "primeTowerWallTypesProduceDistinctExtrusionGeometry",
            "primeTowerPositionMovesThePhysicalTowerWithoutMovingTheObjects",
            "primeTowerBrimChamferChangesPhysicalMultiLayerBrimGeometry",
            "flushMultiplierChangesPhysicalPurgeExtrusionWithoutMovingObjectPaths",
            "purgeRoutingChangesRealInfillAndObjectExtrusionPaths",
            "supportPurgeRoutingAndSolubleInterfaceChangeRealMaterialPaths",
            "setOf(0, 1, 2, 3)",
            "analysis.objectExtrusionByTool.filterValues",
            "analysis.primeTowerExtrusionByTool.filterValues",
            "analysis.primeTowerMotions >= 40",
            "analysis.toolChanges >= 6",
            "withoutTowerAnalysis.primeTowerMotions",
            "rectangle.primeTowerMotionSignature",
            "Changing X must move the physical prime tower by 80 mm",
            "Changing Y must move the physical prime tower by 50 mm",
            "Tower placement must not rewrite object extrusion paths",
            "Disabling chamfer must leave only the first-layer tower brim",
            "A wider chamfer must retain the physical brim for more layers",
            "A wider chamfer must emit more physical brim extrusion motions",
            "Tower brim chamfer must not rewrite object extrusion paths",
            "intoInfill.extrusionMotionsByRoleAndTool[\"Sparse infill\"]",
            "intoObjects.nonTowerMotionSignature()",
            "routed.supportMotionSignature()",
            "soluble.supportExtrusionTools()",
        ),
    }.items():
        for marker in markers:
            if marker not in sources[test_file]:
                raise VerificationError(f"partial-facet regression is missing: {marker}")
    if "editedMultiColor = painted.multiColor.paintAt(" not in sources[
        "OrcaModelImportInstrumentedTest.kt"
    ]:
        raise VerificationError("edited partial-facet native slicing regression is missing")

    seam_paint_test = sources["OrcaSeamPaintInstrumentedTest.kt"]
    for marker in (
        "enforcedAndBlockedSeamFacetsControlRealOuterWallStarts",
        "outerWallStartPoints",
        "extrusionMotions(enforced)",
        "extrusionMotions(blocked)",
        "x <= 40.5f",
        "x > 40.5f",
    ):
        if marker not in seam_paint_test:
            raise VerificationError(f"physical seam-paint regression is missing: {marker}")

    prepare_picking = sources["PrepareModelPicking.kt"]
    for marker in (
        "buildPreparePickingIndices(",
        "PreparePickingIndexBuilder(",
        "PREPARE_PICKING_TRIANGLES_PER_LEAF = 48",
        "intersectsProjectedBounds(",
        ".candidateTriangles(",
        "return result.copyOf(output)",
        "candidateCount = candidates?.size ?: triangleCount",
        "candidates?.get(candidatePosition) ?: candidatePosition",
        "PREPARE_PICKING_CANCELLATION_INTERVAL = 256",
        "checkCancellation()",
        "internal fun findPrepareFacetsAtScreenSamples(",
        "MAX_PREPARE_BRUSH_SAMPLES = 64",
        "val candidateRadius = footprintRadius + touchRadiusPx",
        "samplePositions.forEachIndexed",
    ):
        if marker not in prepare_picking:
            raise VerificationError(f"exact Prepare picking acceleration is missing: {marker}")

    model_preparation = sources["ModelPreparationScheduler.kt"]
    for marker in (
        "Dispatchers.Default.limitedParallelism(1)",
        "Process.THREAD_PRIORITY_BACKGROUND",
        "withModelPreparationContext(",
        "Process.setThreadPriority(threadId, previousPriority)",
    ):
        if marker not in model_preparation:
            raise VerificationError(f"contention-safe model preparation is missing: {marker}")

    transform = sources["ModelTransform.kt"]
    for marker in (
        "MinimumRotatedZCalculator(this)",
        "val afterXz = scaledY * sinX + scaledZ * cosX",
        "result = minOf(result, -scaledX * sinY + afterXz * cosY)",
        "minimumZWithoutTilt(geometry, centerZ)",
    ):
        if marker not in transform:
            raise VerificationError(f"allocation-free Prepare placement is missing: {marker}")
    minimum_z_section = transform.split(
        "internal fun ModelTransform.minimumRotatedZ(projectObject: ProjectObject)", 1
    )[-1].split("internal fun ModelTransform.placeVertex(", 1)[0]
    if "transformLocal(" in minimum_z_section or "floatArrayOf(" in minimum_z_section:
        raise VerificationError("Prepare placement reverted to per-vertex array allocation")

    prepare_tests = sources["PrepareModelRendererInstrumentedTest.kt"]
    for marker in (
        "densePrepareSceneBuildStaysWithinLoadBudget",
        "denseMinimumRotatedZStaysWithinTransformBudget",
        "densePrepareCameraFramesReuseOneUploadedMesh",
        "densePreparePickingStaysWithinTapBudget",
        "p95Ms <= 50.0",
        "renderer.geometryUploadCountForTest() == 1",
        "p95Ms <= 16.0",
        "objectP95Ms <= 16.0",
        "facetP95Ms <= 16.0",
        "brushP95Ms <= 16.0",
        "12k-triangle 37-point brush selection must stay inside one frame",
        "denseDefaultPlacementStaysWithinLoadBudget",
        "denseUnpaintedOverlayBuildStaysWithinLoadBudget",
        "lastMeshVertexCountForTest()",
        "interactionActive = true",
        "p95Ms <= 1.0",
        "densePrepareInteractionReducesRasterWorkWithoutDroppingTheLowDetailShape",
        "densePrepareProgressivelyUploadsPreviewThenDetailAndGestureTopology",
        "The first useful frame retains four bed buffers and one position/normal pair",
        "Idle refinement appends one detail position/normal pair without recreating the scene",
        "Gesture entry appends one connected coarse position/normal pair",
        "productionPrepareSurfaceRestoresFullDetailAfterReducedRasterInteraction",
        "reducedMetrics.vertexCount",
        "reducedMetrics.p95Ms <= fullMetrics.p95Ms * 1.35 + 2.0",
        "Repeated memory callbacks must be deduplicated until foreground recovery",
        "Recovered Prepare rendering must remain available",
        "repeatedPlacementsShareOneLazilyRequestedDetailTopology",
        "Four bed buffers plus one shared position/normal topology must be retained",
        "geometry.normalUploadCache.pendingTopologyCountForTest()",
        "geometry.normalUploadCache.fallbackGenerationCountForTest()",
    ):
        if marker not in prepare_tests:
            raise VerificationError(f"Prepare performance regression is missing: {marker}")

    picking_tests = sources["PrepareModelPickingTest.kt"]
    for marker in (
        "spatialIndexCullsArbitraryFacetOrderWithoutChangingExactHits",
        "candidates.size in 1 until model.triangles",
        "assertTrue(index.leafCount > 1)",
        "pickingIndices = indices",
        "batchedBrushPickingKeepsEachSampleOnTheFrontmostSelectableSurface",
    ):
        if marker not in picking_tests:
            raise VerificationError(f"exact Prepare picking regression is missing: {marker}")

    model_tests = sources["ModelInfoTest.kt"]
    for marker in (
        "nativePayloadDecodesBoundedGeometryAndSourceFacetMapping",
        "nativePayloadRejectsMissingOrUnknownEnvelope",
        "nativePayloadRejectsNonFiniteOrInconsistentGeometry",
        "nativePayloadRejectsInvalidSourceTriangleIndices",
    ):
        if marker not in model_tests:
            raise VerificationError(f"model payload host regression is missing: {marker}")

    model_performance = sources["ModelImportPerformanceInstrumentedTest.kt"]
    for marker in (
        "denseBinaryStlUsesBoundedPrimitiveImportWithinBudget",
        "sourceTriangles=${info.triangles}",
        "native.last() / 1_000_000.0 <= 250.0",
        "decode.last() / 1_000_000.0 <= 100.0",
        "(native.last() + decode.last()) / 1_000_000.0 <= 300.0",
    ):
        if marker not in model_performance:
            raise VerificationError(f"model import performance regression is missing: {marker}")

    toolpath_performance = sources["ToolpathRendererPerformanceInstrumentedTest.kt"]
    for marker in (
        "maximumLayerRangeBuildsResponsiveInteractionGeometry",
        "maximumPreviewCacheLookupNeverRehashesCoordinates",
        "segmentCount = GcodeLayerPreview.MAX_SEGMENTS",
        "preview.prepareRenderIndex()",
        "planP95Ms <= 25.0",
        "p50Ms <= 80.0",
        "p95Ms <= 150.0",
        "cacheP95Ms <= 4.0",
    ):
        if marker not in toolpath_performance:
            raise VerificationError(f"toolpath performance regression is missing: {marker}")

    surface_performance = sources["ToolpathSurfaceInstrumentedTest.kt"]
    for marker in (
        "productionSurfaceBuildsDenseGeometryOffTheGlThread",
        "Performance must lower raster resolution without changing Preview geometry",
        "Detail must restore the logical surface resolution",
        "Detail gestures must lower only raster resolution",
        "Settled Detail must return to full resolution",
        "UI memory pressure must drop rebuildable path and plan caches",
        "The same dense Preview must rebuild after memory-pressure reclamation",
    ):
        if marker not in surface_performance:
            raise VerificationError(f"adaptive SurfaceView regression is missing: {marker}")

    rust = sources["lib.rs"]
    for marker in (
        "MODEL_PREVIEW_PAYLOAD_MAGIC",
        "MODEL_PREVIEW_PAYLOAD_VERSION",
        "MODEL_PREVIEW_HEADER_FLOATS",
        "DETAIL_PREVIEW_TRIANGLE_LIMIT",
        "COARSE_PREVIEW_TRIANGLE_LIMIT",
        "detail_preview_triangles",
        "coarse_preview_triangles",
        "fn model_preview_payload(",
        "NativeEngine_inspectStlPayload",
        "get_direct_buffer_capacity(&output)",
        "get_direct_buffer_address(&output)",
        "std::ptr::copy_nonoverlapping(",
        '"Toolpath direct buffer is too small"',
    ):
        if marker not in rust:
            raise VerificationError(f"native model payload contract is missing: {marker}")

    benchmark = sources["PreviewPerformanceHarnessActivity.kt"]
    for marker in (
        "detail = PreviewDetail.AUTOMATIC",
        "renderer.automaticCalibrationSettledForTest()",
        "automaticDetail = checkNotNull(renderer.effectiveDetailForTest())",
        "renderer.zoomBy(",
        "holder.setFixedSize(target.width, target.height)",
        "framebufferWidth = width",
        "framebufferHeight = height",
        "interactionFramebufferWidth = width",
        "interactionFramebufferHeight = height",
        "Phase.WAIT_FOR_SURFACE",
        "MAXIMUM_AUTOMATIC_CALIBRATION_FRAMES = 30",
    ):
        if marker not in benchmark:
            raise VerificationError(f"foreground adaptive Preview benchmark is missing: {marker}")

    workspace = sources["WorkspaceScreen.kt"]
    for marker in (
        "previewDeviceCapabilities(context)",
        "resolvePreviewDetail(previewDetail, previewCapabilities)",
        "detail = previewDetail",
        "compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = false)",
        "compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = true)",
        "if (selectedTab == WorkspaceTab.PREVIEW)",
        "PreviewExportSplitButton(",
        "Icons.Default.ArrowDropDown",
        "Icons.Default.SaveAlt",
        "onSend = onRemoteUpload",
        "var previewControlsExpanded by rememberSaveable",
        "PreviewSummaryHeader(",
        "Icons.Default.ExpandLess",
        "Icons.Default.ExpandMore",
        "summary.filamentGrams",
        "summary.filamentMeters",
        "shouldUseDepthTestedPreview(",
        "depthPreviewRuntimeAvailable",
        "onUnavailable = { depthPreviewRuntimeAvailable = false }",
        "placements = modelPlacements",
        "currentModelPlacements[activeObject.id]",
        "val placement = checkNotNull(modelPlacements[projectObject.id])",
        "LaunchedEffect(\n        modelTopology,\n        interactionActive,\n        layOnFaceObjectId,\n        prepareDerivedCacheLifecycle,",
        "prepareDerivedCacheLifecycle.suspended || interactionActive ||",
        "delay(PREPARE_PICKING_PREWARM_DELAY_MS)",
        "modelPickingIndices = withModelPreparationContext",
        "LaunchedEffect(modelTopology, layOnFaceObjectId, prepareDerivedCacheLifecycle)",
        "buildPreparePickingIndices(listOf(selected))",
        "modelPickingIndices = modelPickingIndices + selectedIndices",
        "findLayOnFaceFacetAtScreen(",
        "checkCancellation = { ensureActive() }",
        "PrepareModelOverlayKey(",
        "orcaFacetAnnotations = volume.orcaFacetAnnotations",
        "prepareOverlays = withModelPreparationContext",
        "PrepareModelOverlayBuilder.build(",
        "overlays = prepareOverlays",
        "interactionActive && !facetPaintingActive && customVertices != null",
        "customVertices.indices step 9",
    ):
        if marker not in workspace:
            raise VerificationError(f"preview device policy is not connected to the UI: {marker}")
    lay_on_face_touch = workspace.split(
        "if (layOnFaceObject != null || measuringObject != null)", 1
    )[-1].split("if (supportPaintObject != null", 1)[0]
    if "layOnFaceCandidateFacets" in lay_on_face_touch:
        raise VerificationError(
            "place-on-face suggestions must not reject an otherwise valid surface tap"
        )
    if workspace.count("pickingIndices = currentModelPickingIndices") < 3:
        raise VerificationError("all GPU Prepare touch paths must use the immutable picking index")

    gizmo = sources["TransformGizmo.kt"]
    for marker in (
        "internal enum class TransformGizmoMode { MOVE, SCALE }",
        "internal fun transformGizmoLayoutForObject(",
        "internal fun hitTestTransformGizmo(",
        "internal fun transformGizmoDragMillimeters(",
        "internal fun moveTransformFromGizmo(",
        "internal fun scaleTransformFromGizmo(",
        "pixelsPerMillimeter = sceneScale * max(projectedLength, 0.08f)",
        "start.withAxisScale(axis.scaleAxis, requested, keepProportions = false, range = range)",
    ):
        if marker not in gizmo:
            raise VerificationError(f"direct transform gizmo contract is missing: {marker}")
    for marker in (
        "transformGizmoMode = transformGizmoMode",
        "hitTestTransformGizmo(layout, down.position, 22.dp.toPx())",
        "change.position - down.position",
        "currentTransformCommitCallback(dragStartTransform)",
        "drawSelectedTransformGizmo()",
        "selected = transformGizmoMode == TransformGizmoMode.MOVE",
        "selected = transformGizmoMode == TransformGizmoMode.SCALE",
    ):
        if marker not in workspace:
            raise VerificationError(f"direct transform gizmo is not connected to the UI: {marker}")

    lay_on_face_candidates = sources["LayOnFaceCandidates.kt"]
    for marker in (
        "candidatePlaneSupportsMesh(",
        "SUPPORT_CHECK_MULTIPLIER = 4",
        "SUPPORT_PLANE_TOLERANCE_MM = 0.05f",
        "if (hasPositive && hasNegative) return false",
        "checkCancellation()",
    ):
        if marker not in lay_on_face_candidates:
            raise VerificationError(
                f"supporting place-on-face candidate contract is missing: {marker}"
            )
    for marker in (
        "onLayOnFace: (String, FloatArray) -> Boolean",
        "if (onLayOnFace(objectId, triangle)) layingOnFace = false",
    ):
        if marker not in workspace:
            raise VerificationError(f"place-on-face retry contract is missing: {marker}")
    if (
        "fun laySelectedFaceOnBed(objectId: String, triangle: FloatArray): Boolean"
        not in model
    ):
        raise VerificationError("place-on-face result is not reported to the UI")
    lay_on_face_tests = sources["LayOnFaceCandidatesTest.kt"]
    if "recessedPlaneThatCannotSupportTheMeshIsNotSuggested" not in lay_on_face_tests:
        raise VerificationError("supporting place-on-face candidate regression is missing")
    if (
        "failedPlaceOnFaceTapKeepsTheModeOpenForRetry"
        not in sources["AccessibilityInstrumentedTest.kt"]
    ):
        raise VerificationError("place-on-face retry device regression is missing")

    export_controls = workspace.split("private fun PreviewExportSplitButton(", 1)[-1].split(
        "@Composable", 1
    )[0]
    for marker in (
        ".width(48.dp)",
        ".height(50.dp)",
        ".clickable(",
        "role = Role.Button",
        "modifier = Modifier.width(34.dp).height(50.dp)",
    ):
        if marker not in export_controls:
            raise VerificationError(
                f"preview export split button accessibility is missing: {marker}"
            )
    if export_controls.find(".width(48.dp)") > export_controls.find(".width(34.dp)"):
        raise VerificationError("preview export hit target must wrap the compact visual control")

    preview_controls = workspace.split("internal fun PreviewControls(", 1)[-1].split(
        "@Composable", 1
    )[0]
    if ".heightIn(min = 48.dp)" not in preview_controls or ".toggleable(" not in preview_controls:
        raise VerificationError("preview role toggles need a 48 dp minimum touch target")
    for marker in (
        "Modifier.clearAndSetSemantics { }",
        "ProgressBarRangeInfo(",
        "setProgress { requestedValue ->",
        "R.string.first_visible_layer",
        "R.string.last_visible_layer",
        "contentDescription = startLayerLabel",
        "contentDescription = endLayerLabel",
        "stateDescription = startLayerState",
        "stateDescription = endLayerState",
        "contentDescription = toolpathVisibilityLabel",
        "contentDescription = toolpathDepthLabel",
        "stateDescription = toolpathVisibilityState",
        "stateDescription = toolpathDepthState",
    ):
        if marker not in preview_controls:
            raise VerificationError(f"preview slider accessibility is missing: {marker}")
    if preview_controls.count("setProgress { requestedValue ->") != 2:
        raise VerificationError("both layer range thumbs need independent accessibility adjustment")

    transform_slider = workspace.split("private fun TransformSlider(", 1)[-1].split(
        "@Composable", 1
    )[0]
    for marker in (
        "modifier = Modifier.semantics",
        "contentDescription = label",
        "stateDescription = valueText",
    ):
        if marker not in transform_slider:
            raise VerificationError(f"transform slider accessibility is missing: {marker}")

    for marker in (
        "TabletShortestSideDp = 600f",
        "useWorkspaceNavigationRail(maxWidth.value, maxHeight.value)",
        "minOf(widthDp, heightDp) >= TabletShortestSideDp",
        "WorkspaceTopOverlayClearanceDp = 142f",
        "workspacePanelMaxHeightDp(maxHeight.value).dp",
        "BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize())",
        "showWorkspaceNavigationLabels(LocalDensity.current.fontScale)",
        "contentDescription = if (showLabels) null else labelText",
        "alwaysShowLabel = showLabels",
        "workspaceEditingBusy(autoLaying, arranging, slicing, previewLoading)",
    ):
        if marker not in workspace:
            raise VerificationError(f"responsive workspace policy is missing: {marker}")
    workspace_card = workspace.split("private fun WorkspaceCard(", 1)[-1]
    if ".verticalScroll(rememberScrollState())" not in workspace_card:
        raise VerificationError("height-limited workspace cards must remain scrollable")

    app_settings = sources["AppSettingsSheet.kt"]
    if app_settings.count("FlowRow(") < 2:
        raise VerificationError("preview setting chips must wrap at large font scales")
    for marker in (
        "val toolpathVisibilityLabel = stringResource(R.string.toolpath_visibility_control)",
        "val toolpathDepthLabel = stringResource(R.string.toolpath_depth_contrast_control)",
        "val connectionTimeoutLabel = stringResource(R.string.connection_timeout_control)",
        "contentDescription = toolpathVisibilityLabel",
        "contentDescription = toolpathDepthLabel",
        "contentDescription = connectionTimeoutLabel",
        "stateDescription = toolpathVisibilityState",
        "stateDescription = toolpathDepthState",
        "stateDescription = connectionTimeoutState",
        ".toggleable(",
        "role = Role.Switch",
        ".semantics(mergeDescendants = true)",
        "Switch(checked = checked, onCheckedChange = null)",
        "Modifier.semantics { heading() }",
    ):
        if marker not in app_settings:
            raise VerificationError(f"Settings accessibility contract is missing: {marker}")

    layout_tests = sources["WorkspaceLayoutPolicyTest.kt"]
    for marker in (
        "landscapePhoneKeepsBottomNavigation",
        "tabletUsesNavigationRailInBothOrientations",
        "thresholdRequiresTheShortestSideToBeTabletSized",
        "largeFontUsesIconNavigationWithoutClippedVisibleLabels",
        "workspacePanelAlwaysLeavesTheTopOverlayReachable",
        "activeSliceAndInitialPreviewLockModelEditing",
        "prepareDerivedCachesReleaseAndResumeOncePerPressureEpisode",
    ):
        if marker not in layout_tests:
            raise VerificationError(f"responsive workspace regression is missing: {marker}")

    accessibility_test = sources["AccessibilityInstrumentedTest.kt"]
    for marker in (
        "appSettingsExposeNamedSlidersWholeRowSwitchesAndHeadings",
        "largeTextLandscapeKeepsMenuClearOfScrollableWorkspaceSheet",
        "SCREEN_ORIENTATION_LANDSCAPE",
        "menu.isVisibleToUser",
        "!Rect.intersects(menu.screenBounds(), printerProfile.screenBounds())",
        "it.isHeading",
        "menu.isFocusable",
        "printerProfile.isFocusable",
        "modelTransformExposesIndependentAxesAndProportionLock",
        "selectedObjectExposesAccessibleMoveAndScaleGizmoModes",
        "directMoveGizmoDragCommitsOneTransformGesture",
        "scrollAnchorLabel = placement",
        "target?.scrollableAncestor()",
        "retainedScrollBounds",
        "AccessibilityNodeInfo.ACTION_SCROLL_FORWARD",
        "Support brush size must expose an adjustable bounded range",
    ):
        if marker not in accessibility_test:
            raise VerificationError(f"device accessibility regression is missing: {marker}")

    outcome = sources["OnDeviceSlicer.kt"]
    for marker in (
        "val filamentMm: Float",
        ") : Serializable",
        "fun SliceOutcome.isRestorableFrom(filesRoot: File)",
        "canonicalOutput.parentFile == outputRoot",
        "canonicalOutput.length() in 1..SliceArtifactStore.MAXIMUM_OUTPUT_BYTES",
    ):
        if marker not in outcome:
            raise VerificationError(f"restorable slice outcome contract is missing: {marker}")
    for marker in (
        'listOf("organic", "tree_slim", "tree_strong", "tree_hybrid")',
        'listOf("grid", "snug")',
        'val fallback = if (supportType.isTreeSupportType()) "organic" else "grid"',
    ):
        if marker not in outcome:
            raise VerificationError(f"canonical support style contract is missing: {marker}")
    for marker in (
        "native.wipeTowerX = multiMaterial.primeTowerPositionX",
        "native.wipeTowerY = multiMaterial.primeTowerPositionY",
        "native.primeTowerBrimChamfer = multiMaterial.primeTowerBrimChamfer",
        "native.primeTowerBrimChamferMaxWidth = multiMaterial.primeTowerBrimChamferMaxWidth",
        "native.flushMultiplierOverrideEnabled = multiMaterial.flushMultiplierOverrideEnabled",
        "native.flushMultiplier = multiMaterial.flushMultiplier",
    ):
        if marker not in outcome:
            raise VerificationError(f"prime tower position mapping is missing: {marker}")

    main_activity = sources["MainActivity.kt"]
    for marker in (
        "var plateSliceResults by rememberSaveable",
        "var selectedTab by rememberSaveable",
        "restored.isRestorableFrom(context.filesDir)",
        "completed?.isRestorableFrom(context.filesDir) == true",
        "val requested = plateSliceResults.resultFor(selectedPlateId)",
        "requested.plateId",
        "requested.outcome",
        "loadPreviewRange(0, Int.MAX_VALUE)",
    ):
        if marker not in main_activity:
            raise VerificationError(f"configuration restoration is missing: {marker}")

    restoration_tests = sources["SliceOutcomeRestorationTest.kt"]
    for marker in (
        "retainedPrivateOutputCanBeRestoredAfterConfigurationChange",
        "missingOrOutsideOutputCannotBeRestored",
        "invalidStatisticsCannotReenterPreviewState",
    ):
        if marker not in restoration_tests:
            raise VerificationError(f"slice restoration host regression is missing: {marker}")
    service = sources["SlicerProcessService.kt"]
    for marker in (
        "result.estimatedFilamentMm",
        "KEY_FILAMENT_MM",
        "filamentMm = response.getFloat(SlicerProcessContract.KEY_FILAMENT_MM)",
        "putFloat(SlicerProcessContract.KEY_FILAMENT_MM, outcome.filamentMm)",
    ):
        if marker not in service:
            raise VerificationError(f"isolated slice result drops filament length: {marker}")

    summary = sources["PreviewSummary.kt"]
    for marker in (
        "fun SliceOutcome.previewSummary()",
        "estimatedSeconds / SECONDS_PER_MINUTE",
        "filamentMm / MILLIMETERS_PER_METER",
        "Invalid preview filament mass",
    ):
        if marker not in summary:
            raise VerificationError(f"preview summary derivation is missing: {marker}")
    summary_test = sources["PreviewSummaryTest.kt"]
    for marker in (
        "sliceResultKeepsTimeMassAndLengthWithoutReadingGcode",
        "subMinuteEstimateUsesCompactFallback",
        "invalidNativeStatisticsAreRejectedBeforeDisplay",
    ):
        if marker not in summary_test:
            raise VerificationError(f"preview summary regression is missing: {marker}")
    for source_name in ("strings.xml", "strings-ko.xml"):
        strings = sources[source_name]
        for resource in (
            'name="estimated_print_time"',
            'name="filament_usage_compact"',
            'name="expand_preview_controls"',
            'name="collapse_preview_controls"',
            'name="first_visible_layer"',
            'name="last_visible_layer"',
            'name="toolpath_visibility_control"',
            'name="toolpath_depth_contrast_control"',
            'name="connection_timeout_control"',
        ):
            if resource not in strings:
                raise VerificationError(
                    f"localized preview summary is missing from {source_name}: {resource}"
                )

    rust = sources["lib.rs"]
    for marker in (
        "NativeEngine_previewGcodeRangeInto",
        "output: JByteBuffer",
        "PREVIEW_PAYLOAD_MAGIC",
        "PREVIEW_PAYLOAD_VERSION: f32 = 3.0",
        "PREVIEW_HEADER_FLOATS: usize = 9 + ToolpathRole::COUNT",
        "PREVIEW_PATH_FLOATS: usize = 1",
        "paths: Vec<PreviewPathRange>",
        "role_segment_counts[path.role as usize]",
        "MAX_PREVIEW_SEGMENTS: usize = 120_000",
        "MAX_PREVIEW_LAYERS: usize = 1_000_000",
        "get_direct_buffer_capacity(&output)",
        "get_direct_buffer_address(&output)",
        "write_preview_payload(preview, output_floats)",
        "G-code preview direct buffer is too small",
    ):
        if marker not in rust:
            raise VerificationError(f"Rust primitive preview contract is missing: {marker}")
    export = rust.split(
        "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRangeInto", 1
    )[-1].split("#[cfg(test)]", 1)[0]
    if "guarded_json(" in export or "serde_json::to_string" in export:
        raise VerificationError("Rust G-code preview reverted to JSON serialization")

    if (
        "internal fun loadGcodePreview(" not in native
        or "GcodeLayerPreview.fromTrustedNative(" not in native
        or "loadGcodePreview(" not in sources["SliceOperationViewModel.kt"]
    ):
        raise VerificationError(
            "retained application Preview does not use the trusted primitive payload"
        )
    device = sources["NativeEngineInstrumentedTest.kt"]
    for marker in (
        "ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)",
        "NativeEngine.previewGcodeRangeInto(",
        "gcodeResult < 0",
        "GcodeLayerPreview.fromTrustedNative(previewPayload, usedFloats)",
    ):
        if marker not in device:
            raise VerificationError(f"ARM64 direct preview regression is missing: {marker}")
    for marker in (
        "depthPreviewPrewarmsGestureVboAndReusesItAcrossCameraFrames",
        "The first frame must upload one coherent low-cost geometry set",
        "The next idle frame must upload the requested detail geometry set",
        "Camera-only frames must reuse the uploaded GPU buffers",
        "A geometry change must replace the GPU buffers exactly once",
        "Old-scene GPU buffers must be released before the new gesture tier is prewarmed",
        "automaticPreviewQualityResolvesToAConcreteDeviceTier",
        "Starting a gesture must reuse the prewarmed lower-detail geometry",
        "Every subsequent gesture frame must reuse the lower-detail geometry",
        "Settling after a gesture must reuse the requested geometry",
        "The GPU cache must remain bounded to two geometry sets",
        "UI memory pressure must release every reconstructable preview buffer",
        "The first frame after memory pressure must rebuild the low-cost geometry once",
        "Instanced toolpath must change the rendered framebuffer",
        "ARM64 GPU bed staging must use direct memory",
        "ARM64 GPU instance staging must use direct memory",
        "ARM64 compact preview instances must stay below four MiB",
        "Slice outcome must retain Orca's print-time estimate",
        "Slice outcome must retain Orca's filament-length estimate",
        "Slice outcome must retain Orca's filament-mass estimate",
        "A failed depth renderer must request compatibility fallback exactly once",
        "A trivial Preview workload must promote Automatic through measured tiers",
        "Automatic calibration must settle after bounded completed-frame samples",
        "The last compatible GPU frame must remain visible during refinement",
        "Background refinement must not clear the visible Preview",
        "A geometry result started before memory pressure must not repopulate CPU buffers",
    ):
        if marker not in device:
            raise VerificationError(f"ARM64 GPU preview regression is missing: {marker}")
    for marker in (
        "paintedEnforcersAndBlockersControlRealManualSupportModes",
        "automaticBlocked.roleSegmentCounts[5] == 0",
        "; support_type = normal(manual)",
        "; support_type = tree(manual)",
        "everyTreeSupportStyleProducesDistinctPhysicalSupportGeometry",
        "signatures.values.toSet().size",
        "supportExtrusionMotion",
    ):
        if marker not in device:
            raise VerificationError(f"physical support regression is missing: {marker}")

    host_tests = sources["PreviewModelsTest.kt"]
    for marker in (
        "nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson",
        "nativePayloadRejectsNullTruncatedOrUnknownFormats",
        "nativePayloadRejectsNonFiniteCoordinatesAndInvalidRoles",
        "memoryPressureDropsOnlyRebuildablePreviewCaches",
        "nativePreviewPoolRejectsLeasesReleasedAfterATrim",
    ):
        if marker not in host_tests:
            raise VerificationError(f"preview payload host regression is missing: {marker}")
    mesh_tests = sources["ToolpathMeshBuilderTest.kt"]
    for marker in (
        "balancedModeCapsDensePreviewGeometry",
        "GPU bed staging must use direct native memory",
        "GPU instance staging must use direct native memory",
        "maximum 120,000-segment instance payload must stay below 4 MiB",
        "unchangedSceneUploadsOnceUntilGeometryOrContextChanges",
        "sceneCacheUsesImmutablePreviewIdentityWithoutHashingItsCoordinates",
        "twoSlotGeometryCacheEvictsTheLeastRecentlyUsedDetail",
        "Camera-only frames must reuse the GPU buffer",
        "The least recently used gesture VBO must be evicted",
        "Context recreation must re-upload retained scene data",
        "gpuPreviewMemoryIsReleasedOnlyAfterTheUiBecomesHidden",
        "nearOpaquePreviewUploadsHighLayersFirstForEarlyDepthRejection",
        "Near-opaque paths must start at the high layer",
        "Translucent paths must retain source order",
        "pendingLodCanReuseOnlyGeometryFromTheSameVisualScene",
    ):
        if marker not in mesh_tests:
            raise VerificationError(f"GPU preview performance regression is missing: {marker}")
    policy_tests = sources["PreviewPerformancePolicyTest.kt"]
    for marker in (
        "automaticDefaultsToMeasuredPerformanceTier",
        "automaticDoesNotMistakeRamCapacityForGpuHeadroom",
        "explicitQualityAlwaysWinsOverAutomaticDeviceSelection",
        "gesturesTemporarilyUseOneLowerGeometryTier",
        "segmentBudgetsStayBoundedForBothRenderers",
        "depthRendererFailureFallsBackWithoutOverwritingTheUserPreference",
        "automaticPromotesOnlyAfterFiveCompletedResponsiveFramesPerTier",
        "slowCandidateFallsBackToLastProvenTierWithoutOscillation",
        "automaticCalibrationResetsForAChangedPreviewWorkload",
        "explicitQualityNeverRunsAutomaticCalibration",
        "denseOverviewUsesScreenSpaceBudgetAndRestoresFullDetailWhenZoomed",
        "adaptiveSurfaceResolutionPreservesLogicalCoverageWhileScalingRasterWork",
        "prepareInteractionScalesOnlyTheTransientRenderBuffer",
    ):
        if marker not in policy_tests:
            raise VerificationError(f"adaptive preview host regression is missing: {marker}")

    toolpath_surface = sources["ToolpathPreviewView.kt"]
    for marker in (
        "previewSurfaceSize(width, height, detail)",
        "holder.setFixedSize(target.width, target.height)",
        "holder.setSizeFromLayout()",
        "reportEffectiveDetail(sourceScene.detail)",
        "viewportWidth = logicalViewportWidth",
        "viewportHeight = logicalViewportHeight",
    ):
        if marker not in toolpath_surface:
            raise VerificationError(f"adaptive Preview surface scaling is missing: {marker}")

    for document in ("CONTRIBUTING.md",):
        lowered = sources[document].lower()
        if (
            "preview" not in lowered
            or "floatarray" not in lowered
            or "direct `bytebuffer`" not in lowered
            or "vbo" not in lowered
            or "automatic" not in lowered
            or "instanced" not in lowered
            or "32-byte" not in lowered
            or "fallback" not in lowered
            or "large-model inspection" not in lowered
            or "source-facet" not in lowered
        ):
            raise VerificationError(f"primitive preview boundary is not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    debug = ROOT / "android/app/src/debug/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    device = ROOT / "android/app/src/androidTest/java/com/ashcastle/duckyslicer"
    return {
        "NativeEngine.kt": (main / "NativeEngine.kt").read_text(encoding="utf-8"),
        "PreviewModels.kt": (main / "PreviewModels.kt").read_text(encoding="utf-8"),
        "PreviewSummary.kt": (main / "PreviewSummary.kt").read_text(encoding="utf-8"),
        "AppSettings.kt": (main / "AppSettings.kt").read_text(encoding="utf-8"),
        "AppSettingsSheet.kt": (main / "AppSettingsSheet.kt").read_text(
            encoding="utf-8"
        ),
        "ToolpathPreviewView.kt": (main / "ToolpathPreviewView.kt").read_text(
            encoding="utf-8"
        ),
        "PrepareModelPreviewView.kt": (main / "PrepareModelPreviewView.kt").read_text(
            encoding="utf-8"
        ),
        "PrepareModelOverlays.kt": (main / "PrepareModelOverlays.kt").read_text(
            encoding="utf-8"
        ),
        "OrcaFacetPreview.kt": (main / "OrcaFacetPreview.kt").read_text(
            encoding="utf-8"
        ),
        "OrcaFacetEditing.kt": (main / "OrcaFacetEditing.kt").read_text(
            encoding="utf-8"
        ),
        "PrepareModelPicking.kt": (main / "PrepareModelPicking.kt").read_text(
            encoding="utf-8"
        ),
        "ModelPreparationScheduler.kt": (main / "ModelPreparationScheduler.kt").read_text(
            encoding="utf-8"
        ),
        "ModelTransform.kt": (main / "ModelTransform.kt").read_text(encoding="utf-8"),
        "TransformGizmo.kt": (main / "TransformGizmo.kt").read_text(encoding="utf-8"),
        "LayOnFaceCandidates.kt": (main / "LayOnFaceCandidates.kt").read_text(
            encoding="utf-8"
        ),
        "PreviewPerformanceHarnessActivity.kt": (
            debug / "PreviewPerformanceHarnessActivity.kt"
        ).read_text(encoding="utf-8"),
        "WorkspaceScreen.kt": (main / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
        "ProjectState.kt": (main / "ProjectState.kt").read_text(encoding="utf-8"),
        "ProjectStore.kt": (main / "ProjectStore.kt").read_text(encoding="utf-8"),
        "OrcaModelCut.kt": (main / "OrcaModelCut.kt").read_text(encoding="utf-8"),
        "SliceOperationViewModel.kt": (main / "SliceOperationViewModel.kt").read_text(
            encoding="utf-8"
        ),
        "OnDeviceSlicer.kt": (main / "OnDeviceSlicer.kt").read_text(encoding="utf-8"),
        "SlicerProcessService.kt": (main / "SlicerProcessService.kt").read_text(
            encoding="utf-8"
        ),
        "NativeEngineInstrumentedTest.kt": (
            device / "NativeEngineInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "OrcaModelImportInstrumentedTest.kt": (
            device / "OrcaModelImportInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "OrcaMultiColorPaintInstrumentedTest.kt": (
            device / "OrcaMultiColorPaintInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "OrcaSeamPaintInstrumentedTest.kt": (
            device / "OrcaSeamPaintInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "PrepareModelRendererInstrumentedTest.kt": (
            device / "PrepareModelRendererInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "PrepareModelPickingTest.kt": (tests / "PrepareModelPickingTest.kt").read_text(
            encoding="utf-8"
        ),
        "ModelInfoTest.kt": (tests / "ModelInfoTest.kt").read_text(encoding="utf-8"),
        "TransformGizmoTest.kt": (tests / "TransformGizmoTest.kt").read_text(
            encoding="utf-8"
        ),
        "LayOnFaceCandidatesTest.kt": (tests / "LayOnFaceCandidatesTest.kt").read_text(
            encoding="utf-8"
        ),
        "ModelImportPerformanceInstrumentedTest.kt": (
            device / "ModelImportPerformanceInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ToolpathRendererPerformanceInstrumentedTest.kt": (
            device / "ToolpathRendererPerformanceInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ToolpathSurfaceInstrumentedTest.kt": (
            device / "ToolpathSurfaceInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "ToolpathNativePackingInstrumentedTest.kt": (
            device / "ToolpathNativePackingInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "AccessibilityInstrumentedTest.kt": (
            device / "AccessibilityInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "PreviewModelsTest.kt": (tests / "PreviewModelsTest.kt").read_text(
            encoding="utf-8"
        ),
        "PreviewSummaryTest.kt": (tests / "PreviewSummaryTest.kt").read_text(
            encoding="utf-8"
        ),
        "SliceOutcomeRestorationTest.kt": (
            tests / "SliceOutcomeRestorationTest.kt"
        ).read_text(encoding="utf-8"),
        "PreviewPerformancePolicyTest.kt": (
            tests / "PreviewPerformancePolicyTest.kt"
        ).read_text(encoding="utf-8"),
        "ToolpathMeshBuilderTest.kt": (tests / "ToolpathMeshBuilderTest.kt").read_text(
            encoding="utf-8"
        ),
        "WorkspaceLayoutPolicyTest.kt": (tests / "WorkspaceLayoutPolicyTest.kt").read_text(
            encoding="utf-8"
        ),
        "OrcaFacetEditingTest.kt": (tests / "OrcaFacetEditingTest.kt").read_text(
            encoding="utf-8"
        ),
        "ProjectStateTest.kt": (tests / "ProjectStateTest.kt").read_text(
            encoding="utf-8"
        ),
        "SupportPaintHitTest.kt": (tests / "SupportPaintHitTest.kt").read_text(
            encoding="utf-8"
        ),
        "lib.rs": (ROOT / "rust/duckyslicer-jni/src/lib.rs").read_text(encoding="utf-8"),
        "strings.xml": (ROOT / "android/app/src/main/res/values/strings.xml").read_text(
            encoding="utf-8"
        ),
        "strings-ko.xml": (
            ROOT / "android/app/src/main/res/values-ko/strings.xml"
        ).read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_preview_boundary(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Preview boundary verification failed: {error}") from error
    print(
        "Verified bounded FloatArray model and direct-buffer toolpath previews, responsive controls, adaptive detail, "
        "compact ribbon/line toolpaths, bounded GPU caching, and automatic compatibility fallback"
    )


if __name__ == "__main__":
    main()

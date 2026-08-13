from __future__ import annotations

import unittest

from tools.verify_preview_boundary import VerificationError, verify_preview_boundary


def valid_sources() -> dict[str, str]:
    return {
        "NativeEngine.kt": (
            "external fun previewGcodeRangeInto( output: ByteBuffer "
            "inspectStlPayload(path: String): FloatArray? external fun packToolpathGeometry( "
            "output: ByteBuffer internal fun loadGcodePreview( "
            "GcodeLayerPreview.fromTrustedNative( NativePreviewBufferPool.acquire() "
            "NativePreviewBufferPool.release(payload) MAX_RETAINED_BUFFERS = 2"
        ),
        "PreviewModels.kt": (
            "fun fromNative(raw: FloatArray?) PAYLOAD_MAGIC PAYLOAD_VERSION "
            "HEADER_FLOATS = 9 + ROLE_COUNT PATH_STRIDE = 1 "
            "MAX_SEGMENTS = 120_000 preview_coordinate_invalid "
            "MAX_PAYLOAD_FLOATS preview_role_invalid fun fromTrustedNative(raw: FloatArray?) "
            "raw: ByteBuffer? usedFloats: Int "
            "raw.isDirect && raw.order() == ByteOrder.nativeOrder() MAX_PAYLOAD_BYTES "
            "validateCoordinates = true validateCoordinates = false cachedPathIndex "
            "PrimitivePathBuilder(totalSegments) preview.cachedPathIndex = pathIndex "
            "RolePathIndex( val pathOrdinals: IntArray "
            "selectedPaths = BooleanArray(allPaths.pathCount) "
            "selectedPathCounts = IntArray(ROLE_COUNT) "
            "internal val pathStarts: IntArray internal val pathEndsExclusive: IntArray "
            "internal val segmentCount: Int val segmentOffsets: IntArray by lazy "
            "selectedPathCount = selectedPathCounts.sum()"
        ),
        "PreviewSummary.kt": (
            "fun SliceOutcome.previewSummary() estimatedSeconds / SECONDS_PER_MINUTE "
            "filamentMm / MILLIMETERS_PER_METER Invalid preview filament mass"
        ),
        "AppSettings.kt": (
            "PreviewDetail.AUTOMATIC val previewDetail: PreviewDetail = PreviewDetail.AUTOMATIC "
            "PreviewDeviceCapabilities manager?.isLowRamDevice "
            "PreviewDetail.AUTOMATIC -> PreviewDetail.PERFORMANCE "
            "resolvePreviewDetail( previewDetailForInteraction( depthPreviewSegmentBudget( "
            "shouldDrawToolpathLines( shouldUseDenseOverviewLines( "
            "depthPreviewOverviewSegmentBudget( "
            "DENSE_PREVIEW_OVERVIEW_SEGMENTS = 40_000 DENSE_PREVIEW_RIBBON_ZOOM = 1.5f "
            "DENSE_PREVIEW_MIN_SEGMENTS = 10_000 DENSE_PREVIEW_MAX_SEGMENTS = 32_000 "
            "compatibilityPreviewSegmentBudget( AdaptivePreviewDetailController( "
            "ADAPTIVE_PREVIEW_FAST_FRAME_MS = 48.0 "
            "ADAPTIVE_PREVIEW_FAST_SAMPLE_COUNT = 2 recordCompletedFrame( "
            "currentDetail = lastProvenDetail"
        ),
        "AppSettingsSheet.kt": (
            "FlowRow( PreviewRenderingMode.entries.forEach "
            "FlowRow( PreviewDetail.entries.forEach "
            "val toolpathVisibilityLabel = stringResource(R.string.toolpath_visibility_control) "
            "val toolpathDepthLabel = stringResource(R.string.toolpath_depth_contrast_control) "
            "val connectionTimeoutLabel = stringResource(R.string.connection_timeout_control) "
            "contentDescription = toolpathVisibilityLabel "
            "contentDescription = toolpathDepthLabel contentDescription = connectionTimeoutLabel "
            "stateDescription = toolpathVisibilityState stateDescription = toolpathDepthState "
            "stateDescription = connectionTimeoutState .toggleable( role = Role.Switch "
            ".semantics(mergeDescendants = true) "
            "Switch(checked = checked, onCheckedChange = null) Modifier.semantics { heading() }"
        ),
        "ToolpathPreviewView.kt": (
            "renderMode = RENDERMODE_WHEN_DIRTY ToolpathGeometryUploadState "
            "System.identityHashCode(preview) "
            "uploadState.needsUpload(scene) "
            "ToolpathGeometryUploadState(capacity = GPU_GEOMETRY_CACHE_SIZE) "
            "const val GPU_GEOMETRY_CACHE_SIZE = 2 "
            "pendingPrewarmScene requestPrewarmFrame() uploadState.markUsed(scene) "
            "releaseStaleGeometry(buildSet candidate.canReuseGeometryWhileBuilding(scene) "
            "fallbackFrameCount += 1 "
            "uploadState.remove(staleScene) "
            "GLES30.glGenBuffers GLES30.glDeleteBuffers "
            "GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER GLES30.glBufferData( "
            "GLES30.GL_STATIC_DRAW GLES30.glDrawArraysInstanced( "
            "private fun drawToolpathLines( GLES30.glDrawArrays(GLES30.GL_LINES "
            "renderAsLines = true val lineVertices = when { "
            "lineBuilder?.finish() ?: ByteBuffer.allocateDirect(0) "
            "GLES30.GL_TRIANGLE_STRIP const val TOOLPATH_VERTICES_PER_INSTANCE = 4 "
            "GLES30.glVertexAttribDivisor( GLES30.GL_UNSIGNED_BYTE "
            "geometryUploadCountForTest cachedGeometryCountForTest "
            "ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN "
            "registerComponentCallbacks(memoryCallbacks) "
            "unregisterComponentCallbacks(memoryCallbacks) "
            "queueEvent { toolpathRenderer.releaseGpuGeometryForMemoryPressure() } "
            "releaseGpuGeometryForMemoryPressure() "
            "ToolpathUploadPayload INSTANCE_STRIDE_BYTES = 32 "
            "INSTANCE_START_OFFSET_BYTES INSTANCE_COLOR_OFFSET_BYTES "
            "val toolpathInstances = when { "
            "instanceBuilder?.finish() ?: ByteBuffer.allocateDirect(0) "
            "EARLY_Z_OPACITY_THRESHOLD = 0.85f "
            "val reverseForEarlyZ = scene.opacity >= EARLY_Z_OPACITY_THRESHOLD "
            "plan.pathStarts[pathIndex] plan.pathEndsExclusive[pathIndex] "
            ".allocateDirect(capacity * Float.SIZE_BYTES) "
            "ByteBuffer.allocateDirect(usedFloats * Float.SIZE_BYTES) "
            ".also { buffer -> buffer.asFloatBuffer().put(values, 0, usedFloats) } "
            "TOOLPATH_INSTANCE_FLOATS Float.fromBits( "
            "setInteractionActive(true) postDelayed(restoreDetail, DETAIL_RESTORE_DELAY_MS) "
            "previewDetailForInteraction(sourceScene.detail, interactionActive = true) "
            "depthPreviewSegmentBudget(scene.detail) "
            "val overview = shouldUseDenseOverviewLines(sourceSegmentCount, zoom) "
            "val overviewBudget = depthPreviewOverviewSegmentBudget( "
            "shouldDrawToolpathLines( overview, "
            "adaptivePreviewController.shouldMeasure( "
            "GLES30.glFinish() glOperationSucceeded(\"adaptive_gpu_completion\") "
            "adaptivePreviewController.recordCompletedFrame( "
            "reportFrameReady reportRendererStarting reportUnavailable "
            "override fun surfaceDestroyed(holder: SurfaceHolder) "
            "RENDERER_STARTUP_TIMEOUT_MS = 5_000L "
            "GLES30.glGetError() failRenderer(\"program_creation\") "
            "NativeToolpathPacker.pack(scene, plan, reverseForEarlyZ) "
            "val maximumBytes = plan.segmentCount * PACKED_TOOLPATH_FLOATS * Float.SIZE_BYTES "
            "ByteBuffer.allocateDirect(maximumBytes).order(ByteOrder.nativeOrder()) "
            "if (segmentCount !in 0..plan.segmentCount) return null "
            "output.limit(usedBytes) "
            "nativePackingUsed = nativePacked != null"
        ),
        "PrepareModelPreviewView.kt": (
            "PrepareModelTopologyKey( filamentSlot = volume.filamentSlot "
            "withContext(Dispatchers.Default) PrepareModelSceneBuilder.build(projectObjects "
            "PrepareModelSceneBuilder.build(\n                    emptyList() "
            "overlays.takeIf { sceneLoad.complete }.orEmpty() PrepareModelOverlayKey( "
            "overlays = withContext(Dispatchers.Default)"
        ),
        "PrepareModelPicking.kt": (
            "buildPreparePickingIndices( PreparePickingIndexBuilder( "
            "PREPARE_PICKING_TRIANGLES_PER_LEAF = 48 intersectsProjectedBounds( "
            ".candidateTriangles( return result.copyOf(output) "
            "candidateCount = candidates?.size ?: triangleCount "
            "candidates?.get(candidatePosition) ?: candidatePosition "
            "PREPARE_PICKING_CANCELLATION_INTERVAL = 256 checkCancellation()"
        ),
        "ModelPreparationScheduler.kt": (
            "Dispatchers.Default.limitedParallelism(1) "
            "Process.THREAD_PRIORITY_BACKGROUND withModelPreparationContext( "
            "Process.setThreadPriority(threadId, previousPriority)"
        ),
        "ModelTransform.kt": (
            "internal fun ModelTransform.minimumRotatedZ(projectObject: ProjectObject) "
            "MinimumRotatedZCalculator(this) "
            "val afterXz = scaledY * sinX + scaledZ * cosX "
            "result = minOf(result, -scaledX * sinY + afterXz * cosY) "
            "minimumZWithoutTilt(geometry, centerZ) "
            "internal fun ModelTransform.minimumRotatedZ(model: ModelInfo) "
            "internal fun ModelTransform.placeVertex("
        ),
        "PreviewPerformanceHarnessActivity.kt": (
            "detail = PreviewDetail.AUTOMATIC "
            "renderer.automaticCalibrationSettledForTest() "
            "automaticDetail = checkNotNull(renderer.effectiveDetailForTest()) "
            "MAXIMUM_AUTOMATIC_CALIBRATION_FRAMES = 12"
        ),
        "WorkspaceScreen.kt": (
            "previewDeviceCapabilities(context) resolvePreviewDetail(previewDetail, previewCapabilities) "
            "detail = previewDetail "
            "compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = false) "
            "compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = true) "
            "if (selectedTab == WorkspaceTab.PREVIEW) PreviewExportSplitButton( "
            "Icons.Default.ArrowDropDown Icons.Default.SaveAlt onSend = onRemoteUpload "
            "var previewControlsExpanded by rememberSaveable PreviewSummaryHeader( "
            "Icons.Default.ExpandLess Icons.Default.ExpandMore "
            "summary.filamentGrams summary.filamentMeters"
            " shouldUseDepthTestedPreview( depthPreviewRuntimeAvailable "
            "onUnavailable = { depthPreviewRuntimeAvailable = false }"
            " placements = modelPlacements currentModelPlacements[activeObject.id] "
            "val placement = checkNotNull(modelPlacements[projectObject.id])"
            " LaunchedEffect(modelTopology, interactionActive, layOnFaceObjectId)"
            " interactionActive || layOnFaceObjectId != null"
            " delay(PREPARE_PICKING_PREWARM_DELAY_MS)"
            " modelPickingIndices = withModelPreparationContext"
            " checkCancellation = { ensureActive() }"
            " pickingIndices = currentModelPickingIndices"
            " pickingIndices = currentModelPickingIndices"
            " pickingIndices = currentModelPickingIndices"
            " private fun TransformSlider( modifier = Modifier.semantics "
            "contentDescription = label stateDescription = valueText @Composable"
            " private fun PreviewExportSplitButton( .width(48.dp) .height(50.dp) "
            ".clickable( role = Role.Button modifier = Modifier.width(34.dp).height(50.dp) "
            "@Composable internal fun PreviewControls( .heightIn(min = 48.dp) .toggleable( "
            "Modifier.clearAndSetSemantics { } ProgressBarRangeInfo( "
            "setProgress { requestedValue -> setProgress { requestedValue -> "
            "R.string.first_visible_layer R.string.last_visible_layer "
            "contentDescription = startLayerLabel contentDescription = endLayerLabel "
            "stateDescription = startLayerState stateDescription = endLayerState "
            "contentDescription = toolpathVisibilityLabel contentDescription = toolpathDepthLabel "
            "stateDescription = toolpathVisibilityState stateDescription = toolpathDepthState "
            "@Composable private const val TabletShortestSideDp = 600f "
            "useWorkspaceNavigationRail(maxWidth.value, maxHeight.value) "
            "minOf(widthDp, heightDp) >= TabletShortestSideDp "
            "WorkspaceTopOverlayClearanceDp = 142f "
            "workspacePanelMaxHeightDp(maxHeight.value).dp "
            "BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxSize()) "
            "private fun WorkspaceCard( .verticalScroll(rememberScrollState()) @Composable"
            " showWorkspaceNavigationLabels(LocalDensity.current.fontScale) "
            "contentDescription = if (showLabels) null else labelText "
            "alwaysShowLabel = showLabels "
            "workspaceEditingBusy(autoLaying, arranging, slicing, previewLoading)"
        ),
        "MainActivity.kt": (
            "fun fromNative(raw: FloatArray?, localPath: String) "
            "MODEL_PREVIEW_PAYLOAD_MAGIC MODEL_PREVIEW_PAYLOAD_VERSION "
            "MODEL_PREVIEW_HEADER_FLOATS = 10 MODEL_MAX_PREVIEW_TRIANGLES = 12_000 "
            "raw.copyOfRange(vertexStart, vertexEnd) exactModelIntegerOrNull() "
            "MODEL_MAX_COORDINATE_ABS_MM "
            "var plateSliceResults by rememberSaveable var selectedTab by rememberSaveable "
            "restored.isRestorableFrom(context.filesDir) "
            "completed?.isRestorableFrom(context.filesDir) == true "
            "val requested = plateSliceResults.resultFor(selectedPlateId) "
            "requested.plateId requested.outcome "
            "loadPreviewRange(0, Int.MAX_VALUE)"
        ),
        "ProjectStore.kt": "inspectModel(",
        "OrcaModelCut.kt": "inspectModel(",
        "SliceOperationViewModel.kt": "loadGcodePreview(",
        "OnDeviceSlicer.kt": (
            "inspectModel( val filamentMm: Float ) : Serializable "
            "fun SliceOutcome.isRestorableFrom(filesRoot: File) "
            "canonicalOutput.parentFile == outputRoot "
            "canonicalOutput.length() in 1..SliceArtifactStore.MAXIMUM_OUTPUT_BYTES"
        ),
        "SlicerProcessService.kt": (
            "result.estimatedFilamentMm KEY_FILAMENT_MM "
            "filamentMm = response.getFloat(SlicerProcessContract.KEY_FILAMENT_MM) "
            "putFloat(SlicerProcessContract.KEY_FILAMENT_MM, outcome.filamentMm)"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES) "
            "NativeEngine.previewGcodeRangeInto( gcodeResult < 0 "
            "GcodeLayerPreview.fromTrustedNative(previewPayload, usedFloats) "
            "depthPreviewPrewarmsGestureVboAndReusesItAcrossCameraFrames "
            "The first frame must upload one coherent low-cost geometry set "
            "The next idle frame must upload the requested detail geometry set "
            "Camera-only frames must reuse the uploaded GPU buffers "
            "A geometry change must replace the GPU buffers exactly once "
            "Old-scene GPU buffers must be released before the new gesture tier is prewarmed "
            "automaticPreviewQualityResolvesToAConcreteDeviceTier "
            "Starting a gesture must reuse the prewarmed lower-detail geometry "
            "Every subsequent gesture frame must reuse the lower-detail geometry "
            "Settling after a gesture must reuse the requested geometry "
            "The GPU cache must remain bounded to two geometry sets "
            "UI memory pressure must release every reconstructable preview buffer "
            "The first frame after memory pressure must rebuild the low-cost geometry once "
            "Instanced toolpath must change the rendered framebuffer "
            "ARM64 GPU bed staging must use direct memory "
            "ARM64 GPU instance staging must use direct memory "
            "ARM64 compact preview instances must stay below four MiB "
            "Slice outcome must retain Orca's print-time estimate "
            "Slice outcome must retain Orca's filament-length estimate "
            "Slice outcome must retain Orca's filament-mass estimate"
            " A failed depth renderer must request compatibility fallback exactly once"
            " A trivial Preview workload must promote Automatic through measured tiers"
            " Automatic calibration must settle after bounded completed-frame samples"
            " The last compatible GPU frame must remain visible during refinement"
            " Background refinement must not clear the visible Preview"
        ),
        "PrepareModelRendererInstrumentedTest.kt": (
            "densePrepareSceneBuildStaysWithinLoadBudget "
            "denseMinimumRotatedZStaysWithinTransformBudget "
            "densePrepareCameraFramesReuseOneUploadedMesh "
            "densePreparePickingStaysWithinTapBudget "
            "p95Ms <= 50.0 renderer.geometryUploadCountForTest() == 1 "
            "p95Ms <= 16.0 objectP95Ms <= 16.0 facetP95Ms <= 16.0 "
            "denseDefaultPlacementStaysWithinLoadBudget "
            "denseUnpaintedOverlayBuildStaysWithinLoadBudget p95Ms <= 1.0"
        ),
        "PrepareModelPickingTest.kt": (
            "spatialIndexCullsArbitraryFacetOrderWithoutChangingExactHits "
            "candidates.size in 1 until model.triangles assertTrue(index.leafCount > 1) "
            "pickingIndices = indices"
        ),
        "ModelInfoTest.kt": (
            "nativePayloadDecodesBoundedGeometryAndSourceFacetMapping "
            "nativePayloadRejectsMissingOrUnknownEnvelope "
            "nativePayloadRejectsNonFiniteOrInconsistentGeometry "
            "nativePayloadRejectsInvalidSourceTriangleIndices"
        ),
        "ModelImportPerformanceInstrumentedTest.kt": (
            "denseBinaryStlUsesBoundedPrimitiveImportWithinBudget "
            "sourceTriangles=${info.triangles} "
            "native.last() / 1_000_000.0 <= 250.0 "
            "decode.last() / 1_000_000.0 <= 100.0 "
            "(native.last() + decode.last()) / 1_000_000.0 <= 300.0"
        ),
        "ToolpathRendererPerformanceInstrumentedTest.kt": (
            "maximumLayerRangeBuildsResponsiveInteractionGeometry "
            "maximumPreviewCacheLookupNeverRehashesCoordinates "
            "segmentCount = GcodeLayerPreview.MAX_SEGMENTS preview.prepareRenderIndex() "
            "planP95Ms <= 25.0 p50Ms <= 80.0 p95Ms <= 150.0 cacheP95Ms <= 4.0"
        ),
        "ToolpathNativePackingInstrumentedTest.kt": (
            "rustPackingIsByteExactWithTheManagedFallback "
            "ToolpathMeshBuilder.build(scene, useNativePacking = true) "
            "ToolpathMeshBuilder.build(scene, useNativePacking = false) "
            "assertArrayEquals(managed.toolpathInstances.bytes(), native.toolpathInstances.bytes()) "
            "assertArrayEquals(managed.lineVertices.bytes(), native.lineVertices.bytes())"
        ),
        "AccessibilityInstrumentedTest.kt": (
            "appSettingsExposeNamedSlidersWholeRowSwitchesAndHeadings "
            "largeTextLandscapeKeepsMenuClearOfScrollableWorkspaceSheet "
            "SCREEN_ORIENTATION_LANDSCAPE menu.isVisibleToUser "
            "!Rect.intersects(menu.screenBounds(), printerProfile.screenBounds()) "
            "it.isHeading menu.isFocusable printerProfile.isFocusable "
            "modelTransformExposesIndependentAxesAndProportionLock "
            "scrollAnchorLabel = placement target?.scrollableAncestor() retainedScrollBounds "
            "AccessibilityNodeInfo.ACTION_SCROLL_FORWARD"
        ),
        "PreviewModelsTest.kt": (
            "nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson "
            "nativePayloadRejectsNullTruncatedOrUnknownFormats "
            "nativePayloadRejectsNonFiniteCoordinatesAndInvalidRoles"
        ),
        "PreviewSummaryTest.kt": (
            "sliceResultKeepsTimeMassAndLengthWithoutReadingGcode "
            "subMinuteEstimateUsesCompactFallback "
            "invalidNativeStatisticsAreRejectedBeforeDisplay"
        ),
        "SliceOutcomeRestorationTest.kt": (
            "retainedPrivateOutputCanBeRestoredAfterConfigurationChange "
            "missingOrOutsideOutputCannotBeRestored "
            "invalidStatisticsCannotReenterPreviewState"
        ),
        "PreviewPerformancePolicyTest.kt": (
            "automaticDefaultsToMeasuredPerformanceTier "
            "automaticDoesNotMistakeRamCapacityForGpuHeadroom "
            "explicitQualityAlwaysWinsOverAutomaticDeviceSelection "
            "gesturesTemporarilyUseOneLowerGeometryTier "
            "segmentBudgetsStayBoundedForBothRenderers "
            "depthRendererFailureFallsBackWithoutOverwritingTheUserPreference "
            "automaticPromotesOnlyAfterTwoCompletedFastFramesPerTier "
            "slowCandidateFallsBackToLastProvenTierWithoutOscillation "
            "automaticCalibrationResetsForAChangedPreviewWorkload "
            "explicitQualityNeverRunsAutomaticCalibration"
            " denseOverviewUsesScreenSpaceBudgetAndRestoresFullDetailWhenZoomed"
        ),
        "ToolpathMeshBuilderTest.kt": (
            "balancedModeCapsDensePreviewGeometry "
            "GPU bed staging must use direct native memory "
            "GPU instance staging must use direct native memory "
            "maximum 120,000-segment instance payload must stay below 4 MiB "
            "unchangedSceneUploadsOnceUntilGeometryOrContextChanges "
            "sceneCacheUsesImmutablePreviewIdentityWithoutHashingItsCoordinates "
            "twoSlotGeometryCacheEvictsTheLeastRecentlyUsedDetail "
            "Camera-only frames must reuse the GPU buffer "
            "The least recently used gesture VBO must be evicted "
            "Context recreation must re-upload retained scene data"
            " gpuPreviewMemoryIsReleasedOnlyAfterTheUiBecomesHidden "
            "nearOpaquePreviewUploadsHighLayersFirstForEarlyDepthRejection "
            "Near-opaque paths must start at the high layer "
            "Translucent paths must retain source order "
            "pendingLodCanReuseOnlyGeometryFromTheSameVisualScene"
        ),
        "WorkspaceLayoutPolicyTest.kt": (
            "landscapePhoneKeepsBottomNavigation "
            "tabletUsesNavigationRailInBothOrientations "
            "thresholdRequiresTheShortestSideToBeTabletSized "
            "largeFontUsesIconNavigationWithoutClippedVisibleLabels "
            "workspacePanelAlwaysLeavesTheTopOverlayReachable "
            "activeSliceAndInitialPreviewLockModelEditing"
        ),
        "lib.rs": (
            "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRangeInto "
            "output: JByteBuffer "
            "PREVIEW_PAYLOAD_MAGIC PREVIEW_PAYLOAD_VERSION: f32 = 2.0 "
            "PREVIEW_HEADER_FLOATS: usize = 9 + ToolpathRole::COUNT "
            "PREVIEW_PATH_FLOATS: usize = 1 paths: Vec<PreviewPathRange> "
            "role_segment_counts[path.role as usize] "
            "MAX_PREVIEW_SEGMENTS: usize = 120_000 MAX_PREVIEW_LAYERS: usize = 1_000_000 "
            "get_direct_buffer_capacity(&output) get_direct_buffer_address(&output) "
            "write_preview_payload(preview, output_floats) "
            '"G-code preview direct buffer is too small" '
            "MODEL_PREVIEW_PAYLOAD_MAGIC MODEL_PREVIEW_PAYLOAD_VERSION "
            "MODEL_PREVIEW_HEADER_FLOATS fn model_preview_payload( "
            "NativeEngine_inspectStlPayload get_direct_buffer_capacity(&output) "
            "get_direct_buffer_address(&output) std::ptr::copy_nonoverlapping( "
            '"Toolpath direct buffer is too small" '
            "#[cfg(test)]"
        ),
        "strings.xml": (
            'name="estimated_print_time" name="filament_usage_compact" '
            'name="expand_preview_controls" name="collapse_preview_controls" '
            'name="first_visible_layer" name="last_visible_layer" '
            'name="toolpath_visibility_control" name="toolpath_depth_contrast_control" '
            'name="connection_timeout_control"'
        ),
        "strings-ko.xml": (
            'name="estimated_print_time" name="filament_usage_compact" '
            'name="expand_preview_controls" name="collapse_preview_controls" '
            'name="first_visible_layer" name="last_visible_layer" '
            'name="toolpath_visibility_control" name="toolpath_depth_contrast_control" '
            'name="connection_timeout_control"'
        ),
        "CONTRIBUTING.md": (
            "Preview FloatArray direct `ByteBuffer` VBO Automatic instanced 32-byte fallback "
            "Large-model inspection source-facet"
        ),
    }


class VerifyPreviewBoundaryTest(unittest.TestCase):
    def test_accepts_primitive_bounded_preview_contract(self) -> None:
        verify_preview_boundary(valid_sources())

    def test_rejects_unnamed_preview_slider(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "contentDescription = toolpathVisibilityLabel",
            "missingDescription = toolpathVisibilityLabel",
        )
        with self.assertRaisesRegex(VerificationError, "preview slider accessibility"):
            verify_preview_boundary(sources)

    def test_rejects_unnamed_transform_slider(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "stateDescription = valueText", "missingState = valueText"
        )
        with self.assertRaisesRegex(VerificationError, "transform slider accessibility"):
            verify_preview_boundary(sources)

    def test_requires_target_anchored_accessibility_scrolling(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = sources[
            "AccessibilityInstrumentedTest.kt"
        ].replace("target?.scrollableAncestor()", "null")
        with self.assertRaisesRegex(VerificationError, "device accessibility regression"):
            verify_preview_boundary(sources)

    def test_rejects_android_json_decoder(self) -> None:
        sources = valid_sources()
        sources["PreviewModels.kt"] += " JSONObject fun fromJson"
        with self.assertRaisesRegex(VerificationError, "JSON decoding"):
            verify_preview_boundary(sources)

    def test_requires_native_path_metadata_for_bounded_preview_decode(self) -> None:
        sources = valid_sources()
        sources["lib.rs"] = sources["lib.rs"].replace(
            "PREVIEW_PATH_FLOATS: usize = 1", ""
        )
        with self.assertRaisesRegex(VerificationError, "primitive preview contract"):
            verify_preview_boundary(sources)

    def test_requires_the_trusted_native_preview_path_in_production(self) -> None:
        sources = valid_sources()
        sources["SliceOperationViewModel.kt"] = "GcodeLayerPreview.fromNative("
        with self.assertRaisesRegex(VerificationError, "trusted primitive payload"):
            verify_preview_boundary(sources)

    def test_rejects_boxed_dense_path_sorting(self) -> None:
        sources = valid_sources()
        sources["PreviewModels.kt"] += " selected.sortedBy(SegmentPath::start)"
        with self.assertRaisesRegex(VerificationError, "boxed path sorting"):
            verify_preview_boundary(sources)

    def test_rejects_model_import_that_reverts_to_json(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = "ModelInfo.fromJson(NativeEngine.inspectStl(path), path)"
        with self.assertRaisesRegex(VerificationError, "reverted to JSON"):
            verify_preview_boundary(sources)

    def test_rejects_missing_primitive_model_jni_boundary(self) -> None:
        sources = valid_sources()
        sources["NativeEngine.kt"] = sources["NativeEngine.kt"].replace(
            "inspectStlPayload(path: String): FloatArray?", "inspectStl(path: String): String"
        )
        with self.assertRaisesRegex(VerificationError, "model inspection JNI"):
            verify_preview_boundary(sources)

    def test_rejects_model_decode_without_a_frame_budget(self) -> None:
        sources = valid_sources()
        sources["ModelImportPerformanceInstrumentedTest.kt"] = sources[
            "ModelImportPerformanceInstrumentedTest.kt"
        ].replace("decode.last() / 1_000_000.0 <= 100.0", "decode.isNotEmpty()")
        with self.assertRaisesRegex(VerificationError, "model import performance"):
            verify_preview_boundary(sources)

    def test_rejects_rust_json_serialization(self) -> None:
        sources = valid_sources()
        sources["lib.rs"] = sources["lib.rs"].replace(
            "#[cfg(test)]", "guarded_json( #[cfg(test)]"
        )
        with self.assertRaisesRegex(VerificationError, "JSON serialization"):
            verify_preview_boundary(sources)

    def test_rejects_missing_gpu_buffer_upload(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "GLES30.glBufferData(", "upload("
        )
        with self.assertRaisesRegex(VerificationError, "glBufferData"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_mesh_construction_on_the_main_thread(self) -> None:
        sources = valid_sources()
        sources["PrepareModelPreviewView.kt"] = sources["PrepareModelPreviewView.kt"].replace(
            "withContext(Dispatchers.Default)", "run"
        )
        with self.assertRaisesRegex(VerificationError, "Prepare model loading"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_cache_that_ignores_filament_color(self) -> None:
        sources = valid_sources()
        sources["PrepareModelPreviewView.kt"] = sources["PrepareModelPreviewView.kt"].replace(
            "filamentSlot = volume.filamentSlot", "filamentSlot = 0"
        )
        with self.assertRaisesRegex(VerificationError, "Prepare model loading"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_placement_that_allocates_per_vertex(self) -> None:
        sources = valid_sources()
        sources["ModelTransform.kt"] = sources["ModelTransform.kt"].replace(
            "MinimumRotatedZCalculator(this)", "transformLocal(floatArrayOf(0f, 0f, 0f))"
        )
        with self.assertRaisesRegex(VerificationError, "allocation-free|per-vertex"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_renderer_that_recalculates_placement_each_frame(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "val placement = checkNotNull(modelPlacements[projectObject.id])",
            "val placement = recalculatePlacement(projectObject)",
        )
        with self.assertRaisesRegex(VerificationError, "modelPlacements"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_touch_paths_without_the_coarse_index(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "pickingIndices = currentModelPickingIndices", "pickingIndices = emptyMap()", 1
        )
        with self.assertRaisesRegex(VerificationError, "touch paths"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_work_that_competes_at_display_priority(self) -> None:
        sources = valid_sources()
        sources["ModelPreparationScheduler.kt"] = sources[
            "ModelPreparationScheduler.kt"
        ].replace("Process.THREAD_PRIORITY_BACKGROUND", "Process.THREAD_PRIORITY_DEFAULT")
        with self.assertRaisesRegex(VerificationError, "contention-safe"):
            verify_preview_boundary(sources)

    def test_rejects_prepare_index_that_has_no_exact_fallback(self) -> None:
        sources = valid_sources()
        sources["PrepareModelPicking.kt"] = sources["PrepareModelPicking.kt"].replace(
            "candidateCount = candidates?.size ?: triangleCount",
            "candidateCount = candidates?.size ?: 0",
        )
        with self.assertRaisesRegex(VerificationError, "picking acceleration"):
            verify_preview_boundary(sources)

    def test_rejects_missing_instanced_toolpath_draw(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "GLES30.glDrawArraysInstanced(", "drawExpandedToolpaths("
        )
        with self.assertRaisesRegex(VerificationError, "glDrawArraysInstanced"):
            verify_preview_boundary(sources)

    def test_rejects_missing_non_instanced_interaction_line_draw(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "GLES30.glDrawArrays(GLES30.GL_LINES", "drawInstancedInteractionLines("
        )
        with self.assertRaisesRegex(VerificationError, "glDrawArrays"):
            verify_preview_boundary(sources)

    def test_rejects_expanded_per_segment_ribbon_vertices(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] += " plan.segmentOffsets.size * 6 * 8"
        with self.assertRaisesRegex(VerificationError, "expanded per-segment"):
            verify_preview_boundary(sources)

    def test_rejects_materialized_segment_offsets_in_gpu_packing(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] += " plan.segmentOffsets"
        with self.assertRaisesRegex(VerificationError, "materialized per-segment"):
            verify_preview_boundary(sources)

    def test_rejects_unbounded_native_toolpath_output(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "if (segmentCount !in 0..plan.segmentCount) return null", ""
        )
        with self.assertRaisesRegex(VerificationError, "GPU preview upload contract"):
            verify_preview_boundary(sources)

    def test_rejects_native_packing_without_managed_parity(self) -> None:
        sources = valid_sources()
        sources["ToolpathNativePackingInstrumentedTest.kt"] = sources[
            "ToolpathNativePackingInstrumentedTest.kt"
        ].replace("ToolpathMeshBuilder.build(scene, useNativePacking = false)", "native")
        with self.assertRaisesRegex(VerificationError, "native Preview packing regression"):
            verify_preview_boundary(sources)

    def test_rejects_missing_gpu_memory_pressure_release(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "registerComponentCallbacks(memoryCallbacks)", ""
        )
        with self.assertRaisesRegex(VerificationError, "GPU preview upload contract"):
            verify_preview_boundary(sources)

    def test_rejects_duplicated_client_side_vertex_storage(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] += " private var vertices: FloatBuffer? builder.writeTo"
        with self.assertRaisesRegex(VerificationError, "client-side"):
            verify_preview_boundary(sources)

    def test_rejects_missing_automatic_device_policy(self) -> None:
        sources = valid_sources()
        sources["AppSettings.kt"] = sources["AppSettings.kt"].replace(
            "manager?.isLowRamDevice", "false"
        )
        with self.assertRaisesRegex(VerificationError, "isLowRamDevice"):
            verify_preview_boundary(sources)

    def test_rejects_automatic_preview_without_completed_gpu_measurement(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "GLES30.glFinish()", ""
        )
        with self.assertRaisesRegex(VerificationError, "GLES30.glFinish"):
            verify_preview_boundary(sources)

    def test_rejects_automatic_preview_without_slow_tier_fallback(self) -> None:
        sources = valid_sources()
        sources["AppSettings.kt"] = sources["AppSettings.kt"].replace(
            "currentDetail = lastProvenDetail", "settled = true"
        )
        with self.assertRaisesRegex(VerificationError, "lastProvenDetail"):
            verify_preview_boundary(sources)

    def test_rejects_foreground_benchmark_that_does_not_measure_automatic_tier(self) -> None:
        sources = valid_sources()
        sources["PreviewPerformanceHarnessActivity.kt"] = sources[
            "PreviewPerformanceHarnessActivity.kt"
        ].replace("detail = PreviewDetail.AUTOMATIC", "detail = PreviewDetail.PERFORMANCE")
        with self.assertRaisesRegex(VerificationError, "foreground adaptive Preview benchmark"):
            verify_preview_boundary(sources)

    def test_rejects_missing_interaction_lod_transition(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "previewDetailForInteraction(sourceScene.detail, interactionActive = true)",
            "sourceScene.detail",
        )
        with self.assertRaisesRegex(VerificationError, "previewDetailForInteraction"):
            verify_preview_boundary(sources)

    def test_rejects_missing_depth_renderer_failure_callback(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onUnavailable = { depthPreviewRuntimeAvailable = false }", ""
        )
        with self.assertRaisesRegex(VerificationError, "depthPreviewRuntimeAvailable"):
            verify_preview_boundary(sources)

    def test_rejects_missing_depth_renderer_startup_watchdog(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "RENDERER_STARTUP_TIMEOUT_MS = 5_000L", ""
        )
        with self.assertRaisesRegex(VerificationError, "RENDERER_STARTUP_TIMEOUT_MS"):
            verify_preview_boundary(sources)

    def test_rejects_missing_preview_export_split_button(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "PreviewExportSplitButton(", "ModelNameBadge("
        )
        with self.assertRaisesRegex(VerificationError, "PreviewExportSplitButton"):
            verify_preview_boundary(sources)

    def test_rejects_undersized_preview_export_hit_target(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            ".width(48.dp)", ".width(34.dp)", 1
        )
        with self.assertRaisesRegex(VerificationError, r"width\(48\.dp\)"):
            verify_preview_boundary(sources)

    def test_rejects_undersized_toolpath_role_toggle(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            ".heightIn(min = 48.dp)", ".heightIn(min = 32.dp)", 1
        )
        with self.assertRaisesRegex(VerificationError, "48 dp minimum"):
            verify_preview_boundary(sources)

    def test_rejects_width_only_tablet_detection(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "minOf(widthDp, heightDp) >= TabletShortestSideDp",
            "widthDp >= TabletShortestSideDp",
        )
        with self.assertRaisesRegex(VerificationError, "minOf"):
            verify_preview_boundary(sources)

    def test_rejects_unscrollable_height_limited_workspace_card(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            ".verticalScroll(rememberScrollState())", "", 1
        )
        with self.assertRaisesRegex(VerificationError, "scrollable"):
            verify_preview_boundary(sources)

    def test_rejects_workspace_sheet_that_can_cover_top_actions(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "workspacePanelMaxHeightDp(maxHeight.value).dp",
            "maxHeight",
        )
        with self.assertRaisesRegex(VerificationError, "workspacePanelMaxHeightDp"):
            verify_preview_boundary(sources)

    def test_rejects_duplicate_settings_switch_action(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            "Switch(checked = checked, onCheckedChange = null)",
            "Switch(checked = checked, onCheckedChange = onCheckedChange)",
        )
        with self.assertRaisesRegex(VerificationError, "Settings accessibility"):
            verify_preview_boundary(sources)

    def test_rejects_unbounded_preview_vbo_cache(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "const val GPU_GEOMETRY_CACHE_SIZE = 2",
            "const val GPU_GEOMETRY_CACHE_SIZE = 3",
        )
        with self.assertRaisesRegex(VerificationError, "GPU preview upload contract"):
            verify_preview_boundary(sources)

    def test_rejects_six_vertex_toolpath_ribbons(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "GLES30.GL_TRIANGLE_STRIP const val TOOLPATH_VERTICES_PER_INSTANCE = 4",
            "GLES30.GL_TRIANGLES const val TOOLPATH_VERTICES_PER_INSTANCE = 6",
        )
        with self.assertRaisesRegex(VerificationError, "GPU preview upload contract"):
            verify_preview_boundary(sources)

    def test_rejects_loss_of_early_depth_ordering(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "val reverseForEarlyZ = scene.opacity >= EARLY_Z_OPACITY_THRESHOLD",
            "val reverseForEarlyZ = false",
        )
        with self.assertRaisesRegex(VerificationError, "GPU preview upload contract"):
            verify_preview_boundary(sources)

    def test_rejects_single_row_large_text_setting_chips(self) -> None:
        sources = valid_sources()
        sources["AppSettingsSheet.kt"] = sources["AppSettingsSheet.kt"].replace(
            "FlowRow(", "Row(", 1
        )
        with self.assertRaisesRegex(VerificationError, "wrap"):
            verify_preview_boundary(sources)

    def test_rejects_clipped_large_font_navigation_labels(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "alwaysShowLabel = showLabels", "alwaysShowLabel = true"
        )
        with self.assertRaisesRegex(VerificationError, "alwaysShowLabel"):
            verify_preview_boundary(sources)

    def test_rejects_configuration_loss_of_completed_slice(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "var plateSliceResults by rememberSaveable", "var plateSliceResults by remember"
        )
        with self.assertRaisesRegex(VerificationError, "plateSliceResults"):
            verify_preview_boundary(sources)

    def test_rejects_unvalidated_restored_output_path(self) -> None:
        sources = valid_sources()
        sources["OnDeviceSlicer.kt"] = sources["OnDeviceSlicer.kt"].replace(
            "canonicalOutput.parentFile == outputRoot", "canonicalOutput.isFile"
        )
        with self.assertRaisesRegex(VerificationError, "parentFile"):
            verify_preview_boundary(sources)

    def test_rejects_missing_collapsed_preview_summary(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "PreviewSummaryHeader(", "PreviewControls("
        )
        with self.assertRaisesRegex(VerificationError, "PreviewSummaryHeader"):
            verify_preview_boundary(sources)

    def test_rejects_dropped_filament_length(self) -> None:
        sources = valid_sources()
        sources["SlicerProcessService.kt"] = sources["SlicerProcessService.kt"].replace(
            "result.estimatedFilamentMm", "0f"
        )
        with self.assertRaisesRegex(VerificationError, "estimatedFilamentMm"):
            verify_preview_boundary(sources)

    def test_rejects_missing_device_statistic_regression(self) -> None:
        sources = valid_sources()
        sources["NativeEngineInstrumentedTest.kt"] = sources[
            "NativeEngineInstrumentedTest.kt"
        ].replace("Slice outcome must retain Orca's filament-length estimate", "")
        with self.assertRaisesRegex(VerificationError, "filament-length estimate"):
            verify_preview_boundary(sources)


if __name__ == "__main__":
    unittest.main()

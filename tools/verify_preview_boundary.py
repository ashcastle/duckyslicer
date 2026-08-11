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
        "WorkspaceScreen.kt",
        "MainActivity.kt",
        "SliceOperationViewModel.kt",
        "OnDeviceSlicer.kt",
        "SlicerProcessService.kt",
        "NativeEngineInstrumentedTest.kt",
        "AccessibilityInstrumentedTest.kt",
        "PreviewModelsTest.kt",
        "PreviewSummaryTest.kt",
        "SliceOutcomeRestorationTest.kt",
        "PreviewPerformancePolicyTest.kt",
        "ToolpathMeshBuilderTest.kt",
        "WorkspaceLayoutPolicyTest.kt",
        "strings.xml",
        "strings-ko.xml",
        "lib.rs",
        "CONTRIBUTING.md",
    }
    missing = sorted(required - sources.keys())
    if missing:
        raise VerificationError(f"preview boundary sources are missing: {missing}")

    native = sources["NativeEngine.kt"]
    if "previewGcodeRange(path: String, startLayer: Int, endLayer: Int): FloatArray?" not in native:
        raise VerificationError("Android preview JNI does not return a nullable primitive float array")

    preview = sources["PreviewModels.kt"]
    for marker in (
        "fun fromNative(raw: FloatArray?)",
        "PAYLOAD_MAGIC",
        "PAYLOAD_VERSION",
        "HEADER_FLOATS = 7",
        "MAX_SEGMENTS = 120_000",
        "MAX_PAYLOAD_FLOATS",
        "preview_coordinate_invalid",
        "preview_role_invalid",
    ):
        if marker not in preview:
            raise VerificationError(f"Android preview payload validation is missing: {marker}")
    if "JSONObject" in preview or "fun fromJson" in preview:
        raise VerificationError("G-code preview reverted to object-heavy JSON decoding")

    settings = sources["AppSettings.kt"]
    for marker in (
        "PreviewDetail.AUTOMATIC",
        "val previewDetail: PreviewDetail = PreviewDetail.AUTOMATIC",
        "PreviewDeviceCapabilities",
        "manager?.isLowRamDevice",
        "capabilities.appMemoryClassMb <= 192",
        "resolvePreviewDetail(",
        "previewDetailForInteraction(",
        "depthPreviewSegmentBudget(",
        "compatibilityPreviewSegmentBudget(",
    ):
        if marker not in settings:
            raise VerificationError(f"adaptive preview policy is missing: {marker}")

    renderer = sources["ToolpathPreviewView.kt"]
    for marker in (
        "renderMode = RENDERMODE_WHEN_DIRTY",
        "ToolpathGeometryUploadState",
        "uploadState.needsUpload(scene)",
        "ToolpathGeometryUploadState(capacity = GPU_GEOMETRY_CACHE_SIZE)",
        "const val GPU_GEOMETRY_CACHE_SIZE = 2",
        "pendingPrewarmScene",
        "requestPrewarmFrame()",
        "uploadState.markUsed(scene)",
        "releaseStaleGeometry(setOf(sourceScene, interactionScene))",
        "uploadState.remove(staleScene)",
        "GLES30.glGenBuffers",
        "GLES30.glDeleteBuffers",
        "GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER",
        "GLES30.glBufferData(",
        "GLES30.GL_STATIC_DRAW",
        "GLES30.glDrawArraysInstanced(",
        "GLES30.glVertexAttribDivisor(",
        "GLES30.GL_UNSIGNED_BYTE",
        "geometryUploadCountForTest",
        "cachedGeometryCountForTest",
        "ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN",
        "registerComponentCallbacks(memoryCallbacks)",
        "unregisterComponentCallbacks(memoryCallbacks)",
        "queueEvent { toolpathRenderer.releaseGpuGeometryForMemoryPressure() }",
        "releaseGpuGeometryForMemoryPressure()",
        "ToolpathUploadPayload",
        "INSTANCE_STRIDE_BYTES = 32",
        "INSTANCE_START_OFFSET_BYTES",
        "INSTANCE_COLOR_OFFSET_BYTES",
        "toolpathInstances = instanceBuilder.finish()",
        ".allocateDirect(capacity * Float.SIZE_BYTES)",
        ".allocateDirect(capacity)",
        "setInteractionActive(true)",
        "postDelayed(restoreDetail, DETAIL_RESTORE_DELAY_MS)",
        "previewDetailForInteraction(sourceScene.detail, interactionActive)",
        "depthPreviewSegmentBudget(scene.detail)",
        "reportFrameReady",
        "reportRendererStarting",
        "reportUnavailable",
        "override fun surfaceDestroyed(holder: SurfaceHolder)",
        "RENDERER_STARTUP_TIMEOUT_MS = 5_000L",
        "GLES30.glGetError()",
        'failRenderer("program_creation")',
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

    workspace = sources["WorkspaceScreen.kt"]
    for marker in (
        "previewDeviceCapabilities(context)",
        "resolvePreviewDetail(previewDetail, previewCapabilities)",
        "detail = effectivePreviewDetail",
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
    ):
        if marker not in workspace:
            raise VerificationError(f"preview device policy is not connected to the UI: {marker}")

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
        "WorkspaceTopOverlayClearanceDp = 82f",
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
        "scrollAnchorLabel = placement",
        "target?.scrollableAncestor()",
        "retainedScrollBounds",
        "AccessibilityNodeInfo.ACTION_SCROLL_FORWARD",
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

    main_activity = sources["MainActivity.kt"]
    for marker in (
        "var sliceOutcome by rememberSaveable",
        "var selectedTab by rememberSaveable",
        "restored.isRestorableFrom(context.filesDir)",
        "completed?.isRestorableFrom(context.filesDir) == true",
        "sliceOperationModel.loadPreview(completed, startLayer, endLayer)",
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
        "-> jfloatArray",
        "PREVIEW_PAYLOAD_MAGIC",
        "PREVIEW_PAYLOAD_VERSION",
        "PREVIEW_HEADER_FLOATS",
        "MAX_PREVIEW_SEGMENTS: usize = 120_000",
        "MAX_PREVIEW_LAYERS: usize = 1_000_000",
        "env.new_float_array",
        "env.set_float_array_region",
        "preview_payload(preview_gcode(",
    ):
        if marker not in rust:
            raise VerificationError(f"Rust primitive preview contract is missing: {marker}")
    export = rust.split(
        "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRange", 1
    )[-1].split("#[cfg(test)]", 1)[0]
    if "guarded_json(" in export or "serde_json::to_string" in export:
        raise VerificationError("Rust G-code preview reverted to JSON serialization")

    if "GcodeLayerPreview.fromNative" not in sources["SliceOperationViewModel.kt"]:
        raise VerificationError("retained application Preview does not use the primitive payload")
    device = sources["NativeEngineInstrumentedTest.kt"]
    if device.count("GcodeLayerPreview.fromNative") < 3 or "gcodeResult == null" not in device:
        raise VerificationError("ARM64 primitive preview regressions are incomplete")
    for marker in (
        "depthPreviewPrewarmsGestureVboAndReusesItAcrossCameraFrames",
        "The first frame must upload one geometry set",
        "The next idle frame must prewarm one lower-detail geometry set",
        "Camera-only frames must reuse the uploaded GPU buffers",
        "A geometry change must replace the GPU buffers exactly once",
        "Old-scene GPU buffers must be released before the new gesture tier is prewarmed",
        "automaticPreviewQualityResolvesToAConcreteDeviceTier",
        "Starting a gesture must reuse the prewarmed lower-detail geometry",
        "Every subsequent gesture frame must reuse the lower-detail geometry",
        "Settling after a gesture must reuse the requested geometry",
        "The GPU cache must remain bounded to two geometry sets",
        "UI memory pressure must release every reconstructable preview buffer",
        "The first frame after memory pressure must rebuild the requested geometry once",
        "Instanced toolpath must change the rendered framebuffer",
        "ARM64 GPU bed staging must use direct memory",
        "ARM64 GPU instance staging must use direct memory",
        "ARM64 compact preview instances must stay below four MiB",
        "Slice outcome must retain Orca's print-time estimate",
        "Slice outcome must retain Orca's filament-length estimate",
        "Slice outcome must retain Orca's filament-mass estimate",
        "A failed depth renderer must request compatibility fallback exactly once",
    ):
        if marker not in device:
            raise VerificationError(f"ARM64 GPU preview regression is missing: {marker}")

    host_tests = sources["PreviewModelsTest.kt"]
    for marker in (
        "nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson",
        "nativePayloadRejectsNullTruncatedOrUnknownFormats",
        "nativePayloadRejectsNonFiniteCoordinatesAndInvalidRoles",
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
        "twoSlotGeometryCacheEvictsTheLeastRecentlyUsedDetail",
        "Camera-only frames must reuse the GPU buffer",
        "The least recently used gesture VBO must be evicted",
        "Context recreation must re-upload retained scene data",
        "gpuPreviewMemoryIsReleasedOnlyAfterTheUiBecomesHidden",
    ):
        if marker not in mesh_tests:
            raise VerificationError(f"GPU preview performance regression is missing: {marker}")
    policy_tests = sources["PreviewPerformancePolicyTest.kt"]
    for marker in (
        "automaticDefaultsToSmoothOnMemoryConstrainedDevices",
        "automaticUsesBalancedQualityWhenTheDeviceHasHeadroom",
        "explicitQualityAlwaysWinsOverAutomaticDeviceSelection",
        "gesturesTemporarilyUseOneLowerGeometryTier",
        "segmentBudgetsStayBoundedForBothRenderers",
        "depthRendererFailureFallsBackWithoutOverwritingTheUserPreference",
    ):
        if marker not in policy_tests:
            raise VerificationError(f"adaptive preview host regression is missing: {marker}")

    for document in ("CONTRIBUTING.md",):
        lowered = sources[document].lower()
        if (
            "preview" not in lowered
            or "floatarray" not in lowered
            or "vbo" not in lowered
            or "automatic" not in lowered
            or "instanced" not in lowered
            or "32-byte" not in lowered
            or "fallback" not in lowered
        ):
            raise VerificationError(f"primitive preview boundary is not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
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
        "WorkspaceScreen.kt": (main / "WorkspaceScreen.kt").read_text(encoding="utf-8"),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
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
        "Verified bounded FloatArray preview, responsive controls, adaptive detail, "
        "compact instanced toolpaths, bounded GPU caching, and automatic compatibility fallback"
    )


if __name__ == "__main__":
    main()

from __future__ import annotations

import unittest

from tools.verify_preview_boundary import VerificationError, verify_preview_boundary


def valid_sources() -> dict[str, str]:
    return {
        "NativeEngine.kt": (
            "previewGcodeRange(path: String, startLayer: Int, endLayer: Int): FloatArray?"
        ),
        "PreviewModels.kt": (
            "fun fromNative(raw: FloatArray?) PAYLOAD_MAGIC PAYLOAD_VERSION "
            "HEADER_FLOATS = 7 MAX_SEGMENTS = 120_000 preview_coordinate_invalid "
            "MAX_PAYLOAD_FLOATS preview_role_invalid"
        ),
        "PreviewSummary.kt": (
            "fun SliceOutcome.previewSummary() estimatedSeconds / SECONDS_PER_MINUTE "
            "filamentMm / MILLIMETERS_PER_METER Invalid preview filament mass"
        ),
        "AppSettings.kt": (
            "PreviewDetail.AUTOMATIC val previewDetail: PreviewDetail = PreviewDetail.AUTOMATIC "
            "PreviewDeviceCapabilities manager?.isLowRamDevice capabilities.appMemoryClassMb <= 192 "
            "resolvePreviewDetail( previewDetailForInteraction( depthPreviewSegmentBudget( "
            "compatibilityPreviewSegmentBudget("
        ),
        "AppSettingsSheet.kt": (
            "FlowRow( PreviewRenderingMode.entries.forEach "
            "FlowRow( PreviewDetail.entries.forEach"
        ),
        "ToolpathPreviewView.kt": (
            "renderMode = RENDERMODE_WHEN_DIRTY ToolpathGeometryUploadState "
            "uploadState.needsUpload(scene) GLES30.glGenBuffers "
            "GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER GLES30.glBufferData( "
            "GLES30.GL_STATIC_DRAW geometryUploadCountForTest "
            "POSITION_OFFSET_BYTES COLOR_OFFSET_BYTES "
            ".allocateDirect(capacity * Float.SIZE_BYTES) return builder.finish() "
            "setInteractionActive(true) postDelayed(restoreDetail, DETAIL_RESTORE_DELAY_MS) "
            "previewDetailForInteraction(sourceScene.detail, interactionActive) "
            "depthPreviewSegmentBudget(scene.detail)"
        ),
        "WorkspaceScreen.kt": (
            "previewDeviceCapabilities(context) resolvePreviewDetail(previewDetail, previewCapabilities) "
            "detail = effectivePreviewDetail "
            "compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = false) "
            "compatibilityPreviewSegmentBudget(effectivePreviewDetail, refined = true) "
            "if (selectedTab == WorkspaceTab.PREVIEW) PreviewExportSplitButton( "
            "Icons.Default.ArrowDropDown Icons.Default.SaveAlt onSend = onRemoteUpload "
            "var previewControlsExpanded by rememberSaveable PreviewSummaryHeader( "
            "Icons.Default.ExpandLess Icons.Default.ExpandMore "
            "summary.filamentGrams summary.filamentMeters"
            " private fun PreviewExportSplitButton( .width(48.dp) .height(50.dp) "
            ".clickable( role = Role.Button modifier = Modifier.width(34.dp).height(50.dp) "
            "@Composable private fun PreviewControls( .heightIn(min = 48.dp) .toggleable( "
            "@Composable private const val TabletShortestSideDp = 600f "
            "useWorkspaceNavigationRail(maxWidth.value, maxHeight.value) "
            "minOf(widthDp, heightDp) >= TabletShortestSideDp "
            "private fun WorkspaceCard( .verticalScroll(rememberScrollState()) @Composable"
            " showWorkspaceNavigationLabels(LocalDensity.current.fontScale) "
            "contentDescription = if (showLabels) null else labelText "
            "alwaysShowLabel = showLabels "
            "workspaceEditingBusy(autoLaying, arranging, slicing, previewLoading)"
        ),
        "MainActivity.kt": (
            "var sliceOutcome by rememberSaveable var selectedTab by rememberSaveable "
            "restored.isRestorableFrom(context.filesDir) "
            "completed?.isRestorableFrom(context.filesDir) == true "
            "sliceOperationModel.loadPreview(completed, startLayer, endLayer) "
            "loadPreviewRange(0, Int.MAX_VALUE)"
        ),
        "SliceOperationViewModel.kt": "GcodeLayerPreview.fromNative",
        "OnDeviceSlicer.kt": (
            "val filamentMm: Float ) : Serializable "
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
            "GcodeLayerPreview.fromNative GcodeLayerPreview.fromNative "
            "GcodeLayerPreview.fromNative gcodeResult == null "
            "depthPreviewUploadsVboOnceAcrossCameraFrames "
            "The first frame must upload one VBO "
            "Camera-only frames must reuse the uploaded VBO "
            "A geometry change must replace the VBO exactly once "
            "automaticPreviewQualityResolvesToAConcreteDeviceTier "
            "Starting a gesture must upload one lower-detail VBO "
            "Every subsequent gesture frame must reuse the lower-detail VBO "
            "Settling after a gesture must restore the requested VBO once "
            "ARM64 GPU staging must use direct memory "
            "ARM64 balanced preview must honor its geometry budget "
            "Slice outcome must retain Orca's print-time estimate "
            "Slice outcome must retain Orca's filament-length estimate "
            "Slice outcome must retain Orca's filament-mass estimate"
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
            "automaticDefaultsToSmoothOnMemoryConstrainedDevices "
            "automaticUsesBalancedQualityWhenTheDeviceHasHeadroom "
            "explicitQualityAlwaysWinsOverAutomaticDeviceSelection "
            "gesturesTemporarilyUseOneLowerGeometryTier "
            "segmentBudgetsStayBoundedForBothRenderers"
        ),
        "ToolpathMeshBuilderTest.kt": (
            "balancedModeCapsDensePreviewGeometry "
            "GPU staging geometry must use direct native memory "
            "unchangedSceneUploadsOnceUntilGeometryOrContextChanges "
            "Camera-only frames must reuse the GPU buffer "
            "Context recreation must re-upload retained scene data"
        ),
        "WorkspaceLayoutPolicyTest.kt": (
            "landscapePhoneKeepsBottomNavigation "
            "tabletUsesNavigationRailInBothOrientations "
            "thresholdRequiresTheShortestSideToBeTabletSized "
            "largeFontUsesIconNavigationWithoutClippedVisibleLabels "
            "activeSliceAndInitialPreviewLockModelEditing"
        ),
        "lib.rs": (
            "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRange -> jfloatArray "
            "PREVIEW_PAYLOAD_MAGIC PREVIEW_PAYLOAD_VERSION PREVIEW_HEADER_FLOATS "
            "MAX_PREVIEW_SEGMENTS: usize = 120_000 MAX_PREVIEW_LAYERS: usize = 1_000_000 "
            "env.new_float_array env.set_float_array_region preview_payload(preview_gcode( "
            "#[cfg(test)]"
        ),
        "strings.xml": (
            'name="estimated_print_time" name="filament_usage_compact" '
            'name="expand_preview_controls" name="collapse_preview_controls"'
        ),
        "strings-ko.xml": (
            'name="estimated_print_time" name="filament_usage_compact" '
            'name="expand_preview_controls" name="collapse_preview_controls"'
        ),
        "CONTRIBUTING.md": "Preview FloatArray VBO Automatic",
    }


class VerifyPreviewBoundaryTest(unittest.TestCase):
    def test_accepts_primitive_bounded_preview_contract(self) -> None:
        verify_preview_boundary(valid_sources())

    def test_rejects_android_json_decoder(self) -> None:
        sources = valid_sources()
        sources["PreviewModels.kt"] += " JSONObject fun fromJson"
        with self.assertRaisesRegex(VerificationError, "JSON decoding"):
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

    def test_rejects_missing_interaction_lod_transition(self) -> None:
        sources = valid_sources()
        sources["ToolpathPreviewView.kt"] = sources["ToolpathPreviewView.kt"].replace(
            "previewDetailForInteraction(sourceScene.detail, interactionActive)",
            "sourceScene.detail",
        )
        with self.assertRaisesRegex(VerificationError, "previewDetailForInteraction"):
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
            "var sliceOutcome by rememberSaveable", "var sliceOutcome by remember"
        )
        with self.assertRaisesRegex(VerificationError, "sliceOutcome"):
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

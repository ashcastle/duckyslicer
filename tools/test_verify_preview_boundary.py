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
        "MainActivity.kt": "GcodeLayerPreview.fromNative GcodeLayerPreview.fromNative",
        "NativeEngineInstrumentedTest.kt": (
            "GcodeLayerPreview.fromNative GcodeLayerPreview.fromNative "
            "GcodeLayerPreview.fromNative gcodeResult == null"
        ),
        "PreviewModelsTest.kt": (
            "nativePayloadKeepsMetadataSegmentsAndRolesWithoutJson "
            "nativePayloadRejectsNullTruncatedOrUnknownFormats "
            "nativePayloadRejectsNonFiniteCoordinatesAndInvalidRoles"
        ),
        "lib.rs": (
            "Java_com_ashcastle_duckyslicer_NativeEngine_previewGcodeRange -> jfloatArray "
            "PREVIEW_PAYLOAD_MAGIC PREVIEW_PAYLOAD_VERSION PREVIEW_HEADER_FLOATS "
            "MAX_PREVIEW_SEGMENTS: usize = 120_000 MAX_PREVIEW_LAYERS: usize = 1_000_000 "
            "env.new_float_array env.set_float_array_region preview_payload(preview_gcode( "
            "#[cfg(test)]"
        ),
        "README.md": "Preview FloatArray",
        "CONTRIBUTING.md": "Preview FloatArray",
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


if __name__ == "__main__":
    unittest.main()

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
        "ToolpathPreviewView.kt",
        "MainActivity.kt",
        "NativeEngineInstrumentedTest.kt",
        "PreviewModelsTest.kt",
        "ToolpathMeshBuilderTest.kt",
        "lib.rs",
        "README.md",
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

    renderer = sources["ToolpathPreviewView.kt"]
    for marker in (
        "renderMode = RENDERMODE_WHEN_DIRTY",
        "ToolpathGeometryUploadState",
        "uploadState.needsUpload(scene)",
        "GLES30.glGenBuffers",
        "GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER",
        "GLES30.glBufferData(",
        "GLES30.GL_STATIC_DRAW",
        "geometryUploadCountForTest",
        "POSITION_OFFSET_BYTES",
        "COLOR_OFFSET_BYTES",
        ".allocateDirect(capacity * Float.SIZE_BYTES)",
        "return builder.finish()",
    ):
        if marker not in renderer:
            raise VerificationError(f"GPU preview upload contract is missing: {marker}")
    if "private var vertices: FloatBuffer?" in renderer or "builder.writeTo" in renderer:
        raise VerificationError("GPU preview reverted to duplicated client-side vertex storage")

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

    if sources["MainActivity.kt"].count("GcodeLayerPreview.fromNative") < 2:
        raise VerificationError("application preview consumers do not use the primitive payload")
    device = sources["NativeEngineInstrumentedTest.kt"]
    if device.count("GcodeLayerPreview.fromNative") < 3 or "gcodeResult == null" not in device:
        raise VerificationError("ARM64 primitive preview regressions are incomplete")
    for marker in (
        "depthPreviewUploadsVboOnceAcrossCameraFrames",
        "The first frame must upload one VBO",
        "Camera-only frames must reuse the uploaded VBO",
        "A geometry change must replace the VBO exactly once",
        "ARM64 GPU staging must use direct memory",
        "ARM64 balanced preview must honor its geometry budget",
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
        "GPU staging geometry must use direct native memory",
        "unchangedSceneUploadsOnceUntilGeometryOrContextChanges",
        "Camera-only frames must reuse the GPU buffer",
        "Context recreation must re-upload retained scene data",
    ):
        if marker not in mesh_tests:
            raise VerificationError(f"GPU preview performance regression is missing: {marker}")

    for document in ("README.md", "CONTRIBUTING.md"):
        lowered = sources[document].lower()
        if "preview" not in lowered or "floatarray" not in lowered or "vbo" not in lowered:
            raise VerificationError(f"primitive preview boundary is not documented in {document}")


def read_sources() -> dict[str, str]:
    main = ROOT / "android/app/src/main/java/com/ashcastle/duckyslicer"
    tests = ROOT / "android/app/src/test/java/com/ashcastle/duckyslicer"
    device = ROOT / "android/app/src/androidTest/java/com/ashcastle/duckyslicer"
    return {
        "NativeEngine.kt": (main / "NativeEngine.kt").read_text(encoding="utf-8"),
        "PreviewModels.kt": (main / "PreviewModels.kt").read_text(encoding="utf-8"),
        "ToolpathPreviewView.kt": (main / "ToolpathPreviewView.kt").read_text(
            encoding="utf-8"
        ),
        "MainActivity.kt": (main / "MainActivity.kt").read_text(encoding="utf-8"),
        "NativeEngineInstrumentedTest.kt": (
            device / "NativeEngineInstrumentedTest.kt"
        ).read_text(encoding="utf-8"),
        "PreviewModelsTest.kt": (tests / "PreviewModelsTest.kt").read_text(
            encoding="utf-8"
        ),
        "ToolpathMeshBuilderTest.kt": (tests / "ToolpathMeshBuilderTest.kt").read_text(
            encoding="utf-8"
        ),
        "lib.rs": (ROOT / "rust/duckyslicer-jni/src/lib.rs").read_text(encoding="utf-8"),
        "README.md": (ROOT / "README.md").read_text(encoding="utf-8"),
        "CONTRIBUTING.md": (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8"),
    }


def main() -> None:
    try:
        verify_preview_boundary(read_sources())
    except (OSError, VerificationError) as error:
        raise SystemExit(f"Preview boundary verification failed: {error}") from error
    print("Verified bounded FloatArray preview boundary and scene-stable OpenGL VBO uploads")


if __name__ == "__main__":
    main()

package com.ashcastle.duckyslicer

internal object NativeEngine {
    init {
        System.loadLibrary("duckyslicer")
    }

    external fun version(): String

    external fun vulkanCapabilities(): String

    external fun inspectStlPayload(path: String): FloatArray?

    external fun transformStl(inputPath: String, outputPath: String, transformJson: String): String

    external fun transformStlGroup(requestJson: String): String

    external fun layOnFace(requestJson: String): String

    external fun previewGcodeRange(path: String, startLayer: Int, endLayer: Int): FloatArray?
}

internal fun inspectModel(path: String): ModelInfo = ModelInfo.fromNative(
    NativeEngine.inspectStlPayload(path),
    path,
)

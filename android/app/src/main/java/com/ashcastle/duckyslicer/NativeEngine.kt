package com.ashcastle.duckyslicer

import java.nio.ByteBuffer

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

    external fun packToolpathGeometry(
        segments: FloatArray,
        pathStarts: IntArray,
        pathEndsExclusive: IntArray,
        bedOriginX: Float,
        bedOriginY: Float,
        minZMm: Float,
        maxZMm: Float,
        opacity: Float,
        depthContrast: Float,
        reverseForEarlyZ: Boolean,
        renderAsLines: Boolean,
        output: ByteBuffer,
    ): Int
}

internal fun inspectModel(path: String): ModelInfo = ModelInfo.fromNative(
    NativeEngine.inspectStlPayload(path),
    path,
)

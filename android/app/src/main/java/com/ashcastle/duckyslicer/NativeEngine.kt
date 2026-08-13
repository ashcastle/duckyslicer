package com.ashcastle.duckyslicer

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    external fun previewGcodeRangeInto(
        path: String,
        startLayer: Int,
        endLayer: Int,
        output: ByteBuffer,
    ): Int

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

internal fun loadGcodePreview(path: String, startLayer: Int, endLayer: Int): GcodeLayerPreview {
    val payload = NativePreviewBufferPool.acquire()
    return try {
        val usedFloats = NativeEngine.previewGcodeRangeInto(
            path = path,
            startLayer = startLayer,
            endLayer = endLayer,
            output = payload,
        )
        GcodeLayerPreview.fromTrustedNative(payload, usedFloats)
    } finally {
        NativePreviewBufferPool.release(payload)
    }
}

private object NativePreviewBufferPool {
    private const val MAX_RETAINED_BUFFERS = 2
    private val available = ArrayDeque<ByteBuffer>(MAX_RETAINED_BUFFERS)

    fun acquire(): ByteBuffer = synchronized(available) {
        available.removeFirstOrNull()
    } ?: ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)
        .order(ByteOrder.nativeOrder())

    fun release(buffer: ByteBuffer) {
        buffer.clear()
        synchronized(available) {
            if (available.size < MAX_RETAINED_BUFFERS) {
                available.addLast(buffer)
            }
        }
    }
}

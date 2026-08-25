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
        filamentColors: IntArray,
        colorByFilament: Boolean,
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
            output = payload.buffer,
        )
        GcodeLayerPreview.fromTrustedNative(payload.buffer, usedFloats)
    } finally {
        NativePreviewBufferPool.release(payload)
    }
}

internal object NativePreviewBufferPool {
    private const val MAX_RETAINED_BUFFERS = 2
    private val available = ArrayDeque<ByteBuffer>(MAX_RETAINED_BUFFERS)
    private var generation = 0L

    internal data class Lease(
        val buffer: ByteBuffer,
        internal val generation: Long,
    )

    fun acquire(): Lease = synchronized(available) {
        Lease(
            buffer = available.removeFirstOrNull()
                ?: ByteBuffer.allocateDirect(GcodeLayerPreview.MAX_PAYLOAD_BYTES)
                    .order(ByteOrder.nativeOrder()),
            generation = generation,
        )
    }

    fun release(lease: Lease) {
        lease.buffer.clear()
        synchronized(available) {
            if (lease.generation == generation && available.size < MAX_RETAINED_BUFFERS) {
                available.addLast(lease.buffer)
            }
        }
    }

    fun trimForMemoryPressure() {
        synchronized(available) {
            generation += 1L
            available.clear()
        }
    }

    internal fun retainedBufferCountForTest(): Int = synchronized(available) { available.size }
}

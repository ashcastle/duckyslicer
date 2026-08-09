package com.u1.slicer

import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult

/**
 * Compatibility seam for the inherited Android slicer runtime used during the MVP bootstrap.
 * Keep this package and method surface stable until the runtime is rebuilt behind the Rust ABI.
 */
class NativeLibrary(
    private val progressListener: (Int) -> Unit = {},
) {
    companion object {
        init {
            System.loadLibrary("prusaslicer-jni")
        }
    }

    external fun getCoreVersion(): String
    external fun loadModel(path: String): Boolean
    external fun addModel(path: String): Boolean
    external fun nativeAutoOrientObject(objectIndex: Int): DoubleArray?
    external fun applySupportPaint(objectIndex: Int, sidecarPath: String): Boolean
    external fun getObjectBoundingBoxes(): FloatArray
    external fun clearModel()
    external fun getModelInfo(): ModelInfo?
    external fun slice(config: SliceConfig): SliceResult?
    external fun getGcodePreview(maxLines: Int = 100): String

    @Suppress("UNUSED_PARAMETER")
    fun onSliceProgress(percent: Int, stage: String) {
        progressListener(percent.coerceIn(0, 100))
    }
}

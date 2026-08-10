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
    external fun nativeAutoArrangeObjects(
        bedPolygon: FloatArray,
        minimumGap: Float,
    ): FloatArray?
    external fun nativeSplitObject(objectIndex: Int): IntArray?
    external fun nativeIsObjectSplittable(objectIndex: Int): Boolean
    external fun nativeSetVolumeExtruder(objectIndex: Int, volumeIndex: Int, slot: Int): Boolean
    external fun applySupportPaint(objectIndex: Int, sidecarPath: String): Boolean
    external fun getObjectBoundingBoxes(): FloatArray
    external fun nativeGetObjectWorldAABBMins(): FloatArray
    external fun nativeExportLoadedObjects(outputDirectory: String): Array<String>?
    external fun clearModel()
    external fun getModelInfo(): ModelInfo?
    external fun slice(config: SliceConfig): SliceResult?
    external fun getGcodePreview(maxLines: Int = 100): String

    @Suppress("UNUSED_PARAMETER")
    fun onSliceProgress(percent: Int, stage: String) {
        progressListener(percent.coerceIn(0, 100))
    }
}

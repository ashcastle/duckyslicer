package com.u1.slicer

import com.u1.slicer.data.ModelInfo
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.data.SliceResult

/**
 * Stable interoperability seam for the source-built native slicing runtime.
 * Keep this package and method surface stable while ownership moves behind the Rust ABI.
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
        bedExcludeArea: FloatArray,
        minimumGap: Float,
    ): FloatArray?
    external fun nativeSplitObject(objectIndex: Int): IntArray?
    external fun nativeIsVolumeSplittable(objectIndex: Int, volumeIndex: Int): Boolean
    external fun nativeSplitVolume(objectIndex: Int, volumeIndex: Int): Int
    external fun nativeCutObject(objectIndex: Int, heightRatio: Float, placeOnCut: Boolean): IntArray?
    external fun nativeSimplifyObject(objectIndex: Int, targetTriangles: Int): Int
    external fun nativeAddModelPartVolume(objectIndex: Int, path: String, name: String): Int
    external fun nativeSetVolumeSemantics(
        objectIndex: Int,
        volumeIndex: Int,
        volumeType: Int,
        configPath: String,
    ): Boolean
    external fun nativeCreatePrimitive(primitiveType: Int, sizeMm: Float, outputPath: String): Boolean
    external fun nativeIsObjectSplittable(objectIndex: Int): Boolean
    external fun nativeSetVolumeExtruder(objectIndex: Int, volumeIndex: Int, slot: Int): Boolean
    external fun applySupportPaint(objectIndex: Int, volumeIndex: Int, sidecarPath: String): Boolean
    external fun applySeamPaint(objectIndex: Int, volumeIndex: Int, sidecarPath: String): Boolean
    external fun applyMultiColorPaint(objectIndex: Int, volumeIndex: Int, sidecarPath: String): Boolean
    external fun nativeApplyOrcaFacetAnnotations(
        objectIndex: Int,
        volumeIndex: Int,
        supportPath: String,
        seamPath: String,
        multiColorPath: String,
    ): Boolean
    external fun applyVariableLayerHeights(objectIndex: Int, sidecarPath: String): Boolean
    external fun applyObjectProcessOverrides(objectIndex: Int, sidecarPath: String): Boolean

    external fun applyHeightRangeModifiers(objectIndex: Int, sidecarPath: String): Boolean
    external fun applyBrimPoints(objectIndex: Int, sidecarPath: String): Boolean
    external fun getObjectBoundingBoxes(): FloatArray
    external fun nativeGetObjectWorldAABBMins(): FloatArray
    external fun nativeExportLoadedObjects(outputDirectory: String): Array<String>?
    external fun nativeGetUnsupportedProjectSemanticCount(): Int
    external fun nativeExportLoadedProjectVolumes(outputDirectory: String): Array<String>?
    external fun nativeSetProjectObjectName(objectIndex: Int, name: String): Boolean
    external fun nativeSetProjectVolumeName(
        objectIndex: Int,
        volumeIndex: Int,
        name: String,
    ): Boolean
    external fun nativeExportLoadedProject3mf(outputPath: String): Boolean
    external fun nativeExportObjectVolumeRange(
        outputDirectory: String,
        objectIndex: Int,
        startVolumeIndex: Int,
        volumeCount: Int,
    ): Array<String>?
    external fun clearModel()
    external fun getModelInfo(): ModelInfo?
    external fun slice(config: SliceConfig): SliceResult?
    external fun getGcodePreview(maxLines: Int = 100): String

    @Suppress("UNUSED_PARAMETER")
    fun onSliceProgress(percent: Int, stage: String) {
        progressListener(percent.coerceIn(0, 100))
    }
}

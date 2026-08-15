package com.u1.slicer.data

/** JNI data contract. User-facing wording is mapped by the app layer. */
data class SliceResult(
    @JvmField val success: Boolean,
    @JvmField val cancelled: Boolean,
    @JvmField val errorMessage: String,
    @JvmField val gcodePath: String,
    @JvmField val totalLayers: Int,
    @JvmField val estimatedTimeSeconds: Float,
    @JvmField val estimatedFilamentMm: Float,
    @JvmField val estimatedFilamentGrams: Float,
    @JvmField val suggestedFilename: String,
)

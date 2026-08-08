package com.u1.slicer.data

/** JNI data contract. Not exposed directly in the app UI. */
data class ModelInfo(
    @JvmField val filename: String,
    @JvmField val format: String,
    @JvmField val sizeX: Float,
    @JvmField val sizeY: Float,
    @JvmField val sizeZ: Float,
    @JvmField val triangleCount: Int,
    @JvmField val volumeCount: Int,
    @JvmField val isManifold: Boolean,
)

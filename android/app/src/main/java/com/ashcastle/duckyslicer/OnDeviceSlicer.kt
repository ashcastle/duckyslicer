package com.ashcastle.duckyslicer

import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import java.io.File

data class SliceOutcome(
    val output: File,
    val layers: Int,
    val estimatedSeconds: Float,
    val filamentGrams: Float,
)

enum class QualityProfile(val layerHeightMm: Float) {
    DRAFT(0.28f),
    STANDARD(0.20f),
    FINE(0.12f),
}

data class SliceOptions(
    val quality: QualityProfile = QualityProfile.STANDARD,
)

object OnDeviceSlicer {
    fun slice(
        model: File,
        options: SliceOptions = SliceOptions(),
        onProgress: (Int) -> Unit = {},
    ): SliceOutcome {
        require(model.isFile) { "모델 파일을 찾을 수 없습니다" }

        val runtime = NativeLibrary(onProgress)
        return try {
            check(runtime.loadModel(model.absolutePath)) { "모델을 준비하지 못했습니다" }
            val config = SliceConfig(layerHeight = options.quality.layerHeightMm)
            val result = requireNotNull(runtime.slice(config)) { "출력 데이터를 만들지 못했습니다" }
            check(result.success) {
                if (result.cancelled) "작업이 취소되었습니다" else "출력 데이터를 만들지 못했습니다"
            }
            val output = File(result.gcodePath)
            check(output.isFile && output.length() > 0L) { "저장할 데이터를 만들지 못했습니다" }
            SliceOutcome(
                output = output,
                layers = result.totalLayers,
                estimatedSeconds = result.estimatedTimeSeconds,
                filamentGrams = result.estimatedFilamentGrams,
            )
        } finally {
            runtime.clearModel()
        }
    }
}

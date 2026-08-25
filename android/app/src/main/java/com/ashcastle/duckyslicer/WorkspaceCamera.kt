package com.ashcastle.duckyslicer

internal enum class WorkspaceCameraPreset {
    ISOMETRIC,
    TOP,
    FRONT,
    RIGHT,
}

internal data class WorkspaceCameraRequest(
    val id: Long,
    val preset: WorkspaceCameraPreset,
)

internal data class WorkspaceCameraPose(
    val yawDegrees: Float,
    val elevationDegrees: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

internal fun cameraPoseForPreset(preset: WorkspaceCameraPreset): WorkspaceCameraPose =
    when (preset) {
        WorkspaceCameraPreset.ISOMETRIC -> WorkspaceCameraPose(
            yawDegrees = -45f,
            elevationDegrees = 55f,
        )
        WorkspaceCameraPreset.TOP -> WorkspaceCameraPose(
            yawDegrees = -90f,
            elevationDegrees = 86f,
        )
        WorkspaceCameraPreset.FRONT -> WorkspaceCameraPose(
            yawDegrees = 0f,
            elevationDegrees = 18f,
        )
        WorkspaceCameraPreset.RIGHT -> WorkspaceCameraPose(
            yawDegrees = 90f,
            elevationDegrees = 18f,
        )
    }

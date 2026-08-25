package com.ashcastle.duckyslicer

import java.io.Serializable

data class PlateSliceResult(
    val plateId: String,
    val outcome: SliceOutcome,
) : Serializable {
    init {
        require(plateId.length in 1..ProjectStore.MAX_ID_LENGTH) { "Invalid slice plate id" }
    }
}

data class PlateSliceResults(
    val values: List<PlateSliceResult> = emptyList(),
) : Serializable {
    init {
        require(values.size <= MAX_PROJECT_PLATES) { "Too many plate slice results" }
        require(values.map(PlateSliceResult::plateId).toSet().size == values.size) {
            "Duplicate plate slice result"
        }
    }

    fun resultFor(plateId: String): PlateSliceResult? =
        values.firstOrNull { it.plateId == plateId }

    fun outcomeFor(plateId: String): SliceOutcome? = resultFor(plateId)?.outcome

    fun put(plateId: String, outcome: SliceOutcome): PlateSliceResults = PlateSliceResults(
        values.filterNot { it.plateId == plateId } + PlateSliceResult(plateId, outcome),
    )

    fun clear(plateId: String): PlateSliceResults = PlateSliceResults(
        values.filterNot { it.plateId == plateId },
    )

    fun retain(plateIds: Set<String>): PlateSliceResults = PlateSliceResults(
        values.filter { it.plateId in plateIds },
    )
}

data class PlateSliceInput(
    val plateId: String,
    val objects: List<ProjectObject>,
    val options: SliceOptions,
    val layerPauseEvents: LayerPauseEvents,
    val layerFilamentChanges: LayerFilamentChanges,
)

internal fun ProjectSnapshot.sliceInput(
    plateOptions: Map<String, SliceOptions>,
): PlateSliceInput? {
    val plate = activePlate
    if (plate.objects.isEmpty()) return null
    return PlateSliceInput(
        plateId = plate.id,
        objects = plate.objects,
        options = plateOptions[plate.id] ?: return null,
        layerPauseEvents = plate.layerPauseEvents,
        layerFilamentChanges = plate.layerFilamentChanges,
    )
}

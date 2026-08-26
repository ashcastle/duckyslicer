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
    val layerCustomGCodeEvents: LayerCustomGCodeEvents,
)

internal fun ProjectSnapshot.sliceInput(
    plateOptions: Map<String, SliceOptions>,
): PlateSliceInput? {
    return sliceInput(activePlate.id, plateOptions)
}

internal fun ProjectSnapshot.sliceInput(
    plateId: String,
    plateOptions: Map<String, SliceOptions>,
): PlateSliceInput? {
    val plate = plates.firstOrNull { it.id == plateId } ?: return null
    if (plate.objects.isEmpty()) return null
    return PlateSliceInput(
        plateId = plate.id,
        objects = plate.objects,
        options = plateOptions[plate.id] ?: return null,
        layerPauseEvents = plate.layerPauseEvents,
        layerFilamentChanges = plate.layerFilamentChanges,
        layerCustomGCodeEvents = plate.layerCustomGCodeEvents,
    )
}

internal fun ProjectSnapshot.sliceablePlateIds(
    plateOptions: Map<String, SliceOptions>,
): List<String> = plates.mapNotNull { plate ->
    plate.id.takeIf { plate.objects.isNotEmpty() && plateOptions.containsKey(plate.id) }
}

internal fun PlateSliceResults.completeExportBatch(
    snapshot: ProjectSnapshot,
): GcodeExportBatch? {
    val printablePlates = snapshot.plates.withIndex().filter { it.value.objects.isNotEmpty() }
    if (printablePlates.size < 2) return null
    val entries = printablePlates.map { indexedPlate ->
        val plate = indexedPlate.value
        val result = resultFor(plate.id) ?: return null
        GcodeExportEntry(
            displayName = plateGcodeFileName(indexedPlate.index + 1, result.outcome.suggestedName),
            outcome = result.outcome,
        )
    }
    return GcodeExportBatch(entries)
}

internal fun plateGcodeFileName(plateNumber: Int, suggestedName: String): String {
    require(plateNumber in 1..MAX_PROJECT_PLATES) { "Invalid plate number" }
    val base = safeGcodeFileName(suggestedName).removeSuffix(".gcode")
    return safeGcodeFileName("plate-${plateNumber.toString().padStart(2, '0')}-$base")
}

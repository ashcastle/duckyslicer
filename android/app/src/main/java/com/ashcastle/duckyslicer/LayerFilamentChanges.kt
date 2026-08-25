package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

data class LayerFilamentChange(
    val printZMm: Float,
    val filamentSlot: Int,
) {
    init {
        require(printZMm.isFinite() && printZMm in MIN_PRINT_Z_MM..MAX_PRINT_Z_MM) {
            "Invalid layer filament change height"
        }
        require(filamentSlot in 0 until MAX_FILAMENT_SLOTS) {
            "Invalid layer filament change slot"
        }
    }

    companion object {
        const val MIN_PRINT_Z_MM = LayerPauseEvent.MIN_PRINT_Z_MM
        const val MAX_PRINT_Z_MM = LayerPauseEvent.MAX_PRINT_Z_MM
    }
}

data class LayerFilamentChanges(
    val values: List<LayerFilamentChange> = emptyList(),
) {
    init {
        require(values.size <= MAX_EVENTS) { "Too many layer filament changes" }
        require(values.zipWithNext().all { (left, right) -> left.printZMm < right.printZMm }) {
            "Layer filament changes must be unique and ordered"
        }
    }

    fun put(change: LayerFilamentChange): LayerFilamentChanges {
        val replaced = values.filterNot { it.printZMm == change.printZMm } + change
        return LayerFilamentChanges(replaced.sortedBy(LayerFilamentChange::printZMm))
    }

    fun remove(printZMm: Float): LayerFilamentChanges = LayerFilamentChanges(
        values.filterNot { it.printZMm == printZMm },
    )

    fun changeAt(printZMm: Float): LayerFilamentChange? =
        values.firstOrNull { it.printZMm == printZMm }

    fun constrainedToSlotCount(slotCount: Int): LayerFilamentChanges {
        require(slotCount in 1..MAX_FILAMENT_SLOTS) { "Filament slot count is invalid" }
        return LayerFilamentChanges(values.filter { it.filamentSlot < slotCount })
    }

    companion object {
        const val MAX_EVENTS = 256
    }
}

internal fun LayerFilamentChanges.toProjectJson(): JSONArray = JSONArray().also { changes ->
    values.forEach { change ->
        changes.put(
            JSONObject()
                .put("printZMm", change.printZMm.toDouble())
                .put("filamentSlot", change.filamentSlot),
        )
    }
}

internal fun JSONArray.toLayerFilamentChanges(): LayerFilamentChanges {
    require(length() <= LayerFilamentChanges.MAX_EVENTS) { "Too many layer filament changes" }
    return LayerFilamentChanges(
        List(length()) { index ->
            val value = getJSONObject(index)
            require(value.length() == 2 && value.has("printZMm") && value.has("filamentSlot")) {
                "Invalid layer filament change"
            }
            LayerFilamentChange(
                printZMm = value.getDouble("printZMm").toFloat(),
                filamentSlot = value.getInt("filamentSlot"),
            )
        },
    )
}

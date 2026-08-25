package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

data class LayerPauseEvent(
    val printZMm: Float,
    val message: String = "",
) {
    init {
        require(printZMm.isFinite() && printZMm in MIN_PRINT_Z_MM..MAX_PRINT_Z_MM) {
            "Invalid layer pause height"
        }
        require(message == message.trim() && message.length <= MAX_MESSAGE_LENGTH) {
            "Invalid layer pause message"
        }
        require(message.none(Char::isISOControl)) { "Invalid layer pause message" }
    }

    companion object {
        const val MIN_PRINT_Z_MM = 0.001f
        const val MAX_PRINT_Z_MM = 10_000f
        const val MAX_MESSAGE_LENGTH = 80
    }
}

data class LayerPauseEvents(
    val values: List<LayerPauseEvent> = emptyList(),
) {
    init {
        require(values.size <= MAX_EVENTS) { "Too many layer pause events" }
        require(values.zipWithNext().all { (left, right) -> left.printZMm < right.printZMm }) {
            "Layer pause events must be unique and ordered"
        }
    }

    fun put(event: LayerPauseEvent): LayerPauseEvents {
        val replaced = values.filterNot { it.printZMm == event.printZMm } + event
        return LayerPauseEvents(replaced.sortedBy(LayerPauseEvent::printZMm))
    }

    fun remove(printZMm: Float): LayerPauseEvents = LayerPauseEvents(
        values.filterNot { it.printZMm == printZMm },
    )

    fun eventAt(printZMm: Float): LayerPauseEvent? =
        values.firstOrNull { it.printZMm == printZMm }

    companion object {
        const val MAX_EVENTS = 256
    }
}

internal fun LayerPauseEvents.toProjectJson(): JSONArray = JSONArray().also { events ->
    values.forEach { event ->
        events.put(
            JSONObject()
                .put("printZMm", event.printZMm.toDouble())
                .put("message", event.message),
        )
    }
}

internal fun JSONArray.toLayerPauseEvents(): LayerPauseEvents {
    require(length() <= LayerPauseEvents.MAX_EVENTS) { "Too many layer pause events" }
    return LayerPauseEvents(
        List(length()) { index ->
            val value = getJSONObject(index)
            require(value.length() == 2 && value.has("printZMm") && value.has("message")) {
                "Invalid layer pause event"
            }
            LayerPauseEvent(
                printZMm = value.getDouble("printZMm").toFloat(),
                message = value.getString("message"),
            )
        },
    )
}

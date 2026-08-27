package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

enum class LayerCustomGCodeKind(
    val storageValue: String,
    val nativeValue: Int,
) {
    CUSTOM("custom", 0),
    PRINTER_TEMPLATE("printer_template", 1),
    ;

    companion object {
        fun fromStorage(value: String): LayerCustomGCodeKind? = entries.firstOrNull {
            it.storageValue == value
        }

        fun fromNative(value: Int): LayerCustomGCodeKind? = entries.firstOrNull {
            it.nativeValue == value
        }
    }
}

data class LayerCustomGCodeEvent(
    val printZMm: Float,
    val gcode: String,
    val kind: LayerCustomGCodeKind = LayerCustomGCodeKind.CUSTOM,
) {
    init {
        require(printZMm.isFinite() && printZMm in MIN_PRINT_Z_MM..MAX_PRINT_Z_MM) {
            "Invalid layer G-code height"
        }
        require(
            when (kind) {
                LayerCustomGCodeKind.CUSTOM -> gcode.isNotEmpty() && gcode == gcode.trim()
                LayerCustomGCodeKind.PRINTER_TEMPLATE -> gcode.isEmpty()
            },
        ) { "Invalid layer G-code" }
        require('\r' !in gcode && '\u0000' !in gcode) { "Invalid layer G-code" }
        require(gcode.all { it == '\n' || it == '\t' || !it.isISOControl() }) {
            "Invalid layer G-code"
        }
        require(gcode.lineSequence().count() <= MAX_LINES) { "Layer G-code has too many lines" }
        require(gcode.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_BYTES) {
            "Layer G-code is too large"
        }
        require(gcode.lineSequence().all { it.toByteArray(Charsets.UTF_8).size <= MAX_LINE_BYTES }) {
            "Layer G-code line is too large"
        }
    }

    companion object {
        const val MIN_PRINT_Z_MM = LayerPauseEvent.MIN_PRINT_Z_MM
        const val MAX_PRINT_Z_MM = LayerPauseEvent.MAX_PRINT_Z_MM
        const val MAX_LINES = 32
        const val MAX_LINE_BYTES = 256
        const val MAX_GCODE_BYTES = 2_048
    }
}

data class LayerCustomGCodeEvents(
    val values: List<LayerCustomGCodeEvent> = emptyList(),
) {
    init {
        require(values.size <= MAX_EVENTS) { "Too many layer G-code events" }
        require(values.zipWithNext().all { (left, right) -> left.printZMm < right.printZMm }) {
            "Layer G-code events must be unique and ordered"
        }
        require(values.sumOf { it.gcode.toByteArray(Charsets.UTF_8).size } <= MAX_TOTAL_BYTES) {
            "Layer G-code events are too large"
        }
    }

    fun put(event: LayerCustomGCodeEvent): LayerCustomGCodeEvents {
        val replaced = values.filterNot { it.printZMm == event.printZMm } + event
        return LayerCustomGCodeEvents(replaced.sortedBy(LayerCustomGCodeEvent::printZMm))
    }

    fun remove(printZMm: Float): LayerCustomGCodeEvents = LayerCustomGCodeEvents(
        values.filterNot { it.printZMm == printZMm },
    )

    fun eventAt(printZMm: Float): LayerCustomGCodeEvent? =
        values.firstOrNull { it.printZMm == printZMm }

    companion object {
        const val MAX_EVENTS = 64
        const val MAX_TOTAL_BYTES = 32_768
    }
}

internal fun normalizedLayerCustomGCode(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .trim()

internal fun LayerCustomGCodeEvents.toProjectJson(): JSONArray = JSONArray().also { events ->
    values.forEach { event ->
        events.put(
            JSONObject()
                .put("printZMm", event.printZMm.toDouble())
                .put("gcode", event.gcode)
                .put("kind", event.kind.storageValue),
        )
    }
}

internal fun JSONArray.toLayerCustomGCodeEvents(): LayerCustomGCodeEvents {
    require(length() <= LayerCustomGCodeEvents.MAX_EVENTS) { "Too many layer G-code events" }
    return LayerCustomGCodeEvents(
        List(length()) { index ->
            val value = getJSONObject(index)
            require(
                value.length() in 2..3 && value.has("printZMm") && value.has("gcode") &&
                    (value.length() == 2 || value.has("kind")),
            ) {
                "Invalid layer G-code event"
            }
            LayerCustomGCodeEvent(
                printZMm = value.getDouble("printZMm").toFloat(),
                gcode = value.getString("gcode"),
                kind = if (value.has("kind")) {
                    requireNotNull(LayerCustomGCodeKind.fromStorage(value.getString("kind"))) {
                        "Invalid layer G-code kind"
                    }
                } else {
                    LayerCustomGCodeKind.CUSTOM
                },
            )
        },
    )
}

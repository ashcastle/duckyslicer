package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

internal data class OrcaPrinterConversion(
    val profile: PrinterProfile,
    val appliedKeys: Set<String>,
    val unsupportedKeys: Set<String>,
    val defaultedKeys: Set<String>,
)

private val printerNumericFields = mapOf(
    "printable_height" to "maxPrintHeight", "nozzle_diameter" to "nozzleDiameter",
    "min_layer_height" to "minLayerHeight", "max_layer_height" to "maxLayerHeight",
    "retraction_length" to "retractLength", "retraction_speed" to "retractSpeed",
    "deretraction_speed" to "deretractSpeed",
)
private val printerStringFields = mapOf("gcode_flavor" to "gcodeFlavor", "printer_structure" to "printerStructure")
private val printerGcodeFields = mapOf(
    "machine_start_gcode" to "machineStartGcode", "machine_end_gcode" to "machineEndGcode",
    "before_layer_change_gcode" to "beforeLayerChangeGcode", "layer_change_gcode" to "layerChangeGcode",
    "change_filament_gcode" to "changeFilamentGcode",
)
internal val orcaPrinterSupportedKeys = printerNumericFields.keys + printerStringFields.keys + printerGcodeFields.keys + "printable_area"

internal fun convertOrcaPrinter(raw: JSONObject, id: String, baseline: PrinterProfile): OrcaPrinterConversion {
    require(raw.optString("type") == "machine" && raw.optString("name").isNotBlank()) { "invalid_printer_preset" }
    val output = baseline.toProfileJson().put("id", id).put("name", raw.getString("name"))
    val applied = mutableSetOf<String>()
    printerGcodeFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = raw.get(source)
            require(value is String) { "invalid_printer_gcode:$source" }
            output.put(target, value)
            applied += source
        }
    }
    printerStringFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = raw.get(source)
            require(value is String && value.isNotBlank()) { "invalid_printer_string:$source" }
            output.put(target, value)
            applied += source
        }
    }
    printerNumericFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = raw.orcaSingleValue(source).toString().trim().toDoubleOrNull()
            require(value != null && value.isFinite()) { "invalid_number:$source" }
            output.put(target, value)
            applied += source
        }
    }
    // Orca uses zero as an automatic layer limit, not a literal zero height.
    if (raw.has("min_layer_height")) {
        val minimum = output.getDouble("minLayerHeight")
        require(minimum >= 0) { "invalid_min_layer_height" }
        output.put("minLayerHeight", if (minimum == 0.0) 0.07 else maxOf(0.01, minimum))
    }
    if (raw.has("max_layer_height")) {
        val maximum = output.getDouble("maxLayerHeight")
        require(maximum >= 0) { "invalid_max_layer_height" }
        output.put("maxLayerHeight", maxOf(output.getDouble("minLayerHeight"),
            if (maximum == 0.0) output.getDouble("nozzleDiameter") * 0.75 else maximum))
    }
    if (raw.has("printable_area")) {
        val source = raw.get("printable_area")
        val strings = when (source) {
            is String -> listOf(source)
            is JSONArray -> {
                require(source.length() <= 257) { "too_many_bed_points" }
                (0 until source.length()).map {
                    val point = source.get(it)
                    require(point is String) { "invalid_bed_point" }
                    point
                }
            }
            else -> throw IllegalArgumentException("invalid_printable_area")
        }
        val points = strings.flatMap { it.split(',') }.map { text ->
            val coordinates = text.trim().lowercase().split('x')
            require(coordinates.size == 2) { "invalid_bed_point" }
            coordinates.map { coordinate ->
                requireNotNull(coordinate.trim().toFloatOrNull()?.takeIf(Float::isFinite)) { "invalid_bed_point" }
            }
        }.toMutableList()
        if (points.size > 1 && points.first() == points.last()) points.removeAt(points.lastIndex)
        require(points.size in 3..256) { "invalid_bed_point_count" }
        val originX = points.minOf { it[0] }
        val originY = points.minOf { it[1] }
        output.put("bedOriginX", originX).put("bedOriginY", originY)
            .put("bedSizeX", points.maxOf { it[0] } - originX)
            .put("bedSizeY", points.maxOf { it[1] } - originY)
            .put("bedPolygon", JSONArray(points.flatMap { listOf(it[0] - originX, it[1] - originY) }))
        applied += "printable_area"
    }
    val profile = requireNotNull(output.toPrinterProfileOrNull()) { "invalid_printer_settings" }
    require(ProfileValidation.printer(profile)) { "invalid_printer_settings" }
    val metadata = setOf("type", "name", "inherits", "from", "version", "instantiation", "setting_id")
    return OrcaPrinterConversion(profile, applied,
        raw.keys().asSequence().filter { it !in applied && it !in metadata }.toSortedSet(),
        orcaPrinterSupportedKeys - applied)
}

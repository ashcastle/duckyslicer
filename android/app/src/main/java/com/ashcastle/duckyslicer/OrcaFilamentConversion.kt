package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

internal data class OrcaFilamentConversion(
    val profile: FilamentProfile,
    val appliedKeys: Set<String>,
    val unsupportedKeys: Set<String>,
    val defaultedKeys: Set<String>,
)

private val filamentNumericFields = mapOf(
    "nozzle_temperature" to "nozzleTemp",
    "nozzle_temperature_initial_layer" to "firstLayerNozzleTemp",
    "hot_plate_temp" to "bedTemp",
    "hot_plate_temp_initial_layer" to "firstLayerBedTemp",
    "textured_plate_temp" to "texturedPlateTemp",
    "textured_plate_temp_initial_layer" to "firstLayerTexturedPlateTemp",
    "eng_plate_temp" to "engineeringPlateTemp",
    "eng_plate_temp_initial_layer" to "firstLayerEngineeringPlateTemp",
    "cool_plate_temp" to "coolPlateTemp",
    "cool_plate_temp_initial_layer" to "firstLayerCoolPlateTemp",
    "filament_flow_ratio" to "flowRatio",
    "filament_max_volumetric_speed" to "maxVolumetricSpeed",
    "filament_diameter" to "diameter",
    "filament_density" to "density",
    "filament_cost" to "costPerKilogram",
    "fan_min_speed" to "fanMinSpeed",
    "fan_max_speed" to "fanMaxSpeed",
    "overhang_fan_speed" to "overhangFanSpeed",
    "slow_down_layer_time" to "slowDownLayerTime",
    "slow_down_min_speed" to "slowDownMinSpeed",
    "close_fan_the_first_x_layers" to "closeFanFirstLayers",
    "full_fan_speed_layer" to "fullFanSpeedLayer",
    "pressure_advance" to "pressureAdvance",
)
private val filamentBooleanFields = mapOf(
    "enable_pressure_advance" to "pressureAdvanceEnabled",
    "slow_down_for_layer_cooling" to "slowDownForLayerCooling",
)
private val filamentStringFields = mapOf(
    "filament_type" to "nativeName",
    "filament_start_gcode" to "filamentStartGcode",
    "filament_end_gcode" to "filamentEndGcode",
    "filament_notes" to "notes",
)
internal val orcaFilamentSupportedKeys = filamentNumericFields.keys + filamentStringFields.keys + filamentBooleanFields.keys +
    setOf("compatible_printers", "compatible_prints", "default_filament_colour")

internal fun JSONObject.orcaSingleValue(key: String): Any {
    val value = get(key)
    if (value !is JSONArray) return value
    require(value.length() == 1) { "multiple_tool_values_require_mapping:$key" }
    return value.get(0)
}

internal fun convertOrcaFilament(raw: JSONObject, id: String, baseline: FilamentProfile): OrcaFilamentConversion {
    require(raw.optString("type") == "filament") { "wrong_preset_type" }
    require(raw.optString("name").isNotBlank()) { "missing_preset_name" }
    val output = baseline.toProfileJson().put("id", id).put("name", raw.getString("name"))
    val applied = mutableSetOf<String>()
    applied += copyOrcaPresetCompatibility(raw, output)
    if (raw.has("default_filament_colour")) {
        val value = raw.orcaSingleValue("default_filament_colour")
        require(value is String && (value.isEmpty() || Regex("#[0-9a-fA-F]{6}").matches(value))) {
            "invalid_filament_color"
        }
        output.put("defaultColor", if (value.isEmpty()) NO_FILAMENT_COLOR else value.drop(1).toInt(16))
        applied += "default_filament_colour"
    }
    filamentNumericFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = raw.orcaSingleValue(source).toString().trim().toDoubleOrNull()
            require(value != null && value.isFinite()) { "invalid_number:$source" }
            if (target.contains("Temp") || target.startsWith("fan") || target in
                setOf("overhangFanSpeed", "closeFanFirstLayers", "fullFanSpeedLayer")) {
                require(value % 1.0 == 0.0) { "invalid_integer:$source" }
            }
            output.put(target, value)
            applied += source
        }
    }
    filamentBooleanFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = when (raw.orcaSingleValue(source).toString()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> throw IllegalArgumentException("invalid_boolean:$source")
            }
            output.put(target, value)
            applied += source
        }
    }
    filamentStringFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = raw.orcaSingleValue(source)
            require(value is String) { "invalid_string:$source" }
            output.put(target, value)
            applied += source
        }
    }
    val profile = requireNotNull(output.toFilamentProfileOrNull()) { "invalid_filament_settings" }
    require(ProfileValidation.filament(profile)) { "invalid_filament_settings" }
    val metadata = setOf("type", "name", "inherits", "from", "version", "instantiation", "setting_id")
    return OrcaFilamentConversion(profile, applied,
        raw.keys().asSequence().filter { it !in applied && it !in metadata }.toSortedSet(),
        orcaFilamentSupportedKeys - applied)
}

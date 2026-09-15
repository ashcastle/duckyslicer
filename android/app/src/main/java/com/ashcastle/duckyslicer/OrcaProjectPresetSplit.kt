package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

internal data class OrcaProjectPresetSplit(
    val printer: JSONObject,
    val process: JSONObject,
    val filaments: List<JSONObject>,
    val unsupportedKeys: Set<String>,
)

/** Project filament vectors are indexed by material, not physical extruder. */
internal fun splitOrcaProjectSettings(settings: JSONObject): OrcaProjectPresetSplit {
    val filamentFields = orcaFilamentSupportedKeys - setOf("compatible_printers", "compatible_prints")
    val names = settings.optJSONArray("filament_settings_id")
    val lengths = (filamentFields + "filament_colour").mapNotNull { settings.optJSONArray(it)?.length() }
    val count = names?.length() ?: lengths.maxOrNull() ?: 1
    require(count in 1..MAX_FILAMENT_SLOTS) { "project_filament_count" }
    require(lengths.all { it == count }) { "project_filament_vector_length" }
    fun record(kind: String, name: String, fields: Set<String>): JSONObject =
        JSONObject().put("type", kind).put("name", name).also { out ->
            fields.filter(settings::has).forEach { key -> out.put(key, settings.get(key)) }
        }
    fun profileName(key: String, fallback: String): String {
        if (!settings.has(key)) return fallback
        val value = settings.get(key)
        require(value is String && value.isNotBlank()) { "invalid_project_profile_name:$key" }
        return value
    }
    val printer = record("machine", profileName("printer_settings_id", "Imported printer"), orcaPrinterSupportedKeys)
    val process = record("process", profileName("print_settings_id", "Imported process"), orcaProcessSupportedKeys)
    val filaments = List(count) { slot ->
        val name = if (names == null) "Imported filament ${slot + 1}" else {
            val value = names.get(slot)
            require(value is String && value.isNotBlank()) { "invalid_project_filament_name" }
            value
        }
        JSONObject().put("type", "filament").put("name", name).also { out ->
            filamentFields.filter(settings::has).forEach { key ->
                val value = settings.get(key)
                // Scalars remain explicit shared values; vectors must match exactly.
                out.put(key, if (value is JSONArray) value.get(slot) else value)
            }
            if (settings.has("filament_colour")) {
                val colors = settings.get("filament_colour")
                out.put("default_filament_colour", if (colors is JSONArray) colors.get(slot) else colors)
            }
        }
    }
    val handled = orcaPrinterSupportedKeys + orcaProcessSupportedKeys + filamentFields +
        setOf("printer_settings_id", "print_settings_id", "filament_settings_id", "filament_colour")
    return OrcaProjectPresetSplit(printer, process, filaments,
        settings.keys().asSequence().filter { it !in handled }.toSortedSet())
}

package com.ashcastle.duckyslicer

import org.json.JSONObject

internal data class OrcaProcessConversion(
    val profile: QualityProfile,
    val appliedKeys: Set<String>,
    val unsupportedKeys: Set<String>,
    val defaultedKeys: Set<String>,
)

private val processNumericFields = mapOf(
    "layer_height" to "layerHeightMm",
    "initial_layer_print_height" to "firstLayerHeightMm",
    "wall_loops" to "perimeters",
    "outer_wall_speed" to "printSpeed",
    "inner_wall_speed" to "innerWallSpeed",
    "sparse_infill_speed" to "sparseInfillSpeed",
    "internal_solid_infill_speed" to "internalSolidInfillSpeed",
    "top_surface_speed" to "topSurfaceSpeed",
    "support_speed" to "supportSpeed",
    "bridge_speed" to "bridgeSpeed",
    "gap_infill_speed" to "gapInfillSpeed",
    "initial_layer_infill_speed" to "firstLayerInfillSpeed",
    "initial_layer_speed" to "firstLayerSpeed",
    "travel_speed" to "travelSpeed",
    "travel_speed_z" to "travelSpeedZ",
    "support_interface_speed" to "supportInterfaceSpeed",
    "print_flow_ratio" to "printFlowRatio",
    "bridge_flow" to "bridgeFlowRatio",
    "top_shell_layers" to "topSolidLayers",
    "bottom_shell_layers" to "bottomSolidLayers",
    "top_shell_thickness" to "topShellThickness",
    "bottom_shell_thickness" to "bottomShellThickness",
    "support_top_z_distance" to "supportTopZDistance",
    "support_bottom_z_distance" to "supportBottomZDistance",
    "support_interface_top_layers" to "supportInterfaceTopLayers",
    "support_interface_bottom_layers" to "supportInterfaceBottomLayers",
    "support_interface_spacing" to "supportInterfaceSpacing",
    "support_bottom_interface_spacing" to "supportBottomInterfaceSpacing",
    "support_filament" to "supportFilament",
    "support_interface_filament" to "supportInterfaceFilament",
    "support_threshold_angle" to "supportAngle",
    "brim_width" to "brimWidth",
    "brim_object_gap" to "brimObjectGap",
    "prime_tower_width" to "wipeTowerWidth",
    "prime_volume" to "primeVolume",
    "flush_multiplier" to "flushMultiplier",
    "prime_tower_brim_width" to "primeTowerBrimWidth",
    "wipe_tower_rotation_angle" to "wipeTowerRotationAngle",
    "wipe_tower_bridging" to "wipeTowerBridging",
    "wipe_tower_max_purge_speed" to "wipeTowerMaxPurgeSpeed",
    "wipe_tower_filament" to "wipeTowerFilament",
)
private val processIntegerFields = setOf("wall_loops", "top_shell_layers", "bottom_shell_layers",
    "support_interface_top_layers", "support_interface_bottom_layers", "support_filament", "support_interface_filament",
    "wipe_tower_filament")
private val processBooleanFields = mapOf("enable_support" to "supportEnabled",
    "support_on_build_plate_only" to "supportOnBuildPlateOnly",
    "enable_prime_tower" to "wipeTowerEnabled",
    "wipe_tower_no_sparse_layers" to "wipeTowerNoSparseLayers",
    "prime_tower_enable_framework" to "primeTowerFramework",
    "flush_into_infill" to "flushIntoInfill",
    "flush_into_support" to "flushIntoSupport",
    "flush_into_objects" to "flushIntoObjects")

private val processEnumFields = mapOf(
    "support_type" to "supportType", "support_style" to "supportStyle",
    "sparse_infill_pattern" to "fillPattern", "seam_position" to "seamPosition",
    "wall_generator" to "wallGenerator", "top_surface_pattern" to "topSurfacePattern",
    "bottom_surface_pattern" to "bottomSurfacePattern", "print_sequence" to "printSequence",
    "brim_type" to "brimType",
)
internal val orcaProcessSupportedKeys = processNumericFields.keys + processBooleanFields.keys +
    processEnumFields.keys + setOf("sparse_infill_density", "compatible_printers")

/** Unmapped settings stay in the review instead of being misrepresented as imported. */
internal fun convertOrcaProcess(raw: JSONObject, id: String, baseline: QualityProfile): OrcaProcessConversion {
    require(raw.optString("type") == "process") { "wrong_preset_type" }
    require(raw.optString("name").isNotBlank()) { "missing_preset_name" }
    val output = baseline.toProfileJson().put("id", id).put("name", raw.getString("name"))
    if (raw.has("flush_multiplier")) output.put("flushMultiplierOverrideEnabled", true)
    val applied = mutableSetOf<String>()
    applied += copyOrcaPresetCompatibility(raw, output).filter { it == "compatible_printers" }
    fun numeric(key: String): Double {
        val value = raw.get(key)
        require(value is Number || value is String) { "invalid_scalar:$key" }
        val text = value.toString().trim()
        if (text.endsWith("%") && key in setOf("inner_wall_speed", "sparse_infill_speed")) {
            val percent = text.removeSuffix("%").toDoubleOrNull()
            require(percent != null && percent.isFinite() && percent >= 0) { "invalid_percentage:$key" }
            val base = if (raw.has("outer_wall_speed")) numeric("outer_wall_speed") else baseline.printSpeed.toDouble()
            val resolved = base * percent / 100.0
            require(resolved.isFinite()) { "invalid_number:$key" }
            return resolved
        }
        val number = text.toDoubleOrNull()
        require(number != null && number.isFinite()) { "invalid_number:$key" }
        return number
    }
    processNumericFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = numeric(source)
            if (source in processIntegerFields) require(value % 1.0 == 0.0) { "invalid_integer:$source" }
            output.put(target, value)
            applied += source
        }
    }
    processBooleanFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = when (raw.get(source).toString().trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> throw IllegalArgumentException("invalid_boolean:$source")
            }
            output.put(target, value)
            applied += source
        }
    }
    processEnumFields.forEach { (source, target) ->
        if (raw.has(source)) {
            val value = raw.get(source)
            require(value is String && value.isNotBlank()) { "invalid_enum:$source" }
            output.put(target, value)
            applied += source
        }
    }
    if (raw.has("support_style")) {
        val style = raw.getString("support_style").trim().lowercase()
        require(style == "default" || style in compatibleSupportStyles(output.getString("supportType"))) {
            "incompatible_support_style"
        }
    }
    if (raw.has("sparse_infill_density")) {
        val text = raw.get("sparse_infill_density").toString().trim().removeSuffix("%")
        val percent = text.toDoubleOrNull()
        require(percent != null && percent.isFinite() && percent in 0.0..100.0) { "invalid_infill_density" }
        output.put("fillDensity", percent / 100.0)
        applied += "sparse_infill_density"
    }
    val profile = requireNotNull(output.toQualityProfileOrNull()) { "invalid_process_settings" }
    require(ProfileValidation.slicing(profile)) { "invalid_process_settings" }
    val metadata = setOf("type", "name", "inherits", "from", "version", "instantiation", "setting_id")
    return OrcaProcessConversion(profile, applied,
        raw.keys().asSequence().filter { it !in applied && it !in metadata }.toSortedSet(),
        orcaProcessSupportedKeys - applied)
}

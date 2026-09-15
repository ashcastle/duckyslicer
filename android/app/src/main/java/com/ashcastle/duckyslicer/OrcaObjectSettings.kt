package com.ashcastle.duckyslicer

/** Converts serialized native options without inventing defaults or changing units. */
internal fun orcaObjectOverrides(values: Map<String, String>): ObjectProcessOverrides {
    // Native normalization has already resolved this object fallback onto each volume.
    values["extruder"]?.let {
        require(it.trim().toIntOrNull() in 0..MAX_FILAMENT_SLOTS) { "Invalid object material" }
    }
    fun number(key: String, percent: Boolean = false): Float? = values[key]?.let { raw ->
        val text = raw.trim().let { if (percent) it.removeSuffix("%") else it }
        requireNotNull(text.toFloatOrNull()?.takeIf(Float::isFinite)) { "Invalid object setting: $key" }
    }
    fun integer(key: String): Int? = values[key]?.let {
        requireNotNull(it.trim().toIntOrNull()) { "Invalid object setting: $key" }
    }
    return ObjectProcessOverrides(
        layerHeightMm = number("layer_height"),
        wallLoops = integer("wall_loops"),
        topShellLayers = integer("top_shell_layers"),
        bottomShellLayers = integer("bottom_shell_layers"),
        sparseInfillDensityPercent = number("sparse_infill_density", percent = true),
        outerWallSpeedMmS = number("outer_wall_speed"),
        innerWallSpeedMmS = number("inner_wall_speed"),
        sparseInfillSpeedMmS = number("sparse_infill_speed"),
        supportEnabled = values["enable_support"]?.let {
            when (it.trim()) {
                "1" -> true
                "0" -> false
                else -> error("Invalid object setting: enable_support")
            }
        },
    )
}

internal val orcaObjectOverrideKeys = setOf(
    "layer_height", "wall_loops", "top_shell_layers", "bottom_shell_layers",
    "sparse_infill_density", "outer_wall_speed", "inner_wall_speed", "sparse_infill_speed",
    "enable_support",
)
internal val orcaObjectSettingsKeys = orcaObjectOverrideKeys + "extruder"

internal fun orcaHeightRange(start: Float, end: Float, values: Map<String, String>): HeightRangeModifier {
    val materialKeys = setOf("wall_filament", "sparse_infill_filament", "solid_infill_filament")
    require(values.keys.all { it in orcaObjectOverrideKeys || it in materialKeys }) {
        "Height range contains unsupported settings"
    }
    val materials = materialKeys.map { key ->
        values[key]?.let { requireNotNull(it.trim().toIntOrNull()) { "Invalid height material" } } ?: 0
    }
    require(materials.all { it in 0..MAX_FILAMENT_SLOTS }) { "Invalid height material" }
    // One Android range selects all extrusion roles together. Never silently collapse mixed roles.
    require(materials.distinct().size == 1) { "Height range uses different materials for extrusion roles" }
    return HeightRangeModifier(start, end, orcaObjectOverrides(values),
        filamentSlot = materials.first().takeIf { it > 0 }?.minus(1))
}

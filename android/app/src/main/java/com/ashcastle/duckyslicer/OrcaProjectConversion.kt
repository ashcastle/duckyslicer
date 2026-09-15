package com.ashcastle.duckyslicer

import org.json.JSONObject

internal data class OrcaProjectConversion(
    val options: SliceOptions,
    val appliedKeys: Set<String>,
    val unsupportedKeys: Set<String>,
    val defaultedKeys: Set<String>,
)

/** Produces a review candidate only; never writes or applies it to a live project. */
internal fun convertOrcaProjectSettings(raw: JSONObject, baseline: SliceOptions): OrcaProjectConversion {
    val split = splitOrcaProjectSettings(raw)
    val printer = convertOrcaPrinter(split.printer, "import-project-printer", baseline.printerProfile)
    require(split.filaments.size <= printer.profile.extruderCount) { "project_requires_more_filament_slots" }
    val process = convertOrcaProcess(split.process, "import-project-process",
        baseline.quality.copy(nozzleDiameter = printer.profile.nozzleDiameter))
    val filaments = split.filaments.mapIndexed { slot, record ->
        convertOrcaFilament(record, "import-project-filament-$slot",
            baseline.resolvedFilamentSlots().getOrNull(slot) ?: baseline.filamentProfile)
    }
    val profiles = filaments.map { it.profile }
    require(process.profile.supportFilament in 0..profiles.size &&
        process.profile.supportInterfaceFilament in 0..profiles.size &&
        process.profile.featureFilaments.wipeTowerFilament in 0..profiles.size) {
        "project_support_material_unavailable"
    }
    var options = baseline.selectPrinter(printer.profile).selectFilament(profiles.first())
        .copy(filamentSlots = profiles, filamentColors = profiles.mapIndexed(::suggestedFilamentColor))
        .selectQuality(process.profile)
    require(options.resolvedFilamentSlots().size == profiles.size) { "project_filament_mapping_lost" }
    val projectApplied = mutableSetOf<String>()
    if (raw.has("flush_volumes_matrix")) {
        val values = raw.getJSONArray("flush_volumes_matrix")
        require(values.length() == profiles.size * profiles.size) { "project_purge_matrix_size" }
        val volumes = List(values.length()) { index ->
            val source = values.get(index)
            require(source is Number || source is String) { "invalid_project_purge_volume" }
            val value = source.toString().toFloatOrNull()
            require(value != null && value.isFinite() && value in MIN_PURGE_VOLUME..MAX_PURGE_VOLUME) {
                "invalid_project_purge_volume"
            }
            require(index / profiles.size != index % profiles.size || value == 0f) {
                "project_purge_diagonal_not_zero"
            }
            value
        }
        options = options.copy(multiMaterial = options.multiMaterial.copy(purgeVolumes = volumes))
        projectApplied += "flush_volumes_matrix"
    }
    val defaulted = printer.defaultedKeys.map { "printer:$it" } +
        process.defaultedKeys.map { "process:$it" } +
        filaments.flatMapIndexed { slot, result -> result.defaultedKeys.map { "filament${slot + 1}:$it" } }
    return OrcaProjectConversion(options,
        printer.appliedKeys + process.appliedKeys + filaments.flatMap { it.appliedKeys } + projectApplied,
        split.unsupportedKeys - projectApplied, defaulted.toSet())
}

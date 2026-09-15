package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

internal data class OrcaImportItem(
    val review: OrcaPresetReview,
    val converted: JSONObject?,
    val appliedKeys: Set<String> = emptySet(),
    val defaultedKeys: Set<String> = emptySet(),
    val problem: String? = review.problem,
)

internal fun prepareOrcaImport(
    bytes: ByteArray,
    parents: List<JSONObject>,
    baseline: SliceOptions,
    savedNames: Set<Pair<String, String>>,
): List<OrcaImportItem> {
    val archive = readOrcaPresetArchive(bytes)
    return reviewOrcaPresets(archive.presets, parents, mapOf(
        "machine" to orcaPrinterSupportedKeys,
        "filament" to orcaFilamentSupportedKeys,
        "process" to orcaProcessSupportedKeys,
    ), savedNames).mapIndexed { index, review ->
        val raw = review.resolved
        if (raw == null) OrcaImportItem(review, null) else runCatching {
            val id = "orca-import-$index"
            when (review.kind) {
                "machine" -> convertOrcaPrinter(raw, id, baseline.printerProfile).let {
                    OrcaImportItem(review, it.profile.toProfileJson(), it.appliedKeys, it.defaultedKeys)
                }
                "filament" -> convertOrcaFilament(raw, id, baseline.filamentProfile).let {
                    OrcaImportItem(review, it.profile.toProfileJson(), it.appliedKeys, it.defaultedKeys)
                }
                else -> convertOrcaProcess(raw, id, baseline.quality).let {
                    OrcaImportItem(review, it.profile.toProfileJson(), it.appliedKeys, it.defaultedKeys)
                }
            }
        }.getOrElse { OrcaImportItem(review, null, problem = it.message ?: "invalid_preset") }
    } + archive.unrecognizedEntries.map { name ->
        OrcaImportItem(
            OrcaPresetReview(name, "archive-entry", null, emptySet(), false,
                problem = "unrecognized_archive_entry"),
            converted = null,
        )
    }
}

/** Produces a bundle for the existing atomic importer only after explicit selection. */
internal fun orcaImportSelectionReady(
    items: List<OrcaImportItem>, selected: Set<Int>, acknowledged: Set<Int>,
): Boolean = selected.isNotEmpty() && selected.all { index ->
    val item = items.getOrNull(index) ?: return@all false
    item.problem == null && item.converted != null &&
        ((item.review.unsupportedKeys.isEmpty() && item.defaultedKeys.isEmpty()) || index in acknowledged)
}

internal fun selectedOrcaImportBundle(
    items: List<OrcaImportItem>, selected: Set<Int>, acknowledgedWarnings: Set<Int>,
): ByteArray {
    require(selected.isNotEmpty() && selected.all { it in items.indices }) { "invalid_import_selection" }
    val root = JSONObject().put("printers", JSONArray()).put("filaments", JSONArray()).put("slicing", JSONArray())
    selected.sorted().forEach { index ->
        val item = items[index]
        require(item.problem == null && item.converted != null) { "unresolved_import_item" }
        require((item.review.unsupportedKeys.isEmpty() && item.defaultedKeys.isEmpty()) || index in acknowledgedWarnings) {
            "import_warnings_not_acknowledged"
        }
        val section = when (item.review.kind) { "machine" -> "printers"; "filament" -> "filaments"; else -> "slicing" }
        root.getJSONArray(section).put(JSONObject(item.converted.toString()))
    }
    return encodeProfileBundle(root)
}

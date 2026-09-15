package com.ashcastle.duckyslicer

import org.json.JSONObject

internal data class OrcaPresetReview(
    val name: String,
    val kind: String,
    val resolved: JSONObject?,
    val unsupportedKeys: Set<String>,
    val conflictsWithSavedName: Boolean,
    val problem: String? = null,
)

/** Resolves an import without writing profiles or silently substituting defaults. */
internal fun reviewOrcaPresets(
    incoming: List<JSONObject>,
    parents: List<JSONObject>,
    supportedKeys: Map<String, Set<String>>,
    savedNames: Set<Pair<String, String>>,
): List<OrcaPresetReview> {
    require(incoming.size + parents.size <= 10_000) { "preset_limit" }
    val records = (parents + incoming).map { JSONObject(it.toString()) }
    fun key(record: JSONObject) = record.optString("type") to record.optString("name")
    val index = records.groupBy(::key)
    val metadata = setOf("name", "type", "inherits", "from", "version", "instantiation", "setting_id")

    fun resolve(record: JSONObject, path: Set<Pair<String, String>>): JSONObject {
        val identity = key(record)
        require(identity.first in setOf("machine", "filament", "process")) { "unknown_preset_type" }
        require(identity.second.isNotBlank()) { "missing_preset_name" }
        require(identity !in path && path.size < 64) { "cyclic_preset_inheritance" }
        require(index[identity]?.size == 1) { "ambiguous_preset_name" }
        val parentName = record.optString("inherits").trim()
        val result = if (parentName.isEmpty()) JSONObject() else {
            val candidates = index[identity.first to parentName].orEmpty()
            require(candidates.isNotEmpty()) { "missing_parent:$parentName" }
            require(candidates.size == 1) { "ambiguous_parent:$parentName" }
            resolve(candidates.single(), path + identity)
        }
        record.keys().forEach { name -> result.put(name, record.get(name)) }
        return result
    }

    return records.takeLast(incoming.size).map { record ->
        val identity = key(record)
        val resolution = runCatching { resolve(record, emptySet()) }
        val resolved = resolution.getOrNull()
        OrcaPresetReview(
            name = identity.second,
            kind = identity.first,
            resolved = resolved,
            unsupportedKeys = resolved?.keys()?.asSequence()?.filter {
                it !in metadata && it !in supportedKeys[identity.first].orEmpty()
            }?.toSortedSet().orEmpty(),
            conflictsWithSavedName = identity in savedNames,
            problem = resolution.exceptionOrNull()?.message,
        )
    }
}

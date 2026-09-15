package com.ashcastle.duckyslicer

import org.json.JSONArray
import org.json.JSONObject

/** Preserve explicit compatibility; conditional expressions require a separate resolver. */
internal fun copyOrcaPresetCompatibility(raw: JSONObject, output: JSONObject): Set<String> {
    val applied = mutableSetOf<String>()
    listOf("compatible_printers" to "compatiblePrinters", "compatible_prints" to "compatiblePrints")
        .forEach { (source, target) ->
            if (raw.has(source)) {
                val entries = raw.get(source)
                require(entries is JSONArray && entries.length() <= 10_000) { "invalid_compatibility:$source" }
                val names = (0 until entries.length()).map { index ->
                    val name = entries.get(index)
                    require(name is String && name.isNotBlank() && name.length <= 512) {
                        "invalid_compatibility:$source"
                    }
                    name
                }.distinct()
                output.put(target, JSONArray(names))
                applied += source
            }
        }
    return applied
}

package com.ashcastle.duckyslicer

import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject

/** Only the requested ancestor closure reaches the converter, not the full catalog. */
internal fun readOrcaBundledParents(input: InputStream, incoming: List<JSONObject>): List<JSONObject> {
    fun key(record: JSONObject) = record.optString("type") to record.optString("name")
    val index = mutableMapOf<Pair<String, String>, MutableList<String>>()
    GZIPInputStream(input).bufferedReader().useLines { lines ->
        var expanded = 0L
        var count = 0
        lines.forEach { line ->
            expanded += line.length
            require(++count <= 50_000 && expanded <= 64 * 1024 * 1024) { "bundled_preset_limit" }
            if (line.isNotBlank()) {
                val record = JSONObject(line)
                index.getOrPut(key(record)) { mutableListOf() }.add(line)
            }
        }
    }
    val supplied = incoming.map(::key).toSet()
    val visited = mutableSetOf<Pair<String, String>>()
    val pending = ArrayDeque<JSONObject>().apply { addAll(incoming) }
    val result = mutableListOf<JSONObject>()
    while (pending.isNotEmpty()) {
        val record = pending.removeFirst()
        val name = record.optString("inherits").trim()
        val identity = record.optString("type") to name
        if (name.isEmpty() || identity in supplied || !visited.add(identity)) continue
        index[identity].orEmpty().forEach { raw ->
            val parent = JSONObject(raw)
            result += parent
            require(result.size + incoming.size <= 10_000) { "preset_limit" }
            pending.addLast(parent)
        }
    }
    return result
}

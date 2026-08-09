package com.ashcastle.duckyslicer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

internal fun parseBoundedJsonObject(
    bytes: ByteArray,
    maximumBytes: Int,
    maximumDepth: Int = 64,
): JSONObject {
    require(bytes.size in 1..maximumBytes) { "json_size_invalid" }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val source = decoder.decode(ByteBuffer.wrap(bytes)).toString()
    requireJsonDepth(source, maximumDepth)
    return JSONObject(source)
}

private fun requireJsonDepth(source: String, maximumDepth: Int) {
    require(maximumDepth > 0)
    var depth = 0
    var inString = false
    var escaped = false
    source.forEach { character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
        } else {
            when (character) {
                '"' -> inString = true
                '{', '[' -> {
                    depth += 1
                    require(depth <= maximumDepth) { "json_nesting_too_deep" }
                }
                '}', ']' -> {
                    depth -= 1
                    require(depth >= 0) { "json_nesting_invalid" }
                }
            }
        }
    }
    require(!inString && depth == 0) { "json_structure_invalid" }
}

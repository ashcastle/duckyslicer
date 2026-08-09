package com.ashcastle.duckyslicer

import java.io.InputStream
import java.io.OutputStream

internal const val MAX_MODEL_IMPORT_BYTES = 512L * 1024L * 1024L

internal class ModelTooLargeException : IllegalArgumentException("model_too_large")

internal fun copyModelWithLimit(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_MODEL_IMPORT_BYTES,
): Long {
    require(maxBytes >= 0) { "maxBytes must be non-negative" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (count.toLong() > maxBytes - total) throw ModelTooLargeException()
        output.write(buffer, 0, count)
        total += count
    }
    return total
}

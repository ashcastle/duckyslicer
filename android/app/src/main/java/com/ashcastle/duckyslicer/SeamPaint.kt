package com.ashcastle.duckyslicer

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

enum class SeamPaintState(val code: Int) {
    ENFORCE(1),
    BLOCK(2),
    ;

    companion object {
        fun fromCode(code: Int): SeamPaintState? = entries.firstOrNull { it.code == code }
    }
}

data class SeamPaint(
    val facets: Map<Int, SeamPaintState> = emptyMap(),
) {
    fun paint(facetIndex: Int, state: SeamPaintState?): SeamPaint {
        require(facetIndex >= 0) { "Facet index must be non-negative" }
        val next = facets.toMutableMap()
        if (state == null) next.remove(facetIndex) else next[facetIndex] = state
        require(next.size <= MAX_PAINTED_FACETS) { "Seam paint is too large" }
        return if (next == facets) this else SeamPaint(next.toSortedMap())
    }

    fun writeSidecar(output: File) {
        require(facets.size <= MAX_PAINTED_FACETS) { "Seam paint is too large" }
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writer.writeInt(facets.size)
                facets.toSortedMap().forEach { (facetIndex, state) ->
                    require(facetIndex >= 0) { "Facet index must be non-negative" }
                    writer.writeInt(facetIndex)
                    writer.writeByte(state.code)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == HEADER_BYTES + facets.size.toLong() * ENTRY_BYTES) {
            "Seam paint could not be stored"
        }
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
        const val MAX_PAINTED_FACETS = 100_000
        const val HEADER_BYTES = 8L
        const val ENTRY_BYTES = 5L
        const val MAX_SIDECAR_BYTES = HEADER_BYTES + MAX_PAINTED_FACETS.toLong() * ENTRY_BYTES
    }
}

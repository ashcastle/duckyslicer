package com.ashcastle.duckyslicer

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

data class MultiColorPaint(
    val facets: Map<Int, Int> = emptyMap(),
) {
    init {
        require(facets.size <= MAX_PAINTED_FACETS) { "Multi-color paint is too large" }
        require(facets.keys.all { it >= 0 }) { "Painted facet index is invalid" }
        require(facets.values.all { it in 0 until MAX_FILAMENT_SLOTS }) {
            "Painted filament slot is invalid"
        }
    }

    fun paint(facetIndex: Int, filamentSlot: Int?): MultiColorPaint {
        require(facetIndex >= 0) { "Facet index must be non-negative" }
        require(filamentSlot == null || filamentSlot in 0 until MAX_FILAMENT_SLOTS) {
            "Filament slot is invalid"
        }
        val next = facets.toMutableMap()
        if (filamentSlot == null) next.remove(facetIndex) else next[facetIndex] = filamentSlot
        require(next.size <= MAX_PAINTED_FACETS) { "Multi-color paint is too large" }
        return if (next == facets) this else MultiColorPaint(next.toSortedMap())
    }

    fun constrainedToSlotCount(slotCount: Int): MultiColorPaint {
        require(slotCount in 1..MAX_FILAMENT_SLOTS) { "Filament slot count is invalid" }
        val retained = facets.filterValues { it < slotCount }
        return if (retained.size == facets.size) this else MultiColorPaint(retained.toSortedMap())
    }

    fun writeSidecar(output: File) {
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writer.writeInt(facets.size)
                facets.toSortedMap().forEach { (facetIndex, filamentSlot) ->
                    writer.writeInt(facetIndex)
                    writer.writeByte(filamentSlot + 1)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == HEADER_BYTES + facets.size.toLong() * ENTRY_BYTES) {
            "Multi-color paint could not be stored"
        }
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'M'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
        const val MAX_PAINTED_FACETS = 100_000
        const val HEADER_BYTES = 8L
        const val ENTRY_BYTES = 5L
        const val MAX_SIDECAR_BYTES = HEADER_BYTES + MAX_PAINTED_FACETS.toLong() * ENTRY_BYTES
    }
}

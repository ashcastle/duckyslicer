package com.ashcastle.duckyslicer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONArray

/**
 * Lossless Orca/BBS facet annotation payloads. Unlike DuckySlicer's editable paint maps,
 * these strings may describe recursively split portions of one source triangle.
 */
data class OrcaFacetAnnotation(
    val triangles: Map<Int, String> = emptyMap(),
) {
    init {
        require(triangles.size <= MAX_ANNOTATED_TRIANGLES) { "Facet annotation is too large" }
        triangles.forEach { (triangleIndex, value) ->
            require(triangleIndex >= 0) { "Facet annotation triangle is invalid" }
            validateTriangleValue(value)
        }
    }

    val maximumState: Int by lazy(LazyThreadSafetyMode.NONE) {
        triangles.values.maxOfOrNull(::maximumTriangleState) ?: 0
    }

    fun constrainedToTriangleCount(triangleCount: Int): OrcaFacetAnnotation {
        require(triangles.keys.all { it in 0 until triangleCount }) {
            "Facet annotation references unavailable geometry"
        }
        return this
    }

    fun writeSidecar(file: File) {
        require(triangles.isNotEmpty()) { "Empty facet annotation sidecars are unnecessary" }
        var encodedBytes = HEADER_BYTES.toLong()
        val sorted = triangles.toSortedMap()
        sorted.forEach { (_, value) ->
            encodedBytes += ENTRY_HEADER_BYTES + value.length
            require(encodedBytes <= MAX_SIDECAR_BYTES) { "Facet annotation is too large" }
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.write(MAGIC)
            output.writeInt(sorted.size)
            sorted.forEach { (triangleIndex, value) ->
                output.writeInt(triangleIndex)
                output.writeInt(value.length)
                output.write(value.toByteArray(Charsets.US_ASCII))
            }
            output.flush()
        }
        require(file.length() == encodedBytes) { "Facet annotation sidecar was not written" }
    }

    internal fun toJson(): JSONArray = JSONArray().also { values ->
        triangles.toSortedMap().forEach { (triangleIndex, value) ->
            values.put(triangleIndex)
            values.put(value)
        }
    }

    companion object {
        const val MAX_ANNOTATED_TRIANGLES = 100_000
        const val MAX_TRIANGLE_VALUE_BYTES = 4_096
        const val MAX_SIDECAR_BYTES = 8 * 1_024 * 1_024
        private const val HEADER_BYTES = 8
        private const val ENTRY_HEADER_BYTES = 8L
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'O'.code.toByte(), 'A'.code.toByte(), '1'.code.toByte())

        fun fromJson(values: JSONArray, triangleCount: Int): OrcaFacetAnnotation {
            require(values.length() % 2 == 0 && values.length() / 2 <= MAX_ANNOTATED_TRIANGLES) {
                "Invalid facet annotation"
            }
            val triangles = LinkedHashMap<Int, String>(values.length() / 2)
            var previousIndex = -1
            for (offset in 0 until values.length() step 2) {
                val triangleIndex = values.getInt(offset)
                val value = values.getString(offset + 1)
                require(triangleIndex in 0 until triangleCount && triangleIndex > previousIndex) {
                    "Invalid facet annotation triangle"
                }
                validateTriangleValue(value)
                triangles[triangleIndex] = value
                previousIndex = triangleIndex
            }
            return OrcaFacetAnnotation(triangles)
        }

        fun isValidJson(values: JSONArray): Boolean = runCatching {
            require(values.length() % 2 == 0 && values.length() / 2 <= MAX_ANNOTATED_TRIANGLES)
            var previousIndex = -1
            for (offset in 0 until values.length() step 2) {
                val triangleIndex = values.getInt(offset)
                require(triangleIndex >= 0 && triangleIndex > previousIndex)
                validateTriangleValue(values.getString(offset + 1))
                previousIndex = triangleIndex
            }
        }.isSuccess

        fun readSidecar(file: File): OrcaFacetAnnotation {
            require(file.isFile && file.length() in HEADER_BYTES.toLong()..MAX_SIDECAR_BYTES.toLong()) {
                "Facet annotation sidecar is invalid"
            }
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Facet annotation sidecar is invalid" }
                val count = input.readInt()
                require(count in 0..MAX_ANNOTATED_TRIANGLES) { "Facet annotation sidecar is invalid" }
                val triangles = LinkedHashMap<Int, String>(count)
                var previousIndex = -1
                var consumed = HEADER_BYTES.toLong()
                repeat(count) {
                    val triangleIndex = input.readInt()
                    val valueLength = input.readInt()
                    require(
                        triangleIndex >= 0 && triangleIndex > previousIndex &&
                            valueLength in 1..MAX_TRIANGLE_VALUE_BYTES,
                    ) { "Facet annotation sidecar is invalid" }
                    consumed += ENTRY_HEADER_BYTES + valueLength
                    require(consumed <= file.length() && consumed <= MAX_SIDECAR_BYTES) {
                        "Facet annotation sidecar is invalid"
                    }
                    val bytes = ByteArray(valueLength)
                    input.readFully(bytes)
                    val value = bytes.toString(Charsets.US_ASCII)
                    validateTriangleValue(value)
                    triangles[triangleIndex] = value
                    previousIndex = triangleIndex
                }
                require(consumed == file.length() && input.read() == -1) {
                    "Facet annotation sidecar is invalid"
                }
                return OrcaFacetAnnotation(triangles)
            }
        }

        private fun validateTriangleValue(value: String) {
            require(value.length in 1..MAX_TRIANGLE_VALUE_BYTES && value.all { it in '0'..'9' || it in 'A'..'F' }) {
                "Facet annotation value is invalid"
            }
            maximumTriangleState(value)
        }

        /** Validates the complete serialized split tree and returns its largest leaf state. */
        private fun maximumTriangleState(value: String): Int {
            var cursor = value.lastIndex
            var pendingNodes = 1
            var maximumState = 0
            while (pendingNodes > 0) {
                require(cursor >= 0) { "Facet annotation split tree is incomplete" }
                val code = value[cursor--].digitToInt(16)
                pendingNodes -= 1
                val splitSides = code and 0b11
                if (splitSides != 0) {
                    val specialSide = code ushr 2
                    require(
                        (splitSides == 3 && specialSide == 0) ||
                            (splitSides < 3 && specialSide in 0..2),
                    ) { "Facet annotation split side is invalid" }
                    pendingNodes += splitSides + 1
                    require(pendingNodes <= MAX_TRIANGLE_VALUE_BYTES * 4) {
                        "Facet annotation split tree is too deep"
                    }
                    continue
                }
                var state = code ushr 2
                if ((code and 0b1100) == 0b1100) {
                    var extensions = 0
                    var next: Int
                    do {
                        require(cursor >= 0) { "Facet annotation state is incomplete" }
                        next = value[cursor--].digitToInt(16)
                        if (next == 0xF) extensions += 1
                        require(extensions <= 16) { "Facet annotation state is invalid" }
                    } while (next == 0xF)
                    state = next + 15 * extensions + 3
                }
                require(state <= 255) { "Facet annotation state is invalid" }
                maximumState = maxOf(maximumState, state)
            }
            require(cursor == -1) { "Facet annotation split tree has trailing data" }
            return maximumState
        }
    }
}

data class OrcaFacetAnnotations(
    val support: OrcaFacetAnnotation = OrcaFacetAnnotation(),
    val seam: OrcaFacetAnnotation = OrcaFacetAnnotation(),
    val multiColor: OrcaFacetAnnotation = OrcaFacetAnnotation(),
) {
    val isEmpty: Boolean
        get() = support.triangles.isEmpty() && seam.triangles.isEmpty() && multiColor.triangles.isEmpty()
}

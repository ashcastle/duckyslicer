package com.ashcastle.duckyslicer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Orca process overrides applied only between two object-relative Z heights. */
data class HeightRangeModifier(
    val startZmm: Float,
    val endZmm: Float,
    val overrides: ObjectProcessOverrides,
) {
    init {
        require(startZmm.isFinite() && endZmm.isFinite()) { "Height range is invalid" }
        require(startZmm in 0f..HeightRangeModifiers.MAX_Z_MM) { "Height range start is invalid" }
        require(endZmm in 0f..HeightRangeModifiers.MAX_Z_MM) { "Height range end is invalid" }
        require(endZmm - startZmm >= HeightRangeModifiers.MIN_RANGE_MM) {
            "Height range is too small"
        }
        require(!overrides.isEmpty) { "Height range settings are empty" }
    }
}

data class HeightRangeModifiers(
    val ranges: List<HeightRangeModifier> = emptyList(),
) {
    init {
        require(ranges.size <= MAX_RANGES) { "Too many height ranges" }
        require(ranges.zipWithNext().all { (first, second) ->
            first.startZmm <= second.startZmm && first.endZmm <= second.startZmm
        }) { "Height ranges overlap or are not sorted" }
    }

    fun writeSidecar(output: File) {
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writer.writeInt(ranges.size)
                ranges.forEach { range ->
                    writer.writeFloat(range.startZmm)
                    writer.writeFloat(range.endZmm)
                    range.overrides.writePayload(writer)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == sidecarBytes(ranges.size)) {
            "Height range settings could not be stored"
        }
    }

    internal fun constrainedToHeight(heightMm: Float): HeightRangeModifiers {
        require(heightMm.isFinite() && heightMm >= MIN_RANGE_MM) { "Object height is invalid" }
        require(ranges.all { it.endZmm <= heightMm + HEIGHT_TOLERANCE_MM }) {
            "Height range exceeds the object"
        }
        return this
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'H'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
        const val MAX_RANGES = 32
        const val MIN_RANGE_MM = 0.01f
        const val MAX_Z_MM = 10_000f
        const val HEADER_BYTES = 8L
        const val ENTRY_BYTES = 8L + ObjectProcessOverrides.PAYLOAD_BYTES
        private const val HEIGHT_TOLERANCE_MM = 0.01f

        fun sidecarBytes(count: Int): Long {
            require(count in 0..MAX_RANGES)
            return HEADER_BYTES + count * ENTRY_BYTES
        }

        internal fun readSidecar(input: File): HeightRangeModifiers {
            require(input.isFile && input.length() in HEADER_BYTES..sidecarBytes(MAX_RANGES)) {
                "Height range settings are unavailable"
            }
            return DataInputStream(BufferedInputStream(input.inputStream())).use { reader ->
                val magic = ByteArray(MAGIC.size)
                reader.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Height range settings format is invalid" }
                val count = reader.readInt()
                require(count in 1..MAX_RANGES && input.length() == sidecarBytes(count)) {
                    "Height range setting count is invalid"
                }
                HeightRangeModifiers(
                    List(count) {
                        HeightRangeModifier(
                            startZmm = reader.readFloat(),
                            endZmm = reader.readFloat(),
                            overrides = ObjectProcessOverrides.readPayload(reader),
                        )
                    },
                )
            }
        }
    }
}

internal fun HeightRangeModifiers.toProjectJson(): JSONArray = JSONArray().also { values ->
    ranges.forEach { range ->
        values.put(
            JSONObject()
                .put("startZmm", range.startZmm.toDouble())
                .put("endZmm", range.endZmm.toDouble())
                .put("overrides", range.overrides.toProjectJson()),
        )
    }
}

internal fun JSONArray.toHeightRangeModifiers(): HeightRangeModifiers = HeightRangeModifiers(
    List(length()) { index ->
        getJSONObject(index).let { value ->
            HeightRangeModifier(
                startZmm = value.requiredFiniteFloat("startZmm"),
                endZmm = value.requiredFiniteFloat("endZmm"),
                overrides = value.getJSONObject("overrides").toObjectProcessOverrides(),
            )
        }
    },
)

private fun JSONObject.requiredFiniteFloat(key: String): Float {
    val value = (get(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)
        ?: throw IllegalArgumentException("Invalid height range setting")
    return value.toFloat().takeIf(Float::isFinite)
        ?: throw IllegalArgumentException("Invalid height range setting")
}

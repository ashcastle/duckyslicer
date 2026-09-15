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
    val filamentSlot: Int? = null,
) {
    init {
        require(startZmm.isFinite() && endZmm.isFinite()) { "Height range is invalid" }
        require(startZmm in 0f..HeightRangeModifiers.MAX_Z_MM) { "Height range start is invalid" }
        require(endZmm in 0f..HeightRangeModifiers.MAX_Z_MM) { "Height range end is invalid" }
        require(endZmm - startZmm >= HeightRangeModifiers.MIN_RANGE_MM) {
            "Height range is too small"
        }
        require(filamentSlot == null || filamentSlot in 0 until MAX_FILAMENT_SLOTS)
        require(!overrides.isEmpty || filamentSlot != null) { "Height range settings are empty" }
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
        val withColor = ranges.any { it.filamentSlot != null }
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(if (withColor) COLOR_MAGIC else MAGIC)
                writer.writeInt(ranges.size)
                ranges.forEach { range ->
                    writer.writeFloat(range.startZmm)
                    writer.writeFloat(range.endZmm)
                    range.overrides.writePayload(writer)
                    if (withColor) writer.writeInt(range.filamentSlot?.plus(1) ?: 0)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == sidecarBytes(ranges.size, withColor)) {
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

    internal fun resolvedForSlicing(layerHeightMm: Float): HeightRangeModifiers {
        require(layerHeightMm.isFinite() && layerHeightMm in
            ObjectProcessOverrides.MIN_LAYER_HEIGHT_MM..ObjectProcessOverrides.MAX_LAYER_HEIGHT_MM)
        return copy(ranges = ranges.map { range ->
            if (range.overrides.layerHeightMm != null) range else range.copy(
                overrides = range.overrides.copy(layerHeightMm = layerHeightMm),
            )
        })
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'H'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())
        val COLOR_MAGIC = byteArrayOf('D'.code.toByte(), 'H'.code.toByte(), 'R'.code.toByte(), '2'.code.toByte())
        const val MAX_RANGES = 32
        const val MIN_RANGE_MM = 0.01f
        const val MAX_Z_MM = 10_000f
        const val HEADER_BYTES = 8L
        const val ENTRY_BYTES = 8L + ObjectProcessOverrides.PAYLOAD_BYTES
        private const val HEIGHT_TOLERANCE_MM = 0.01f

        fun sidecarBytes(count: Int, withColor: Boolean = false): Long {
            require(count in 0..MAX_RANGES)
            return HEADER_BYTES + count * (ENTRY_BYTES + if (withColor) 4L else 0L)
        }

        internal fun readSidecar(input: File): HeightRangeModifiers {
            require(input.isFile && input.length() in HEADER_BYTES..sidecarBytes(MAX_RANGES, true)) {
                "Height range settings are unavailable"
            }
            return DataInputStream(BufferedInputStream(input.inputStream())).use { reader ->
                val magic = ByteArray(MAGIC.size)
                reader.readFully(magic)
                val withColor = magic.contentEquals(COLOR_MAGIC)
                require(withColor || magic.contentEquals(MAGIC)) { "Height range settings format is invalid" }
                val count = reader.readInt()
                require(count in 1..MAX_RANGES && input.length() == sidecarBytes(count, withColor)) {
                    "Height range setting count is invalid"
                }
                HeightRangeModifiers(
                    List(count) {
                        HeightRangeModifier(
                            startZmm = reader.readFloat(),
                            endZmm = reader.readFloat(),
                            overrides = ObjectProcessOverrides.readPayload(reader, allowEmpty = withColor),
                            filamentSlot = if (withColor) reader.readInt().let {
                                require(it in 0..MAX_FILAMENT_SLOTS)
                                if (it == 0) null else it - 1
                            } else null,
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
                .put("overrides", range.overrides.toProjectJson())
                .apply { range.filamentSlot?.let { put("filamentSlot", it) } },
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
                filamentSlot = if (value.has("filamentSlot")) {
                    val number = value.get("filamentSlot") as? Number
                        ?: throw IllegalArgumentException("Invalid height range filament")
                    number.toInt().also { require(it.toDouble() == number.toDouble()) }
                } else null,
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

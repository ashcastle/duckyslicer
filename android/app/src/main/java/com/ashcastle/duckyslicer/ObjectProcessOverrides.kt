package com.ashcastle.duckyslicer

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** OrcaSlicer process values that override the selected process profile for one object. */
data class ObjectProcessOverrides(
    val layerHeightMm: Float? = null,
    val wallLoops: Int? = null,
    val topShellLayers: Int? = null,
    val bottomShellLayers: Int? = null,
    val sparseInfillDensityPercent: Float? = null,
    val outerWallSpeedMmS: Float? = null,
    val innerWallSpeedMmS: Float? = null,
    val sparseInfillSpeedMmS: Float? = null,
    val supportEnabled: Boolean? = null,
) {
    init {
        require(layerHeightMm == null || layerHeightMm.isFinite() && layerHeightMm in MIN_LAYER_HEIGHT_MM..MAX_LAYER_HEIGHT_MM) {
            "Object layer height is invalid"
        }
        require(wallLoops == null || wallLoops in MIN_WALL_LOOPS..MAX_WALL_LOOPS) {
            "Object wall count is invalid"
        }
        require(topShellLayers == null || topShellLayers in MIN_SHELL_LAYERS..MAX_SHELL_LAYERS) {
            "Object top shell count is invalid"
        }
        require(bottomShellLayers == null || bottomShellLayers in MIN_SHELL_LAYERS..MAX_SHELL_LAYERS) {
            "Object bottom shell count is invalid"
        }
        require(
            sparseInfillDensityPercent == null ||
                sparseInfillDensityPercent.isFinite() &&
                sparseInfillDensityPercent in MIN_INFILL_PERCENT..MAX_INFILL_PERCENT,
        ) { "Object infill density is invalid" }
        listOf(outerWallSpeedMmS, innerWallSpeedMmS, sparseInfillSpeedMmS).forEach { speed ->
            require(speed == null || speed.isFinite() && speed in MIN_SPEED_MM_S..MAX_SPEED_MM_S) {
                "Object speed is invalid"
            }
        }
    }

    val isEmpty: Boolean
        get() = layerHeightMm == null && wallLoops == null && topShellLayers == null &&
            bottomShellLayers == null && sparseInfillDensityPercent == null &&
            outerWallSpeedMmS == null && innerWallSpeedMmS == null &&
            sparseInfillSpeedMmS == null && supportEnabled == null

    fun writeSidecar(output: File) {
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writePayload(writer)
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == SIDECAR_BYTES) { "Object settings could not be stored" }
    }

    internal val mask: Int
        get() = (if (layerHeightMm != null) LAYER_HEIGHT_BIT else 0) or
            (if (wallLoops != null) WALL_LOOPS_BIT else 0) or
            (if (topShellLayers != null) TOP_SHELL_LAYERS_BIT else 0) or
            (if (bottomShellLayers != null) BOTTOM_SHELL_LAYERS_BIT else 0) or
            (if (sparseInfillDensityPercent != null) INFILL_DENSITY_BIT else 0) or
            (if (outerWallSpeedMmS != null) OUTER_WALL_SPEED_BIT else 0) or
            (if (innerWallSpeedMmS != null) INNER_WALL_SPEED_BIT else 0) or
            (if (sparseInfillSpeedMmS != null) INFILL_SPEED_BIT else 0) or
            (if (supportEnabled != null) SUPPORT_ENABLED_BIT else 0)

    internal fun writePayload(writer: DataOutputStream) {
        writer.writeInt(mask)
        writer.writeFloat(layerHeightMm ?: 0f)
        writer.writeInt(wallLoops ?: 0)
        writer.writeInt(topShellLayers ?: 0)
        writer.writeInt(bottomShellLayers ?: 0)
        writer.writeFloat(sparseInfillDensityPercent ?: 0f)
        writer.writeFloat(outerWallSpeedMmS ?: 0f)
        writer.writeFloat(innerWallSpeedMmS ?: 0f)
        writer.writeFloat(sparseInfillSpeedMmS ?: 0f)
        writer.writeByte(if (supportEnabled == true) 1 else 0)
    }

    companion object {
        val MAGIC = byteArrayOf('D'.code.toByte(), 'P'.code.toByte(), 'O'.code.toByte(), '1'.code.toByte())
        const val LAYER_HEIGHT_BIT = 1 shl 0
        const val WALL_LOOPS_BIT = 1 shl 1
        const val TOP_SHELL_LAYERS_BIT = 1 shl 2
        const val BOTTOM_SHELL_LAYERS_BIT = 1 shl 3
        const val INFILL_DENSITY_BIT = 1 shl 4
        const val OUTER_WALL_SPEED_BIT = 1 shl 5
        const val INNER_WALL_SPEED_BIT = 1 shl 6
        const val INFILL_SPEED_BIT = 1 shl 7
        const val SUPPORT_ENABLED_BIT = 1 shl 8
        const val ALL_BITS = (1 shl 9) - 1
        const val PAYLOAD_BYTES = 37L
        const val SIDECAR_BYTES = 41L

        const val MIN_LAYER_HEIGHT_MM = 0.01f
        const val MAX_LAYER_HEIGHT_MM = 2f
        const val MIN_WALL_LOOPS = 0
        const val MAX_WALL_LOOPS = 20
        const val MIN_SHELL_LAYERS = 0
        const val MAX_SHELL_LAYERS = 100
        const val MIN_INFILL_PERCENT = 0f
        const val MAX_INFILL_PERCENT = 100f
        const val MIN_SPEED_MM_S = 1f
        const val MAX_SPEED_MM_S = 1_000f

        internal fun readPayload(reader: DataInputStream): ObjectProcessOverrides {
            val mask = reader.readInt()
            require(mask != 0 && mask and ALL_BITS == mask) {
                "Object setting mask is invalid"
            }
            return ObjectProcessOverrides(
                layerHeightMm = reader.readFloat().takeIf { mask and LAYER_HEIGHT_BIT != 0 },
                wallLoops = reader.readInt().takeIf { mask and WALL_LOOPS_BIT != 0 },
                topShellLayers = reader.readInt().takeIf {
                    mask and TOP_SHELL_LAYERS_BIT != 0
                },
                bottomShellLayers = reader.readInt().takeIf {
                    mask and BOTTOM_SHELL_LAYERS_BIT != 0
                },
                sparseInfillDensityPercent = reader.readFloat().takeIf {
                    mask and INFILL_DENSITY_BIT != 0
                },
                outerWallSpeedMmS = reader.readFloat().takeIf {
                    mask and OUTER_WALL_SPEED_BIT != 0
                },
                innerWallSpeedMmS = reader.readFloat().takeIf {
                    mask and INNER_WALL_SPEED_BIT != 0
                },
                sparseInfillSpeedMmS = reader.readFloat().takeIf {
                    mask and INFILL_SPEED_BIT != 0
                },
                supportEnabled = reader.readUnsignedByte().let { enabled ->
                    require(enabled in 0..1) { "Object support setting is invalid" }
                    (enabled == 1).takeIf { mask and SUPPORT_ENABLED_BIT != 0 }
                },
            ).also { require(!it.isEmpty) { "Object settings are empty" } }
        }
    }
}

internal fun ObjectProcessOverrides.toProjectJson(): JSONObject = JSONObject().apply {
    layerHeightMm?.let { put("layerHeightMm", it.toDouble()) }
    wallLoops?.let { put("wallLoops", it) }
    topShellLayers?.let { put("topShellLayers", it) }
    bottomShellLayers?.let { put("bottomShellLayers", it) }
    sparseInfillDensityPercent?.let { put("sparseInfillDensityPercent", it.toDouble()) }
    outerWallSpeedMmS?.let { put("outerWallSpeedMmS", it.toDouble()) }
    innerWallSpeedMmS?.let { put("innerWallSpeedMmS", it.toDouble()) }
    sparseInfillSpeedMmS?.let { put("sparseInfillSpeedMmS", it.toDouble()) }
    supportEnabled?.let { put("supportEnabled", it) }
}

internal fun JSONObject.toObjectProcessOverrides(): ObjectProcessOverrides = ObjectProcessOverrides(
    layerHeightMm = optionalFiniteFloat("layerHeightMm"),
    wallLoops = optionalStrictInt("wallLoops"),
    topShellLayers = optionalStrictInt("topShellLayers"),
    bottomShellLayers = optionalStrictInt("bottomShellLayers"),
    sparseInfillDensityPercent = optionalFiniteFloat("sparseInfillDensityPercent"),
    outerWallSpeedMmS = optionalFiniteFloat("outerWallSpeedMmS"),
    innerWallSpeedMmS = optionalFiniteFloat("innerWallSpeedMmS"),
    sparseInfillSpeedMmS = optionalFiniteFloat("sparseInfillSpeedMmS"),
    supportEnabled = if (has("supportEnabled")) {
        get("supportEnabled") as? Boolean ?: throw IllegalArgumentException("Invalid object support setting")
    } else {
        null
    },
)

private fun JSONObject.optionalFiniteFloat(key: String): Float? {
    if (!has(key)) return null
    val value = (get(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)
        ?: throw IllegalArgumentException("Invalid object setting")
    return value.toFloat().takeIf(Float::isFinite)
        ?: throw IllegalArgumentException("Invalid object setting")
}

private fun JSONObject.optionalStrictInt(key: String): Int? {
    if (!has(key)) return null
    val number = get(key) as? Number ?: throw IllegalArgumentException("Invalid object setting")
    val value = number.toLong()
    require(number.toDouble() == value.toDouble() && value in Int.MIN_VALUE..Int.MAX_VALUE) {
        "Invalid object setting"
    }
    return value.toInt()
}

package com.ashcastle.duckyslicer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

/** Orca's semantic role for one mesh inside a project object. */
enum class ProjectVolumeRole(val nativeValue: Int) {
    MODEL_PART(0),
    NEGATIVE_VOLUME(1),
    PARAMETER_MODIFIER(2),
    SUPPORT_BLOCKER(3),
    SUPPORT_ENFORCER(4),
    ;

    val acceptsFilament: Boolean
        get() = this == MODEL_PART || this == PARAMETER_MODIFIER

    val acceptsFacetPaint: Boolean
        get() = this == MODEL_PART

    companion object {
        fun fromNative(value: Int): ProjectVolumeRole = entries.firstOrNull {
            it.nativeValue == value
        } ?: throw IllegalArgumentException("Invalid project volume role")
    }
}

/**
 * Exact Orca volume overrides imported from a 3MF modifier volume.
 *
 * Values remain serialized Orca configuration strings. The native boundary deserializes them
 * through Orca's own ModelConfigObject, so this layer neither guesses their types nor broadens
 * the configuration surface. Tight aggregate limits keep projects, Binder requests, and native
 * parsing bounded.
 */
data class ProjectVolumeConfig(
    val values: Map<String, String> = emptyMap(),
) {
    init {
        require(values.size <= MAX_ENTRIES) { "Too many project volume settings" }
        var encodedBytes = HEADER_BYTES
        values.forEach { (key, value) ->
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)
            require(keyBytes.size in 1..MAX_KEY_BYTES && CONFIG_KEY.matches(key)) {
                "Invalid project volume setting name"
            }
            require(valueBytes.size <= MAX_VALUE_BYTES && value.indexOf('\u0000') < 0) {
                "Invalid project volume setting value"
            }
            encodedBytes += ENTRY_HEADER_BYTES + keyBytes.size + valueBytes.size
            require(encodedBytes <= MAX_SIDECAR_BYTES) { "Project volume settings are too large" }
        }
    }

    val isEmpty: Boolean get() = values.isEmpty()

    val encodedBytes: Int
        get() = HEADER_BYTES + values.entries.sumOf { (key, value) ->
            ENTRY_HEADER_BYTES + key.toByteArray(Charsets.UTF_8).size +
                value.toByteArray(Charsets.UTF_8).size
        }

    fun writeSidecar(output: File) {
        FileOutputStream(output).use { fileStream ->
            DataOutputStream(BufferedOutputStream(fileStream)).use { writer ->
                writer.write(MAGIC)
                writer.writeInt(values.size)
                values.toSortedMap().forEach { (key, value) ->
                    val keyBytes = key.toByteArray(Charsets.UTF_8)
                    val valueBytes = value.toByteArray(Charsets.UTF_8)
                    writer.writeInt(keyBytes.size)
                    writer.writeInt(valueBytes.size)
                    writer.write(keyBytes)
                    writer.write(valueBytes)
                }
                writer.flush()
                fileStream.fd.sync()
            }
        }
        check(output.length() == encodedBytes.toLong()) {
            "Project volume settings could not be stored"
        }
    }

    internal fun toJson(): JSONObject = JSONObject().also { output ->
        values.toSortedMap().forEach(output::put)
    }

    companion object {
        private val MAGIC = byteArrayOf(
            'D'.code.toByte(),
            'V'.code.toByte(),
            'C'.code.toByte(),
            '1'.code.toByte(),
        )
        private val CONFIG_KEY = Regex("[a-z][a-z0-9_]{0,127}")
        private const val HEADER_BYTES = 8
        private const val ENTRY_HEADER_BYTES = 8
        const val MAX_ENTRIES = 128
        const val MAX_KEY_BYTES = 128
        const val MAX_VALUE_BYTES = 4 * 1_024
        const val MAX_SIDECAR_BYTES = 64 * 1_024

        fun readSidecar(input: File): ProjectVolumeConfig {
            require(input.isFile && input.length() in HEADER_BYTES.toLong()..MAX_SIDECAR_BYTES.toLong()) {
                "Project volume settings are unavailable"
            }
            return DataInputStream(BufferedInputStream(FileInputStream(input))).use { reader ->
                val magic = ByteArray(MAGIC.size)
                reader.readFully(magic)
                require(magic.contentEquals(MAGIC)) { "Invalid project volume settings" }
                val count = reader.readInt()
                require(count in 0..MAX_ENTRIES) { "Invalid project volume setting count" }
                val values = LinkedHashMap<String, String>(count)
                repeat(count) {
                    val keyLength = reader.readInt()
                    val valueLength = reader.readInt()
                    require(keyLength in 1..MAX_KEY_BYTES && valueLength in 0..MAX_VALUE_BYTES) {
                        "Invalid project volume setting length"
                    }
                    val key = decodeUtf8(reader.readExact(keyLength))
                    val value = decodeUtf8(reader.readExact(valueLength))
                    require(values.put(key, value) == null) { "Duplicate project volume setting" }
                }
                require(reader.read() == -1) { "Unexpected project volume setting data" }
                ProjectVolumeConfig(values)
            }
        }

        internal fun fromJson(value: JSONObject?): ProjectVolumeConfig {
            if (value == null) return ProjectVolumeConfig()
            val names = value.keys().asSequence().toList()
            require(names.size <= MAX_ENTRIES) { "Too many project volume settings" }
            return ProjectVolumeConfig(
                names.associateWith { key ->
                    value.get(key) as? String
                        ?: throw IllegalArgumentException("Invalid project volume setting")
                },
            )
        }

        private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

        private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also {
            try {
                readFully(it)
            } catch (failure: EOFException) {
                throw IllegalArgumentException("Truncated project volume settings", failure)
            }
        }
    }
}

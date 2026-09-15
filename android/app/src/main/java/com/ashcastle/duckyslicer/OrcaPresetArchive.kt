package com.ashcastle.duckyslicer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal data class OrcaPresetArchive(
    val presets: List<JSONObject>,
    val unrecognizedEntries: List<String>,
)

/** Reads data only. No archive path is ever extracted to the filesystem. */
internal fun readOrcaPresetArchive(bytes: ByteArray): OrcaPresetArchive {
    require(bytes.size in 1..MAX_PROFILE_BUNDLE_BYTES) { "preset_archive_size" }
    val presets = mutableListOf<JSONObject>()
    val ignored = mutableListOf<String>()
    fun accept(name: String, data: ByteArray) {
        val root = parseBoundedJsonObject(data, MAX_PROFILE_BUNDLE_BYTES)
        val kind = root.optString("type")
        if (kind in setOf("machine", "filament", "process") && root.optString("name").isNotBlank()) {
            presets += root
        } else {
            ignored += name
        }
    }
    if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4b.toByte()) {
        accept("profile.json", bytes)
    } else {
        var expanded = 0L
        var entryCount = 0
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entryCount <= 10_000) { "preset_archive_entry_limit" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && ':' !in name && name.split('/').none { it == ".." }) {
                    "preset_archive_unsafe_path"
                }
                require(names.add(name)) { "preset_archive_duplicate_entry" }
                val json = !entry.isDirectory && name.lowercase(Locale.ROOT).endsWith(".json")
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= MAX_PROFILE_BUNDLE_BYTES) { "preset_archive_expanded_size" }
                    if (json) output.write(buffer, 0, count)
                }
                if (json) accept(name, output.toByteArray()) else if (!entry.isDirectory) ignored += name
                zip.closeEntry()
            }
        }
    }
    require(presets.isNotEmpty()) { "preset_archive_no_profiles" }
    return OrcaPresetArchive(presets, ignored)
}

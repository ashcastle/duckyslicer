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
    val documents = linkedMapOf<String, JSONObject>()
    fun accept(name: String, data: ByteArray) {
        documents[name] = parseBoundedJsonObject(data, MAX_PROFILE_BUNDLE_BYTES)
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
    // Orca exports user JSON without `type`. Its bundle manifest is written last,
    // and standalone presets are identified by their settings-id keys.
    val kinds = mutableMapOf<String, String>()
    fun register(path: String, kind: String) {
        require(path in documents && path != "bundle_structure.json") { "preset_bundle_missing_entry" }
        require(kinds.put(path, kind).let { it == null || it == kind }) { "preset_bundle_conflicting_type" }
    }
    documents["bundle_structure.json"]?.let { manifest ->
        fun paths(key: String, kind: String, source: JSONObject = manifest) {
            val values = source.optJSONArray(key) ?: return
            for (i in 0 until values.length()) register(values.getString(i), kind)
        }
        when (manifest.optString("bundle_type")) {
            "printer config bundle" -> {
                paths("printer_config", "machine")
                paths("filament_config", "filament")
                paths("process_config", "process")
            }
            "filament config bundle" -> {
                val vendors = manifest.optJSONArray("printer_vendor")
                if (vendors != null) for (i in 0 until vendors.length()) {
                    paths("filament_path", "filament", vendors.getJSONObject(i))
                }
            }
        }
    }
    documents.forEach { (name, root) ->
        if (name == "bundle_structure.json" && root.optString("bundle_type") in
            setOf("printer config bundle", "filament config bundle")) return@forEach
        val identifiers = mapOf("printer_settings_id" to "machine",
            "filament_settings_id" to "filament", "print_settings_id" to "process")
            .filterKeys(root::has).values.toSet()
        val declared = root.optString("type").takeIf { it.isNotBlank() }
        val candidates = (identifiers + listOfNotNull(declared, kinds[name])).toSet()
        require(candidates.size <= 1) { "preset_bundle_conflicting_type" }
        val kind = candidates.singleOrNull()
        if (kind in setOf("machine", "filament", "process") && root.optString("name").isNotBlank()) {
            root.put("type", kind)
            presets += root
        } else ignored += name
    }
    require(presets.isNotEmpty()) { "preset_archive_no_profiles" }
    return OrcaPresetArchive(presets, ignored)
}

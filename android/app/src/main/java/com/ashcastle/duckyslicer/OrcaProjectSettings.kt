package com.ashcastle.duckyslicer

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/** Reads only the project configuration; geometry is still imported by the native engine. */
internal fun readOrcaProjectSettings(
    archive: File,
    cancellationRequested: () -> Boolean = { false },
): JSONObject? = ZipFile(archive).use { zip ->
    var count = 0
    var settings: java.util.zip.ZipEntry? = null
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        if (cancellationRequested()) throw ProjectEditCancelledException()
        require(++count <= 100_000) { "project_archive_entry_limit" }
        val entry = entries.nextElement()
        if (entry.name == "Metadata/project_settings.config") {
            require(settings == null && !entry.isDirectory) { "ambiguous_project_settings" }
            settings = entry
        }
    }
    val entry = settings ?: return@use null
    require(entry.size <= MAX_PROFILE_BUNDLE_BYTES) { "project_settings_size" }
    val bytes = zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (cancellationRequested()) throw ProjectEditCancelledException()
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size().toLong() + read <= MAX_PROFILE_BUNDLE_BYTES) { "project_settings_size" }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
    parseBoundedJsonObject(bytes, MAX_PROFILE_BUNDLE_BYTES)
}

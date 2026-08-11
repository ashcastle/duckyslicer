package com.ashcastle.duckyslicer

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONArray
import org.json.JSONObject

internal const val PROFILE_BUNDLE_FILE_EXTENSION = ".duckyprofiles"
internal const val PROFILE_BUNDLE_MIME_TYPE = "application/vnd.duckyslicer.profiles+json"
// The stored root is capped at 16 MiB; the envelope and added indentation need headroom.
internal const val MAX_PROFILE_BUNDLE_BYTES = 24 * 1_024 * 1_024

internal data class ProfileBundleImportResult(
    val importedPrinters: Int,
    val importedFilaments: Int,
    val importedSlicing: Int,
    val skippedDuplicates: Int,
) {
    val importedTotal: Int
        get() = importedPrinters + importedFilaments + importedSlicing
}

internal data class ProfileBundleMerge(
    val root: JSONObject,
    val result: ProfileBundleImportResult,
)

internal fun encodeProfileBundle(userProfiles: JSONObject): ByteArray {
    val profiles = decodeUserProfiles(userProfiles)
    val bundle = JSONObject()
        .put("type", PROFILE_BUNDLE_TYPE)
        .put("bundleVersion", PROFILE_BUNDLE_VERSION)
        .put("profileSchemaVersion", PROFILE_BUNDLE_PROFILE_SCHEMA_VERSION)
        .put("profiles", profiles.toJsonRoot(includeSchema = false))
    return bundle.toString(2).toByteArray(Charsets.UTF_8).also { bytes ->
        require(bytes.size in 1..MAX_PROFILE_BUNDLE_BYTES) { "profile_bundle_too_large" }
    }
}

internal fun mergeProfileBundle(
    currentRoot: JSONObject,
    bundleBytes: ByteArray,
    newUserId: () -> String,
): ProfileBundleMerge {
    val current = decodeUserProfiles(currentRoot)
    val incoming = decodeProfileBundle(bundleBytes)

    var skipped = 0
    val printers = current.printers.toMutableList()
    val printerIds = printers.mapTo(mutableSetOf(), PrinterProfile::id)
    val portablePrinters = printers.associateByTo(
        mutableMapOf(),
        PrinterProfile::portableProfile,
    )
    val importedPrinterIds = mutableMapOf<String, String>()
    var importedPrinters = 0
    incoming.printers.forEach { source ->
        val sanitized = source.copy(builtIn = false)
        val portable = sanitized.portableProfile()
        val existing = portablePrinters[portable]
        if (existing != null) {
            importedPrinterIds[source.id] = existing.id
            skipped += 1
        } else {
            val imported = sanitized.copy(id = importedId(sanitized.id, printerIds, newUserId))
            printers += imported
            portablePrinters[portable] = imported
            importedPrinterIds[source.id] = imported.id
            importedPrinters += 1
        }
    }

    val filaments = current.filaments.toMutableList()
    val filamentIds = filaments.mapTo(mutableSetOf(), FilamentProfile::id)
    val portableFilaments = filaments.mapTo(mutableSetOf()) { it.portableProfile() }
    var importedFilaments = 0
    incoming.filaments.forEach { source ->
        val sanitized = source.copy(
            builtIn = false,
            compatiblePrinters = source.compatiblePrinters.remapPrinterIds(importedPrinterIds),
        )
        if (!portableFilaments.add(sanitized.portableProfile())) {
            skipped += 1
        } else {
            filaments += sanitized.copy(id = importedId(sanitized.id, filamentIds, newUserId))
            importedFilaments += 1
        }
    }

    val slicing = current.slicing.toMutableList()
    val slicingIds = slicing.mapTo(mutableSetOf(), QualityProfile::id)
    val portableSlicing = slicing.mapTo(mutableSetOf()) { it.portableProfile() }
    var importedSlicing = 0
    incoming.slicing.forEach { source ->
        val sanitized = source.copy(
            builtIn = false,
            compatiblePrinters = source.compatiblePrinters.remapPrinterIds(importedPrinterIds),
        )
        if (!portableSlicing.add(sanitized.portableProfile())) {
            skipped += 1
        } else {
            slicing += sanitized.copy(id = importedId(sanitized.id, slicingIds, newUserId))
            importedSlicing += 1
        }
    }

    val merged = UserProfileBundle(printers, filaments, slicing)
    require(merged.total <= MAX_USER_PROFILES) { "profile_bundle_profile_limit" }
    return ProfileBundleMerge(
        root = merged.toJsonRoot(includeSchema = true),
        result = ProfileBundleImportResult(
            importedPrinters = importedPrinters,
            importedFilaments = importedFilaments,
            importedSlicing = importedSlicing,
            skippedDuplicates = skipped,
        ),
    )
}

internal fun readProfileBundleBytes(
    input: InputStream,
    cancellation: DocumentTransferCancellation,
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(PROFILE_BUNDLE_BUFFER_BYTES)
    while (true) {
        cancellation.throwIfRequested()
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_PROFILE_BUNDLE_BYTES) { "profile_bundle_too_large" }
        output.write(buffer, 0, count)
    }
    cancellation.throwIfRequested()
    return output.toByteArray().also { bytes ->
        require(bytes.isNotEmpty()) { "profile_bundle_empty" }
    }
}

internal fun writeProfileBundleBytes(
    output: OutputStream,
    bytes: ByteArray,
    cancellation: DocumentTransferCancellation,
) {
    require(bytes.size in 1..MAX_PROFILE_BUNDLE_BYTES) { "profile_bundle_too_large" }
    var offset = 0
    while (offset < bytes.size) {
        cancellation.throwIfRequested()
        val count = minOf(PROFILE_BUNDLE_BUFFER_BYTES, bytes.size - offset)
        output.write(bytes, offset, count)
        offset += count
    }
    cancellation.throwIfRequested()
}

private data class UserProfileBundle(
    val printers: List<PrinterProfile>,
    val filaments: List<FilamentProfile>,
    val slicing: List<QualityProfile>,
) {
    val total: Int
        get() = printers.size + filaments.size + slicing.size

    fun toJsonRoot(includeSchema: Boolean): JSONObject = JSONObject().apply {
        if (includeSchema) put("schemaVersion", PROFILE_BUNDLE_PROFILE_SCHEMA_VERSION)
        put("printers", JSONArray().also { array ->
            printers.forEach { array.put(it.copy(builtIn = false).toProfileJson()) }
        })
        put("filaments", JSONArray().also { array ->
            filaments.forEach { array.put(it.copy(builtIn = false).toProfileJson()) }
        })
        put("slicing", JSONArray().also { array ->
            slicing.forEach { array.put(it.copy(builtIn = false).toProfileJson()) }
        })
    }
}

private fun decodeProfileBundle(bytes: ByteArray): UserProfileBundle {
    val root = parseBoundedJsonObject(bytes, MAX_PROFILE_BUNDLE_BYTES)
    require(root.keys().asSequence().toSet() == PROFILE_BUNDLE_KEYS) {
        "profile_bundle_fields_invalid"
    }
    require(root.optString("type") == PROFILE_BUNDLE_TYPE) { "profile_bundle_type_invalid" }
    require(root.optInt("bundleVersion", -1) == PROFILE_BUNDLE_VERSION) {
        "profile_bundle_version_unsupported"
    }
    require(root.optInt("profileSchemaVersion", -1) in 1..PROFILE_BUNDLE_PROFILE_SCHEMA_VERSION) {
        "profile_bundle_schema_unsupported"
    }
    val profiles = root.optJSONObject("profiles")
        ?: throw IllegalArgumentException("profile_bundle_profiles_missing")
    require(profiles.keys().asSequence().toSet() == PROFILE_ARRAY_KEYS) {
        "profile_bundle_profile_fields_invalid"
    }
    return decodeUserProfiles(profiles)
}

private fun decodeUserProfiles(root: JSONObject): UserProfileBundle {
    val printers = root.requireProfileArray("printers", JSONObject::toPrinterProfileOrNull)
    val filaments = root.requireProfileArray("filaments", JSONObject::toFilamentProfileOrNull)
    val slicing = root.requireProfileArray("slicing", JSONObject::toQualityProfileOrNull)
    return UserProfileBundle(printers, filaments, slicing).also { profiles ->
        require(profiles.total <= MAX_USER_PROFILES) {
            "profile_bundle_profile_limit"
        }
    }
}

private fun <T> JSONObject.requireProfileArray(
    key: String,
    parser: (JSONObject) -> T?,
): List<T> {
    val array = optJSONArray(key) ?: if (has(key)) {
        throw IllegalArgumentException("profile_bundle_array_invalid")
    } else {
        JSONArray()
    }
    val ids = mutableSetOf<String>()
    return List(array.length()) { index ->
        val value = array.optJSONObject(index)
            ?: throw IllegalArgumentException("profile_bundle_entry_invalid")
        val parsed = parser(value)
            ?: throw IllegalArgumentException("profile_bundle_profile_invalid")
        val safe = when (parsed) {
            is PrinterProfile -> ProfileValidation.printer(parsed)
            is FilamentProfile -> ProfileValidation.filament(parsed)
            is QualityProfile -> ProfileValidation.slicing(parsed)
            else -> false
        }
        require(safe) { "profile_bundle_profile_invalid" }
        val id = when (parsed) {
            is PrinterProfile -> parsed.id
            is FilamentProfile -> parsed.id
            is QualityProfile -> parsed.id
            else -> throw IllegalArgumentException("profile_bundle_profile_invalid")
        }
        require(ids.add(id)) { "profile_bundle_duplicate_id" }
        parsed
    }
}

private fun importedId(
    requested: String,
    used: MutableSet<String>,
    newUserId: () -> String,
): String {
    if (requested.startsWith(USER_PROFILE_ID_PREFIX) && used.add(requested)) return requested
    repeat(MAX_ID_GENERATION_ATTEMPTS) {
        val generated = newUserId()
        if (generated.startsWith(USER_PROFILE_ID_PREFIX) && used.add(generated)) return generated
    }
    throw IllegalStateException("profile_bundle_id_generation_failed")
}

private fun PrinterProfile.portableProfile(): PrinterProfile = copy(id = "", builtIn = false)

private fun FilamentProfile.portableProfile(): FilamentProfile = copy(id = "", builtIn = false)

private fun QualityProfile.portableProfile(): QualityProfile = copy(id = "", builtIn = false)

private fun List<String>.remapPrinterIds(importedPrinterIds: Map<String, String>): List<String> =
    map { importedPrinterIds[it] ?: it }.distinct()

private const val PROFILE_BUNDLE_TYPE = "com.ashcastle.duckyslicer.user-profiles"
private const val PROFILE_BUNDLE_VERSION = 1
private const val PROFILE_BUNDLE_PROFILE_SCHEMA_VERSION = USER_PROFILE_SCHEMA_VERSION
private const val PROFILE_BUNDLE_BUFFER_BYTES = 64 * 1_024
private const val USER_PROFILE_ID_PREFIX = "user-"
private const val MAX_ID_GENERATION_ATTEMPTS = 1_024
private val PROFILE_BUNDLE_KEYS = setOf(
    "type",
    "bundleVersion",
    "profileSchemaVersion",
    "profiles",
)
private val PROFILE_ARRAY_KEYS = setOf("printers", "filaments", "slicing")

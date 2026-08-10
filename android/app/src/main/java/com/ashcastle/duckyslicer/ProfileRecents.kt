package com.ashcastle.duckyslicer

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val RECENT_PROFILE_VERSION = 1
private const val MAX_RECENT_PROFILES = 5
private const val MAX_RECENT_PROFILE_BYTES = 8 * 1024

internal data class ProfileRecents(
    val printerIds: List<String> = emptyList(),
    val filamentIds: List<String> = emptyList(),
    val slicingIds: List<String> = emptyList(),
) {
    fun recordPrinter(id: String): ProfileRecents = copy(printerIds = printerIds.promote(id))

    fun recordFilament(id: String): ProfileRecents = copy(filamentIds = filamentIds.promote(id))

    fun recordSlicing(id: String): ProfileRecents = copy(slicingIds = slicingIds.promote(id))

    fun record(options: SliceOptions): ProfileRecents =
        recordPrinter(options.printerProfile.id)
            .recordFilament(options.filamentProfile.id)
            .recordSlicing(options.quality.id)
}

/** Stores bounded recent-profile identifiers in app-private storage. */
internal class ProfileRecentStore(private val file: File) {
    private val durable = DurableJsonFile(file, MAX_RECENT_PROFILE_BYTES)

    @Volatile
    var storageUnavailable: Boolean = false
        private set

    constructor(context: Context) : this(
        File(context.filesDir, "profiles/recent_profiles.json"),
    )

    @Synchronized
    fun load(): ProfileRecents {
        val result = durable.read(::parse, ::compatible)
        storageUnavailable = !result.status.mutationSafe
        return result.value ?: ProfileRecents()
    }

    @Synchronized
    fun save(recents: ProfileRecents) {
        check(!storageUnavailable) { "saved_data_unreadable" }
        durable.write(recents.toJson(), ::parse, ::compatible)
    }

    private fun compatible(root: JSONObject): Boolean =
        root.optInt("version", 0) <= RECENT_PROFILE_VERSION

    private fun parse(root: JSONObject): ProfileRecents? {
        if (root.optInt("version", 0) != RECENT_PROFILE_VERSION) return null
        return ProfileRecents(
            printerIds = root.optJSONArray("printers").toProfileIds() ?: return null,
            filamentIds = root.optJSONArray("filaments").toProfileIds() ?: return null,
            slicingIds = root.optJSONArray("slicing").toProfileIds() ?: return null,
        )
    }
}

private fun ProfileRecents.toJson(): JSONObject = JSONObject()
    .put("version", RECENT_PROFILE_VERSION)
    .put("printers", JSONArray(printerIds))
    .put("filaments", JSONArray(filamentIds))
    .put("slicing", JSONArray(slicingIds))

private fun JSONArray?.toProfileIds(): List<String>? {
    if (this == null) return emptyList()
    if (length() > MAX_RECENT_PROFILES) return null
    return buildList(length()) {
        repeat(length()) {
            val id = optString(it, "")
            if (!id.isSafeProfileId() || id in this) return null
            add(id)
        }
    }
}

private fun List<String>.promote(id: String): List<String> {
    if (!id.isSafeProfileId()) return this
    return (listOf(id) + filterNot { it == id }).take(MAX_RECENT_PROFILES)
}

private fun String.isSafeProfileId(): Boolean =
    isNotBlank() && length <= 512 && none(Char::isISOControl)

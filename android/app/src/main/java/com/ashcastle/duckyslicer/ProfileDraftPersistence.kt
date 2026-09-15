package com.ashcastle.duckyslicer

import org.json.JSONObject

internal fun ProfileEditorState.encodeDraft(): String? {
    if (!session.isDirty) return null
    return JSONObject().put("version", 1).put("kind", kind.name)
        .put("opening", session.opening.toProjectJson())
        .put("working", session.working.toProjectJson()).toString()
}

internal fun restoreProfileDraft(value: String?, current: SliceOptions): ProfileEditorState? = runCatching {
    if (value == null || value.length > 4 * 1024 * 1024) return null
    val saved = JSONObject(value)
    if (saved.optInt("version", 1) != 1) return null
    val opening = saved.getJSONObject("opening").toProjectSliceOptionsOrNull() ?: return null
    val working = saved.getJSONObject("working").toProjectSliceOptionsOrNull() ?: return null
    val normalizedCurrent = current.toProjectJson().toProjectSliceOptionsOrNull() ?: return null
    if (opening != normalizedCurrent || opening == working) return null
    ProfileEditorState(ProfileSettingsKind.valueOf(saved.getString("kind")), ProfileEditSession(current, working))
}.getOrNull()

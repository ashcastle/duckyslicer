package com.ashcastle.duckyslicer

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

/** Keeps routine persistence, export, and window status effects outside the workspace root. */
@Composable
internal fun WorkspaceStatusEffects(
    gcodeExportState: GcodeExportState,
    gcodeExportModel: GcodeExportViewModel,
    persistenceMessage: ProjectPersistenceMessage?,
    projectRestored: Boolean,
    profileRecentsLoaded: Boolean,
    sliceOptions: SliceOptions,
    profileLibraryState: ProfileLibraryState,
    profileLibraryModel: ProfileLibraryViewModel,
    remoteProfilesLoaded: Boolean,
    remoteStorageUnavailable: Boolean,
    keepScreenAwake: Boolean,
    onPresentation: (String?, String?) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    LaunchedEffect(gcodeExportState.completion?.id) {
        val completion = gcodeExportState.completion ?: return@LaunchedEffect
        when (completion.result) {
            GcodeExportResult.SAVED -> onPresentation(
                resources.getString(R.string.gcode_saved),
                null,
            )
            GcodeExportResult.CANCELED -> onPresentation(
                resources.getString(R.string.gcode_export_canceled),
                null,
            )
            GcodeExportResult.FAILED -> onPresentation(
                null,
                resources.getString(R.string.save_error),
            )
        }
        gcodeExportModel.consumeCompletion(completion.id)
    }
    LaunchedEffect(persistenceMessage) {
        val message = when (persistenceMessage) {
            ProjectPersistenceMessage.STORAGE_UNAVAILABLE -> R.string.saved_data_unavailable
            ProjectPersistenceMessage.SAVE_FAILED -> R.string.project_save_error
            null -> return@LaunchedEffect
        }
        onPresentation(null, resources.getString(message))
    }
    LaunchedEffect(projectRestored, profileRecentsLoaded) {
        if (projectRestored && profileRecentsLoaded) profileLibraryModel.recordSelection(sliceOptions)
    }
    LaunchedEffect(profileLibraryState.message) {
        val message = profileLibraryState.message ?: return@LaunchedEffect
        val resource = when (message) {
            ProfileLibraryMessage.STORAGE_UNAVAILABLE -> R.string.saved_data_unavailable
            ProfileLibraryMessage.SAVE_FAILED -> R.string.profile_save_error
            ProfileLibraryMessage.DELETE_FAILED -> R.string.profile_delete_error
        }
        onPresentation(null, resources.getString(resource))
        profileLibraryModel.consumeMessage(message)
    }
    LaunchedEffect(remoteProfilesLoaded, remoteStorageUnavailable) {
        if (remoteProfilesLoaded && remoteStorageUnavailable) {
            onPresentation(null, resources.getString(R.string.saved_data_unavailable))
        }
    }
    DisposableEffect(keepScreenAwake) {
        val window = (context as? MainActivity)?.window
        if (keepScreenAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

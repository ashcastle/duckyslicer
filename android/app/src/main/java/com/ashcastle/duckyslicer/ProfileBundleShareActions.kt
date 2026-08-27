package com.ashcastle.duckyslicer

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

internal data class ProfileBundleShareActions(
    val pendingOperationId: Long?,
    val start: () -> Unit,
)

/** Owns one retained profile export until its Android Sharesheet handoff is complete. */
@Composable
internal fun rememberProfileBundleShareActions(
    model: ProfileLibraryViewModel,
    completion: ProfileTransferCompletion?,
    busy: Boolean,
    enabled: Boolean,
    onPresentation: (notice: String?, error: String?) -> Unit,
): ProfileBundleShareActions {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share_profiles)
    val shareError = stringResource(R.string.profile_share_error)
    var pendingOperationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(completion?.id, pendingOperationId) {
        val completed = completion ?: return@LaunchedEffect
        if (completed.id != pendingOperationId) return@LaunchedEffect
        val launched = if (completed.outcome == ProfileTransferOutcome.SUCCEEDED) {
            profileBundleShareIntentOrNull(context, pendingUri?.let(Uri::parse))
                ?.let { shareIntent ->
                    runCatching {
                        context.startActivity(Intent.createChooser(shareIntent, shareTitle))
                    }.isSuccess
                } ?: false
        } else {
            false
        }
        if (!launched) discardProfileBundleShare(context, pendingPath)
        if (completed.outcome == ProfileTransferOutcome.SUCCEEDED) {
            onPresentation(null, shareError.takeUnless { launched })
        }
        pendingOperationId = null
        pendingUri = null
        pendingPath = null
    }

    LaunchedEffect(pendingOperationId, completion, busy) {
        if (pendingOperationId != null && !busy && completion == null) {
            discardProfileBundleShare(context, pendingPath)
            pendingOperationId = null
            pendingUri = null
            pendingPath = null
        }
    }

    return ProfileBundleShareActions(
        pendingOperationId = pendingOperationId,
        start = {
            if (enabled) {
                val target = prepareProfileBundleShare(context)
                if (target != null && model.exportBundle(target.uri)) {
                    pendingOperationId = model.state.value.activeOperationId
                    pendingUri = target.uri.toString()
                    pendingPath = target.file.absolutePath
                    onPresentation(null, null)
                } else {
                    target?.let { discardProfileBundleShare(context, it.file.absolutePath) }
                    onPresentation(null, shareError)
                }
            }
        },
    )
}

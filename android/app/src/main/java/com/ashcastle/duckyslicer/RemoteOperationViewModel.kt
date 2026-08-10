package com.ashcastle.duckyslicer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class RemoteOperationMessage(val isError: Boolean = false) {
    CONNECTED,
    UPLOADED,
    STARTED,
    PAUSED,
    RESUMED,
    CANCELED,
    ACCESS_DENIED(isError = true),
    CONNECTION_FAILED(isError = true),
    COMMAND_FAILED(isError = true),
}

internal data class RemoteStatusSnapshot(
    val profileId: String,
    val status: RemoteDeviceStatus,
)

internal data class RemoteOperationState(
    val operationId: Long = 0,
    val busy: Boolean = false,
    val activeProfileId: String? = null,
    val artifactRevision: Long = 0,
    val activeArtifactRevision: Long? = null,
    val status: RemoteStatusSnapshot? = null,
    val upload: RemoteUpload? = null,
    val uploadProgress: Int? = null,
    val messageProfileId: String? = null,
    val message: RemoteOperationMessage? = null,
) {
    fun statusFor(profileId: String?): RemoteDeviceStatus? = status
        ?.takeIf { remoteResultBelongsToSelection(it.profileId, profileId) }
        ?.status

    fun uploadFor(profileId: String?): RemoteUpload? = upload
        ?.takeIf { remoteResultBelongsToSelection(it.profileId, profileId) }

    fun progressFor(profileId: String?): Int? = uploadProgress.takeIf {
        busy && remoteResultBelongsToSelection(activeProfileId.orEmpty(), profileId)
    }

    fun messageFor(profileId: String?): RemoteOperationMessage? = message.takeIf {
        remoteResultBelongsToSelection(messageProfileId.orEmpty(), profileId)
    }
}

internal sealed interface RemoteOperationOutcome {
    data class Refreshed(val status: RemoteDeviceStatus) : RemoteOperationOutcome
    data class Uploaded(val upload: RemoteUpload) : RemoteOperationOutcome
    data class Commanded(
        val state: String,
        val message: RemoteOperationMessage,
    ) : RemoteOperationOutcome

    data class Failed(
        val message: RemoteOperationMessage,
        val clearStatus: Boolean,
    ) : RemoteOperationOutcome
}

internal fun RemoteOperationState.beginRemoteOperation(
    nextOperationId: Long,
    profileId: String,
    uploadOperation: Boolean = false,
): RemoteOperationState {
    require(!busy) { "remote_operation_busy" }
    return copy(
        operationId = nextOperationId,
        busy = true,
        activeProfileId = profileId,
        activeArtifactRevision = artifactRevision.takeIf { uploadOperation },
        uploadProgress = 0.takeIf { uploadOperation },
        messageProfileId = null,
        message = null,
    )
}

internal fun RemoteOperationState.withRemoteUploadProgress(
    expectedOperationId: Long,
    profileId: String,
    progress: Int,
): RemoteOperationState {
    if (
        !busy || operationId != expectedOperationId || activeProfileId != profileId ||
        activeArtifactRevision == null || activeArtifactRevision != artifactRevision
    ) {
        return this
    }
    return copy(uploadProgress = maxOf(uploadProgress ?: 0, progress.coerceIn(0, 100)))
}

internal fun RemoteOperationState.finishRemoteOperation(
    expectedOperationId: Long,
    profileId: String,
    outcome: RemoteOperationOutcome,
): RemoteOperationState {
    if (!busy || operationId != expectedOperationId || activeProfileId != profileId) return this
    val uploadBecameStale = outcome is RemoteOperationOutcome.Uploaded &&
        activeArtifactRevision != artifactRevision
    val settled = copy(
        busy = false,
        activeProfileId = null,
        activeArtifactRevision = null,
        uploadProgress = null,
        messageProfileId = profileId.takeUnless { uploadBecameStale },
        message = null,
    )
    if (uploadBecameStale) return settled
    return when (outcome) {
        is RemoteOperationOutcome.Refreshed -> settled.copy(
            status = RemoteStatusSnapshot(profileId, outcome.status),
            message = RemoteOperationMessage.CONNECTED,
        )
        is RemoteOperationOutcome.Uploaded -> settled.copy(
            upload = outcome.upload,
            message = RemoteOperationMessage.UPLOADED,
        )
        is RemoteOperationOutcome.Commanded -> {
            val previous = status?.takeIf { it.profileId == profileId }?.status
            settled.copy(
                status = RemoteStatusSnapshot(
                    profileId,
                    (previous ?: RemoteDeviceStatus(outcome.state)).copy(
                        state = outcome.state,
                        fileName = previous?.fileName ?: upload?.displayName,
                    ),
                ),
                message = outcome.message,
            )
        }
        is RemoteOperationOutcome.Failed -> settled.copy(
            status = status?.takeUnless { outcome.clearStatus && it.profileId == profileId },
            message = outcome.message,
        )
    }
}

internal fun RemoteOperationState.invalidateRemoteUpload(): RemoteOperationState = copy(
    artifactRevision = artifactRevision + 1,
    upload = null,
    uploadProgress = null,
    messageProfileId = messageProfileId.takeUnless { message == RemoteOperationMessage.UPLOADED },
    message = message.takeUnless { it == RemoteOperationMessage.UPLOADED },
)

internal fun RemoteOperationState.changeRemoteSelection(profileId: String): RemoteOperationState {
    if (busy) return this
    return copy(
        status = null,
        upload = upload?.takeIf { it.profileId == profileId },
        uploadProgress = null,
        messageProfileId = null,
        message = null,
    )
}

internal fun RemoteOperationState.forgetRemoteProfile(profileId: String): RemoteOperationState = copy(
    status = status?.takeUnless { it.profileId == profileId },
    upload = upload?.takeUnless { it.profileId == profileId },
    messageProfileId = messageProfileId?.takeUnless { it == profileId },
    message = message.takeUnless { messageProfileId == profileId },
)

internal class RemoteOperationViewModel(application: Application) : AndroidViewModel(application) {
    private val remoteDeviceStore = RemoteDeviceStore(application)
    private val supportEvents = SupportEventJournal(application)
    private val mutableState = MutableStateFlow(RemoteOperationState())
    val state: StateFlow<RemoteOperationState> = mutableState.asStateFlow()
    private var nextOperationId = 0L

    fun refresh(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = launchOperation(
        profile = profile,
        timeoutSeconds = timeoutSeconds,
        clearStatusOnFailure = true,
    ) { client, credential, _ ->
        RemoteOperationOutcome.Refreshed(client.status(profile, credential))
    }

    fun upload(
        profile: RemoteDeviceProfile,
        output: File,
        timeoutSeconds: Int,
    ): Boolean = launchOperation(
        profile = profile,
        timeoutSeconds = timeoutSeconds,
        uploadOperation = true,
    ) { client, credential, onProgress ->
        RemoteOperationOutcome.Uploaded(
            client.upload(profile, credential, output, onProgress),
        )
    }

    fun start(
        profile: RemoteDeviceProfile,
        upload: RemoteUpload,
        timeoutSeconds: Int,
    ): Boolean {
        if (upload.profileId != profile.id) return false
        return command(
            profile,
            timeoutSeconds,
            resultingState = "printing",
            message = RemoteOperationMessage.STARTED,
        ) { client, credential -> client.start(profile, credential, upload) }
    }

    fun pause(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = command(
        profile,
        timeoutSeconds,
        resultingState = "paused",
        message = RemoteOperationMessage.PAUSED,
    ) { client, credential -> client.pause(profile, credential) }

    fun resume(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = command(
        profile,
        timeoutSeconds,
        resultingState = "printing",
        message = RemoteOperationMessage.RESUMED,
    ) { client, credential -> client.resume(profile, credential) }

    fun cancel(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = command(
        profile,
        timeoutSeconds,
        resultingState = "canceled",
        message = RemoteOperationMessage.CANCELED,
    ) { client, credential -> client.cancel(profile, credential) }

    fun invalidateUpload() {
        mutableState.update(RemoteOperationState::invalidateRemoteUpload)
    }

    fun selectionChanged(profileId: String) {
        mutableState.update { it.changeRemoteSelection(profileId) }
    }

    fun forgetProfile(profileId: String) {
        mutableState.update { it.forgetRemoteProfile(profileId) }
    }

    private fun command(
        profile: RemoteDeviceProfile,
        timeoutSeconds: Int,
        resultingState: String,
        message: RemoteOperationMessage,
        operation: (RemoteDeviceClient, String) -> Unit,
    ): Boolean = launchOperation(
        profile = profile,
        timeoutSeconds = timeoutSeconds,
        commandOperation = true,
    ) { client, credential, _ ->
        operation(client, credential)
        RemoteOperationOutcome.Commanded(resultingState, message)
    }

    @Synchronized
    private fun launchOperation(
        profile: RemoteDeviceProfile,
        timeoutSeconds: Int,
        uploadOperation: Boolean = false,
        commandOperation: Boolean = false,
        clearStatusOnFailure: Boolean = false,
        operation: (
            RemoteDeviceClient,
            String,
            (Int) -> Unit,
        ) -> RemoteOperationOutcome,
    ): Boolean {
        if (mutableState.value.busy) return false
        val operationId = ++nextOperationId
        mutableState.value = mutableState.value.beginRemoteOperation(
            operationId,
            profile.id,
            uploadOperation,
        )
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val client = RemoteDeviceClient(timeoutSeconds.coerceIn(5, 60) * 1_000)
                    val credential = remoteDeviceStore.credential(profile)
                    operation(client, credential) { progress ->
                        mutableState.update {
                            it.withRemoteUploadProgress(operationId, profile.id, progress)
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val unauthorized = failure is RemoteDeviceException &&
                    failure.statusCode in setOf(401, 403)
                supportEvents.record(
                    when {
                        unauthorized -> SupportEvent.REMOTE_AUTH_FAILED
                        commandOperation -> SupportEvent.REMOTE_COMMAND_FAILED
                        else -> SupportEvent.REMOTE_CONNECTION_FAILED
                    },
                )
                RemoteOperationOutcome.Failed(
                    message = when {
                        unauthorized -> RemoteOperationMessage.ACCESS_DENIED
                        commandOperation && failure is RemoteDeviceException -> {
                            RemoteOperationMessage.COMMAND_FAILED
                        }
                        else -> RemoteOperationMessage.CONNECTION_FAILED
                    },
                    clearStatus = clearStatusOnFailure,
                )
            }
            mutableState.update {
                it.finishRemoteOperation(operationId, profile.id, outcome)
            }
        }
        return true
    }
}

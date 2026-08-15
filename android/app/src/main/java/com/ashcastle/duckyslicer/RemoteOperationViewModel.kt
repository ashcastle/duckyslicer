package com.ashcastle.duckyslicer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.atomic.AtomicReference
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
    UPLOAD_CANCELED,
    REQUEST_CANCELED,
    PROFILE_SAVED,
    PROFILE_DELETED,
    ACCESS_DENIED(isError = true),
    CONNECTION_FAILED(isError = true),
    COMMAND_FAILED(isError = true),
    PROFILE_SAVE_FAILED(isError = true),
    STORAGE_UNAVAILABLE(isError = true),
}

internal enum class RemoteNetworkOperationKind {
    REFRESH,
    UPLOAD,
    COMMAND,
}

internal data class RemoteStatusSnapshot(
    val profileId: String,
    val status: RemoteDeviceStatus,
)

internal data class RemoteOperationState(
    val operationId: Long = 0,
    val busy: Boolean = false,
    val activeProfileId: String? = null,
    val activeNetworkOperation: RemoteNetworkOperationKind? = null,
    val artifactRevision: Long = 0,
    val activeArtifactRevision: Long? = null,
    val cancellationRequested: Boolean = false,
    val profiles: List<RemoteDeviceProfile> = emptyList(),
    val profilesLoaded: Boolean = false,
    val storageUnavailable: Boolean = false,
    val selectedProfileId: String? = null,
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
        uploadActiveFor(profileId)
    }

    fun networkRequestActiveFor(profileId: String?): Boolean =
        busy && activeNetworkOperation != null &&
            remoteResultBelongsToSelection(activeProfileId.orEmpty(), profileId)

    fun uploadActiveFor(profileId: String?): Boolean =
        networkRequestActiveFor(profileId) &&
            activeNetworkOperation == RemoteNetworkOperationKind.UPLOAD &&
            activeArtifactRevision != null

    fun requestCancellationRequestedFor(profileId: String?): Boolean =
        cancellationRequested && networkRequestActiveFor(profileId)

    fun messageFor(profileId: String?): RemoteOperationMessage? = message.takeIf {
        messageProfileId == null ||
            remoteResultBelongsToSelection(messageProfileId.orEmpty(), profileId)
    }

    fun selectedProfile(): RemoteDeviceProfile? = profiles.firstOrNull {
        it.id == selectedProfileId
    }
}

internal sealed interface RemoteOperationOutcome {
    data class Refreshed(val status: RemoteDeviceStatus) : RemoteOperationOutcome
    data class Uploaded(val upload: RemoteUpload) : RemoteOperationOutcome
    data class RequestCanceled(val kind: RemoteNetworkOperationKind) : RemoteOperationOutcome
    data class Commanded(
        val state: String,
        val message: RemoteOperationMessage,
    ) : RemoteOperationOutcome

    data class ProfileSaved(
        val saved: RemoteDeviceProfile,
        val profiles: List<RemoteDeviceProfile>,
    ) : RemoteOperationOutcome

    data class ProfileDeleted(
        val deletedProfileId: String,
        val profiles: List<RemoteDeviceProfile>,
    ) : RemoteOperationOutcome

    data class Failed(
        val message: RemoteOperationMessage,
        val clearStatus: Boolean,
    ) : RemoteOperationOutcome
}

internal fun RemoteOperationState.beginRemoteOperation(
    nextOperationId: Long,
    profileId: String,
    networkOperation: RemoteNetworkOperationKind? = null,
): RemoteOperationState {
    require(!busy) { "remote_operation_busy" }
    return copy(
        operationId = nextOperationId,
        busy = true,
        activeProfileId = profileId,
        activeNetworkOperation = networkOperation,
        activeArtifactRevision = artifactRevision.takeIf {
            networkOperation == RemoteNetworkOperationKind.UPLOAD
        },
        cancellationRequested = false,
        uploadProgress = 0.takeIf { networkOperation == RemoteNetworkOperationKind.UPLOAD },
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
        activeArtifactRevision == null || activeArtifactRevision != artifactRevision ||
        cancellationRequested
    ) {
        return this
    }
    return copy(uploadProgress = maxOf(uploadProgress ?: 0, progress.coerceIn(0, 100)))
}

internal fun RemoteOperationState.withRemoteRequestCancellationRequested(
    expectedOperationId: Long,
    profileId: String,
): RemoteOperationState {
    if (
        !busy || operationId != expectedOperationId || activeProfileId != profileId ||
        activeNetworkOperation == null || cancellationRequested
    ) {
        return this
    }
    return copy(cancellationRequested = true, uploadProgress = null)
}

internal fun RemoteOperationState.finishRemoteOperation(
    expectedOperationId: Long,
    profileId: String,
    outcome: RemoteOperationOutcome,
): RemoteOperationState {
    if (!busy || operationId != expectedOperationId || activeProfileId != profileId) return this
    val operationKind = activeNetworkOperation
    val uploadBecameStale = operationKind == RemoteNetworkOperationKind.UPLOAD &&
        activeArtifactRevision != null &&
        activeArtifactRevision != artifactRevision
    val effectiveOutcome = if (
        cancellationRequested && operationKind != null &&
        outcome !is RemoteOperationOutcome.RequestCanceled
    ) {
        RemoteOperationOutcome.RequestCanceled(operationKind)
    } else {
        outcome
    }
    val explicitCancellation = effectiveOutcome is RemoteOperationOutcome.RequestCanceled &&
        effectiveOutcome.kind == operationKind && cancellationRequested && !uploadBecameStale
    val settled = copy(
        busy = false,
        activeProfileId = null,
        activeNetworkOperation = null,
        activeArtifactRevision = null,
        cancellationRequested = false,
        uploadProgress = null,
        messageProfileId = profileId.takeUnless {
            uploadBecameStale || effectiveOutcome is RemoteOperationOutcome.RequestCanceled &&
                !explicitCancellation
        },
        message = null,
    )
    if (uploadBecameStale) return settled
    return when (effectiveOutcome) {
        is RemoteOperationOutcome.Refreshed -> settled.copy(
            status = RemoteStatusSnapshot(profileId, effectiveOutcome.status),
            message = RemoteOperationMessage.CONNECTED,
        )
        is RemoteOperationOutcome.Uploaded -> settled.copy(
            upload = effectiveOutcome.upload,
            message = RemoteOperationMessage.UPLOADED,
        )
        is RemoteOperationOutcome.RequestCanceled -> settled.copy(
            message = if (!explicitCancellation) {
                null
            } else if (effectiveOutcome.kind == RemoteNetworkOperationKind.UPLOAD) {
                RemoteOperationMessage.UPLOAD_CANCELED
            } else {
                RemoteOperationMessage.REQUEST_CANCELED
            },
        )
        is RemoteOperationOutcome.Commanded -> {
            val previous = status?.takeIf { it.profileId == profileId }?.status
            settled.copy(
                status = RemoteStatusSnapshot(
                    profileId,
                    (previous ?: RemoteDeviceStatus(effectiveOutcome.state)).copy(
                        state = effectiveOutcome.state,
                        fileName = previous?.fileName ?: upload?.displayName,
                    ),
                ),
                message = effectiveOutcome.message,
            )
        }
        is RemoteOperationOutcome.ProfileSaved -> settled.copy(
            profiles = effectiveOutcome.profiles,
            profilesLoaded = true,
            storageUnavailable = false,
            selectedProfileId = effectiveOutcome.saved.id,
            status = null,
            upload = null,
            messageProfileId = null,
            message = RemoteOperationMessage.PROFILE_SAVED,
        )
        is RemoteOperationOutcome.ProfileDeleted -> {
            val nextSelected = selectedProfileId
                ?.takeUnless { it == effectiveOutcome.deletedProfileId }
                ?.takeIf { selected -> effectiveOutcome.profiles.any { it.id == selected } }
                ?: effectiveOutcome.profiles.firstOrNull()?.id
            settled.copy(
                profiles = effectiveOutcome.profiles,
                profilesLoaded = true,
                storageUnavailable = false,
                selectedProfileId = nextSelected,
                status = status?.takeUnless {
                    it.profileId == effectiveOutcome.deletedProfileId
                },
                upload = upload?.takeUnless {
                    it.profileId == effectiveOutcome.deletedProfileId
                },
                messageProfileId = null,
                message = RemoteOperationMessage.PROFILE_DELETED,
            )
        }
        is RemoteOperationOutcome.Failed -> settled.copy(
            status = status?.takeUnless {
                effectiveOutcome.clearStatus && it.profileId == profileId
            },
            message = effectiveOutcome.message,
        )
    }
}

internal fun RemoteOperationState.invalidateRemoteUpload(): RemoteOperationState {
    val uploadMessage = message == RemoteOperationMessage.UPLOADED ||
        message == RemoteOperationMessage.UPLOAD_CANCELED
    return copy(
        artifactRevision = artifactRevision + 1,
        cancellationRequested = cancellationRequested ||
            (busy && activeNetworkOperation == RemoteNetworkOperationKind.UPLOAD),
        upload = null,
        uploadProgress = null,
        messageProfileId = messageProfileId.takeUnless { uploadMessage },
        message = message.takeUnless { uploadMessage },
    )
}

private data class ActiveRemoteRequest(
    val operationId: Long,
    val kind: RemoteNetworkOperationKind,
    val cancellation: RemoteRequestCancellation,
)

internal fun RemoteOperationState.changeRemoteSelection(profileId: String): RemoteOperationState {
    if (busy || profiles.none { it.id == profileId }) return this
    return copy(
        selectedProfileId = profileId,
        status = null,
        upload = upload?.takeIf { it.profileId == profileId },
        uploadProgress = null,
        messageProfileId = null,
        message = null,
    )
}

internal class RemoteOperationViewModel(application: Application) : AndroidViewModel(application) {
    private val remoteDeviceStore = RemoteDeviceStore(application)
    private val supportEvents = SupportEventJournal(application)
    private val mutableState = MutableStateFlow(
        RemoteOperationState().beginRemoteOperation(
            nextOperationId = 1,
            profileId = PROFILE_STORAGE_OPERATION,
        ),
    )
    val state: StateFlow<RemoteOperationState> = mutableState.asStateFlow()
    private var nextOperationId = 1L
    private val activeRemoteRequest = AtomicReference<ActiveRemoteRequest?>(null)

    init {
        loadProfiles(operationId = 1L)
    }

    fun saveProfile(draft: RemoteDeviceDraft): Boolean = launchProfileOperation(
        profileId = draft.id ?: NEW_PROFILE_OPERATION,
    ) {
        val saved = remoteDeviceStore.save(draft)
        RemoteOperationOutcome.ProfileSaved(saved, remoteDeviceStore.load())
    }

    fun deleteProfile(profileId: String): Boolean = launchProfileOperation(profileId) {
        remoteDeviceStore.delete(profileId)
        RemoteOperationOutcome.ProfileDeleted(profileId, remoteDeviceStore.load())
    }

    fun refresh(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = launchOperation(
        profile = profile,
        timeoutSeconds = timeoutSeconds,
        kind = RemoteNetworkOperationKind.REFRESH,
        clearStatusOnFailure = true,
    ) { client, credential, _, cancellation ->
        RemoteOperationOutcome.Refreshed(client.status(profile, credential, cancellation))
    }

    fun upload(
        profile: RemoteDeviceProfile,
        output: File,
        displayName: String = output.name,
        timeoutSeconds: Int,
    ): Boolean = launchOperation(
        profile = profile,
        timeoutSeconds = timeoutSeconds,
        kind = RemoteNetworkOperationKind.UPLOAD,
    ) { client, credential, onProgress, cancellation ->
        RemoteOperationOutcome.Uploaded(
            client.upload(
                profile,
                credential,
                output,
                displayName,
                onProgress,
                cancellation,
            ),
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
        ) { client, credential, cancellation ->
            client.start(profile, credential, upload, cancellation)
        }
    }

    fun pause(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = command(
        profile,
        timeoutSeconds,
        resultingState = "paused",
        message = RemoteOperationMessage.PAUSED,
    ) { client, credential, cancellation -> client.pause(profile, credential, cancellation) }

    fun resume(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = command(
        profile,
        timeoutSeconds,
        resultingState = "printing",
        message = RemoteOperationMessage.RESUMED,
    ) { client, credential, cancellation -> client.resume(profile, credential, cancellation) }

    fun cancel(profile: RemoteDeviceProfile, timeoutSeconds: Int): Boolean = command(
        profile,
        timeoutSeconds,
        resultingState = "canceled",
        message = RemoteOperationMessage.CANCELED,
    ) { client, credential, cancellation -> client.cancel(profile, credential, cancellation) }

    @Synchronized
    fun cancelActiveRequest(): Boolean {
        val current = mutableState.value
        val active = activeRemoteRequest.get()
        if (
            !current.busy || current.activeNetworkOperation == null ||
            current.cancellationRequested || active?.operationId != current.operationId ||
            active.kind != current.activeNetworkOperation ||
            !active.cancellation.cancel()
        ) {
            return false
        }
        mutableState.value = current.withRemoteRequestCancellationRequested(
            current.operationId,
            current.activeProfileId.orEmpty(),
        )
        return true
    }

    @Synchronized
    fun invalidateUpload() {
        val current = mutableState.value
        mutableState.value = current.invalidateRemoteUpload()
        activeRemoteRequest.get()
            ?.takeIf {
                it.operationId == current.operationId &&
                    it.kind == RemoteNetworkOperationKind.UPLOAD
            }
            ?.cancellation
            ?.cancel()
    }

    fun selectionChanged(profileId: String) {
        mutableState.update { it.changeRemoteSelection(profileId) }
    }

    private fun command(
        profile: RemoteDeviceProfile,
        timeoutSeconds: Int,
        resultingState: String,
        message: RemoteOperationMessage,
        operation: (RemoteDeviceClient, String, RemoteRequestCancellation) -> Unit,
    ): Boolean = launchOperation(
        profile = profile,
        timeoutSeconds = timeoutSeconds,
        kind = RemoteNetworkOperationKind.COMMAND,
    ) { client, credential, _, cancellation ->
        operation(client, credential, cancellation)
        RemoteOperationOutcome.Commanded(resultingState, message)
    }

    private fun loadProfiles(operationId: Long) {
        viewModelScope.launch {
            val (loaded, unavailable) = try {
                withContext(Dispatchers.IO) {
                    remoteDeviceStore.load() to remoteDeviceStore.storageUnavailable
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList<RemoteDeviceProfile>() to true
            }
            if (unavailable) supportEvents.record(SupportEvent.REMOTE_STORAGE_UNAVAILABLE)
            mutableState.update { current ->
                if (
                    !current.busy || current.operationId != operationId ||
                    current.activeProfileId != PROFILE_STORAGE_OPERATION
                ) {
                    current
                } else {
                    current.copy(
                        busy = false,
                        activeProfileId = null,
                        profiles = loaded,
                        profilesLoaded = true,
                        storageUnavailable = unavailable,
                        selectedProfileId = current.selectedProfileId
                            ?.takeIf { selected -> loaded.any { it.id == selected } }
                            ?: loaded.firstOrNull()?.id,
                        messageProfileId = null,
                        message = RemoteOperationMessage.STORAGE_UNAVAILABLE.takeIf {
                            unavailable
                        },
                    )
                }
            }
        }
    }

    @Synchronized
    private fun launchProfileOperation(
        profileId: String,
        operation: () -> RemoteOperationOutcome,
    ): Boolean {
        if (mutableState.value.busy) return false
        val operationId = ++nextOperationId
        mutableState.value = mutableState.value.beginRemoteOperation(operationId, profileId)
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) { operation() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                supportEvents.record(SupportEvent.REMOTE_PROFILE_SAVE_FAILED)
                RemoteOperationOutcome.Failed(
                    message = RemoteOperationMessage.PROFILE_SAVE_FAILED,
                    clearStatus = false,
                )
            }
            mutableState.update {
                it.finishRemoteOperation(operationId, profileId, outcome).let { settled ->
                    if (outcome is RemoteOperationOutcome.Failed) {
                        settled.copy(messageProfileId = null)
                    } else {
                        settled
                    }
                }
            }
        }
        return true
    }

    @Synchronized
    private fun launchOperation(
        profile: RemoteDeviceProfile,
        timeoutSeconds: Int,
        kind: RemoteNetworkOperationKind,
        clearStatusOnFailure: Boolean = false,
        operation: (
            RemoteDeviceClient,
            String,
            (Int) -> Unit,
            RemoteRequestCancellation,
        ) -> RemoteOperationOutcome,
    ): Boolean {
        if (mutableState.value.busy) return false
        val operationId = ++nextOperationId
        val activeRequest = ActiveRemoteRequest(
            operationId,
            kind,
            RemoteRequestCancellation(),
        ).also {
            check(activeRemoteRequest.compareAndSet(null, it)) {
                "remote_request_lifecycle_invalid"
            }
        }
        mutableState.value = mutableState.value.beginRemoteOperation(
            operationId,
            profile.id,
            kind,
        )
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    val client = RemoteDeviceClient(timeoutSeconds.coerceIn(5, 60) * 1_000)
                    val credential = remoteDeviceStore.credential(profile)
                    try {
                        operation(client, credential, { progress ->
                            mutableState.update {
                                it.withRemoteUploadProgress(operationId, profile.id, progress)
                            }
                        }, activeRequest.cancellation)
                    } finally {
                        activeRequest.cancellation.close()
                    }
                }
            } catch (cancellation: RemoteRequestCancelledException) {
                RemoteOperationOutcome.RequestCanceled(kind)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val unauthorized = failure is RemoteDeviceException &&
                    failure.statusCode in setOf(401, 403)
                supportEvents.record(
                    when {
                        unauthorized -> SupportEvent.REMOTE_AUTH_FAILED
                        kind == RemoteNetworkOperationKind.COMMAND -> {
                            SupportEvent.REMOTE_COMMAND_FAILED
                        }
                        else -> SupportEvent.REMOTE_CONNECTION_FAILED
                    },
                )
                RemoteOperationOutcome.Failed(
                    message = when {
                        unauthorized -> RemoteOperationMessage.ACCESS_DENIED
                        kind == RemoteNetworkOperationKind.COMMAND &&
                            failure is RemoteDeviceException -> {
                            RemoteOperationMessage.COMMAND_FAILED
                        }
                        else -> RemoteOperationMessage.CONNECTION_FAILED
                    },
                    clearStatus = clearStatusOnFailure,
                )
            }
            finishOperation(operationId, profile.id, outcome, activeRequest)
        }
        return true
    }

    @Synchronized
    private fun finishOperation(
        operationId: Long,
        profileId: String,
        outcome: RemoteOperationOutcome,
        activeRequest: ActiveRemoteRequest,
    ) {
        activeRemoteRequest.compareAndSet(activeRequest, null)
        mutableState.value = mutableState.value.finishRemoteOperation(
            operationId,
            profileId,
            outcome,
        )
    }

    override fun onCleared() {
        activeRemoteRequest.getAndSet(null)?.cancellation?.cancel()
        super.onCleared()
    }

    private companion object {
        const val PROFILE_STORAGE_OPERATION = "profile-storage"
        const val NEW_PROFILE_OPERATION = "new-profile"
    }
}

package com.ashcastle.duckyslicer

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ProfileLibraryMessage {
    STORAGE_UNAVAILABLE,
    SAVE_FAILED,
}

internal enum class ProfileTransferDirection {
    IMPORT,
    EXPORT,
}

internal enum class ProfileTransferOutcome {
    SUCCEEDED,
    CANCELED,
    FAILED,
}

internal data class ProfileTransferCompletion(
    val id: Long,
    val direction: ProfileTransferDirection,
    val outcome: ProfileTransferOutcome,
    val importResult: ProfileBundleImportResult? = null,
)

internal sealed interface ProfileSaveCompletion {
    val id: Long
    val sessionRevision: Long
    val sourceOptions: SliceOptions

    data class Printer(
        override val id: Long,
        override val sessionRevision: Long,
        override val sourceOptions: SliceOptions,
        val saved: PrinterProfile,
    ) : ProfileSaveCompletion

    data class Filament(
        override val id: Long,
        override val sessionRevision: Long,
        override val sourceOptions: SliceOptions,
        val slot: Int,
        val saved: FilamentProfile,
    ) : ProfileSaveCompletion

    data class Slicing(
        override val id: Long,
        override val sessionRevision: Long,
        override val sourceOptions: SliceOptions,
        val saved: QualityProfile,
    ) : ProfileSaveCompletion
}

internal fun ProfileSaveCompletion.optionsForSession(currentRevision: Long): SliceOptions? {
    if (sessionRevision != currentRevision) return null
    return when (this) {
        is ProfileSaveCompletion.Printer -> sourceOptions.selectPrinter(saved)
        is ProfileSaveCompletion.Filament -> sourceOptions.updateFilamentSlot(slot, saved)
        is ProfileSaveCompletion.Slicing -> sourceOptions.selectQuality(saved)
    }
}

internal data class ProfileLibraryState(
    val busy: Boolean = true,
    val catalog: ProfileCatalog = ProfileCatalog(),
    val catalogLoaded: Boolean = false,
    val recents: ProfileRecents = ProfileRecents(),
    val recentsLoaded: Boolean = false,
    val recentsRevision: Long = 0,
    val persistedRecentsRevision: Long = 0,
    val storageUnavailable: Boolean = false,
    val completion: ProfileSaveCompletion? = null,
    val message: ProfileLibraryMessage? = null,
    val activeOperationId: Long = 0,
    val activeTransferDirection: ProfileTransferDirection? = null,
    val transferCancellationRequested: Boolean = false,
    val transferCompletion: ProfileTransferCompletion? = null,
)

internal fun ProfileLibraryState.withStartedProfileTransfer(
    operationId: Long,
    direction: ProfileTransferDirection,
): ProfileLibraryState? {
    if (busy || !catalogLoaded || completion != null || transferCompletion != null) return null
    return copy(
        busy = true,
        message = null,
        activeOperationId = operationId,
        activeTransferDirection = direction,
        transferCancellationRequested = false,
    )
}

internal fun ProfileLibraryState.withProfileTransferCancellationRequested(
    operationId: Long,
    direction: ProfileTransferDirection,
): ProfileLibraryState? {
    if (
        !busy || activeOperationId != operationId || activeTransferDirection != direction ||
        transferCancellationRequested
    ) {
        return null
    }
    return copy(transferCancellationRequested = true)
}

internal fun ProfileLibraryState.withCompletedProfileTransfer(
    operationId: Long,
    direction: ProfileTransferDirection,
    requestedOutcome: ProfileTransferOutcome,
    importResult: ProfileBundleImportResult?,
    refreshedCatalog: ProfileCatalog?,
    profileStorageUnavailable: Boolean,
): ProfileLibraryState? {
    if (!busy || activeOperationId != operationId || activeTransferDirection != direction) return null
    val outcome = if (
        transferCancellationRequested && requestedOutcome == ProfileTransferOutcome.SUCCEEDED
    ) {
        ProfileTransferOutcome.CANCELED
    } else {
        requestedOutcome
    }
    return copy(
        busy = false,
        catalog = refreshedCatalog ?: catalog,
        catalogLoaded = catalogLoaded || refreshedCatalog != null,
        storageUnavailable = storageUnavailable || profileStorageUnavailable,
        activeTransferDirection = null,
        transferCancellationRequested = false,
        transferCompletion = ProfileTransferCompletion(
            operationId,
            direction,
            outcome,
            importResult.takeIf { outcome == ProfileTransferOutcome.SUCCEEDED },
        ),
    )
}

private data class ActiveProfileTransfer(
    val id: Long,
    val direction: ProfileTransferDirection,
    val cancellation: DocumentTransferCancellation,
)

/** Owns profile persistence for the whole Activity lifetime, including recreation. */
internal class ProfileLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val recentStore = ProfileRecentStore(application)
    private val supportEvents = SupportEventJournal(application)
    private val mutableState = MutableStateFlow(ProfileLibraryState())
    val state: StateFlow<ProfileLibraryState> = mutableState.asStateFlow()
    private var nextOperationId = 0L
    private var recentPersistenceJob: Job? = null
    private var activeTransfer: ActiveProfileTransfer? = null

    init {
        loadLibrary()
    }

    fun savePrinter(name: String, options: SliceOptions, sessionRevision: Long): Boolean =
        launchSave(SupportEvent.PRINTER_PROFILE_SAVE_FAILED) { operationId ->
            val saved = profileStore.savePrinter(name, options)
            ProfileSaveResult(
                catalog = profileStore.load(),
                completion = ProfileSaveCompletion.Printer(
                    operationId,
                    sessionRevision,
                    options,
                    saved,
                ),
            )
        }

    fun saveFilament(
        name: String,
        options: SliceOptions,
        slot: Int,
        sessionRevision: Long,
    ): Boolean = launchSave(SupportEvent.FILAMENT_PROFILE_SAVE_FAILED) {
        operationId ->
        val saved = profileStore.saveFilament(name, options, slot)
        ProfileSaveResult(
            catalog = profileStore.load(),
            completion = ProfileSaveCompletion.Filament(
                operationId,
                sessionRevision,
                options,
                slot,
                saved,
            ),
        )
    }

    fun saveSlicing(name: String, options: SliceOptions, sessionRevision: Long): Boolean =
        launchSave(SupportEvent.SLICING_PROFILE_SAVE_FAILED) { operationId ->
            val saved = profileStore.saveSlicing(name, options)
            ProfileSaveResult(
                catalog = profileStore.load(),
                completion = ProfileSaveCompletion.Slicing(
                    operationId,
                    sessionRevision,
                    options,
                    saved,
                ),
            )
        }

    fun importBundle(uri: Uri): Boolean = launchTransfer(uri, ProfileTransferDirection.IMPORT)

    fun exportBundle(uri: Uri): Boolean = launchTransfer(uri, ProfileTransferDirection.EXPORT)

    fun cancelTransfer(): Boolean {
        val active = synchronized(this) { activeTransfer } ?: return false
        if (!active.cancellation.cancel()) return false
        synchronized(this) {
            val current = mutableState.value
            current.withProfileTransferCancellationRequested(active.id, active.direction)
                ?.let { canceling -> mutableState.value = canceling }
        }
        return true
    }

    @Synchronized
    fun recordSelection(options: SliceOptions): Boolean {
        val current = mutableState.value
        if (!current.recentsLoaded) return false
        val next = current.recents.record(options)
        if (next == current.recents) return true
        val updated = current.copy(
            recents = next,
            recentsRevision = current.recentsRevision + 1,
        )
        mutableState.value = updated
        scheduleRecentPersistenceLocked(next, updated.recentsRevision)
        return true
    }

    @Synchronized
    fun consumeCompletion(operationId: Long) {
        val current = mutableState.value
        if (current.completion?.id != operationId) return
        mutableState.value = current.copy(completion = null)
    }

    @Synchronized
    fun consumeMessage(message: ProfileLibraryMessage) {
        val current = mutableState.value
        if (current.message != message) return
        mutableState.value = current.copy(message = null)
    }

    @Synchronized
    fun consumeTransferCompletion(operationId: Long) {
        val current = mutableState.value
        if (current.transferCompletion?.id != operationId) return
        mutableState.value = current.copy(transferCompletion = null)
    }

    private fun loadLibrary() {
        viewModelScope.launch {
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val catalog = profileStore.load()
                    val profileUnavailable = profileStore.storageUnavailable
                    val recents = recentStore.load()
                    LoadedProfileLibrary(
                        catalog,
                        recents,
                        profileUnavailable || recentStore.storageUnavailable,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LoadedProfileLibrary(ProfileCatalog(), ProfileRecents(), true)
            }
            if (loaded.storageUnavailable) {
                supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
            }
            synchronized(this@ProfileLibraryViewModel) {
                mutableState.value = ProfileLibraryState(
                    busy = false,
                    catalog = loaded.catalog,
                    catalogLoaded = true,
                    recents = loaded.recents,
                    recentsLoaded = true,
                    storageUnavailable = loaded.storageUnavailable,
                    message = ProfileLibraryMessage.STORAGE_UNAVAILABLE.takeIf {
                        loaded.storageUnavailable
                    },
                )
            }
        }
    }

    @Synchronized
    private fun launchTransfer(uri: Uri, direction: ProfileTransferDirection): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val current = mutableState.value
        val operationId = ++nextOperationId
        val started = current.withStartedProfileTransfer(operationId, direction) ?: return false
        val cancellation = DocumentTransferCancellation()
        val transfer = ActiveProfileTransfer(operationId, direction, cancellation)
        activeTransfer = transfer
        mutableState.value = started
        viewModelScope.launch(Dispatchers.IO) {
            val application = getApplication<Application>()
            var imported: ProfileBundleImportResult? = null
            val outcome = try {
                when (direction) {
                    ProfileTransferDirection.IMPORT -> {
                        val provider = requireNotNull(
                            application.contentResolver.acquireContentProviderClient(uri),
                        ) { "profile_bundle_provider_unavailable" }
                        val descriptor = requireNotNull(
                            provider.use {
                                provider.openAssetFile(uri, "r", cancellation.providerSignal)
                            },
                        ) { "profile_bundle_input_unavailable" }
                        if (descriptor.length >= 0L) {
                            require(descriptor.length <= MAX_PROFILE_BUNDLE_BYTES) {
                                "profile_bundle_too_large"
                            }
                        }
                        val bytes = descriptor.use {
                            descriptor.createInputStream().use { input ->
                                cancellation.attachInput(input)
                                try {
                                    readProfileBundleBytes(input, cancellation)
                                } finally {
                                    cancellation.detachInput(input)
                                }
                            }
                        }
                        imported = profileStore.importBundle(bytes, cancellation::complete)
                    }
                    ProfileTransferDirection.EXPORT -> {
                        val bytes = profileStore.exportBundle()
                        cancellation.throwIfRequested()
                        val descriptor = requireNotNull(
                            application.contentResolver.openAssetFileDescriptor(
                                uri,
                                "wt",
                                cancellation.providerSignal,
                            ),
                        ) { "profile_bundle_output_unavailable" }
                        descriptor.use {
                            descriptor.createOutputStream().use { output ->
                                cancellation.attachOutput(output)
                                try {
                                    writeProfileBundleBytes(output, bytes, cancellation)
                                    output.flush()
                                    cancellation.complete()
                                } finally {
                                    cancellation.detachOutput(output)
                                }
                            }
                        }
                    }
                }
                ProfileTransferOutcome.SUCCEEDED
            } catch (scopeCancellation: CancellationException) {
                cancellation.cancel()
                if (direction == ProfileTransferDirection.EXPORT) {
                    deleteFailedCreatedDocument(application, uri)
                }
                throw scopeCancellation
            } catch (failure: Exception) {
                val canceled = cancellation.wasRequested() ||
                    failure is DocumentTransferCancelledException
                if (direction == ProfileTransferDirection.EXPORT) {
                    deleteFailedCreatedDocument(application, uri)
                }
                if (canceled) {
                    ProfileTransferOutcome.CANCELED
                } else {
                    if (BuildConfig.DEBUG) Log.e(LOG_TAG, "Profile bundle transfer failed", failure)
                    supportEvents.record(
                        if (direction == ProfileTransferDirection.IMPORT) {
                            SupportEvent.PROFILE_BUNDLE_IMPORT_FAILED
                        } else {
                            SupportEvent.PROFILE_BUNDLE_EXPORT_FAILED
                        },
                    )
                    ProfileTransferOutcome.FAILED
                }
            } finally {
                cancellation.close()
            }
            val refreshedCatalog = if (
                direction == ProfileTransferDirection.IMPORT &&
                outcome == ProfileTransferOutcome.SUCCEEDED
            ) {
                runCatching { profileStore.load() }.getOrNull()
            } else {
                null
            }
            synchronized(this@ProfileLibraryViewModel) {
                if (activeTransfer?.id == operationId) activeTransfer = null
                mutableState.value.withCompletedProfileTransfer(
                    operationId = operationId,
                    direction = direction,
                    requestedOutcome = outcome,
                    importResult = imported,
                    refreshedCatalog = refreshedCatalog,
                    profileStorageUnavailable = profileStore.storageUnavailable,
                )?.let { completed -> mutableState.value = completed }
            }
        }
        return true
    }

    @Synchronized
    private fun launchSave(
        failureEvent: SupportEvent,
        operation: (Long) -> ProfileSaveResult,
    ): Boolean {
        val current = mutableState.value
        if (current.busy || !current.catalogLoaded || current.completion != null) return false
        val operationId = ++nextOperationId
        mutableState.value = current.copy(
            busy = true,
            message = null,
            activeOperationId = operationId,
        )
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { operation(operationId) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                supportEvents.record(failureEvent)
                if (profileStore.storageUnavailable) {
                    supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
                }
                null
            }
            synchronized(this@ProfileLibraryViewModel) {
                val active = mutableState.value
                if (!active.busy || active.activeOperationId != operationId) return@synchronized
                mutableState.value = if (result == null) {
                    active.copy(
                        busy = false,
                        storageUnavailable = active.storageUnavailable ||
                            profileStore.storageUnavailable || recentStore.storageUnavailable,
                        message = if (profileStore.storageUnavailable) {
                            ProfileLibraryMessage.STORAGE_UNAVAILABLE
                        } else {
                            ProfileLibraryMessage.SAVE_FAILED
                        },
                    )
                } else {
                    active.copy(
                        busy = false,
                        catalog = result.catalog,
                        catalogLoaded = true,
                        storageUnavailable = recentStore.storageUnavailable,
                        completion = result.completion,
                        message = null,
                    )
                }
            }
        }
        return true
    }

    @Synchronized
    fun flushRecentPersistence(): Boolean {
        val current = mutableState.value
        if (!current.hasDirtyRecents() || recentStore.storageUnavailable) return false
        scheduleRecentPersistenceLocked(
            recents = current.recents,
            expectedRevision = current.recentsRevision,
            delayMillis = 0L,
        )
        return true
    }

    private fun scheduleRecentPersistenceLocked(
        recents: ProfileRecents,
        expectedRevision: Long,
        delayMillis: Long = RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS,
    ) {
        recentPersistenceJob?.cancel()
        if (recentStore.storageUnavailable) return
        recentPersistenceJob = viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            val failed = try {
                withContext(Dispatchers.IO) { recentStore.save(recents) }
                false
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                true
            }
            if (failed) {
                supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
            }
            synchronized(this@ProfileLibraryViewModel) {
                val current = mutableState.value
                if (
                    current.recentsRevision != expectedRevision ||
                    current.recents != recents
                ) {
                    return@synchronized
                }
                if (failed) {
                    mutableState.value = current.copy(
                        message = ProfileLibraryMessage.STORAGE_UNAVAILABLE,
                    )
                } else {
                    mutableState.value = current.copy(
                        persistedRecentsRevision = expectedRevision,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        val pending = synchronized(this) {
            activeTransfer?.cancellation?.cancel()
            activeTransfer = null
            recentPersistenceJob?.cancel()
            recentPersistenceJob = null
            mutableState.value.takeIf { current ->
                current.hasDirtyRecents() && !recentStore.storageUnavailable
            }
        }
        try {
            if (pending != null) {
                try {
                    recentStore.save(pending.recents)
                } catch (_: Exception) {
                    supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
                }
            }
        } finally {
            super.onCleared()
        }
    }

    private data class LoadedProfileLibrary(
        val catalog: ProfileCatalog,
        val recents: ProfileRecents,
        val storageUnavailable: Boolean,
    )

    private data class ProfileSaveResult(
        val catalog: ProfileCatalog,
        val completion: ProfileSaveCompletion,
    )

    private companion object {
        const val RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS = 350L
        const val LOG_TAG = "DuckyProfileTransfer"
    }
}

private fun ProfileLibraryState.hasDirtyRecents(): Boolean =
    recentsLoaded && recentsRevision != persistedRecentsRevision

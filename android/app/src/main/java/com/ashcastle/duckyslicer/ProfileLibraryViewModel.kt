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
    DELETE_FAILED,
}

internal enum class ProfileKind {
    PRINTER,
    FILAMENT,
    SLICING,
}

internal data class ProfileDeleteCompletion(
    val id: Long,
    val kind: ProfileKind,
    val profileId: String,
)

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
        val selectSaved: Boolean = true,
    ) : ProfileSaveCompletion

    data class Filament(
        override val id: Long,
        override val sessionRevision: Long,
        override val sourceOptions: SliceOptions,
        val slot: Int,
        val saved: FilamentProfile,
        val selectSaved: Boolean = true,
    ) : ProfileSaveCompletion

    data class Slicing(
        override val id: Long,
        override val sessionRevision: Long,
        override val sourceOptions: SliceOptions,
        val saved: QualityProfile,
        val selectSaved: Boolean = true,
    ) : ProfileSaveCompletion
}

internal fun ProfileSaveCompletion.optionsForSession(currentRevision: Long): SliceOptions? {
    if (sessionRevision != currentRevision) return null
    return when (this) {
        is ProfileSaveCompletion.Printer -> if (selectSaved) {
            sourceOptions.selectPrinter(saved)
        } else {
            sourceOptions.replaceSelectedPrinter(saved)
        }
        is ProfileSaveCompletion.Filament -> if (selectSaved) {
            sourceOptions.updateFilamentSlot(slot, saved)
        } else {
            sourceOptions.replaceSelectedFilaments(saved)
        }
        is ProfileSaveCompletion.Slicing -> if (selectSaved) {
            sourceOptions.selectQuality(saved)
        } else {
            sourceOptions.replaceSelectedSlicing(saved)
        }
    }
}

private fun SliceOptions.replaceSelectedPrinter(saved: PrinterProfile): SliceOptions =
    if (printerProfile.id == saved.id) selectPrinter(saved) else this

private fun SliceOptions.replaceSelectedFilaments(saved: FilamentProfile): SliceOptions {
    var updated = this
    resolvedFilamentSlots().forEachIndexed { index, profile ->
        if (profile.id == saved.id) updated = updated.updateFilamentSlot(index, saved)
    }
    return updated
}

private fun SliceOptions.replaceSelectedSlicing(saved: QualityProfile): SliceOptions =
    if (quality.id == saved.id) selectQuality(saved) else this

internal data class ProfileLibraryState(
    val busy: Boolean = true,
    val catalog: ProfileCatalog = ProfileCatalog(),
    val catalogLoaded: Boolean = false,
    val bundledCatalogUnavailable: Boolean = false,
    val recents: ProfileRecents = ProfileRecents(),
    val recentsLoaded: Boolean = false,
    val recentsRevision: Long = 0,
    val persistedRecentsRevision: Long = 0,
    val storageUnavailable: Boolean = false,
    val completion: ProfileSaveCompletion? = null,
    val deletionCompletion: ProfileDeleteCompletion? = null,
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
    if (
        busy || !catalogLoaded || completion != null || deletionCompletion != null ||
        transferCompletion != null
    ) return null
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
        launchSave(SupportEvent.PRINTER_PROFILE_SAVE_FAILED) { operationId, catalog ->
            val saved = profileStore.savePrinter(name, options)
            ProfileSaveResult(
                catalog = catalog.copy(printers = catalog.printers + saved),
                completion = ProfileSaveCompletion.Printer(
                    operationId,
                    sessionRevision,
                    options,
                    saved,
                ),
            )
        }

    fun updatePrinter(
        profileId: String,
        options: SliceOptions,
        sessionRevision: Long,
    ): Boolean = launchSave(
        failureEvent = SupportEvent.PRINTER_PROFILE_SAVE_FAILED,
        editableProfile = { catalog ->
            catalog.printers.any { it.id == profileId && !it.builtIn }
        },
    ) { operationId, catalog ->
        check(options.printerProfile.id == profileId) { "profile_selection_changed" }
        val saved = profileStore.updatePrinter(profileId, options.printerProfile.name, options)
        ProfileSaveResult(
            catalog = catalog.copy(
                printers = catalog.printers.replaceProfile(saved, PrinterProfile::id),
            ),
            completion = ProfileSaveCompletion.Printer(
                operationId,
                sessionRevision,
                options,
                saved,
            ),
        )
    }

    fun renamePrinter(
        profileId: String,
        name: String,
        options: SliceOptions,
        sessionRevision: Long,
    ): Boolean = launchSave(
        failureEvent = SupportEvent.PRINTER_PROFILE_SAVE_FAILED,
        editableProfile = { catalog ->
            catalog.printers.any { it.id == profileId && !it.builtIn }
        },
    ) { operationId, catalog ->
        val saved = profileStore.renamePrinter(profileId, name)
        ProfileSaveResult(
            catalog = catalog.copy(
                printers = catalog.printers.replaceProfile(saved, PrinterProfile::id),
            ),
            completion = ProfileSaveCompletion.Printer(
                operationId,
                sessionRevision,
                options,
                saved,
                selectSaved = false,
            ),
        )
    }

    fun saveFilament(
        name: String,
        options: SliceOptions,
        slot: Int,
        sessionRevision: Long,
    ): Boolean = launchSave(SupportEvent.FILAMENT_PROFILE_SAVE_FAILED) {
        operationId, catalog ->
        val saved = profileStore.saveFilament(name, options, slot)
        ProfileSaveResult(
            catalog = catalog.copy(filaments = catalog.filaments + saved),
            completion = ProfileSaveCompletion.Filament(
                operationId,
                sessionRevision,
                options,
                slot,
                saved,
            ),
        )
    }

    fun updateFilament(
        profileId: String,
        options: SliceOptions,
        slot: Int,
        sessionRevision: Long,
    ): Boolean = launchSave(
        failureEvent = SupportEvent.FILAMENT_PROFILE_SAVE_FAILED,
        editableProfile = { catalog ->
            catalog.filaments.any { it.id == profileId && !it.builtIn }
        },
    ) { operationId, catalog ->
        val current = options.resolvedFilamentSlots().getOrElse(slot) {
            throw IllegalArgumentException("Filament slot is unavailable")
        }
        check(current.id == profileId) { "profile_selection_changed" }
        val saved = profileStore.updateFilament(profileId, current.name, options, slot)
        ProfileSaveResult(
            catalog = catalog.copy(
                filaments = catalog.filaments.replaceProfile(saved, FilamentProfile::id),
            ),
            completion = ProfileSaveCompletion.Filament(
                operationId,
                sessionRevision,
                options,
                slot,
                saved,
            ),
        )
    }

    fun renameFilament(
        profileId: String,
        name: String,
        options: SliceOptions,
        sessionRevision: Long,
    ): Boolean = launchSave(
        failureEvent = SupportEvent.FILAMENT_PROFILE_SAVE_FAILED,
        editableProfile = { catalog ->
            catalog.filaments.any { it.id == profileId && !it.builtIn }
        },
    ) { operationId, catalog ->
        val saved = profileStore.renameFilament(profileId, name)
        ProfileSaveResult(
            catalog = catalog.copy(
                filaments = catalog.filaments.replaceProfile(saved, FilamentProfile::id),
            ),
            completion = ProfileSaveCompletion.Filament(
                operationId,
                sessionRevision,
                options,
                slot = 0,
                saved = saved,
                selectSaved = false,
            ),
        )
    }

    fun saveSlicing(name: String, options: SliceOptions, sessionRevision: Long): Boolean =
        launchSave(SupportEvent.SLICING_PROFILE_SAVE_FAILED) { operationId, catalog ->
            val saved = profileStore.saveSlicing(name, options)
            ProfileSaveResult(
                catalog = catalog.copy(slicing = catalog.slicing + saved),
                completion = ProfileSaveCompletion.Slicing(
                    operationId,
                    sessionRevision,
                    options,
                    saved,
                ),
            )
        }

    fun updateSlicing(
        profileId: String,
        options: SliceOptions,
        sessionRevision: Long,
    ): Boolean = launchSave(
        failureEvent = SupportEvent.SLICING_PROFILE_SAVE_FAILED,
        editableProfile = { catalog ->
            catalog.slicing.any { it.id == profileId && !it.builtIn }
        },
    ) { operationId, catalog ->
        check(options.quality.id == profileId) { "profile_selection_changed" }
        val saved = profileStore.updateSlicing(profileId, options.quality.name, options)
        ProfileSaveResult(
            catalog = catalog.copy(
                slicing = catalog.slicing.replaceProfile(saved, QualityProfile::id),
            ),
            completion = ProfileSaveCompletion.Slicing(
                operationId,
                sessionRevision,
                options,
                saved,
            ),
        )
    }

    fun renameSlicing(
        profileId: String,
        name: String,
        options: SliceOptions,
        sessionRevision: Long,
    ): Boolean = launchSave(
        failureEvent = SupportEvent.SLICING_PROFILE_SAVE_FAILED,
        editableProfile = { catalog ->
            catalog.slicing.any { it.id == profileId && !it.builtIn }
        },
    ) { operationId, catalog ->
        val saved = profileStore.renameSlicing(profileId, name)
        ProfileSaveResult(
            catalog = catalog.copy(
                slicing = catalog.slicing.replaceProfile(saved, QualityProfile::id),
            ),
            completion = ProfileSaveCompletion.Slicing(
                operationId,
                sessionRevision,
                options,
                saved,
                selectSaved = false,
            ),
        )
    }

    fun deletePrinter(id: String): Boolean = launchDelete(ProfileKind.PRINTER, id) {
        profileStore.deletePrinter(id)
    }

    fun deleteFilament(id: String): Boolean = launchDelete(ProfileKind.FILAMENT, id) {
        profileStore.deleteFilament(id)
    }

    fun deleteSlicing(id: String): Boolean = launchDelete(ProfileKind.SLICING, id) {
        profileStore.deleteSlicing(id)
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
    fun consumeDeletionCompletion(operationId: Long) {
        val current = mutableState.value
        if (current.deletionCompletion?.id != operationId) return
        mutableState.value = current.copy(deletionCompletion = null)
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
                        profileStore.bundledCatalogUnavailable,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LoadedProfileLibrary(
                    ProfileCatalog(),
                    ProfileRecents(),
                    storageUnavailable = true,
                    bundledCatalogUnavailable = true,
                )
            }
            if (loaded.storageUnavailable) {
                supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
            }
            if (loaded.bundledCatalogUnavailable) {
                supportEvents.record(SupportEvent.PROFILE_CATALOG_UNAVAILABLE)
            }
            synchronized(this@ProfileLibraryViewModel) {
                mutableState.value = ProfileLibraryState(
                    busy = false,
                    catalog = loaded.catalog,
                    catalogLoaded = true,
                    bundledCatalogUnavailable = loaded.bundledCatalogUnavailable,
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
        editableProfile: (ProfileCatalog) -> Boolean = { true },
        operation: (Long, ProfileCatalog) -> ProfileSaveResult,
    ): Boolean {
        val current = mutableState.value
        if (
            current.busy || !current.catalogLoaded || current.completion != null ||
            current.deletionCompletion != null || current.transferCompletion != null
        ) return false
        if (!editableProfile(current.catalog)) return false
        val operationId = ++nextOperationId
        mutableState.value = current.copy(
            busy = true,
            message = null,
            activeOperationId = operationId,
        )
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { operation(operationId, current.catalog) }
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
    private fun launchDelete(
        kind: ProfileKind,
        profileId: String,
        operation: () -> Boolean,
    ): Boolean {
        val current = mutableState.value
        if (
            current.busy || !current.catalogLoaded || current.completion != null ||
            current.deletionCompletion != null || current.transferCompletion != null
        ) return false
        val profileIsUserOwned = when (kind) {
            ProfileKind.PRINTER -> current.catalog.printers.any {
                it.id == profileId && !it.builtIn
            }
            ProfileKind.FILAMENT -> current.catalog.filaments.any {
                it.id == profileId && !it.builtIn
            }
            ProfileKind.SLICING -> current.catalog.slicing.any {
                it.id == profileId && !it.builtIn
            }
        }
        if (!profileIsUserOwned) return false
        val operationId = ++nextOperationId
        mutableState.value = current.copy(
            busy = true,
            message = null,
            activeOperationId = operationId,
        )
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    check(operation()) { "profile_not_found" }
                    profileStore.load()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (profileStore.storageUnavailable) {
                    supportEvents.record(SupportEvent.PROFILE_STORAGE_UNAVAILABLE)
                }
                null
            }
            synchronized(this@ProfileLibraryViewModel) {
                val active = mutableState.value
                if (!active.busy || active.activeOperationId != operationId) return@synchronized
                if (result == null) {
                    mutableState.value = active.copy(
                        busy = false,
                        storageUnavailable = active.storageUnavailable ||
                            profileStore.storageUnavailable || recentStore.storageUnavailable,
                        message = if (profileStore.storageUnavailable) {
                            ProfileLibraryMessage.STORAGE_UNAVAILABLE
                        } else {
                            ProfileLibraryMessage.DELETE_FAILED
                        },
                    )
                } else {
                    val nextRecents = when (kind) {
                        ProfileKind.PRINTER -> active.recents.removePrinter(profileId)
                        ProfileKind.FILAMENT -> active.recents.removeFilament(profileId)
                        ProfileKind.SLICING -> active.recents.removeSlicing(profileId)
                    }
                    val recentsChanged = nextRecents != active.recents
                    val revision = active.recentsRevision + if (recentsChanged) 1 else 0
                    mutableState.value = active.copy(
                        busy = false,
                        catalog = result,
                        catalogLoaded = true,
                        recents = nextRecents,
                        recentsRevision = revision,
                        storageUnavailable = recentStore.storageUnavailable,
                        deletionCompletion = ProfileDeleteCompletion(
                            operationId,
                            kind,
                            profileId,
                        ),
                        message = null,
                    )
                    if (recentsChanged) {
                        scheduleRecentPersistenceLocked(nextRecents, revision)
                    }
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
        val bundledCatalogUnavailable: Boolean,
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

private fun <T> List<T>.replaceProfile(saved: T, id: (T) -> String): List<T> {
    val savedId = id(saved)
    check(count { id(it) == savedId } == 1) { "profile_not_found" }
    return map { profile -> if (id(profile) == savedId) saved else profile }
}

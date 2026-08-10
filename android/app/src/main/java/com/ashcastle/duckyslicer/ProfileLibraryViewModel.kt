package com.ashcastle.duckyslicer

import android.app.Application
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
    val storageUnavailable: Boolean = false,
    val completion: ProfileSaveCompletion? = null,
    val message: ProfileLibraryMessage? = null,
    val activeOperationId: Long = 0,
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

    @Synchronized
    fun recordSelection(options: SliceOptions): Boolean {
        val current = mutableState.value
        if (!current.recentsLoaded) return false
        val next = current.recents.record(options)
        if (next == current.recents) return true
        mutableState.value = current.copy(recents = next)
        scheduleRecentPersistenceLocked(next)
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

    private fun scheduleRecentPersistenceLocked(recents: ProfileRecents) {
        recentPersistenceJob?.cancel()
        if (recentStore.storageUnavailable) return
        recentPersistenceJob = viewModelScope.launch {
            delay(RECENT_PROFILE_SAVE_DEBOUNCE_MILLIS)
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
                synchronized(this@ProfileLibraryViewModel) {
                    if (mutableState.value.recents == recents) {
                        mutableState.value = mutableState.value.copy(
                            message = ProfileLibraryMessage.STORAGE_UNAVAILABLE,
                        )
                    }
                }
            }
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
    }
}

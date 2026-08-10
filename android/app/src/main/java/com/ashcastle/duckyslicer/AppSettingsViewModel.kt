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

internal enum class AppSettingsMessage {
    SAVE_FAILED,
}

internal data class AppSettingsState(
    val settings: AppSettings = AppSettings(),
    val revision: Long = 0,
    val persistedRevision: Long = 0,
    val message: AppSettingsMessage? = null,
)

internal fun AppSettingsState.withUpdatedSettings(settings: AppSettings): AppSettingsState? {
    val normalized = settings.normalized()
    if (normalized == this.settings) return null
    return copy(
        settings = normalized,
        revision = revision + 1,
        message = null,
    )
}

/** Retains live settings and their single persistence pipeline across Activity recreation. */
internal class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppSettingsStore(application)
    private val supportEvents = SupportEventJournal(application)
    private val mutableState = MutableStateFlow(AppSettingsState(settings = store.load()))
    val state: StateFlow<AppSettingsState> = mutableState.asStateFlow()
    private var persistenceJob: Job? = null

    @Synchronized
    fun updateSettings(settings: AppSettings): Boolean {
        val current = mutableState.value
        val updated = current.withUpdatedSettings(settings) ?: return false
        mutableState.value = updated
        schedulePersistenceLocked(updated.revision, updated.settings)
        return true
    }

    private fun schedulePersistenceLocked(revision: Long, settings: AppSettings) {
        persistenceJob?.cancel()
        persistenceJob = viewModelScope.launch {
            delay(SETTINGS_SAVE_DEBOUNCE_MILLIS)
            val saved = try {
                withContext(Dispatchers.IO) { store.save(settings) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
            synchronized(this@AppSettingsViewModel) {
                val current = mutableState.value
                if (current.revision != revision) return@synchronized
                mutableState.value = if (saved) {
                    current.copy(persistedRevision = revision, message = null)
                } else {
                    supportEvents.record(SupportEvent.APP_SETTINGS_SAVE_FAILED)
                    current.copy(message = AppSettingsMessage.SAVE_FAILED)
                }
            }
        }
    }

    override fun onCleared() {
        val pending = synchronized(this) {
            persistenceJob?.cancel()
            mutableState.value.takeIf { it.revision != it.persistedRevision }
        }
        val saved = pending == null || runCatching { store.save(pending.settings) }.getOrDefault(false)
        if (!saved) {
            supportEvents.record(SupportEvent.APP_SETTINGS_SAVE_FAILED)
        }
        super.onCleared()
    }

    private companion object {
        const val SETTINGS_SAVE_DEBOUNCE_MILLIS = 350L
    }
}

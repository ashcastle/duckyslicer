package com.ashcastle.duckyslicer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun RemoteMonitoringEffect(
    lifecycleOwner: LifecycleOwner,
    selectedTab: WorkspaceTab,
    profileId: String?,
    connectionTimeoutSeconds: Int,
    model: RemoteOperationViewModel,
) {
    LaunchedEffect(lifecycleOwner, selectedTab, profileId, connectionTimeoutSeconds) {
        if (selectedTab != WorkspaceTab.DEVICE || profileId == null) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (currentCoroutineContext().isActive) {
                    val current = model.state.value
                    model.refreshInBackground(profileId, connectionTimeoutSeconds)
                    delay(
                        remoteMonitoringIntervalMillis(
                            current.statusFor(profileId),
                            current.messageFor(profileId),
                        ),
                    )
                }
            } finally {
                model.stopBackgroundRefresh(profileId)
            }
        }
    }
}

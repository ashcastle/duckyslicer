package com.ashcastle.duckyslicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun DeviceSheet(
    profiles: List<RemoteDeviceProfile>,
    selectedProfileId: String?,
    status: RemoteDeviceStatus?,
    upload: RemoteUpload?,
    gcodeAvailable: Boolean,
    busy: Boolean,
    uploadProgress: Int?,
    uploadActive: Boolean,
    uploadCancellationRequested: Boolean,
    message: String?,
    isError: Boolean,
    confirmBeforePrint: Boolean,
    onSelect: (String) -> Unit,
    onSave: (RemoteDeviceDraft) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onCancelUpload: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<RemoteDeviceDraft?>(null) }
    var confirmStart by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.id == selectedProfileId }

    Card(
        modifier = modifier.padding(12.dp).fillMaxWidth().widthIn(max = 620.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xEE2A2A27),
            contentColor = Color(0xFFF4F4EE),
        ),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.device_profiles),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    onClick = { editing = RemoteDeviceDraft() },
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.add_device))
                }
            }
            if (profiles.isEmpty()) {
                Text(stringResource(R.string.no_devices), color = Color(0xFFC8C9C2))
            }
            profiles.forEach { profile ->
                val isSelected = profile.id == selectedProfileId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF3A382D) else Color(0xFF32332F),
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = isSelected,
                                    enabled = !busy,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(profile.id) },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${profile.kind.displayName()} · ${profile.baseUrl}",
                                    color = Color(0xFFC8C9C2),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                editing = RemoteDeviceDraft(
                                    id = profile.id,
                                    name = profile.name,
                                    kind = profile.kind,
                                    baseUrl = profile.baseUrl,
                                )
                            },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Default.Edit, stringResource(R.string.edit_device))
                        }
                        IconButton(
                            onClick = { onDelete(profile.id) },
                            enabled = !busy,
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                stringResource(R.string.delete_device),
                                tint = Color(0xFFFF8A80),
                            )
                        }
                    }
                }
            }

            if (selected != null) {
                val localizedStatus = status?.displayState()
                val normalizedState = status?.state?.lowercase().orEmpty()
                val printIsActive = normalizedState.contains("print") || normalizedState.contains("pause")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(selected.name, fontWeight = FontWeight.Bold)
                        Text(
                            status?.let { value ->
                                buildString {
                                    append(localizedStatus)
                                    value.progressPercent?.let { append(" · $it%") }
                                    value.fileName?.let { append(" · $it") }
                                }
                            } ?: stringResource(R.string.device_not_checked),
                            color = if (status == null) Color(0xFFC8C9C2) else Color(0xFFF6C945),
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh_device))
                    }
                }

                if (message != null) {
                    Text(message, color = if (isError) Color(0xFFFF8A80) else Color(0xFFF6C945))
                }
                if (busy) {
                    if (uploadProgress != null) {
                        LinearProgressIndicator(
                            progress = { uploadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF6C945),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFF6C945),
                        )
                    }
                    if (uploadActive) {
                        TextButton(
                            onClick = onCancelUpload,
                            enabled = !uploadCancellationRequested,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(
                                stringResource(
                                    if (uploadCancellationRequested) {
                                        R.string.canceling_upload
                                    } else {
                                        R.string.cancel_upload
                                    },
                                ),
                                color = if (uploadCancellationRequested) {
                                    Color(0xFFC8C9C2)
                                } else {
                                    Color(0xFFFF8A80)
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = onUpload,
                    enabled = gcodeAvailable && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF6C945),
                        contentColor = Color(0xFF202124),
                    ),
                ) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (gcodeAvailable) stringResource(R.string.send_gcode)
                        else stringResource(R.string.slice_before_sending),
                    )
                }
                if (upload?.profileId == selected.id) {
                    Text(
                        stringResource(R.string.sent_file, upload.displayName),
                        color = Color(0xFFC8C9C2),
                    )
                    if (!printIsActive) {
                        Button(
                            onClick = {
                                if (confirmBeforePrint) confirmStart = true else onStart()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF6C945),
                                contentColor = Color(0xFF202124),
                            ),
                        ) {
                            Text(stringResource(R.string.start_print))
                        }
                    }
                }

                if (printIsActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (normalizedState.contains("pause")) {
                            TextButton(onClick = onResume, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.resume_print))
                            }
                        } else {
                            TextButton(onClick = onPause, enabled = !busy, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.pause_print))
                            }
                        }
                        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.cancel_print), color = Color(0xFFFF8A80))
                        }
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        DeviceEditorDialog(
            initial = draft,
            onDismiss = { editing = null },
            onSave = {
                onSave(it)
                editing = null
            },
        )
    }
    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text(stringResource(R.string.start_print)) },
            text = { Text(stringResource(R.string.confirm_start_print)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    onStart()
                }) { Text(stringResource(R.string.start_print)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DeviceEditorDialog(
    initial: RemoteDeviceDraft,
    onDismiss: () -> Unit,
    onSave: (RemoteDeviceDraft) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var validation by remember { mutableStateOf<String?>(null) }
    val connectionChanged = initial.id != null && (
        draft.kind != initial.kind ||
            normalizeRemoteBaseUrl(draft.baseUrl) != normalizeRemoteBaseUrl(initial.baseUrl)
    )
    val profile = RemoteDeviceProfile(
        id = draft.id.orEmpty().ifBlank { "new" },
        name = draft.name,
        kind = draft.kind,
        baseUrl = draft.baseUrl,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == null) stringResource(R.string.add_device) else stringResource(R.string.edit_device)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemoteDeviceKind.entries.forEach { kind ->
                        FilterChip(
                            selected = draft.kind == kind,
                            onClick = { draft = draft.copy(kind = kind) },
                            label = { Text(kind.displayName()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFF6C945),
                                selectedLabelColor = Color(0xFF202124),
                            ),
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = {
                        draft = draft.copy(name = it)
                        validation = null
                    },
                    label = { Text(stringResource(R.string.device_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.baseUrl,
                    onValueChange = {
                        draft = draft.copy(baseUrl = it)
                        validation = null
                    },
                    label = { Text(stringResource(R.string.device_address)) },
                    supportingText = { Text(stringResource(R.string.device_address_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.credential,
                    onValueChange = { draft = draft.copy(credential = it) },
                    label = { Text(stringResource(R.string.access_key)) },
                    supportingText = {
                        if (initial.id != null) {
                            Text(
                                stringResource(
                                    if (connectionChanged) {
                                        R.string.access_key_connection_change_hint
                                    } else {
                                        R.string.access_key_keep_hint
                                    },
                                ),
                            )
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (validation != null) {
                    Text(
                        when (validation) {
                            "name_required" -> stringResource(R.string.device_name_required)
                            "cleartext_not_local" -> stringResource(R.string.local_http_only)
                            else -> stringResource(R.string.device_address_invalid)
                        },
                        color = Color(0xFFFF8A80),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                validation = profile.validate()
                if (validation == null) onSave(draft)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun RemoteDeviceKind.displayName(): String = when (this) {
    RemoteDeviceKind.OCTOPRINT -> "OctoPrint"
    RemoteDeviceKind.KLIPPER -> "Klipper"
}

@Composable
private fun RemoteDeviceStatus.displayState(): String {
    val normalized = state.lowercase()
    return stringResource(
        when {
            normalized.contains("print") -> R.string.device_state_printing
            normalized.contains("pause") -> R.string.device_state_paused
            normalized.contains("complete") || normalized.contains("finish") -> R.string.device_state_complete
            normalized.contains("cancel") -> R.string.device_state_canceled
            normalized.contains("error") || normalized.contains("fail") -> R.string.device_state_error
            normalized.contains("offline") || normalized.contains("disconnect") -> R.string.device_state_offline
            normalized.contains("ready") || normalized.contains("operational") ||
                normalized.contains("standby") || normalized.contains("idle") -> R.string.device_state_ready
            else -> R.string.device_state_unknown
        },
    )
}

package com.ashcastle.duckyslicer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun OrcaImportReviewDialog(
    items: List<OrcaImportItem>?,
    onConfirm: (Set<Int>, Set<Int>) -> Unit,
    onCancel: () -> Unit,
) {
    if (items == null) return
    val selectionSaver = remember { listSaver<Set<Int>, Int>(save = { it.toList() }, restore = { it.toSet() }) }
    var selected by rememberSaveable(items, stateSaver = selectionSaver) { mutableStateOf(emptySet<Int>()) }
    var acknowledged by rememberSaveable(items, stateSaver = selectionSaver) { mutableStateOf(emptySet<Int>()) }
    val ready = orcaImportSelectionReady(items, selected, acknowledged)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.import_profiles)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.orca_import_baseline_notice)) }
                itemsIndexed(items) { index, item ->
                    Column {
                        Row {
                            Checkbox(checked = index in selected, enabled = item.problem == null,
                                onCheckedChange = { checked -> selected = if (checked) selected + index else selected - index })
                            Text(item.review.name)
                        }
                        if (item.problem != null) Text(
                            if (item.review.kind == "archive-entry") stringResource(R.string.import_skipped_archive_entry)
                            else item.problem,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (item.appliedKeys.isNotEmpty()) Text(stringResource(R.string.import_mapped_settings,
                            item.appliedKeys.sorted().joinToString(", ")))
                        if (item.review.unsupportedKeys.isNotEmpty()) Text(stringResource(R.string.import_unsupported_settings,
                            item.review.unsupportedKeys.sorted().joinToString(", ")))
                        if (item.defaultedKeys.isNotEmpty()) Text(stringResource(R.string.import_defaulted_settings,
                            item.defaultedKeys.sorted().joinToString(", ")))
                        if (item.review.conflictsWithSavedName) Text(stringResource(R.string.import_name_conflict_notice))
                        if (item.problem == null && (item.review.unsupportedKeys.isNotEmpty() || item.defaultedKeys.isNotEmpty())) {
                            Row {
                                Checkbox(checked = index in acknowledged,
                                    onCheckedChange = { checked -> acknowledged = if (checked) acknowledged + index else acknowledged - index })
                                Text(stringResource(R.string.import_accept_differences))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = ready, onClick = { onConfirm(selected, acknowledged) }) {
            Text(stringResource(R.string.import_profiles))
        } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

package com.ashcastle.duckyslicer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal data class OrcaModelSettingsReview(
    val requestId: String,
    val name: String,
    val conversion: OrcaProjectConversion?,
    val problem: String?,
)

@Composable
internal fun OrcaModelSettingsReviewDialog(
    review: OrcaModelSettingsReview?,
    onChoose: (String, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    if (review == null) return
    var accepted by rememberSaveable(review.requestId, review.name) { mutableStateOf(false) }
    val candidate = review.conversion
    AlertDialog(onDismissRequest = onCancel,
        title = { Text(review.name) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.model_settings_review_notice))
                if (review.problem != null) Text(review.problem, color = MaterialTheme.colorScheme.error)
                if (candidate != null) {
                    Text(stringResource(R.string.import_mapped_settings, candidate.appliedKeys.sorted().joinToString(", ")))
                    if (candidate.unsupportedKeys.isNotEmpty()) Text(stringResource(R.string.import_unsupported_settings,
                        candidate.unsupportedKeys.sorted().joinToString(", ")))
                    if (candidate.defaultedKeys.isNotEmpty()) Text(stringResource(R.string.import_defaulted_settings,
                        candidate.defaultedKeys.sorted().joinToString(", ")))
                    Row {
                        Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                        Text(stringResource(R.string.import_accept_differences))
                    }
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
        },
        confirmButton = {
            TextButton(enabled = candidate != null && accepted,
                onClick = { onChoose(review.requestId, true) }) {
                Text(stringResource(R.string.import_model_and_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(review.requestId, false) }) {
                Text(stringResource(R.string.import_model_only))
            }
        })
}

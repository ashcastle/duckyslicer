package com.ashcastle.duckyslicer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun ProfileImportReviewDialog(review: ProfileBundleImportResult?, onDecision: (Boolean) -> Unit) {
    if (review == null) return
    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text(stringResource(R.string.import_profiles)) },
        text = {
            Text(stringResource(R.string.profile_import_review,
                review.importedPrinters, review.importedFilaments, review.importedSlicing,
                review.skippedDuplicates, review.renamedConflicts))
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) { Text(stringResource(R.string.import_profiles)) }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

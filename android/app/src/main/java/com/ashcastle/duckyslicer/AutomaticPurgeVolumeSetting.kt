package com.ashcastle.duckyslicer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AutomaticPurgeVolumeSetting(
    options: SliceOptions,
    onChanged: (MultiMaterialSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var calculating by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            if (calculating) return@OutlinedButton
            calculating = true
            failed = false
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        SlicerProcessClient.calculatePurgeVolumes(options)
                    }
                }.onSuccess { volumes ->
                    onChanged(options.multiMaterial.copy(purgeVolumes = volumes))
                }.onFailure {
                    failed = true
                }
                calculating = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !calculating,
    ) {
        Text(
            stringResource(
                if (calculating) {
                    R.string.calculating_purge_volumes
                } else {
                    R.string.calculate_purge_volumes
                },
            ),
        )
    }
    if (failed) {
        Text(stringResource(R.string.purge_volume_calculation_failed))
    }
}

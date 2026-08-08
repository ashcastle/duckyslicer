package com.ashcastle.duckyslicer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun AppSettingsSheet(
    settings: AppSettings,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)

            Text(stringResource(R.string.preview_settings), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.preview_detail), color = Color(0xFFC8C9C2))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewDetail.entries.forEach { detail ->
                    FilterChip(
                        selected = settings.previewDetail == detail,
                        onClick = { onSettingsChanged(settings.copy(previewDetail = detail)) },
                        label = {
                            Text(
                                stringResource(
                                    when (detail) {
                                        PreviewDetail.PERFORMANCE -> R.string.preview_detail_performance
                                        PreviewDetail.BALANCED -> R.string.preview_detail_balanced
                                        PreviewDetail.DETAIL -> R.string.preview_detail_detail
                                    },
                                ),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFF6C945),
                            selectedLabelColor = Color(0xFF202124),
                        ),
                    )
                }
            }
            Text(
                stringResource(R.string.toolpath_opacity, (settings.toolpathOpacity * 100).roundToInt()),
                color = Color(0xFFC8C9C2),
            )
            Slider(
                value = settings.toolpathOpacity,
                onValueChange = { onSettingsChanged(settings.copy(toolpathOpacity = it)) },
                valueRange = 0.3f..1f,
                colors = duckySliderColors(),
            )
            Text(
                stringResource(
                    R.string.toolpath_depth_contrast,
                    (settings.toolpathDepthContrast * 100).roundToInt(),
                ),
                color = Color(0xFFC8C9C2),
            )
            Slider(
                value = settings.toolpathDepthContrast,
                onValueChange = { onSettingsChanged(settings.copy(toolpathDepthContrast = it)) },
                valueRange = 0f..1f,
                colors = duckySliderColors(),
            )

            SettingsToggle(
                title = stringResource(R.string.keep_screen_awake),
                summary = stringResource(R.string.keep_screen_awake_summary),
                checked = settings.keepScreenAwakeWhileWorking,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(keepScreenAwakeWhileWorking = it))
                },
            )

            Text(stringResource(R.string.connection_settings), fontWeight = FontWeight.Bold)
            SettingsToggle(
                title = stringResource(R.string.confirm_remote_print),
                summary = stringResource(R.string.confirm_remote_print_summary),
                checked = settings.confirmBeforeRemotePrint,
                onCheckedChange = {
                    onSettingsChanged(settings.copy(confirmBeforeRemotePrint = it))
                },
            )
            Text(
                stringResource(R.string.connection_timeout, settings.connectionTimeoutSeconds),
                color = Color(0xFFC8C9C2),
            )
            Slider(
                value = settings.connectionTimeoutSeconds.toFloat(),
                onValueChange = {
                    onSettingsChanged(settings.copy(connectionTimeoutSeconds = it.roundToInt()))
                },
                valueRange = 5f..60f,
                steps = 10,
                colors = duckySliderColors(),
            )
            Text(stringResource(R.string.connection_security_summary), color = Color(0xFFC8C9C2))

            Text(stringResource(R.string.language_settings), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.settings_message), color = Color(0xFFC8C9C2))
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(summary, color = Color(0xFFC8C9C2), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

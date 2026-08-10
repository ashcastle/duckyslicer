package com.ashcastle.duckyslicer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

private val ObjectSettingsYellow = Color(0xFFF6C945)
private val ObjectSettingsPanel = Color(0xFF343530)

private enum class ObjectSettingCategory(val label: Int) {
    QUALITY(R.string.quality),
    STRENGTH(R.string.strength),
    SPEED(R.string.speed),
    SUPPORT(R.string.supports),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ObjectProcessSettingsSheet(
    current: ObjectProcessOverrides,
    options: SliceOptions,
    onApply: (ObjectProcessOverrides) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    var category by remember { mutableStateOf(ObjectSettingCategory.QUALITY) }
    val dirty = draft != current
    val maximumLayerHeight = (options.nozzleDiameter * 0.7f).coerceAtLeast(0.04f)
    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    } * 0.92f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF282925),
        contentColor = Color(0xFFF4F4EE),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.object_process_settings),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.object_process_settings_summary),
                    color = Color(0xFFC8C9C3),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ObjectSettingCategory.entries.forEach { entry ->
                        FilterChip(
                            selected = category == entry,
                            onClick = { category = entry },
                            label = { Text(stringResource(entry.label)) },
                        )
                    }
                }

                when (category) {
                    ObjectSettingCategory.QUALITY -> NullableFloatObjectSetting(
                        label = stringResource(R.string.layer_height),
                        value = draft.layerHeightMm,
                        inheritedValue = options.layerHeight.coerceIn(0.04f, maximumLayerHeight),
                        range = 0.04f..maximumLayerHeight,
                        valueText = ::millimeterText,
                        onValueChanged = { draft = draft.copy(layerHeightMm = it) },
                    )

                    ObjectSettingCategory.STRENGTH -> {
                        NullableIntObjectSetting(
                            label = stringResource(R.string.wall_loops),
                            value = draft.wallLoops,
                            inheritedValue = options.perimeters,
                            range = 0..20,
                            onValueChanged = { draft = draft.copy(wallLoops = it) },
                        )
                        NullableIntObjectSetting(
                            label = stringResource(R.string.top_shell_layers),
                            value = draft.topShellLayers,
                            inheritedValue = options.topSolidLayers,
                            range = 0..100,
                            onValueChanged = { draft = draft.copy(topShellLayers = it) },
                        )
                        NullableIntObjectSetting(
                            label = stringResource(R.string.bottom_shell_layers),
                            value = draft.bottomShellLayers,
                            inheritedValue = options.bottomSolidLayers,
                            range = 0..100,
                            onValueChanged = { draft = draft.copy(bottomShellLayers = it) },
                        )
                        NullableFloatObjectSetting(
                            label = stringResource(R.string.sparse_infill_density),
                            value = draft.sparseInfillDensityPercent,
                            inheritedValue = options.fillDensity * 100f,
                            range = 0f..100f,
                            steps = 99,
                            valueText = ::percentText,
                            onValueChanged = {
                                draft = draft.copy(sparseInfillDensityPercent = it)
                            },
                        )
                    }

                    ObjectSettingCategory.SPEED -> {
                        NullableFloatObjectSetting(
                            label = stringResource(R.string.outer_wall_speed),
                            value = draft.outerWallSpeedMmS,
                            inheritedValue = options.printSpeed,
                            range = 1f..500f,
                            valueText = ::speedText,
                            onValueChanged = { draft = draft.copy(outerWallSpeedMmS = it) },
                        )
                        NullableFloatObjectSetting(
                            label = stringResource(R.string.inner_wall_speed),
                            value = draft.innerWallSpeedMmS,
                            inheritedValue = options.innerWallSpeed,
                            range = 1f..500f,
                            valueText = ::speedText,
                            onValueChanged = { draft = draft.copy(innerWallSpeedMmS = it) },
                        )
                        NullableFloatObjectSetting(
                            label = stringResource(R.string.sparse_infill_speed),
                            value = draft.sparseInfillSpeedMmS,
                            inheritedValue = options.sparseInfillSpeed,
                            range = 1f..500f,
                            valueText = ::speedText,
                            onValueChanged = { draft = draft.copy(sparseInfillSpeedMmS = it) },
                        )
                    }

                    ObjectSettingCategory.SUPPORT -> BooleanObjectSetting(
                        label = stringResource(R.string.enable_supports),
                        value = draft.supportEnabled,
                        inheritedValue = options.supportEnabled,
                        onValueChanged = { draft = draft.copy(supportEnabled = it) },
                    )
                }
            }
            if (dirty) {
                ObjectSettingsDirtyBar(
                    onRevert = { draft = current },
                    onApply = { onApply(draft) },
                )
            }
        }
    }
}

@Composable
private fun NullableIntObjectSetting(
    label: String,
    value: Int?,
    inheritedValue: Int,
    range: IntRange,
    onValueChanged: (Int?) -> Unit,
) {
    val layers = stringResource(R.string.layers)
    NullableFloatObjectSetting(
        label = label,
        value = value?.toFloat(),
        inheritedValue = inheritedValue.toFloat(),
        range = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        valueText = { valueText ->
            "${valueText.toInt()} $layers"
        },
        onValueChanged = { onValueChanged(it?.toInt()) },
    )
}

@Composable
private fun NullableFloatObjectSetting(
    label: String,
    value: Float?,
    inheritedValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueText: (Float) -> String,
    onValueChanged: (Float?) -> Unit,
) {
    val shownValue = (value ?: inheritedValue).coerceIn(range)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ObjectSettingsPanel,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(
                            if (value == null) R.string.object_setting_profile_value
                            else R.string.object_setting_override_value,
                            valueText(shownValue),
                        ),
                        color = if (value == null) Color(0xFFB8BAB4) else ObjectSettingsYellow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = value != null,
                    modifier = Modifier.semantics { contentDescription = label },
                    onCheckedChange = { enabled ->
                        onValueChanged(if (enabled) inheritedValue.coerceIn(range) else null)
                    },
                )
            }
            Slider(
                value = shownValue,
                onValueChange = { if (value != null) onValueChanged(it) },
                enabled = value != null,
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = ObjectSettingsYellow,
                    activeTrackColor = ObjectSettingsYellow,
                    inactiveTrackColor = Color(0xFF555650),
                ),
            )
        }
    }
}

@Composable
private fun BooleanObjectSetting(
    label: String,
    value: Boolean?,
    inheritedValue: Boolean,
    onValueChanged: (Boolean?) -> Unit,
) {
    val enableForObjectLabel = stringResource(R.string.enable_for_object)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ObjectSettingsPanel,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(
                            if (value == null) R.string.object_setting_profile_value
                            else R.string.object_setting_override_value,
                            stringResource(
                                if (value ?: inheritedValue) R.string.object_setting_on
                                else R.string.object_setting_off,
                            ),
                        ),
                        color = if (value == null) Color(0xFFB8BAB4) else ObjectSettingsYellow,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = value != null,
                    modifier = Modifier.semantics { contentDescription = label },
                    onCheckedChange = { enabled ->
                        onValueChanged(if (enabled) inheritedValue else null)
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.enable_for_object),
                    modifier = Modifier.weight(1f),
                    color = if (value == null) Color(0xFF777872) else Color(0xFFF4F4EE),
                )
                Switch(
                    checked = value ?: inheritedValue,
                    enabled = value != null,
                    modifier = Modifier.semantics {
                        contentDescription = enableForObjectLabel
                    },
                    onCheckedChange = { onValueChanged(it) },
                )
            }
        }
    }
}

@Composable
private fun ObjectSettingsDirtyBar(
    onRevert: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF20211F),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onRevert, modifier = Modifier.weight(3f)) {
                Text(stringResource(R.string.revert_changes))
            }
            Button(onClick = onApply, modifier = Modifier.weight(7f)) {
                Text(stringResource(R.string.apply_changes))
            }
        }
    }
}

private fun millimeterText(value: Float): String =
    String.format(Locale.getDefault(), "%.2f mm", value)

private fun percentText(value: Float): String =
    String.format(Locale.getDefault(), "%.0f%%", value)

private fun speedText(value: Float): String =
    String.format(Locale.getDefault(), "%.0f mm/s", value)

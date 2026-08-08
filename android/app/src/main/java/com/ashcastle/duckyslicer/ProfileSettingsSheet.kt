package com.ashcastle.duckyslicer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private enum class ProfileSettingsKind {
    PRINTER,
    FILAMENT,
    SLICING,
}

@Composable
internal fun ProfileSettings(
    options: SliceOptions,
    enabled: Boolean,
    onOptionsChanged: (SliceOptions) -> Unit,
) {
    var editing by remember { mutableStateOf<ProfileSettingsKind?>(null) }

    Text(stringResource(R.string.profiles), fontWeight = FontWeight.Bold)
    ProfileRow(
        title = stringResource(R.string.printer_profile),
        summary = stringResource(
            R.string.printer_profile_summary,
            options.bedSizeX.roundToInt(),
            options.bedSizeY.roundToInt(),
            options.nozzleDiameter,
        ),
        enabled = enabled,
        onClick = { editing = ProfileSettingsKind.PRINTER },
    )
    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
    ProfileRow(
        title = stringResource(R.string.filament_profile),
        summary = stringResource(
            R.string.filament_profile_summary,
            filamentLabel(options.filamentProfile),
            options.nozzleTemp,
            options.bedTemp,
        ),
        enabled = enabled,
        onClick = { editing = ProfileSettingsKind.FILAMENT },
    )
    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
    ProfileRow(
        title = stringResource(R.string.slicing_profile),
        summary = stringResource(
            R.string.slicing_profile_summary,
            qualityLabel(options.quality),
            options.layerHeight,
            (options.fillDensity * 100f).roundToInt(),
        ),
        enabled = enabled,
        onClick = { editing = ProfileSettingsKind.SLICING },
    )

    when (editing) {
        ProfileSettingsKind.PRINTER -> PrinterSettingsSheet(
            options = options,
            onOptionsChanged = onOptionsChanged,
            onDismiss = { editing = null },
        )

        ProfileSettingsKind.FILAMENT -> FilamentSettingsSheet(
            options = options,
            onOptionsChanged = onOptionsChanged,
            onDismiss = { editing = null },
        )

        ProfileSettingsKind.SLICING -> SlicingSettingsSheet(
            options = options,
            onOptionsChanged = onOptionsChanged,
            onDismiss = { editing = null },
        )

        null -> Unit
    }
}

@Composable
private fun ProfileRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(summary, color = Color(0xFFC8C9C2), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.edit_details))
    }
}

@Composable
private fun PrinterSettingsSheet(
    options: SliceOptions,
    onOptionsChanged: (SliceOptions) -> Unit,
    onDismiss: () -> Unit,
) = SettingsSheet(title = stringResource(R.string.printer_profile), onDismiss = onDismiss) {
    ProfileChips(
        entries = PrinterProfile.entries,
        selected = options.printerProfile,
        label = { printerLabel(it) },
        onSelected = { onOptionsChanged(options.selectPrinter(it)) },
    )
    SettingSlider(
        label = stringResource(R.string.bed_width),
        valueText = stringResource(R.string.millimeters_value, options.bedSizeX),
        value = options.bedSizeX,
        range = 180f..400f,
        steps = 43,
        onValueChange = { onOptionsChanged(options.copy(bedSizeX = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.bed_depth),
        valueText = stringResource(R.string.millimeters_value, options.bedSizeY),
        value = options.bedSizeY,
        range = 180f..400f,
        steps = 43,
        onValueChange = { onOptionsChanged(options.copy(bedSizeY = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.max_print_height),
        valueText = stringResource(R.string.millimeters_value, options.maxPrintHeight),
        value = options.maxPrintHeight,
        range = 150f..500f,
        steps = 34,
        onValueChange = { onOptionsChanged(options.copy(maxPrintHeight = (it / 10f).roundToInt() * 10f)) },
    )
    SettingSlider(
        label = stringResource(R.string.nozzle_diameter),
        valueText = String.format(Locale.US, "%.1f mm", options.nozzleDiameter),
        value = options.nozzleDiameter,
        range = 0.2f..1.0f,
        steps = 7,
        onValueChange = { onOptionsChanged(options.copy(nozzleDiameter = (it * 10f).roundToInt() / 10f)) },
    )
}

@Composable
private fun FilamentSettingsSheet(
    options: SliceOptions,
    onOptionsChanged: (SliceOptions) -> Unit,
    onDismiss: () -> Unit,
) = SettingsSheet(title = stringResource(R.string.filament_profile), onDismiss = onDismiss) {
    ProfileChips(
        entries = FilamentProfile.entries,
        selected = options.filamentProfile,
        label = { filamentLabel(it) },
        onSelected = { onOptionsChanged(options.selectFilament(it)) },
    )
    SettingSlider(
        label = stringResource(R.string.nozzle_temperature),
        valueText = stringResource(R.string.celsius_value, options.nozzleTemp),
        value = options.nozzleTemp.toFloat(),
        range = 170f..300f,
        steps = 129,
        onValueChange = { onOptionsChanged(options.copy(nozzleTemp = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.bed_temperature),
        valueText = stringResource(R.string.celsius_value, options.bedTemp),
        value = options.bedTemp.toFloat(),
        range = 0f..120f,
        steps = 119,
        onValueChange = { onOptionsChanged(options.copy(bedTemp = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.flow_ratio),
        valueText = String.format(Locale.US, "%.2f", options.flowRatio),
        value = options.flowRatio,
        range = 0.8f..1.2f,
        steps = 39,
        onValueChange = { onOptionsChanged(options.copy(flowRatio = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.max_volumetric_speed),
        valueText = String.format(Locale.US, "%.0f mm³/s", options.maxVolumetricSpeed),
        value = options.maxVolumetricSpeed,
        range = 4f..40f,
        steps = 35,
        onValueChange = { onOptionsChanged(options.copy(maxVolumetricSpeed = it.roundToInt().toFloat())) },
    )
}

@Composable
private fun SlicingSettingsSheet(
    options: SliceOptions,
    onOptionsChanged: (SliceOptions) -> Unit,
    onDismiss: () -> Unit,
) = SettingsSheet(title = stringResource(R.string.slicing_profile), onDismiss = onDismiss) {
    ProfileChips(
        entries = QualityProfile.entries,
        selected = options.quality,
        label = { qualityLabel(it) },
        onSelected = { onOptionsChanged(options.selectQuality(it)) },
    )
    SettingSlider(
        label = stringResource(R.string.layer_height),
        valueText = String.format(Locale.US, "%.2f mm", options.layerHeight),
        value = options.layerHeight,
        range = 0.08f..0.40f,
        steps = 31,
        onValueChange = { onOptionsChanged(options.copy(layerHeight = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.first_layer_height),
        valueText = String.format(Locale.US, "%.2f mm", options.firstLayerHeight),
        value = options.firstLayerHeight,
        range = 0.10f..0.50f,
        steps = 39,
        onValueChange = { onOptionsChanged(options.copy(firstLayerHeight = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.walls),
        valueText = options.perimeters.toString(),
        value = options.perimeters.toFloat(),
        range = 1f..6f,
        steps = 4,
        onValueChange = { onOptionsChanged(options.copy(perimeters = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.infill),
        valueText = stringResource(R.string.percent_value, (options.fillDensity * 100f).roundToInt()),
        value = options.fillDensity,
        range = 0f..1f,
        steps = 19,
        onValueChange = { onOptionsChanged(options.copy(fillDensity = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.print_speed),
        valueText = String.format(Locale.US, "%.0f mm/s", options.printSpeed),
        value = options.printSpeed,
        range = 40f..300f,
        steps = 25,
        onValueChange = { onOptionsChanged(options.copy(printSpeed = (it / 10f).roundToInt() * 10f)) },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.supports), fontWeight = FontWeight.SemiBold)
        Switch(
            checked = options.supportEnabled,
            onCheckedChange = { onOptionsChanged(options.copy(supportEnabled = it)) },
        )
    }
    SettingSlider(
        label = stringResource(R.string.brim_width),
        valueText = stringResource(R.string.millimeters_value, options.brimWidth),
        value = options.brimWidth,
        range = 0f..20f,
        steps = 19,
        onValueChange = { onOptionsChanged(options.copy(brimWidth = it.roundToInt().toFloat())) },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            content()
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun <T> ProfileChips(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelected(entry) },
                label = { Text(label(entry), maxLines = 1) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(valueText, color = Color(0xFFC8C9C2))
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun printerLabel(profile: PrinterProfile) = when (profile) {
    PrinterProfile.STANDARD_270 -> stringResource(R.string.printer_270)
    PrinterProfile.COMPACT_220 -> stringResource(R.string.printer_220)
}

@Composable
private fun filamentLabel(profile: FilamentProfile) = when (profile) {
    FilamentProfile.PLA -> stringResource(R.string.filament_pla)
    FilamentProfile.PETG -> stringResource(R.string.filament_petg)
    FilamentProfile.ABS -> stringResource(R.string.filament_abs)
}

@Composable
private fun qualityLabel(profile: QualityProfile) = when (profile) {
    QualityProfile.DRAFT -> stringResource(R.string.quality_draft)
    QualityProfile.STANDARD -> stringResource(R.string.quality_standard)
    QualityProfile.FINE -> stringResource(R.string.quality_fine)
}

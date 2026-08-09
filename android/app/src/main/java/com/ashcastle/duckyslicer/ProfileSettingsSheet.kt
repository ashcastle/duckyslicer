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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

private enum class ProfileSettingsKind {
    PRINTER,
    FILAMENT,
    SLICING,
}

@Composable
internal fun ProfileSettings(
    options: SliceOptions,
    catalog: ProfileCatalog,
    enabled: Boolean,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinter: (String) -> Unit,
    onSaveFilament: (String) -> Unit,
    onSaveSlicing: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<ProfileSettingsKind?>(null) }

    Text(stringResource(R.string.profiles), fontWeight = FontWeight.Bold)
    ProfileRow(
        title = stringResource(R.string.printer_profile),
        summary = stringResource(
            R.string.printer_profile_summary,
            profileLabel(options.printerProfile),
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
            profileLabel(options.filamentProfile),
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
            profileLabel(options.quality),
            options.layerHeight,
            (options.fillDensity * 100f).roundToInt(),
        ),
        enabled = enabled,
        onClick = { editing = ProfileSettingsKind.SLICING },
    )

    when (editing) {
        ProfileSettingsKind.PRINTER -> PrinterSettingsSheet(
            options = options,
            profiles = catalog.printers,
            onOptionsChanged = onOptionsChanged,
            onSave = onSavePrinter,
            onDismiss = { editing = null },
        )

        ProfileSettingsKind.FILAMENT -> FilamentSettingsSheet(
            options = options,
            profiles = catalog.filaments,
            onOptionsChanged = onOptionsChanged,
            onSave = onSaveFilament,
            onDismiss = { editing = null },
        )

        ProfileSettingsKind.SLICING -> SlicingSettingsSheet(
            options = options,
            profiles = catalog.slicing.filter {
                it == options.quality || abs(it.nozzleDiameter - options.nozzleDiameter) < 0.05f
            },
            onOptionsChanged = onOptionsChanged,
            onSave = onSaveSlicing,
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
    profiles: List<PrinterProfile>,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) = SettingsSheet(title = stringResource(R.string.printer_profile), onDismiss = onDismiss) {
    SearchableGroupedProfileChoices(
        entries = profiles,
        selected = options.printerProfile,
        label = { profileLabel(it) },
        brand = { it.brand },
        builtIn = { it.builtIn },
        searchTerms = {
            listOf(it.name, it.brand.orEmpty(), it.nozzleDiameter.toString(), "nozzle")
        },
        onSelected = { onOptionsChanged(options.selectPrinter(it)) },
    )
    Text(
        stringResource(
            R.string.build_volume_summary,
            options.bedSizeX.roundToInt(),
            options.bedSizeY.roundToInt(),
            options.maxPrintHeight.roundToInt(),
        ),
        color = Color(0xFFC8C9C2),
    )
    SaveProfileField(onSave = onSave, onDismiss = onDismiss)
}

@Composable
private fun FilamentSettingsSheet(
    options: SliceOptions,
    profiles: List<FilamentProfile>,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) = SettingsSheet(title = stringResource(R.string.filament_profile), onDismiss = onDismiss) {
    SearchableGroupedProfileChoices(
        entries = profiles,
        selected = options.filamentProfile,
        label = { profileLabel(it) },
        brand = { it.brand },
        builtIn = { it.builtIn },
        searchTerms = {
            listOf(it.name, it.brand.orEmpty(), it.nativeName)
        },
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
        label = stringResource(R.string.first_layer_nozzle_temperature),
        valueText = stringResource(R.string.celsius_value, options.firstLayerNozzleTemp),
        value = options.firstLayerNozzleTemp.toFloat(),
        range = 170f..300f,
        steps = 129,
        onValueChange = { onOptionsChanged(options.copy(firstLayerNozzleTemp = it.roundToInt())) },
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
        label = stringResource(R.string.first_layer_bed_temperature),
        valueText = stringResource(R.string.celsius_value, options.firstLayerBedTemp),
        value = options.firstLayerBedTemp.toFloat(),
        range = 0f..120f,
        steps = 119,
        onValueChange = { onOptionsChanged(options.copy(firstLayerBedTemp = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.flow_ratio),
        valueText = stringResource(R.string.flow_ratio_value, options.flowRatio),
        value = options.flowRatio,
        range = 0.8f..1.2f,
        steps = 39,
        onValueChange = { onOptionsChanged(options.copy(flowRatio = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.max_volumetric_speed),
        valueText = stringResource(R.string.volumetric_speed_value, options.maxVolumetricSpeed),
        value = options.maxVolumetricSpeed,
        range = 4f..40f,
        steps = 35,
        onValueChange = { onOptionsChanged(options.copy(maxVolumetricSpeed = it.roundToInt().toFloat())) },
    )
    SaveProfileField(onSave = onSave, onDismiss = onDismiss)
}

@Composable
private fun SlicingSettingsSheet(
    options: SliceOptions,
    profiles: List<QualityProfile>,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) = SettingsSheet(title = stringResource(R.string.slicing_profile), onDismiss = onDismiss) {
    val maximumLayerHeight = (options.nozzleDiameter * 0.7f).coerceAtLeast(0.14f)
    val layerHeightSteps = ((maximumLayerHeight - 0.04f) / 0.01f).roundToInt().coerceAtLeast(2) - 1
    ProfileChoices(
        entries = profiles,
        selected = options.quality,
        label = { profileLabel(it) },
        onSelected = { onOptionsChanged(options.selectQuality(it)) },
    )
    SettingSlider(
        label = stringResource(R.string.layer_height),
        valueText = stringResource(R.string.millimeters_value_precise, options.layerHeight),
        value = options.layerHeight,
        range = 0.04f..maximumLayerHeight,
        steps = layerHeightSteps,
        onValueChange = { onOptionsChanged(options.copy(layerHeight = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.first_layer_height),
        valueText = stringResource(R.string.millimeters_value_precise, options.firstLayerHeight),
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
        valueText = stringResource(R.string.print_speed_value, options.printSpeed),
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
    SaveProfileField(onSave = onSave, onDismiss = onDismiss)
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
private fun <T> ProfileChoices(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelected(entry) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = entry == selected, onClick = { onSelected(entry) })
                Text(label(entry), maxLines = 1)
            }
        }
    }
}

private data class ProfileChoiceGroup<T>(
    val key: String,
    val title: String,
    val entries: List<T>,
)

@Composable
private fun <T> SearchableGroupedProfileChoices(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    brand: (T) -> String?,
    builtIn: (T) -> Boolean,
    searchTerms: (T) -> List<String>,
    onSelected: (T) -> Unit,
) {
    val myProfiles = stringResource(R.string.my_profiles)
    val otherProfiles = stringResource(R.string.other_profiles)
    val myProfilesKey = "user-profiles"
    var query by remember { mutableStateOf("") }
    val selectedGroupKey = if (builtIn(selected)) {
        "brand:${brand(selected).orEmpty()}"
    } else {
        myProfilesKey
    }
    var expandedGroups by remember(entries) { mutableStateOf(setOf(selectedGroupKey)) }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val matchingEntries = if (normalizedQuery.isBlank()) {
        entries
    } else {
        entries.filter { entry ->
            searchTerms(entry).any { value -> value.lowercase(Locale.ROOT).contains(normalizedQuery) }
        }
    }
    val groups = matchingEntries
        .groupBy { entry ->
            if (builtIn(entry)) "brand:${brand(entry).orEmpty()}" else myProfilesKey
        }
        .map { (key, groupEntries) ->
            val title = if (key == myProfilesKey) {
                myProfiles
            } else {
                brand(groupEntries.first()).orEmpty().ifBlank { otherProfiles }
            }
            ProfileChoiceGroup(key, title, groupEntries)
        }
        .sortedWith(compareBy({ it.key != myProfilesKey }, { it.title.lowercase(Locale.ROOT) }))

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        label = { Text(stringResource(R.string.search_profiles)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (groups.isEmpty()) {
        Text(
            stringResource(R.string.no_profiles_found),
            color = Color(0xFFC8C9C2),
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        groups.forEach { group ->
            val expanded = normalizedQuery.isNotBlank() || group.key in expandedGroups
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedGroups = if (expanded) {
                                expandedGroups - group.key
                            } else {
                                expandedGroups + group.key
                            }
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = null,
                    )
                    Text(
                        "${group.title} · ${group.entries.size}",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (expanded) {
                    group.entries.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(entry) }
                                .padding(start = 18.dp, top = 1.dp, bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = entry == selected,
                                onClick = { onSelected(entry) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFF6C945)),
                            )
                            Text(label(entry), maxLines = 1)
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            }
        }
    }
}

@Composable
private fun SaveProfileField(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.profile_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            onSave(name.trim())
            onDismiss()
        },
        enabled = name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.save_as_new_profile))
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
            colors = duckySliderColors(),
        )
    }
}

@Composable
private fun profileLabel(profile: PrinterProfile) = when (profile.id) {
    PrinterProfile.U1_04.id -> stringResource(R.string.printer_u1_04)
    PrinterProfile.U1_06.id -> stringResource(R.string.printer_u1_06)
    else -> profile.name
}

@Composable
private fun profileLabel(profile: FilamentProfile) = when (profile.id) {
    FilamentProfile.PLA.id -> stringResource(R.string.filament_snapmaker_pla)
    FilamentProfile.PETG.id -> stringResource(R.string.filament_snapmaker_petg)
    FilamentProfile.ABS.id -> stringResource(R.string.filament_snapmaker_abs)
    else -> profile.name
}

@Composable
private fun profileLabel(profile: QualityProfile) = when (profile.id) {
    QualityProfile.DRAFT.id -> stringResource(R.string.quality_draft)
    QualityProfile.STANDARD.id -> stringResource(R.string.quality_standard)
    QualityProfile.FINE.id -> stringResource(R.string.quality_fine)
    QualityProfile.DRAFT_06.id -> stringResource(R.string.quality_draft_06)
    QualityProfile.STANDARD_06.id -> stringResource(R.string.quality_standard_06)
    QualityProfile.FINE_06.id -> stringResource(R.string.quality_fine_06)
    else -> profile.name
}

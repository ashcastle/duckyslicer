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
import kotlin.math.max
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
            onProfileSelected = { printer ->
                var updated = options.selectPrinter(printer)
                if (!updated.filamentProfile.compatiblePrinters.matchesPrinter(printer)) {
                    catalog.filaments.firstOrNull { it.compatiblePrinters.matchesPrinter(printer) }
                        ?.let { updated = updated.selectFilament(it) }
                }
                if (
                    !updated.quality.compatiblePrinters.matchesPrinter(printer) ||
                    abs(updated.quality.nozzleDiameter - printer.nozzleDiameter) >= 0.05f
                ) {
                    catalog.slicing.firstOrNull {
                        it.compatiblePrinters.matchesPrinter(printer) &&
                            abs(it.nozzleDiameter - printer.nozzleDiameter) < 0.05f
                    }?.let { updated = updated.selectQuality(it) }
                }
                onOptionsChanged(updated)
            },
            onOptionsChanged = onOptionsChanged,
            onSave = onSavePrinter,
            onDismiss = { editing = null },
        )

        ProfileSettingsKind.FILAMENT -> FilamentSettingsSheet(
            options = options,
            profiles = catalog.filaments.filter {
                it == options.filamentProfile ||
                    it.compatiblePrinters.matchesPrinter(options.printerProfile)
            },
            onOptionsChanged = onOptionsChanged,
            onSave = onSaveFilament,
            onDismiss = { editing = null },
        )

        ProfileSettingsKind.SLICING -> SlicingSettingsSheet(
            options = options,
            profiles = catalog.slicing.filter {
                it == options.quality ||
                    (
                        abs(it.nozzleDiameter - options.nozzleDiameter) < 0.05f &&
                            it.compatiblePrinters.matchesPrinter(options.printerProfile)
                        )
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
    onProfileSelected: (PrinterProfile) -> Unit,
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
        onSelected = onProfileSelected,
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
    SettingsGroupTitle(stringResource(R.string.build_volume))
    SettingSlider(
        label = stringResource(R.string.bed_width),
        valueText = stringResource(R.string.millimeters_value, options.bedSizeX),
        value = options.bedSizeX,
        range = 100f..500f,
        steps = 399,
        onValueChange = { onOptionsChanged(options.copy(bedSizeX = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.bed_depth),
        valueText = stringResource(R.string.millimeters_value, options.bedSizeY),
        value = options.bedSizeY,
        range = 100f..500f,
        steps = 399,
        onValueChange = { onOptionsChanged(options.copy(bedSizeY = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.build_height),
        valueText = stringResource(R.string.millimeters_value, options.maxPrintHeight),
        value = options.maxPrintHeight,
        range = 100f..600f,
        steps = 499,
        onValueChange = { onOptionsChanged(options.copy(maxPrintHeight = it.roundToInt().toFloat())) },
    )
    Text(stringResource(R.string.nozzle_diameter), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf(0.2f, 0.4f, 0.6f, 0.8f),
        selected = options.nozzleDiameter,
        label = { stringResource(R.string.millimeters_value_precise, it) },
        onSelected = {
            onOptionsChanged(
                options.copy(nozzleDiameter = it)
                    .selectQuality(QualityProfile.standardFor(it)),
            )
        },
    )
    SettingsGroupTitle(stringResource(R.string.printer_firmware))
    CompactChoices(
        entries = listOf("marlin", "marlin2", "klipper"),
        selected = options.gcodeFlavor,
        label = {
            when (it) {
                "marlin2" -> "Marlin 2"
                "klipper" -> "Klipper"
                else -> "Marlin"
            }
        },
        onSelected = { onOptionsChanged(options.copy(gcodeFlavor = it)) },
    )
    SettingsGroupTitle(stringResource(R.string.motion_limits))
    SettingSlider(
        label = stringResource(R.string.maximum_x_speed),
        valueText = stringResource(R.string.print_speed_value, options.maxSpeedX),
        value = options.maxSpeedX,
        range = 50f..700f,
        steps = 649,
        onValueChange = { onOptionsChanged(options.copy(maxSpeedX = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.maximum_y_speed),
        valueText = stringResource(R.string.print_speed_value, options.maxSpeedY),
        value = options.maxSpeedY,
        range = 50f..700f,
        steps = 649,
        onValueChange = { onOptionsChanged(options.copy(maxSpeedY = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.maximum_print_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.maxAccelerationExtruding),
        value = options.maxAccelerationExtruding,
        range = 500f..30_000f,
        steps = 117,
        onValueChange = {
            val value = (it / 250f).roundToInt() * 250f
            onOptionsChanged(
                options.copy(
                    maxAccelerationExtruding = value,
                    maxAccelerationX = max(options.maxAccelerationX, value),
                    maxAccelerationY = max(options.maxAccelerationY, value),
                ),
            )
        },
    )
    SettingSlider(
        label = stringResource(R.string.maximum_travel_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.maxAccelerationTravel),
        value = options.maxAccelerationTravel,
        range = 500f..30_000f,
        steps = 117,
        onValueChange = {
            val value = (it / 250f).roundToInt() * 250f
            onOptionsChanged(
                options.copy(
                    maxAccelerationTravel = value,
                    maxAccelerationX = max(options.maxAccelerationX, value),
                    maxAccelerationY = max(options.maxAccelerationY, value),
                ),
            )
        },
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
    SettingsGroupTitle(stringResource(R.string.retraction))
    SettingSlider(
        label = stringResource(R.string.retraction_length),
        valueText = stringResource(R.string.millimeters_value_precise, options.retractLength),
        value = options.retractLength,
        range = 0f..8f,
        steps = 79,
        onValueChange = { onOptionsChanged(options.copy(retractLength = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.retraction_speed),
        valueText = stringResource(R.string.print_speed_value, options.retractSpeed),
        value = options.retractSpeed,
        range = 10f..100f,
        steps = 89,
        onValueChange = { onOptionsChanged(options.copy(retractSpeed = it.roundToInt().toFloat())) },
    )
    SettingsGroupTitle(stringResource(R.string.cooling))
    SettingSlider(
        label = stringResource(R.string.minimum_fan_speed),
        valueText = stringResource(R.string.percent_value, options.fanMinSpeed),
        value = options.fanMinSpeed.toFloat(),
        range = 0f..100f,
        steps = 99,
        onValueChange = { onOptionsChanged(options.copy(fanMinSpeed = it.roundToInt().coerceAtMost(options.fanMaxSpeed))) },
    )
    SettingSlider(
        label = stringResource(R.string.maximum_fan_speed),
        valueText = stringResource(R.string.percent_value, options.fanMaxSpeed),
        value = options.fanMaxSpeed.toFloat(),
        range = 0f..100f,
        steps = 99,
        onValueChange = { onOptionsChanged(options.copy(fanMaxSpeed = it.roundToInt().coerceAtLeast(options.fanMinSpeed))) },
    )
    SettingSlider(
        label = stringResource(R.string.overhang_fan_speed),
        valueText = stringResource(R.string.percent_value, options.overhangFanSpeed),
        value = options.overhangFanSpeed.toFloat(),
        range = 0f..100f,
        steps = 99,
        onValueChange = { onOptionsChanged(options.copy(overhangFanSpeed = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.slow_down_layer_time),
        valueText = stringResource(R.string.seconds_value, options.slowDownLayerTime),
        value = options.slowDownLayerTime,
        range = 1f..30f,
        steps = 28,
        onValueChange = { onOptionsChanged(options.copy(slowDownLayerTime = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.minimum_print_speed),
        valueText = stringResource(R.string.print_speed_value, options.slowDownMinSpeed),
        value = options.slowDownMinSpeed,
        range = 5f..50f,
        steps = 44,
        onValueChange = { onOptionsChanged(options.copy(slowDownMinSpeed = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.no_fan_first_layers),
        valueText = options.closeFanFirstLayers.toString(),
        value = options.closeFanFirstLayers.toFloat(),
        range = 0f..10f,
        steps = 9,
        onValueChange = { onOptionsChanged(options.copy(closeFanFirstLayers = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.full_fan_layer),
        valueText = options.fullFanSpeedLayer.toString(),
        value = options.fullFanSpeedLayer.toFloat(),
        range = 1f..20f,
        steps = 18,
        onValueChange = { onOptionsChanged(options.copy(fullFanSpeedLayer = it.roundToInt())) },
    )
    SettingsSwitch(
        label = stringResource(R.string.pressure_advance),
        checked = options.pressureAdvanceEnabled,
        onCheckedChange = { onOptionsChanged(options.copy(pressureAdvanceEnabled = it)) },
    )
    if (options.pressureAdvanceEnabled) {
        SettingSlider(
            label = stringResource(R.string.pressure_advance_value),
            valueText = String.format(Locale.ROOT, "%.3f", options.pressureAdvance),
            value = options.pressureAdvance,
            range = 0f..0.2f,
            steps = 199,
            onValueChange = { onOptionsChanged(options.copy(pressureAdvance = it)) },
        )
    }
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
    val minimumLineWidth = options.nozzleDiameter * 0.5f
    val maximumLineWidth = listOf(
        options.nozzleDiameter * 2f,
        options.outerWallLineWidth,
        options.innerWallLineWidth,
        options.topSurfaceLineWidth,
        options.sparseInfillLineWidth,
        options.internalSolidInfillLineWidth,
        options.supportLineWidth,
        options.initialLayerLineWidth,
    ).maxOrNull() ?: options.nozzleDiameter * 2f
    val lineWidthSteps = ((maximumLineWidth - minimumLineWidth) / 0.01f).roundToInt().coerceAtLeast(2) - 1
    val maximumFeatureSpeed = listOf(
        500f,
        options.printSpeed,
        options.innerWallSpeed,
        options.sparseInfillSpeed,
        options.internalSolidInfillSpeed,
        options.topSurfaceSpeed,
        options.supportSpeed,
        options.bridgeSpeed,
        options.gapInfillSpeed,
        options.firstLayerInfillSpeed,
        options.supportInterfaceSpeed,
    ).maxOrNull() ?: 500f
    val featureSpeedSteps = ((maximumFeatureSpeed - 10f) / 5f).roundToInt().coerceAtLeast(2) - 1
    val maximumFlowRatio = listOf(
        1.5f,
        options.bridgeFlowRatio,
        options.internalBridgeFlowRatio,
        options.topSurfaceFlowRatio,
        options.bottomSurfaceFlowRatio,
    ).maxOrNull() ?: 1.5f
    val flowRatioSteps = ((maximumFlowRatio - 0.5f) / 0.01f).roundToInt().coerceAtLeast(2) - 1
    val maximumFeatureAcceleration = listOf(
        20_000f,
        options.defaultAcceleration,
        options.outerWallAcceleration,
        options.innerWallAcceleration,
        options.topSurfaceAcceleration,
        options.travelAcceleration,
        options.firstLayerAcceleration,
    ).maxOrNull() ?: 20_000f
    val featureAccelerationSteps = (maximumFeatureAcceleration / 100f).roundToInt().coerceAtLeast(2) - 1
    SearchableGroupedProfileChoices(
        entries = profiles,
        selected = options.quality,
        label = { profileLabel(it) },
        brand = { it.brand },
        builtIn = { it.builtIn },
        searchTerms = {
            listOf(it.name, it.brand.orEmpty(), it.layerHeightMm.toString())
        },
        onSelected = { onOptionsChanged(options.selectQuality(it)) },
    )
    SettingsGroupTitle(stringResource(R.string.quality))
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
        label = stringResource(R.string.initial_layer_line_width),
        valueText = stringResource(R.string.millimeters_value_precise, options.initialLayerLineWidth),
        value = options.initialLayerLineWidth,
        range = minimumLineWidth..maximumLineWidth,
        steps = lineWidthSteps,
        onValueChange = { onOptionsChanged(options.copy(initialLayerLineWidth = it)) },
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
        label = stringResource(R.string.outer_wall_width),
        valueText = stringResource(R.string.millimeters_value_precise, options.outerWallLineWidth),
        value = options.outerWallLineWidth,
        range = minimumLineWidth..maximumLineWidth,
        steps = lineWidthSteps,
        onValueChange = { onOptionsChanged(options.copy(outerWallLineWidth = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.inner_wall_width),
        valueText = stringResource(R.string.millimeters_value_precise, options.innerWallLineWidth),
        value = options.innerWallLineWidth,
        range = minimumLineWidth..maximumLineWidth,
        steps = lineWidthSteps,
        onValueChange = { onOptionsChanged(options.copy(innerWallLineWidth = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.top_surface_width),
        valueText = stringResource(R.string.millimeters_value_precise, options.topSurfaceLineWidth),
        value = options.topSurfaceLineWidth,
        range = minimumLineWidth..maximumLineWidth,
        steps = lineWidthSteps,
        onValueChange = { onOptionsChanged(options.copy(topSurfaceLineWidth = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.internal_solid_infill_width),
        valueText = stringResource(R.string.millimeters_value_precise, options.internalSolidInfillLineWidth),
        value = options.internalSolidInfillLineWidth,
        range = minimumLineWidth..maximumLineWidth,
        steps = lineWidthSteps,
        onValueChange = { onOptionsChanged(options.copy(internalSolidInfillLineWidth = it)) },
    )
    Text(stringResource(R.string.wall_generator), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf("arachne", "classic"),
        selected = options.wallGenerator,
        label = {
            stringResource(
                if (it == "classic") R.string.wall_generator_classic
                else R.string.wall_generator_arachne,
            )
        },
        onSelected = { onOptionsChanged(options.copy(wallGenerator = it)) },
    )
    Text(stringResource(R.string.wall_order), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf("inner-outer", "outer-inner", "inner-outer-inner"),
        selected = options.wallSequence,
        label = {
            stringResource(
                when (it) {
                    "outer-inner" -> R.string.wall_order_outer_inner
                    "inner-outer-inner" -> R.string.wall_order_inner_outer_inner
                    else -> R.string.wall_order_inner_outer
                },
            )
        },
        onSelected = { onOptionsChanged(options.copy(wallSequence = it)) },
    )
    Text(stringResource(R.string.seam_position), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf("aligned", "nearest", "back", "random"),
        selected = options.seamPosition,
        label = { enumLabel(it) },
        onSelected = { onOptionsChanged(options.copy(seamPosition = it)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.detect_thin_walls),
        checked = options.detectThinWalls,
        onCheckedChange = { onOptionsChanged(options.copy(detectThinWalls = it)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.detect_overhang_walls),
        checked = options.detectOverhangWalls,
        onCheckedChange = { onOptionsChanged(options.copy(detectOverhangWalls = it)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.one_wall_on_top),
        checked = options.onlyOneWallOnTop,
        onCheckedChange = { onOptionsChanged(options.copy(onlyOneWallOnTop = it)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.precise_outer_walls),
        checked = options.preciseOuterWalls,
        onCheckedChange = { onOptionsChanged(options.copy(preciseOuterWalls = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.top_shell_layers),
        valueText = options.topSolidLayers.toString(),
        value = options.topSolidLayers.toFloat(),
        range = 0f..12f,
        steps = 11,
        onValueChange = { onOptionsChanged(options.copy(topSolidLayers = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.bottom_shell_layers),
        valueText = options.bottomSolidLayers.toString(),
        value = options.bottomSolidLayers.toFloat(),
        range = 0f..12f,
        steps = 11,
        onValueChange = { onOptionsChanged(options.copy(bottomSolidLayers = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.top_shell_thickness),
        valueText = stringResource(R.string.millimeters_value_precise, options.topShellThickness),
        value = options.topShellThickness,
        range = 0f..max(5f, options.topShellThickness),
        steps = (max(5f, options.topShellThickness) / 0.05f).roundToInt().coerceAtLeast(2) - 1,
        onValueChange = { onOptionsChanged(options.copy(topShellThickness = (it / 0.05f).roundToInt() * 0.05f)) },
    )
    SettingSlider(
        label = stringResource(R.string.bottom_shell_thickness),
        valueText = stringResource(R.string.millimeters_value_precise, options.bottomShellThickness),
        value = options.bottomShellThickness,
        range = 0f..max(5f, options.bottomShellThickness),
        steps = (max(5f, options.bottomShellThickness) / 0.05f).roundToInt().coerceAtLeast(2) - 1,
        onValueChange = { onOptionsChanged(options.copy(bottomShellThickness = (it / 0.05f).roundToInt() * 0.05f)) },
    )
    SettingsGroupTitle(stringResource(R.string.infill))
    Text(stringResource(R.string.sparse_infill_pattern), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf(
            "crosshatch", "grid", "rectilinear", "gyroid", "cubic",
            "alignedrectilinear", "triangles", "lightning",
        ),
        selected = options.fillPattern,
        label = { fillPatternLabel(it) },
        onSelected = { onOptionsChanged(options.copy(fillPattern = it)) },
    )
    Text(stringResource(R.string.top_surface_pattern), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf("monotonicline", "monotonic", "rectilinear", "concentric"),
        selected = options.topSurfacePattern,
        label = { fillPatternLabel(it) },
        onSelected = { onOptionsChanged(options.copy(topSurfacePattern = it)) },
    )
    Text(stringResource(R.string.bottom_surface_pattern), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf("monotonic", "monotonicline", "rectilinear", "concentric"),
        selected = options.bottomSurfacePattern,
        label = { fillPatternLabel(it) },
        onSelected = { onOptionsChanged(options.copy(bottomSurfacePattern = it)) },
    )
    Text(stringResource(R.string.internal_solid_pattern), fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf("monotonic", "monotonicline", "rectilinear", "grid"),
        selected = options.internalSolidInfillPattern,
        label = { fillPatternLabel(it) },
        onSelected = { onOptionsChanged(options.copy(internalSolidInfillPattern = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.sparse_infill_width),
        valueText = stringResource(R.string.millimeters_value_precise, options.sparseInfillLineWidth),
        value = options.sparseInfillLineWidth,
        range = minimumLineWidth..maximumLineWidth,
        steps = lineWidthSteps,
        onValueChange = { onOptionsChanged(options.copy(sparseInfillLineWidth = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.infill),
        valueText = stringResource(R.string.percent_value, (options.fillDensity * 100f).roundToInt()),
        value = options.fillDensity,
        range = 0f..1f,
        steps = 19,
        onValueChange = { onOptionsChanged(options.copy(fillDensity = it)) },
    )
    SettingsGroupTitle(stringResource(R.string.speed))
    SettingSlider(
        label = stringResource(R.string.print_speed),
        valueText = stringResource(R.string.print_speed_value, options.printSpeed),
        value = options.printSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(printSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.inner_wall_speed),
        valueText = stringResource(R.string.print_speed_value, options.innerWallSpeed),
        value = options.innerWallSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(innerWallSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.sparse_infill_speed),
        valueText = stringResource(R.string.print_speed_value, options.sparseInfillSpeed),
        value = options.sparseInfillSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(sparseInfillSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.internal_solid_infill_speed),
        valueText = stringResource(R.string.print_speed_value, options.internalSolidInfillSpeed),
        value = options.internalSolidInfillSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(internalSolidInfillSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.top_surface_speed),
        valueText = stringResource(R.string.print_speed_value, options.topSurfaceSpeed),
        value = options.topSurfaceSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(topSurfaceSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.travel_speed),
        valueText = stringResource(R.string.print_speed_value, options.travelSpeed),
        value = options.travelSpeed,
        range = 10f..max(700f, options.travelSpeed),
        steps = ((max(700f, options.travelSpeed) - 10f) / 5f).roundToInt().coerceAtLeast(2) - 1,
        onValueChange = { onOptionsChanged(options.copy(travelSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.first_layer_speed),
        valueText = stringResource(R.string.print_speed_value, options.firstLayerSpeed),
        value = options.firstLayerSpeed,
        range = 10f..150f,
        steps = 27,
        onValueChange = { onOptionsChanged(options.copy(firstLayerSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.first_layer_infill_speed),
        valueText = stringResource(R.string.print_speed_value, options.firstLayerInfillSpeed),
        value = options.firstLayerInfillSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(firstLayerInfillSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.bridge_speed),
        valueText = stringResource(R.string.print_speed_value, options.bridgeSpeed),
        value = options.bridgeSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(bridgeSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingSlider(
        label = stringResource(R.string.gap_infill_speed),
        valueText = stringResource(R.string.print_speed_value, options.gapInfillSpeed),
        value = options.gapInfillSpeed,
        range = 10f..maximumFeatureSpeed,
        steps = featureSpeedSteps,
        onValueChange = { onOptionsChanged(options.copy(gapInfillSpeed = (it / 5f).roundToInt() * 5f)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.overhang_speed),
        checked = options.overhangSpeedEnabled,
        onCheckedChange = { onOptionsChanged(options.copy(overhangSpeedEnabled = it)) },
    )
    if (options.overhangSpeedEnabled) {
        OverhangSpeedSetting(
            label = stringResource(R.string.overhang_speed_1),
            value = options.overhangSpeed1,
            percent = options.overhangSpeed1Percent,
            maximumAbsolute = maximumFeatureSpeed,
            onValueChange = { onOptionsChanged(options.copy(overhangSpeed1 = it)) },
            onPercentChange = { onOptionsChanged(options.copy(overhangSpeed1Percent = it)) },
        )
        OverhangSpeedSetting(
            label = stringResource(R.string.overhang_speed_2),
            value = options.overhangSpeed2,
            percent = options.overhangSpeed2Percent,
            maximumAbsolute = maximumFeatureSpeed,
            onValueChange = { onOptionsChanged(options.copy(overhangSpeed2 = it)) },
            onPercentChange = { onOptionsChanged(options.copy(overhangSpeed2Percent = it)) },
        )
        OverhangSpeedSetting(
            label = stringResource(R.string.overhang_speed_3),
            value = options.overhangSpeed3,
            percent = options.overhangSpeed3Percent,
            maximumAbsolute = maximumFeatureSpeed,
            onValueChange = { onOptionsChanged(options.copy(overhangSpeed3 = it)) },
            onPercentChange = { onOptionsChanged(options.copy(overhangSpeed3Percent = it)) },
        )
        OverhangSpeedSetting(
            label = stringResource(R.string.overhang_speed_4),
            value = options.overhangSpeed4,
            percent = options.overhangSpeed4Percent,
            maximumAbsolute = maximumFeatureSpeed,
            onValueChange = { onOptionsChanged(options.copy(overhangSpeed4 = it)) },
            onPercentChange = { onOptionsChanged(options.copy(overhangSpeed4Percent = it)) },
        )
    }
    SettingsGroupTitle(stringResource(R.string.ironing))
    CompactChoices(
        entries = listOf("no ironing", "top", "topmost", "solid"),
        selected = options.ironingType,
        label = { enumLabel(it) },
        onSelected = { onOptionsChanged(options.copy(ironingType = it)) },
    )
    if (options.ironingType != "no ironing") {
        Text(stringResource(R.string.ironing_pattern), fontWeight = FontWeight.SemiBold)
        CompactChoices(
            entries = listOf("rectilinear", "concentric"),
            selected = options.ironingPattern,
            label = { fillPatternLabel(it) },
            onSelected = { onOptionsChanged(options.copy(ironingPattern = it)) },
        )
        SettingSlider(
            label = stringResource(R.string.ironing_flow),
            valueText = stringResource(R.string.percent_value, options.ironingFlow.roundToInt()),
            value = options.ironingFlow,
            range = 0f..100f,
            steps = 99,
            onValueChange = { onOptionsChanged(options.copy(ironingFlow = it.roundToInt().toFloat())) },
        )
        SettingSlider(
            label = stringResource(R.string.ironing_spacing),
            valueText = stringResource(R.string.millimeters_value_precise, options.ironingSpacing),
            value = options.ironingSpacing,
            range = 0.05f..0.5f,
            steps = 44,
            onValueChange = { onOptionsChanged(options.copy(ironingSpacing = it)) },
        )
        SettingSlider(
            label = stringResource(R.string.ironing_speed),
            valueText = stringResource(R.string.print_speed_value, options.ironingSpeed),
            value = options.ironingSpeed,
            range = 1f..maximumFeatureSpeed,
            steps = maximumFeatureSpeed.roundToInt().coerceAtLeast(2) - 2,
            onValueChange = { onOptionsChanged(options.copy(ironingSpeed = it.roundToInt().toFloat())) },
        )
    }
    SettingsGroupTitle(stringResource(R.string.feature_flow_ratio))
    SettingSlider(
        label = stringResource(R.string.bridge_flow_ratio),
        valueText = String.format(Locale.ROOT, "%.2f", options.bridgeFlowRatio),
        value = options.bridgeFlowRatio,
        range = 0.5f..maximumFlowRatio,
        steps = flowRatioSteps,
        onValueChange = { onOptionsChanged(options.copy(bridgeFlowRatio = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.internal_bridge_flow_ratio),
        valueText = String.format(Locale.ROOT, "%.2f", options.internalBridgeFlowRatio),
        value = options.internalBridgeFlowRatio,
        range = 0.5f..maximumFlowRatio,
        steps = flowRatioSteps,
        onValueChange = { onOptionsChanged(options.copy(internalBridgeFlowRatio = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.top_surface_flow_ratio),
        valueText = String.format(Locale.ROOT, "%.2f", options.topSurfaceFlowRatio),
        value = options.topSurfaceFlowRatio,
        range = 0.5f..maximumFlowRatio,
        steps = flowRatioSteps,
        onValueChange = { onOptionsChanged(options.copy(topSurfaceFlowRatio = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.bottom_surface_flow_ratio),
        valueText = String.format(Locale.ROOT, "%.2f", options.bottomSurfaceFlowRatio),
        value = options.bottomSurfaceFlowRatio,
        range = 0.5f..maximumFlowRatio,
        steps = flowRatioSteps,
        onValueChange = { onOptionsChanged(options.copy(bottomSurfaceFlowRatio = it)) },
    )
    SettingsGroupTitle(stringResource(R.string.feature_acceleration))
    SettingSlider(
        label = stringResource(R.string.default_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.defaultAcceleration),
        value = options.defaultAcceleration,
        range = 0f..maximumFeatureAcceleration,
        steps = featureAccelerationSteps,
        onValueChange = { onOptionsChanged(options.copy(defaultAcceleration = (it / 100f).roundToInt() * 100f)) },
    )
    SettingSlider(
        label = stringResource(R.string.outer_wall_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.outerWallAcceleration),
        value = options.outerWallAcceleration,
        range = 0f..maximumFeatureAcceleration,
        steps = featureAccelerationSteps,
        onValueChange = { onOptionsChanged(options.copy(outerWallAcceleration = (it / 100f).roundToInt() * 100f)) },
    )
    SettingSlider(
        label = stringResource(R.string.inner_wall_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.innerWallAcceleration),
        value = options.innerWallAcceleration,
        range = 0f..maximumFeatureAcceleration,
        steps = featureAccelerationSteps,
        onValueChange = { onOptionsChanged(options.copy(innerWallAcceleration = (it / 100f).roundToInt() * 100f)) },
    )
    SettingSlider(
        label = stringResource(R.string.top_surface_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.topSurfaceAcceleration),
        value = options.topSurfaceAcceleration,
        range = 0f..maximumFeatureAcceleration,
        steps = featureAccelerationSteps,
        onValueChange = { onOptionsChanged(options.copy(topSurfaceAcceleration = (it / 100f).roundToInt() * 100f)) },
    )
    SettingSlider(
        label = stringResource(R.string.travel_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.travelAcceleration),
        value = options.travelAcceleration,
        range = 0f..maximumFeatureAcceleration,
        steps = featureAccelerationSteps,
        onValueChange = { onOptionsChanged(options.copy(travelAcceleration = (it / 100f).roundToInt() * 100f)) },
    )
    SettingSlider(
        label = stringResource(R.string.first_layer_acceleration),
        valueText = stringResource(R.string.acceleration_value, options.firstLayerAcceleration),
        value = options.firstLayerAcceleration,
        range = 0f..maximumFeatureAcceleration,
        steps = featureAccelerationSteps,
        onValueChange = { onOptionsChanged(options.copy(firstLayerAcceleration = (it / 100f).roundToInt() * 100f)) },
    )
    SettingsGroupTitle(stringResource(R.string.supports))
    SettingsSwitch(
        label = stringResource(R.string.enable_supports),
        checked = options.supportEnabled,
        onCheckedChange = { onOptionsChanged(options.copy(supportEnabled = it)) },
    )
    if (options.supportEnabled) {
        SettingSlider(
            label = stringResource(R.string.support_speed),
            valueText = stringResource(R.string.print_speed_value, options.supportSpeed),
            value = options.supportSpeed,
            range = 10f..maximumFeatureSpeed,
            steps = featureSpeedSteps,
            onValueChange = { onOptionsChanged(options.copy(supportSpeed = (it / 5f).roundToInt() * 5f)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_interface_speed),
            valueText = stringResource(R.string.print_speed_value, options.supportInterfaceSpeed),
            value = options.supportInterfaceSpeed,
            range = 10f..maximumFeatureSpeed,
            steps = featureSpeedSteps,
            onValueChange = { onOptionsChanged(options.copy(supportInterfaceSpeed = (it / 5f).roundToInt() * 5f)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_line_width),
            valueText = stringResource(R.string.millimeters_value_precise, options.supportLineWidth),
            value = options.supportLineWidth,
            range = minimumLineWidth..maximumLineWidth,
            steps = lineWidthSteps,
            onValueChange = { onOptionsChanged(options.copy(supportLineWidth = it)) },
        )
        CompactChoices(
            entries = listOf("normal", "tree"),
            selected = options.supportType,
            label = { if (it == "tree") stringResource(R.string.tree_support) else stringResource(R.string.normal_support) },
            onSelected = { onOptionsChanged(options.copy(supportType = it)) },
        )
        Text(stringResource(R.string.support_style), fontWeight = FontWeight.SemiBold)
        CompactChoices(
            entries = listOf("default", "grid", "snug", "organic", "tree_hybrid", "tree_slim"),
            selected = options.supportStyle,
            label = { enumLabel(it) },
            onSelected = { onOptionsChanged(options.copy(supportStyle = it)) },
        )
        Text(stringResource(R.string.support_base_pattern), fontWeight = FontWeight.SemiBold)
        CompactChoices(
            entries = listOf("default", "rectilinear", "rectilinear-grid", "lightning", "hollow"),
            selected = options.supportBasePattern,
            label = { enumLabel(it) },
            onSelected = { onOptionsChanged(options.copy(supportBasePattern = it)) },
        )
        Text(stringResource(R.string.support_interface_pattern), fontWeight = FontWeight.SemiBold)
        CompactChoices(
            entries = listOf("auto", "rectilinear", "rectilinear_interlaced", "concentric", "grid"),
            selected = options.supportInterfacePattern,
            label = { enumLabel(it) },
            onSelected = { onOptionsChanged(options.copy(supportInterfacePattern = it)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_threshold_angle),
            valueText = stringResource(R.string.degrees_value, options.supportAngle),
            value = options.supportAngle,
            range = 10f..80f,
            steps = 69,
            onValueChange = { onOptionsChanged(options.copy(supportAngle = it.roundToInt().toFloat())) },
        )
        SettingSlider(
            label = stringResource(R.string.support_top_interface_layers),
            valueText = options.supportInterfaceTopLayers.toString(),
            value = options.supportInterfaceTopLayers.toFloat(),
            range = 0f..20f,
            steps = 19,
            onValueChange = { onOptionsChanged(options.copy(supportInterfaceTopLayers = it.roundToInt())) },
        )
        SettingSlider(
            label = stringResource(R.string.support_bottom_interface_layers),
            valueText = if (options.supportInterfaceBottomLayers < 0) {
                stringResource(R.string.same_as_top)
            } else {
                options.supportInterfaceBottomLayers.toString()
            },
            value = options.supportInterfaceBottomLayers.toFloat(),
            range = -1f..20f,
            steps = 20,
            onValueChange = { onOptionsChanged(options.copy(supportInterfaceBottomLayers = it.roundToInt())) },
        )
        SettingSlider(
            label = stringResource(R.string.support_interface_spacing),
            valueText = stringResource(R.string.millimeters_value_precise, options.supportInterfaceSpacing),
            value = options.supportInterfaceSpacing,
            range = 0f..max(2f, options.supportInterfaceSpacing),
            steps = (max(2f, options.supportInterfaceSpacing) / 0.05f).roundToInt().coerceAtLeast(2) - 1,
            onValueChange = { onOptionsChanged(options.copy(supportInterfaceSpacing = (it / 0.05f).roundToInt() * 0.05f)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_bottom_interface_spacing),
            valueText = stringResource(R.string.millimeters_value_precise, options.supportBottomInterfaceSpacing),
            value = options.supportBottomInterfaceSpacing,
            range = 0f..max(2f, options.supportBottomInterfaceSpacing),
            steps = (max(2f, options.supportBottomInterfaceSpacing) / 0.05f).roundToInt().coerceAtLeast(2) - 1,
            onValueChange = { onOptionsChanged(options.copy(supportBottomInterfaceSpacing = (it / 0.05f).roundToInt() * 0.05f)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_top_z_distance),
            valueText = stringResource(R.string.millimeters_value_precise, options.supportTopZDistance),
            value = options.supportTopZDistance,
            range = 0f..max(2f, options.supportTopZDistance),
            steps = (max(2f, options.supportTopZDistance) / 0.02f).roundToInt().coerceAtLeast(2) - 1,
            onValueChange = { onOptionsChanged(options.copy(supportTopZDistance = (it / 0.02f).roundToInt() * 0.02f)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_bottom_z_distance),
            valueText = stringResource(R.string.millimeters_value_precise, options.supportBottomZDistance),
            value = options.supportBottomZDistance,
            range = 0f..max(2f, options.supportBottomZDistance),
            steps = (max(2f, options.supportBottomZDistance) / 0.02f).roundToInt().coerceAtLeast(2) - 1,
            onValueChange = { onOptionsChanged(options.copy(supportBottomZDistance = (it / 0.02f).roundToInt() * 0.02f)) },
        )
        SettingSlider(
            label = stringResource(R.string.support_object_xy_distance),
            valueText = stringResource(R.string.millimeters_value_precise, options.supportObjectXYDistance),
            value = options.supportObjectXYDistance,
            range = 0f..max(5f, options.supportObjectXYDistance),
            steps = (max(5f, options.supportObjectXYDistance) / 0.05f).roundToInt().coerceAtLeast(2) - 1,
            onValueChange = { onOptionsChanged(options.copy(supportObjectXYDistance = (it / 0.05f).roundToInt() * 0.05f)) },
        )
    }
    SettingsGroupTitle(stringResource(R.string.bed_adhesion))
    SettingSlider(
        label = stringResource(R.string.skirt_loops),
        valueText = options.skirtLoops.toString(),
        value = options.skirtLoops.toFloat(),
        range = 0f..10f,
        steps = 9,
        onValueChange = { onOptionsChanged(options.copy(skirtLoops = it.roundToInt())) },
    )
    SettingSlider(
        label = stringResource(R.string.skirt_distance),
        valueText = stringResource(R.string.millimeters_value, options.skirtDistance),
        value = options.skirtDistance,
        range = 1f..20f,
        steps = 18,
        onValueChange = { onOptionsChanged(options.copy(skirtDistance = it.roundToInt().toFloat())) },
    )
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
private fun SettingsGroupTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
}

@Composable
private fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OverhangSpeedSetting(
    label: String,
    value: Float,
    percent: Boolean,
    maximumAbsolute: Float,
    onValueChange: (Float) -> Unit,
    onPercentChange: (Boolean) -> Unit,
) {
    Text(label, fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf(false, true),
        selected = percent,
        label = { if (it) "%" else "mm/s" },
        onSelected = onPercentChange,
    )
    val maximum = if (percent) 100f else maximumAbsolute
    SettingSlider(
        label = label,
        valueText = if (percent) {
            stringResource(R.string.percent_value, value.coerceAtMost(100f).roundToInt())
        } else {
            stringResource(R.string.print_speed_value, value)
        },
        value = value.coerceIn(0f, maximum),
        range = 0f..maximum,
        steps = maximum.roundToInt().coerceAtLeast(2) - 1,
        onValueChange = onValueChange,
    )
}

@Composable
private fun <T> CompactChoices(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onSelected(entry) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = entry == selected, onClick = { onSelected(entry) })
                Text(label(entry))
            }
        }
    }
}

@Composable
private fun fillPatternLabel(value: String): String = when (value) {
    "grid" -> stringResource(R.string.infill_grid)
    "honeycomb" -> stringResource(R.string.infill_honeycomb)
    "rectilinear" -> stringResource(R.string.infill_rectilinear)
    "alignedrectilinear" -> stringResource(R.string.infill_aligned_rectilinear)
    "gyroid" -> stringResource(R.string.infill_gyroid)
    else -> enumLabel(value)
}

private fun enumLabel(value: String): String = value
    .replace('_', ' ')
    .replace('-', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

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

private data class ProfileChoiceGroup<T>(
    val key: String,
    val title: String,
    val entries: List<T>,
)

private fun List<String>.matchesPrinter(printer: PrinterProfile): Boolean =
    isEmpty() || printer.name in this

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

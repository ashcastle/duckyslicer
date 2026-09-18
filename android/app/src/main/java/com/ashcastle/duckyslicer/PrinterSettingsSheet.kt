package com.ashcastle.duckyslicer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun PrinterSettingsSheet(
    options: SliceOptions,
    profiles: List<PrinterProfile>,
    recentIds: List<String>,
    onProfileSelected: (PrinterProfile) -> Unit,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String, SliceOptions) -> Unit,
    onUpdate: (SliceOptions) -> Unit,
    onRename: (PrinterProfile, String) -> Unit,
    onDelete: (String) -> Unit,
    dirty: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var selectedExtruder by rememberSaveable(options.printerProfile.id) { mutableStateOf(0) }
    LaunchedEffect(options.printerProfile.extruderCount) {
        selectedExtruder = selectedExtruder.coerceIn(0, options.printerProfile.extruderCount - 1)
    }
    val activeExtruder = selectedExtruder.coerceIn(0, options.printerProfile.extruderCount - 1)
    val extruderOffsetsX = options.printerProfile.resolvedExtruderOffsetsX()
    val extruderOffsetsY = options.printerProfile.resolvedExtruderOffsetsY()
    val toolChangeRetractLengths = options.printerProfile.resolvedToolChangeRetractLengths()
    val toolChangeRetractRestartExtras =
        options.printerProfile.resolvedToolChangeRetractRestartExtras()
    SettingsSheet(
        title = stringResource(R.string.printer_profile),
        onDismiss = onDismiss,
        dirty = dirty,
        onRevert = onRevert,
        onApply = onApply,
        settingQuery = settingsQuery,
        onSettingQueryChanged = { settingsQuery = it },
        header = {
            CurrentProfileButton(
                profile = profileLabel(options.printerProfile),
                onClick = { profilesOpen = true },
            )
            UpdateProfileButton(
                visible = !options.printerProfile.builtIn,
                onUpdate = { onUpdate(options) },
                onDismiss = onDismiss,
            )
        },
    ) {
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
            onValueChange = {
                val width = it.roundToInt().toFloat()
                onOptionsChanged(
                    options.copy(
                        bedSizeX = width,
                        bedOriginX = scaledBedOrigin(options.bedOriginX, options.bedSizeX, width),
                        bedPolygon =
                            scaledBedPolygon(
                                options.bedPolygon,
                                options.bedSizeX,
                                options.bedSizeY,
                                width,
                                options.bedSizeY,
                            ),
                        bedExcludeArea =
                            scaledBedExcludeArea(
                                options.bedExcludeArea,
                                options.bedSizeX,
                                options.bedSizeY,
                                width,
                                options.bedSizeY,
                            ),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.bed_depth),
            valueText = stringResource(R.string.millimeters_value, options.bedSizeY),
            value = options.bedSizeY,
            range = 100f..500f,
            steps = 399,
            onValueChange = {
                val depth = it.roundToInt().toFloat()
                onOptionsChanged(
                    options.copy(
                        bedSizeY = depth,
                        bedOriginY = scaledBedOrigin(options.bedOriginY, options.bedSizeY, depth),
                        bedPolygon =
                            scaledBedPolygon(
                                options.bedPolygon,
                                options.bedSizeX,
                                options.bedSizeY,
                                options.bedSizeX,
                                depth,
                            ),
                        bedExcludeArea =
                            scaledBedExcludeArea(
                                options.bedExcludeArea,
                                options.bedSizeX,
                                options.bedSizeY,
                                options.bedSizeX,
                                depth,
                            ),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.build_height),
            valueText = stringResource(R.string.millimeters_value, options.maxPrintHeight),
            value = options.maxPrintHeight,
            range = 100f..600f,
            steps = 499,
            onValueChange = { onOptionsChanged(options.copy(maxPrintHeight = it.roundToInt().toFloat())) },
        )
        BedExcludeAreaSetting(
            value = options.bedExcludeArea,
            bedSizeX = options.bedSizeX,
            bedSizeY = options.bedSizeY,
            onValueChange = { onOptionsChanged(options.copy(bedExcludeArea = it)) },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.nozzle_diameter),
            entries = listOf(0.2f, 0.4f, 0.6f, 0.8f),
            selected = options.nozzleDiameter,
            optionLabel = { stringResource(R.string.millimeters_value_precise, it) },
            onSelected = {
                // Editing hardware must not replace the user's process settings with
                // an unrelated printer's fallback profile.
                onOptionsChanged(options.copy(nozzleDiameter = it))
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.nozzle_material),
            entries = NozzleMaterial.entries,
            selected = options.printerProfile.nozzleMaterial,
            optionLabel = { material ->
                stringResource(
                    when (material) {
                        NozzleMaterial.UNDEFINED -> R.string.nozzle_material_unspecified
                        NozzleMaterial.HARDENED_STEEL -> R.string.nozzle_material_hardened_steel
                        NozzleMaterial.STAINLESS_STEEL -> R.string.nozzle_material_stainless_steel
                        NozzleMaterial.BRASS -> R.string.nozzle_material_brass
                    },
                )
            },
            onSelected = { material ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(nozzleMaterial = material),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.nozzle_hardness),
            valueText =
                if (options.printerProfile.nozzleHrc > 0) {
                    stringResource(R.string.hrc_value, options.printerProfile.nozzleHrc)
                } else if (options.printerProfile.nozzleMaterial.fallbackHrc > 0) {
                    stringResource(
                        R.string.nozzle_hardness_automatic_value,
                        options.printerProfile.nozzleMaterial.fallbackHrc,
                    )
                } else {
                    stringResource(R.string.nozzle_hardness_not_set)
                },
            value = options.printerProfile.nozzleHrc.toFloat(),
            range = 0f..500f,
            steps = 499,
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(nozzleHrc = value.roundToInt()),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.nozzle_height),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    options.printerProfile.nozzleHeight,
                ),
            value = options.printerProfile.nozzleHeight,
            minimum = 0.1f,
            defaultMaximum = 20f,
            increment = 0.1f,
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(nozzleHeight = value),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.nozzle_volume),
            valueText =
                stringResource(
                    R.string.cubic_millimeters_value,
                    options.printerProfile.nozzleVolume,
                ),
            value = options.printerProfile.nozzleVolume,
            minimum = 0f,
            defaultMaximum = 250f,
            increment = 0.1f,
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(nozzleVolume = value),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.minimum_layer_height),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    options.printerProfile.minLayerHeight,
                ),
            value = options.printerProfile.minLayerHeight,
            minimum = 0.01f,
            defaultMaximum = options.printerProfile.maxLayerHeight,
            increment = 0.01f,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(minLayerHeight = it),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_layer_height),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    options.printerProfile.maxLayerHeight,
                ),
            value = options.printerProfile.maxLayerHeight,
            minimum = options.printerProfile.minLayerHeight,
            defaultMaximum = max(options.nozzleDiameter, options.printerProfile.maxLayerHeight),
            increment = 0.01f,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(maxLayerHeight = it),
                    ),
                )
            },
        )
        ExtruderCountSetting(options, onOptionsChanged)
        SettingsSwitch(
            label = stringResource(R.string.single_extruder_multi_material),
            checked = options.printerProfile.singleExtruderMultiMaterial,
            onCheckedChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(singleExtruderMultiMaterial = it),
                    ),
                )
            },
        )
        if (options.printerProfile.singleExtruderMultiMaterial || settingsQuery.isNotBlank()) {
            SettingsGroupTitle(stringResource(R.string.filament_changes))
            QuantizedSettingSlider(
                label = stringResource(R.string.cooling_tube_position),
                valueText =
                    stringResource(
                        R.string.millimeters_value_precise,
                        options.printerProfile.coolingTubeRetraction,
                    ),
                value = options.printerProfile.coolingTubeRetraction,
                minimum = 0f,
                defaultMaximum = max(200f, options.printerProfile.coolingTubeRetraction),
                increment = 0.5f,
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(coolingTubeRetraction = it),
                        ),
                    )
                },
            )
            QuantizedSettingSlider(
                label = stringResource(R.string.cooling_tube_length),
                valueText =
                    stringResource(
                        R.string.millimeters_value_precise,
                        options.printerProfile.coolingTubeLength,
                    ),
                value = options.printerProfile.coolingTubeLength,
                minimum = 0f,
                defaultMaximum = max(100f, options.printerProfile.coolingTubeLength),
                increment = 0.5f,
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(coolingTubeLength = it),
                        ),
                    )
                },
            )
            QuantizedSettingSlider(
                label = stringResource(R.string.filament_parking_position),
                valueText =
                    stringResource(
                        R.string.millimeters_value_precise,
                        options.printerProfile.parkingPosRetraction,
                    ),
                value = options.printerProfile.parkingPosRetraction,
                minimum = 0f,
                defaultMaximum = max(200f, options.printerProfile.parkingPosRetraction),
                increment = 0.5f,
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(parkingPosRetraction = it),
                        ),
                    )
                },
            )
            QuantizedSettingSlider(
                label = stringResource(R.string.extra_loading_distance),
                valueText =
                    stringResource(
                        R.string.millimeters_value_precise,
                        options.printerProfile.extraLoadingMove,
                    ),
                value = options.printerProfile.extraLoadingMove,
                minimum = min(-100f, options.printerProfile.extraLoadingMove),
                defaultMaximum = max(100f, options.printerProfile.extraLoadingMove),
                increment = 0.5f,
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(extraLoadingMove = it),
                        ),
                    )
                },
            )
            SettingsSwitch(
                label = stringResource(R.string.enable_filament_ramming),
                checked = options.printerProfile.enableFilamentRamming,
                onCheckedChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(enableFilamentRamming = it),
                        ),
                    )
                },
            )
            SettingsSwitch(
                label = stringResource(R.string.purge_in_prime_tower),
                checked = options.printerProfile.purgeInPrimeTower,
                onCheckedChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(purgeInPrimeTower = it),
                        ),
                    )
                },
            )
            SettingsSwitch(
                label = stringResource(R.string.high_current_on_filament_swap),
                checked = options.printerProfile.highCurrentOnFilamentSwap,
                onCheckedChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(highCurrentOnFilamentSwap = it),
                        ),
                    )
                },
            )
        }
        if (
            options.printerProfile.extruderCount > 1 ||
            options.printerProfile.singleExtruderMultiMaterial ||
            settingsQuery.isNotBlank()
        ) {
            SettingsGroupTitle(stringResource(R.string.wipe_tower_ramming))
            DecimalSettingField(
                label = stringResource(R.string.ramming_line_width_ratio),
                value = options.printerProfile.rammingLineWidthRatio,
                minimum = 0.1f,
                maximum = 20f,
                suffix = "×",
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(rammingLineWidthRatio = it),
                        ),
                    )
                },
            )
            SettingsSwitch(
                label = stringResource(R.string.change_pressure_when_wiping),
                checked = options.printerProfile.changePressureWhenWiping,
                onCheckedChange = {
                    onOptionsChanged(
                        options.copy(
                            printerProfile = options.printerProfile.copy(changePressureWhenWiping = it),
                        ),
                    )
                },
            )
            if (options.printerProfile.changePressureWhenWiping || settingsQuery.isNotBlank()) {
                DecimalSettingField(
                    label = stringResource(R.string.ramming_pressure_advance),
                    value = options.printerProfile.rammingPressureAdvance,
                    maximum = 2f,
                    suffix = "",
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                printerProfile = options.printerProfile.copy(rammingPressureAdvance = it),
                            ),
                        )
                    },
                )
            }
        }
        SettingsSwitch(
            label = stringResource(R.string.auxiliary_part_cooling_fan),
            checked = options.printerProfile.auxiliaryFan,
            onCheckedChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(auxiliaryFan = it),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.cooling))
        DecimalSettingField(
            label = stringResource(R.string.fan_speedup_time),
            value = options.printerProfile.fanSpeedupTime,
            maximum = 60f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(fanSpeedupTime = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.fan_speedup_overhangs),
            checked = options.printerProfile.fanSpeedupOverhangs,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(fanSpeedupOverhangs = it),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.fan_kickstart),
            value = options.printerProfile.fanKickstart,
            maximum = 60f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(fanKickstart = it),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.printer_environment_capabilities))
        SettingsSwitch(
            label = stringResource(R.string.multiple_build_plate_types),
            checked = options.printerProfile.supportMultiBedTypes,
            onCheckedChange = {
                val printer = options.printerProfile.copy(supportMultiBedTypes = it)
                onOptionsChanged(options.selectPrinter(printer))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.supports_chamber_temperature_control),
            checked = options.printerProfile.supportsChamberTemperatureControl,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                supportsChamberTemperatureControl = it,
                            ),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.supports_air_filtration),
            checked = options.printerProfile.supportsAirFiltration,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(supportsAirFiltration = it),
                    ),
                )
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.printer_firmware),
            entries = listOf("marlin", "marlin2", "klipper", "reprapfirmware"),
            selected = options.gcodeFlavor,
            optionLabel = {
                when (it) {
                    "marlin2" -> "Marlin 2"
                    "klipper" -> "Klipper"
                    "reprapfirmware" -> "RepRapFirmware"
                    else -> "Marlin"
                }
            },
            onSelected = { onOptionsChanged(options.copy(gcodeFlavor = it)) },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.printer_motion_system),
            entries = PRINTER_STRUCTURES,
            selected = options.printerStructure,
            optionLabel = {
                when (it) {
                    "corexy" -> "CoreXY"
                    "i3" -> stringResource(R.string.printer_motion_system_bed_slinger)
                    "hbot" -> "H-bot"
                    "delta" -> "Delta"
                    else -> stringResource(R.string.printer_motion_system_unspecified)
                }
            },
            onSelected = { onOptionsChanged(options.copy(printerStructure = it)) },
        )
        CoordinatePairSettingField(
            label = stringResource(R.string.preferred_arrangement_position),
            valueX = options.printerProfile.bestObjectPositionX * 100f,
            valueY = options.printerProfile.bestObjectPositionY * 100f,
            minimum = 0f,
            maximum = 100f,
            suffix = "%",
            onValueChange = { x, y ->
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                bestObjectPositionX = x / 100f,
                                bestObjectPositionY = y / 100f,
                            ),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.pellet_extruder),
            checked = options.printerProfile.pelletModded,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(pelletModded = it),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.adaptive_bed_mesh))
        CoordinatePairSettingField(
            label = stringResource(R.string.bed_mesh_min),
            valueX = options.printerProfile.bedMeshMinX,
            valueY = options.printerProfile.bedMeshMinY,
            minimum = -100_000f,
            maximum = 100_000f,
            suffix = stringResource(R.string.millimeters_suffix),
            isValid = { x, y ->
                x <= options.printerProfile.bedMeshMaxX &&
                    y <= options.printerProfile.bedMeshMaxY
            },
            onValueChange = { x, y ->
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                bedMeshMinX = x,
                                bedMeshMinY = y,
                            ),
                    ),
                )
            },
        )
        CoordinatePairSettingField(
            label = stringResource(R.string.bed_mesh_max),
            valueX = options.printerProfile.bedMeshMaxX,
            valueY = options.printerProfile.bedMeshMaxY,
            minimum = -100_000f,
            maximum = 100_000f,
            suffix = stringResource(R.string.millimeters_suffix),
            isValid = { x, y ->
                x >= options.printerProfile.bedMeshMinX &&
                    y >= options.printerProfile.bedMeshMinY
            },
            onValueChange = { x, y ->
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                bedMeshMaxX = x,
                                bedMeshMaxY = y,
                            ),
                    ),
                )
            },
        )
        CoordinatePairSettingField(
            label = stringResource(R.string.probe_point_distance),
            valueX = options.printerProfile.bedMeshProbeDistanceX,
            valueY = options.printerProfile.bedMeshProbeDistanceY,
            minimum = 0f,
            maximum = 100_000f,
            suffix = stringResource(R.string.millimeters_suffix),
            onValueChange = { x, y ->
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                bedMeshProbeDistanceX = x,
                                bedMeshProbeDistanceY = y,
                            ),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.mesh_margin),
            value = options.printerProfile.adaptiveBedMeshMargin,
            maximum = 100_000f,
            suffix = stringResource(R.string.millimeters_suffix),
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                adaptiveBedMeshMargin = it,
                            ),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.gcode_output))
        GcodeThumbnailSetting(
            value = options.printerProfile.gcodeThumbnails,
            onValueChange = { definitions ->
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                gcodeThumbnails = definitions,
                            ),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.scan_first_layer),
            checked = options.printerProfile.scanFirstLayer,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(scanFirstLayer = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.use_relative_e_distances),
            checked = options.printerProfile.useRelativeEDistances,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(useRelativeEDistances = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.emit_machine_limits_to_gcode),
            checked = options.printerProfile.emitMachineLimitsToGcode,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(emitMachineLimitsToGcode = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.manual_filament_change),
            checked = options.printerProfile.manualFilamentChange,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(manualFilamentChange = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.disable_m73),
            checked = options.printerProfile.disableM73,
            onCheckedChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(disableM73 = it),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.tool_change_timing))
        DecimalSettingField(
            label = stringResource(R.string.machine_filament_load_time),
            value = options.printerProfile.machineLoadFilamentTime,
            maximum = 3_600f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(machineLoadFilamentTime = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.machine_filament_unload_time),
            value = options.printerProfile.machineUnloadFilamentTime,
            maximum = 3_600f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(machineUnloadFilamentTime = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.machine_tool_change_time),
            value = options.printerProfile.machineToolChangeTime,
            maximum = 3_600f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(machineToolChangeTime = value),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.wait_for_tool_temperature),
            checked = options.printerProfile.toolChangeTemperatureWait,
            onCheckedChange = { enabled ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(toolChangeTemperatureWait = enabled),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.machine_gcode))
        GcodeTemplateSetting(
            label = stringResource(R.string.machine_start_gcode),
            value = options.printerProfile.machineStartGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(machineStartGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.machine_end_gcode),
            value = options.printerProfile.machineEndGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(machineEndGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.machine_pause_gcode),
            value = options.printerProfile.machinePauseGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(machinePauseGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.template_custom_gcode),
            value = options.printerProfile.templateCustomGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(templateCustomGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.time_lapse_gcode),
            value = options.printerProfile.timeLapseGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(timeLapseGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.before_layer_change_gcode),
            value = options.printerProfile.beforeLayerChangeGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(beforeLayerChangeGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.layer_change_gcode),
            value = options.printerProfile.layerChangeGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(layerChangeGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.change_filament_gcode),
            value = options.printerProfile.changeFilamentGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(changeFilamentGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.printing_by_object_gcode),
            value = options.printerProfile.printingByObjectGcode,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(printingByObjectGcode = it),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.extruder_offsets))
        if (options.printerProfile.extruderCount > 1) {
            var extruderMenuOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { extruderMenuOpen = true }) {
                    Text(stringResource(R.string.extruder_number, activeExtruder + 1))
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = extruderMenuOpen, onDismissRequest = { extruderMenuOpen = false }) {
                    repeat(options.printerProfile.extruderCount) { index ->
                        DropdownMenuItem(
                            onClick = {
                                selectedExtruder = index
                                extruderMenuOpen = false
                            },
                            text = { Text(stringResource(R.string.extruder_number, index + 1)) },
                        )
                    }
                }
            }
        }
        QuantizedSettingSlider(
            label = stringResource(R.string.extruder_offset_x),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    extruderOffsetsX[activeExtruder],
                ),
            value = extruderOffsetsX[activeExtruder],
            minimum = min(-100f, extruderOffsetsX[activeExtruder]),
            defaultMaximum = max(100f, extruderOffsetsX[activeExtruder]),
            increment = 0.1f,
            onValueChange = {
                val updated =
                    extruderOffsetsX.toMutableList().apply {
                        this[activeExtruder] = it
                    }
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                extruderOffsetsX = updated,
                                extruderOffsetsY = extruderOffsetsY,
                            ),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.extruder_offset_y),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    extruderOffsetsY[activeExtruder],
                ),
            value = extruderOffsetsY[activeExtruder],
            minimum = min(-100f, extruderOffsetsY[activeExtruder]),
            defaultMaximum = max(100f, extruderOffsetsY[activeExtruder]),
            increment = 0.1f,
            onValueChange = {
                val updated =
                    extruderOffsetsY.toMutableList().apply {
                        this[activeExtruder] = it
                    }
                onOptionsChanged(
                    options.copy(
                        printerProfile =
                            options.printerProfile.copy(
                                extruderOffsetsX = extruderOffsetsX,
                                extruderOffsetsY = updated,
                            ),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.tool_change_retraction))
        QuantizedSettingSlider(
            label = stringResource(R.string.tool_change_retraction_length),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    toolChangeRetractLengths[activeExtruder],
                ),
            value = toolChangeRetractLengths[activeExtruder],
            minimum = 0f,
            defaultMaximum = max(20f, toolChangeRetractLengths[activeExtruder]),
            increment = 0.1f,
            onValueChange = {
                val updated =
                    toolChangeRetractLengths.toMutableList().apply {
                        this[activeExtruder] = it
                    }
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(toolChangeRetractLengths = updated),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.tool_change_retract_restart_extra),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    toolChangeRetractRestartExtras[activeExtruder],
                ),
            value = toolChangeRetractRestartExtras[activeExtruder],
            minimum = min(-20f, toolChangeRetractRestartExtras[activeExtruder]),
            defaultMaximum = max(20f, toolChangeRetractRestartExtras[activeExtruder]),
            increment = 0.1f,
            onValueChange = {
                val updated =
                    toolChangeRetractRestartExtras.toMutableList().apply {
                        this[activeExtruder] = it
                    }
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(toolChangeRetractRestartExtras = updated),
                    ),
                )
            },
        )
        SettingsGroupTitle(stringResource(R.string.retraction_defaults))
        SettingSlider(
            label = stringResource(R.string.retraction_length),
            valueText = stringResource(R.string.millimeters_value_precise, options.printerProfile.retractLength),
            value = options.printerProfile.retractLength,
            range = 0f..8f,
            steps = 79,
            onValueChange = {
                onOptionsChanged(options.updatePrinterRetraction(options.printerProfile.copy(retractLength = it)))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retraction_speed),
            valueText = stringResource(R.string.print_speed_value, options.printerProfile.retractSpeed),
            value = options.printerProfile.retractSpeed,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.deretraction_speed),
            valueText = stringResource(R.string.print_speed_value, options.printerProfile.deretractSpeed),
            value = options.printerProfile.deretractSpeed,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(deretractSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.retraction_minimum_travel),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    options.printerProfile.retractionMinimumTravel,
                ),
            value = options.printerProfile.retractionMinimumTravel,
            range = 0f..20f,
            steps = 199,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractionMinimumTravel = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.retract_when_changing_layer),
            checked = options.printerProfile.retractWhenChangingLayer,
            onCheckedChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractWhenChangingLayer = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.wipe_while_retracting),
            checked = options.printerProfile.wipeWhileRetracting,
            onCheckedChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(wipeWhileRetracting = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.wipe_distance),
            valueText = stringResource(R.string.millimeters_value_precise, options.printerProfile.wipeDistance),
            value = options.printerProfile.wipeDistance,
            range = 0f..10f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(options.updatePrinterRetraction(options.printerProfile.copy(wipeDistance = it)))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retract_before_wipe),
            valueText = stringResource(R.string.percent_value, options.printerProfile.retractBeforeWipe.roundToInt()),
            value = options.printerProfile.retractBeforeWipe,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractBeforeWipe = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.retract_restart_extra),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    options.printerProfile.retractRestartExtra,
                ),
            value = options.printerProfile.retractRestartExtra,
            range = -2f..2f,
            steps = 79,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractRestartExtra = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.z_hop_height),
            valueText = stringResource(R.string.millimeters_value_precise, options.printerProfile.zHop),
            value = options.printerProfile.zHop,
            range = 0f..5f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(options.updatePrinterRetraction(options.printerProfile.copy(zHop = it)))
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.z_hop_type),
            entries = listOf("auto", "normal", "slope", "spiral"),
            selected = options.printerProfile.zHopType,
            optionLabel = {
                stringResource(
                    when (it) {
                        "auto" -> R.string.z_hop_auto
                        "normal" -> R.string.z_hop_normal
                        "spiral" -> R.string.z_hop_spiral
                        else -> R.string.z_hop_slope
                    },
                )
            },
            onSelected = {
                onOptionsChanged(options.updatePrinterRetraction(options.printerProfile.copy(zHopType = it)))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.z_hop_start_height),
            value = options.printerProfile.retractLiftAbove,
            maximum = options.printerProfile.maxPrintHeight,
            suffix = "mm",
            onValueChange = { value ->
                val bounded =
                    if (options.printerProfile.retractLiftBelow > 0f) {
                        min(value, options.printerProfile.retractLiftBelow)
                    } else {
                        value
                    }
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractLiftAbove = bounded),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.z_hop_end_height),
            value = options.printerProfile.retractLiftBelow,
            maximum = options.printerProfile.maxPrintHeight,
            suffix = "mm",
            onValueChange = { value ->
                val bounded = if (value == 0f) 0f else max(value, options.printerProfile.retractLiftAbove)
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractLiftBelow = bounded),
                    ),
                )
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.z_hop_surfaces),
            entries = RETRACT_LIFT_ENFORCEMENTS.toList(),
            selected = options.printerProfile.retractLiftEnforce,
            optionLabel = {
                stringResource(
                    when (it) {
                        "top" -> R.string.z_hop_surface_top
                        "bottom" -> R.string.z_hop_surface_bottom
                        "top_bottom" -> R.string.z_hop_surface_top_bottom
                        else -> R.string.z_hop_surface_all
                    },
                )
            },
            onSelected = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(retractLiftEnforce = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.z_hop_slope_angle),
            valueText = stringResource(R.string.degrees_value, options.printerProfile.travelSlope),
            value = options.printerProfile.travelSlope,
            range = 1f..90f,
            steps = 88,
            onValueChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(travelSlope = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.z_hop_on_prime_tower),
            checked = options.printerProfile.zHopWhenPrime,
            onCheckedChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(zHopWhenPrime = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.firmware_retraction),
            checked = options.printerProfile.useFirmwareRetraction,
            onCheckedChange = {
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(
                            useFirmwareRetraction = it,
                            wipeWhileRetracting = if (it) false else options.printerProfile.wipeWhileRetracting,
                        ),
                    ),
                )
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.filament_cut_retraction_control),
            entries = listOf(0, 1, 2),
            selected = options.printerProfile.longRetractionWhenCutLevel,
            optionLabel = { level ->
                stringResource(
                    when (level) {
                        1 -> R.string.filament_cut_retraction_printer
                        2 -> R.string.filament_cut_retraction_filament
                        else -> R.string.filament_cut_retraction_disabled
                    },
                )
            },
            onSelected = { level ->
                onOptionsChanged(
                    options.updatePrinterRetraction(
                        options.printerProfile.copy(
                            longRetractionWhenCutLevel = level,
                            longRetractionWhenCut =
                                if (level == 0) {
                                    false
                                } else {
                                    options.printerProfile.longRetractionWhenCut
                                },
                        ),
                    ),
                )
            },
        )
        if ((
                options.printerProfile.longRetractionWhenCutLevel > 0 &&
                    !options.printerProfile.useFirmwareRetraction
            ) || settingsQuery.isNotBlank()
        ) {
            SettingsSwitch(
                label = stringResource(R.string.long_retraction_when_cut),
                checked = options.printerProfile.longRetractionWhenCut,
                onCheckedChange = { enabled ->
                    onOptionsChanged(
                        options.updatePrinterRetraction(
                            options.printerProfile.copy(longRetractionWhenCut = enabled),
                        ),
                    )
                },
            )
            if (options.printerProfile.longRetractionWhenCut || settingsQuery.isNotBlank()) {
                SettingSlider(
                    label = stringResource(R.string.retraction_distance_when_cut),
                    valueText =
                        stringResource(
                            R.string.millimeters_value_precise,
                            options.printerProfile.retractionDistanceWhenCut,
                        ),
                    value = options.printerProfile.retractionDistanceWhenCut,
                    range = 10f..18f,
                    steps = 79,
                    onValueChange = { value ->
                        onOptionsChanged(
                            options.updatePrinterRetraction(
                                options.printerProfile.copy(retractionDistanceWhenCut = value),
                            ),
                        )
                    },
                )
            }
        }
        SettingsGroupTitle(stringResource(R.string.sequential_printing_clearance))
        SettingSlider(
            label = stringResource(R.string.extruder_clearance_radius),
            valueText = stringResource(R.string.millimeters_value_precise, options.extruderClearanceRadius),
            value = options.extruderClearanceRadius,
            range = 0.5f..max(200f, options.extruderClearanceRadius),
            steps =
                (((max(200f, options.extruderClearanceRadius) - 0.5f) * 2f).roundToInt() - 1)
                    .coerceAtLeast(0),
            onValueChange = {
                onOptionsChanged(options.copy(extruderClearanceRadius = (it * 2f).roundToInt() / 2f))
            },
        )
        SettingSlider(
            label = stringResource(R.string.extruder_clearance_height_to_rod),
            valueText = stringResource(R.string.millimeters_value_precise, options.extruderClearanceHeightToRod),
            value = options.extruderClearanceHeightToRod,
            range = 0.5f..max(500f, options.extruderClearanceHeightToRod),
            steps =
                (((max(500f, options.extruderClearanceHeightToRod) - 0.5f) * 2f).roundToInt() - 1)
                    .coerceAtLeast(0),
            onValueChange = {
                onOptionsChanged(options.copy(extruderClearanceHeightToRod = (it * 2f).roundToInt() / 2f))
            },
        )
        SettingSlider(
            label = stringResource(R.string.extruder_clearance_height_to_lid),
            valueText = stringResource(R.string.millimeters_value_precise, options.extruderClearanceHeightToLid),
            value = options.extruderClearanceHeightToLid,
            range = 0.5f..max(500f, options.extruderClearanceHeightToLid),
            steps =
                (((max(500f, options.extruderClearanceHeightToLid) - 0.5f) * 2f).roundToInt() - 1)
                    .coerceAtLeast(0),
            onValueChange = {
                onOptionsChanged(options.copy(extruderClearanceHeightToLid = (it * 2f).roundToInt() / 2f))
            },
        )
        SettingsGroupTitle(stringResource(R.string.motion_limits))
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_x_speed),
            valueText = stringResource(R.string.print_speed_value, options.maxSpeedX),
            value = options.maxSpeedX,
            minimum = 1f,
            defaultMaximum = 700f,
            increment = 1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxSpeedX = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_y_speed),
            valueText = stringResource(R.string.print_speed_value, options.maxSpeedY),
            value = options.maxSpeedY,
            minimum = 1f,
            defaultMaximum = 700f,
            increment = 1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxSpeedY = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_z_speed),
            valueText = stringResource(R.string.print_speed_value, options.maxSpeedZ),
            value = options.maxSpeedZ,
            minimum = 0.1f,
            defaultMaximum = 100f,
            increment = 0.1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxSpeedZ = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_e_speed),
            valueText = stringResource(R.string.print_speed_value, options.maxSpeedE),
            value = options.maxSpeedE,
            minimum = 1f,
            defaultMaximum = 500f,
            increment = 1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxSpeedE = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_x_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationX),
            value = options.maxAccelerationX,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 100f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxAccelerationX = it)))
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_y_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationY),
            value = options.maxAccelerationY,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 100f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxAccelerationY = it)))
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_z_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationZ),
            value = options.maxAccelerationZ,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 50f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxAccelerationZ = it)))
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_e_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationE),
            value = options.maxAccelerationE,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 50f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxAccelerationE = it)))
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_print_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationExtruding),
            value = options.maxAccelerationExtruding,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 100f,
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        machineMotion =
                            options.machineMotion.copy(
                                maxAccelerationExtruding = value,
                                maxAccelerationX = max(options.maxAccelerationX, value),
                                maxAccelerationY = max(options.maxAccelerationY, value),
                            ),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_retracting_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationRetracting),
            value = options.maxAccelerationRetracting,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 100f,
            onValueChange = {
                onOptionsChanged(
                    options.copy(machineMotion = options.machineMotion.copy(maxAccelerationRetracting = it)),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_travel_acceleration),
            valueText = stringResource(R.string.acceleration_value, options.maxAccelerationTravel),
            value = options.maxAccelerationTravel,
            minimum = 1f,
            defaultMaximum = 50_000f,
            increment = 100f,
            onValueChange = { value ->
                onOptionsChanged(
                    options.copy(
                        machineMotion =
                            options.machineMotion.copy(
                                maxAccelerationTravel = value,
                                maxAccelerationX = max(options.maxAccelerationX, value),
                                maxAccelerationY = max(options.maxAccelerationY, value),
                            ),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_x_jerk),
            valueText = stringResource(R.string.jerk_value, options.maxJerkX),
            value = options.maxJerkX,
            minimum = 0f,
            defaultMaximum = 100f,
            increment = 0.1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxJerkX = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_y_jerk),
            valueText = stringResource(R.string.jerk_value, options.maxJerkY),
            value = options.maxJerkY,
            minimum = 0f,
            defaultMaximum = 100f,
            increment = 0.1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxJerkY = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_z_jerk),
            valueText = stringResource(R.string.jerk_value, options.maxJerkZ),
            value = options.maxJerkZ,
            minimum = 0f,
            defaultMaximum = 20f,
            increment = 0.1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxJerkZ = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_e_jerk),
            valueText = stringResource(R.string.jerk_value, options.maxJerkE),
            value = options.maxJerkE,
            minimum = 0f,
            defaultMaximum = 100f,
            increment = 0.1f,
            onValueChange = { onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxJerkE = it))) },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.minimum_extruding_rate),
            valueText = stringResource(R.string.print_speed_value, options.minimumExtrudingRate),
            value = options.minimumExtrudingRate,
            minimum = 0f,
            defaultMaximum = 100f,
            increment = 0.1f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(minimumExtrudingRate = it)))
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.minimum_travel_rate),
            valueText = stringResource(R.string.print_speed_value, options.minimumTravelRate),
            value = options.minimumTravelRate,
            minimum = 0f,
            defaultMaximum = 100f,
            increment = 0.1f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(minimumTravelRate = it)))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.silent_mode),
            checked = options.machineMotion.silentMode,
            onCheckedChange = { enabled ->
                onOptionsChanged(
                    options.copy(machineMotion = options.machineMotion.copy(silentMode = enabled)),
                )
            },
        )
        if (options.machineMotion.silentMode || settingsQuery.isNotBlank()) {
            SettingsGroupTitle(stringResource(R.string.silent_motion_limits))
            SilentMotionLimitSettings(
                limits = options.machineMotion.silentMotionLimits,
                onLimitsChanged = { limits ->
                    onOptionsChanged(
                        options.copy(
                            machineMotion = options.machineMotion.copy(silentMotionLimits = limits),
                        ),
                    )
                },
            )
            QuantizedSettingSlider(
                label = stringResource(R.string.minimum_extruding_rate),
                valueText = stringResource(R.string.print_speed_value, options.silentMinimumExtrudingRate),
                value = options.silentMinimumExtrudingRate,
                minimum = 0f,
                defaultMaximum = 100f,
                increment = 0.1f,
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            machineMotion = options.machineMotion.copy(silentMinimumExtrudingRate = it),
                        ),
                    )
                },
            )
            QuantizedSettingSlider(
                label = stringResource(R.string.minimum_travel_rate),
                valueText = stringResource(R.string.print_speed_value, options.silentMinimumTravelRate),
                value = options.silentMinimumTravelRate,
                minimum = 0f,
                defaultMaximum = 100f,
                increment = 0.1f,
                onValueChange = {
                    onOptionsChanged(
                        options.copy(
                            machineMotion = options.machineMotion.copy(silentMinimumTravelRate = it),
                        ),
                    )
                },
            )
        }
        QuantizedSettingSlider(
            label = stringResource(R.string.maximum_junction_deviation),
            valueText =
                stringResource(
                    R.string.junction_deviation_value,
                    options.maxJunctionDeviation,
                ),
            value = options.maxJunctionDeviation,
            minimum = 0f,
            defaultMaximum = 1f,
            increment = 0.001f,
            onValueChange = {
                onOptionsChanged(options.copy(machineMotion = options.machineMotion.copy(maxJunctionDeviation = it)))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.resonance_avoidance),
            checked = options.printerProfile.resonanceAvoidance,
            onCheckedChange = { enabled ->
                onOptionsChanged(
                    options.copy(
                        printerProfile = options.printerProfile.copy(resonanceAvoidance = enabled),
                    ),
                )
            },
        )
        if (options.printerProfile.resonanceAvoidance || settingsQuery.isNotBlank()) {
            QuantizedSettingSlider(
                label = stringResource(R.string.minimum_resonance_avoidance_speed),
                valueText =
                    stringResource(
                        R.string.print_speed_value,
                        options.printerProfile.minResonanceAvoidanceSpeed,
                    ),
                value = options.printerProfile.minResonanceAvoidanceSpeed,
                minimum = 0f,
                defaultMaximum = max(500f, options.printerProfile.maxResonanceAvoidanceSpeed),
                increment = 1f,
                onValueChange = { value ->
                    onOptionsChanged(
                        options.copy(
                            printerProfile =
                                options.printerProfile.copy(
                                    minResonanceAvoidanceSpeed =
                                        min(
                                            value,
                                            options.printerProfile.maxResonanceAvoidanceSpeed,
                                        ),
                                ),
                        ),
                    )
                },
            )
            QuantizedSettingSlider(
                label = stringResource(R.string.maximum_resonance_avoidance_speed),
                valueText =
                    stringResource(
                        R.string.print_speed_value,
                        options.printerProfile.maxResonanceAvoidanceSpeed,
                    ),
                value = options.printerProfile.maxResonanceAvoidanceSpeed,
                minimum = options.printerProfile.minResonanceAvoidanceSpeed,
                defaultMaximum = max(500f, options.printerProfile.maxResonanceAvoidanceSpeed),
                increment = 1f,
                onValueChange = { value ->
                    onOptionsChanged(
                        options.copy(
                            printerProfile =
                                options.printerProfile.copy(
                                    maxResonanceAvoidanceSpeed = value,
                                ),
                        ),
                    )
                },
            )
        }
        SaveProfileField(onSave = { name -> onSave(name, options) }, onDismiss = onDismiss)
    }
    if (profilesOpen) {
        ProfileChooserSheet(
            entries = profiles,
            selected = options.printerProfile,
            recentIds = recentIds,
            id = { it.id },
            name = { it.name },
            label = { profileLabel(it) },
            brand = { it.brand },
            builtIn = { it.builtIn },
            searchTerms = {
                listOf(it.name, it.brand.orEmpty(), it.nozzleDiameter.toString(), "nozzle")
            },
            onSelected = {
                onProfileSelected(it)
                profilesOpen = false
            },
            onDelete = { onDelete(it.id) },
            onRename = onRename,
            onDismiss = { profilesOpen = false },
        )
    }
}

@Composable
internal fun SilentMotionLimitSettings(
    limits: MachineMotionLimits,
    onLimitsChanged: (MachineMotionLimits) -> Unit,
) {
    SILENT_MOTION_LIMIT_CONTROLS.forEach { control ->
        val value = control.value(limits)
        val valueText =
            when (control.unit) {
                MotionLimitUnit.SPEED -> stringResource(R.string.print_speed_value, value)
                MotionLimitUnit.ACCELERATION -> stringResource(R.string.acceleration_value, value)
                MotionLimitUnit.JERK -> stringResource(R.string.jerk_value, value)
            }
        QuantizedSettingSlider(
            label =
                stringResource(
                    R.string.silent_setting_label,
                    stringResource(control.labelResource),
                ),
            valueText = valueText,
            value = value,
            minimum = control.minimum,
            defaultMaximum = control.defaultMaximum,
            increment = control.increment,
            onValueChange = { onLimitsChanged(control.update(limits, it)) },
        )
    }
}

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
internal fun FilamentSettingsSheet(
    options: SliceOptions,
    profiles: List<FilamentProfile>,
    recentIds: List<String>,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String, SliceOptions, Int) -> Unit,
    onUpdate: (SliceOptions, Int) -> Unit,
    onRename: (FilamentProfile, String) -> Unit,
    onDelete: (String) -> Unit,
    dirty: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSlot by rememberSaveable { mutableStateOf(0) }
    var profilesOpen by rememberSaveable { mutableStateOf(false) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    val slots = options.resolvedFilamentSlots()
    LaunchedEffect(slots.size) {
        selectedSlot = selectedSlot.coerceIn(0, slots.lastIndex)
    }
    val activeProfile = slots.getOrElse(selectedSlot) { slots.last() }
    val activeBedTemperature = activeProfile.bedTemperature(options.buildPlate.type)
    val activeFirstLayerBedTemperature =
        activeProfile.firstLayerBedTemperature(options.buildPlate.type)
    val resolvedRetraction = activeProfile.resolveRetraction(options.printerProfile)
    val inheritsPrinterRetraction =
        activeProfile.retractLength == null &&
            activeProfile.retractSpeed == null && activeProfile.deretractSpeed == null &&
            activeProfile.retractionMinimumTravel == null && activeProfile.retractWhenChangingLayer == null &&
            activeProfile.wipeWhileRetracting == null && activeProfile.wipeDistance == null &&
            activeProfile.retractBeforeWipe == null && activeProfile.retractRestartExtra == null &&
            activeProfile.zHop == null && activeProfile.zHopType == null &&
            activeProfile.retractLiftAbove == null && activeProfile.retractLiftBelow == null &&
            activeProfile.retractLiftEnforce == null &&
            activeProfile.longRetractionWhenCut == null &&
            activeProfile.retractionDistanceWhenCut == null
    SettingsSheet(
        title = stringResource(R.string.filament_profile),
        onDismiss = onDismiss,
        scrollKey = selectedSlot,
        dirty = dirty,
        onRevert = onRevert,
        onApply = onApply,
        settingQuery = settingsQuery,
        onSettingQueryChanged = { settingsQuery = it },
        header = {
            CurrentProfileButton(
                profile = profileLabel(activeProfile),
                onClick = { profilesOpen = true },
            )
            UpdateProfileButton(
                visible = !activeProfile.builtIn,
                onUpdate = { onUpdate(options, selectedSlot) },
                onDismiss = onDismiss,
            )
        },
    ) {
        SecondaryScrollableTabRow(selectedTabIndex = selectedSlot.coerceAtMost(slots.lastIndex)) {
            slots.forEachIndexed { index, profile ->
                Tab(
                    selected = index == selectedSlot,
                    onClick = { selectedSlot = index },
                    text = { Text("T${index + 1} · ${profile.nativeName}") },
                )
            }
        }
        FilamentColorSetting(
            slot = selectedSlot,
            color = options.resolvedFilamentColors()[selectedSlot],
            profileDefaultColor =
                slots[selectedSlot].defaultColor.takeIf {
                    it in MIN_FILAMENT_RGB..MAX_FILAMENT_RGB
                },
            onSelected = { color ->
                onOptionsChanged(options.updateFilamentColor(selectedSlot, color))
            },
        )
        val notesLabel = stringResource(R.string.filament_notes)
        if (settingMatchesQuery(notesLabel)) {
            OutlinedTextField(
                value = activeProfile.notes,
                onValueChange = { candidate ->
                    if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_NOTES_BYTES) {
                        onOptionsChanged(
                            options.updateFilamentSlot(
                                selectedSlot,
                                activeProfile.copy(notes = candidate),
                            ),
                        )
                    }
                },
                label = { Text(notesLabel) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (slots.size < options.printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS)) {
            OutlinedButton(
                onClick = {
                    val nextProfile =
                        profiles.firstOrNull { candidate ->
                            candidate.hasCompatibleDiameter(options.filamentProfile) &&
                                slots.none { it.id == candidate.id }
                        } ?: options.filamentProfile
                    onOptionsChanged(options.addFilamentSlot(nextProfile))
                    selectedSlot = slots.size
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_filament_slot))
            }
        }
        if (slots.size > 1 && selectedSlot == slots.lastIndex) {
            OutlinedButton(
                onClick = {
                    onOptionsChanged(options.removeLastFilamentSlot())
                    selectedSlot = (selectedSlot - 1).coerceAtLeast(0)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.remove_filament_slot, selectedSlot + 1))
            }
        }
        SettingSlider(
            label = stringResource(R.string.nozzle_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.nozzleTemp),
            value = activeProfile.nozzleTemp.toFloat(),
            range =
                MIN_FILAMENT_NOZZLE_TEMPERATURE.toFloat()..MAX_FILAMENT_NOZZLE_TEMPERATURE.toFloat(),
            steps = MAX_FILAMENT_NOZZLE_TEMPERATURE - MIN_FILAMENT_NOZZLE_TEMPERATURE - 1,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(nozzleTemp = it.roundToInt())))
            },
        )
        SettingSlider(
            label = stringResource(R.string.first_layer_nozzle_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.firstLayerNozzleTemp),
            value = activeProfile.firstLayerNozzleTemp.toFloat(),
            range =
                MIN_FILAMENT_NOZZLE_TEMPERATURE.toFloat()..MAX_FILAMENT_NOZZLE_TEMPERATURE.toFloat(),
            steps = MAX_FILAMENT_NOZZLE_TEMPERATURE - MIN_FILAMENT_NOZZLE_TEMPERATURE - 1,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(firstLayerNozzleTemp = it.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.idle_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.idleTemperature),
            value = activeProfile.idleTemperature.toFloat(),
            range = 0f..500f,
            steps = 499,
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(idleTemperature = value.roundToInt()),
                    ),
                )
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.build_plate),
            entries = options.printerProfile.availableBuildPlateTypes(),
            selected = options.buildPlate.type,
            optionLabel = { stringResource(it.labelResource()) },
            onSelected = { onOptionsChanged(options.selectBuildPlate(it)) },
        )
        SettingSlider(
            label = stringResource(R.string.bed_temperature),
            valueText =
                if (activeBedTemperature == 0) {
                    stringResource(R.string.plate_not_supported)
                } else {
                    stringResource(R.string.celsius_value, activeBedTemperature)
                },
            value = activeBedTemperature.toFloat(),
            range = 0f..120f,
            steps = 119,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.withBedTemperature(
                            options.buildPlate.type,
                            temperature = it.roundToInt(),
                        ),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.first_layer_bed_temperature),
            valueText =
                if (activeFirstLayerBedTemperature == 0) {
                    stringResource(R.string.plate_not_supported)
                } else {
                    stringResource(R.string.celsius_value, activeFirstLayerBedTemperature)
                },
            value = activeFirstLayerBedTemperature.toFloat(),
            range = 0f..120f,
            steps = 119,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.withBedTemperature(
                            options.buildPlate.type,
                            firstLayerTemperature = it.roundToInt(),
                        ),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.flow_ratio),
            valueText = stringResource(R.string.flow_ratio_value, activeProfile.flowRatio),
            value = activeProfile.flowRatio,
            range = MIN_FILAMENT_FLOW_RATIO..MAX_FILAMENT_FLOW_RATIO,
            steps =
                (
                    (MAX_FILAMENT_FLOW_RATIO - MIN_FILAMENT_FLOW_RATIO) / 0.01f
                ).roundToInt() - 1,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(flowRatio = it)))
            },
        )
        SettingSlider(
            label = stringResource(R.string.max_volumetric_speed),
            valueText = stringResource(R.string.volumetric_speed_value, activeProfile.maxVolumetricSpeed),
            value = activeProfile.maxVolumetricSpeed,
            range = if (options.printerProfile.pelletModded) 4f..300f else 4f..100f,
            steps = if (options.printerProfile.pelletModded) 295 else 95,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(maxVolumetricSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        if (selectedSlot == 0 && options.printerProfile.pelletModded) {
            DecimalSettingField(
                label = stringResource(R.string.pellet_flow_coefficient),
                value = activeProfile.pelletFlowCoefficient,
                minimum = MIN_PELLET_FLOW_COEFFICIENT,
                maximum = MAX_PELLET_FLOW_COEFFICIENT,
                suffix = "",
                onValueChange = { coefficient ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(
                                pelletFlowCoefficient = coefficient,
                                diameter = filamentDiameterFromPelletFlowCoefficient(coefficient),
                            ),
                        ),
                    )
                },
            )
        } else if (selectedSlot == 0) {
            SettingSlider(
                label = stringResource(R.string.filament_diameter),
                valueText =
                    stringResource(
                        R.string.millimeters_value_precise,
                        activeProfile.diameter,
                    ),
                value = activeProfile.diameter,
                range = 0.5f..4f,
                steps = 349,
                onValueChange = {
                    val diameter = (it * 100f).roundToInt() / 100f
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(
                                diameter = diameter,
                                pelletFlowCoefficient = pelletFlowCoefficientFromDiameter(diameter),
                            ),
                        ),
                    )
                },
            )
        }
        QuantizedSettingSlider(
            label = stringResource(R.string.filament_density),
            valueText =
                stringResource(
                    R.string.grams_per_cubic_centimeter_value,
                    activeProfile.density,
                ),
            value = activeProfile.density,
            minimum = 0f,
            defaultMaximum = 3f,
            increment = 0.01f,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(density = it),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_price_per_kilogram),
            value = activeProfile.costPerKilogram,
            maximum = 1_000_000f,
            suffix = stringResource(R.string.per_kilogram_suffix),
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(costPerKilogram = it),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.filament_shrinkage_xy),
            valueText = stringResource(R.string.percent_value_precise, activeProfile.shrinkageXyPercent),
            value = activeProfile.shrinkageXyPercent,
            minimum = 10f,
            defaultMaximum = 200f,
            increment = 0.1f,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(shrinkageXyPercent = it),
                    ),
                )
            },
        )
        QuantizedSettingSlider(
            label = stringResource(R.string.filament_shrinkage_z),
            valueText = stringResource(R.string.percent_value_precise, activeProfile.shrinkageZPercent),
            value = activeProfile.shrinkageZPercent,
            minimum = 10f,
            defaultMaximum = 200f,
            increment = 0.1f,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(shrinkageZPercent = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.filament_soluble_material),
            checked = activeProfile.soluble,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(soluble = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.filament_support_material),
            checked = activeProfile.supportMaterial,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(supportMaterial = it),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_minimal_purge_on_wipe_tower),
            value = activeProfile.minimalPurgeOnWipeTower,
            maximum = MAX_PURGE_VOLUME,
            suffix = stringResource(R.string.cubic_millimeters_suffix),
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(minimalPurgeOnWipeTower = it),
                    ),
                )
            },
        )
        FilamentPrimeTowerInterfaceSettings(
            profile = activeProfile,
            onChanged = { updated ->
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, updated))
            },
        )
        SettingsGroupTitle(stringResource(R.string.filament_material_environment))
        SettingSlider(
            label = stringResource(R.string.required_nozzle_hardness),
            valueText =
                if (activeProfile.requiredNozzleHrc > 0) {
                    stringResource(R.string.hrc_value, activeProfile.requiredNozzleHrc)
                } else {
                    stringResource(R.string.nozzle_hardness_no_requirement)
                },
            value = activeProfile.requiredNozzleHrc.toFloat(),
            range = 0f..500f,
            steps = 499,
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(requiredNozzleHrc = value.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.filament_softening_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.softeningTemperature),
            value = activeProfile.softeningTemperature.toFloat(),
            range = 0f..500f,
            steps = 499,
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(softeningTemperature = value.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.filament_nozzle_temperature_minimum),
            valueText = stringResource(R.string.celsius_value, activeProfile.nozzleTemperatureRangeLow),
            value = activeProfile.nozzleTemperatureRangeLow.toFloat(),
            range = 0f..500f,
            steps = 499,
            onValueChange = { value ->
                val minimum = value.roundToInt()
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(
                            nozzleTemperatureRangeLow = minimum,
                            nozzleTemperatureRangeHigh =
                                max(
                                    minimum,
                                    activeProfile.nozzleTemperatureRangeHigh,
                                ),
                        ),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.filament_nozzle_temperature_maximum),
            valueText = stringResource(R.string.celsius_value, activeProfile.nozzleTemperatureRangeHigh),
            value = activeProfile.nozzleTemperatureRangeHigh.toFloat(),
            range = 0f..500f,
            steps = 499,
            onValueChange = { value ->
                val maximum = value.roundToInt()
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(
                            nozzleTemperatureRangeLow =
                                min(
                                    maximum,
                                    activeProfile.nozzleTemperatureRangeLow,
                                ),
                            nozzleTemperatureRangeHigh = maximum,
                        ),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.filament_chamber_temperature_control),
            checked = activeProfile.chamberTemperatureControl,
            enabled = options.printerProfile.supportsChamberTemperatureControl,
            onCheckedChange = { enabled ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(chamberTemperatureControl = enabled),
                    ),
                )
            },
        )
        if (activeProfile.chamberTemperatureControl || settingsQuery.isNotBlank()) {
            SettingSlider(
                label = stringResource(R.string.filament_chamber_temperature),
                valueText = stringResource(R.string.celsius_value, activeProfile.chamberTemperature),
                value = activeProfile.chamberTemperature.toFloat(),
                range = 0f..200f,
                steps = 199,
                onValueChange = { value ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(chamberTemperature = value.roundToInt()),
                        ),
                    )
                },
            )
        }
        SettingsSwitch(
            label = stringResource(R.string.filament_air_filtration),
            checked = activeProfile.airFiltration,
            enabled = options.printerProfile.supportsAirFiltration,
            onCheckedChange = { enabled ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(airFiltration = enabled),
                    ),
                )
            },
        )
        if (activeProfile.airFiltration || settingsQuery.isNotBlank()) {
            SettingSlider(
                label = stringResource(R.string.filament_exhaust_during_print),
                valueText = stringResource(R.string.percent_value, activeProfile.duringPrintExhaustFanSpeed),
                value = activeProfile.duringPrintExhaustFanSpeed.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = { value ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(duringPrintExhaustFanSpeed = value.roundToInt()),
                        ),
                    )
                },
            )
            SettingSlider(
                label = stringResource(R.string.filament_exhaust_after_print),
                valueText = stringResource(R.string.percent_value, activeProfile.completePrintExhaustFanSpeed),
                value = activeProfile.completePrintExhaustFanSpeed.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = { value ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(completePrintExhaustFanSpeed = value.roundToInt()),
                        ),
                    )
                },
            )
        }
        SettingsGroupTitle(stringResource(R.string.filament_exchange_motion))
        DecimalSettingField(
            label = stringResource(R.string.filament_loading_speed),
            value = activeProfile.loadingSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(loadingSpeed = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_loading_speed_start),
            value = activeProfile.loadingSpeedStart,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(loadingSpeedStart = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_unloading_speed),
            value = activeProfile.unloadingSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(unloadingSpeed = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_unloading_speed_start),
            value = activeProfile.unloadingSpeedStart,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(unloadingSpeedStart = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_toolchange_delay),
            value = activeProfile.toolchangeDelay,
            maximum = 1_000f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(toolchangeDelay = value),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.filament_cooling_moves),
            valueText = activeProfile.coolingMoves.toString(),
            value = activeProfile.coolingMoves.toFloat(),
            range = 0f..20f,
            steps = 19,
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(coolingMoves = value.roundToInt()),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_cooling_initial_speed),
            value = activeProfile.coolingInitialSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(coolingInitialSpeed = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_cooling_final_speed),
            value = activeProfile.coolingFinalSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(coolingFinalSpeed = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_stamping_speed),
            value = activeProfile.stampingLoadingSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(stampingLoadingSpeed = value),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_stamping_distance),
            value = activeProfile.stampingDistance,
            maximum = 1_000f,
            suffix = "mm",
            onValueChange = { value ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(stampingDistance = value),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.filament_multitool_ramming),
            checked = activeProfile.multitoolRamming,
            onCheckedChange = { enabled ->
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(multitoolRamming = enabled),
                    ),
                )
            },
        )
        if (activeProfile.multitoolRamming || settingsQuery.isNotBlank()) {
            DecimalSettingField(
                label = stringResource(R.string.filament_multitool_ramming_volume),
                value = activeProfile.multitoolRammingVolume,
                maximum = 1_000f,
                suffix = stringResource(R.string.cubic_millimeters_suffix),
                onValueChange = { value ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(multitoolRammingVolume = value),
                        ),
                    )
                },
            )
            DecimalSettingField(
                label = stringResource(R.string.filament_multitool_ramming_flow),
                value = activeProfile.multitoolRammingFlow,
                maximum = 1_000f,
                suffix = "mm³/s",
                onValueChange = { value ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(multitoolRammingFlow = value),
                        ),
                    )
                },
            )
        }
        SettingsGroupTitle(stringResource(R.string.retraction))
        SettingsSwitch(
            label = stringResource(R.string.use_printer_retraction_defaults),
            checked = inheritsPrinterRetraction,
            onCheckedChange = { inherit ->
                val updated =
                    if (inherit) {
                        activeProfile.copy(
                            retractLength = null,
                            retractSpeed = null,
                            deretractSpeed = null,
                            retractionMinimumTravel = null,
                            retractWhenChangingLayer = null,
                            wipeWhileRetracting = null,
                            wipeDistance = null,
                            retractBeforeWipe = null,
                            retractRestartExtra = null,
                            zHop = null,
                            zHopType = null,
                            retractLiftAbove = null,
                            retractLiftBelow = null,
                            retractLiftEnforce = null,
                            longRetractionWhenCut = null,
                            retractionDistanceWhenCut = null,
                        )
                    } else {
                        activeProfile.copy(
                            retractLength = resolvedRetraction.length,
                            retractSpeed = resolvedRetraction.speed,
                            deretractSpeed = resolvedRetraction.deretractSpeed,
                            retractionMinimumTravel = resolvedRetraction.minimumTravel,
                            retractWhenChangingLayer = resolvedRetraction.whenChangingLayer,
                            wipeWhileRetracting = resolvedRetraction.wipe,
                            wipeDistance = resolvedRetraction.wipeDistance,
                            retractBeforeWipe = resolvedRetraction.beforeWipe,
                            retractRestartExtra = resolvedRetraction.restartExtra,
                            zHop = resolvedRetraction.zHop,
                            zHopType = resolvedRetraction.zHopType,
                            retractLiftAbove = resolvedRetraction.liftAbove,
                            retractLiftBelow = resolvedRetraction.liftBelow,
                            retractLiftEnforce = resolvedRetraction.liftEnforce,
                            longRetractionWhenCut = resolvedRetraction.longRetractionWhenCut,
                            retractionDistanceWhenCut = resolvedRetraction.retractionDistanceWhenCut,
                        )
                    }
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, updated))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retraction_length),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.length),
            value = resolvedRetraction.length,
            range = 0f..8f,
            steps = 79,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(retractLength = it)))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retraction_speed),
            valueText = stringResource(R.string.print_speed_value, resolvedRetraction.speed),
            value = resolvedRetraction.speed,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.deretraction_speed),
            valueText = stringResource(R.string.print_speed_value, resolvedRetraction.deretractSpeed),
            value = resolvedRetraction.deretractSpeed,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(deretractSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.retraction_minimum_travel),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.minimumTravel),
            value = resolvedRetraction.minimumTravel,
            range = 0f..20f,
            steps = 199,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractionMinimumTravel = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.retract_when_changing_layer),
            checked = resolvedRetraction.whenChangingLayer,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractWhenChangingLayer = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.wipe_while_retracting),
            checked = resolvedRetraction.wipe,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(wipeWhileRetracting = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.wipe_distance),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.wipeDistance),
            value = resolvedRetraction.wipeDistance,
            range = 0f..10f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(wipeDistance = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.retract_before_wipe),
            valueText = stringResource(R.string.percent_value, resolvedRetraction.beforeWipe.roundToInt()),
            value = resolvedRetraction.beforeWipe,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractBeforeWipe = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.retract_restart_extra),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.restartExtra),
            value = resolvedRetraction.restartExtra,
            range = -2f..2f,
            steps = 79,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractRestartExtra = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.z_hop_height),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.zHop),
            value = resolvedRetraction.zHop,
            range = 0f..5f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(zHop = it)))
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.z_hop_type),
            entries = listOf("auto", "normal", "slope", "spiral"),
            selected = resolvedRetraction.zHopType,
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
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(zHopType = it),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.z_hop_start_height),
            value = resolvedRetraction.liftAbove,
            maximum = options.printerProfile.maxPrintHeight,
            suffix = "mm",
            onValueChange = { value ->
                val bounded =
                    if (resolvedRetraction.liftBelow > 0f) {
                        min(value, resolvedRetraction.liftBelow)
                    } else {
                        value
                    }
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractLiftAbove = bounded),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.z_hop_end_height),
            value = resolvedRetraction.liftBelow,
            maximum = options.printerProfile.maxPrintHeight,
            suffix = "mm",
            onValueChange = { value ->
                val bounded = if (value == 0f) 0f else max(value, resolvedRetraction.liftAbove)
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractLiftBelow = bounded),
                    ),
                )
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.z_hop_surfaces),
            entries = RETRACT_LIFT_ENFORCEMENTS.toList(),
            selected = resolvedRetraction.liftEnforce,
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
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(retractLiftEnforce = it),
                    ),
                )
            },
        )
        if ((
                options.printerProfile.longRetractionWhenCutLevel == 2 &&
                    !options.printerProfile.useFirmwareRetraction
            ) || settingsQuery.isNotBlank()
        ) {
            SettingsSwitch(
                label = stringResource(R.string.long_retraction_when_cut),
                checked = resolvedRetraction.longRetractionWhenCut,
                onCheckedChange = { enabled ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(longRetractionWhenCut = enabled),
                        ),
                    )
                },
            )
            if (resolvedRetraction.longRetractionWhenCut || settingsQuery.isNotBlank()) {
                SettingSlider(
                    label = stringResource(R.string.retraction_distance_when_cut),
                    valueText =
                        stringResource(
                            R.string.millimeters_value_precise,
                            resolvedRetraction.retractionDistanceWhenCut,
                        ),
                    value = resolvedRetraction.retractionDistanceWhenCut,
                    range = 10f..18f,
                    steps = 79,
                    onValueChange = { value ->
                        onOptionsChanged(
                            options.updateFilamentSlot(
                                selectedSlot,
                                activeProfile.copy(retractionDistanceWhenCut = value),
                            ),
                        )
                    },
                )
            }
        }
        SettingsGroupTitle(stringResource(R.string.cooling))
        SettingSlider(
            label = stringResource(R.string.auxiliary_part_cooling_fan),
            valueText =
                stringResource(
                    R.string.percent_value,
                    activeProfile.additionalCoolingFanSpeed,
                ),
            value = activeProfile.additionalCoolingFanSpeed.toFloat(),
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(additionalCoolingFanSpeed = it.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.minimum_fan_speed),
            valueText = stringResource(R.string.percent_value, activeProfile.fanMinSpeed),
            value = activeProfile.fanMinSpeed.toFloat(),
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(
                            fanMinSpeed = it.roundToInt().coerceAtMost(activeProfile.fanMaxSpeed),
                        ),
                    ),
                )
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.minimum_fan_layer_time),
            value = activeProfile.fanCoolingLayerTime,
            maximum = 1_000f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(fanCoolingLayerTime = it),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.maximum_fan_speed),
            valueText = stringResource(R.string.percent_value, activeProfile.fanMaxSpeed),
            value = activeProfile.fanMaxSpeed.toFloat(),
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(
                            fanMaxSpeed = it.roundToInt().coerceAtLeast(activeProfile.fanMinSpeed),
                        ),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.keep_fan_running),
            checked = activeProfile.keepFanAlwaysOn,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(keepFanAlwaysOn = it),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.slow_down_for_cooling),
            checked = activeProfile.slowDownForLayerCooling,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(slowDownForLayerCooling = it),
                    ),
                )
            },
        )
        if (activeProfile.slowDownForLayerCooling || settingsQuery.isNotBlank()) {
            SettingsSwitch(
                label = stringResource(R.string.keep_outer_wall_speed),
                checked = activeProfile.dontSlowDownOuterWall,
                onCheckedChange = {
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(dontSlowDownOuterWall = it),
                        ),
                    )
                },
            )
        }
        SettingsSwitch(
            label = stringResource(R.string.overhang_bridge_cooling),
            checked = activeProfile.enableOverhangBridgeFan,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(enableOverhangBridgeFan = it),
                    ),
                )
            },
        )
        if (activeProfile.enableOverhangBridgeFan || settingsQuery.isNotBlank()) {
            SettingChoices(
                settingLabel = stringResource(R.string.overhang_fan_threshold),
                entries = OVERHANG_FAN_THRESHOLDS,
                selected = activeProfile.overhangFanThreshold,
                optionLabel = { it },
                onSelected = {
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(overhangFanThreshold = it),
                        ),
                    )
                },
            )
            SettingSlider(
                label = stringResource(R.string.overhang_fan_speed),
                valueText = stringResource(R.string.percent_value, activeProfile.overhangFanSpeed),
                value = activeProfile.overhangFanSpeed.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = {
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(overhangFanSpeed = it.roundToInt()),
                        ),
                    )
                },
            )
            SettingSlider(
                label = stringResource(R.string.internal_bridge_fan_speed),
                valueText =
                    if (activeProfile.internalBridgeFanSpeed < 0) {
                        stringResource(R.string.fan_override_disabled)
                    } else {
                        stringResource(R.string.percent_value, activeProfile.internalBridgeFanSpeed)
                    },
                value = activeProfile.internalBridgeFanSpeed.toFloat(),
                range = -1f..100f,
                steps = 100,
                onValueChange = {
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(internalBridgeFanSpeed = it.roundToInt()),
                        ),
                    )
                },
            )
        }
        SettingSlider(
            label = stringResource(R.string.support_interface_fan_speed),
            valueText =
                if (activeProfile.supportInterfaceFanSpeed < 0) {
                    stringResource(R.string.fan_override_disabled)
                } else {
                    stringResource(R.string.percent_value, activeProfile.supportInterfaceFanSpeed)
                },
            value = activeProfile.supportInterfaceFanSpeed.toFloat(),
            range = -1f..100f,
            steps = 100,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(supportInterfaceFanSpeed = it.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.ironing_fan_speed),
            valueText =
                if (activeProfile.ironingFanSpeed < 0) {
                    stringResource(R.string.filament_automatic)
                } else {
                    stringResource(R.string.percent_value, activeProfile.ironingFanSpeed)
                },
            value = activeProfile.ironingFanSpeed.toFloat(),
            range = -1f..100f,
            steps = 100,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(ironingFanSpeed = it.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.slow_down_layer_time),
            valueText = stringResource(R.string.seconds_value, activeProfile.slowDownLayerTime),
            value = activeProfile.slowDownLayerTime,
            range = 1f..30f,
            steps = 28,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(slowDownLayerTime = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.minimum_print_speed),
            valueText = stringResource(R.string.print_speed_value, activeProfile.slowDownMinSpeed),
            value = activeProfile.slowDownMinSpeed,
            range = 5f..50f,
            steps = 44,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(slowDownMinSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.no_fan_first_layers),
            valueText = activeProfile.closeFanFirstLayers.toString(),
            value = activeProfile.closeFanFirstLayers.toFloat(),
            range = 0f..10f,
            steps = 9,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(closeFanFirstLayers = it.roundToInt()),
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.full_fan_layer),
            valueText = activeProfile.fullFanSpeedLayer.toString(),
            value = activeProfile.fullFanSpeedLayer.toFloat(),
            range = 1f..20f,
            steps = 18,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(fullFanSpeedLayer = it.roundToInt()),
                    ),
                )
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.pressure_advance),
            checked = activeProfile.pressureAdvanceEnabled,
            onCheckedChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(
                            pressureAdvanceEnabled = it,
                            adaptivePressureAdvance =
                                if (it) {
                                    activeProfile.adaptivePressureAdvance
                                } else {
                                    activeProfile.adaptivePressureAdvance.copy(enabled = false)
                                },
                        ),
                    ),
                )
            },
        )
        if (activeProfile.pressureAdvanceEnabled || settingsQuery.isNotBlank()) {
            SettingSlider(
                label = stringResource(R.string.pressure_advance_value),
                valueText = String.format(Locale.ROOT, "%.3f", activeProfile.pressureAdvance),
                value = activeProfile.pressureAdvance,
                range = 0f..0.2f,
                steps = 199,
                onValueChange = {
                    onOptionsChanged(
                        options.updateFilamentSlot(selectedSlot, activeProfile.copy(pressureAdvance = it)),
                    )
                },
            )
            SettingsSwitch(
                label = stringResource(R.string.adaptive_pressure_advance),
                checked = activeProfile.adaptivePressureAdvance.enabled,
                onCheckedChange = { enabled ->
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(
                                pressureAdvanceEnabled =
                                    activeProfile.pressureAdvanceEnabled || enabled,
                                adaptivePressureAdvance =
                                    activeProfile.adaptivePressureAdvance.copy(
                                        enabled = enabled,
                                    ),
                            ),
                        ),
                    )
                },
            )
            if (activeProfile.adaptivePressureAdvance.enabled || settingsQuery.isNotBlank()) {
                AdaptivePressureAdvanceModelSetting(
                    value = activeProfile.adaptivePressureAdvance.model,
                    onValueChange = { model ->
                        onOptionsChanged(
                            options.updateFilamentSlot(
                                selectedSlot,
                                activeProfile.copy(
                                    adaptivePressureAdvance =
                                        activeProfile.adaptivePressureAdvance.copy(
                                            model = model,
                                        ),
                                ),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.adaptive_pressure_advance_overhangs),
                    checked = activeProfile.adaptivePressureAdvance.overhangs,
                    onCheckedChange = { enabled ->
                        onOptionsChanged(
                            options.updateFilamentSlot(
                                selectedSlot,
                                activeProfile.copy(
                                    adaptivePressureAdvance =
                                        activeProfile.adaptivePressureAdvance.copy(
                                            overhangs = enabled,
                                        ),
                                ),
                            ),
                        )
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.adaptive_pressure_advance_bridge),
                    valueText =
                        String.format(
                            Locale.ROOT,
                            "%.3f",
                            activeProfile.adaptivePressureAdvance.bridge,
                        ),
                    value = activeProfile.adaptivePressureAdvance.bridge,
                    range = 0f..2f,
                    steps = 1_999,
                    onValueChange = { bridge ->
                        onOptionsChanged(
                            options.updateFilamentSlot(
                                selectedSlot,
                                activeProfile.copy(
                                    adaptivePressureAdvance =
                                        activeProfile.adaptivePressureAdvance.copy(
                                            bridge = bridge,
                                        ),
                                ),
                            ),
                        )
                    },
                )
            }
        }
        SettingsGroupTitle(stringResource(R.string.filament_gcode))
        GcodeTemplateSetting(
            label = stringResource(R.string.filament_start_gcode),
            value = activeProfile.filamentStartGcode,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(filamentStartGcode = it),
                    ),
                )
            },
        )
        GcodeTemplateSetting(
            label = stringResource(R.string.filament_end_gcode),
            value = activeProfile.filamentEndGcode,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(filamentEndGcode = it),
                    ),
                )
            },
        )
        SaveProfileField(
            onSave = { name -> onSave(name, options, selectedSlot) },
            onDismiss = onDismiss,
        )
    }
    if (profilesOpen) {
        ProfileChooserSheet(
            entries =
                if (selectedSlot == 0) {
                    profiles
                } else {
                    profiles.filter { it.hasCompatibleDiameter(options.filamentProfile) }
                },
            selected = activeProfile,
            recentIds = recentIds,
            id = { it.id },
            name = { it.name },
            label = { profileLabel(it) },
            brand = { it.brand },
            builtIn = { it.builtIn },
            searchTerms = {
                listOf(
                    it.name,
                    it.brand.orEmpty(),
                    it.nativeName,
                    it.diameter.toString(),
                    it.density.toString(),
                    it.costPerKilogram.toString(),
                )
            },
            onSelected = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, it))
                profilesOpen = false
            },
            onDelete = { onDelete(it.id) },
            onRename = onRename,
            onDismiss = { profilesOpen = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FilamentColorSetting(
    slot: Int,
    color: Int,
    profileDefaultColor: Int?,
    onSelected: (Int) -> Unit,
) {
    val label = stringResource(R.string.filament_color)
    if (!settingMatchesQuery(label)) return
    var pickerOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = { pickerOpen = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        color = Color.Transparent,
        contentColor = Color(0xFFF4F4EE),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                color = Color(0xFF000000.toInt() or color),
                shape = MaterialTheme.shapes.extraLarge,
            ) {}
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.filament_color_for_tool, slot + 1),
                    color = Color(0xFFC8C9C2),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
    if (pickerOpen) {
        var customColorText by rememberSaveable(color) {
            mutableStateOf(filamentColorText(color))
        }
        val customColor = parseFilamentColor(customColorText)
        ModalBottomSheet(
            onDismissRequest = { pickerOpen = false },
            containerColor = Color(0xFF282925),
            contentColor = Color(0xFFF4F4EE),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.filament_color_for_tool, slot + 1),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = customColorText,
                    onValueChange = { value ->
                        customColorText = value.trim().take(7)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.custom_filament_color)) },
                    supportingText = {
                        Text(stringResource(R.string.filament_color_code_example))
                    },
                    leadingIcon = {
                        Surface(
                            modifier = Modifier.size(24.dp),
                            color =
                                customColor
                                    ?.let { Color(0xFF000000.toInt() or it) }
                                    ?: Color.Transparent,
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {}
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = customColor == null,
                )
                Button(
                    onClick = {
                        onSelected(checkNotNull(customColor))
                        pickerOpen = false
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = customColor != null && customColor != color,
                ) {
                    Text(stringResource(R.string.use_filament_color))
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                (listOfNotNull(profileDefaultColor, color) + DefaultFilamentColors)
                    .distinct()
                    .chunked(4)
                    .forEach { rowColors ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowColors.forEach { choice ->
                                val selectedChoice = choice == color
                                val optionIndex = DefaultFilamentColors.indexOf(choice)
                                val optionLabel =
                                    if (choice == profileDefaultColor) {
                                        stringResource(R.string.default_color)
                                    } else if (optionIndex >= 0) {
                                        stringResource(R.string.filament_color_option, optionIndex + 1)
                                    } else {
                                        stringResource(R.string.custom_filament_color)
                                    }
                                Surface(
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .selectable(
                                                selected = selectedChoice,
                                                role = Role.RadioButton,
                                                onClick = {
                                                    onSelected(choice)
                                                    pickerOpen = false
                                                },
                                            ).semantics {
                                                contentDescription = optionLabel
                                            },
                                    color = Color(0xFF000000.toInt() or choice),
                                    shape = MaterialTheme.shapes.large,
                                    tonalElevation = if (selectedChoice) 8.dp else 0.dp,
                                ) {
                                    if (selectedChoice) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().height(58.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "✓",
                                                color =
                                                    Color(
                                                        0xFF000000.toInt() or
                                                            filamentColorContrast(choice),
                                                    ),
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(4 - rowColors.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
            }
        }
    }
}

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
internal fun RotationTemplateSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.length <= 128 && candidate.all { it.isDigit() || it in "-+., " }) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.infill_rotation_template_example)) },
        supportingText = {
            Text(
                stringResource(
                    if (rotationTemplateIsValid(value)) {
                        R.string.infill_rotation_template_hint
                    } else {
                        R.string.infill_rotation_template_invalid
                    },
                ),
            )
        },
        isError = !rotationTemplateIsValid(value),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun ExtraSolidInfillsSetting(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.extra_solid_infills)
    if (!settingMatchesQuery(label)) return
    val valid = extraSolidInfillsIsValid(value)
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (
                candidate.length <= MAX_EXTRA_SOLID_INFILL_PATTERN_LENGTH &&
                candidate.all { it.isDigit() || it == '#' || it == ',' || it.isWhitespace() }
            ) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.extra_solid_infills_example)) },
        supportingText = {
            Text(
                stringResource(
                    if (valid) {
                        R.string.extra_solid_infills_hint
                    } else {
                        R.string.extra_solid_infills_invalid
                    },
                ),
            )
        },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SmallAreaFlowCompensationModelSetting(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.small_area_flow_compensation_model)
    if (!settingMatchesQuery(label)) return
    val valid = smallAreaFlowCompensationModelIsValid(value)
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_SMALL_AREA_FLOW_MODEL_BYTES) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        supportingText = {
            Text(
                stringResource(
                    if (valid) {
                        R.string.small_area_flow_compensation_model_hint
                    } else {
                        R.string.small_area_flow_compensation_model_invalid
                    },
                ),
            )
        },
        isError = !valid,
        minLines = 5,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun AdaptivePressureAdvanceModelSetting(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.adaptive_pressure_advance_model)
    if (!settingMatchesQuery(label)) return
    val valid = adaptivePressureAdvanceModelIsValid(value)
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_ADAPTIVE_PRESSURE_ADVANCE_MODEL_BYTES) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        supportingText = {
            Text(
                stringResource(
                    if (valid) {
                        R.string.adaptive_pressure_advance_model_hint
                    } else {
                        R.string.adaptive_pressure_advance_model_invalid
                    },
                ),
            )
        },
        isError = !valid,
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SmallAreaFlowCompensationSettings(
    options: SliceOptions,
    settingsQuery: String,
    onOptionsChanged: (SliceOptions) -> Unit,
) {
    SettingsSwitch(
        label = stringResource(R.string.small_area_flow_compensation),
        checked = options.quality.smallAreaFlowCompensation,
        onCheckedChange = {
            onOptionsChanged(
                options.copy(
                    quality = options.quality.copy(smallAreaFlowCompensation = it),
                ),
            )
        },
    )
    if (options.quality.smallAreaFlowCompensation || settingsQuery.isNotBlank()) {
        SmallAreaFlowCompensationModelSetting(
            value = options.quality.smallAreaFlowCompensationModel,
            onValueChange = {
                onOptionsChanged(
                    options.copy(
                        quality = options.quality.copy(smallAreaFlowCompensationModel = it),
                    ),
                )
            },
        )
    }
}

@Composable
internal fun BedExcludeAreaSetting(
    value: List<Float>,
    bedSizeX: Float,
    bedSizeY: Float,
    onValueChange: (List<Float>) -> Unit,
) {
    val label = stringResource(R.string.bed_exclude_area)
    if (!settingMatchesQuery(label)) return
    var input by rememberSaveable(label) { mutableStateOf(formatBedExcludeArea(value)) }
    var lastApplied by rememberSaveable(label, stateSaver = FloatListEditorSaver) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != lastApplied) {
            input = formatBedExcludeArea(value)
            lastApplied = value
        }
    }
    val parsed = parseBedExcludeArea(input, bedSizeX, bedSizeY)
    OutlinedTextField(
        value = input,
        onValueChange = { candidate ->
            if (candidate.length <= 4_096 &&
                candidate.all {
                    it.isDigit() || it in "-+.,; \n\t"
                }
            ) {
                input = candidate
                parseBedExcludeArea(candidate, bedSizeX, bedSizeY)?.let { geometry ->
                    lastApplied = geometry
                    onValueChange(geometry)
                }
            }
        },
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.bed_exclude_area_example)) },
        supportingText = {
            Text(
                stringResource(
                    if (parsed != null) {
                        R.string.bed_exclude_area_hint
                    } else {
                        R.string.bed_exclude_area_invalid
                    },
                ),
            )
        },
        isError = parsed == null,
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun parseBedExcludeArea(
    value: String,
    bedSizeX: Float,
    bedSizeY: Float,
): List<Float>? {
    if (value.isBlank()) return listOf(0f, 0f)
    val coordinates =
        value
            .split(';', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { pair ->
                val values = pair.split(',').map(String::trim)
                if (values.size != 2) return null
                values.map { it.replace(',', '.').toFloatOrNull() ?: return null }
            }
    return coordinates.takeIf { bedExcludeAreaIsValid(it, bedSizeX, bedSizeY) }
}

internal fun formatBedExcludeArea(value: List<Float>): String {
    if (value.size == 2 && value.all { abs(it) <= 0.001f }) return ""
    return value.chunked(2).joinToString("; ") { pair ->
        "${editableDecimal(pair[0])},${editableDecimal(pair[1])}"
    }
}

@Composable
internal fun GcodeTemplateSetting(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_TEMPLATE_BYTES) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun FilenameFormatSetting(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.filename_format)
    if (!settingMatchesQuery(label)) return
    val valid = filenameFormatIsValid(value)
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (
                candidate.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_FILENAME_FORMAT_BYTES &&
                candidate.none { it == '\u0000' || it == '\r' || it == '\n' }
            ) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        placeholder = { Text(DEFAULT_GCODE_FILENAME_FORMAT) },
        supportingText = {
            Text(
                stringResource(
                    if (valid) R.string.filename_format_hint else R.string.filename_format_invalid,
                ),
            )
        },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun GcodeThumbnailSetting(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.gcode_thumbnails)
    if (!settingMatchesQuery(label)) return
    var input by rememberSaveable(label) { mutableStateOf(value) }
    var lastApplied by rememberSaveable(label) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != lastApplied) {
            input = value
            lastApplied = value
        }
    }
    val normalized = canonicalGcodeThumbnailDefinitions(input)
    val valid = normalized != null
    OutlinedTextField(
        value = input,
        onValueChange = { candidate ->
            if (
                candidate.toByteArray(Charsets.UTF_8).size <= MAX_GCODE_THUMBNAIL_BYTES &&
                candidate.all { it.isLetterOrDigit() || it in "xX/_-, " }
            ) {
                input = candidate
                val canonical = canonicalGcodeThumbnailDefinitions(candidate)
                if (canonical != null) {
                    lastApplied = canonical
                    onValueChange(canonical)
                }
            }
        },
        label = { Text(label) },
        placeholder = { Text("32x32/PNG,300x300/PNG") },
        supportingText = {
            Text(
                stringResource(
                    if (valid) {
                        R.string.gcode_thumbnails_hint
                    } else {
                        R.string.gcode_thumbnails_invalid
                    },
                ),
            )
        },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun SpiralVaseSettings(
    options: SliceOptions,
    showInactive: Boolean,
    onOptionsChanged: (SliceOptions) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.spiral_vase))
    SettingsSwitch(
        label = stringResource(R.string.spiral_vase),
        checked = options.spiralMode,
        onCheckedChange = { enabled ->
            onOptionsChanged(options.withSpiralMode(enabled))
        },
    )
    if (options.spiralMode || showInactive) {
        Text(
            stringResource(R.string.spiral_vase_summary),
            color = Color(0xFFC8C9C2),
            style = MaterialTheme.typography.bodySmall,
        )
        SettingsSwitch(
            label = stringResource(R.string.smooth_spiral),
            checked = options.spiralModeSmooth,
            onCheckedChange = {
                onOptionsChanged(options.copy(spiralModeSmooth = it))
            },
        )
        LengthOrPercentSetting(
            label = stringResource(R.string.max_xy_smoothing),
            value = options.spiralModeMaxXySmoothing,
            percent = options.spiralModeMaxXySmoothingPercent,
            maximumAbsolute = 10f,
            maximumPercent = 1_000f,
            onValueChange = {
                onOptionsChanged(options.copy(spiralModeMaxXySmoothing = it))
            },
            onPercentChange = { percent, value ->
                onOptionsChanged(
                    options.copy(
                        spiralModeMaxXySmoothing = value,
                        spiralModeMaxXySmoothingPercent = percent,
                    ),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.spiral_starting_flow),
            valueText =
                stringResource(
                    R.string.flow_ratio_value,
                    options.spiralStartingFlowRatio,
                ),
            value = options.spiralStartingFlowRatio,
            range = 0f..1f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.copy(spiralStartingFlowRatio = (it * 100f).roundToInt() / 100f),
                )
            },
        )
        SettingSlider(
            label = stringResource(R.string.spiral_finishing_flow),
            valueText =
                stringResource(
                    R.string.flow_ratio_value,
                    options.spiralFinishingFlowRatio,
                ),
            value = options.spiralFinishingFlowRatio,
            range = 0f..1f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(
                    options.copy(spiralFinishingFlowRatio = (it * 100f).roundToInt() / 100f),
                )
            },
        )
    }
}

@Composable
internal fun FilamentPrimeTowerInterfaceSettings(
    profile: FilamentProfile,
    onChanged: (FilamentProfile) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.filament_prime_tower_interface))
    DecimalSettingField(
        label = stringResource(R.string.tower_interface_pre_extrusion_distance),
        value = profile.towerInterfacePreExtrusionDistance,
        maximum = 1_000f,
        suffix = stringResource(R.string.millimeters_suffix),
        onValueChange = { onChanged(profile.copy(towerInterfacePreExtrusionDistance = it)) },
    )
    DecimalSettingField(
        label = stringResource(R.string.tower_interface_pre_extrusion_length),
        value = profile.towerInterfacePreExtrusionLength,
        maximum = 1_000f,
        suffix = stringResource(R.string.millimeters_suffix),
        onValueChange = { onChanged(profile.copy(towerInterfacePreExtrusionLength = it)) },
    )
    DecimalSettingField(
        label = stringResource(R.string.tower_ironing_area),
        value = profile.towerIroningArea,
        maximum = 10_000f,
        suffix = stringResource(R.string.square_millimeters_suffix),
        onValueChange = { onChanged(profile.copy(towerIroningArea = it)) },
    )
    DecimalSettingField(
        label = stringResource(R.string.tower_interface_purge_length),
        value = profile.towerInterfacePurgeLength,
        maximum = 1_000f,
        suffix = stringResource(R.string.millimeters_suffix),
        onValueChange = { onChanged(profile.copy(towerInterfacePurgeLength = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.tower_interface_print_temperature),
        valueText =
            if (profile.towerInterfacePrintTemperature < 0) {
                stringResource(R.string.filament_automatic)
            } else {
                stringResource(R.string.celsius_value, profile.towerInterfacePrintTemperature)
            },
        value = profile.towerInterfacePrintTemperature.toFloat(),
        range = -1f..500f,
        steps = 500,
        onValueChange = {
            onChanged(profile.copy(towerInterfacePrintTemperature = it.roundToInt()))
        },
    )
}

@Composable
internal fun PrimeTowerStructureSettings(
    settings: MultiMaterialSettings,
    showInactive: Boolean,
    onChanged: (MultiMaterialSettings) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.prime_tower_structure))
    SettingsSwitch(
        label = stringResource(R.string.prime_tower_framework),
        checked = settings.primeTowerFramework,
        onCheckedChange = { onChanged(settings.copy(primeTowerFramework = it)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.prime_tower_skip_points),
        checked = settings.primeTowerSkipPoints,
        onCheckedChange = { onChanged(settings.copy(primeTowerSkipPoints = it)) },
    )
    SettingSlider(
        label = stringResource(R.string.prime_tower_infill_gap),
        valueText = stringResource(R.string.percent_value, settings.primeTowerInfillGap.roundToInt()),
        value = settings.primeTowerInfillGap.coerceIn(100f, 1_000f),
        range = 100f..1_000f,
        steps = 899,
        onValueChange = {
            onChanged(settings.copy(primeTowerInfillGap = it.roundToInt().toFloat()))
        },
    )
    SettingsSwitch(
        label = stringResource(R.string.prime_tower_flat_ironing),
        checked = settings.primeTowerFlatIroning,
        onCheckedChange = { onChanged(settings.copy(primeTowerFlatIroning = it)) },
    )
    SettingsSwitch(
        label = stringResource(R.string.prime_tower_interface_features),
        checked = settings.primeTowerInterfaceFeatures,
        onCheckedChange = { onChanged(settings.copy(primeTowerInterfaceFeatures = it)) },
    )
    if (settings.primeTowerInterfaceFeatures || showInactive) {
        SettingsSwitch(
            label = stringResource(R.string.prime_tower_interface_cooldown),
            checked = settings.primeTowerInterfaceCooldown,
            enabled = settings.primeTowerInterfaceFeatures,
            onCheckedChange = { onChanged(settings.copy(primeTowerInterfaceCooldown = it)) },
        )
    }
}

@Composable
internal fun PrimeTowerBrimChamferSettings(
    settings: MultiMaterialSettings,
    showInactive: Boolean,
    onChanged: (MultiMaterialSettings) -> Unit,
) {
    SettingsSwitch(
        label = stringResource(R.string.prime_tower_brim_chamfer),
        checked = settings.primeTowerBrimChamfer,
        onCheckedChange = { onChanged(settings.copy(primeTowerBrimChamfer = it)) },
    )
    if (settings.primeTowerBrimChamfer || showInactive) {
        val maximum = max(20f, settings.primeTowerBrimChamferMaxWidth)
        SettingSlider(
            label = stringResource(R.string.prime_tower_brim_chamfer_max_width),
            valueText =
                stringResource(
                    R.string.millimeters_value_precise,
                    settings.primeTowerBrimChamferMaxWidth,
                ),
            value = settings.primeTowerBrimChamferMaxWidth,
            range = 0f..maximum,
            steps = (maximum * 2f).roundToInt().coerceAtLeast(2) - 1,
            onValueChange = {
                onChanged(
                    settings.copy(
                        primeTowerBrimChamferMaxWidth = (it * 2f).roundToInt() / 2f,
                    ),
                )
            },
        )
    }
}

@Composable
internal fun PurgeMultiplierSettings(
    settings: MultiMaterialSettings,
    showInactive: Boolean,
    onChanged: (MultiMaterialSettings) -> Unit,
) {
    SettingsSwitch(
        label = stringResource(R.string.custom_purge_multiplier),
        checked = settings.flushMultiplierOverrideEnabled,
        onCheckedChange = {
            onChanged(settings.copy(flushMultiplierOverrideEnabled = it))
        },
    )
    if (settings.flushMultiplierOverrideEnabled || showInactive) {
        val percentage = settings.flushMultiplier * 100f
        val maximum = max(300f, percentage)
        SettingSlider(
            label = stringResource(R.string.purge_multiplier),
            valueText = stringResource(R.string.percent_value, percentage.roundToInt()),
            value = percentage,
            range = 0f..maximum,
            steps = maximum.roundToInt().coerceAtLeast(2) - 1,
            enabled = settings.flushMultiplierOverrideEnabled,
            onValueChange = {
                onChanged(settings.copy(flushMultiplier = it.roundToInt() / 100f))
            },
        )
    }
}

@Composable
internal fun DecimalSettingField(
    label: String,
    value: Float,
    minimum: Float = 0f,
    maximum: Float,
    suffix: String,
    onValueChange: (Float) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var input by rememberSaveable(label) { mutableStateOf(editableDecimal(value)) }
    var lastApplied by rememberSaveable(label) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (abs(value - lastApplied) >= 0.001f) {
            input = editableDecimal(value)
            lastApplied = value
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { candidate ->
            if (candidate.length <= 12 && candidate.matches(DECIMAL_INPUT)) {
                input = candidate
                candidate
                    .replace(',', '.')
                    .toFloatOrNull()
                    ?.takeIf { it.isFinite() && it in minimum..maximum }
                    ?.let { parsed ->
                        lastApplied = parsed
                        onValueChange(parsed)
                    }
            }
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = input.replace(',', '.').toFloatOrNull()?.let { it !in minimum..maximum } ?: true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun editableDecimal(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else value.toString()

internal val DECIMAL_INPUT = Regex("[0-9]{0,7}([.,][0-9]{0,2})?")

@Composable
internal fun CoordinatePairSettingField(
    label: String,
    valueX: Float,
    valueY: Float,
    minimum: Float,
    maximum: Float,
    suffix: String,
    isValid: (Float, Float) -> Boolean = { _, _ -> true },
    onValueChange: (Float, Float) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var input by rememberSaveable(label) { mutableStateOf(editableCoordinatePair(valueX, valueY)) }
    var lastApplied by rememberSaveable(label, stateSaver = CoordinatePairEditorSaver) {
        mutableStateOf(valueX to valueY)
    }
    LaunchedEffect(valueX, valueY) {
        if (abs(valueX - lastApplied.first) >= 0.001f ||
            abs(valueY - lastApplied.second) >= 0.001f
        ) {
            input = editableCoordinatePair(valueX, valueY)
            lastApplied = valueX to valueY
        }
    }
    val parsed =
        parseCoordinatePair(input)?.takeIf { (x, y) ->
            x in minimum..maximum && y in minimum..maximum && isValid(x, y)
        }
    OutlinedTextField(
        value = input,
        onValueChange = { candidate ->
            if (candidate.length <= 30 && candidate.matches(COORDINATE_PAIR_INPUT)) {
                input = candidate
                parseCoordinatePair(candidate)
                    ?.takeIf { (x, y) ->
                        x in minimum..maximum && y in minimum..maximum && isValid(x, y)
                    }?.let { (x, y) ->
                        lastApplied = x to y
                        onValueChange(x, y)
                    }
            }
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = parsed == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun editableCoordinatePair(
    x: Float,
    y: Float,
): String = "${editableDecimal(x)}, ${editableDecimal(y)}"

internal fun parseCoordinatePair(value: String): Pair<Float, Float>? {
    val parts = value.split(',')
    if (parts.size != 2) return null
    val x = parts[0].trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    val y = parts[1].trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    return x to y
}

internal val COORDINATE_PAIR_INPUT =
    Regex(
        "-?[0-9]{0,6}(\\.[0-9]{0,3})?(\\s*,\\s*-?[0-9]{0,6}(\\.[0-9]{0,3})?)?",
    )

@Composable
internal fun IntegerSettingField(
    label: String,
    value: Int,
    maximum: Int,
    suffix: String,
    supportingText: String,
    onValueChange: (Int) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var input by rememberSaveable(label) { mutableStateOf(value.toString()) }
    var lastApplied by rememberSaveable(label) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != lastApplied) {
            input = value.toString()
            lastApplied = value
        }
    }
    val parsed = input.toIntOrNull()
    OutlinedTextField(
        value = input,
        onValueChange = { candidate ->
            if (candidate.length <= maximum.toString().length && candidate.all(Char::isDigit)) {
                input = candidate
                candidate
                    .toIntOrNull()
                    ?.takeIf { it in 0..maximum }
                    ?.let { newValue ->
                        lastApplied = newValue
                        onValueChange(newValue)
                    }
            }
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        supportingText = { Text(supportingText) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = parsed == null || parsed !in 0..maximum,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun OverhangSpeedSetting(
    label: String,
    value: Float,
    percent: Boolean,
    maximumAbsolute: Float,
    maximumPercent: Float = 100f,
    onValueChange: (Float) -> Unit,
    onPercentChange: (Boolean, Float) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    Text(label, fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf(false, true),
        selected = percent,
        label = { if (it) "%" else "mm/s" },
        onSelected = { selectedPercent ->
            val newMaximum = if (selectedPercent) maximumPercent else maximumAbsolute
            onPercentChange(selectedPercent, value.coerceAtMost(newMaximum))
        },
    )
    val maximum = if (percent) maximumPercent else maximumAbsolute
    SettingSlider(
        label = label,
        valueText =
            if (percent) {
                stringResource(R.string.percent_value, value.coerceAtMost(maximumPercent).roundToInt())
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
internal fun AccelerationOrPercentSetting(
    label: String,
    value: Float,
    percent: Boolean,
    maximumAbsolute: Float,
    onValueChange: (Float) -> Unit,
    onPercentChange: (Boolean, Float) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    Text(label, fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf(false, true),
        selected = percent,
        label = { if (it) "%" else "mm/s²" },
        onSelected = { selectedPercent ->
            val newMaximum = if (selectedPercent) 300f else maximumAbsolute
            onPercentChange(selectedPercent, value.coerceAtMost(newMaximum))
        },
    )
    val maximum = if (percent) 300f else maximumAbsolute
    SettingSlider(
        label = label,
        valueText =
            if (percent) {
                stringResource(R.string.percent_value, value.coerceAtMost(maximum).roundToInt())
            } else {
                stringResource(R.string.acceleration_value, value)
            },
        value = value.coerceIn(0f, maximum),
        range = 0f..maximum,
        steps = if (percent) 299 else (maximum / 100f).roundToInt().coerceAtLeast(2) - 1,
        onValueChange = {
            onValueChange(if (percent) it.roundToInt().toFloat() else (it / 100f).roundToInt() * 100f)
        },
    )
}

@Composable
internal fun AccelerationSettings(
    options: SliceOptions,
    maximumFeatureAcceleration: Float,
    featureAccelerationSteps: Int,
    onOptionsChanged: (SliceOptions) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.feature_acceleration))
    listOf(
        Triple(R.string.default_acceleration, options.defaultAcceleration) { value: Float ->
            options.copy(defaultAcceleration = value)
        },
        Triple(R.string.outer_wall_acceleration, options.outerWallAcceleration) { value: Float ->
            options.copy(outerWallAcceleration = value)
        },
        Triple(R.string.inner_wall_acceleration, options.innerWallAcceleration) { value: Float ->
            options.copy(innerWallAcceleration = value)
        },
        Triple(R.string.top_surface_acceleration, options.topSurfaceAcceleration) { value: Float ->
            options.copy(topSurfaceAcceleration = value)
        },
        Triple(R.string.travel_acceleration, options.travelAcceleration) { value: Float ->
            options.copy(travelAcceleration = value)
        },
        Triple(R.string.first_layer_acceleration, options.firstLayerAcceleration) { value: Float ->
            options.copy(firstLayerAcceleration = value)
        },
    ).forEach { (labelResource, value, update) ->
        SettingSlider(
            label = stringResource(labelResource),
            valueText = stringResource(R.string.acceleration_value, value),
            value = value,
            range = 0f..maximumFeatureAcceleration,
            steps = featureAccelerationSteps,
            onValueChange = {
                onOptionsChanged(update((it / 100f).roundToInt() * 100f))
            },
        )
    }
    AccelerationOrPercentSetting(
        label = stringResource(R.string.first_layer_travel_acceleration),
        value = options.firstLayerTravelAcceleration,
        percent = options.firstLayerTravelAccelerationPercent,
        maximumAbsolute = maximumFeatureAcceleration,
        onValueChange = { onOptionsChanged(options.copy(firstLayerTravelAcceleration = it)) },
        onPercentChange = { selectedPercent, adjustedValue ->
            onOptionsChanged(
                options.copy(
                    firstLayerTravelAcceleration = adjustedValue,
                    firstLayerTravelAccelerationPercent = selectedPercent,
                ),
            )
        },
    )
    AccelerationOrPercentSetting(
        label = stringResource(R.string.bridge_acceleration),
        value = options.bridgeAcceleration,
        percent = options.bridgeAccelerationPercent,
        maximumAbsolute = maximumFeatureAcceleration,
        onValueChange = { onOptionsChanged(options.copy(bridgeAcceleration = it)) },
        onPercentChange = { selectedPercent, adjustedValue ->
            onOptionsChanged(options.copy(bridgeAcceleration = adjustedValue, bridgeAccelerationPercent = selectedPercent))
        },
    )
    AccelerationOrPercentSetting(
        label = stringResource(R.string.sparse_infill_acceleration),
        value = options.sparseInfillAcceleration,
        percent = options.sparseInfillAccelerationPercent,
        maximumAbsolute = maximumFeatureAcceleration,
        onValueChange = { onOptionsChanged(options.copy(sparseInfillAcceleration = it)) },
        onPercentChange = { selectedPercent, adjustedValue ->
            onOptionsChanged(
                options.copy(
                    sparseInfillAcceleration = adjustedValue,
                    sparseInfillAccelerationPercent = selectedPercent,
                ),
            )
        },
    )
    AccelerationOrPercentSetting(
        label = stringResource(R.string.internal_solid_acceleration),
        value = options.internalSolidInfillAcceleration,
        percent = options.internalSolidInfillAccelerationPercent,
        maximumAbsolute = maximumFeatureAcceleration,
        onValueChange = { onOptionsChanged(options.copy(internalSolidInfillAcceleration = it)) },
        onPercentChange = { selectedPercent, adjustedValue ->
            onOptionsChanged(
                options.copy(
                    internalSolidInfillAcceleration = adjustedValue,
                    internalSolidInfillAccelerationPercent = selectedPercent,
                ),
            )
        },
    )
}

@Composable
internal fun LengthOrPercentSetting(
    label: String,
    value: Float,
    percent: Boolean,
    maximumAbsolute: Float = 2f,
    maximumPercent: Float = 100f,
    onValueChange: (Float) -> Unit,
    onPercentChange: (Boolean, Float) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    Text(label, fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = listOf(false, true),
        selected = percent,
        label = { if (it) "%" else "mm" },
        onSelected = { selectedPercent ->
            val newMaximum = if (selectedPercent) maximumPercent else maximumAbsolute
            onPercentChange(selectedPercent, value.coerceAtMost(newMaximum))
        },
    )
    val maximum = if (percent) maximumPercent else maximumAbsolute
    SettingSlider(
        label = label,
        valueText =
            if (percent) {
                stringResource(R.string.percent_value, value.coerceAtMost(maximumPercent).roundToInt())
            } else {
                stringResource(R.string.millimeters_value_precise, value)
            },
        value = value.coerceIn(0f, maximum),
        range = 0f..maximum,
        steps = if (percent) maximumPercent.roundToInt().coerceIn(2, 1_000) - 1 else 199,
        onValueChange = { onValueChange(if (percent) it.roundToInt().toFloat() else it) },
    )
}

@Composable
internal fun DirectionalPurgeSetting(
    options: SliceOptions,
    onChanged: (MultiMaterialSettings) -> Unit,
) {
    val filaments = options.resolvedFilamentSlots()
    if (filaments.size < 2) return
    val filamentColors = options.previewFilamentColors()
    val multiMaterial = options.multiMaterial
    val purgeLabel = stringResource(R.string.purge_volume)
    val fromLabel = stringResource(R.string.from_filament)
    val toLabel = stringResource(R.string.to_filament)
    val calculateLabel = stringResource(R.string.calculate_purge_volumes)
    val query = LocalSettingsQuery.current
    if (listOf(purgeLabel, fromLabel, toLabel, calculateLabel).none { settingQueryMatches(query, it) }) return

    var fromSlot by rememberSaveable(filaments.size) { mutableStateOf(1) }
    var toSlot by rememberSaveable(filaments.size) { mutableStateOf(2) }
    LaunchedEffect(filaments.size) {
        fromSlot = fromSlot.coerceIn(1, filaments.size)
        toSlot = toSlot.coerceIn(1, filaments.size)
        if (fromSlot == toSlot) toSlot = if (fromSlot < filaments.size) fromSlot + 1 else 1
    }
    val matrix = multiMaterial.resolvedPurgeVolumes(filaments.size)
    val volume = matrix[(fromSlot - 1) * filaments.size + (toSlot - 1)]

    fun differentFrom(slot: Int): Int = if (slot < filaments.size) slot + 1 else 1

    CompositionLocalProvider(LocalSettingsQuery provides "") {
        AutomaticPurgeVolumeSetting(
            options = options,
            onChanged = onChanged,
        )
        FilamentSlotSetting(
            label = fromLabel,
            filaments = filaments,
            filamentColors = filamentColors,
            selectedSlot = fromSlot,
            onSelected = { selected ->
                fromSlot = selected
                if (toSlot == selected) toSlot = differentFrom(selected)
            },
        )
        FilamentSlotSetting(
            label = toLabel,
            filaments = filaments,
            filamentColors = filamentColors,
            selectedSlot = toSlot,
            onSelected = { selected ->
                toSlot = selected
                if (fromSlot == selected) fromSlot = differentFrom(selected)
            },
        )
        SettingSlider(
            label = purgeLabel,
            valueText = stringResource(R.string.cubic_millimeters_value, volume),
            value = volume,
            range = MIN_PURGE_VOLUME..MAX_PURGE_VOLUME,
            steps = (MAX_PURGE_VOLUME - MIN_PURGE_VOLUME).roundToInt() - 1,
            onValueChange = {
                onChanged(
                    multiMaterial.withPurgeVolume(
                        filaments.size,
                        fromSlot - 1,
                        toSlot - 1,
                        it.roundToInt().toFloat(),
                    ),
                )
            },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FilamentSlotSetting(
    label: String,
    filaments: List<FilamentProfile>,
    filamentColors: List<Int> = DefaultFilamentColors,
    selectedSlot: Int,
    defaultLabel: String? = null,
    onSelected: (Int) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var pickerOpen by remember { mutableStateOf(false) }
    val boundedSlot = selectedSlot.coerceIn(if (defaultLabel == null) 1 else 0, filaments.size)
    val valueLabel =
        if (boundedSlot == 0) {
            requireNotNull(defaultLabel)
        } else {
            val filament = filaments[boundedSlot - 1]
            stringResource(R.string.filament_tool_summary, boundedSlot, profileLabel(filament))
        }
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
            if (boundedSlot > 0) {
                Surface(
                    modifier = Modifier.size(14.dp),
                    color = filamentSlotColor(boundedSlot - 1, filamentColors),
                    shape = MaterialTheme.shapes.extraSmall,
                ) {}
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = if (boundedSlot > 0) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(valueLabel, color = Color(0xFFC8C9C2), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
    if (pickerOpen) {
        ModalBottomSheet(
            onDismissRequest = { pickerOpen = false },
            containerColor = Color(0xFF282925),
            contentColor = Color(0xFFF4F4EE),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 680.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (defaultLabel != null) {
                    FilamentSlotChoice(
                        label = defaultLabel,
                        color = null,
                        selected = boundedSlot == 0,
                        onClick = {
                            onSelected(0)
                            pickerOpen = false
                        },
                    )
                }
                filaments.forEachIndexed { index, filament ->
                    val slot = index + 1
                    FilamentSlotChoice(
                        label =
                            stringResource(
                                R.string.filament_tool_summary,
                                slot,
                                profileLabel(filament),
                            ),
                        color = filamentSlotColor(index, filamentColors),
                        selected = boundedSlot == slot,
                        onClick = {
                            onSelected(slot)
                            pickerOpen = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun FilamentSlotChoice(
    label: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors =
                color?.let {
                    RadioButtonDefaults.colors(selectedColor = it, unselectedColor = it.copy(alpha = 0.72f))
                } ?: RadioButtonDefaults.colors(),
        )
        Text(label)
    }
}

@Composable
internal fun <T> CompactChoices(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelected: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        entries.forEach { entry ->
            val selectedEntry = entry == selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selectedEntry,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelected(entry) },
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedEntry, enabled = enabled, onClick = null)
                Text(label(entry))
            }
        }
    }
}

@Composable
internal fun LockedZagInfillSettings(
    quality: QualityProfile,
    onQualityChanged: (QualityProfile) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.locked_zag_infill))
    SettingSlider(
        label = stringResource(R.string.skin_infill_density),
        valueText = stringResource(R.string.percent_value, quality.skinInfillDensity.roundToInt()),
        value = quality.skinInfillDensity,
        range = 0f..100f,
        steps = 99,
        onValueChange = { onQualityChanged(quality.copy(skinInfillDensity = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.skeleton_infill_density),
        valueText = stringResource(R.string.percent_value, quality.skeletonInfillDensity.roundToInt()),
        value = quality.skeletonInfillDensity,
        range = 0f..100f,
        steps = 99,
        onValueChange = { onQualityChanged(quality.copy(skeletonInfillDensity = it.roundToInt().toFloat())) },
    )
    SettingSlider(
        label = stringResource(R.string.skin_infill_depth),
        valueText = stringResource(R.string.millimeters_value_precise, quality.skinInfillDepth),
        value = quality.skinInfillDepth,
        range = 0f..100f,
        steps = 999,
        onValueChange = {
            onQualityChanged(quality.copy(skinInfillDepth = (it * 10f).roundToInt() / 10f))
        },
    )
    SettingSlider(
        label = stringResource(R.string.infill_lock_depth),
        valueText = stringResource(R.string.millimeters_value_precise, quality.infillLockDepth),
        value = quality.infillLockDepth,
        range = 0f..100f,
        steps = 999,
        onValueChange = {
            onQualityChanged(quality.copy(infillLockDepth = (it * 10f).roundToInt() / 10f))
        },
    )
    LengthOrPercentSetting(
        label = stringResource(R.string.skin_infill_line_width),
        value = quality.skinInfillLineWidth,
        percent = quality.skinInfillLineWidthPercent,
        maximumAbsolute = 10f,
        maximumPercent = 1_000f,
        onValueChange = { onQualityChanged(quality.copy(skinInfillLineWidth = it)) },
        onPercentChange = { selectedPercent, adjustedValue ->
            onQualityChanged(
                quality.copy(
                    skinInfillLineWidth = adjustedValue,
                    skinInfillLineWidthPercent = selectedPercent,
                ),
            )
        },
    )
    LengthOrPercentSetting(
        label = stringResource(R.string.skeleton_infill_line_width),
        value = quality.skeletonInfillLineWidth,
        percent = quality.skeletonInfillLineWidthPercent,
        maximumAbsolute = 10f,
        maximumPercent = 1_000f,
        onValueChange = { onQualityChanged(quality.copy(skeletonInfillLineWidth = it)) },
        onPercentChange = { selectedPercent, adjustedValue ->
            onQualityChanged(
                quality.copy(
                    skeletonInfillLineWidth = adjustedValue,
                    skeletonInfillLineWidthPercent = selectedPercent,
                ),
            )
        },
    )
}

@Composable
internal fun LateralInfillGeometrySettings(
    settings: LateralInfillSettings,
    onSettingsChanged: (LateralInfillSettings) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.lateral_infill_geometry))
    SettingSlider(
        label = stringResource(R.string.lateral_lattice_angle_1),
        valueText = stringResource(R.string.degrees_value, settings.firstAngle),
        value = settings.firstAngle,
        range = -75f..75f,
        steps = 149,
        onValueChange = {
            onSettingsChanged(settings.copy(firstAngle = it.roundToInt().toFloat()))
        },
    )
    SettingSlider(
        label = stringResource(R.string.lateral_lattice_angle_2),
        valueText = stringResource(R.string.degrees_value, settings.secondAngle),
        value = settings.secondAngle,
        range = -75f..75f,
        steps = 149,
        onValueChange = {
            onSettingsChanged(settings.copy(secondAngle = it.roundToInt().toFloat()))
        },
    )
    SettingSlider(
        label = stringResource(R.string.infill_overhang_angle),
        valueText = stringResource(R.string.degrees_value, settings.overhangAngle),
        value = settings.overhangAngle,
        range = 15f..75f,
        steps = 59,
        onValueChange = {
            onSettingsChanged(settings.copy(overhangAngle = it.roundToInt().toFloat()))
        },
    )
}

@Composable
internal fun fillPatternLabel(value: String): String =
    when (value) {
        "crosshatch" -> stringResource(R.string.infill_cross_hatch)
        "line" -> stringResource(R.string.infill_line)
        "grid" -> stringResource(R.string.infill_grid)
        "triangles" -> stringResource(R.string.infill_triangles)
        "tri-hexagon" -> stringResource(R.string.infill_tri_hexagon)
        "cubic" -> stringResource(R.string.infill_cubic)
        "adaptivecubic" -> stringResource(R.string.infill_adaptive_cubic)
        "quartercubic" -> stringResource(R.string.infill_quarter_cubic)
        "supportcubic" -> stringResource(R.string.infill_support_cubic)
        "lightning" -> stringResource(R.string.infill_lightning)
        "honeycomb" -> stringResource(R.string.infill_honeycomb)
        "3dhoneycomb" -> stringResource(R.string.infill_3d_honeycomb)
        "lateral-honeycomb" -> stringResource(R.string.infill_lateral_honeycomb)
        "lateral-lattice" -> stringResource(R.string.infill_lateral_lattice)
        "tpmsd" -> stringResource(R.string.infill_tpms_d)
        "tpmsfk" -> stringResource(R.string.infill_tpms_fk)
        "concentric" -> stringResource(R.string.infill_concentric)
        "hilbertcurve" -> stringResource(R.string.infill_hilbert_curve)
        "archimedeanchords" -> stringResource(R.string.infill_archimedean_chords)
        "octagramspiral" -> stringResource(R.string.infill_octagram_spiral)
        "rectilinear" -> stringResource(R.string.infill_rectilinear)
        "alignedrectilinear" -> stringResource(R.string.infill_aligned_rectilinear)
        "zigzag" -> stringResource(R.string.infill_zig_zag)
        "crosszag" -> stringResource(R.string.infill_cross_zag)
        "lockedzag" -> stringResource(R.string.infill_locked_zag)
        "gyroid" -> stringResource(R.string.infill_gyroid)
        else -> enumLabel(value)
    }

internal fun enumLabel(value: String): String =
    value
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

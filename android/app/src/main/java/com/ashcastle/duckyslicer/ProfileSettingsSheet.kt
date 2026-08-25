package com.ashcastle.duckyslicer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

private enum class ProfileSettingsKind {
    PRINTER,
    FILAMENT,
    SLICING,
}

private val LocalSettingsQuery = compositionLocalOf { "" }
private const val MAX_GCODE_TEMPLATE_BYTES = 262_144

@Composable
private fun settingMatchesQuery(label: String): Boolean {
    return settingQueryMatches(LocalSettingsQuery.current, label)
}

internal fun settingQueryMatches(query: String, label: String): Boolean {
    val normalized = query.trim().lowercase(Locale.ROOT)
    if (normalized.isBlank()) return true
    val candidate = label.lowercase(Locale.ROOT)
    return normalized.split(' ').filter(String::isNotBlank).all(candidate::contains)
}

internal fun SliceOptions.withPrintSequenceSelection(sequence: String): SliceOptions = copy(
    printSequence = sequence,
    gcodeSettings = if (sequence == "by object" && gcodeSettings.timelapseType == "smooth") {
        gcodeSettings.copy(timelapseType = "traditional")
    } else {
        gcodeSettings
    },
)

internal fun SliceOptions.withTimelapseSelection(type: String): SliceOptions = copy(
    printSequence = if (type == "smooth") "by layer" else printSequence,
    wipeTowerEnabled = wipeTowerEnabled || type == "smooth",
    gcodeSettings = gcodeSettings.copy(timelapseType = type),
)

private fun BuildPlateType.labelResource(): Int = when (this) {
    BuildPlateType.TEXTURED_PEI -> R.string.textured_pei_plate
    BuildPlateType.HIGH_TEMP -> R.string.high_temp_plate
    BuildPlateType.ENGINEERING -> R.string.engineering_plate
    BuildPlateType.COOL -> R.string.cool_plate
    BuildPlateType.TEXTURED_COOL -> R.string.textured_cool_plate
    BuildPlateType.SUPER_TACK -> R.string.super_tack_plate
    BuildPlateType.GRAPHIC_EFFECT -> R.string.graphic_effect_plate
}

internal data class ProfileEditSession(
    val opening: SliceOptions,
    val working: SliceOptions = opening,
) {
    val isDirty: Boolean get() = working != opening

    fun update(options: SliceOptions): ProfileEditSession = copy(working = options)

    fun revert(): ProfileEditSession = copy(working = opening)

    fun applied(): ProfileEditSession = copy(opening = working)
}

private data class ProfileEditorState(
    val kind: ProfileSettingsKind,
    val session: ProfileEditSession,
)

internal enum class SlicingSettingsSection(val titleResource: Int) {
    QUALITY(R.string.quality),
    STRENGTH(R.string.strength),
    SPEED(R.string.speed),
    SUPPORT(R.string.supports),
    OTHERS(R.string.others),
}

@Composable
internal fun ProfileSettings(
    options: SliceOptions,
    catalog: ProfileCatalog,
    recents: ProfileRecents,
    enabled: Boolean,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSavePrinter: (String, SliceOptions) -> Unit,
    onSaveFilament: (String, SliceOptions, Int) -> Unit,
    onSaveSlicing: (String, SliceOptions) -> Unit,
) {
    var editor by remember { mutableStateOf<ProfileEditorState?>(null) }
    var expanded by rememberSaveable { mutableStateOf(true) }

    fun open(kind: ProfileSettingsKind) {
        editor = ProfileEditorState(kind, ProfileEditSession(options))
    }

    fun updateEditor(options: SliceOptions) {
        editor = editor?.let { it.copy(session = it.session.update(options)) }
    }

    fun revertEditor() {
        editor = editor?.let { it.copy(session = it.session.revert()) }
    }

    fun applyEditor() {
        editor = editor?.let {
            onOptionsChanged(it.session.working)
            it.copy(session = it.session.applied())
        }
    }

    val profileState = stringResource(
        if (expanded) R.string.expanded_state else R.string.collapsed_state,
    )
    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = profileState },
        color = Color.Transparent,
        contentColor = Color(0xFFF4F4EE),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.profiles),
                modifier = Modifier.weight(1f).semantics { heading() },
                fontWeight = FontWeight.Bold,
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
    }
    if (expanded) {
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
            onClick = { open(ProfileSettingsKind.PRINTER) },
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
        ProfileRow(
            title = stringResource(R.string.filament_profile),
            summary = if (options.resolvedFilamentSlots().size == 1) {
                stringResource(
                    R.string.filament_profile_summary,
                    profileLabel(options.filamentProfile),
                    options.nozzleTemp,
                    options.bedTemp,
                )
            } else {
                stringResource(
                    R.string.filament_slots_summary,
                    options.resolvedFilamentSlots().size,
                    options.printerProfile.extruderCount,
                )
            },
            enabled = enabled,
            onClick = { open(ProfileSettingsKind.FILAMENT) },
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
            onClick = { open(ProfileSettingsKind.SLICING) },
        )
    }

    val activeEditor = editor
    when (activeEditor?.kind) {
        ProfileSettingsKind.PRINTER -> PrinterSettingsSheet(
            options = activeEditor.session.working,
            profiles = catalog.printers,
            recentIds = recents.printerIds,
            onProfileSelected = { printer ->
                updateEditor(activeEditor.session.working.selectPrinter(printer, catalog))
            },
            onOptionsChanged = ::updateEditor,
            onSave = { name, staged ->
                onOptionsChanged(staged)
                onSavePrinter(name, staged)
            },
            dirty = activeEditor.session.isDirty,
            onRevert = ::revertEditor,
            onApply = ::applyEditor,
            onDismiss = { editor = null },
        )

        ProfileSettingsKind.FILAMENT -> FilamentSettingsSheet(
            options = activeEditor.session.working,
            recentIds = recents.filamentIds,
            profiles = catalog.filaments.filter {
                it == activeEditor.session.working.filamentProfile ||
                    it.compatiblePrinters.matchesPrinter(activeEditor.session.working.printerProfile)
            },
            onOptionsChanged = ::updateEditor,
            onSave = { name, staged, slot ->
                onOptionsChanged(staged)
                onSaveFilament(name, staged, slot)
            },
            dirty = activeEditor.session.isDirty,
            onRevert = ::revertEditor,
            onApply = ::applyEditor,
            onDismiss = { editor = null },
        )

        ProfileSettingsKind.SLICING -> SlicingSettingsSheet(
            options = activeEditor.session.working,
            recentIds = recents.slicingIds,
            profiles = catalog.slicing.filter {
                it == activeEditor.session.working.quality ||
                    (
                        abs(it.nozzleDiameter - activeEditor.session.working.nozzleDiameter) < 0.05f &&
                            it.compatiblePrinters.matchesPrinter(activeEditor.session.working.printerProfile)
                        )
            },
            onOptionsChanged = ::updateEditor,
            onSave = { name, staged ->
                onOptionsChanged(staged)
                onSaveSlicing(name, staged)
            },
            dirty = activeEditor.session.isDirty,
            onRevert = ::revertEditor,
            onApply = ::applyEditor,
            onDismiss = { editor = null },
        )

        null -> Unit
    }
}

@Composable
private fun ProfileRow(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
    val editDetailsLabel = stringResource(R.string.edit_details)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = enabled,
                onClickLabel = editDetailsLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(summary, color = Color(0xFFC8C9C2), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun PrinterSettingsSheet(
    options: SliceOptions,
    profiles: List<PrinterProfile>,
    recentIds: List<String>,
    onProfileSelected: (PrinterProfile) -> Unit,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String, SliceOptions) -> Unit,
    dirty: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var profilesOpen by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }
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
                    bedPolygon = scaledBedPolygon(
                        options.bedPolygon,
                        options.bedSizeX,
                        options.bedSizeY,
                        width,
                        options.bedSizeY,
                    ),
                    bedExcludeArea = scaledBedExcludeArea(
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
                    bedPolygon = scaledBedPolygon(
                        options.bedPolygon,
                        options.bedSizeX,
                        options.bedSizeY,
                        options.bedSizeX,
                        depth,
                    ),
                    bedExcludeArea = scaledBedExcludeArea(
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
            onOptionsChanged(
                options.copy(nozzleDiameter = it)
                    .selectQuality(QualityProfile.standardFor(it)),
            )
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
        valueText = if (options.printerProfile.nozzleHrc > 0) {
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
        valueText = stringResource(
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
        valueText = stringResource(
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
        valueText = stringResource(
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
        valueText = stringResource(
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
    SettingSlider(
        label = stringResource(R.string.extruder_count),
        valueText = options.printerProfile.extruderCount.toString(),
        value = options.printerProfile.extruderCount.toFloat(),
        range = 1f..MAX_FILAMENT_SLOTS.toFloat(),
        steps = MAX_FILAMENT_SLOTS - 2,
        onValueChange = {
            onOptionsChanged(
                options.updatePrinterRetraction(
                    options.printerProfile.copy(extruderCount = it.roundToInt()),
                ),
            )
        },
    )
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
            valueText = stringResource(
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
            valueText = stringResource(
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
            valueText = stringResource(
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
            valueText = stringResource(
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
        label = stringResource(R.string.supports_chamber_temperature_control),
        checked = options.printerProfile.supportsChamberTemperatureControl,
        onCheckedChange = {
            onOptionsChanged(
                options.copy(
                    printerProfile = options.printerProfile.copy(
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
        entries = listOf("marlin", "marlin2", "klipper"),
        selected = options.gcodeFlavor,
        optionLabel = {
            when (it) {
                "marlin2" -> "Marlin 2"
                "klipper" -> "Klipper"
                else -> "Marlin"
            }
        },
        onSelected = { onOptionsChanged(options.copy(gcodeFlavor = it)) },
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
                    printerProfile = options.printerProfile.copy(
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
                    printerProfile = options.printerProfile.copy(
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
                    printerProfile = options.printerProfile.copy(
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
                    printerProfile = options.printerProfile.copy(
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
                    printerProfile = options.printerProfile.copy(
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
            onOptionsChanged(options.copy(
                printerProfile = options.printerProfile.copy(machineLoadFilamentTime = value),
            ))
        },
    )
    DecimalSettingField(
        label = stringResource(R.string.machine_filament_unload_time),
        value = options.printerProfile.machineUnloadFilamentTime,
        maximum = 3_600f,
        suffix = stringResource(R.string.seconds_suffix),
        onValueChange = { value ->
            onOptionsChanged(options.copy(
                printerProfile = options.printerProfile.copy(machineUnloadFilamentTime = value),
            ))
        },
    )
    DecimalSettingField(
        label = stringResource(R.string.machine_tool_change_time),
        value = options.printerProfile.machineToolChangeTime,
        maximum = 3_600f,
        suffix = stringResource(R.string.seconds_suffix),
        onValueChange = { value ->
            onOptionsChanged(options.copy(
                printerProfile = options.printerProfile.copy(machineToolChangeTime = value),
            ))
        },
    )
    SettingsSwitch(
        label = stringResource(R.string.wait_for_tool_temperature),
        checked = options.printerProfile.toolChangeTemperatureWait,
        onCheckedChange = { enabled ->
            onOptionsChanged(options.copy(
                printerProfile = options.printerProfile.copy(toolChangeTemperatureWait = enabled),
            ))
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
        SecondaryScrollableTabRow(selectedTabIndex = activeExtruder) {
            repeat(options.printerProfile.extruderCount) { index ->
                Tab(
                    selected = index == activeExtruder,
                    onClick = { selectedExtruder = index },
                    text = { Text(stringResource(R.string.extruder_number, index + 1)) },
                )
            }
        }
    }
    QuantizedSettingSlider(
        label = stringResource(R.string.extruder_offset_x),
        valueText = stringResource(
            R.string.millimeters_value_precise,
            extruderOffsetsX[activeExtruder],
        ),
        value = extruderOffsetsX[activeExtruder],
        minimum = min(-100f, extruderOffsetsX[activeExtruder]),
        defaultMaximum = max(100f, extruderOffsetsX[activeExtruder]),
        increment = 0.1f,
        onValueChange = {
            val updated = extruderOffsetsX.toMutableList().apply {
                this[activeExtruder] = it
            }
            onOptionsChanged(
                options.copy(
                    printerProfile = options.printerProfile.copy(
                        extruderOffsetsX = updated,
                        extruderOffsetsY = extruderOffsetsY,
                    ),
                ),
            )
        },
    )
    QuantizedSettingSlider(
        label = stringResource(R.string.extruder_offset_y),
        valueText = stringResource(
            R.string.millimeters_value_precise,
            extruderOffsetsY[activeExtruder],
        ),
        value = extruderOffsetsY[activeExtruder],
        minimum = min(-100f, extruderOffsetsY[activeExtruder]),
        defaultMaximum = max(100f, extruderOffsetsY[activeExtruder]),
        increment = 0.1f,
        onValueChange = {
            val updated = extruderOffsetsY.toMutableList().apply {
                this[activeExtruder] = it
            }
            onOptionsChanged(
                options.copy(
                    printerProfile = options.printerProfile.copy(
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
        valueText = stringResource(
            R.string.millimeters_value_precise,
            toolChangeRetractLengths[activeExtruder],
        ),
        value = toolChangeRetractLengths[activeExtruder],
        minimum = 0f,
        defaultMaximum = max(20f, toolChangeRetractLengths[activeExtruder]),
        increment = 0.1f,
        onValueChange = {
            val updated = toolChangeRetractLengths.toMutableList().apply {
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
        valueText = stringResource(
            R.string.millimeters_value_precise,
            toolChangeRetractRestartExtras[activeExtruder],
        ),
        value = toolChangeRetractRestartExtras[activeExtruder],
        minimum = min(-20f, toolChangeRetractRestartExtras[activeExtruder]),
        defaultMaximum = max(20f, toolChangeRetractRestartExtras[activeExtruder]),
        increment = 0.1f,
        onValueChange = {
            val updated = toolChangeRetractRestartExtras.toMutableList().apply {
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
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractSpeed = it.roundToInt().toFloat()),
            ))
        },
    )
    SettingSlider(
        label = stringResource(R.string.deretraction_speed),
        valueText = stringResource(R.string.print_speed_value, options.printerProfile.deretractSpeed),
        value = options.printerProfile.deretractSpeed,
        range = 0f..100f,
        steps = 99,
        onValueChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(deretractSpeed = it.roundToInt().toFloat()),
            ))
        },
    )
    SettingSlider(
        label = stringResource(R.string.retraction_minimum_travel),
        valueText = stringResource(
            R.string.millimeters_value_precise, options.printerProfile.retractionMinimumTravel,
        ),
        value = options.printerProfile.retractionMinimumTravel,
        range = 0f..20f,
        steps = 199,
        onValueChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractionMinimumTravel = it),
            ))
        },
    )
    SettingsSwitch(
        label = stringResource(R.string.retract_when_changing_layer),
        checked = options.printerProfile.retractWhenChangingLayer,
        onCheckedChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractWhenChangingLayer = it),
            ))
        },
    )
    SettingsSwitch(
        label = stringResource(R.string.wipe_while_retracting),
        checked = options.printerProfile.wipeWhileRetracting,
        onCheckedChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(wipeWhileRetracting = it),
            ))
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
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractBeforeWipe = it.roundToInt().toFloat()),
            ))
        },
    )
    SettingSlider(
        label = stringResource(R.string.retract_restart_extra),
        valueText = stringResource(
            R.string.millimeters_value_precise, options.printerProfile.retractRestartExtra,
        ),
        value = options.printerProfile.retractRestartExtra,
        range = -2f..2f,
        steps = 79,
        onValueChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractRestartExtra = it),
            ))
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
        optionLabel = { stringResource(when (it) {
            "auto" -> R.string.z_hop_auto
            "normal" -> R.string.z_hop_normal
            "spiral" -> R.string.z_hop_spiral
            else -> R.string.z_hop_slope
        }) },
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
            val bounded = if (options.printerProfile.retractLiftBelow > 0f) {
                min(value, options.printerProfile.retractLiftBelow)
            } else value
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractLiftAbove = bounded),
            ))
        },
    )
    DecimalSettingField(
        label = stringResource(R.string.z_hop_end_height),
        value = options.printerProfile.retractLiftBelow,
        maximum = options.printerProfile.maxPrintHeight,
        suffix = "mm",
        onValueChange = { value ->
            val bounded = if (value == 0f) 0f else max(value, options.printerProfile.retractLiftAbove)
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractLiftBelow = bounded),
            ))
        },
    )
    SettingChoices(
        settingLabel = stringResource(R.string.z_hop_surfaces),
        entries = RETRACT_LIFT_ENFORCEMENTS.toList(),
        selected = options.printerProfile.retractLiftEnforce,
        optionLabel = { stringResource(when (it) {
            "top" -> R.string.z_hop_surface_top
            "bottom" -> R.string.z_hop_surface_bottom
            "top_bottom" -> R.string.z_hop_surface_top_bottom
            else -> R.string.z_hop_surface_all
        }) },
        onSelected = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(retractLiftEnforce = it),
            ))
        },
    )
    SettingSlider(
        label = stringResource(R.string.z_hop_slope_angle),
        valueText = stringResource(R.string.degrees_value, options.printerProfile.travelSlope),
        value = options.printerProfile.travelSlope,
        range = 1f..90f,
        steps = 88,
        onValueChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(travelSlope = it.roundToInt().toFloat()),
            ))
        },
    )
    SettingsSwitch(
        label = stringResource(R.string.z_hop_on_prime_tower),
        checked = options.printerProfile.zHopWhenPrime,
        onCheckedChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(zHopWhenPrime = it),
            ))
        },
    )
    SettingsSwitch(
        label = stringResource(R.string.firmware_retraction),
        checked = options.printerProfile.useFirmwareRetraction,
        onCheckedChange = {
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(
                    useFirmwareRetraction = it,
                    wipeWhileRetracting = if (it) false else options.printerProfile.wipeWhileRetracting,
                ),
            ))
        },
    )
    SettingChoices(
        settingLabel = stringResource(R.string.filament_cut_retraction_control),
        entries = listOf(0, 1, 2),
        selected = options.printerProfile.longRetractionWhenCutLevel,
        optionLabel = { level ->
            stringResource(when (level) {
                1 -> R.string.filament_cut_retraction_printer
                2 -> R.string.filament_cut_retraction_filament
                else -> R.string.filament_cut_retraction_disabled
            })
        },
        onSelected = { level ->
            onOptionsChanged(options.updatePrinterRetraction(
                options.printerProfile.copy(
                    longRetractionWhenCutLevel = level,
                    longRetractionWhenCut = if (level == 0) {
                        false
                    } else {
                        options.printerProfile.longRetractionWhenCut
                    },
                ),
            ))
        },
    )
    if ((options.printerProfile.longRetractionWhenCutLevel > 0 &&
            !options.printerProfile.useFirmwareRetraction) || settingsQuery.isNotBlank()
    ) {
        SettingsSwitch(
            label = stringResource(R.string.long_retraction_when_cut),
            checked = options.printerProfile.longRetractionWhenCut,
            onCheckedChange = { enabled ->
                onOptionsChanged(options.updatePrinterRetraction(
                    options.printerProfile.copy(longRetractionWhenCut = enabled),
                ))
            },
        )
        if (options.printerProfile.longRetractionWhenCut || settingsQuery.isNotBlank()) {
            SettingSlider(
                label = stringResource(R.string.retraction_distance_when_cut),
                valueText = stringResource(
                    R.string.millimeters_value_precise,
                    options.printerProfile.retractionDistanceWhenCut,
                ),
                value = options.printerProfile.retractionDistanceWhenCut,
                range = 10f..18f,
                steps = 79,
                onValueChange = { value ->
                    onOptionsChanged(options.updatePrinterRetraction(
                        options.printerProfile.copy(retractionDistanceWhenCut = value),
                    ))
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
        steps = (((max(200f, options.extruderClearanceRadius) - 0.5f) * 2f).roundToInt() - 1)
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
        steps = (((max(500f, options.extruderClearanceHeightToRod) - 0.5f) * 2f).roundToInt() - 1)
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
        steps = (((max(500f, options.extruderClearanceHeightToLid) - 0.5f) * 2f).roundToInt() - 1)
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
                    machineMotion = options.machineMotion.copy(
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
                    machineMotion = options.machineMotion.copy(
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
        label = stringResource(R.string.maximum_junction_deviation),
        valueText = stringResource(
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
            onDismiss = { profilesOpen = false },
        )
    }
}

@Composable
private fun FilamentSettingsSheet(
    options: SliceOptions,
    profiles: List<FilamentProfile>,
    recentIds: List<String>,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String, SliceOptions, Int) -> Unit,
    dirty: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSlot by remember { mutableStateOf(0) }
    var profilesOpen by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }
    val slots = options.resolvedFilamentSlots()
    LaunchedEffect(slots.size) {
        selectedSlot = selectedSlot.coerceIn(0, slots.lastIndex)
    }
    val activeProfile = slots.getOrElse(selectedSlot) { slots.last() }
    val activeBedTemperature = activeProfile.bedTemperature(options.buildPlate.type)
    val activeFirstLayerBedTemperature =
        activeProfile.firstLayerBedTemperature(options.buildPlate.type)
    val resolvedRetraction = activeProfile.resolveRetraction(options.printerProfile)
    val inheritsPrinterRetraction = activeProfile.retractLength == null &&
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
        if (slots.size < options.printerProfile.extruderCount.coerceIn(1, MAX_FILAMENT_SLOTS)) {
            OutlinedButton(
                onClick = {
                    val nextProfile = profiles.firstOrNull { candidate ->
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
            range = 170f..300f,
            steps = 129,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(nozzleTemp = it.roundToInt())))
            },
        )
        SettingSlider(
            label = stringResource(R.string.first_layer_nozzle_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.firstLayerNozzleTemp),
            value = activeProfile.firstLayerNozzleTemp.toFloat(),
            range = 170f..300f,
            steps = 129,
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(idleTemperature = value.roundToInt()),
                ))
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.build_plate),
            entries = BUILD_PLATE_TYPES,
            selected = options.buildPlate.type,
            optionLabel = { stringResource(it.labelResource()) },
            onSelected = { onOptionsChanged(options.selectBuildPlate(it)) },
        )
        SettingSlider(
            label = stringResource(R.string.bed_temperature),
            valueText = if (activeBedTemperature == 0) {
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
            valueText = if (activeFirstLayerBedTemperature == 0) {
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
            range = 0.8f..1.2f,
            steps = 39,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(flowRatio = it)))
            },
        )
        SettingSlider(
            label = stringResource(R.string.max_volumetric_speed),
            valueText = stringResource(R.string.volumetric_speed_value, activeProfile.maxVolumetricSpeed),
            value = activeProfile.maxVolumetricSpeed,
            range = 4f..40f,
            steps = 35,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(maxVolumetricSpeed = it.roundToInt().toFloat()),
                    ),
                )
            },
        )
        if (selectedSlot == 0) {
            SettingSlider(
                label = stringResource(R.string.filament_diameter),
                valueText = stringResource(
                    R.string.millimeters_value_precise,
                    activeProfile.diameter,
                ),
                value = activeProfile.diameter,
                range = 0.5f..4f,
                steps = 349,
                onValueChange = {
                    onOptionsChanged(
                        options.updateFilamentSlot(
                            selectedSlot,
                            activeProfile.copy(diameter = (it * 100f).roundToInt() / 100f),
                        ),
                    )
                },
            )
        }
        QuantizedSettingSlider(
            label = stringResource(R.string.filament_density),
            valueText = stringResource(
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
            valueText = if (activeProfile.requiredNozzleHrc > 0) {
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(softeningTemperature = value.roundToInt()),
                ))
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot,
                    activeProfile.copy(
                        nozzleTemperatureRangeLow = minimum,
                        nozzleTemperatureRangeHigh = max(
                            minimum,
                            activeProfile.nozzleTemperatureRangeHigh,
                        ),
                    ),
                ))
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot,
                    activeProfile.copy(
                        nozzleTemperatureRangeLow = min(
                            maximum,
                            activeProfile.nozzleTemperatureRangeLow,
                        ),
                        nozzleTemperatureRangeHigh = maximum,
                    ),
                ))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.filament_chamber_temperature_control),
            checked = activeProfile.chamberTemperatureControl,
            enabled = options.printerProfile.supportsChamberTemperatureControl,
            onCheckedChange = { enabled ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(chamberTemperatureControl = enabled),
                ))
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
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(chamberTemperature = value.roundToInt()),
                    ))
                },
            )
        }
        SettingsSwitch(
            label = stringResource(R.string.filament_air_filtration),
            checked = activeProfile.airFiltration,
            enabled = options.printerProfile.supportsAirFiltration,
            onCheckedChange = { enabled ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(airFiltration = enabled),
                ))
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
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(duringPrintExhaustFanSpeed = value.roundToInt()),
                    ))
                },
            )
            SettingSlider(
                label = stringResource(R.string.filament_exhaust_after_print),
                valueText = stringResource(R.string.percent_value, activeProfile.completePrintExhaustFanSpeed),
                value = activeProfile.completePrintExhaustFanSpeed.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = { value ->
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(completePrintExhaustFanSpeed = value.roundToInt()),
                    ))
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(loadingSpeed = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_loading_speed_start),
            value = activeProfile.loadingSpeedStart,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(loadingSpeedStart = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_unloading_speed),
            value = activeProfile.unloadingSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(unloadingSpeed = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_unloading_speed_start),
            value = activeProfile.unloadingSpeedStart,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(unloadingSpeedStart = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_toolchange_delay),
            value = activeProfile.toolchangeDelay,
            maximum = 1_000f,
            suffix = stringResource(R.string.seconds_suffix),
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(toolchangeDelay = value),
                ))
            },
        )
        SettingSlider(
            label = stringResource(R.string.filament_cooling_moves),
            valueText = activeProfile.coolingMoves.toString(),
            value = activeProfile.coolingMoves.toFloat(),
            range = 0f..20f,
            steps = 19,
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(coolingMoves = value.roundToInt()),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_cooling_initial_speed),
            value = activeProfile.coolingInitialSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(coolingInitialSpeed = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_cooling_final_speed),
            value = activeProfile.coolingFinalSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(coolingFinalSpeed = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_stamping_speed),
            value = activeProfile.stampingLoadingSpeed,
            maximum = 1_000f,
            suffix = "mm/s",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(stampingLoadingSpeed = value),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.filament_stamping_distance),
            value = activeProfile.stampingDistance,
            maximum = 1_000f,
            suffix = "mm",
            onValueChange = { value ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(stampingDistance = value),
                ))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.filament_multitool_ramming),
            checked = activeProfile.multitoolRamming,
            onCheckedChange = { enabled ->
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(multitoolRamming = enabled),
                ))
            },
        )
        if (activeProfile.multitoolRamming || settingsQuery.isNotBlank()) {
            DecimalSettingField(
                label = stringResource(R.string.filament_multitool_ramming_volume),
                value = activeProfile.multitoolRammingVolume,
                maximum = 1_000f,
                suffix = stringResource(R.string.cubic_millimeters_suffix),
                onValueChange = { value ->
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(multitoolRammingVolume = value),
                    ))
                },
            )
            DecimalSettingField(
                label = stringResource(R.string.filament_multitool_ramming_flow),
                value = activeProfile.multitoolRammingFlow,
                maximum = 1_000f,
                suffix = "mm³/s",
                onValueChange = { value ->
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(multitoolRammingFlow = value),
                    ))
                },
            )
        }
        SettingsGroupTitle(stringResource(R.string.retraction))
        SettingsSwitch(
            label = stringResource(R.string.use_printer_retraction_defaults),
            checked = inheritsPrinterRetraction,
            onCheckedChange = { inherit ->
                val updated = if (inherit) {
                    activeProfile.copy(
                        retractLength = null, retractSpeed = null, deretractSpeed = null,
                        retractionMinimumTravel = null, retractWhenChangingLayer = null,
                        wipeWhileRetracting = null, wipeDistance = null, retractBeforeWipe = null,
                        retractRestartExtra = null, zHop = null, zHopType = null,
                        retractLiftAbove = null, retractLiftBelow = null,
                        retractLiftEnforce = null,
                        longRetractionWhenCut = null, retractionDistanceWhenCut = null,
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(deretractSpeed = it.roundToInt().toFloat()),
                ))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retraction_minimum_travel),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.minimumTravel),
            value = resolvedRetraction.minimumTravel,
            range = 0f..20f,
            steps = 199,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractionMinimumTravel = it),
                ))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.retract_when_changing_layer),
            checked = resolvedRetraction.whenChangingLayer,
            onCheckedChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractWhenChangingLayer = it),
                ))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.wipe_while_retracting),
            checked = resolvedRetraction.wipe,
            onCheckedChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(wipeWhileRetracting = it),
                ))
            },
        )
        SettingSlider(
            label = stringResource(R.string.wipe_distance),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.wipeDistance),
            value = resolvedRetraction.wipeDistance,
            range = 0f..10f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(wipeDistance = it),
                ))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retract_before_wipe),
            valueText = stringResource(R.string.percent_value, resolvedRetraction.beforeWipe.roundToInt()),
            value = resolvedRetraction.beforeWipe,
            range = 0f..100f,
            steps = 99,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractBeforeWipe = it.roundToInt().toFloat()),
                ))
            },
        )
        SettingSlider(
            label = stringResource(R.string.retract_restart_extra),
            valueText = stringResource(R.string.millimeters_value_precise, resolvedRetraction.restartExtra),
            value = resolvedRetraction.restartExtra,
            range = -2f..2f,
            steps = 79,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractRestartExtra = it),
                ))
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
            optionLabel = { stringResource(when (it) {
                "auto" -> R.string.z_hop_auto
                "normal" -> R.string.z_hop_normal
                "spiral" -> R.string.z_hop_spiral
                else -> R.string.z_hop_slope
            }) },
            onSelected = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(zHopType = it),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.z_hop_start_height),
            value = resolvedRetraction.liftAbove,
            maximum = options.printerProfile.maxPrintHeight,
            suffix = "mm",
            onValueChange = { value ->
                val bounded = if (resolvedRetraction.liftBelow > 0f) {
                    min(value, resolvedRetraction.liftBelow)
                } else value
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractLiftAbove = bounded),
                ))
            },
        )
        DecimalSettingField(
            label = stringResource(R.string.z_hop_end_height),
            value = resolvedRetraction.liftBelow,
            maximum = options.printerProfile.maxPrintHeight,
            suffix = "mm",
            onValueChange = { value ->
                val bounded = if (value == 0f) 0f else max(value, resolvedRetraction.liftAbove)
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractLiftBelow = bounded),
                ))
            },
        )
        SettingChoices(
            settingLabel = stringResource(R.string.z_hop_surfaces),
            entries = RETRACT_LIFT_ENFORCEMENTS.toList(),
            selected = resolvedRetraction.liftEnforce,
            optionLabel = { stringResource(when (it) {
                "top" -> R.string.z_hop_surface_top
                "bottom" -> R.string.z_hop_surface_bottom
                "top_bottom" -> R.string.z_hop_surface_top_bottom
                else -> R.string.z_hop_surface_all
            }) },
            onSelected = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(retractLiftEnforce = it),
                ))
            },
        )
        if ((options.printerProfile.longRetractionWhenCutLevel == 2 &&
                !options.printerProfile.useFirmwareRetraction) || settingsQuery.isNotBlank()
        ) {
            SettingsSwitch(
                label = stringResource(R.string.long_retraction_when_cut),
                checked = resolvedRetraction.longRetractionWhenCut,
                onCheckedChange = { enabled ->
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(longRetractionWhenCut = enabled),
                    ))
                },
            )
            if (resolvedRetraction.longRetractionWhenCut || settingsQuery.isNotBlank()) {
                SettingSlider(
                    label = stringResource(R.string.retraction_distance_when_cut),
                    valueText = stringResource(
                        R.string.millimeters_value_precise,
                        resolvedRetraction.retractionDistanceWhenCut,
                    ),
                    value = resolvedRetraction.retractionDistanceWhenCut,
                    range = 10f..18f,
                    steps = 79,
                    onValueChange = { value ->
                        onOptionsChanged(options.updateFilamentSlot(
                            selectedSlot, activeProfile.copy(retractionDistanceWhenCut = value),
                        ))
                    },
                )
            }
        }
        SettingsGroupTitle(stringResource(R.string.cooling))
        SettingSlider(
            label = stringResource(R.string.auxiliary_part_cooling_fan),
            valueText = stringResource(
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
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(keepFanAlwaysOn = it),
                ))
            },
        )
        SettingsSwitch(
            label = stringResource(R.string.slow_down_for_cooling),
            checked = activeProfile.slowDownForLayerCooling,
            onCheckedChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(slowDownForLayerCooling = it),
                ))
            },
        )
        if (activeProfile.slowDownForLayerCooling || settingsQuery.isNotBlank()) {
            SettingsSwitch(
                label = stringResource(R.string.keep_outer_wall_speed),
                checked = activeProfile.dontSlowDownOuterWall,
                onCheckedChange = {
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(dontSlowDownOuterWall = it),
                    ))
                },
            )
        }
        SettingsSwitch(
            label = stringResource(R.string.overhang_bridge_cooling),
            checked = activeProfile.enableOverhangBridgeFan,
            onCheckedChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(enableOverhangBridgeFan = it),
                ))
            },
        )
        if (activeProfile.enableOverhangBridgeFan || settingsQuery.isNotBlank()) {
            SettingChoices(
                settingLabel = stringResource(R.string.overhang_fan_threshold),
                entries = OVERHANG_FAN_THRESHOLDS,
                selected = activeProfile.overhangFanThreshold,
                optionLabel = { it },
                onSelected = {
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(overhangFanThreshold = it),
                    ))
                },
            )
            SettingSlider(
                label = stringResource(R.string.overhang_fan_speed),
                valueText = stringResource(R.string.percent_value, activeProfile.overhangFanSpeed),
                value = activeProfile.overhangFanSpeed.toFloat(),
                range = 0f..100f,
                steps = 99,
                onValueChange = {
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(overhangFanSpeed = it.roundToInt()),
                    ))
                },
            )
            SettingSlider(
                label = stringResource(R.string.internal_bridge_fan_speed),
                valueText = if (activeProfile.internalBridgeFanSpeed < 0) {
                    stringResource(R.string.fan_override_disabled)
                } else {
                    stringResource(R.string.percent_value, activeProfile.internalBridgeFanSpeed)
                },
                value = activeProfile.internalBridgeFanSpeed.toFloat(),
                range = -1f..100f,
                steps = 100,
                onValueChange = {
                    onOptionsChanged(options.updateFilamentSlot(
                        selectedSlot, activeProfile.copy(internalBridgeFanSpeed = it.roundToInt()),
                    ))
                },
            )
        }
        SettingSlider(
            label = stringResource(R.string.support_interface_fan_speed),
            valueText = if (activeProfile.supportInterfaceFanSpeed < 0) {
                stringResource(R.string.fan_override_disabled)
            } else {
                stringResource(R.string.percent_value, activeProfile.supportInterfaceFanSpeed)
            },
            value = activeProfile.supportInterfaceFanSpeed.toFloat(),
            range = -1f..100f,
            steps = 100,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(
                    selectedSlot, activeProfile.copy(supportInterfaceFanSpeed = it.roundToInt()),
                ))
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
                            adaptivePressureAdvance = if (it) {
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
                                adaptivePressureAdvance = activeProfile.adaptivePressureAdvance.copy(
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
                                    adaptivePressureAdvance = activeProfile.adaptivePressureAdvance.copy(
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
                                    adaptivePressureAdvance = activeProfile.adaptivePressureAdvance.copy(
                                        overhangs = enabled,
                                    ),
                                ),
                            ),
                        )
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.adaptive_pressure_advance_bridge),
                    valueText = String.format(
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
                                    adaptivePressureAdvance = activeProfile.adaptivePressureAdvance.copy(
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
            entries = if (selectedSlot == 0) {
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
            onDismiss = { profilesOpen = false },
        )
    }
}

@Composable
private fun SlicingSettingsSheet(
    options: SliceOptions,
    profiles: List<QualityProfile>,
    recentIds: List<String>,
    onOptionsChanged: (SliceOptions) -> Unit,
    onSave: (String, SliceOptions) -> Unit,
    dirty: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedSection by remember { mutableStateOf(SlicingSettingsSection.QUALITY) }
    var profilesOpen by remember { mutableStateOf(false) }
    var settingsQuery by remember { mutableStateOf("") }
    SettingsSheet(
        title = stringResource(R.string.slicing_profile),
        onDismiss = onDismiss,
        scrollKey = selectedSection,
        dirty = dirty,
        onRevert = onRevert,
        onApply = onApply,
        settingQuery = settingsQuery,
        onSettingQueryChanged = { settingsQuery = it },
        header = {
            CurrentProfileButton(
                profile = profileLabel(options.quality),
                onClick = { profilesOpen = true },
            )
        },
    ) {
        val maximumLayerHeight = options.printerProfile.maxLayerHeight.coerceAtLeast(0.04f)
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
        val minimumPrintFlowRatio = minOf(0.8f, options.printFlowRatio)
        val maximumPrintFlowRatio = maxOf(1.2f, options.printFlowRatio)
        val printFlowRatioSteps = (
            (maximumPrintFlowRatio - minimumPrintFlowRatio) / 0.01f
        ).roundToInt().coerceAtLeast(2) - 1
        val maximumFeatureAcceleration = listOf(
            20_000f,
            options.defaultAcceleration,
            options.outerWallAcceleration,
            options.innerWallAcceleration,
            options.topSurfaceAcceleration,
            options.travelAcceleration,
            options.firstLayerAcceleration,
            options.firstLayerTravelAcceleration.takeUnless { options.firstLayerTravelAccelerationPercent } ?: 0f,
        ).maxOrNull() ?: 20_000f
        val featureAccelerationSteps = (maximumFeatureAcceleration / 100f).roundToInt().coerceAtLeast(2) - 1
        val maximumFeatureJerk = listOf(
            30f,
            options.defaultJerk,
            options.outerWallJerk,
            options.innerWallJerk,
            options.topSurfaceJerk,
            options.infillJerk,
            options.firstLayerJerk,
            options.travelJerk,
        ).maxOrNull() ?: 30f
        val featureJerkSteps = (maximumFeatureJerk / 0.5f).roundToInt().coerceAtLeast(2) - 1
        val maximumFilamentSlot = options.resolvedFilamentSlots().size.coerceIn(1, MAX_FILAMENT_SLOTS)
        val supportAvailability = options.supportSettingsAvailability()
        val isSearchingSettings = settingsQuery.isNotBlank()
        val minimumOrganicTipDiameter = minimumOrganicTreeTipDiameter(options.supportLineWidth)
        val minimumOrganicBranchDiameter = minimumOrganicTreeBranchDiameter(
            options.supportLineWidth,
            options.treeSupportTipDiameter,
        )
        val maximumOrganicTipDiameter = max(
            10f,
            max(options.treeSupportTipDiameter, minimumOrganicTipDiameter),
        )
        val maximumOrganicBranchDiameter = max(
            10f,
            max(options.treeSupportOrganicBranchDiameter, minimumOrganicBranchDiameter),
        )
        if (settingsQuery.isBlank()) {
            SlicingSettingsTabs(
                selected = selectedSection,
                onSelected = { selectedSection = it },
            )
        }
        val renderedSections = if (settingsQuery.isBlank()) {
            listOf(selectedSection)
        } else {
            SlicingSettingsSection.entries
        }
        renderedSections.forEach { renderedSection ->
            when (renderedSection) {
            SlicingSettingsSection.QUALITY -> {
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
                SettingChoices(
                    settingLabel = stringResource(R.string.wall_generator),
                    entries = listOf("arachne", "classic"),
                    selected = options.wallGenerator,
                    optionLabel = {
                        stringResource(
                            if (it == "classic") R.string.wall_generator_classic
                            else R.string.wall_generator_arachne,
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(wallGenerator = it)) },
                )
                if (options.wallGenerator == "arachne" || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.wall_transition_length),
                        valueText = stringResource(R.string.percent_value, options.wallTransitionLength.roundToInt()),
                        value = options.wallTransitionLength,
                        range = 0f..200f,
                        steps = 39,
                        onValueChange = { onOptionsChanged(options.copy(wallTransitionLength = it.roundToInt().toFloat())) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wall_transition_filter),
                        valueText = stringResource(R.string.percent_value, options.wallTransitionFilterDeviation.roundToInt()),
                        value = options.wallTransitionFilterDeviation,
                        range = 0f..100f,
                        steps = 19,
                        onValueChange = { onOptionsChanged(options.copy(wallTransitionFilterDeviation = it.roundToInt().toFloat())) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wall_transition_angle),
                        valueText = stringResource(R.string.degrees_value, options.wallTransitionAngle),
                        value = options.wallTransitionAngle,
                        range = 1f..59f,
                        steps = 57,
                        onValueChange = { onOptionsChanged(options.copy(wallTransitionAngle = it.roundToInt().toFloat())) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wall_distribution_count),
                        valueText = options.wallDistributionCount.toString(),
                        value = options.wallDistributionCount.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onValueChange = { onOptionsChanged(options.copy(wallDistributionCount = it.roundToInt())) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.minimum_feature_size),
                        valueText = stringResource(R.string.percent_value, options.minimumFeatureSize.roundToInt()),
                        value = options.minimumFeatureSize,
                        range = 0f..100f,
                        steps = 19,
                        onValueChange = { onOptionsChanged(options.copy(minimumFeatureSize = it.roundToInt().toFloat())) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.minimum_wall_width),
                        valueText = stringResource(
                            R.string.percent_value,
                            options.precision.minimumWallWidth.roundToInt(),
                        ),
                        value = options.precision.minimumWallWidth,
                        range = 0f..200f,
                        steps = 199,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        minimumWallWidth = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.first_layer_minimum_wall_width),
                        valueText = stringResource(
                            R.string.percent_value,
                            options.precision.firstLayerMinimumWallWidth.roundToInt(),
                        ),
                        value = options.precision.firstLayerMinimumWallWidth,
                        range = 0f..200f,
                        steps = 199,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        firstLayerMinimumWallWidth = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.minimum_wall_length),
                        valueText = stringResource(R.string.flow_ratio_value, options.minimumWallLengthFactor),
                        value = options.minimumWallLengthFactor,
                        range = 0f..2f,
                        steps = 39,
                        onValueChange = {
                            onOptionsChanged(options.copy(minimumWallLengthFactor = (it * 20f).roundToInt() / 20f))
                        },
                    )
                }
                SettingChoices(
                    settingLabel = stringResource(R.string.wall_order),
                    entries = listOf("inner-outer", "outer-inner", "inner-outer-inner"),
                    selected = options.wallSequence,
                    optionLabel = {
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
                SettingChoices(
                    settingLabel = stringResource(R.string.wall_direction),
                    entries = listOf("auto", "ccw", "cw"),
                    selected = options.wallDirection,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "ccw" -> R.string.wall_direction_counter_clockwise
                                "cw" -> R.string.wall_direction_clockwise
                                else -> R.string.wall_direction_auto
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(wallDirection = it)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.seam_position),
                    entries = listOf("aligned", "nearest", "back", "random"),
                    selected = options.seamPosition,
                    optionLabel = { enumLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(seamPosition = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.staggered_inner_seams),
                    checked = options.staggeredInnerSeams,
                    onCheckedChange = { onOptionsChanged(options.copy(staggeredInnerSeams = it)) },
                )
                LengthOrPercentSetting(
                    label = stringResource(R.string.seam_gap),
                    value = options.seamGap,
                    percent = options.seamGapPercent,
                    maximumAbsolute = 10f,
                    maximumPercent = 100f,
                    onValueChange = { onOptionsChanged(options.copy(seamGap = it)) },
                    onPercentChange = { selectedPercent, adjustedValue ->
                        onOptionsChanged(options.copy(seamGap = adjustedValue, seamGapPercent = selectedPercent))
                    },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.scarf_joint_seam),
                    entries = listOf("none", "external", "all"),
                    selected = options.scarfSeam.type,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "external" -> R.string.scarf_contour
                                "all" -> R.string.scarf_contour_and_hole
                                else -> R.string.scarf_none
                            },
                        )
                    },
                    onSelected = {
                        onOptionsChanged(options.copy(scarfSeam = options.scarfSeam.copy(type = it)))
                    },
                )
                if (options.scarfSeam.type != "none" || settingsQuery.isNotBlank()) {
                    SettingsSwitch(
                        label = stringResource(R.string.conditional_scarf_joint),
                        checked = options.scarfSeam.conditional,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(scarfSeam = options.scarfSeam.copy(conditional = it)),
                            )
                        },
                    )
                    if (options.scarfSeam.conditional || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.scarf_angle_threshold),
                            valueText = stringResource(
                                R.string.degrees_value,
                                options.scarfSeam.angleThreshold.toFloat(),
                            ),
                            value = options.scarfSeam.angleThreshold.toFloat(),
                            range = 0f..180f,
                            steps = 179,
                            enabled = options.brimWidth > 0f,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        scarfSeam = options.scarfSeam.copy(
                                            angleThreshold = it.roundToInt(),
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.scarf_overhang_threshold),
                            valueText = stringResource(
                                R.string.percent_value,
                                options.scarfSeam.overhangThreshold.roundToInt(),
                            ),
                            value = options.scarfSeam.overhangThreshold,
                            range = 0f..100f,
                            steps = 99,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        scarfSeam = options.scarfSeam.copy(
                                            overhangThreshold = it.roundToInt().toFloat(),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    OverhangSpeedSetting(
                        label = stringResource(R.string.scarf_joint_speed),
                        value = options.scarfSeam.speed,
                        percent = options.scarfSeam.speedPercent,
                        maximumAbsolute = 700f,
                        maximumPercent = 300f,
                        onValueChange = {
                            onOptionsChanged(options.copy(scarfSeam = options.scarfSeam.copy(speed = it)))
                        },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    scarfSeam = options.scarfSeam.copy(
                                        speed = adjustedValue,
                                        speedPercent = selectedPercent,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.scarf_joint_flow_ratio),
                        valueText = String.format(Locale.ROOT, "%.2f", options.scarfSeam.flowRatio),
                        value = options.scarfSeam.flowRatio,
                        range = 0f..2f,
                        steps = 199,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    scarfSeam = options.scarfSeam.copy(
                                        flowRatio = (it * 100f).roundToInt() / 100f,
                                    ),
                                ),
                            )
                        },
                    )
                    LengthOrPercentSetting(
                        label = stringResource(R.string.scarf_start_height),
                        value = options.scarfSeam.startHeight,
                        percent = options.scarfSeam.startHeightPercent,
                        maximumAbsolute = 10f,
                        maximumPercent = 100f,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(scarfSeam = options.scarfSeam.copy(startHeight = it)),
                            )
                        },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    scarfSeam = options.scarfSeam.copy(
                                        startHeight = adjustedValue,
                                        startHeightPercent = selectedPercent,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.scarf_entire_wall),
                        checked = options.scarfSeam.entireLoop,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(scarfSeam = options.scarfSeam.copy(entireLoop = it)),
                            )
                        },
                    )
                    if (!options.scarfSeam.entireLoop || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.scarf_length),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.scarfSeam.length,
                            ),
                            value = options.scarfSeam.length,
                            range = 0f..max(100f, options.scarfSeam.length),
                            steps = (max(100f, options.scarfSeam.length) * 2f)
                                .roundToInt().coerceAtLeast(2) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        scarfSeam = options.scarfSeam.copy(
                                            length = (it * 2f).roundToInt() / 2f,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    SettingSlider(
                        label = stringResource(R.string.scarf_steps),
                        valueText = options.scarfSeam.steps.toString(),
                        value = options.scarfSeam.steps.toFloat(),
                        range = 1f..max(100f, options.scarfSeam.steps.toFloat()),
                        steps = max(100, options.scarfSeam.steps) - 2,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    scarfSeam = options.scarfSeam.copy(steps = it.roundToInt()),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.scarf_inner_walls),
                        checked = options.scarfSeam.innerWalls,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(scarfSeam = options.scarfSeam.copy(innerWalls = it)),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.wipe_before_external_loop),
                    checked = options.wipeBeforeExternalLoop,
                    onCheckedChange = { onOptionsChanged(options.copy(wipeBeforeExternalLoop = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.wipe_on_loops),
                    checked = options.wipeOnLoops,
                    onCheckedChange = { onOptionsChanged(options.copy(wipeOnLoops = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.role_based_wipe_speed),
                    checked = options.roleBasedWipeSpeed,
                    onCheckedChange = { onOptionsChanged(options.copy(roleBasedWipeSpeed = it)) },
                )
                if (!options.roleBasedWipeSpeed || settingsQuery.isNotBlank()) {
                    OverhangSpeedSetting(
                        label = stringResource(R.string.wipe_speed),
                        value = options.wipeSpeed,
                        percent = options.wipeSpeedPercent,
                        maximumAbsolute = 700f,
                        maximumPercent = 300f,
                        onValueChange = { onOptionsChanged(options.copy(wipeSpeed = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(options.copy(wipeSpeed = adjustedValue, wipeSpeedPercent = selectedPercent))
                        },
                    )
                }
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
                    label = stringResource(R.string.make_overhangs_printable),
                    checked = options.printableOverhangs.enabled,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                precision = options.precision.copy(
                                    printableOverhangs = options.printableOverhangs.copy(enabled = it),
                                ),
                            ),
                        )
                    },
                )
                if (options.printableOverhangs.enabled || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.maximum_overhang_angle),
                        valueText = stringResource(
                            R.string.degrees_value,
                            options.printableOverhangs.maximumAngle,
                        ),
                        value = options.printableOverhangs.maximumAngle,
                        range = 0f..90f,
                        steps = 89,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        printableOverhangs = options.printableOverhangs.copy(
                                            maximumAngle = it.roundToInt().toFloat(),
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                    val maximumHoleArea = max(1_000f, options.printableOverhangs.holeArea)
                    SettingSlider(
                        label = stringResource(R.string.overhang_base_hole_area),
                        valueText = stringResource(
                            R.string.square_millimeters_value,
                            options.printableOverhangs.holeArea,
                        ),
                        value = options.printableOverhangs.holeArea,
                        range = 0f..maximumHoleArea,
                        steps = 999,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        printableOverhangs = options.printableOverhangs.copy(
                                            holeArea = it.roundToInt().toFloat(),
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.one_wall_on_top),
                    checked = options.onlyOneWallOnTop,
                    onCheckedChange = { onOptionsChanged(options.copy(onlyOneWallOnTop = it)) },
                )
                if (options.onlyOneWallOnTop || settingsQuery.isNotBlank()) {
                    LengthOrPercentSetting(
                        label = stringResource(R.string.one_wall_threshold),
                        value = options.minWidthTopSurface,
                        percent = options.minWidthTopSurfacePercent,
                        maximumAbsolute = if (options.minWidthTopSurfacePercent) {
                            15f
                        } else {
                            max(15f, options.minWidthTopSurface)
                        },
                        maximumPercent = if (options.minWidthTopSurfacePercent) {
                            max(1_500f, options.minWidthTopSurface)
                        } else {
                            1_500f
                        },
                        onValueChange = { onOptionsChanged(options.copy(minWidthTopSurface = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    minWidthTopSurface = adjustedValue,
                                    minWidthTopSurfacePercent = selectedPercent,
                                ),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.one_wall_on_first_layer),
                    checked = options.onlyOneWallFirstLayer,
                    onCheckedChange = { onOptionsChanged(options.copy(onlyOneWallFirstLayer = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.extra_perimeters_on_overhangs),
                    checked = options.extraPerimetersOnOverhangs,
                    onCheckedChange = { onOptionsChanged(options.copy(extraPerimetersOnOverhangs = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.overhang_reversal),
                    checked = options.overhangReverse,
                    onCheckedChange = { onOptionsChanged(options.copy(overhangReverse = it)) },
                )
                if (options.overhangReverse || settingsQuery.isNotBlank()) {
                    SettingsSwitch(
                        label = stringResource(R.string.reverse_internal_only),
                        checked = options.overhangReverseInternalOnly,
                        onCheckedChange = { onOptionsChanged(options.copy(overhangReverseInternalOnly = it)) },
                    )
                    LengthOrPercentSetting(
                        label = stringResource(R.string.reverse_threshold),
                        value = options.overhangReverseThreshold,
                        percent = options.overhangReverseThresholdPercent,
                        maximumAbsolute = if (options.overhangReverseThresholdPercent) {
                            20f
                        } else {
                            max(20f, options.overhangReverseThreshold)
                        },
                        maximumPercent = if (options.overhangReverseThresholdPercent) {
                            max(2_000f, options.overhangReverseThreshold)
                        } else {
                            2_000f
                        },
                        onValueChange = { onOptionsChanged(options.copy(overhangReverseThreshold = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    overhangReverseThreshold = adjustedValue,
                                    overhangReverseThresholdPercent = selectedPercent,
                                ),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.alternate_extra_wall),
                    checked = options.alternateExtraWall,
                    onCheckedChange = { onOptionsChanged(options.copy(alternateExtraWall = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.precise_outer_walls),
                    checked = options.preciseOuterWalls,
                    onCheckedChange = { onOptionsChanged(options.copy(preciseOuterWalls = it)) },
                )
                SettingSlider(
                    label = stringResource(R.string.path_resolution),
                    valueText = stringResource(R.string.millimeters_value_fine, options.resolution),
                    value = options.resolution,
                    range = 0.001f..max(0.1f, options.resolution),
                    steps = ((max(0.1f, options.resolution) - 0.001f) / 0.001f).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(resolution = (it * 1_000f).roundToInt() / 1_000f)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.slicing_mode),
                    entries = listOf("regular", "even_odd", "close_holes"),
                    selected = options.precision.mode,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "even_odd" -> R.string.slicing_mode_even_odd
                                "close_holes" -> R.string.slicing_mode_close_holes
                                else -> R.string.slicing_mode_regular
                            },
                        )
                    },
                    onSelected = {
                        onOptionsChanged(
                            options.copy(precision = options.precision.copy(mode = it)),
                        )
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.slice_gap_closing_radius),
                    valueText = stringResource(
                        R.string.millimeters_value_fine,
                        options.precision.closingRadius,
                    ),
                    value = options.precision.closingRadius,
                    range = 0f..max(1f, options.precision.closingRadius),
                    steps = 999,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                precision = options.precision.copy(
                                    closingRadius = (it * 1_000f).roundToInt() / 1_000f,
                                ),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.precise_z_height),
                    checked = options.precision.preciseZHeight,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                precision = options.precision.copy(preciseZHeight = it),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.arc_fitting),
                    checked = options.gcodeSettings.arcFitting &&
                        options.quality.extrusionRateSmoothing.maximumSlope <= 0f,
                    enabled = options.quality.extrusionRateSmoothing.maximumSlope <= 0f,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(arcFitting = it),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.hole_to_polyhole),
                    checked = options.precision.polyholes.enabled,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                precision = options.precision.copy(
                                    polyholes = options.precision.polyholes.copy(enabled = it),
                                ),
                            ),
                        )
                    },
                )
                if (options.precision.polyholes.enabled || settingsQuery.isNotBlank()) {
                    LengthOrPercentSetting(
                        label = stringResource(R.string.hole_to_polyhole_threshold),
                        value = options.precision.polyholes.detectionMargin,
                        percent = options.precision.polyholes.detectionMarginPercent,
                        maximumAbsolute = 10f,
                        maximumPercent = 10f,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        polyholes = options.precision.polyholes.copy(detectionMargin = it),
                                    ),
                                ),
                            )
                        },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        polyholes = options.precision.polyholes.copy(
                                            detectionMargin = adjustedValue,
                                            detectionMarginPercent = selectedPercent,
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.hole_to_polyhole_twisted),
                        checked = options.precision.polyholes.twist,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    precision = options.precision.copy(
                                        polyholes = options.precision.polyholes.copy(twist = it),
                                    ),
                                ),
                            )
                        },
                    )
                }
                SettingChoices(
                    settingLabel = stringResource(R.string.ensure_vertical_shell_thickness),
                    entries = listOf("none", "ensure_critical_only", "ensure_moderate", "ensure_all"),
                    selected = options.ensureVerticalShellThickness,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "none" -> R.string.vertical_shell_none
                                "ensure_critical_only" -> R.string.vertical_shell_critical
                                "ensure_moderate" -> R.string.vertical_shell_moderate
                                else -> R.string.vertical_shell_all
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(ensureVerticalShellThickness = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.detect_narrow_internal_solid_infill),
                    checked = options.detectNarrowInternalSolidInfill,
                    onCheckedChange = { onOptionsChanged(options.copy(detectNarrowInternalSolidInfill = it)) },
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
                SettingSlider(
                    label = stringResource(R.string.top_surface_density),
                    valueText = stringResource(R.string.percent_value, options.quality.surfaceDensity.topPercent.roundToInt()),
                    value = options.quality.surfaceDensity.topPercent,
                    range = 0f..100f,
                    steps = 99,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                quality = options.quality.copy(
                                    surfaceDensity = options.quality.surfaceDensity.copy(
                                        topPercent = it.roundToInt().toFloat(),
                                    ),
                                ),
                            ),
                        )
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.bottom_surface_density),
                    valueText = stringResource(R.string.percent_value, options.quality.surfaceDensity.bottomPercent.roundToInt()),
                    value = options.quality.surfaceDensity.bottomPercent,
                    range = 10f..100f,
                    steps = 89,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                quality = options.quality.copy(
                                    surfaceDensity = options.quality.surfaceDensity.copy(
                                        bottomPercent = it.roundToInt().toFloat(),
                                    ),
                                ),
                            ),
                        )
                    },
                )
                SmallAreaFlowCompensationSettings(options, settingsQuery, onOptionsChanged)
                SettingsGroupTitle(stringResource(R.string.dimensional_accuracy))
                SettingSlider(
                    label = stringResource(R.string.xy_hole_compensation),
                    valueText = stringResource(R.string.millimeters_value_precise, options.xyHoleCompensation),
                    value = options.xyHoleCompensation,
                    range = -2f..2f,
                    steps = 399,
                    onValueChange = { onOptionsChanged(options.copy(xyHoleCompensation = (it * 100f).roundToInt() / 100f)) },
                )
                SettingSlider(
                    label = stringResource(R.string.xy_contour_compensation),
                    valueText = stringResource(R.string.millimeters_value_precise, options.xyContourCompensation),
                    value = options.xyContourCompensation,
                    range = -2f..2f,
                    steps = 399,
                    onValueChange = { onOptionsChanged(options.copy(xyContourCompensation = (it * 100f).roundToInt() / 100f)) },
                )
                SettingSlider(
                    label = stringResource(R.string.elephant_foot_compensation),
                    valueText = stringResource(R.string.millimeters_value_precise, options.elephantFootCompensation),
                    value = options.elephantFootCompensation,
                    range = 0f..max(1f, options.elephantFootCompensation),
                    steps = (max(1f, options.elephantFootCompensation) * 100f).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(elephantFootCompensation = (it * 100f).roundToInt() / 100f)) },
                )
                if (options.elephantFootCompensation > 0f || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.elephant_foot_layers),
                        valueText = options.elephantFootCompensationLayers.toString(),
                        value = options.elephantFootCompensationLayers.toFloat(),
                        range = 1f..max(10f, options.elephantFootCompensationLayers.toFloat()),
                        steps = max(10, options.elephantFootCompensationLayers) - 2,
                        onValueChange = { onOptionsChanged(options.copy(elephantFootCompensationLayers = it.roundToInt())) },
                    )
                }
            }

            SlicingSettingsSection.STRENGTH -> {
                SettingsGroupTitle(stringResource(R.string.infill))
                if (maximumFilamentSlot > 1) {
                    SettingsSwitch(
                        label = stringResource(R.string.override_infill_filament),
                        checked = options.featureFilaments.infillOverrideEnabled,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    featureFilaments = options.featureFilaments.copy(
                                        infillOverrideEnabled = it,
                                    ),
                                ),
                            )
                        },
                    )
                    if (options.featureFilaments.infillOverrideEnabled || settingsQuery.isNotBlank()) {
                        FilamentSlotSetting(
                            label = stringResource(R.string.sparse_infill_filament),
                            filaments = options.resolvedFilamentSlots(),
                            selectedSlot = options.featureFilaments.sparseInfillFilament,
                            onSelected = {
                                onOptionsChanged(
                                    options.copy(
                                        featureFilaments = options.featureFilaments.copy(
                                            sparseInfillFilament = it,
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.base_infill_first_layers),
                            valueText = options.featureFilaments.baseFirstLayers.toString(),
                            value = options.featureFilaments.baseFirstLayers.toFloat(),
                            range = 0f..max(100f, options.featureFilaments.baseFirstLayers.toFloat()),
                            steps = max(100, options.featureFilaments.baseFirstLayers) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        featureFilaments = options.featureFilaments.copy(
                                            baseFirstLayers = it.roundToInt(),
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.base_infill_last_layers),
                            valueText = options.featureFilaments.baseLastLayers.toString(),
                            value = options.featureFilaments.baseLastLayers.toFloat(),
                            range = 0f..max(100f, options.featureFilaments.baseLastLayers.toFloat()),
                            steps = max(100, options.featureFilaments.baseLastLayers) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        featureFilaments = options.featureFilaments.copy(
                                            baseLastLayers = it.roundToInt(),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
                SettingChoices(
                    settingLabel = stringResource(R.string.sparse_infill_pattern),
                    entries = SPARSE_INFILL_PATTERNS,
                    selected = options.fillPattern,
                    optionLabel = { fillPatternLabel(it) },
                    onSelected = { pattern ->
                        onOptionsChanged(
                            options.copy(
                                fillPattern = pattern,
                                quality = options.quality.copy(
                                    fillMultiline = fillMultilineForPattern(
                                        pattern,
                                        options.quality.fillMultiline,
                                    ),
                                ),
                            ),
                        )
                    },
                )
                if (
                    options.fillPattern in MULTILINE_INFILL_PATTERNS ||
                    settingsQuery.isNotBlank()
                ) {
                    SettingSlider(
                        label = stringResource(R.string.fill_multiline),
                        valueText = stringResource(
                            R.string.fill_multiline_value,
                            options.quality.fillMultiline,
                        ),
                        value = options.quality.fillMultiline.toFloat(),
                        range = 1f..5f,
                        steps = 3,
                        enabled = options.fillPattern in MULTILINE_INFILL_PATTERNS,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(
                                        fillMultiline = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
                    )
                }
                if (
                    options.fillPattern in setOf("lateral-honeycomb", "lateral-lattice") ||
                    settingsQuery.isNotBlank()
                ) {
                    LateralInfillGeometrySettings(
                        settings = options.quality.lateralInfill,
                        onSettingsChanged = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(lateralInfill = it),
                                ),
                            )
                        },
                    )
                }
                if (options.fillPattern == "lockedzag" || settingsQuery.isNotBlank()) {
                    LockedZagInfillSettings(
                        quality = options.quality,
                        onQualityChanged = { onOptionsChanged(options.copy(quality = it)) },
                    )
                }
                if (options.fillPattern in setOf("crosszag", "lockedzag") || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.infill_shift_step),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.quality.infillShiftStep,
                        ),
                        value = options.quality.infillShiftStep,
                        range = 0f..10f,
                        steps = 99,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(
                                        infillShiftStep = (it * 10f).roundToInt() / 10f,
                                    ),
                                ),
                            )
                        },
                    )
                }
                if (
                    options.fillPattern in setOf("zigzag", "crosszag", "lockedzag") ||
                    settingsQuery.isNotBlank()
                ) {
                    SettingsSwitch(
                        label = stringResource(R.string.symmetric_infill_y_axis),
                        checked = options.quality.symmetricInfillYAxis,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(symmetricInfillYAxis = it),
                                ),
                            )
                        },
                    )
                }
                SettingChoices(
                    settingLabel = stringResource(R.string.top_surface_pattern),
                    entries = listOf("monotonicline", "monotonic", "rectilinear", "concentric"),
                    selected = options.topSurfacePattern,
                    optionLabel = { fillPatternLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(topSurfacePattern = it)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.bottom_surface_pattern),
                    entries = listOf("monotonic", "monotonicline", "rectilinear", "concentric"),
                    selected = options.bottomSurfacePattern,
                    optionLabel = { fillPatternLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(bottomSurfacePattern = it)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.internal_solid_pattern),
                    entries = listOf("monotonic", "monotonicline", "rectilinear", "grid"),
                    selected = options.internalSolidInfillPattern,
                    optionLabel = { fillPatternLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(internalSolidInfillPattern = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.infill_first),
                    checked = options.infillFirst,
                    onCheckedChange = { onOptionsChanged(options.copy(infillFirst = it)) },
                )
                SettingSlider(
                    label = stringResource(R.string.infill_wall_overlap),
                    valueText = stringResource(R.string.percent_value, options.infillWallOverlap.roundToInt()),
                    value = options.infillWallOverlap,
                    range = 0f..100f,
                    steps = 99,
                    onValueChange = { onOptionsChanged(options.copy(infillWallOverlap = it.roundToInt().toFloat())) },
                )
                SettingSlider(
                    label = stringResource(R.string.solid_infill_wall_overlap),
                    valueText = stringResource(R.string.percent_value, options.topBottomInfillWallOverlap.roundToInt()),
                    value = options.topBottomInfillWallOverlap,
                    range = 0f..100f,
                    steps = 99,
                    onValueChange = { onOptionsChanged(options.copy(topBottomInfillWallOverlap = it.roundToInt().toFloat())) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.combine_infill_layers),
                    checked = options.infillCombination,
                    onCheckedChange = { onOptionsChanged(options.copy(infillCombination = it)) },
                )
                if (options.infillCombination || settingsQuery.isNotBlank()) {
                    LengthOrPercentSetting(
                        label = stringResource(R.string.combined_infill_max_height),
                        value = options.infillCombinationMaxLayerHeight,
                        percent = options.infillCombinationMaxLayerHeightPercent,
                        onValueChange = { onOptionsChanged(options.copy(infillCombinationMaxLayerHeight = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    infillCombinationMaxLayerHeight = adjustedValue,
                                    infillCombinationMaxLayerHeightPercent = selectedPercent,
                                ),
                            )
                        },
                    )
                }
                RotationTemplateSetting(
                    label = stringResource(R.string.sparse_infill_rotation_template),
                    value = options.quality.sparseInfillRotationTemplate,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(quality = options.quality.copy(sparseInfillRotationTemplate = it)),
                        )
                    },
                )
                if (options.quality.sparseInfillRotationTemplate.isBlank() || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.sparse_infill_direction),
                        valueText = stringResource(R.string.degrees_value, options.infillDirection),
                        value = options.infillDirection,
                        range = 0f..360f,
                        steps = 359,
                        onValueChange = { onOptionsChanged(options.copy(infillDirection = it.roundToInt().toFloat())) },
                    )
                }
                RotationTemplateSetting(
                    label = stringResource(R.string.solid_infill_rotation_template),
                    value = options.quality.solidInfillRotationTemplate,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(quality = options.quality.copy(solidInfillRotationTemplate = it)),
                        )
                    },
                )
                if (options.quality.solidInfillRotationTemplate.isBlank() || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.solid_infill_direction),
                        valueText = stringResource(R.string.degrees_value, options.solidInfillDirection),
                        value = options.solidInfillDirection,
                        range = 0f..360f,
                        steps = 359,
                        onValueChange = { onOptionsChanged(options.copy(solidInfillDirection = it.roundToInt().toFloat())) },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.align_infill_to_model),
                    checked = options.alignInfillDirectionToModel,
                    onCheckedChange = { onOptionsChanged(options.copy(alignInfillDirectionToModel = it)) },
                )
                SettingSlider(
                    label = stringResource(R.string.minimum_sparse_infill_area),
                    valueText = stringResource(R.string.square_millimeters_value, options.minimumSparseInfillArea),
                    value = options.minimumSparseInfillArea,
                    range = 0f..max(100f, options.minimumSparseInfillArea),
                    steps = max(100f, options.minimumSparseInfillArea).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(minimumSparseInfillArea = it.roundToInt().toFloat())) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.gap_fill_target),
                    entries = listOf("everywhere", "topbottom", "nowhere"),
                    selected = options.gapFillTarget,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "everywhere" -> R.string.gap_fill_everywhere
                                "topbottom" -> R.string.gap_fill_top_bottom
                                else -> R.string.gap_fill_nowhere
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(gapFillTarget = it)) },
                )
                SettingSlider(
                    label = stringResource(R.string.filter_tiny_gaps),
                    valueText = stringResource(R.string.millimeters_value_precise, options.filterOutGapFill),
                    value = options.filterOutGapFill,
                    range = 0f..max(10f, options.filterOutGapFill),
                    steps = (max(10f, options.filterOutGapFill) * 10f).roundToInt().coerceIn(2, 10_000) - 1,
                    onValueChange = { onOptionsChanged(options.copy(filterOutGapFill = (it * 10f).roundToInt() / 10f)) },
                )
                LengthOrPercentSetting(
                    label = stringResource(R.string.infill_anchor),
                    value = options.infillAnchor,
                    percent = options.infillAnchorPercent,
                    maximumAbsolute = 1_000f,
                    maximumPercent = 1_000f,
                    onValueChange = { onOptionsChanged(options.copy(infillAnchor = it)) },
                    onPercentChange = { selectedPercent, adjustedValue ->
                        onOptionsChanged(options.copy(infillAnchor = adjustedValue, infillAnchorPercent = selectedPercent))
                    },
                )
                LengthOrPercentSetting(
                    label = stringResource(R.string.infill_anchor_max),
                    value = options.infillAnchorMax,
                    percent = options.infillAnchorMaxPercent,
                    maximumAbsolute = 1_000f,
                    maximumPercent = 1_000f,
                    onValueChange = { onOptionsChanged(options.copy(infillAnchorMax = it)) },
                    onPercentChange = { selectedPercent, adjustedValue ->
                        onOptionsChanged(options.copy(infillAnchorMax = adjustedValue, infillAnchorMaxPercent = selectedPercent))
                    },
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
            }

            SlicingSettingsSection.SPEED -> {
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
                OverhangSpeedSetting(
                    label = stringResource(R.string.small_perimeter_speed),
                    value = options.smallPerimeterSpeed,
                    percent = options.smallPerimeterSpeedPercent,
                    maximumAbsolute = maximumFeatureSpeed,
                    maximumPercent = 300f,
                    onValueChange = { onOptionsChanged(options.copy(smallPerimeterSpeed = it)) },
                    onPercentChange = { selectedPercent, adjustedValue ->
                        onOptionsChanged(
                            options.copy(
                                smallPerimeterSpeed = adjustedValue,
                                smallPerimeterSpeedPercent = selectedPercent,
                            ),
                        )
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.small_perimeter_threshold),
                    valueText = stringResource(R.string.millimeters_value_precise, options.smallPerimeterThreshold),
                    value = options.smallPerimeterThreshold,
                    range = 0f..max(20f, options.smallPerimeterThreshold),
                    steps = (max(20f, options.smallPerimeterThreshold) * 2f).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(smallPerimeterThreshold = (it * 2f).roundToInt() / 2f)) },
                )
                SettingSlider(
                    label = stringResource(R.string.travel_speed),
                    valueText = stringResource(R.string.print_speed_value, options.travelSpeed),
                    value = options.travelSpeed,
                    range = 10f..max(700f, options.travelSpeed),
                    steps = ((max(700f, options.travelSpeed) - 10f) / 5f).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(travelSpeed = (it / 5f).roundToInt() * 5f)) },
                )
                val maximumVerticalTravelSpeed = max(500f, options.travelSpeedZ)
                SettingSlider(
                    label = stringResource(R.string.vertical_travel_speed),
                    valueText = if (options.travelSpeedZ == 0f) {
                        stringResource(R.string.use_travel_speed)
                    } else {
                        stringResource(R.string.print_speed_value, options.travelSpeedZ)
                    },
                    value = options.travelSpeedZ,
                    range = 0f..maximumVerticalTravelSpeed,
                    steps = maximumVerticalTravelSpeed.roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                quality = options.quality.copy(
                                    travelSpeedZ = it.roundToInt().toFloat(),
                                ),
                            ),
                        )
                    },
                )
                OverhangSpeedSetting(
                    label = stringResource(R.string.initial_layer_travel_speed),
                    value = options.gcodeSettings.initialLayerTravelSpeed,
                    percent = options.gcodeSettings.initialLayerTravelSpeedPercent,
                    maximumAbsolute = max(700f, options.gcodeSettings.initialLayerTravelSpeed),
                    maximumPercent = max(300f, options.gcodeSettings.initialLayerTravelSpeed),
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(initialLayerTravelSpeed = it),
                            ),
                        )
                    },
                    onPercentChange = { selectedPercent, adjustedValue ->
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(
                                    initialLayerTravelSpeed = adjustedValue,
                                    initialLayerTravelSpeedPercent = selectedPercent,
                                ),
                            ),
                        )
                    },
                )
                val maximumSlowLayers = max(20, options.gcodeSettings.slowDownLayers)
                SettingSlider(
                    label = stringResource(R.string.number_of_slow_layers),
                    valueText = options.gcodeSettings.slowDownLayers.toString(),
                    value = options.gcodeSettings.slowDownLayers.toFloat(),
                    range = 0f..maximumSlowLayers.toFloat(),
                    steps = (maximumSlowLayers - 1).coerceAtLeast(0),
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(
                                    slowDownLayers = it.roundToInt(),
                                ),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.acceleration_smoothing),
                    checked = options.gcodeSettings.accelToDecelEnabled,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(accelToDecelEnabled = it),
                            ),
                        )
                    },
                )
                if (options.gcodeSettings.accelToDecelEnabled || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.acceleration_smoothing_ratio),
                        valueText = stringResource(
                            R.string.percent_value,
                            options.gcodeSettings.accelToDecelFactor.roundToInt(),
                        ),
                        value = options.gcodeSettings.accelToDecelFactor,
                        range = 1f..100f,
                        steps = 98,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    gcodeSettings = options.gcodeSettings.copy(
                                        accelToDecelFactor = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                }
                SettingsGroupTitle(stringResource(R.string.extrusion_rate_smoothing))
                val extrusionRateSmoothing = options.quality.extrusionRateSmoothing
                val maximumExtrusionSlope = max(100f, extrusionRateSmoothing.maximumSlope)
                SettingSlider(
                    label = stringResource(R.string.extrusion_rate_smoothing),
                    valueText = stringResource(
                        R.string.volumetric_slope_value,
                        extrusionRateSmoothing.maximumSlope,
                    ),
                    value = extrusionRateSmoothing.maximumSlope,
                    range = 0f..maximumExtrusionSlope,
                    steps = maximumExtrusionSlope.roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = {
                        val slope = it.roundToInt().toFloat()
                        onOptionsChanged(
                            options.copy(
                                quality = options.quality.copy(
                                    extrusionRateSmoothing = extrusionRateSmoothing.copy(
                                        maximumSlope = slope,
                                    ),
                                ),
                                gcodeSettings = if (slope > 0f) {
                                    options.gcodeSettings.copy(arcFitting = false)
                                } else {
                                    options.gcodeSettings
                                },
                            ),
                        )
                    },
                )
                if (extrusionRateSmoothing.maximumSlope > 0f || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.smoothing_segment_length),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            extrusionRateSmoothing.segmentLength,
                        ),
                        value = extrusionRateSmoothing.segmentLength,
                        range = 0.5f..5f,
                        steps = 44,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(
                                        extrusionRateSmoothing = extrusionRateSmoothing.copy(
                                            segmentLength = (it * 10f).roundToInt() / 10f,
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.smoothing_external_only),
                        checked = extrusionRateSmoothing.externalOnly,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(
                                        extrusionRateSmoothing = extrusionRateSmoothing.copy(
                                            externalOnly = it,
                                        ),
                                    ),
                                ),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.avoid_crossing_walls),
                    checked = options.reduceCrossingWall,
                    onCheckedChange = { onOptionsChanged(options.copy(reduceCrossingWall = it)) },
                )
                if (options.reduceCrossingWall || settingsQuery.isNotBlank()) {
                    LengthOrPercentSetting(
                        label = stringResource(R.string.maximum_travel_detour),
                        value = options.maxTravelDetourDistance,
                        percent = options.maxTravelDetourDistancePercent,
                        maximumAbsolute = 1_000f,
                        maximumPercent = 1_000f,
                        onValueChange = { onOptionsChanged(options.copy(maxTravelDetourDistance = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(
                                options.copy(
                                    maxTravelDetourDistance = adjustedValue,
                                    maxTravelDetourDistancePercent = selectedPercent,
                                ),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.reduce_infill_retraction),
                    checked = options.reduceInfillRetraction,
                    onCheckedChange = { onOptionsChanged(options.copy(reduceInfillRetraction = it)) },
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
                OverhangSpeedSetting(
                    label = stringResource(R.string.internal_bridge_speed),
                    value = options.internalBridgeSpeed,
                    percent = options.internalBridgeSpeedPercent,
                    maximumAbsolute = maximumFeatureSpeed,
                    maximumPercent = 300f,
                    onValueChange = { onOptionsChanged(options.copy(internalBridgeSpeed = it)) },
                    onPercentChange = { selectedPercent, adjustedValue ->
                        onOptionsChanged(
                            options.copy(
                                internalBridgeSpeed = adjustedValue,
                                internalBridgeSpeedPercent = selectedPercent,
                            ),
                        )
                    },
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
                SettingsSwitch(
                    label = stringResource(R.string.slowdown_for_curled_perimeters),
                    checked = options.slowdownForCurledPerimeters,
                    onCheckedChange = { onOptionsChanged(options.copy(slowdownForCurledPerimeters = it)) },
                )
                if (options.overhangSpeedEnabled || settingsQuery.isNotBlank()) {
                    OverhangSpeedSetting(
                        label = stringResource(R.string.overhang_speed_1),
                        value = options.overhangSpeed1,
                        percent = options.overhangSpeed1Percent,
                        maximumAbsolute = maximumFeatureSpeed,
                        onValueChange = { onOptionsChanged(options.copy(overhangSpeed1 = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(options.copy(overhangSpeed1 = adjustedValue, overhangSpeed1Percent = selectedPercent))
                        },
                    )
                    OverhangSpeedSetting(
                        label = stringResource(R.string.overhang_speed_2),
                        value = options.overhangSpeed2,
                        percent = options.overhangSpeed2Percent,
                        maximumAbsolute = maximumFeatureSpeed,
                        onValueChange = { onOptionsChanged(options.copy(overhangSpeed2 = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(options.copy(overhangSpeed2 = adjustedValue, overhangSpeed2Percent = selectedPercent))
                        },
                    )
                    OverhangSpeedSetting(
                        label = stringResource(R.string.overhang_speed_3),
                        value = options.overhangSpeed3,
                        percent = options.overhangSpeed3Percent,
                        maximumAbsolute = maximumFeatureSpeed,
                        onValueChange = { onOptionsChanged(options.copy(overhangSpeed3 = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(options.copy(overhangSpeed3 = adjustedValue, overhangSpeed3Percent = selectedPercent))
                        },
                    )
                    OverhangSpeedSetting(
                        label = stringResource(R.string.overhang_speed_4),
                        value = options.overhangSpeed4,
                        percent = options.overhangSpeed4Percent,
                        maximumAbsolute = maximumFeatureSpeed,
                        onValueChange = { onOptionsChanged(options.copy(overhangSpeed4 = it)) },
                        onPercentChange = { selectedPercent, adjustedValue ->
                            onOptionsChanged(options.copy(overhangSpeed4 = adjustedValue, overhangSpeed4Percent = selectedPercent))
                        },
                    )
                }
                SettingsGroupTitle(stringResource(R.string.bridges))
                if (supportAvailability.treeKind == TreeSupportSettingsKind.BRANCHED || isSearchingSettings) {
                    SettingSlider(
                        label = stringResource(R.string.maximum_unsupported_bridge_length),
                        valueText = stringResource(R.string.millimeters_value, options.maxBridgeLength),
                        value = options.maxBridgeLength,
                        range = 0f..max(100f, options.maxBridgeLength),
                        steps = max(100f, options.maxBridgeLength).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = { onOptionsChanged(options.copy(maxBridgeLength = it.roundToInt().toFloat())) },
                    )
                }
                SettingSlider(
                    label = stringResource(R.string.external_bridge_density),
                    valueText = stringResource(R.string.percent_value, options.bridgeDensity.roundToInt()),
                    value = options.bridgeDensity,
                    range = 10f..100f,
                    steps = 89,
                    onValueChange = { onOptionsChanged(options.copy(bridgeDensity = it.roundToInt().toFloat())) },
                )
                SettingSlider(
                    label = stringResource(R.string.internal_bridge_density),
                    valueText = stringResource(R.string.percent_value, options.internalBridgeDensity.roundToInt()),
                    value = options.internalBridgeDensity,
                    range = 10f..100f,
                    steps = 89,
                    onValueChange = { onOptionsChanged(options.copy(internalBridgeDensity = it.roundToInt().toFloat())) },
                )
                SettingSlider(
                    label = stringResource(R.string.external_bridge_angle),
                    valueText = stringResource(R.string.angle_value, options.bridgeAngle),
                    value = options.bridgeAngle,
                    range = 0f..360f,
                    steps = 359,
                    onValueChange = { onOptionsChanged(options.copy(bridgeAngle = it.roundToInt().toFloat())) },
                )
                SettingSlider(
                    label = stringResource(R.string.internal_bridge_angle),
                    valueText = stringResource(R.string.angle_value, options.internalBridgeAngle),
                    value = options.internalBridgeAngle,
                    range = 0f..360f,
                    steps = 359,
                    onValueChange = { onOptionsChanged(options.copy(internalBridgeAngle = it.roundToInt().toFloat())) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.extra_bridge_layers),
                    entries = listOf("disabled", "external_bridge_only", "internal_bridge_only", "apply_to_all"),
                    selected = options.extraBridgeLayer,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "external_bridge_only" -> R.string.extra_bridge_external
                                "internal_bridge_only" -> R.string.extra_bridge_internal
                                "apply_to_all" -> R.string.extra_bridge_all
                                else -> R.string.extra_bridge_disabled
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(extraBridgeLayer = it)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.internal_bridge_filter),
                    entries = listOf("disabled", "limited", "nofilter"),
                    selected = options.internalBridgeFilter,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "limited" -> R.string.bridge_filter_limited
                                "nofilter" -> R.string.bridge_filter_none
                                else -> R.string.bridge_filter_default
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(internalBridgeFilter = it)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.counterbore_bridging),
                    entries = listOf("none", "partiallybridge", "sacrificiallayer"),
                    selected = options.counterboreHoleBridging,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "partiallybridge" -> R.string.counterbore_partial
                                "sacrificiallayer" -> R.string.counterbore_sacrificial
                                else -> R.string.counterbore_none
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(counterboreHoleBridging = it)) },
                )
                if (supportAvailability.treeKind != TreeSupportSettingsKind.BRANCHED || isSearchingSettings) {
                    SettingsSwitch(
                        label = stringResource(R.string.do_not_support_bridges),
                        checked = options.bridgeNoSupport,
                        onCheckedChange = { onOptionsChanged(options.copy(bridgeNoSupport = it)) },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.thick_external_bridges),
                    checked = options.thickBridges,
                    onCheckedChange = { onOptionsChanged(options.copy(thickBridges = it)) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.thick_internal_bridges),
                    checked = options.thickInternalBridges,
                    onCheckedChange = { onOptionsChanged(options.copy(thickInternalBridges = it)) },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.ironing),
                    entries = listOf("no ironing", "top", "topmost", "solid"),
                    selected = options.ironing.type,
                    optionLabel = { enumLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(ironing = options.ironing.copy(type = it))) },
                )
                if (options.ironing.type != "no ironing" || settingsQuery.isNotBlank()) {
                    SettingChoices(
                        settingLabel = stringResource(R.string.ironing_pattern),
                        entries = listOf("rectilinear", "concentric"),
                        selected = options.ironing.pattern,
                        optionLabel = { fillPatternLabel(it) },
                        onSelected = { onOptionsChanged(options.copy(ironing = options.ironing.copy(pattern = it))) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.ironing_flow),
                        valueText = stringResource(R.string.percent_value, options.ironing.flow.roundToInt()),
                        value = options.ironing.flow,
                        range = 0f..100f,
                        steps = 99,
                        onValueChange = { onOptionsChanged(options.copy(ironing = options.ironing.copy(flow = it.roundToInt().toFloat()))) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.ironing_spacing),
                        valueText = stringResource(R.string.millimeters_value_precise, options.ironing.spacing),
                        value = options.ironing.spacing,
                        range = 0.05f..0.5f,
                        steps = 44,
                        onValueChange = { onOptionsChanged(options.copy(ironing = options.ironing.copy(spacing = it))) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.ironing_inset),
                        valueText = stringResource(R.string.millimeters_value_precise, options.ironing.inset),
                        value = options.ironing.inset,
                        range = 0f..100f,
                        steps = 9_999,
                        onValueChange = { onOptionsChanged(options.copy(ironing = options.ironing.copy(inset = it))) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.ironing_speed),
                        valueText = stringResource(R.string.print_speed_value, options.ironing.speed),
                        value = options.ironing.speed,
                        range = 1f..maximumFeatureSpeed,
                        steps = maximumFeatureSpeed.roundToInt().coerceAtLeast(2) - 2,
                        onValueChange = { onOptionsChanged(options.copy(ironing = options.ironing.copy(speed = it.roundToInt().toFloat()))) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.ironing_angle),
                        valueText = if (options.ironing.angle < 0f) {
                            stringResource(R.string.order_default)
                        } else {
                            stringResource(R.string.angle_value, options.ironing.angle)
                        },
                        value = options.ironing.angle,
                        range = -1f..359f,
                        steps = 359,
                        onValueChange = { onOptionsChanged(options.copy(ironing = options.ironing.copy(angle = it.roundToInt().toFloat()))) },
                    )
                }
                SettingsGroupTitle(stringResource(R.string.feature_flow_ratio))
                SettingSlider(
                    label = stringResource(R.string.flow_ratio),
                    valueText = stringResource(R.string.flow_ratio_value, options.printFlowRatio),
                    value = options.printFlowRatio,
                    range = minimumPrintFlowRatio..maximumPrintFlowRatio,
                    steps = printFlowRatioSteps,
                    onValueChange = {
                        onOptionsChanged(options.copy(printFlowRatio = (it * 100f).roundToInt() / 100f))
                    },
                )
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
                AccelerationSettings(
                    options = options,
                    maximumFeatureAcceleration = maximumFeatureAcceleration,
                    featureAccelerationSteps = featureAccelerationSteps,
                    onOptionsChanged = onOptionsChanged,
                )
                SettingsGroupTitle(stringResource(R.string.feature_jerk))
                SettingSlider(
                    label = stringResource(R.string.order_default),
                    valueText = stringResource(R.string.jerk_value, options.defaultJerk),
                    value = options.defaultJerk,
                    range = 0f..maximumFeatureJerk,
                    steps = featureJerkSteps,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(jerk = options.jerk.copy(defaultJerk = (it * 2f).roundToInt() / 2f)),
                        )
                    },
                )
                if (options.defaultJerk > 0f || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.toolpath_outer_wall),
                        valueText = stringResource(R.string.jerk_value, options.outerWallJerk),
                        value = options.outerWallJerk,
                        range = 0f..maximumFeatureJerk,
                        steps = featureJerkSteps,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(jerk = options.jerk.copy(outerWallJerk = (it * 2f).roundToInt() / 2f)),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.toolpath_inner_wall),
                        valueText = stringResource(R.string.jerk_value, options.innerWallJerk),
                        value = options.innerWallJerk,
                        range = 0f..maximumFeatureJerk,
                        steps = featureJerkSteps,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(jerk = options.jerk.copy(innerWallJerk = (it * 2f).roundToInt() / 2f)),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.toolpath_top_surface),
                        valueText = stringResource(R.string.jerk_value, options.topSurfaceJerk),
                        value = options.topSurfaceJerk,
                        range = 0f..maximumFeatureJerk,
                        steps = featureJerkSteps,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(jerk = options.jerk.copy(topSurfaceJerk = (it * 2f).roundToInt() / 2f)),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.infill),
                        valueText = stringResource(R.string.jerk_value, options.infillJerk),
                        value = options.infillJerk,
                        range = 0f..maximumFeatureJerk,
                        steps = featureJerkSteps,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(jerk = options.jerk.copy(infillJerk = (it * 2f).roundToInt() / 2f)),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.initial_layer),
                        valueText = stringResource(R.string.jerk_value, options.firstLayerJerk),
                        value = options.firstLayerJerk,
                        range = 0f..maximumFeatureJerk,
                        steps = featureJerkSteps,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(jerk = options.jerk.copy(firstLayerJerk = (it * 2f).roundToInt() / 2f)),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.travel),
                        valueText = stringResource(R.string.jerk_value, options.travelJerk),
                        value = options.travelJerk,
                        range = 0f..maximumFeatureJerk,
                        steps = featureJerkSteps,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(jerk = options.jerk.copy(travelJerk = (it * 2f).roundToInt() / 2f)),
                            )
                        },
                    )
                }
            }

            SlicingSettingsSection.SUPPORT -> {
                SettingsGroupTitle(stringResource(R.string.supports))
                SettingsSwitch(
                    label = stringResource(R.string.enable_supports),
                    checked = options.supportEnabled,
                    onCheckedChange = { onOptionsChanged(options.copy(supportEnabled = it)) },
                )
                IntegerSettingField(
                    label = stringResource(R.string.enforce_support_layers),
                    value = options.supportCoverage.enforcedLayers,
                    maximum = 5_000,
                    suffix = stringResource(R.string.layers),
                    supportingText = stringResource(R.string.enforce_support_layers_hint),
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                supportCoverage = options.supportCoverage.copy(enforcedLayers = it),
                            ),
                        )
                    },
                )
                if (supportAvailability.haveSupportMaterial || isSearchingSettings) {
                    SettingSlider(
                        label = stringResource(R.string.support_speed),
                        valueText = stringResource(R.string.print_speed_value, options.supportSpeed),
                        value = options.supportSpeed,
                        range = 10f..maximumFeatureSpeed,
                        steps = featureSpeedSteps,
                        onValueChange = { onOptionsChanged(options.copy(supportSpeed = (it / 5f).roundToInt() * 5f)) },
                    )
                    if (
                        (supportAvailability.haveSupportMaterial && supportAvailability.haveInterface) ||
                        isSearchingSettings
                    ) {
                        SettingSlider(
                            label = stringResource(R.string.support_interface_speed),
                            valueText = stringResource(R.string.print_speed_value, options.supportInterfaceSpeed),
                            value = options.supportInterfaceSpeed,
                            range = 10f..maximumFeatureSpeed,
                            steps = featureSpeedSteps,
                            onValueChange = {
                                onOptionsChanged(options.copy(supportInterfaceSpeed = (it / 5f).roundToInt() * 5f))
                            },
                        )
                    }
                    SettingSlider(
                        label = stringResource(R.string.support_line_width),
                        valueText = stringResource(R.string.millimeters_value_precise, options.supportLineWidth),
                        value = options.supportLineWidth,
                        range = minimumLineWidth..maximumLineWidth,
                        steps = lineWidthSteps,
                        onValueChange = { lineWidth ->
                            if (supportAvailability.treeKind == TreeSupportSettingsKind.ORGANIC) {
                                val tipDiameter = max(options.treeSupportTipDiameter, lineWidth)
                                onOptionsChanged(
                                    options.copy(
                                        supportLineWidth = lineWidth,
                                        treeSupportTipDiameter = tipDiameter,
                                        treeSupportOrganicBranchDiameter = max(
                                            options.treeSupportOrganicBranchDiameter,
                                            minimumOrganicTreeBranchDiameter(lineWidth, tipDiameter),
                                        ),
                                    ),
                                )
                            } else {
                                onOptionsChanged(options.copy(supportLineWidth = lineWidth))
                            }
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.support_type),
                        entries = listOf("normal(auto)", "tree(auto)", "normal(manual)", "tree(manual)"),
                        selected = normalizedSupportType(options.supportType),
                        optionLabel = {
                            when (it) {
                                "tree(auto)" -> stringResource(R.string.tree_support_auto)
                                "normal(manual)" -> stringResource(R.string.normal_support_manual)
                                "tree(manual)" -> stringResource(R.string.tree_support_manual)
                                else -> stringResource(R.string.normal_support_auto)
                            }
                        },
                        onSelected = {
                            onOptionsChanged(
                                options.copy(
                                    supportType = it,
                                    supportStyle = normalizedSupportStyle(it, options.supportStyle),
                                ),
                            )
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.support_style),
                        entries = compatibleSupportStyles(options.supportType),
                        selected = normalizedSupportStyle(options.supportType, options.supportStyle),
                        optionLabel = { enumLabel(it) },
                        onSelected = {
                            onOptionsChanged(
                                options.copy(
                                    supportStyle = normalizedSupportStyle(options.supportType, it),
                                ),
                            )
                        },
                    )
                    if (supportAvailability.treeKind != TreeSupportSettingsKind.NONE || isSearchingSettings) {
                        SettingsGroupTitle(stringResource(R.string.tree_support))
                        if (supportAvailability.treeKind == TreeSupportSettingsKind.BRANCHED || isSearchingSettings) {
                            SettingSlider(
                                label = stringResource(R.string.tree_support_branch_angle),
                                valueText = stringResource(R.string.degrees_value, options.treeSupportBranchAngle),
                                value = options.treeSupportBranchAngle,
                                range = 0f..60f,
                                steps = 59,
                                onValueChange = {
                                    onOptionsChanged(options.copy(treeSupportBranchAngle = it.roundToInt().toFloat()))
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_branch_distance),
                                valueText = stringResource(
                                    R.string.millimeters_value_precise,
                                    options.treeSupportBranchDistance,
                                ),
                                value = options.treeSupportBranchDistance,
                                range = 1f..10f,
                                steps = 89,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(treeSupportBranchDistance = (it * 10f).roundToInt() / 10f),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_branch_diameter),
                                valueText = stringResource(
                                    R.string.millimeters_value_precise,
                                    options.treeSupportBranchDiameter,
                                ),
                                value = options.treeSupportBranchDiameter,
                                range = 1f..10f,
                                steps = 89,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(treeSupportBranchDiameter = (it * 10f).roundToInt() / 10f),
                                    )
                                },
                            )
                        }
                        SettingSlider(
                            label = stringResource(R.string.tree_support_wall_count),
                            valueText = options.treeSupportWallCount.toString(),
                            value = options.treeSupportWallCount.toFloat(),
                            range = 0f..2f,
                            steps = 1,
                            onValueChange = {
                                onOptionsChanged(options.copy(treeSupportWallCount = it.roundToInt()))
                            },
                        )
                        if (supportAvailability.treeKind == TreeSupportSettingsKind.ORGANIC || isSearchingSettings) {
                            SettingSlider(
                                label = stringResource(R.string.tree_support_tip_diameter),
                                valueText = stringResource(
                                    R.string.millimeters_value_precise,
                                    options.treeSupportTipDiameter,
                                ),
                                value = options.treeSupportTipDiameter.coerceAtLeast(minimumOrganicTipDiameter),
                                range = minimumOrganicTipDiameter..maximumOrganicTipDiameter,
                                steps = ((maximumOrganicTipDiameter - minimumOrganicTipDiameter) * 10f)
                                    .roundToInt().coerceAtLeast(2) - 1,
                                onValueChange = {
                                    val tipDiameter = (it * 10f).roundToInt() / 10f
                                    onOptionsChanged(
                                        options.copy(
                                            treeSupportTipDiameter = tipDiameter,
                                            treeSupportOrganicBranchDiameter = max(
                                                options.treeSupportOrganicBranchDiameter,
                                                minimumOrganicTreeBranchDiameter(
                                                    options.supportLineWidth,
                                                    tipDiameter,
                                                ),
                                            ),
                                        ),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_organic_branch_angle),
                                valueText = stringResource(
                                    R.string.degrees_value,
                                    options.treeSupportOrganicBranchAngle,
                                ),
                                value = options.treeSupportOrganicBranchAngle,
                                range = 0f..60f,
                                steps = 59,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(treeSupportOrganicBranchAngle = it.roundToInt().toFloat()),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_organic_branch_distance),
                                valueText = stringResource(
                                    R.string.millimeters_value_precise,
                                    options.treeSupportOrganicBranchDistance,
                                ),
                                value = options.treeSupportOrganicBranchDistance,
                                range = 1f..10f,
                                steps = 89,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(
                                            treeSupportOrganicBranchDistance = (it * 10f).roundToInt() / 10f,
                                        ),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_organic_branch_diameter),
                                valueText = stringResource(
                                    R.string.millimeters_value_precise,
                                    options.treeSupportOrganicBranchDiameter,
                                ),
                                value = options.treeSupportOrganicBranchDiameter.coerceAtLeast(
                                    minimumOrganicBranchDiameter,
                                ),
                                range = minimumOrganicBranchDiameter..maximumOrganicBranchDiameter,
                                steps = ((maximumOrganicBranchDiameter - minimumOrganicBranchDiameter) * 10f)
                                    .roundToInt().coerceAtLeast(1) - 1,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(
                                            treeSupportOrganicBranchDiameter = (it * 10f).roundToInt() / 10f,
                                        ),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_branch_diameter_angle),
                                valueText = stringResource(
                                    R.string.degrees_value,
                                    options.treeSupportBranchDiameterAngle,
                                ),
                                value = options.treeSupportBranchDiameterAngle,
                                range = 0f..15f,
                                steps = 14,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(treeSupportBranchDiameterAngle = it.roundToInt().toFloat()),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_preferred_branch_angle),
                                valueText = stringResource(
                                    R.string.degrees_value,
                                    options.treeSupportPreferredBranchAngle,
                                ),
                                value = options.treeSupportPreferredBranchAngle,
                                range = 10f..85f,
                                steps = 74,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(treeSupportPreferredBranchAngle = it.roundToInt().toFloat()),
                                    )
                                },
                            )
                            SettingSlider(
                                label = stringResource(R.string.tree_support_branch_density),
                                valueText = stringResource(
                                    R.string.percent_value,
                                    options.treeSupportBranchDensity.roundToInt(),
                                ),
                                value = options.treeSupportBranchDensity,
                                range = 5f..max(35f, options.treeSupportBranchDensity),
                                steps = (max(35f, options.treeSupportBranchDensity) - 5f).roundToInt() - 1,
                                onValueChange = {
                                    onOptionsChanged(options.copy(treeSupportBranchDensity = it.roundToInt().toFloat()))
                                },
                            )
                        }
                        if (supportAvailability.treeKind == TreeSupportSettingsKind.BRANCHED || isSearchingSettings) {
                            SettingsSwitch(
                                label = stringResource(R.string.tree_support_adaptive_layer_height),
                                checked = options.treeSupportAdaptiveLayerHeight,
                                onCheckedChange = {
                                    onOptionsChanged(options.copy(treeSupportAdaptiveLayerHeight = it))
                                },
                            )
                            SettingsSwitch(
                                label = stringResource(R.string.tree_support_auto_brim),
                                checked = options.treeSupportAutoBrim,
                                onCheckedChange = {
                                    onOptionsChanged(options.copy(treeSupportAutoBrim = it))
                                },
                            )
                        }
                        if (
                            (
                                supportAvailability.treeKind == TreeSupportSettingsKind.BRANCHED &&
                                    !options.treeSupportAutoBrim
                                ) ||
                            isSearchingSettings
                        ) {
                            SettingSlider(
                                label = stringResource(R.string.tree_support_brim_width),
                                valueText = stringResource(
                                    R.string.millimeters_value_precise,
                                    options.treeSupportBrimWidth,
                                ),
                                value = options.treeSupportBrimWidth,
                                range = 0f..max(20f, options.treeSupportBrimWidth),
                                steps = (max(20f, options.treeSupportBrimWidth) * 10f).roundToInt() - 1,
                                onValueChange = {
                                    onOptionsChanged(
                                        options.copy(treeSupportBrimWidth = (it * 10f).roundToInt() / 10f),
                                    )
                                },
                            )
                        }
                    }
                    SettingsSwitch(
                        label = stringResource(R.string.support_on_build_plate_only),
                        checked = options.supportCoverage.onBuildPlateOnly,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    supportCoverage = options.supportCoverage.copy(onBuildPlateOnly = it),
                                ),
                            )
                        },
                    )
                    if (
                        (
                            supportAvailability.automatic &&
                                supportAvailability.treeKind != TreeSupportSettingsKind.NONE
                            ) ||
                        isSearchingSettings
                    ) {
                        SettingsSwitch(
                            label = stringResource(R.string.support_critical_regions_only),
                            checked = options.supportCoverage.criticalRegionsOnly,
                            onCheckedChange = {
                                onOptionsChanged(
                                    options.copy(
                                        supportCoverage = options.supportCoverage.copy(criticalRegionsOnly = it),
                                    ),
                                )
                            },
                        )
                    }
                    SettingsSwitch(
                        label = stringResource(R.string.support_remove_small_overhangs),
                        checked = options.supportCoverage.removeSmallOverhangs,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    supportCoverage = options.supportCoverage.copy(removeSmallOverhangs = it),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.independent_support_layer_height),
                        checked = options.independentSupportLayerHeight,
                        onCheckedChange = {
                            onOptionsChanged(options.copy(independentSupportLayerHeight = it))
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.support_base_pattern),
                        entries = listOf("default", "rectilinear", "rectilinear-grid", "lightning", "hollow"),
                        selected = options.supportBasePattern,
                        optionLabel = { enumLabel(it) },
                        onSelected = { onOptionsChanged(options.copy(supportBasePattern = it)) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.support_pattern_angle),
                        valueText = stringResource(R.string.degrees_value, options.supportAdvanced.patternAngle),
                        value = options.supportAdvanced.patternAngle,
                        range = 0f..359f,
                        steps = 358,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    supportAdvanced = options.supportAdvanced.copy(patternAngle = it.roundToInt().toFloat()),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.support_base_pattern_spacing),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.supportBasePatternSpacing,
                        ),
                        value = options.supportBasePatternSpacing,
                        range = 0f..max(20f, options.supportBasePatternSpacing),
                        steps = (max(20f, options.supportBasePatternSpacing) / 0.1f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(supportBasePatternSpacing = (it * 10f).roundToInt() / 10f),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.support_expansion),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.supportExpansion,
                        ),
                        value = options.supportExpansion,
                        range = min(-10f, options.supportExpansion)..max(10f, options.supportExpansion),
                        steps = (
                            (max(10f, options.supportExpansion) - min(-10f, options.supportExpansion)) * 10f
                        ).roundToInt() - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(supportExpansion = (it * 10f).roundToInt() / 10f),
                            )
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.support_interface_pattern),
                        entries = listOf("auto", "rectilinear", "rectilinear_interlaced", "concentric", "grid"),
                        selected = options.supportInterfacePattern,
                        optionLabel = { enumLabel(it) },
                        onSelected = { onOptionsChanged(options.copy(supportInterfacePattern = it)) },
                    )
                    if (
                        (supportAvailability.haveSupportMaterial && supportAvailability.haveInterface) ||
                        isSearchingSettings
                    ) {
                        SettingsSwitch(
                            label = stringResource(R.string.support_interface_loop_pattern),
                            checked = options.supportInterfaceLoopPattern,
                            onCheckedChange = {
                                onOptionsChanged(options.copy(supportInterfaceLoopPattern = it))
                            },
                        )
                    }
                    SupportFlowSettings(
                        options = options,
                        showInterface = supportAvailability.haveSupportMaterial &&
                            supportAvailability.haveInterface || isSearchingSettings,
                        onOptionsChanged = onOptionsChanged,
                    )
                    SettingsGroupTitle(stringResource(R.string.support_filament_routing))
                    FilamentSlotSetting(
                        label = stringResource(R.string.support_filament),
                        filaments = options.resolvedFilamentSlots(),
                        selectedSlot = options.supportFilament,
                        defaultLabel = stringResource(R.string.filament_default),
                        onSelected = {
                            onOptionsChanged(options.copy(supportFilament = it))
                        },
                    )
                    if (
                        (supportAvailability.haveSupportMaterial && supportAvailability.haveInterface) ||
                        isSearchingSettings
                    ) {
                        FilamentSlotSetting(
                            label = stringResource(R.string.support_interface_filament),
                            filaments = options.resolvedFilamentSlots(),
                            selectedSlot = options.supportInterfaceFilament,
                            defaultLabel = stringResource(R.string.filament_default),
                            onSelected = {
                                onOptionsChanged(options.copy(supportInterfaceFilament = it))
                            },
                        )
                    }
                    SettingsSwitch(
                        label = stringResource(R.string.avoid_interface_filament_for_base),
                        checked = options.supportAdvanced.avoidInterfaceFilamentForBase,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    supportAdvanced = options.supportAdvanced.copy(avoidInterfaceFilamentForBase = it),
                                ),
                            )
                        },
                    )
                    if (supportAvailability.automatic || isSearchingSettings) {
                        SettingSlider(
                            label = stringResource(R.string.support_threshold_angle),
                            valueText = stringResource(R.string.degrees_value, options.supportAngle),
                            value = options.supportAngle,
                            range = 0f..90f,
                            steps = 89,
                            onValueChange = {
                                onOptionsChanged(options.copy(supportAngle = it.roundToInt().toFloat()))
                            },
                        )
                    }
                    if (
                        (supportAvailability.automatic && options.supportAngle.roundToInt() == 0) ||
                        isSearchingSettings
                    ) {
                        SettingChoices(
                            settingLabel = stringResource(R.string.support_threshold_overlap_unit),
                            entries = listOf("percent", "millimeters"),
                            selected = if (options.supportAdvanced.thresholdOverlapPercent) {
                                "percent"
                            } else {
                                "millimeters"
                            },
                            optionLabel = {
                                stringResource(if (it == "percent") R.string.percent_unit else R.string.millimeters_unit)
                            },
                            onSelected = {
                                val asPercent = it == "percent"
                                onOptionsChanged(
                                    options.copy(
                                        supportAdvanced = options.supportAdvanced.copy(
                                            thresholdOverlap = if (asPercent) 50f else 0.2f,
                                            thresholdOverlapPercent = asPercent,
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.support_threshold_overlap),
                            valueText = if (options.supportAdvanced.thresholdOverlapPercent) {
                                stringResource(
                                    R.string.percent_value,
                                    options.supportAdvanced.thresholdOverlap.roundToInt(),
                                )
                            } else {
                                stringResource(
                                    R.string.millimeters_value_precise,
                                    options.supportAdvanced.thresholdOverlap,
                                )
                            },
                            value = options.supportAdvanced.thresholdOverlap,
                            range = if (options.supportAdvanced.thresholdOverlapPercent) 0f..100f else 0f..0.5f,
                            steps = if (options.supportAdvanced.thresholdOverlapPercent) 99 else 49,
                            onValueChange = {
                                val value = if (options.supportAdvanced.thresholdOverlapPercent) {
                                    it.roundToInt().toFloat()
                                } else {
                                    (it * 100f).roundToInt() / 100f
                                }
                                onOptionsChanged(
                                    options.copy(
                                        supportAdvanced = options.supportAdvanced.copy(thresholdOverlap = value),
                                    ),
                                )
                            },
                        )
                    }
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
                    if (
                        (
                            supportAvailability.haveSupportMaterial &&
                                supportAvailability.haveInterface &&
                                !supportAvailability.ironingActive
                            ) ||
                        isSearchingSettings
                    ) {
                        SettingSlider(
                            label = stringResource(R.string.support_interface_spacing),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.supportInterfaceSpacing,
                            ),
                            value = options.supportInterfaceSpacing,
                            range = 0f..max(2f, options.supportInterfaceSpacing),
                            steps = (max(2f, options.supportInterfaceSpacing) / 0.05f)
                                .roundToInt().coerceAtLeast(2) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        supportInterfaceSpacing = (it / 0.05f).roundToInt() * 0.05f,
                                    ),
                                )
                            },
                        )
                    }
                    if (
                        (supportAvailability.haveSupportMaterial && supportAvailability.haveInterface) ||
                        isSearchingSettings
                    ) {
                        SettingSlider(
                            label = stringResource(R.string.support_bottom_interface_spacing),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.supportBottomInterfaceSpacing,
                            ),
                            value = options.supportBottomInterfaceSpacing,
                            range = 0f..max(2f, options.supportBottomInterfaceSpacing),
                            steps = (max(2f, options.supportBottomInterfaceSpacing) / 0.05f)
                                .roundToInt().coerceAtLeast(2) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        supportBottomInterfaceSpacing = (it / 0.05f).roundToInt() * 0.05f,
                                    ),
                                )
                            },
                        )
                    }
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
                    SettingSlider(
                        label = stringResource(R.string.support_object_first_layer_gap),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.supportAdvanced.objectFirstLayerGap,
                        ),
                        value = options.supportAdvanced.objectFirstLayerGap,
                        range = 0f..max(2f, options.supportAdvanced.objectFirstLayerGap),
                        steps = (max(2f, options.supportAdvanced.objectFirstLayerGap) / 0.02f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    supportAdvanced = options.supportAdvanced.copy(
                                        objectFirstLayerGap = (it * 50f).roundToInt() / 50f,
                                    ),
                                ),
                            )
                        },
                    )
                    if (supportAvailability.canIron || isSearchingSettings) {
                        SettingsGroupTitle(stringResource(R.string.support_ironing))
                        SettingsSwitch(
                            label = stringResource(R.string.support_ironing),
                            checked = options.supportAdvanced.ironingEnabled,
                            onCheckedChange = {
                                onOptionsChanged(
                                    options.copy(supportAdvanced = options.supportAdvanced.copy(ironingEnabled = it)),
                                )
                            },
                        )
                    }
                    if (supportAvailability.ironingActive || isSearchingSettings) {
                        SettingChoices(
                            settingLabel = stringResource(R.string.support_ironing_pattern),
                            entries = listOf("rectilinear", "concentric"),
                            selected = options.supportAdvanced.ironingPattern,
                            optionLabel = { enumLabel(it) },
                            onSelected = {
                                onOptionsChanged(
                                    options.copy(supportAdvanced = options.supportAdvanced.copy(ironingPattern = it)),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.support_ironing_flow),
                            valueText = stringResource(
                                R.string.percent_value,
                                options.supportAdvanced.ironingFlow.roundToInt(),
                            ),
                            value = options.supportAdvanced.ironingFlow,
                            range = 0f..100f,
                            steps = 99,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        supportAdvanced = options.supportAdvanced.copy(
                                            ironingFlow = it.roundToInt().toFloat(),
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.support_ironing_spacing),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.supportAdvanced.ironingSpacing,
                            ),
                            value = options.supportAdvanced.ironingSpacing,
                            range = 0f..1f,
                            steps = 99,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        supportAdvanced = options.supportAdvanced.copy(
                                            ironingSpacing = (it * 100f).roundToInt() / 100f,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            SlicingSettingsSection.OTHERS -> {
                SettingsGroupTitle(stringResource(R.string.fuzzy_skin))
                SettingChoices(
                    settingLabel = stringResource(R.string.fuzzy_skin_type),
                    entries = listOf("none", "external", "all", "allwalls"),
                    selected = options.fuzzySkin.type,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "external" -> R.string.fuzzy_skin_external
                                "all" -> R.string.fuzzy_skin_all
                                "allwalls" -> R.string.fuzzy_skin_all_walls
                                else -> R.string.fuzzy_skin_none
                            },
                        )
                    },
                    onSelected = {
                        onOptionsChanged(options.copy(fuzzySkin = options.fuzzySkin.copy(type = it)))
                    },
                )
                if (options.fuzzySkin.type != "none" || settingsQuery.isNotBlank()) {
                    SettingsSwitch(
                        label = stringResource(R.string.fuzzy_skin_first_layer),
                        checked = options.fuzzySkin.firstLayer,
                        onCheckedChange = {
                            onOptionsChanged(options.copy(fuzzySkin = options.fuzzySkin.copy(firstLayer = it)))
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.fuzzy_skin_point_distance),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.fuzzySkin.pointDistance,
                        ),
                        value = options.fuzzySkin.pointDistance,
                        range = 0f..5f,
                        steps = 499,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    fuzzySkin = options.fuzzySkin.copy(
                                        pointDistance = (it * 100f).roundToInt() / 100f,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.fuzzy_skin_thickness),
                        valueText = stringResource(R.string.millimeters_value_precise, options.fuzzySkin.thickness),
                        value = options.fuzzySkin.thickness,
                        range = 0f..1f,
                        steps = 99,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    fuzzySkin = options.fuzzySkin.copy(
                                        thickness = (it * 100f).roundToInt() / 100f,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.fuzzy_skin_mode),
                        entries = if (options.wallGenerator == "arachne") {
                            listOf("displacement", "extrusion", "combined")
                        } else {
                            listOf("displacement")
                        },
                        selected = if (options.wallGenerator == "arachne") {
                            options.fuzzySkin.mode
                        } else {
                            "displacement"
                        },
                        optionLabel = { enumLabel(it) },
                        onSelected = {
                            onOptionsChanged(options.copy(fuzzySkin = options.fuzzySkin.copy(mode = it)))
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.fuzzy_skin_noise),
                        entries = listOf("classic", "perlin", "billow", "ridgedmulti", "voronoi"),
                        selected = options.fuzzySkin.noiseType,
                        optionLabel = { enumLabel(it) },
                        onSelected = {
                            onOptionsChanged(options.copy(fuzzySkin = options.fuzzySkin.copy(noiseType = it)))
                        },
                    )
                    if (options.fuzzySkin.noiseType != "classic" || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.fuzzy_skin_scale),
                            valueText = String.format(Locale.US, "%.1f", options.fuzzySkin.scale),
                            value = options.fuzzySkin.scale,
                            range = 0.1f..max(20f, options.fuzzySkin.scale),
                            steps = ((max(20f, options.fuzzySkin.scale) - 0.1f) * 10f).roundToInt() - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        fuzzySkin = options.fuzzySkin.copy(
                                            scale = (it * 10f).roundToInt() / 10f,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    if (options.fuzzySkin.noiseType !in setOf("classic", "voronoi") || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.fuzzy_skin_octaves),
                            valueText = options.fuzzySkin.octaves.toString(),
                            value = options.fuzzySkin.octaves.toFloat(),
                            range = 1f..10f,
                            steps = 8,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(fuzzySkin = options.fuzzySkin.copy(octaves = it.roundToInt())),
                                )
                            },
                        )
                    }
                    if (options.fuzzySkin.noiseType in setOf("perlin", "billow") || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.fuzzy_skin_persistence),
                            valueText = String.format(Locale.US, "%.2f", options.fuzzySkin.persistence),
                            value = options.fuzzySkin.persistence,
                            range = 0.01f..1f,
                            steps = 98,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        fuzzySkin = options.fuzzySkin.copy(
                                            persistence = (it * 100f).roundToInt() / 100f,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
                SettingsGroupTitle(stringResource(R.string.print_sequence))
                SettingChoices(
                    settingLabel = stringResource(R.string.print_sequence),
                    entries = listOf("by layer", "by object"),
                    selected = options.printSequence,
                    optionLabel = {
                        stringResource(
                            if (it == "by object") R.string.print_by_object else R.string.print_by_layer,
                        )
                    },
                    onSelected = { sequence ->
                        onOptionsChanged(options.withPrintSequenceSelection(sequence))
                    },
                )
                if (options.printSequence == "by layer" || settingsQuery.isNotBlank()) {
                    SettingChoices(
                        settingLabel = stringResource(R.string.intra_layer_order),
                        entries = listOf("default", "as_obj_list"),
                        selected = options.printOrder,
                        optionLabel = {
                            stringResource(
                                if (it == "as_obj_list") {
                                    R.string.order_as_object_list
                                } else {
                                    R.string.order_default
                                },
                            )
                        },
                        onSelected = { onOptionsChanged(options.copy(printOrder = it)) },
                    )
                }
                SettingsGroupTitle(stringResource(R.string.gcode_output))
                SettingsSwitch(
                    label = stringResource(R.string.label_objects),
                    checked = options.gcodeSettings.labelObjects,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(labelObjects = it),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.exclude_objects),
                    checked = options.gcodeSettings.excludeObjects,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(excludeObjects = it),
                            ),
                        )
                    },
                )
                SettingsSwitch(
                    label = stringResource(R.string.verbose_gcode),
                    checked = options.gcodeSettings.verboseComments,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(verboseComments = it),
                            ),
                        )
                    },
                )
                SettingChoices(
                    settingLabel = stringResource(R.string.timelapse),
                    entries = listOf("traditional", "smooth"),
                    selected = options.gcodeSettings.timelapseType,
                    optionLabel = {
                        stringResource(
                            if (it == "smooth") {
                                R.string.timelapse_smooth
                            } else {
                                R.string.timelapse_traditional
                            },
                        )
                    },
                    onSelected = { type ->
                        onOptionsChanged(options.withTimelapseSelection(type))
                    },
                )
                FilenameFormatSetting(
                    value = options.gcodeSettings.filenameFormat,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(filenameFormat = it),
                            ),
                        )
                    },
                )
                SpiralVaseSettings(
                    options = options,
                    showInactive = settingsQuery.isNotBlank(),
                    onOptionsChanged = onOptionsChanged,
                )
                SettingsGroupTitle(stringResource(R.string.prime_tower))
                SettingsSwitch(
                    label = stringResource(R.string.enable_prime_tower),
                    checked = options.wipeTowerEnabled,
                    onCheckedChange = { onOptionsChanged(options.copy(wipeTowerEnabled = it)) },
                )
                if (options.wipeTowerEnabled || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.prime_tower_width),
                        valueText = stringResource(R.string.millimeters_value_precise, options.wipeTowerWidth),
                        value = options.wipeTowerWidth,
                        range = 10f..max(100f, options.wipeTowerWidth),
                        steps = (max(100f, options.wipeTowerWidth) - 10f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(options.copy(wipeTowerWidth = it.roundToInt().toFloat()))
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.prime_volume),
                        valueText = stringResource(
                            R.string.cubic_millimeters_value,
                            options.multiMaterial.primeVolume,
                        ),
                        value = options.multiMaterial.primeVolume,
                        range = 1f..max(200f, options.multiMaterial.primeVolume),
                        steps = (max(200f, options.multiMaterial.primeVolume) - 1f).roundToInt() - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        primeVolume = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    val minimumTowerX = options.bedOriginX
                    val maximumTowerX = (options.bedOriginX + options.bedSizeX)
                        .coerceAtLeast(minimumTowerX + 1f)
                    val minimumTowerY = options.bedOriginY
                    val maximumTowerY = (options.bedOriginY + options.bedSizeY)
                        .coerceAtLeast(minimumTowerY + 1f)
                    SettingsGroupTitle(stringResource(R.string.prime_tower_position))
                    SettingSlider(
                        label = stringResource(R.string.prime_tower_position_x),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.multiMaterial.primeTowerPositionX,
                        ),
                        value = options.multiMaterial.primeTowerPositionX.coerceIn(
                            minimumTowerX,
                            maximumTowerX,
                        ),
                        range = minimumTowerX..maximumTowerX,
                        steps = ((maximumTowerX - minimumTowerX) * 2f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        primeTowerPositionX = (it * 2f).roundToInt() / 2f,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.prime_tower_position_y),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.multiMaterial.primeTowerPositionY,
                        ),
                        value = options.multiMaterial.primeTowerPositionY.coerceIn(
                            minimumTowerY,
                            maximumTowerY,
                        ),
                        range = minimumTowerY..maximumTowerY,
                        steps = ((maximumTowerY - minimumTowerY) * 2f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        primeTowerPositionY = (it * 2f).roundToInt() / 2f,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.prime_tower_brim_width),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.multiMaterial.primeTowerBrimWidth,
                        ),
                        value = options.multiMaterial.primeTowerBrimWidth,
                        range = 0f..max(20f, options.multiMaterial.primeTowerBrimWidth),
                        steps = (max(20f, options.multiMaterial.primeTowerBrimWidth) * 2f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        primeTowerBrimWidth = (it * 2f).roundToInt() / 2f,
                                    ),
                                ),
                            )
                        },
                    )
                    PrimeTowerBrimChamferSettings(
                        settings = options.multiMaterial,
                        showInactive = settingsQuery.isNotBlank(),
                        onChanged = {
                            onOptionsChanged(options.copy(multiMaterial = it))
                        },
                    )
                    PurgeMultiplierSettings(
                        settings = options.multiMaterial,
                        showInactive = settingsQuery.isNotBlank(),
                        onChanged = {
                            onOptionsChanged(options.copy(multiMaterial = it))
                        },
                    )
                    PrimeTowerStructureSettings(
                        settings = options.multiMaterial,
                        showInactive = settingsQuery.isNotBlank(),
                        onChanged = {
                            onOptionsChanged(options.copy(multiMaterial = it))
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wipe_tower_rotation_angle),
                        valueText = stringResource(
                            R.string.degrees_value,
                            options.multiMaterial.wipeTowerRotationAngle,
                        ),
                        value = options.multiMaterial.wipeTowerRotationAngle.coerceIn(0f, 359f),
                        range = 0f..359f,
                        steps = 358,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        wipeTowerRotationAngle = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wipe_tower_bridging),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.multiMaterial.wipeTowerBridging,
                        ),
                        value = options.multiMaterial.wipeTowerBridging,
                        range = 0f..max(100f, options.multiMaterial.wipeTowerBridging),
                        steps = (max(100f, options.multiMaterial.wipeTowerBridging) * 2f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        wipeTowerBridging = (it * 2f).roundToInt() / 2f,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wipe_tower_extra_spacing),
                        valueText = stringResource(
                            R.string.percent_value,
                            options.multiMaterial.wipeTowerExtraSpacing.roundToInt(),
                        ),
                        value = options.multiMaterial.wipeTowerExtraSpacing.coerceIn(100f, 300f),
                        range = 100f..300f,
                        steps = 199,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        wipeTowerExtraSpacing = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wipe_tower_extra_flow),
                        valueText = stringResource(
                            R.string.percent_value,
                            options.multiMaterial.wipeTowerExtraFlow.roundToInt(),
                        ),
                        value = options.multiMaterial.wipeTowerExtraFlow.coerceIn(100f, 300f),
                        range = 100f..300f,
                        steps = 199,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        wipeTowerExtraFlow = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.wipe_tower_max_purge_speed),
                        valueText = stringResource(
                            R.string.print_speed_value,
                            options.multiMaterial.wipeTowerMaxPurgeSpeed,
                        ),
                        value = options.multiMaterial.wipeTowerMaxPurgeSpeed,
                        range = 10f..max(300f, options.multiMaterial.wipeTowerMaxPurgeSpeed),
                        steps = (max(300f, options.multiMaterial.wipeTowerMaxPurgeSpeed) - 10f)
                            .roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        wipeTowerMaxPurgeSpeed = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingChoices(
                        settingLabel = stringResource(R.string.wipe_tower_wall_type),
                        entries = listOf("rectangle", "cone", "rib"),
                        selected = options.multiMaterial.wipeTowerWallType,
                        optionLabel = {
                            when (it) {
                                "cone" -> stringResource(R.string.wipe_tower_wall_cone)
                                "rib" -> stringResource(R.string.wipe_tower_wall_rib)
                                else -> stringResource(R.string.wipe_tower_wall_rectangle)
                            }
                        },
                        onSelected = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(wipeTowerWallType = it),
                                ),
                            )
                        },
                    )
                    if (options.multiMaterial.wipeTowerWallType == "cone" || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.wipe_tower_cone_angle),
                            valueText = stringResource(
                                R.string.degrees_value,
                                options.multiMaterial.wipeTowerConeAngle,
                            ),
                            value = options.multiMaterial.wipeTowerConeAngle.coerceIn(0f, 90f),
                            range = 0f..90f,
                            steps = 89,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        multiMaterial = options.multiMaterial.copy(
                                            wipeTowerConeAngle = it.roundToInt().toFloat(),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                    if (options.multiMaterial.wipeTowerWallType == "rib" || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.wipe_tower_extra_rib_length),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.multiMaterial.wipeTowerExtraRibLength,
                            ),
                            value = options.multiMaterial.wipeTowerExtraRibLength,
                            range = min(-300f, options.multiMaterial.wipeTowerExtraRibLength)..
                                max(300f, options.multiMaterial.wipeTowerExtraRibLength),
                            steps = (
                                (max(300f, options.multiMaterial.wipeTowerExtraRibLength) -
                                    min(-300f, options.multiMaterial.wipeTowerExtraRibLength)) * 2f
                            ).roundToInt().coerceAtLeast(2) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        multiMaterial = options.multiMaterial.copy(
                                            wipeTowerExtraRibLength = (it * 2f).roundToInt() / 2f,
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.wipe_tower_rib_width),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.multiMaterial.wipeTowerRibWidth,
                            ),
                            value = options.multiMaterial.wipeTowerRibWidth,
                            range = 0f..max(50f, options.multiMaterial.wipeTowerRibWidth),
                            steps = (max(50f, options.multiMaterial.wipeTowerRibWidth) * 2f)
                                .roundToInt().coerceAtLeast(2) - 1,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        multiMaterial = options.multiMaterial.copy(
                                            wipeTowerRibWidth = (it * 2f).roundToInt() / 2f,
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingsSwitch(
                            label = stringResource(R.string.wipe_tower_fillet_wall),
                            checked = options.multiMaterial.wipeTowerFilletWall,
                            onCheckedChange = {
                                onOptionsChanged(
                                    options.copy(
                                        multiMaterial = options.multiMaterial.copy(wipeTowerFilletWall = it),
                                    ),
                                )
                            },
                        )
                    }
                    SettingsSwitch(
                        label = stringResource(R.string.wipe_tower_no_sparse_layers),
                        checked = options.multiMaterial.wipeTowerNoSparseLayers,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        wipeTowerNoSparseLayers = it,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.single_extruder_multi_material_priming),
                        checked = options.multiMaterial.singleExtruderMultiMaterialPriming,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        singleExtruderMultiMaterialPriming = it,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingsGroupTitle(stringResource(R.string.flush_options))
                    SettingsSwitch(
                        label = stringResource(R.string.flush_into_infill),
                        checked = options.multiMaterial.flushIntoInfill,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(flushIntoInfill = it),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.flush_into_support),
                        checked = options.multiMaterial.flushIntoSupport,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(flushIntoSupport = it),
                                ),
                            )
                        },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.flush_into_objects),
                        checked = options.multiMaterial.flushIntoObjects,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(flushIntoObjects = it),
                                ),
                            )
                        },
                    )
                }
                if (maximumFilamentSlot > 1) {
                    if (options.printerProfile.singleExtruderMultiMaterial) {
                        SettingsGroupTitle(stringResource(R.string.filament_changes))
                        DirectionalPurgeSetting(
                            filaments = options.resolvedFilamentSlots(),
                            multiMaterial = options.multiMaterial,
                            onChanged = {
                                onOptionsChanged(options.copy(multiMaterial = it))
                            },
                        )
                    }
                    SettingsGroupTitle(stringResource(R.string.filament_for_features))
                    FilamentSlotSetting(
                        label = stringResource(R.string.wall_filament),
                        filaments = options.resolvedFilamentSlots(),
                        selectedSlot = options.featureFilaments.wallFilament,
                        onSelected = {
                            onOptionsChanged(
                                options.copy(
                                    featureFilaments = options.featureFilaments.copy(
                                        wallFilament = it,
                                    ),
                                ),
                            )
                        },
                    )
                    FilamentSlotSetting(
                        label = stringResource(R.string.solid_infill_filament),
                        filaments = options.resolvedFilamentSlots(),
                        selectedSlot = options.featureFilaments.solidInfillFilament,
                        onSelected = {
                            onOptionsChanged(
                                options.copy(
                                    featureFilaments = options.featureFilaments.copy(
                                        solidInfillFilament = it,
                                    ),
                                ),
                            )
                        },
                    )
                    FilamentSlotSetting(
                        label = stringResource(R.string.wipe_tower_filament),
                        filaments = options.resolvedFilamentSlots(),
                        selectedSlot = options.featureFilaments.wipeTowerFilament,
                        defaultLabel = stringResource(R.string.filament_automatic),
                        onSelected = {
                            onOptionsChanged(
                                options.copy(
                                    featureFilaments = options.featureFilaments.copy(
                                        wipeTowerFilament = it,
                                    ),
                                ),
                            )
                        },
                    )
                }
                SettingsGroupTitle(stringResource(R.string.ooze_prevention))
                SettingsSwitch(
                    label = stringResource(R.string.ooze_prevention),
                    checked = options.multiMaterial.oozePrevention,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                multiMaterial = options.multiMaterial.copy(oozePrevention = it),
                            ),
                        )
                    },
                )
                if (options.multiMaterial.oozePrevention || settingsQuery.isNotBlank()) {
                    val temperatureDeltaLimit = max(100, kotlin.math.abs(options.multiMaterial.standbyTemperatureDelta))
                    SettingSlider(
                        label = stringResource(R.string.standby_temperature_delta),
                        valueText = stringResource(
                            R.string.celsius_value,
                            options.multiMaterial.standbyTemperatureDelta,
                        ),
                        value = options.multiMaterial.standbyTemperatureDelta.toFloat(),
                        range = -temperatureDeltaLimit.toFloat()..temperatureDeltaLimit.toFloat(),
                        steps = temperatureDeltaLimit * 2 - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        standbyTemperatureDelta = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.preheat_time),
                        valueText = stringResource(
                            R.string.seconds_value,
                            options.multiMaterial.preheatTime,
                        ),
                        value = options.multiMaterial.preheatTime.coerceIn(0f, 120f),
                        range = 0f..120f,
                        steps = 119,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        preheatTime = it.roundToInt().toFloat(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.preheat_delta_temperature),
                        valueText = stringResource(
                            R.string.celsius_value,
                            options.multiMaterial.preheatDeltaTemperature,
                        ),
                        value = options.multiMaterial.preheatDeltaTemperature.toFloat(),
                        range = -50f..50f,
                        steps = 99,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        preheatDeltaTemperature = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.preheat_steps),
                        valueText = options.multiMaterial.preheatSteps.toString(),
                        value = options.multiMaterial.preheatSteps.toFloat(),
                        range = 1f..10f,
                        steps = 8,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        preheatSteps = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
                    )
                }
                SettingsSwitch(
                    label = stringResource(R.string.interface_shells),
                    checked = options.multiMaterial.interfaceShells,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                multiMaterial = options.multiMaterial.copy(interfaceShells = it),
                            ),
                        )
                    },
                )
                SettingsGroupTitle(stringResource(R.string.material_interlocking))
                SettingsSwitch(
                    label = stringResource(R.string.interlocking_beam),
                    checked = options.multiMaterial.interlockingBeam,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                multiMaterial = options.multiMaterial.copy(
                                    interlockingBeam = it,
                                    segmentedRegionInterlockingDepth = if (it) {
                                        0f
                                    } else {
                                        options.multiMaterial.segmentedRegionInterlockingDepth
                                    },
                                ),
                            ),
                        )
                    },
                )
                if (!options.multiMaterial.interlockingBeam || settingsQuery.isNotBlank()) {
                    val segmentedWidthMaximum = max(
                        20f,
                        options.multiMaterial.segmentedRegionMaxWidth,
                    )
                    SettingSlider(
                        label = stringResource(R.string.segmented_region_max_width),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.multiMaterial.segmentedRegionMaxWidth,
                        ),
                        value = options.multiMaterial.segmentedRegionMaxWidth,
                        range = 0f..segmentedWidthMaximum,
                        steps = (segmentedWidthMaximum * 10f).roundToInt().coerceAtLeast(2) - 1,
                        enabled = !options.multiMaterial.interlockingBeam,
                        onValueChange = { rawValue ->
                            val width = (rawValue * 10f).roundToInt() / 10f
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        segmentedRegionMaxWidth = width,
                                        segmentedRegionInterlockingDepth = if (width == 0f) {
                                            0f
                                        } else {
                                            options.multiMaterial.segmentedRegionInterlockingDepth
                                                .coerceAtMost(width)
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                    if (
                        options.multiMaterial.segmentedRegionMaxWidth > 0f ||
                        settingsQuery.isNotBlank()
                    ) {
                        val depthMaximum = options.multiMaterial.segmentedRegionMaxWidth
                            .coerceAtLeast(0.1f)
                        SettingSlider(
                            label = stringResource(R.string.segmented_region_interlocking_depth),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.multiMaterial.segmentedRegionInterlockingDepth,
                            ),
                            value = options.multiMaterial.segmentedRegionInterlockingDepth,
                            range = 0f..depthMaximum,
                            steps = (depthMaximum * 10f).roundToInt().coerceAtLeast(2) - 1,
                            enabled = !options.multiMaterial.interlockingBeam &&
                                options.multiMaterial.segmentedRegionMaxWidth > 0f,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        multiMaterial = options.multiMaterial.copy(
                                            segmentedRegionInterlockingDepth =
                                                (it * 10f).roundToInt() / 10f,
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
                if (options.multiMaterial.interlockingBeam || settingsQuery.isNotBlank()) {
                    val beamWidthMaximum = max(10f, options.multiMaterial.interlockingBeamWidth)
                    SettingSlider(
                        label = stringResource(R.string.interlocking_beam_width),
                        valueText = stringResource(
                            R.string.millimeters_value_precise,
                            options.multiMaterial.interlockingBeamWidth,
                        ),
                        value = options.multiMaterial.interlockingBeamWidth,
                        range = 0.01f..beamWidthMaximum,
                        steps = ((beamWidthMaximum - 0.01f) / 0.01f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        interlockingBeamWidth = (it * 100f).roundToInt() / 100f,
                                    ),
                                ),
                            )
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.interlocking_orientation),
                        valueText = stringResource(
                            R.string.degrees_value,
                            options.multiMaterial.interlockingOrientation,
                        ),
                        value = options.multiMaterial.interlockingOrientation.coerceIn(0f, 360f),
                        range = 0f..360f,
                        steps = 719,
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        interlockingOrientation = (it * 2f).roundToInt() / 2f,
                                    ),
                                ),
                            )
                        },
                    )
                    val layerMaximum = max(20, options.multiMaterial.interlockingBeamLayerCount)
                    SettingSlider(
                        label = stringResource(R.string.interlocking_beam_layer_count),
                        valueText = options.multiMaterial.interlockingBeamLayerCount.toString(),
                        value = options.multiMaterial.interlockingBeamLayerCount.toFloat(),
                        range = 1f..layerMaximum.toFloat(),
                        steps = (layerMaximum - 2).coerceAtLeast(0),
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        interlockingBeamLayerCount = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
                    )
                    val depthMaximum = max(20, options.multiMaterial.interlockingDepth)
                    SettingSlider(
                        label = stringResource(R.string.interlocking_depth),
                        valueText = options.multiMaterial.interlockingDepth.toString(),
                        value = options.multiMaterial.interlockingDepth.toFloat(),
                        range = 1f..depthMaximum.toFloat(),
                        steps = (depthMaximum - 2).coerceAtLeast(0),
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        interlockingDepth = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
                    )
                    val avoidanceMaximum = max(20, options.multiMaterial.interlockingBoundaryAvoidance)
                    SettingSlider(
                        label = stringResource(R.string.interlocking_boundary_avoidance),
                        valueText = options.multiMaterial.interlockingBoundaryAvoidance.toString(),
                        value = options.multiMaterial.interlockingBoundaryAvoidance.toFloat(),
                        range = 0f..avoidanceMaximum.toFloat(),
                        steps = (avoidanceMaximum - 1).coerceAtLeast(0),
                        onValueChange = {
                            onOptionsChanged(
                                options.copy(
                                    multiMaterial = options.multiMaterial.copy(
                                        interlockingBoundaryAvoidance = it.roundToInt(),
                                    ),
                                ),
                            )
                        },
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
                if (options.skirtLoops > 0 || settingsQuery.isNotBlank()) {
                    SettingChoices(
                        settingLabel = stringResource(R.string.skirt_type),
                        entries = listOf("combined", "perobject"),
                        selected = options.quality.skirtType,
                        optionLabel = {
                            stringResource(
                                if (it == "perobject") R.string.skirt_per_object else R.string.skirt_combined,
                            )
                        },
                        enabled = options.skirtLoops > 0,
                        onSelected = {
                            onOptionsChanged(
                                options.copy(quality = options.quality.copy(skirtType = it)),
                            )
                        },
                    )
                }
                SettingSlider(
                    label = stringResource(R.string.skirt_distance),
                    valueText = stringResource(R.string.millimeters_value_precise, options.skirtDistance),
                    value = options.skirtDistance,
                    range = 0f..max(60f, options.skirtDistance),
                    steps = (max(60f, options.skirtDistance) / 0.5f).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(skirtDistance = (it * 2f).roundToInt() / 2f)) },
                )
                SettingSlider(
                    label = stringResource(R.string.skirt_start_point),
                    valueText = stringResource(R.string.degrees_value, options.quality.skirtStartAngle),
                    value = options.quality.skirtStartAngle,
                    range = -180f..180f,
                    steps = 359,
                    onValueChange = {
                        onOptionsChanged(
                            options.copy(
                                quality = options.quality.copy(skirtStartAngle = it.roundToInt().toFloat()),
                            ),
                        )
                    },
                )
                SettingSlider(
                    label = stringResource(R.string.skirt_height),
                    valueText = options.skirtHeight.toString(),
                    value = options.skirtHeight.toFloat().coerceAtMost(max(10, options.skirtHeight).toFloat()),
                    range = 0f..max(10, options.skirtHeight).toFloat(),
                    steps = max(10, options.skirtHeight).coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(skirtHeight = it.roundToInt())) },
                )
                SettingSlider(
                    label = stringResource(R.string.skirt_speed),
                    valueText = stringResource(R.string.print_speed_value, options.skirtSpeed),
                    value = options.skirtSpeed,
                    range = 0f..max(300f, options.skirtSpeed),
                    steps = max(300f, options.skirtSpeed).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(skirtSpeed = it.roundToInt().toFloat())) },
                )
                SettingSlider(
                    label = stringResource(R.string.minimum_skirt_length),
                    valueText = stringResource(R.string.millimeters_value, options.minimumSkirtLength),
                    value = options.minimumSkirtLength,
                    range = 0f..max(100f, options.minimumSkirtLength),
                    steps = max(100f, options.minimumSkirtLength).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(minimumSkirtLength = it.roundToInt().toFloat())) },
                )
                SettingsSwitch(
                    label = stringResource(R.string.draft_shield),
                    checked = options.draftShield == "enabled",
                    onCheckedChange = {
                        onOptionsChanged(options.copy(draftShield = if (it) "enabled" else "disabled"))
                    },
                )
                if (options.skirtLoops > 0 || settingsQuery.isNotBlank()) {
                    SettingsSwitch(
                        label = stringResource(R.string.single_loop_after_first_layer),
                        checked = options.quality.singleLoopDraftShield,
                        enabled = options.skirtLoops > 0,
                        onCheckedChange = {
                            onOptionsChanged(
                                options.copy(
                                    quality = options.quality.copy(singleLoopDraftShield = it),
                                ),
                            )
                        },
                    )
                }
                SettingChoices(
                    settingLabel = stringResource(R.string.brim_type),
                    entries = listOf("auto_brim", "brim_ears", "outer_only", "inner_only", "outer_and_inner", "no_brim"),
                    selected = options.brimType,
                    optionLabel = {
                        stringResource(
                            when (it) {
                                "auto_brim" -> R.string.brim_auto
                                "brim_ears" -> R.string.brim_ears
                                "outer_only" -> R.string.brim_outer
                                "inner_only" -> R.string.brim_inner
                                "outer_and_inner" -> R.string.brim_both
                                else -> R.string.brim_none
                            },
                        )
                    },
                    onSelected = { onOptionsChanged(options.copy(brimType = it)) },
                )
                if (options.brimType != "no_brim" || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.brim_width),
                        valueText = stringResource(R.string.millimeters_value_precise, options.brimWidth),
                        value = options.brimWidth,
                        range = 0f..max(100f, options.brimWidth),
                        steps = (max(100f, options.brimWidth) / 0.5f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = { onOptionsChanged(options.copy(brimWidth = (it * 2f).roundToInt() / 2f)) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.brim_object_gap),
                        valueText = stringResource(R.string.millimeters_value_precise, options.brimObjectGap),
                        value = options.brimObjectGap,
                        range = 0f..max(2f, options.brimObjectGap),
                        steps = (max(2f, options.brimObjectGap) / 0.05f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = { onOptionsChanged(options.copy(brimObjectGap = (it * 20f).roundToInt() / 20f)) },
                    )
                    if (options.brimType == "brim_ears" || settingsQuery.isNotBlank()) {
                        SettingSlider(
                            label = stringResource(R.string.brim_ear_maximum_angle),
                            valueText = stringResource(
                                R.string.degrees_value,
                                options.precision.brimEars.maximumAngle,
                            ),
                            value = options.precision.brimEars.maximumAngle,
                            range = 0f..180f,
                            steps = 179,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        precision = options.precision.copy(
                                            brimEars = options.precision.brimEars.copy(
                                                maximumAngle = it.roundToInt().toFloat(),
                                            ),
                                        ),
                                    ),
                                )
                            },
                        )
                        SettingSlider(
                            label = stringResource(R.string.brim_ear_detection_radius),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.precision.brimEars.detectionRadius,
                            ),
                            value = options.precision.brimEars.detectionRadius,
                            range = 0f..max(20f, options.precision.brimEars.detectionRadius),
                            steps = (max(20f, options.precision.brimEars.detectionRadius) * 10f)
                                .roundToInt().coerceAtLeast(2) - 1,
                            enabled = options.brimWidth > 0f,
                            onValueChange = {
                                onOptionsChanged(
                                    options.copy(
                                        precision = options.precision.copy(
                                            brimEars = options.precision.brimEars.copy(
                                                detectionRadius = (it * 10f).roundToInt() / 10f,
                                            ),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
                SettingsGroupTitle(stringResource(R.string.raft))
                SettingSlider(
                    label = stringResource(R.string.raft_layers),
                    valueText = options.raftLayers.toString(),
                    value = options.raftLayers.toFloat(),
                    range = 0f..max(20, options.raftLayers).toFloat(),
                    steps = max(20, options.raftLayers).coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(raftLayers = it.roundToInt())) },
                )
                if (options.raftLayers > 0 || settingsQuery.isNotBlank()) {
                    SettingSlider(
                        label = stringResource(R.string.raft_contact_distance),
                        valueText = stringResource(R.string.millimeters_value_precise, options.raftContactDistance),
                        value = options.raftContactDistance,
                        range = 0f..max(2f, options.raftContactDistance),
                        steps = (max(2f, options.raftContactDistance) / 0.02f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = { onOptionsChanged(options.copy(raftContactDistance = (it * 50f).roundToInt() / 50f)) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.raft_expansion),
                        valueText = stringResource(R.string.millimeters_value_precise, options.raftExpansion),
                        value = options.raftExpansion,
                        range = 0f..max(20f, options.raftExpansion),
                        steps = (max(20f, options.raftExpansion) / 0.5f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = { onOptionsChanged(options.copy(raftExpansion = (it * 2f).roundToInt() / 2f)) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.raft_first_layer_density),
                        valueText = stringResource(R.string.percent_value, options.raftFirstLayerDensity.roundToInt()),
                        value = options.raftFirstLayerDensity,
                        range = 10f..100f,
                        steps = 89,
                        onValueChange = { onOptionsChanged(options.copy(raftFirstLayerDensity = it.roundToInt().toFloat())) },
                    )
                    SettingSlider(
                        label = stringResource(R.string.raft_first_layer_expansion),
                        valueText = stringResource(R.string.millimeters_value_precise, options.raftFirstLayerExpansion),
                        value = options.raftFirstLayerExpansion,
                        range = 0f..max(20f, options.raftFirstLayerExpansion),
                        steps = (max(20f, options.raftFirstLayerExpansion) / 0.5f).roundToInt().coerceAtLeast(2) - 1,
                        onValueChange = {
                            onOptionsChanged(options.copy(raftFirstLayerExpansion = (it * 2f).roundToInt() / 2f))
                        },
                    )
                }
            }
            }
        }
        SaveProfileField(onSave = { name -> onSave(name, options) }, onDismiss = onDismiss)
    }
    if (profilesOpen) {
        ProfileChooserSheet(
            entries = profiles,
            selected = options.quality,
            recentIds = recentIds,
            id = { it.id },
            name = { it.name },
            label = { profileLabel(it) },
            brand = { it.brand },
            builtIn = { it.builtIn },
            searchTerms = {
                listOf(it.name, it.brand.orEmpty(), it.layerHeightMm.toString())
            },
            onSelected = {
                onOptionsChanged(options.selectQuality(it))
                profilesOpen = false
            },
            onDismiss = { profilesOpen = false },
        )
    }
}

@Composable
private fun SupportFlowSettings(
    options: SliceOptions,
    showInterface: Boolean,
    onOptionsChanged: (SliceOptions) -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.feature_flow_ratio))
    SettingSlider(
        label = stringResource(R.string.support_flow_ratio),
        valueText = stringResource(R.string.flow_ratio_value, options.supportFlowRatio),
        value = options.supportFlowRatio,
        range = 0f..2f,
        steps = 199,
        onValueChange = {
            onOptionsChanged(options.copy(supportFlowRatio = (it * 100f).roundToInt() / 100f))
        },
    )
    if (showInterface) {
        SettingSlider(
            label = stringResource(R.string.support_interface_flow_ratio),
            valueText = stringResource(R.string.flow_ratio_value, options.supportInterfaceFlowRatio),
            value = options.supportInterfaceFlowRatio,
            range = 0f..2f,
            steps = 199,
            onValueChange = {
                onOptionsChanged(
                    options.copy(supportInterfaceFlowRatio = (it * 100f).roundToInt() / 100f),
                )
            },
        )
    }
}

@Composable
private fun SlicingSettingsTabs(
    selected: SlicingSettingsSection,
    onSelected: (SlicingSettingsSection) -> Unit,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = SlicingSettingsSection.entries.indexOf(selected),
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
        contentColor = Color(0xFFF6C945),
        divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.12f)) },
    ) {
        SlicingSettingsSection.entries.forEach { section ->
            Tab(
                selected = selected == section,
                onClick = { onSelected(section) },
                text = { Text(stringResource(section.titleResource)) },
                selectedContentColor = Color(0xFFF6C945),
                unselectedContentColor = Color(0xFFC8C9C2),
            )
        }
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    if (!settingMatchesQuery(title)) return
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
}

@Composable
internal fun SettingsSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun RotationTemplateSetting(
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
private fun SmallAreaFlowCompensationModelSetting(
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
                    if (valid) R.string.small_area_flow_compensation_model_hint
                    else R.string.small_area_flow_compensation_model_invalid,
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
private fun AdaptivePressureAdvanceModelSetting(
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
                    if (valid) R.string.adaptive_pressure_advance_model_hint
                    else R.string.adaptive_pressure_advance_model_invalid,
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
private fun SmallAreaFlowCompensationSettings(
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
private fun BedExcludeAreaSetting(
    value: List<Float>,
    bedSizeX: Float,
    bedSizeY: Float,
    onValueChange: (List<Float>) -> Unit,
) {
    val label = stringResource(R.string.bed_exclude_area)
    if (!settingMatchesQuery(label)) return
    var input by remember { mutableStateOf(formatBedExcludeArea(value)) }
    var lastApplied by remember { mutableStateOf(value) }
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
            if (candidate.length <= 4_096 && candidate.all {
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
                    if (parsed != null) R.string.bed_exclude_area_hint
                    else R.string.bed_exclude_area_invalid,
                ),
            )
        },
        isError = parsed == null,
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun parseBedExcludeArea(value: String, bedSizeX: Float, bedSizeY: Float): List<Float>? {
    if (value.isBlank()) return listOf(0f, 0f)
    val coordinates = value.split(';', '\n')
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
private fun GcodeTemplateSetting(
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
private fun FilenameFormatSetting(
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
private fun GcodeThumbnailSetting(
    value: String,
    onValueChange: (String) -> Unit,
) {
    val label = stringResource(R.string.gcode_thumbnails)
    if (!settingMatchesQuery(label)) return
    var input by remember { mutableStateOf(value) }
    var lastApplied by remember { mutableStateOf(value) }
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
        placeholder = { Text("48x48/PNG,300x300/PNG") },
        supportingText = {
            Text(
                stringResource(
                    if (valid) R.string.gcode_thumbnails_hint
                    else R.string.gcode_thumbnails_invalid,
                ),
            )
        },
        isError = !valid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SpiralVaseSettings(
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
            valueText = stringResource(
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
            valueText = stringResource(
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
private fun FilamentPrimeTowerInterfaceSettings(
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
        valueText = if (profile.towerInterfacePrintTemperature < 0) {
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
private fun PrimeTowerStructureSettings(
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
private fun PrimeTowerBrimChamferSettings(
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
            valueText = stringResource(
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
private fun PurgeMultiplierSettings(
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
private fun DecimalSettingField(
    label: String,
    value: Float,
    maximum: Float,
    suffix: String,
    onValueChange: (Float) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var input by remember { mutableStateOf(editableDecimal(value)) }
    var lastApplied by remember { mutableStateOf(value) }
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
                candidate.replace(',', '.').toFloatOrNull()
                    ?.takeIf { it.isFinite() && it in 0f..maximum }
                    ?.let { parsed ->
                        lastApplied = parsed
                        onValueChange(parsed)
                    }
            }
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = input.replace(',', '.').toFloatOrNull()?.let { it !in 0f..maximum } ?: true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun editableDecimal(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else value.toString()

private val DECIMAL_INPUT = Regex("[0-9]{0,7}([.,][0-9]{0,2})?")

@Composable
private fun CoordinatePairSettingField(
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
    var input by remember { mutableStateOf(editableCoordinatePair(valueX, valueY)) }
    var lastApplied by remember { mutableStateOf(valueX to valueY) }
    LaunchedEffect(valueX, valueY) {
        if (abs(valueX - lastApplied.first) >= 0.001f ||
            abs(valueY - lastApplied.second) >= 0.001f
        ) {
            input = editableCoordinatePair(valueX, valueY)
            lastApplied = valueX to valueY
        }
    }
    val parsed = parseCoordinatePair(input)?.takeIf { (x, y) ->
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
                    }
                    ?.let { (x, y) ->
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

private fun editableCoordinatePair(x: Float, y: Float): String =
    "${editableDecimal(x)}, ${editableDecimal(y)}"

internal fun parseCoordinatePair(value: String): Pair<Float, Float>? {
    val parts = value.split(',')
    if (parts.size != 2) return null
    val x = parts[0].trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    val y = parts[1].trim().toFloatOrNull()?.takeIf(Float::isFinite) ?: return null
    return x to y
}

private val COORDINATE_PAIR_INPUT = Regex(
    "-?[0-9]{0,6}(\\.[0-9]{0,3})?(\\s*,\\s*-?[0-9]{0,6}(\\.[0-9]{0,3})?)?",
)

@Composable
private fun IntegerSettingField(
    label: String,
    value: Int,
    maximum: Int,
    suffix: String,
    supportingText: String,
    onValueChange: (Int) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var input by remember { mutableStateOf(value.toString()) }
    var lastApplied by remember { mutableStateOf(value) }
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
                candidate.toIntOrNull()
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
private fun OverhangSpeedSetting(
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
        valueText = if (percent) {
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
private fun AccelerationOrPercentSetting(
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
        valueText = if (percent) {
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
private fun AccelerationSettings(
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
private fun LengthOrPercentSetting(
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
        valueText = if (percent) {
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
private fun <T> SettingChoices(
    settingLabel: String,
    entries: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelected: (T) -> Unit,
) {
    if (!settingMatchesQuery(settingLabel)) return
    Text(settingLabel, fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = entries,
        selected = selected,
        label = optionLabel,
        enabled = enabled,
        onSelected = onSelected,
    )
}

@Composable
private fun DirectionalPurgeSetting(
    filaments: List<FilamentProfile>,
    multiMaterial: MultiMaterialSettings,
    onChanged: (MultiMaterialSettings) -> Unit,
) {
    if (filaments.size < 2) return
    val purgeLabel = stringResource(R.string.purge_volume)
    val fromLabel = stringResource(R.string.from_filament)
    val toLabel = stringResource(R.string.to_filament)
    val query = LocalSettingsQuery.current
    if (listOf(purgeLabel, fromLabel, toLabel).none { settingQueryMatches(query, it) }) return

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
        FilamentSlotSetting(
            label = fromLabel,
            filaments = filaments,
            selectedSlot = fromSlot,
            onSelected = { selected ->
                fromSlot = selected
                if (toSlot == selected) toSlot = differentFrom(selected)
            },
        )
        FilamentSlotSetting(
            label = toLabel,
            filaments = filaments,
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
private fun FilamentSlotSetting(
    label: String,
    filaments: List<FilamentProfile>,
    selectedSlot: Int,
    defaultLabel: String? = null,
    onSelected: (Int) -> Unit,
) {
    if (!settingMatchesQuery(label)) return
    var pickerOpen by remember { mutableStateOf(false) }
    val boundedSlot = selectedSlot.coerceIn(if (defaultLabel == null) 1 else 0, filaments.size)
    val valueLabel = if (boundedSlot == 0) {
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
                    color = filamentSlotColor(boundedSlot - 1),
                    shape = MaterialTheme.shapes.extraSmall,
                ) {}
            }
            Column(
                modifier = Modifier
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
                modifier = Modifier
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
                        label = stringResource(
                            R.string.filament_tool_summary,
                            slot,
                            profileLabel(filament),
                        ),
                        color = filamentSlotColor(index),
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
private fun FilamentSlotChoice(
    label: String,
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = color?.let {
                RadioButtonDefaults.colors(selectedColor = it, unselectedColor = it.copy(alpha = 0.72f))
            } ?: RadioButtonDefaults.colors(),
        )
        Text(label)
    }
}

@Composable
private fun <T> CompactChoices(
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
                modifier = Modifier
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
private fun LockedZagInfillSettings(
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
private fun LateralInfillGeometrySettings(
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
private fun fillPatternLabel(value: String): String = when (value) {
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

private fun enumLabel(value: String): String = value
    .replace('_', ' ')
    .replace('-', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

@Composable
private fun CurrentProfileButton(
    profile: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.profile_list), fontWeight = FontWeight.SemiBold)
            Text(
                profile,
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> ProfileChooserSheet(
    entries: List<T>,
    selected: T,
    recentIds: List<String>,
    id: (T) -> String,
    name: (T) -> String,
    label: @Composable (T) -> String,
    brand: (T) -> String?,
    builtIn: (T) -> Boolean,
    searchTerms: (T) -> List<String>,
    onSelected: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    } * 0.88f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.profile_list),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            SearchableGroupedProfileChoices(
                entries = entries,
                selected = selected,
                recentIds = recentIds,
                id = id,
                name = name,
                label = label,
                brand = brand,
                builtIn = builtIn,
                searchTerms = searchTerms,
                onSelected = onSelected,
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsSheet(
    title: String,
    onDismiss: () -> Unit,
    scrollKey: Any? = null,
    dirty: Boolean,
    onRevert: () -> Unit,
    onApply: () -> Unit,
    settingQuery: String,
    onSettingQueryChanged: (String) -> Unit,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val sheetHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    } * 0.92f
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(scrollKey) { scrollState.scrollTo(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                header()
                OutlinedTextField(
                    value = settingQuery,
                    onValueChange = onSettingQueryChanged,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.search_settings)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CompositionLocalProvider(
                    LocalSettingsQuery provides settingQuery.trim().lowercase(Locale.ROOT),
                ) {
                    content()
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Text(stringResource(R.string.done))
                }
            }
            if (dirty) {
                ProfileDirtyActionBar(onRevert = onRevert, onApply = onApply)
            }
        }
    }
}

@Composable
private fun ProfileDirtyActionBar(
    onRevert: () -> Unit,
    onApply: () -> Unit,
) {
    Surface(
        color = Color(0xFF20211F),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onRevert,
                modifier = Modifier.weight(3f),
            ) {
                Text(stringResource(R.string.revert_changes))
            }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(7f),
            ) {
                Text(stringResource(R.string.apply_changes))
            }
        }
    }
}

private data class ProfileChoiceGroup<T>(
    val key: String,
    val title: String,
    val entries: List<T>,
)

internal fun List<String>.matchesPrinter(printer: PrinterProfile): Boolean =
    isEmpty() || printer.name in this

internal fun <T> deduplicateProfileChoices(
    entries: List<T>,
    selected: T,
    id: (T) -> String,
    name: (T) -> String,
    brand: (T) -> String?,
    builtIn: (T) -> Boolean,
): List<T> {
    val choices = LinkedHashMap<String, T>(entries.size)
    entries.forEach { entry ->
        val key = if (builtIn(entry)) {
            "built-in:${brand(entry).orEmpty().trim().lowercase(Locale.ROOT)}:" +
                name(entry).trim().lowercase(Locale.ROOT)
        } else {
            "user:${id(entry)}"
        }
        val existing = choices[key]
        choices[key] = when {
            existing == null -> entry
            existing == selected -> existing
            entry == selected -> entry
            else -> entry // Generated catalog entries follow the small fallback catalog.
        }
    }
    return choices.values.toList()
}

@Composable
internal fun <T> SearchableGroupedProfileChoices(
    entries: List<T>,
    selected: T,
    recentIds: List<String>,
    id: (T) -> String,
    name: (T) -> String,
    label: @Composable (T) -> String,
    brand: (T) -> String?,
    builtIn: (T) -> Boolean,
    searchTerms: (T) -> List<String>,
    onSelected: (T) -> Unit,
) {
    val myProfiles = stringResource(R.string.my_profiles)
    val otherProfiles = stringResource(R.string.other_profiles)
    val recentProfiles = stringResource(R.string.recent_profiles)
    val myProfilesKey = "user-profiles"
    val recentProfilesKey = "recent-profiles"
    val uniqueEntries = remember(entries, selected) {
        deduplicateProfileChoices(entries, selected, id, name, brand, builtIn)
    }
    var query by remember { mutableStateOf("") }
    val selectedGroupKey = if (builtIn(selected)) {
        "brand:${brand(selected).orEmpty()}"
    } else {
        myProfilesKey
    }
    var expandedGroups by remember(uniqueEntries, recentIds) {
        mutableStateOf(setOf(recentProfilesKey, selectedGroupKey))
    }
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val matchingEntries = if (normalizedQuery.isBlank()) {
        uniqueEntries
    } else {
        uniqueEntries.filter { entry ->
            searchTerms(entry).any { value -> value.lowercase(Locale.ROOT).contains(normalizedQuery) }
        }
    }
    val groupedProfiles = matchingEntries
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
    val recentEntries = recentIds.mapNotNull { recentId ->
        matchingEntries.firstOrNull { id(it) == recentId }
    }
    val groups = buildList {
        if (recentEntries.isNotEmpty()) {
            add(ProfileChoiceGroup(recentProfilesKey, recentProfiles, recentEntries))
        }
        addAll(groupedProfiles)
    }

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
            val groupState = stringResource(
                if (expanded) R.string.expanded_state else R.string.collapsed_state,
            )
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            enabled = normalizedQuery.isBlank(),
                            role = Role.Button,
                        ) {
                            expandedGroups = if (expanded) {
                                expandedGroups - group.key
                            } else {
                                expandedGroups + group.key
                            }
                        }
                        .semantics { stateDescription = groupState }
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
                        val selectedEntry = entry == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selectedEntry,
                                    role = Role.RadioButton,
                                    onClick = { onSelected(entry) },
                                )
                                .padding(start = 18.dp, top = 1.dp, bottom = 1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedEntry,
                                onClick = null,
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
    if (LocalSettingsQuery.current.isNotBlank()) return
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
private fun QuantizedSettingSlider(
    label: String,
    valueText: String,
    value: Float,
    minimum: Float,
    defaultMaximum: Float,
    increment: Float,
    onValueChange: (Float) -> Unit,
) {
    val maximum = max(defaultMaximum, value).coerceAtLeast(minimum + increment)
    val steps = (((maximum - minimum) / increment).roundToInt() - 1).coerceIn(0, 999)
    SettingSlider(
        label = label,
        valueText = valueText,
        value = value,
        range = minimum..maximum,
        steps = steps,
        onValueChange = { rawValue ->
            onValueChange(
                ((rawValue / increment).roundToInt() * increment).coerceIn(minimum, maximum),
            )
        },
    )
}

@Composable
internal fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    if (!settingMatchesQuery(label)) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(valueText, color = Color(0xFFC8C9C2))
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = valueText
            },
            valueRange = range,
            steps = steps,
            colors = duckySliderColors(),
        )
    }
}

@Composable
internal fun profileLabel(profile: PrinterProfile) = when (profile.id) {
    PrinterProfile.U1_04.id -> stringResource(R.string.printer_u1_04)
    PrinterProfile.U1_06.id -> stringResource(R.string.printer_u1_06)
    else -> profile.name
}

@Composable
internal fun profileLabel(profile: FilamentProfile) = when (profile.id) {
    FilamentProfile.PLA.id -> stringResource(R.string.filament_snapmaker_pla)
    FilamentProfile.PETG.id -> stringResource(R.string.filament_snapmaker_petg)
    FilamentProfile.ABS.id -> stringResource(R.string.filament_snapmaker_abs)
    else -> profile.name
}

@Composable
internal fun profileLabel(profile: QualityProfile) = when (profile.id) {
    QualityProfile.DRAFT.id -> stringResource(R.string.quality_draft)
    QualityProfile.STANDARD.id -> stringResource(R.string.quality_standard)
    QualityProfile.FINE.id -> stringResource(R.string.quality_fine)
    QualityProfile.DRAFT_06.id -> stringResource(R.string.quality_draft_06)
    QualityProfile.STANDARD_06.id -> stringResource(R.string.quality_standard_06)
    QualityProfile.FINE_06.id -> stringResource(R.string.quality_fine_06)
    else -> profile.name
}

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
                var updated = activeEditor.session.working.selectPrinter(printer)
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
                updateEditor(updated)
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
    val resolvedRetraction = activeProfile.resolveRetraction(options.printerProfile)
    val inheritsPrinterRetraction = activeProfile.retractLength == null &&
        activeProfile.retractSpeed == null && activeProfile.deretractSpeed == null &&
        activeProfile.retractionMinimumTravel == null && activeProfile.retractWhenChangingLayer == null &&
        activeProfile.wipeWhileRetracting == null && activeProfile.wipeDistance == null &&
        activeProfile.retractBeforeWipe == null && activeProfile.retractRestartExtra == null &&
        activeProfile.zHop == null && activeProfile.zHopType == null
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
            label = stringResource(R.string.bed_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.bedTemp),
            value = activeProfile.bedTemp.toFloat(),
            range = 0f..120f,
            steps = 119,
            onValueChange = {
                onOptionsChanged(options.updateFilamentSlot(selectedSlot, activeProfile.copy(bedTemp = it.roundToInt())))
            },
        )
        SettingSlider(
            label = stringResource(R.string.first_layer_bed_temperature),
            valueText = stringResource(R.string.celsius_value, activeProfile.firstLayerBedTemp),
            value = activeProfile.firstLayerBedTemp.toFloat(),
            range = 0f..120f,
            steps = 119,
            onValueChange = {
                onOptionsChanged(
                    options.updateFilamentSlot(
                        selectedSlot,
                        activeProfile.copy(firstLayerBedTemp = it.roundToInt()),
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
        SettingsGroupTitle(stringResource(R.string.cooling))
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
                        activeProfile.copy(pressureAdvanceEnabled = it),
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
        }
        SaveProfileField(
            onSave = { name -> onSave(name, options, selectedSlot) },
            onDismiss = onDismiss,
        )
    }
    if (profilesOpen) {
        ProfileChooserSheet(
            entries = profiles,
            selected = activeProfile,
            recentIds = recentIds,
            id = { it.id },
            name = { it.name },
            label = { profileLabel(it) },
            brand = { it.brand },
            builtIn = { it.builtIn },
            searchTerms = { listOf(it.name, it.brand.orEmpty(), it.nativeName) },
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
                SettingsSwitch(
                    label = stringResource(R.string.arc_fitting),
                    checked = options.gcodeSettings.arcFitting,
                    onCheckedChange = {
                        onOptionsChanged(
                            options.copy(
                                gcodeSettings = options.gcodeSettings.copy(arcFitting = it),
                            ),
                        )
                    },
                )
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
                SettingChoices(
                    settingLabel = stringResource(R.string.sparse_infill_pattern),
                    entries = listOf(
                        "crosshatch", "grid", "rectilinear", "gyroid", "cubic",
                        "alignedrectilinear", "triangles", "lightning",
                    ),
                    selected = options.fillPattern,
                    optionLabel = { fillPatternLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(fillPattern = it)) },
                )
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
                SettingSlider(
                    label = stringResource(R.string.sparse_infill_direction),
                    valueText = stringResource(R.string.degrees_value, options.infillDirection),
                    value = options.infillDirection,
                    range = 0f..360f,
                    steps = 359,
                    onValueChange = { onOptionsChanged(options.copy(infillDirection = it.roundToInt().toFloat())) },
                )
                SettingSlider(
                    label = stringResource(R.string.solid_infill_direction),
                    valueText = stringResource(R.string.degrees_value, options.solidInfillDirection),
                    value = options.solidInfillDirection,
                    range = 0f..360f,
                    steps = 359,
                    onValueChange = { onOptionsChanged(options.copy(solidInfillDirection = it.roundToInt().toFloat())) },
                )
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
                SettingSlider(
                    label = stringResource(R.string.maximum_unsupported_bridge_length),
                    valueText = stringResource(R.string.millimeters_value, options.maxBridgeLength),
                    value = options.maxBridgeLength,
                    range = 0f..max(100f, options.maxBridgeLength),
                    steps = max(100f, options.maxBridgeLength).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(maxBridgeLength = it.roundToInt().toFloat())) },
                )
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
                SettingsSwitch(
                    label = stringResource(R.string.do_not_support_bridges),
                    checked = options.bridgeNoSupport,
                    onCheckedChange = { onOptionsChanged(options.copy(bridgeNoSupport = it)) },
                )
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
                    selected = options.ironingType,
                    optionLabel = { enumLabel(it) },
                    onSelected = { onOptionsChanged(options.copy(ironingType = it)) },
                )
                if (options.ironingType != "no ironing" || settingsQuery.isNotBlank()) {
                    SettingChoices(
                        settingLabel = stringResource(R.string.ironing_pattern),
                        entries = listOf("rectilinear", "concentric"),
                        selected = options.ironingPattern,
                        optionLabel = { fillPatternLabel(it) },
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
                if (options.supportEnabled || settingsQuery.isNotBlank()) {
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
                        onSelected = { onOptionsChanged(options.copy(supportType = it)) },
                    )
                    if (options.supportType.isTreeSupportType() || settingsQuery.isNotBlank()) {
                        SettingsGroupTitle(stringResource(R.string.tree_support))
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
                        SettingSlider(
                            label = stringResource(R.string.tree_support_tip_diameter),
                            valueText = stringResource(
                                R.string.millimeters_value_precise,
                                options.treeSupportTipDiameter,
                            ),
                            value = options.treeSupportTipDiameter,
                            range = 0.1f..max(10f, options.treeSupportTipDiameter),
                            steps = ((max(10f, options.treeSupportTipDiameter) - 0.1f) * 10f)
                                .roundToInt() - 1,
                            onValueChange = {
                                val tipDiameter = (it * 10f).roundToInt() / 10f
                                onOptionsChanged(
                                    options.copy(
                                        treeSupportTipDiameter = tipDiameter,
                                        treeSupportOrganicBranchDiameter = max(
                                            options.treeSupportOrganicBranchDiameter,
                                            tipDiameter,
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
                            value = options.treeSupportOrganicBranchDiameter,
                            range = options.treeSupportTipDiameter.coerceIn(1f, 10f)..10f,
                            steps = (
                                ((10f - options.treeSupportTipDiameter.coerceIn(1f, 10f)) * 10f)
                                    .roundToInt() - 1
                                ).coerceAtLeast(0),
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
                        if (!options.treeSupportAutoBrim || settingsQuery.isNotBlank()) {
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
                    SettingChoices(
                        settingLabel = stringResource(R.string.support_style),
                        entries = listOf("default", "grid", "snug", "organic", "tree_hybrid", "tree_slim"),
                        selected = options.supportStyle,
                        optionLabel = { enumLabel(it) },
                        onSelected = { onOptionsChanged(options.copy(supportStyle = it)) },
                    )
                    SettingsSwitch(
                        label = stringResource(R.string.support_on_build_plate_only),
                        checked = options.supportOnBuildPlateOnly,
                        onCheckedChange = {
                            onOptionsChanged(options.copy(supportOnBuildPlateOnly = it))
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
                    SettingsSwitch(
                        label = stringResource(R.string.support_interface_loop_pattern),
                        checked = options.supportInterfaceLoopPattern,
                        onCheckedChange = {
                            onOptionsChanged(options.copy(supportInterfaceLoopPattern = it))
                        },
                    )
                    SettingsGroupTitle(stringResource(R.string.support_filament_routing))
                    SettingSlider(
                        label = stringResource(R.string.support_filament),
                        valueText = if (options.supportFilament == 0) {
                            stringResource(R.string.filament_default)
                        } else {
                            stringResource(R.string.extruder_number, options.supportFilament)
                        },
                        value = options.supportFilament.toFloat().coerceAtMost(maximumFilamentSlot.toFloat()),
                        range = 0f..maximumFilamentSlot.toFloat(),
                        steps = (maximumFilamentSlot - 1).coerceAtLeast(0),
                        onValueChange = {
                            onOptionsChanged(options.copy(supportFilament = it.roundToInt()))
                        },
                    )
                    SettingSlider(
                        label = stringResource(R.string.support_interface_filament),
                        valueText = if (options.supportInterfaceFilament == 0) {
                            stringResource(R.string.filament_default)
                        } else {
                            stringResource(R.string.extruder_number, options.supportInterfaceFilament)
                        },
                        value = options.supportInterfaceFilament.toFloat()
                            .coerceAtMost(maximumFilamentSlot.toFloat()),
                        range = 0f..maximumFilamentSlot.toFloat(),
                        steps = (maximumFilamentSlot - 1).coerceAtLeast(0),
                        onValueChange = {
                            onOptionsChanged(options.copy(supportInterfaceFilament = it.roundToInt()))
                        },
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
            }

            SlicingSettingsSection.OTHERS -> {
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
                    onSelected = { onOptionsChanged(options.copy(printSequence = it)) },
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
                SettingsGroupTitle(stringResource(R.string.spiral_vase))
                SettingsSwitch(
                    label = stringResource(R.string.spiral_vase),
                    checked = options.spiralMode,
                    onCheckedChange = { enabled ->
                        onOptionsChanged(options.withSpiralMode(enabled))
                    },
                )
                if (options.spiralMode || settingsQuery.isNotBlank()) {
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
                    valueText = stringResource(R.string.millimeters_value_precise, options.skirtDistance),
                    value = options.skirtDistance,
                    range = 0f..max(60f, options.skirtDistance),
                    steps = (max(60f, options.skirtDistance) / 0.5f).roundToInt().coerceAtLeast(2) - 1,
                    onValueChange = { onOptionsChanged(options.copy(skirtDistance = (it * 2f).roundToInt() / 2f)) },
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
internal fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (!settingMatchesQuery(label)) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
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
        Switch(checked = checked, onCheckedChange = null)
    }
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
    onSelected: (T) -> Unit,
) {
    if (!settingMatchesQuery(settingLabel)) return
    Text(settingLabel, fontWeight = FontWeight.SemiBold)
    CompactChoices(
        entries = entries,
        selected = selected,
        label = optionLabel,
        onSelected = onSelected,
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
            val selectedEntry = entry == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selectedEntry,
                        role = Role.RadioButton,
                        onClick = { onSelected(entry) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selectedEntry, onClick = null)
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
internal fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
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

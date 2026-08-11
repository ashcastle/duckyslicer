package com.ashcastle.duckyslicer

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AppSettingsSheet(
    settings: AppSettings,
    saveFailed: Boolean,
    supportReportExportState: SupportReportExportState,
    onSettingsChanged: (AppSettings) -> Unit,
    onSupportReportExport: (Uri) -> Unit,
    onCancelSupportReportExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val toolpathVisibilityLabel = stringResource(R.string.toolpath_visibility_control)
    val toolpathVisibilityState = stringResource(
        R.string.percent_value,
        (settings.toolpathOpacity * 100).roundToInt(),
    )
    val toolpathDepthLabel = stringResource(R.string.toolpath_depth_contrast_control)
    val toolpathDepthState = stringResource(
        R.string.percent_value,
        (settings.toolpathDepthContrast * 100).roundToInt(),
    )
    val connectionTimeoutLabel = stringResource(R.string.connection_timeout_control)
    val connectionTimeoutState = stringResource(
        R.string.seconds_value,
        settings.connectionTimeoutSeconds.toFloat(),
    )
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var showDataPractices by remember { mutableStateOf(false) }
    var notificationsEnabled by remember(context) {
        mutableStateOf(sliceNotificationsEnabled(context))
    }
    val notificationState = stringResource(
        if (notificationsEnabled) {
            R.string.slice_notifications_on
        } else {
            R.string.slice_notifications_off
        },
    )
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = sliceNotificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val supportReportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let(onSupportReportExport)
    }

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
            SettingsHeading(stringResource(R.string.settings), primary = true)
            if (saveFailed) {
                Text(
                    stringResource(R.string.settings_save_error),
                    color = Color(0xFFFF8A80),
                )
            }

            SettingsHeading(stringResource(R.string.preview_settings))
            Text(stringResource(R.string.preview_renderer), color = Color(0xFFC8C9C2))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewRenderingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.previewRenderingMode == mode,
                        onClick = { onSettingsChanged(settings.copy(previewRenderingMode = mode)) },
                        label = {
                            Text(
                                stringResource(
                                    if (mode == PreviewRenderingMode.DEPTH_TESTED) {
                                        R.string.preview_renderer_depth
                                    } else {
                                        R.string.preview_renderer_compatibility
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
            Text(stringResource(R.string.preview_detail), color = Color(0xFFC8C9C2))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PreviewDetail.entries.forEach { detail ->
                    FilterChip(
                        selected = settings.previewDetail == detail,
                        onClick = { onSettingsChanged(settings.copy(previewDetail = detail)) },
                        label = {
                            Text(
                                stringResource(
                                    when (detail) {
                                        PreviewDetail.AUTOMATIC -> R.string.preview_detail_automatic
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
                modifier = Modifier.semantics {
                    contentDescription = toolpathVisibilityLabel
                    stateDescription = toolpathVisibilityState
                },
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
                modifier = Modifier.semantics {
                    contentDescription = toolpathDepthLabel
                    stateDescription = toolpathDepthState
                },
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

            SettingsHeading(stringResource(R.string.connection_settings))
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
                modifier = Modifier.semantics {
                    contentDescription = connectionTimeoutLabel
                    stateDescription = connectionTimeoutState
                },
                valueRange = 5f..60f,
                steps = 10,
                colors = duckySliderColors(),
            )
            Text(stringResource(R.string.connection_security_summary), color = Color(0xFFC8C9C2))

            SettingsHeading(stringResource(R.string.background_slicing_title))
            Text(
                notificationState,
                color = if (notificationsEnabled) Color(0xFF9FE2A2) else Color(0xFFC8C9C2),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.slice_notifications_summary),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { openSliceNotificationSettings(context) },
                modifier = Modifier.semantics { stateDescription = notificationState },
            ) {
                Text(stringResource(R.string.manage_slice_notifications))
            }

            SettingsHeading(stringResource(R.string.language_settings))
            Text(stringResource(R.string.settings_message), color = Color(0xFFC8C9C2))

            SettingsHeading(stringResource(R.string.data_privacy))
            Text(
                stringResource(R.string.data_privacy_summary),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { showDataPractices = true }) {
                Text(stringResource(R.string.data_handling_details))
            }
            TextButton(onClick = { legalDocument = LegalDocument.PRIVACY }) {
                Text(stringResource(R.string.privacy_policy))
            }

            SettingsHeading(stringResource(R.string.help))
            Text(
                stringResource(R.string.support_details_summary),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                enabled = !supportReportExportState.cancellationRequested,
                onClick = {
                    if (supportReportExportState.busy) {
                        onCancelSupportReportExport()
                    } else {
                        supportReportLauncher.launch(SUPPORT_REPORT_FILE_NAME)
                    }
                },
            ) {
                if (supportReportExportState.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    stringResource(
                        when {
                            supportReportExportState.cancellationRequested ->
                                R.string.stopping_support_details_save
                            supportReportExportState.busy -> R.string.stop_support_details_save
                            else -> R.string.save_support_details
                        },
                    ),
                )
            }
            supportReportExportState.completion?.outcome?.let { outcome ->
                Text(
                    stringResource(
                        when (outcome) {
                            SupportReportExportOutcome.SAVED -> R.string.support_details_saved
                            SupportReportExportOutcome.CANCELED ->
                                R.string.support_details_save_canceled
                            SupportReportExportOutcome.FAILED ->
                                R.string.support_details_save_error
                        },
                    ),
                    color = when (outcome) {
                        SupportReportExportOutcome.SAVED -> Color(0xFF9FE2A2)
                        SupportReportExportOutcome.CANCELED -> Color(0xFFC8C9C2)
                        SupportReportExportOutcome.FAILED -> MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SettingsHeading(stringResource(R.string.about))
            Text(
                stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.open_source_summary),
                color = Color(0xFFC8C9C2),
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(
                    SOURCE_CODE_URL,
                    color = Color(0xFFF6C945),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { legalDocument = LegalDocument.LICENSE }) {
                    Text(stringResource(R.string.open_source_license))
                }
                TextButton(onClick = { legalDocument = LegalDocument.THIRD_PARTY }) {
                    Text(stringResource(R.string.third_party_notices))
                }
            }
            TextButton(onClick = { openSourceRepository(context) }) {
                Text(stringResource(R.string.view_source_code))
            }
        }
    }

    legalDocument?.let { document ->
        val content by produceState<LegalDocumentContent>(
            initialValue = LegalDocumentContent.Loading,
            key1 = document,
            key2 = context.applicationContext,
        ) {
            value = try {
                val chunks = withContext(Dispatchers.IO) {
                    val loadContext = currentCoroutineContext()
                    context.assets.open(document.assetPath).use { input ->
                        legalTextChunks(
                            input,
                            cancellationRequested = { !loadContext.isActive },
                        )
                    }
                }
                LegalDocumentContent.Ready(chunks)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                LegalDocumentContent.Failed
            }
        }
        AlertDialog(
            onDismissRequest = { legalDocument = null },
            title = { Text(stringResource(document.titleResource)) },
            text = {
                when (val current = content) {
                    LegalDocumentContent.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.opening_legal_document))
                    }
                    LegalDocumentContent.Failed -> Text(
                        stringResource(R.string.legal_document_open_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is LegalDocumentContent.Ready -> {
                        LazyColumn(Modifier.heightIn(max = 480.dp)) {
                            itemsIndexed(current.chunks, key = { index, _ -> index }) { _, chunk ->
                                SelectionContainer {
                                    Text(chunk, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { legalDocument = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (showDataPractices) {
        DataPracticesDialog(onDismiss = { showDataPractices = false })
    }
}

@Composable
private fun DataPracticesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.data_handling_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DataPracticesSection(
                    title = stringResource(R.string.data_stored_title),
                    body = stringResource(R.string.data_stored_body),
                )
                DataPracticesSection(
                    title = stringResource(R.string.background_slicing_title),
                    body = stringResource(R.string.background_slicing_body),
                )
                DataPracticesSection(
                    title = stringResource(R.string.printer_connection_data_title),
                    body = stringResource(R.string.printer_connection_data_body),
                )
                DataPracticesSection(
                    title = stringResource(R.string.no_tracking_title),
                    body = stringResource(R.string.no_tracking_body),
                )
                DataPracticesSection(
                    title = stringResource(R.string.removing_data_title),
                    body = stringResource(R.string.removing_data_body),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun DataPracticesSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            body,
            color = Color(0xFFC8C9C2),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private enum class LegalDocument(
    val assetPath: String,
    val titleResource: Int,
) {
    PRIVACY("legal/PRIVACY.md", R.string.privacy_policy),
    LICENSE("legal/AGPL-3.0.txt", R.string.open_source_license),
    THIRD_PARTY("legal/THIRD_PARTY_LICENSES.txt", R.string.third_party_notices),
}

private sealed interface LegalDocumentContent {
    data object Loading : LegalDocumentContent
    data class Ready(val chunks: List<String>) : LegalDocumentContent
    data object Failed : LegalDocumentContent
}

private fun openSourceRepository(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_CODE_URL)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: ActivityNotFoundException) {
        // The selectable address remains available on devices without a browser.
    }
}

internal fun sliceNotificationsEnabled(context: Context): Boolean {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!manager.areNotificationsEnabled()) return false
    return manager.getNotificationChannel(SlicerProcessService.NOTIFICATION_CHANNEL_ID)
        ?.importance != NotificationManager.IMPORTANCE_NONE
}

internal fun sliceNotificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun openSliceNotificationSettings(context: Context) {
    try {
        context.startActivity(sliceNotificationSettingsIntent(context))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private const val SOURCE_CODE_URL = "https://github.com/ashcastle/duckyslicer"
private const val SUPPORT_REPORT_FILE_NAME = "DuckySlicer-support.txt"

internal fun legalTextChunks(
    input: InputStream,
    maximumCharacters: Int = 12_000,
    maximumBytes: Int = MAX_LEGAL_DOCUMENT_BYTES,
    cancellationRequested: () -> Boolean = { false },
): List<String> {
    require(maximumCharacters > 0)
    require(maximumBytes > 0)
    val reader = BoundedLegalInputStream(input, maximumBytes).reader(Charsets.UTF_8)
    val buffer = CharArray(minOf(8 * 1_024, maximumCharacters))
    val pending = StringBuilder(maximumCharacters)
    val chunks = ArrayList<String>()
    while (true) {
        if (cancellationRequested()) throw CancellationException("legal_document_canceled")
        val count = reader.read(buffer)
        if (count < 0) break
        if (cancellationRequested()) throw CancellationException("legal_document_canceled")
        var offset = 0
        while (offset < count) {
            val copyCount = minOf(maximumCharacters - pending.length, count - offset)
            pending.append(buffer, offset, copyCount)
            offset += copyCount
            if (pending.length == maximumCharacters) {
                chunks += pending.toString()
                pending.clear()
            }
        }
    }
    if (pending.isNotEmpty() || chunks.isEmpty()) chunks += pending.toString()
    return chunks
}

private class BoundedLegalInputStream(
    input: InputStream,
    private val maximumBytes: Int,
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) recordBytes(1)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesBeforeOverflow = maximumBytes.toLong() - bytesRead + 1L
        val boundedLength = minOf(length.toLong(), bytesBeforeOverflow).toInt()
        return super.read(buffer, offset, boundedLength).also { count ->
            if (count > 0) recordBytes(count)
        }
    }

    private fun recordBytes(count: Int) {
        bytesRead += count
        require(bytesRead <= maximumBytes.toLong()) { "legal_document_too_large" }
    }
}

private const val MAX_LEGAL_DOCUMENT_BYTES = 4 * 1_024 * 1_024

@Composable
private fun SettingsToggle(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
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
                contentDescription = title
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(summary, color = Color(0xFFC8C9C2), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsHeading(title: String, primary: Boolean = false) {
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = if (primary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
    )
}

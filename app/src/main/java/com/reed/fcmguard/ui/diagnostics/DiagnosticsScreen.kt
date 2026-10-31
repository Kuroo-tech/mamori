package com.reed.fcmguard.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reed.fcmguard.R
import com.reed.fcmguard.data.DeviceStatus
import com.reed.fcmguard.ui.components.KeyValueRow
import com.reed.fcmguard.ui.components.PackageChips
import com.reed.fcmguard.ui.components.SectionCard
import com.reed.fcmguard.ui.components.StatusLine
import com.reed.fcmguard.ui.components.StatusTone
import com.reed.fcmguard.ui.components.formatTime
import kotlinx.coroutines.launch

/**
 * Diagnostics.
 *
 * The screen is read-only by design: it explains the execution mechanism, shows the live
 * permission and setting state, lists stored backups and operation records, and displays
 * the log. The only mutations are log maintenance (share/clear), which touch nothing but
 * the log itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    onShareLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DiagnosticsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            SectionCard(
                title = stringResource(R.string.diagnostics_title),
                subtitle = stringResource(R.string.diagnostics_summary)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::refresh) {
                        Text(stringResource(R.string.diagnostics_refresh))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch { onShareLog(viewModel.exportText()) }
                        }
                    ) {
                        Text(stringResource(R.string.diagnostics_share_log))
                    }
                    OutlinedButton(onClick = viewModel::clearLog) {
                        Text(stringResource(R.string.diagnostics_clear_log))
                    }
                }
            }
        }

        val data = state.data
        item(key = "execution") {
            SectionCard(title = stringResource(R.string.diagnostics_section_execution)) {
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_backend),
                    value = stringResource(R.string.diagnostics_backend_value)
                )
                if (data != null) {
                    ExecutionLines(data.status)
                } else {
                    Text(
                        text = stringResource(R.string.status_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (data != null) {
            item(key = "configuration") {
                SectionCard(title = stringResource(R.string.diagnostics_section_configuration)) {
                    KeyValueRow(
                        label = stringResource(R.string.label_key),
                        value = data.status.key
                    )
                    KeyValueRow(
                        label = stringResource(R.string.label_item),
                        value = data.status.requiredItem
                    )
                    PackageChips(
                        whitelist = data.status.whitelist,
                        requiredItem = data.status.requiredItem,
                        ownPackage = ""
                    )
                }
            }

            item(key = "storage") {
                SectionCard(title = stringResource(R.string.diagnostics_section_storage)) {
                    KeyValueRow(
                        label = stringResource(R.string.diagnostics_snapshots),
                        value = data.snapshots.size.toString()
                    )
                    KeyValueRow(
                        label = stringResource(R.string.diagnostics_history_records),
                        value = data.historyCount.toString()
                    )
                    val latest = data.snapshots.firstOrNull()
                    KeyValueRow(
                        label = stringResource(R.string.diagnostics_latest_backup),
                        value = if (latest == null) {
                            stringResource(R.string.diagnostics_none)
                        } else {
                            stringResource(
                                R.string.diagnostics_last_backup,
                                formatTime(latest.createdAt),
                                latest.value.split(',').count { it.isNotBlank() }
                            )
                        }
                    )
                    KeyValueRow(
                        label = stringResource(R.string.diagnostics_log_file),
                        value = stringResource(R.string.diagnostics_persisted_lines, state.persistedLines)
                    )
                }
            }
        }

        item(key = "device") {
            SectionCard(title = stringResource(R.string.diagnostics_section_device)) {
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_manufacturer),
                    value = state.device.manufacturer.ifBlank { "—" }
                )
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_model),
                    value = state.device.model.ifBlank { "—" }
                )
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_android),
                    value = "${state.device.androidRelease} (API ${state.device.sdkInt})"
                )
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_build),
                    value = state.device.buildDisplay.ifBlank { "—" }
                )
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_security_center),
                    value = stringResource(
                        if (state.device.securityCenterInstalled) {
                            R.string.diagnostics_installed
                        } else {
                            R.string.diagnostics_not_installed
                        }
                    )
                )
                KeyValueRow(
                    label = stringResource(R.string.diagnostics_app_version),
                    value = state.device.appVersion.ifBlank { "—" }
                )
            }
        }

        item(key = "log") {
            SectionCard(
                title = stringResource(R.string.diagnostics_section_log),
                subtitle = stringResource(R.string.diagnostics_log_note)
            ) {
                val visible = state.log.takeLast(LOG_LINES_SHOWN)
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.diagnostics_log_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        SelectionContainer {
                            Text(
                                text = visible.joinToString("\n") { it.format() },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionLines(status: DeviceStatus) {
    StatusLine(
        text = if (status.canWrite) {
            stringResource(R.string.status_granted)
        } else {
            stringResource(R.string.status_not_granted)
        },
        tone = if (status.canWrite) StatusTone.OK else StatusTone.ERROR
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    StatusLine(
        text = if (status.protectionEnabled) {
            stringResource(R.string.status_enabled)
        } else {
            stringResource(R.string.status_disabled)
        },
        tone = if (status.protectionEnabled) StatusTone.OK else StatusTone.WARN
    )
    StatusLine(
        text = if (status.notificationVisible) {
            stringResource(R.string.notification_mode_foreground)
        } else {
            stringResource(R.string.notification_mode_quiet)
        },
        tone = if (status.notificationVisible) StatusTone.OK else StatusTone.WARN
    )
    if (status.pendingInterrupted != null) {
        StatusLine(
            text = stringResource(R.string.result_recovery_required),
            tone = StatusTone.WARN
        )
    }
}

private const val LOG_LINES_SHOWN = 80

package com.reed.fcmguard.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.reed.fcmguard.PackageList
import com.reed.fcmguard.R
import com.reed.fcmguard.SettingsGuard
import com.reed.fcmguard.data.DeviceStatus
import com.reed.fcmguard.execution.OperationRecord
import com.reed.fcmguard.execution.OperationState
import com.reed.fcmguard.execution.OutcomeExplanation
import com.reed.fcmguard.execution.OutcomeSeverity
import com.reed.fcmguard.execution.Snapshot
import com.reed.fcmguard.ui.components.InfoNote
import com.reed.fcmguard.ui.components.KeyValueRow
import com.reed.fcmguard.ui.components.PackageChips
import com.reed.fcmguard.ui.components.SectionCard
import com.reed.fcmguard.ui.components.StatusLine
import com.reed.fcmguard.ui.components.StatusPill
import com.reed.fcmguard.ui.components.StatusTone
import com.reed.fcmguard.ui.components.formatTime
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect

/**
 * Dashboard.
 *
 * Layout order answers the three questions a user actually has, in order: what is the
 * state right now, what can I do about it, and what happened last time. The compact
 * status strip is pinned while scrolling so the live state never disappears.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenWriteSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        launch {
            viewModel.events.collect { event ->
                when (event) {
                    HomeEvent.OpenWriteSettings -> onOpenWriteSettings()
                    HomeEvent.OpenNotificationSettings -> onOpenNotificationSettings()
                }
            }
        }
        launch { viewModel.messages.collect(onMessage) }
    }

    // Permission, notification access and the whitelist can all change outside the app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    val status = state.status

    if (state.loading && status == null) {
        BoxLoading(modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "hero") {
            HeroHeader(status)
        }

        stickyHeader(key = "status-strip") {
            CompactStatusStrip(state, status)
        }

        item(key = "status-detail") {
            StatusDetailCard(
                status = status,
                onOpenWriteSettings = onOpenWriteSettings
            )
        }

        item(key = "protection") {
            ProtectionCard(
                status = status,
                onProtectionChange = viewModel::setProtectionEnabled,
                onNotificationChange = viewModel::setPersistentNotification
            )
        }

        item(key = "actions") {
            QuickActionsCard(
                restoreAvailable = state.latestSnapshot != null,
                busy = state.busy,
                onRepair = { viewModel.repair() },
                onRestore = viewModel::requestRestore,
                onWake = viewModel::wakeFcm
            )
        }

        item(key = "whitelist") {
            WhitelistCard(status)
        }

        item(key = "recent") {
            RecentActivityCard(history = state.history, onOpenHistory = onOpenHistory)
        }

        item(key = "note") {
            InfoNote(
                text = stringResource(R.string.note_text),
                icon = Icons.Filled.Info
            )
        }
    }

    if (state.busy) {
        OperationProgressDialog(state.stage)
    }

    state.emptyWhitelistPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::dismissEmptyWhitelistPrompt,
            title = { Text(stringResource(R.string.empty_whitelist_title)) },
            text = {
                Text(stringResource(R.string.empty_whitelist_message, prompt.requiredItem))
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmEmptyWhitelist) {
                    Text(stringResource(R.string.empty_whitelist_confirm, prompt.requiredItem))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissEmptyWhitelistPrompt) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    state.restorePrompt?.let { snapshot ->
        RestoreConfirmDialog(
            snapshot = snapshot,
            onConfirm = { viewModel.confirmRestore(snapshot) },
            onDismiss = viewModel::dismissRestorePrompt
        )
    }

    state.recoveryPrompt?.let { pending ->
        RecoveryDialog(
            pending = pending,
            onRestore = viewModel::restoreFromRecovery,
            onDismiss = viewModel::dismissRecovery
        )
    }

    state.resultPrompt?.let { explanation ->
        OperationResultDialog(
            explanation = explanation,
            onDismiss = viewModel::dismissResultPrompt,
            onViewHistory = {
                viewModel.dismissResultPrompt()
                onOpenHistory()
            }
        )
    }
}

/**
 * Result of a failed, partial or unverifiable operation: the headline, the suggested
 * recovery action, and the raw detail the engine captured. A successful verified write
 * never reaches this dialog; it is reported with a snackbar.
 */
@Composable
private fun OperationResultDialog(
    explanation: OutcomeExplanation,
    onDismiss: () -> Unit,
    onViewHistory: () -> Unit
) {
    val context = LocalContext.current
    val tone = when (explanation.severity) {
        OutcomeSeverity.SUCCESS -> StatusTone.OK
        OutcomeSeverity.WARNING -> StatusTone.WARN
        OutcomeSeverity.ERROR -> StatusTone.ERROR
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.operation_result_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusLine(text = explanation.headline(context), tone = tone)
                explanation.suggestion?.let { suggestion ->
                    Text(
                        text = stringResource(suggestion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                explanation.technicalDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                    KeyValueRow(
                        label = stringResource(R.string.history_detail),
                        value = detail
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = onViewHistory) {
                Text(stringResource(R.string.view_all_history))
            }
        }
    )
}

@Composable
private fun BoxLoading(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HeroHeader(status: DeviceStatus?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall
            )
            Text(
                text = stringResource(R.string.subtitle),
                style = MaterialTheme.typography.bodyMedium
            )
            if (status != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.active_key, status.key),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Pinned status strip. It restates only what the live read returned, and it is the same
 * text the detail card uses, so the two cannot disagree.
 */
@Composable
private fun CompactStatusStrip(state: HomeUiState, status: DeviceStatus?) {
    val headline = statusHeadline(status)
    val tone = statusTone(status)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(text = headline, tone = tone)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (status == null) "" else summaryLine(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun StatusDetailCard(status: DeviceStatus?, onOpenWriteSettings: () -> Unit) {
    SectionCard(title = stringResource(R.string.status_title)) {
        if (status == null) {
            Text(
                text = stringResource(R.string.status_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@SectionCard
        }

        StatusLine(
            text = if (status.canWrite) {
                stringResource(R.string.status_granted)
            } else {
                stringResource(R.string.status_not_granted)
            },
            tone = if (status.canWrite) StatusTone.OK else StatusTone.ERROR
        )
        StatusLine(
            text = if (status.protectionEnabled) {
                stringResource(R.string.status_enabled)
            } else {
                stringResource(R.string.status_disabled)
            },
            tone = if (status.protectionEnabled) StatusTone.OK else StatusTone.WARN
        )
        StatusLine(
            text = if (status.requiredPresent) {
                stringResource(R.string.present_yes)
            } else {
                stringResource(R.string.present_no)
            },
            tone = if (status.requiredPresent) StatusTone.OK else StatusTone.ERROR
        )
        StatusLine(
            text = if (status.notificationVisible) {
                stringResource(R.string.notification_mode_foreground)
            } else {
                stringResource(R.string.notification_mode_quiet)
            },
            tone = if (status.notificationVisible) StatusTone.OK else StatusTone.WARN
        )

        if (!status.canWrite) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoNote(
                text = stringResource(R.string.permission_explanation),
                icon = Icons.Filled.Warning,
                tone = StatusTone.ERROR
            )
            FilledTonalButton(onClick = onOpenWriteSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.grant_modify))
            }
        }
    }
}

@Composable
private fun ProtectionCard(
    status: DeviceStatus?,
    onProtectionChange: (Boolean) -> Unit,
    onNotificationChange: (Boolean) -> Unit
) {
    SectionCard(title = stringResource(R.string.protection_title)) {
        ToggleRow(
            title = stringResource(R.string.automatic_protection),
            summary = stringResource(R.string.automatic_protection_summary),
            checked = status?.protectionEnabled == true,
            enabled = status != null,
            onCheckedChange = onProtectionChange
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ToggleRow(
            title = stringResource(R.string.persistent_notification),
            summary = stringResource(R.string.persistent_notification_summary),
            checked = status?.persistentNotification == true,
            enabled = status != null,
            onCheckedChange = onNotificationChange
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActionsCard(
    restoreAvailable: Boolean,
    busy: Boolean,
    onRepair: () -> Unit,
    onRestore: () -> Unit,
    onWake: () -> Unit
) {
    SectionCard(title = stringResource(R.string.actions_title)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = onRepair, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.repair_now))
            }
            OutlinedButton(onClick = onRestore, enabled = !busy) {
                Text(stringResource(R.string.restore_previous_value))
            }
            OutlinedButton(onClick = onWake, enabled = !busy) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.wake_fcm))
            }
        }
        if (!restoreAvailable) {
            Text(
                text = stringResource(R.string.restore_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(R.string.helper_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WhitelistCard(status: DeviceStatus?) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.current_value_title)) {
        PackageChips(
            whitelist = status?.whitelist,
            requiredItem = status?.requiredItem ?: SettingsGuard.getConfiguredRequiredItem(context),
            ownPackage = context.packageName
        )
        if (status != null) {
            KeyValueRow(label = stringResource(R.string.label_key), value = status.key)
            KeyValueRow(
                label = stringResource(R.string.label_item),
                value = status.requiredItem
            )
        }
    }
}

@Composable
private fun RecentActivityCard(history: List<OperationRecord>, onOpenHistory: () -> Unit) {
    SectionCard(
        title = stringResource(R.string.recent_activity_title),
        subtitle = if (history.isEmpty()) stringResource(R.string.recent_activity_empty) else null
    ) {
        for (record in history) {
            OperationRow(record)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        TextButton(onClick = onOpenHistory) {
            Text(stringResource(R.string.view_all_history))
        }
    }
}

@Composable
fun OperationRow(record: OperationRecord, showDetail: Boolean = false) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(text = operationStateLabel(record.state), tone = operationStateTone(record.state))
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatTime(record.updatedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = record.key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showDetail) {
            KeyValueRow(label = stringResource(R.string.history_operation_id), value = record.id)
            record.snapshotId?.let {
                KeyValueRow(label = stringResource(R.string.history_snapshot), value = it)
            }
            record.detail?.let {
                KeyValueRow(label = stringResource(R.string.history_detail), value = it)
            }
            KeyValueRow(
                label = stringResource(R.string.history_started),
                value = formatTime(record.startedAt)
            )
        }
    }
}

@Composable
private fun RestoreConfirmDialog(snapshot: Snapshot, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_dialog_title)) },
        text = {
            Text(
                stringResource(
                    R.string.restore_dialog_message,
                    snapshot.key,
                    formatTime(snapshot.createdAt),
                    PackageList.parse(snapshot.value).size
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

@Composable
private fun RecoveryDialog(
    pending: OperationRecord,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    val hasSnapshot = !pending.snapshotId.isNullOrEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recovery_title)) },
        text = {
            Text(
                stringResource(
                    R.string.recovery_message,
                    formatTime(pending.startedAt),
                    pending.state.name,
                    pending.key
                )
            )
        },
        confirmButton = {
            if (hasSnapshot) {
                TextButton(onClick = onRestore) {
                    Text(stringResource(R.string.recovery_restore))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.recovery_dismiss)) }
        }
    )
}

@Composable
private fun OperationProgressDialog(stage: OperationState?) {
    Dialog(onDismissRequest = { }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.operation_running),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stageLabel(stage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Stage text is derived from the journaled state the engine wrote. A null stage means the
 * record has not been observed yet, so the generic "working" line is shown rather than an
 * invented percentage.
 */
@Composable
fun stageLabel(stage: OperationState?): String = when (stage) {
    OperationState.VALIDATING -> stringResource(R.string.stage_checking)
    OperationState.SNAPSHOTTING -> stringResource(R.string.stage_backing_up)
    OperationState.WRITING -> stringResource(R.string.stage_applying)
    OperationState.VERIFYING -> stringResource(R.string.stage_verifying)
    OperationState.ROLLING_BACK -> stringResource(R.string.stage_restoring)
    else -> stringResource(R.string.stage_working)
}

@Composable
fun operationStateLabel(state: OperationState): String = when (state) {
    OperationState.COMPLETED -> stringResource(R.string.result_state_completed)
    OperationState.PARTIALLY_COMPLETED -> stringResource(R.string.result_state_partially_completed)
    OperationState.FAILED -> stringResource(R.string.result_state_failed)
    OperationState.CANCELLED -> stringResource(R.string.result_state_cancelled)
    OperationState.INTERRUPTED -> stringResource(R.string.result_state_interrupted)
    else -> stringResource(R.string.result_state_running)
}

@Composable
fun operationStateTone(state: OperationState): StatusTone = when (state) {
    OperationState.COMPLETED -> StatusTone.OK
    OperationState.PARTIALLY_COMPLETED -> StatusTone.WARN
    OperationState.INTERRUPTED -> StatusTone.WARN
    OperationState.FAILED -> StatusTone.ERROR
    OperationState.CANCELLED -> StatusTone.NEUTRAL
    else -> StatusTone.NEUTRAL
}

@Composable
fun statusHeadline(status: DeviceStatus?): String = when {
    status == null -> stringResource(R.string.status_unavailable)
    status.protectionEnabled && status.canWrite && status.requiredPresent ->
        stringResource(R.string.status_protected)
    status.canWrite && status.requiredPresent -> stringResource(R.string.status_ready)
    else -> stringResource(R.string.status_attention)
}

@Composable
fun statusTone(status: DeviceStatus?): StatusTone = when {
    status == null -> StatusTone.NEUTRAL
    status.canWrite && status.requiredPresent -> StatusTone.OK
    else -> StatusTone.ERROR
}

@Composable
private fun summaryLine(status: DeviceStatus): String {
    val list = PackageList.parse(status.whitelist)
    return if (list.isEmpty()) {
        stringResource(R.string.no_packages_listed)
    } else {
        stringResource(R.string.package_count, list.size)
    }
}

